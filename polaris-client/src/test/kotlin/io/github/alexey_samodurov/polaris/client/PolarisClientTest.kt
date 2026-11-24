package io.github.alexey_samodurov.polaris.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class PolarisClientTest {
    private fun client(server: StubServer, clock: Clock = Clock.systemUTC(), realm: String? = null) = PolarisClient(
        PolarisConnection(server.baseUrl + "/api/catalog/", clientId = "reader", secret = { TEST_SECRET }, realm = realm),
        clock,
    )

    private val namespacesBody = """{"namespaces":[["a"],["b"]]}"""

    @Test
    fun `a token is requested once and reused`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply()
                else -> Reply(200, namespacesBody)
            }
        }.use { server ->
            val c = client(server).catalog("cat")
            c.listNamespaces()
            c.listNamespaces()
            assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/oauth/tokens") })
            val tokenRequest = server.requests.first { it.pathAndQuery.endsWith("/oauth/tokens") }
            assertTrue(tokenRequest.body.contains("grant_type=client_credentials"))
            assertTrue(tokenRequest.body.contains("scope=PRINCIPAL_ROLE%3AALL"))
            assertTrue(server.dataRequests().all { it.headers["authorization"] == "Bearer t1" })
        }
    }

    @Test
    fun `concurrent callers share one token request`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> {
                    Thread.sleep(300)
                    tokenReply()
                }
                r.pathAndQuery.contains("/config") -> configReply()
                else -> Reply(200, namespacesBody)
            }
        }.use { server ->
            val client = client(server)
            (1..20).map { async(kotlinx.coroutines.Dispatchers.IO) { client.catalog("cat").listNamespaces() } }.awaitAll()
            assertEquals(1, client.transport.tokenRequests)
        }
    }

    @Test
    fun `a token is refreshed before it expires`() = runBlocking {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val issued = AtomicInteger()
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply("t${issued.incrementAndGet()}", expiresIn = 100)
                r.pathAndQuery.contains("/config") -> configReply()
                else -> Reply(200, namespacesBody)
            }
        }.use { server ->
            val c = client(server, clock).catalog("cat")
            c.listNamespaces()
            now = now.plusSeconds(79)
            c.listNamespaces()
            assertEquals(1, issued.get())
            now = now.plusSeconds(2)
            c.listNamespaces()
            assertEquals(2, issued.get())
            assertEquals("Bearer t2", server.dataRequests().last().headers["authorization"])
        }
    }

    @Test
    fun `a rejected token is replaced and the call retried once`() = runBlocking {
        val issued = AtomicInteger()
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply("t${issued.incrementAndGet()}")
                r.headers["authorization"] == "Bearer t1" -> Reply(401, """{"error":{"message":"expired","type":"NotAuthorizedException","code":401}}""")
                r.pathAndQuery.contains("/config") -> configReply()
                else -> Reply(200, namespacesBody)
            }
        }.use { server ->
            val result = client(server).catalog("cat").listNamespaces()
            assertEquals(listOf(Namespace.of("a"), Namespace.of("b")), result)
            assertEquals(2, issued.get())
        }
    }

    @Test
    fun `a refused token request is unauthorized and does not leak the secret`() = runBlocking {
        StubServer { r ->
            if (r.pathAndQuery.endsWith("/oauth/tokens")) {
                Reply(401, """{"error":"invalid_client","error_description":"bad credentials"}""")
            } else {
                Reply(500)
            }
        }.use { server ->
            val e = assertThrows<PolarisException.Unauthorized> { runBlocking { client(server).testConnection() } }
            assertTrue(e.message!!.contains("invalid_client"))
            assertFalse(e.message!!.contains(TEST_SECRET))
            assertFalse(PolarisConnection(server.baseUrl, "reader", { TEST_SECRET }).toString().contains(TEST_SECRET))
        }
    }

    @Test
    fun `the realm header is sent on every request`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply()
                else -> Reply(200, namespacesBody)
            }
        }.use { server ->
            client(server, realm = "test-realm").catalog("cat").listNamespaces()
            assertTrue(server.requests.all { it.headers["polaris-realm"] == "test-realm" })
        }
    }

    @Test
    fun `lists follow page tokens and accept an unpaged answer`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply()
                r.pathAndQuery.endsWith("/tables") ->
                    Reply(200, """{"identifiers":[{"namespace":["a"],"name":"t2"}],"next-page-token":"p 2"}""")
                r.pathAndQuery.endsWith("/tables?pageToken=p%202") ->
                    Reply(200, """{"identifiers":[{"namespace":["a"],"name":"t1"}],"next-page-token":null}""")
                else -> Reply(200, namespacesBody)
            }
        }.use { server ->
            val c = client(server).catalog("cat")
            assertEquals(listOf("t1", "t2"), c.listTables(Namespace.of("a")).map { it.name })
            assertEquals(2, c.listNamespaces().size)
        }
    }

    @Test
    fun `a repeated page token stops the loop`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply()
                else -> Reply(200, """{"identifiers":[],"next-page-token":"same"}""")
            }
        }.use { server ->
            assertThrows<PolarisException.BadResponse> { runBlocking { client(server).catalog("cat").listTables(Namespace.of("a")) } }
            Unit
        }
    }

    @Test
    fun `nested namespaces and odd names are encoded in the path`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                // Servers hand the prefix out already fit for a path.
                r.pathAndQuery.contains("/config") -> configReply(prefix = "my%20cat")
                r.pathAndQuery.contains("/tables/") -> Reply(200, """{"metadata":{"format-version":2,"schemas":[]}}""")
                r.pathAndQuery.endsWith("/tables") -> Reply(200, """{"identifiers":[]}""")
                else -> Reply(200, """{"namespaces":[["a","b"]]}""")
            }
        }.use { server ->
            val c = client(server).catalog("my cat")
            assertEquals(listOf(Namespace.of("a", "b")), c.listNamespaces(Namespace.of("a")))
            c.listTables(Namespace.of("a", "b"))
            c.loadTable(TableIdentifier(Namespace.of("a", "b"), "x/y z"))
            val paths = server.dataRequests().map { it.pathAndQuery }
            assertTrue(paths.contains("/api/catalog/v1/config?warehouse=my%20cat"), paths.toString())
            assertTrue(paths.contains("/api/catalog/v1/my%20cat/namespaces?parent=a"), paths.toString())
            assertTrue(paths.contains("/api/catalog/v1/my%20cat/namespaces/a%1Fb/tables"), paths.toString())
            assertTrue(paths.contains("/api/catalog/v1/my%20cat/namespaces/a%1Fb/tables/x%2Fy%20z?snapshots=refs"), paths.toString())
        }
    }

    @Test
    fun `views are not requested when the server does not list them`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply(endpoints = listOf(Endpoints.LIST_NAMESPACES, Endpoints.LIST_TABLES))
                else -> Reply(500)
            }
        }.use { server ->
            assertEquals(emptyList<TableIdentifier>(), client(server).catalog("cat").listViews(Namespace.of("a")))
            assertTrue(server.dataRequests().none { it.pathAndQuery.contains("/views") })
        }
    }

    @Test
    fun `views forbidden by the server are an empty list`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply(endpoints = listOf(Endpoints.LIST_VIEWS))
                else -> Reply(403, """{"error":{"message":"no","type":"ForbiddenException","code":403}}""")
            }
        }.use { server ->
            assertEquals(emptyList<TableIdentifier>(), client(server).catalog("cat").listViews(Namespace.of("a")))
        }
    }

    @Test
    fun `a table is revalidated with its etag and kept on 304`() = runBlocking {
        StubServer { r ->
            when {
                r.pathAndQuery.endsWith("/oauth/tokens") -> tokenReply()
                r.pathAndQuery.contains("/config") -> configReply()
                r.headers["if-none-match"] == "\"v1\"" -> Reply(304)
                else -> Reply(
                    200,
                    """{"metadata-location":"s3://b/m.json","metadata":{"format-version":3,"current-schema-id":0,
                       "schemas":[{"schema-id":0,"type":"struct","fields":[]}]},
                       "config":{"s3.secret-access-key":"$TEST_SECRET"}}""",
                    mapOf("ETag" to "\"v1\""),
                )
            }
        }.use { server ->
            val c = client(server).catalog("cat")
            val id = TableIdentifier(Namespace.of("a"), "t")
            val first = c.loadTable(id)
            assertSame(first, c.loadTable(id))
            assertEquals(1, server.dataRequests().count { it.pathAndQuery.contains("/tables/") })
            assertSame(first, c.loadTable(id, revalidate = true))
            assertEquals(2, server.dataRequests().count { it.pathAndQuery.contains("/tables/") })
            assertEquals("s3://b/m.json", first.metadataLocation)
            assertFalse(first.metadata.raw.toString().contains(TEST_SECRET))
        }
    }

    @Test
    fun `listing catalogs without the privilege is forbidden`() = runBlocking {
        StubServer { r ->
            if (r.pathAndQuery.endsWith("/oauth/tokens")) tokenReply()
            else Reply(403, """{"error":{"message":"Principal 'reader' is not authorized for op LIST_CATALOGS","type":"ForbiddenException","code":403}}""")
        }.use { server ->
            val e = assertThrows<PolarisException.Forbidden> { runBlocking { client(server).listCatalogs() } }
            assertTrue(e.message!!.contains("LIST_CATALOGS"))
        }
    }

    @Test
    fun `catalogs are listed with their storage type`() = runBlocking {
        StubServer { r ->
            if (r.pathAndQuery.endsWith("/oauth/tokens")) tokenReply()
            else Reply(
                200,
                """{"catalogs":[{"type":"INTERNAL","name":"lakehouse","properties":{"default-base-location":"s3://x"},
                    "storageConfigInfo":{"storageType":"S3"}},{"type":"INTERNAL","name":"bronze","properties":{}}]}""",
            )
        }.use { server ->
            val catalogs = client(server).listCatalogs()
            assertEquals(listOf("bronze", "lakehouse"), catalogs.map { it.name })
            assertEquals("S3", catalogs[1].storageType)
            assertNull(catalogs[0].storageType)
        }
    }

    @Test
    fun `an unreachable server is a transport error`() {
        val c = PolarisClient(PolarisConnection("http://127.0.0.1:1", "reader", { TEST_SECRET }))
        assertThrows<PolarisException.Transport> { runBlocking { c.testConnection() } }
    }
}
