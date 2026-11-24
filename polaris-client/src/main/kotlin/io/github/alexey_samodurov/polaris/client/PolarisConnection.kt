package io.github.alexey_samodurov.polaris.client

import java.net.ProxySelector
import java.net.URI
import java.time.Duration
import javax.net.ssl.SSLContext

/** Default OAuth2 scope: every principal role the principal holds. */
const val DEFAULT_SCOPE: String = "PRINCIPAL_ROLE:ALL"

/**
 * Where a Polaris server is and how to authenticate against it.
 *
 * The secret is read through [secret] on every token request, so the caller decides where it
 * lives and the connection never holds it longer than one request. [sslContext] and
 * [proxySelector] are supplied by the caller so that an IDE can pass its own certificate store
 * and proxy settings; `null` means the JDK defaults.
 */
class PolarisConnection(
    baseUrl: String,
    val clientId: String,
    val secret: () -> String,
    val realm: String? = null,
    val scope: String = DEFAULT_SCOPE,
    val sslContext: SSLContext? = null,
    val proxySelector: ProxySelector? = null,
    /** Answers proxy logins (407); an IDE passes its own, which knows the proxy credentials. */
    val authenticator: java.net.Authenticator? = null,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val maxConcurrentRequests: Int = 4,
) {
    /** The server root, with no trailing slash and no API path. */
    val baseUri: URI = normalizeBaseUrl(baseUrl)

    init {
        require(clientId.isNotBlank()) { "client id is empty" }
        require(scope.isNotBlank()) { "scope is empty" }
        require(maxConcurrentRequests >= 1) { "maxConcurrentRequests must be at least 1" }
    }

    override fun toString(): String = "PolarisConnection(baseUri=$baseUri, realm=$realm, clientId=$clientId)"
}

private val API_SUFFIXES = listOf("/api/catalog/v1", "/api/catalog", "/api/management/v1", "/api/management")

/**
 * Reduces what a user pastes to the server root.
 *
 * `https://host/`, `https://host/api/catalog` and `https://host/api/management/v1/` all become
 * `https://host`; a path prefix in front of `/api` (a reverse proxy) is kept.
 */
fun normalizeBaseUrl(raw: String): URI {
    var url = raw.trim()
    require(url.isNotEmpty()) { "base URL is empty" }
    url = url.trimEnd('/')
    for (suffix in API_SUFFIXES) {
        if (url.endsWith(suffix, ignoreCase = true)) {
            url = url.dropLast(suffix.length).trimEnd('/')
            break
        }
    }
    val uri = try {
        URI(url)
    } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("base URL is not a valid URI: ${e.reason}")
    }
    require(uri.scheme?.lowercase() in setOf("http", "https")) { "base URL must start with http:// or https://" }
    require(!uri.host.isNullOrEmpty()) { "base URL has no host" }
    require(uri.rawQuery == null && uri.rawFragment == null) { "base URL must not carry a query or a fragment" }
    return uri
}
