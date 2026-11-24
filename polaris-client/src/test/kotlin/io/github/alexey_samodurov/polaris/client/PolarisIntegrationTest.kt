package io.github.alexey_samodurov.polaris.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The client against a real Polaris 1.4.1. The fixture writes through the REST APIs as the
 * bootstrap `root` principal; the client itself only reads.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolarisIntegrationTest {
    private lateinit var polaris: GenericContainer<*>
    private lateinit var base: String
    private lateinit var reader: Pair<String, String>
    private val http = HttpClient.newHttpClient()

    private val catalog = "it"
    private val nsA = Namespace.of("a")
    private val nsAB = Namespace.of("a", "b")
    private val table = TableIdentifier(nsAB, "events")

    @BeforeAll
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available: integration tests skipped")
        polaris = GenericContainer(DockerImageName.parse("apache/polaris:1.4.1"))
            .withExposedPorts(8181, 8182)
            .withEnv("POLARIS_BOOTSTRAP_CREDENTIALS", "POLARIS,root,s3cr3t")
            .withEnv("polaris.features.\"ALLOW_INSECURE_STORAGE_TYPES\"", "true")
            .withEnv("polaris.features.\"SUPPORTED_CATALOG_STORAGE_TYPES\"", "[\"FILE\"]")
            .withEnv("polaris.readiness.ignore-severe-issues", "true")
            .waitingFor(Wait.forHttp("/q/health").forPort(8182).withStartupTimeout(Duration.ofMinutes(3)))
        polaris.start()
        base = "http://${polaris.host}:${polaris.getMappedPort(8181)}"
        setUp()
    }

    @AfterAll
    fun stop() {
        if (::polaris.isInitialized) polaris.stop()
    }

    private fun client(id: String = "root", secret: String = "s3cr3t") =
        PolarisClient(PolarisConnection(base, id, { secret }, realm = "POLARIS"))

    // --- fixture: writes as root through raw HTTP ---------------------------------------------

    private val rootToken by lazy { token("root", "s3cr3t") }

    private fun token(id: String, secret: String): String {
        val form = "grant_type=client_credentials&client_id=${encodeSegment(id)}&client_secret=${encodeSegment(secret)}&scope=PRINCIPAL_ROLE%3AALL"
        val r = http.send(
            HttpRequest.newBuilder(URI("$base/api/catalog/v1/oauth/tokens"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Polaris-Realm", "POLARIS")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(r.statusCode() == 200) { "token: ${r.statusCode()} ${r.body()}" }
        return json.parseToJsonElement(r.body()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    private fun write(method: String, path: String, body: String): JsonObject? {
        val r = http.send(
            HttpRequest.newBuilder(URI(base + path))
                .header("Authorization", "Bearer $rootToken")
                .header("Polaris-Realm", "POLARIS")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(r.statusCode() in 200..299) { "$method $path: ${r.statusCode()} ${r.body()}" }
        return r.body().takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) as? JsonObject }
    }

    private fun setUp() {
        val mgmt = "/api/management/v1"
        write(
            "POST", "$mgmt/catalogs",
            """{"catalog":{"name":"$catalog","type":"INTERNAL",
               "properties":{"default-base-location":"file:///tmp/polaris/$catalog"},
               "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/polaris/"]}}}""",
        )
        val rest = "/api/catalog/v1/$catalog"
        write("POST", "$rest/namespaces", """{"namespace":["a"]}""")
        write("POST", "$rest/namespaces", """{"namespace":["a","b"]}""")
        write(
            "POST", "$rest/namespaces/${encodeNamespace(nsAB)}/tables",
            """{"name":"events",
               "schema":{"type":"struct","schema-id":0,"identifier-field-ids":[1],"fields":[
                 {"id":1,"name":"id","required":true,"type":"long"},
                 {"id":2,"name":"ts","required":true,"type":"timestamp"},
                 {"id":3,"name":"tags","required":false,"type":{"type":"list","element-id":4,"element":"string","element-required":false}}]},
               "partition-spec":{"spec-id":0,"fields":[{"source-id":2,"field-id":1000,"name":"ts_day","transform":"day"}]},
               "write-order":{"order-id":1,"fields":[{"transform":"identity","source-id":1,"direction":"asc","null-order":"nulls-first"}]},
               "properties":{"format-version":"3","owner":"it"}}""",
        )
        val tablePath = "$rest/namespaces/${encodeNamespace(nsAB)}/tables/events"
        for ((id, parent) in listOf(1L to null, 2L to 1L)) {
            val parentField = parent?.let { ""","parent-snapshot-id":$it""" } ?: ""
            write(
                "POST", tablePath,
                """{"requirements":[],"updates":[
                   {"action":"add-snapshot","snapshot":{"snapshot-id":$id$parentField,"sequence-number":$id,
                     "timestamp-ms":${System.currentTimeMillis() + id * 1000},"schema-id":0,"first-row-id":0,"added-rows":0,
                     "manifest-list":"file:///tmp/polaris/$catalog/a/b/events/metadata/snap-$id.avro",
                     "summary":{"operation":"append"}}},
                   {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":$id}]}""",
            )
        }
        write(
            "POST", "$rest/namespaces/a/views",
            """{"name":"recent","schema":{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":false,"type":"long"}]},
               "view-version":{"version-id":1,"timestamp-ms":1700000000000,"schema-id":0,"summary":{"engine-name":"spark"},
                 "representations":[{"type":"sql","sql":"select id from a.b.events","dialect":"spark"}],"default-namespace":["a"]},
               "properties":{}}""",
        )

        // A principal that reads the catalog but may not list catalogs.
        val created = write("POST", "$mgmt/principals", """{"principal":{"name":"reader"}}""")!!
        val credentials = created["credentials"]!!.jsonObject
        reader = credentials["clientId"]!!.jsonPrimitive.content to credentials["clientSecret"]!!.jsonPrimitive.content
        write("POST", "$mgmt/principal-roles", """{"principalRole":{"name":"reader_role"}}""")
        write("PUT", "$mgmt/principals/reader/principal-roles", """{"principalRole":{"name":"reader_role"}}""")
        write("POST", "$mgmt/catalogs/$catalog/catalog-roles", """{"catalogRole":{"name":"reader_cat"}}""")
        for (privilege in listOf("CATALOG_READ_PROPERTIES", "NAMESPACE_LIST", "NAMESPACE_READ_PROPERTIES", "TABLE_LIST", "TABLE_READ_PROPERTIES", "VIEW_LIST", "VIEW_READ_PROPERTIES")) {
            write("PUT", "$mgmt/catalogs/$catalog/catalog-roles/reader_cat/grants", """{"grant":{"type":"catalog","privilege":"$privilege"}}""")
        }
        write("PUT", "$mgmt/principal-roles/reader_role/catalog-roles/$catalog", """{"catalogRole":{"name":"reader_cat"}}""")
    }

    // --- tests: reads through the client ------------------------------------------------------

    @Test
    fun `root lists catalogs`() = runBlocking {
        assertTrue(client().listCatalogs().any { it.name == catalog && it.storageType == "FILE" })
    }

    @Test
    fun `a principal without the privilege is forbidden to list catalogs but browses a named one`() = runBlocking {
        val c = client(reader.first, reader.second)
        assertThrows<PolarisException.Forbidden> { runBlocking { c.listCatalogs() } }
        assertEquals(listOf(nsA), c.catalog(catalog).listNamespaces())
        assertEquals(listOf(table), c.catalog(catalog).listTables(nsAB))
    }

    @Test
    fun `nested namespaces are listed level by level`() = runBlocking {
        val session = client().catalog(catalog)
        assertEquals(listOf(nsA), session.listNamespaces())
        assertEquals(listOf(nsAB), session.listNamespaces(nsA))
        assertEquals(emptyList<Namespace>(), session.listNamespaces(nsAB))
        assertEquals(listOf(table), session.listTables(nsAB))
        assertEquals(emptyList<TableIdentifier>(), session.listTables(nsA))
    }

    @Test
    fun `table metadata matches what was created`() = runBlocking {
        val loaded = client().catalog(catalog).loadTable(table, SnapshotMode.ALL)
        val m = loaded.metadata
        assertEquals(3, m.formatVersion)
        assertNotNull(loaded.metadataLocation)
        assertEquals(listOf(1, 2, 3), m.currentSchema!!.fields.map { it.id })
        assertEquals(listOf(1), m.currentSchema!!.identifierFieldIds)
        assertEquals("tags.element", m.currentSchema!!.fieldName(4))
        val spec = m.defaultSpec!!.fields.single()
        assertEquals("day", spec.transform)
        assertEquals("ts", m.fieldName(spec.sourceIds.single()))
        assertEquals("nulls-first", m.defaultSortOrder!!.fields.single().nullOrder)
        assertEquals(2L, m.currentSnapshotId)
        assertEquals(listOf(2L, 1L), m.snapshotsNewestFirst.map { it.snapshotId })
        assertEquals("it", m.properties.valueOrNull()!!["owner"])
    }

    @Test
    fun `refs mode returns only referenced snapshots`() = runBlocking {
        val refs = client().catalog(catalog).loadTable(table, SnapshotMode.REFS).metadata
        val ids = refs.snapshots.valueOrNull()!!.map { it.snapshotId }
        // Polaris 1.4.1 honours snapshots=refs; if a later version ignores it the client still
        // works, and this assertion is the place that will say so.
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `a second load with the etag is answered from the cache`() = runBlocking {
        val session = client().catalog(catalog)
        val first = session.loadTable(table)
        val second = session.loadTable(table, revalidate = true)
        if (first.etag != null) {
            assertTrue(first === second, "expected 304 to keep the cached table")
        } else {
            println("Polaris returned no ETag; revalidation reloads the table")
        }
    }

    @Test
    fun `the view loads with its sql`() = runBlocking {
        val session = client().catalog(catalog)
        val views = session.listViews(nsA)
        assertEquals(listOf(TableIdentifier(nsA, "recent")), views)
        val view = session.loadView(views.single()).metadata
        assertEquals("select id from a.b.events", view.currentVersion!!.representations.single().sql)
    }

    @Test
    fun `a missing table is not found`() {
        assertThrows<PolarisException.NotFound> {
            runBlocking { client().catalog(catalog).loadTable(TableIdentifier(nsAB, "nope")) }
        }
    }
}
