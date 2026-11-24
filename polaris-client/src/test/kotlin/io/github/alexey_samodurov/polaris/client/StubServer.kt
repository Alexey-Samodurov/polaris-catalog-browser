package io.github.alexey_samodurov.polaris.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A request the stub received. */
data class Recorded(val method: String, val pathAndQuery: String, val headers: Map<String, String>, val body: String)

/** What the stub answers. */
data class Reply(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/**
 * A local HTTP server answering with [handler]. Paths are matched by the caller on the raw path
 * and query, exactly as sent, so encoding is visible to the tests.
 */
class StubServer(private val handler: (Recorded) -> Reply) : AutoCloseable {
    val requests: MutableList<Recorded> = CopyOnWriteArrayList()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/") { exchange ->
            val uri = exchange.requestURI
            val recorded = Recorded(
                method = exchange.requestMethod,
                pathAndQuery = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: ""),
                headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() },
                body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
            )
            requests += recorded
            val reply = try {
                handler(recorded)
            } catch (e: Throwable) {
                Reply(500, """{"error":{"message":"stub failed: ${e.message}","type":"Stub","code":500}}""")
            }
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun dataRequests(): List<Recorded> = requests.filterNot { it.pathAndQuery.endsWith("/oauth/tokens") }

    override fun close() {
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }
}

const val TEST_SECRET = "very-hidden-value-42"

fun tokenReply(token: String = "t1", expiresIn: Long = 3600): Reply =
    Reply(200, """{"access_token":"$token","token_type":"bearer","expires_in":$expiresIn}""")

fun configReply(prefix: String = "cat", endpoints: List<String>? = null): Reply {
    val ep = endpoints?.joinToString(",", "[", "]") { "\"$it\"" }?.let { ""","endpoints":$it""" } ?: ""
    return Reply(200, """{"defaults":{},"overrides":{"prefix":"$prefix"}$ep}""")
}
