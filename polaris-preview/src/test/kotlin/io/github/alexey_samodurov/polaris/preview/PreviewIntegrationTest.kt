package io.github.alexey_samodurov.polaris.preview

import io.github.alexey_samodurov.polaris.client.Namespace
import io.github.alexey_samodurov.polaris.client.TableIdentifier
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * The preview against Polaris 1.4.1 and SeaweedFS 4.28 with STS off, as every catalog runs today:
 * Polaris vends no credentials and the reader brings its own S3 keys. The table is v3, and its rows
 * were deleted and updated through deletion vectors.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewIntegrationTest {
    private val network = Network.newNetwork()
    private lateinit var seaweed: GenericContainer<*>
    private lateinit var s3Endpoint: String
    private lateinit var polaris: GenericContainer<*>
    private lateinit var base: String
    private lateinit var reader: Pair<String, String>

    private val catalog = "it"
    private val id = TableIdentifier(Namespace.of("cdc"), "events")
    private var firstSnapshot = 0L
    private lateinit var fixture: PreviewFixture


    @BeforeAll
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available: integration tests skipped")
        seaweed = GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:4.28"))
            .withNetwork(network).withNetworkAliases("seaweedfs")
            .withCopyToContainer(Transferable.of(S3_IDENTITIES), "/etc/seaweedfs/s3_config.json")
            .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3_config.json", "-filer", "-dir=/data", "-master.volumeSizeLimitMB=64")
            .withExposedPorts(8333, 9333)
            .waitingFor(Wait.forHttp("/cluster/status").forPort(9333).forResponsePredicate { "IsLeader" in it })
        seaweed.start()
        s3Endpoint = "http://${seaweed.host}:${seaweed.getMappedPort(8333)}"
        createBucket()

        polaris = GenericContainer(DockerImageName.parse("apache/polaris:1.4.1"))
            .withNetwork(network)
            .withExposedPorts(8181, 8182)
            .withEnv("AWS_REGION", "us-east-1")
            .withEnv("AWS_ACCESS_KEY_ID", "polaris")
            .withEnv("AWS_SECRET_ACCESS_KEY", "polaris-secret")
            .withEnv("AWS_ENDPOINT_URL_S3", "http://seaweedfs:8333")
            .withEnv("QUARKUS_S3_ENDPOINT_OVERRIDE", "http://seaweedfs:8333")
            .withEnv("QUARKUS_S3_PATH_STYLE_ACCESS", "true")
            .withEnv("POLARIS_BOOTSTRAP_CREDENTIALS", "POLARIS,root,s3cr3t")
            .withEnv("polaris.realm-context.realms", "POLARIS")
            .withEnv("quarkus.otel.sdk.disabled", "true")
            .waitingFor(Wait.forHttp("/q/health").forPort(8182).withStartupTimeout(Duration.ofMinutes(3)))
        polaris.start()
        base = "http://${polaris.host}:${polaris.getMappedPort(8181)}"
        fixture = PreviewFixture(base, s3Endpoint, catalog)
        reader = fixture.setUpCatalog()
        firstSnapshot = fixture.writeData()
    }

    /** SeaweedFS starts its S3 gateway after the master; retry until it answers. */
    private fun createBucket() {
        val deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos()
        s3Client("polaris", "polaris-secret").use { client ->
            while (true) {
                try {
                    client.createBucket { b -> b.bucket("warehouse") }
                    return
                } catch (e: Exception) {
                    if (System.nanoTime() > deadline) throw e
                    Thread.sleep(500)
                }
            }
        }
    }

    private fun s3Client(key: String, secret: String): S3Client = S3Client.builder()
        .endpointOverride(URI(s3Endpoint)).forcePathStyle(true).region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
        .httpClient(UrlConnectionHttpClient.create())
        .build()

    @AfterAll
    fun stop() {
        if (::polaris.isInitialized) polaris.stop()
        if (::seaweed.isInitialized) seaweed.stop()
        network.close()
    }

    /** The reader's own S3 keys; the endpoint comes from Polaris unless [endpoint] overrides it. */
    private fun keys(key: String = "reader", secret: String = "reader-secret", endpoint: String? = null) =
        S3Access(key, { secret }, endpoint = endpoint, addressing = S3Addressing.PATH_STYLE)

    private fun preview(id: String = "root", secret: String = "s3cr3t", s3: S3Access? = keys()) =
        PolarisPreview(PreviewConnection(base, id, { secret }, realm = "POLARIS", s3 = s3))

    private fun ok(result: PreviewResult<PreviewRows>): PreviewRows = when (result) {
        is PreviewResult.Ok -> result.value
        else -> error("expected rows, got $result")
    }

    private fun idsAndValues(rows: PreviewRows): Map<String, String?> = rows.rows.associate { it[0]!! to it[2] }

    // --- tests -----------------------------------------------------------------------------

    @Test
    fun `the current snapshot shows exactly the live rows`() = runBlocking {
        val rows = ok(preview().read(catalog, id, PreviewRequest()))
        val byId = idsAndValues(rows)
        assertEquals(11, rows.rows.size, rows.rows.toString())
        assertFalse("2" in byId)
        assertEquals("v5-updated", byId["5"])
        assertEquals(1, rows.rows.count { it[0] == "5" })
        assertEquals(listOf("id", "ts", "value", "note"), rows.columns.map { it.name })
        assertTrue(rows.rows.all { it[3] == null }, "a column added after the last commit reads as null")
        assertFalse(rows.hasMore)
    }

    @Test
    fun `a column added since the last commit can be chosen at the current state and on the branch`() = runBlocking {
        for (selector in listOf(SnapshotSelector.Current, SnapshotSelector.Ref("main"))) {
            val rows = ok(preview().read(catalog, id, PreviewRequest(selector = selector, columns = listOf("id", "note"))))
            assertEquals(listOf("id", "note"), rows.columns.map { it.name }, "$selector")
            assertEquals(11, rows.rows.size)
        }
    }

    @Test
    fun `an older snapshot reads with its own schema, and a column it did not have is named`() = runBlocking {
        val rows = ok(preview().read(catalog, id, PreviewRequest(selector = SnapshotSelector.Snapshot(firstSnapshot))))
        assertEquals(listOf("id", "ts", "value"), rows.columns.map { it.name })
        val missing = preview().read(catalog, id, PreviewRequest(selector = SnapshotSelector.Snapshot(firstSnapshot), columns = listOf("note")))
        assertTrue(missing is PreviewResult.Failed && "note" in missing.message, missing.toString())
    }

    @Test
    fun `an older snapshot and a tag show their own rows`() = runBlocking {
        for (selector in listOf(SnapshotSelector.Snapshot(firstSnapshot), SnapshotSelector.Ref("before-merge"))) {
            val rows = ok(preview().read(catalog, id, PreviewRequest(selector = selector)))
            val byId = idsAndValues(rows)
            assertEquals(12, rows.rows.size, "$selector")
            assertEquals("v2", byId["2"])
            assertEquals("v5", byId["5"])
            assertEquals(firstSnapshot, rows.snapshotId)
        }
    }

    @Test
    fun `a filter on the partition source column plans only that day's files`() = runBlocking {
        val request = PreviewRequest(
            filters = listOf(
                ColumnFilter("ts", FilterOp.GT_EQ, listOf("2026-11-02T00:00:00")),
                ColumnFilter("ts", FilterOp.LT, listOf("2026-11-03T00:00:00")),
            ),
        )
        val rows = ok(preview().read(catalog, id, request))
        assertEquals(setOf("5", "6", "7", "8"), rows.rows.map { it[0] }.toSet())
        assertEquals("v5-updated", idsAndValues(rows)["5"])
        val planned = fixture.plannedFiles(request.filters)
        assertEquals(2, planned.size, planned.toString())
        assertTrue(planned.all { "ts_day=2026-11-02" in it }, planned.toString())
    }

    @Test
    fun `a bad filter value names the column`() = runBlocking {
        val result = preview().read(catalog, id, PreviewRequest(filters = listOf(ColumnFilter("id", FilterOp.EQ, listOf("abc")))))
        assertTrue(result is PreviewResult.Failed && "id" in result.message, result.toString())
    }

    @Test
    fun `chosen columns are the only ones read`() = runBlocking {
        val rows = ok(preview().read(catalog, id, PreviewRequest(columns = listOf("value"))))
        assertEquals(listOf("value"), rows.columns.map { it.name })
        assertTrue(rows.rows.all { it.size == 1 })
    }

    @Test
    fun `pages continue the same scan without repeating rows and the session closes itself`() = runBlocking {
        val session = (preview().open(catalog, id, PreviewRequest()) as PreviewResult.Ok).value
        val first = ok(session.next(5))
        assertEquals(5, first.rows.size)
        assertTrue(first.hasMore)
        assertFalse(session.isClosed)
        val rest = ok(session.next(100))
        assertEquals(6, rest.rows.size)
        assertFalse(rest.hasMore)
        assertTrue(session.isClosed, "an exhausted session releases its catalog and keys")
        assertEquals(11, (first.rows + rest.rows).map { it[0] }.toSet().size)
    }

    @Test
    fun `the Data tab's choices come from the metadata the browser already loaded`() = runBlocking {
        val client = io.github.alexey_samodurov.polaris.client.PolarisClient(
            io.github.alexey_samodurov.polaris.client.PolarisConnection(base, "root", { "s3cr3t" }, realm = "POLARIS"),
        )
        val loaded = client.catalog(catalog).loadTable(id, io.github.alexey_samodurov.polaris.client.SnapshotMode.ALL)
        val info = PreviewTableInfo.of(loaded.metadata)
        assertEquals(listOf("id", "ts", "value", "note"), info.columns.map { it.name })
        assertEquals(listOf("ts"), info.partitionSourceColumns.map { it.name })
        assertEquals(setOf("main", "before-merge"), info.refs.keys)
        assertEquals(2, info.snapshots.size, "the history, not only the snapshots refs hold")
    }

    @Test
    fun `closing a session while a page is read releases it after the page`() = runBlocking {
        val session = (preview().open(catalog, id, PreviewRequest()) as PreviewResult.Ok).value
        val page = async(kotlinx.coroutines.Dispatchers.IO) { session.next(1) }
        session.close()
        page.await()
        assertTrue(session.isClosed)
        val after = session.next(1)
        assertTrue(after is PreviewResult.Failed, after.toString())
    }

    @Test
    fun `reads on a thread whose context class loader cannot see the plugin, as inside the IDE`() = runBlocking {
        // Inside PyCharm, IO threads carry the platform's context class loader, which does not see
        // the plugin's jars; Iceberg builds its HTTP client and FileIO through that loader.
        val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = ClassLoader.getPlatformClassLoader()
            try {
                preview().read(catalog, id, PreviewRequest())
            } finally {
                thread.contextClassLoader = previous
            }
        }
        assertEquals(11, ok(rows).rows.size)
    }

    @Test
    fun `S3 keys without access to the bucket get no permission`() = runBlocking {
        val result = preview(s3 = keys("stranger", "stranger-secret")).read(catalog, id, PreviewRequest())
        assertTrue(result is PreviewResult.NoPermission, result.toString())
    }

    @Test
    fun `without keys and with STS off the preview asks for keys instead of using others`() = runBlocking {
        val result = preview(s3 = null).read(catalog, id, PreviewRequest())
        assertEquals(PreviewResult.Failed(PolarisPreview.NO_CREDENTIALS), result)
    }

    @Test
    fun `an endpoint set on the connection wins over the one Polaris returns`() = runBlocking {
        val wrong = preview(s3 = keys(endpoint = "http://127.0.0.1:1")).read(catalog, id, PreviewRequest())
        assertTrue(wrong is PreviewResult.Failed, wrong.toString())
        val right = ok(preview(s3 = keys(endpoint = s3Endpoint)).read(catalog, id, PreviewRequest()))
        assertEquals(11, right.rows.size)
    }

    @Test
    fun `a principal that only reads metadata previews with its own S3 keys`() = runBlocking {
        val rows = ok(preview(reader.first, reader.second).read(catalog, id, PreviewRequest()))
        assertEquals(11, rows.rows.size)
    }

    private companion object {
        /** polaris: the server and the fixture; reader: read-only; stranger: another bucket only. */
        val S3_IDENTITIES = """
            {"identities":[
              {"name":"polaris","credentials":[{"accessKey":"polaris","secretKey":"polaris-secret"}],
               "actions":["Admin","Read","Write","List","Tagging"]},
              {"name":"reader","credentials":[{"accessKey":"reader","secretKey":"reader-secret"}],
               "actions":["Read","List"]},
              {"name":"stranger","credentials":[{"accessKey":"stranger","secretKey":"stranger-secret"}],
               "actions":["Read:elsewhere","List:elsewhere"]}
            ]}
        """.trimIndent()
    }
}
