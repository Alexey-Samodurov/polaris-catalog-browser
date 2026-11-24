package io.github.alexey_samodurov.polaris.preview

import org.apache.iceberg.FileFormat
import org.apache.iceberg.PartitionKey
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.data.GenericFileWriterFactory
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.Record
import org.apache.iceberg.deletes.BaseDVFileWriter
import org.apache.iceberg.io.OutputFileFactory
import org.apache.iceberg.rest.RESTCatalog
import org.apache.iceberg.types.Types
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import org.apache.iceberg.catalog.Namespace as IcebergNamespace
import org.apache.iceberg.catalog.TableIdentifier as IcebergIdentifier

/**
 * Data for preview tests and for a local stand: a catalog on SeaweedFS with STS off, a principal
 * that reads metadata only, and table `cdc.events` (v3, partitioned by day) whose rows were deleted
 * and updated through deletion vectors, with tag `before-merge` on the first state.
 *
 * Writes as the bootstrap principal `root` with the `polaris` S3 identity; [s3Endpoint] is the S3
 * endpoint clients reach, `http://seaweedfs:8333` the one Polaris reaches.
 */
class PreviewFixture(private val base: String, private val s3Endpoint: String, private val catalog: String) {
    private val http = HttpClient.newHttpClient()

    /**
     * Runs [body] with the AWS SDK's checksums only where required. AWS SDK 2.54 adds streaming
     * CRC checksums to uploads, and SeaweedFS 4.28 then intermittently rejects the request's
     * Content-MD5. Only the fixture writes, so only it opts out, and the properties are restored
     * before any test reads, so reads run with the defaults the plugin runs with.
     */
    private fun <T> withRelaxedChecksums(body: () -> T): T {
        val keys = listOf("aws.requestChecksumCalculation", "aws.responseChecksumValidation")
        val previous = keys.associateWith { System.getProperty(it) }
        keys.forEach { System.setProperty(it, "WHEN_REQUIRED") }
        try {
            return body()
        } finally {
            previous.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }

    private val schema = Schema(
        Types.NestedField.required(1, "id", Types.LongType.get()),
        Types.NestedField.required(2, "ts", Types.TimestampType.withoutZone()),
        Types.NestedField.optional(3, "value", Types.StringType.get()),
    )
    private val spec = PartitionSpec.builderFor(schema).day("ts").build()
    private val days = listOf("2026-11-01", "2026-11-02", "2026-11-03")

    private val rootToken by lazy {
        val r = http.send(
            HttpRequest.newBuilder(URI("$base/api/catalog/v1/oauth/tokens"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Polaris-Realm", "POLARIS")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=root&client_secret=s3cr3t&scope=PRINCIPAL_ROLE%3AALL"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(r.body())!!.groupValues[1]
    }

    private fun write(method: String, path: String, body: String): String {
        val r = http.send(
            HttpRequest.newBuilder(URI(base + path))
                .header("Authorization", "Bearer $rootToken").header("Polaris-Realm", "POLARIS")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(r.statusCode() in 200..299) { "$method $path: ${r.statusCode()} ${r.body()}" }
        return r.body()
    }

    /** Creates the catalog and the metadata-only principal; returns that principal's id and secret. */
    fun setUpCatalog(): Pair<String, String> {
        val m = "/api/management/v1"
        write(
            "POST", "$m/catalogs",
            """{"catalog":{"name":"$catalog","type":"INTERNAL","properties":{"default-base-location":"s3://warehouse/$catalog"},
               "storageConfigInfo":{"storageType":"S3","allowedLocations":["s3://warehouse/"],
               "endpoint":"$s3Endpoint","endpointInternal":"http://seaweedfs:8333","pathStyleAccess":true,
               "stsUnavailable":true}}}""",
        )
        write("PUT", "$m/catalogs/$catalog/catalog-roles/catalog_admin/grants", """{"grant":{"type":"catalog","privilege":"CATALOG_MANAGE_CONTENT"}}""")

        // A principal that may list and read metadata, but not data.
        val created = write("POST", "$m/principals", """{"principal":{"name":"meta_reader"}}""")
        val reader = Regex("\"clientId\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1] to
            Regex("\"clientSecret\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
        write("POST", "$m/principal-roles", """{"principalRole":{"name":"meta_role"}}""")
        write("PUT", "$m/principals/meta_reader/principal-roles", """{"principalRole":{"name":"meta_role"}}""")
        write("POST", "$m/catalogs/$catalog/catalog-roles", """{"catalogRole":{"name":"meta_cat"}}""")
        for (p in listOf("CATALOG_READ_PROPERTIES", "NAMESPACE_LIST", "NAMESPACE_READ_PROPERTIES", "TABLE_LIST", "TABLE_READ_PROPERTIES")) {
            write("PUT", "$m/catalogs/$catalog/catalog-roles/meta_cat/grants", """{"grant":{"type":"catalog","privilege":"$p"}}""")
        }
        write("PUT", "$m/principal-roles/meta_role/catalog-roles/$catalog", """{"catalogRole":{"name":"meta_cat"}}""")
        return reader
    }

    private fun rootCatalog(): RESTCatalog = RESTCatalog().also {
        it.initialize(
            "fixture",
            mapOf(
                "uri" to "$base/api/catalog", "credential" to "root:s3cr3t", "scope" to "PRINCIPAL_ROLE:ALL",
                "warehouse" to catalog, "header.Polaris-Realm" to "POLARIS",
                "io-impl" to PreviewS3FileIO::class.java.name, "http-client.type" to "urlconnection",
                "client.region" to "us-east-1", "s3.endpoint" to s3Endpoint, "s3.path-style-access" to "true",
                "s3.access-key-id" to "polaris", "s3.secret-access-key" to "polaris-secret",
                // SeaweedFS 4.28 intermittently rejects the Content-MD5 Iceberg sends on uploads.
                // Set as an override, because the table config Polaris returns wins over catalog properties.
                "${PreviewS3FileIO.PREFIX}s3.checksum-enabled" to "false",
            ),
        )
    }

    private fun record(id: Long, day: String, value: String): Record = GenericRecord.create(schema).apply {
        setField("id", id)
        setField("ts", LocalDateTime.parse("${day}T12:00:00"))
        setField("value", value)
    }

    /** Partition keys are computed on Iceberg's internal values (timestamps as micros). */
    private fun internal(table: Table, row: Record) = org.apache.iceberg.data.InternalRecordWrapper(table.schema().asStruct()).wrap(row)

    /**
     * Writes the table and returns the id of its first snapshot: 12 rows over three days; then id 2
     * deleted and id 5 updated through deletion vectors; a tag on the first state; and last a
     * column `note` added with no commit of data after it, as a schema change on a live table.
     */
    fun writeData(): Long = withRelaxedChecksums { rootCatalog().use { rest ->
        rest.createNamespace(IcebergNamespace.of("cdc"))
        val table = rest.createTable(IcebergIdentifier.of(IcebergNamespace.of("cdc"), "events"), schema, spec, mapOf("format-version" to "3"))
        val files = OutputFileFactory.builderFor(table, 1, 1).format(FileFormat.PARQUET).build()
        val writers = GenericFileWriterFactory.Builder(table).dataFileFormat(FileFormat.PARQUET).build()

        // One data file per day, four rows each: ids 1-4, 5-8, 9-12.
        val append = table.newAppend()
        val dataFiles = days.mapIndexed { d, day ->
            val rows = (1..4).map { record(d * 4L + it, day, "v${d * 4 + it}") }
            val key = PartitionKey(table.spec(), table.schema()).also { it.partition(internal(table, rows.first())) }
            val writer = writers.newDataWriter(files.newOutputFile(table.spec(), key), table.spec(), key)
            writer.use { w -> rows.forEach { w.write(it) } }
            writer.toDataFile().also(append::appendFile)
        }
        append.commit()
        table.refresh()
        val firstSnapshot = table.currentSnapshot().snapshotId()
        table.manageSnapshots().createTag("before-merge", firstSnapshot).commit()

        // Delete id 2 (day 1, position 1) and replace id 5 (day 2, position 0) with a new row.
        val dv = BaseDVFileWriter(files) { null }
        dv.use {
            it.delete(dataFiles[0].location(), 1, table.spec(), dataFiles[0].partition())
            it.delete(dataFiles[1].location(), 0, table.spec(), dataFiles[1].partition())
        }
        val updated = record(5, days[1], "v5-updated")
        val key = PartitionKey(table.spec(), table.schema()).also { it.partition(internal(table, updated)) }
        val writer = writers.newDataWriter(files.newOutputFile(table.spec(), key), table.spec(), key)
        writer.use { w -> w.write(updated) }
        val delta = table.newRowDelta().addRows(writer.toDataFile())
        dv.result().deleteFiles().forEach(delta::addDeletes)
        delta.commit()
        table.updateSchema().addColumn("note", Types.StringType.get()).commit()
        firstSnapshot
    } }

    /** Data files a scan with [filters] plans at the current snapshot, read as root. */
    fun plannedFiles(filters: List<ColumnFilter>): List<String> = rootCatalog().use { rest ->
        val table = rest.loadTable(IcebergIdentifier.of(IcebergNamespace.of("cdc"), "events"))
        table.newScan().filter(PolarisPreview.filterExpression(table.schema(), filters)).planFiles().use { tasks ->
            tasks.map { it.file().location() }.toList()
        }
    }
}

