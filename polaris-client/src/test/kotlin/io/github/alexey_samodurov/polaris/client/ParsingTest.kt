package io.github.alexey_samodurov.polaris.client

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParsingTest {
    private fun metadata(text: String) = TableMetadata(json.parseToJsonElement(text) as JsonObject)

    @Test
    fun `base urls are reduced to the server root`() {
        assertEquals("https://polaris.example", normalizeBaseUrl("https://polaris.example/").toString())
        assertEquals("https://polaris.example", normalizeBaseUrl(" https://polaris.example/api/catalog/ ").toString())
        assertEquals("https://polaris.example", normalizeBaseUrl("https://polaris.example/api/catalog/v1").toString())
        assertEquals("https://polaris.example", normalizeBaseUrl("https://polaris.example/api/management/v1/").toString())
        assertEquals("https://h/proxy", normalizeBaseUrl("https://h/proxy/api/catalog").toString())
        assertThrows<IllegalArgumentException> { normalizeBaseUrl("polaris.example") }
        assertThrows<IllegalArgumentException> { normalizeBaseUrl("https://h/?x=1") }
        assertThrows<IllegalArgumentException> { normalizeBaseUrl("  ") }
    }

    @Test
    fun `segments are percent-encoded per RFC 3986`() {
        assertEquals("a%20b%2Fc%2Bd~e", encodeSegment("a b/c+d~e"))
        assertEquals("%D1%82", encodeSegment("т"))
        assertEquals("a%1Fb%1Fc", encodeNamespace(Namespace.of("a", "b", "c")))
    }

    @Test
    fun `a v3 table with nested and unknown types parses`() {
        val m = metadata(
            """
            {"format-version":3,"table-uuid":"u","location":"s3://b/t","current-schema-id":1,"next-row-id":10,
             "schemas":[
               {"schema-id":0,"type":"struct","fields":[{"id":1,"name":"old","required":false,"type":"int"}]},
               {"schema-id":1,"type":"struct","identifier-field-ids":[2],"fields":[
                 {"id":2,"name":"id","required":true,"type":"long","doc":"key","initial-default":0},
                 {"id":3,"name":"payload","required":false,"type":"variant"},
                 {"id":4,"name":"tags","required":false,"type":{"type":"list","element-id":5,"element":"string","element-required":true}},
                 {"id":6,"name":"attrs","required":false,"type":{"type":"map","key-id":7,"key":"string","value-id":8,
                   "value":{"type":"struct","fields":[{"id":9,"name":"v","required":false,"type":"decimal(10,2)"}]},"value-required":false}},
                 {"id":10,"name":"shape","required":false,"type":{"type":"something-new","x":1}}
               ]}],
             "default-spec-id":0,
             "partition-specs":[{"spec-id":0,"fields":[
               {"source-id":2,"field-id":1000,"name":"id_bucket","transform":"bucket[16]"},
               {"source-ids":[2,3],"field-id":1001,"name":"multi","transform":"some-v3-transform"},
               {"source-id":1,"field-id":1002,"name":"old_identity","transform":"identity"}]}],
             "default-sort-order-id":1,
             "sort-orders":[{"order-id":1,"fields":[{"transform":"identity","source-id":2,"direction":"asc","null-order":"nulls-first"}]}],
             "current-snapshot-id":2,
             "snapshots":[
               {"snapshot-id":1,"timestamp-ms":100,"summary":{"operation":"append"}},
               {"snapshot-id":2,"parent-snapshot-id":1,"timestamp-ms":200,"sequence-number":2,"summary":{"operation":"overwrite","added-records":"5"}}],
             "refs":{"main":{"snapshot-id":2,"type":"branch"}},
             "properties":{"write.format.default":"parquet","owner":"ingest"}}
            """,
        )
        assertEquals(3, m.formatVersion)
        assertEquals(10L, m.nextRowId)
        val schema = m.currentSchema!!
        assertEquals(listOf(2), schema.identifierFieldIds)
        assertEquals(IcebergType.Primitive("variant"), schema.fields[1].type)
        assertEquals("list<string>", schema.fields[2].type.render())
        assertEquals("map<string, struct<v: decimal(10,2)>>", schema.fields[3].type.render())
        assertTrue(schema.fields[4].type is IcebergType.Unknown)
        assertEquals("attrs.value.v", schema.fieldName(9))
        assertEquals("tags.element", schema.fieldName(5))
        assertNull(schema.fieldName(1))
        assertEquals("old", m.fieldName(1))
        assertEquals(listOf(2, 3), m.defaultSpec!!.fields[1].sourceIds)
        assertEquals("nulls-first", m.defaultSortOrder!!.fields.single().nullOrder)
        assertEquals(listOf(2L, 1L), m.snapshotsNewestFirst.map { it.snapshotId })
        assertEquals("overwrite", m.snapshotsNewestFirst.first().operation)
        assertEquals(2L, m.refs.valueOrNull()!!["main"]!!.snapshotId)
    }

    @Test
    fun `a v1 table with a single schema and spec parses`() {
        val m = metadata(
            """
            {"format-version":1,"location":"s3://b/t","current-snapshot-id":-1,
             "schema":{"type":"struct","fields":[{"id":1,"name":"a","required":true,"type":"int"}]},
             "partition-spec":[{"source-id":1,"field-id":1000,"name":"a","transform":"identity"}]}
            """,
        )
        assertNull(m.currentSnapshotId)
        assertEquals("a", m.currentSchema!!.fields.single().name)
        assertEquals("identity", m.defaultSpec!!.fields.single().transform)
        assertTrue(m.snapshots.valueOrNull()!!.isEmpty())
    }

    @Test
    fun `one unreadable section leaves the others readable`() {
        val m = metadata(
            """
            {"format-version":2,"current-schema-id":0,
             "schemas":[{"schema-id":0,"type":"struct","fields":[{"id":1,"name":"a","required":true,"type":"int"}]}],
             "snapshots":"not an array",
             "properties":{"k":"v"}}
            """,
        )
        assertTrue(m.snapshots is Section.Raw)
        assertEquals("a", m.currentSchema!!.fields.single().name)
        assertEquals(mapOf("k" to "v"), m.properties.valueOrNull())
    }

    @Test
    fun `a view parses with its sql per dialect`() {
        val v = ViewMetadata(
            json.parseToJsonElement(
                """
                {"view-uuid":"u","format-version":1,"location":"s3://v","current-version-id":2,
                 "schemas":[{"schema-id":5,"type":"struct","fields":[{"id":1,"name":"c","required":false,"type":"string"}]}],
                 "versions":[
                   {"version-id":1,"timestamp-ms":1,"schema-id":5,"summary":{},"representations":[],"default-namespace":["a"]},
                   {"version-id":2,"timestamp-ms":2,"schema-id":5,"summary":{"engine-name":"spark"},
                    "representations":[{"type":"sql","sql":"select 1","dialect":"spark"},{"type":"sql","sql":"SELECT 1","dialect":"trino"}],
                    "default-namespace":["a"]}]}
                """,
            ) as JsonObject,
        )
        assertEquals(listOf("spark", "trino"), v.currentVersion!!.representations.map { it.dialect })
        assertEquals("c", v.currentSchema!!.fields.single().name)
    }

    @Test
    fun `sensitive property values are masked`() {
        val masked = maskProperties(
            mapOf(
                "s3.secret-access-key" to "x",
                "s3.access-key-id" to "AKIA",
                "s3.accessKey" to "x",
                "client.credential" to "x",
                "s3.sse.key" to "x",
                "adls.auth.shared-key.account.key" to "x",
                "gcs.oauth2.private-key" to "x",
                "adls.connection-string.acct" to "x",
                "adls.sas-token.acct" to "x",
                "write.format.default" to "parquet",
                "owner" to "ingest",
            ),
        )
        assertEquals(MASK, masked["s3.secret-access-key"])
        assertEquals(MASK, masked["s3.accessKey"])
        assertEquals(MASK, masked["client.credential"])
        for (k in listOf("s3.sse.key", "adls.auth.shared-key.account.key", "gcs.oauth2.private-key", "adls.connection-string.acct", "adls.sas-token.acct")) {
            assertEquals(MASK, masked[k], k)
        }
        assertEquals("parquet", masked["write.format.default"])
        assertEquals("ingest", masked["owner"])
        assertFalse(isSensitiveKey("owner"))
    }
}
