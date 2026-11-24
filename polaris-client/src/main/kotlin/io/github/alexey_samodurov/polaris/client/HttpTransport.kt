package io.github.alexey_samodurov.polaris.client

import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Instant

/** A response the client keeps: status, the headers it reads, and the body. */
internal class Response(val status: Int, val body: String, private val headers: java.net.http.HttpHeaders) {
    fun header(name: String): String? = headers.firstValue(name).orElse(null)
}

/** A token and the instant after which it is no longer used. */
internal class AccessToken(val value: String, val refreshAfter: Instant?) {
    override fun toString(): String = "AccessToken(refreshAfter=$refreshAfter)"
}

/**
 * Sends requests to one Polaris server: authenticates them, caps how many are in flight, and
 * retries a request once when its token is rejected.
 */
internal class HttpTransport(
    private val connection: PolarisConnection,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connection.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .apply { connection.sslContext?.let(::sslContext) }
        .apply { connection.proxySelector?.let(::proxy) }
        .apply { connection.authenticator?.let(::authenticator) }
        .build()

    private val permits = Semaphore(connection.maxConcurrentRequests)
    private val tokenLock = Mutex()

    @Volatile
    private var token: AccessToken? = null

    /** Counts token requests; tests use it to prove concurrent callers share one. */
    @Volatile
    var tokenRequests: Int = 0
        private set

    fun uri(path: String): URI = URI.create(connection.baseUri.toString() + path)

    /**
     * Sends an authenticated GET to [path] (already encoded, starting with `/api/`).
     *
     * Non-success statuses become [PolarisException]s, except those listed in [passStatuses], which
     * are returned for the caller to handle (304, for instance).
     */
    suspend fun get(path: String, headers: Map<String, String> = emptyMap(), passStatuses: Set<Int> = emptySet()): Response {
        var current = currentToken(rejected = null)
        var response = send(authorized(path, current, headers))
        if (response.status == 401) {
            current = currentToken(rejected = current)
            response = send(authorized(path, current, headers))
        }
        if (response.status in 200..299 || response.status in passStatuses) return response
        throw errorFor("GET", path, response)
    }

    /** Fetches a token now, whatever is cached. */
    suspend fun authenticate() {
        tokenLock.withLock { token = fetchToken() }
    }

    private fun authorized(path: String, token: AccessToken, headers: Map<String, String>): HttpRequest {
        val builder = HttpRequest.newBuilder(uri(path))
            .timeout(connection.requestTimeout)
            .header("Authorization", "Bearer ${token.value}")
            .header("Accept", "application/json")
            .GET()
        connection.realm?.let { builder.header(REALM_HEADER, it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    /**
     * The token to use. One caller fetches a new token while the others wait for it; a caller whose
     * token was just rejected gets a fresh one unless another caller has already replaced it.
     */
    private suspend fun currentToken(rejected: AccessToken?): AccessToken = tokenLock.withLock {
        val cached = token
        val usable = cached != null && cached !== rejected &&
            (cached.refreshAfter == null || clock.instant().isBefore(cached.refreshAfter))
        if (usable) return cached
        fetchToken().also { token = it }
    }

    private suspend fun fetchToken(): AccessToken {
        tokenRequests++
        val path = "/api/catalog/v1/oauth/tokens"
        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to connection.clientId,
            "client_secret" to connection.secret(),
            "scope" to connection.scope,
        ).joinToString("&") { (k, v) -> "${encodeSegment(k)}=${encodeSegment(v)}" }
        val builder = HttpRequest.newBuilder(uri(path))
            .timeout(connection.requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
        connection.realm?.let { builder.header(REALM_HEADER, it) }
        val response = send(builder.build())
        if (response.status !in 200..299) {
            val reason = oauthError(response.body) ?: "status ${response.status}"
            if (response.status in setOf(400, 401, 403)) {
                throw PolarisException.Unauthorized("token request refused: $reason")
            }
            throw PolarisException.Server(response.status, "token request failed: $reason")
        }
        val body = parseObject("POST", path, response.body)
        val value = body.str("access_token") ?: throw PolarisException.BadResponse("token response has no access_token")
        val expiresIn = body.long("expires_in")
        // Refresh a fifth of the lifetime early, but at most a minute early.
        val refreshAfter = expiresIn?.takeIf { it > 0 }?.let {
            val early = minOf(it / 5, 60L)
            clock.instant().plusSeconds(it - early)
        }
        return AccessToken(value, refreshAfter)
    }

    private suspend fun send(request: HttpRequest): Response = permits.withPermit {
        val target = "${request.method()} ${request.uri().rawPath}"
        try {
            val r = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            Response(r.statusCode(), r.body() ?: "", r.headers())
        } catch (e: IOException) {
            throw transportError(target, e)
        }
    }

    private fun transportError(target: String, cause: Throwable): PolarisException = when (cause) {
        is HttpConnectTimeoutException -> PolarisException.Transport("$target: connection timed out", cause)
        is HttpTimeoutException -> PolarisException.Transport("$target: no answer in ${connection.requestTimeout.seconds} s", cause)
        is javax.net.ssl.SSLException -> PolarisException.Transport("$target: TLS failed: ${cause.message}", cause)
        else -> PolarisException.Transport("$target: ${cause.message ?: cause.javaClass.simpleName}", cause)
    }

    companion object {
        const val REALM_HEADER: String = "Polaris-Realm"

        fun parseObject(method: String, path: String, body: String): JsonObject = try {
            json.parseToJsonElement(body) as? JsonObject ?: throw PolarisException.BadResponse("$method $path: body is not a JSON object")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PolarisException.BadResponse("$method $path: body is not JSON", e)
        }

        fun errorFor(method: String, path: String, response: Response): PolarisException {
            val detail = icebergError(response.body)?.let { ": $it" } ?: ""
            val message = "$method $path: ${response.status}$detail"
            return when (response.status) {
                401 -> PolarisException.Unauthorized(message)
                403 -> PolarisException.Forbidden(message)
                404 -> PolarisException.NotFound(message)
                else -> PolarisException.Server(response.status, message)
            }
        }

        /** The message of an Iceberg `ErrorModel`, or `null` when the body is not one. */
        private fun icebergError(body: String): String? = runCatching {
            val error = json.parseToJsonElement(body) as? JsonObject ?: return null
            val inner = error["error"] as? JsonObject ?: return null
            listOfNotNull(inner.str("type"), inner.str("message")).joinToString(": ").ifEmpty { null }
        }.getOrNull()

        /** `error` and `error_description` of an OAuth2 error response. */
        private fun oauthError(body: String): String? = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
            val error = (obj["error"] as? JsonPrimitive)?.content ?: return icebergError(body)
            listOfNotNull(error, obj.str("error_description")).joinToString(": ")
        }.getOrNull()
    }
}
