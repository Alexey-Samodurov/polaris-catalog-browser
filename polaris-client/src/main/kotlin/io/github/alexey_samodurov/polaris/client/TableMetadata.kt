package io.github.alexey_samodurov.polaris.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A schema of a table or a view. */
data class Schema(val schemaId: Int?, val identifierFieldIds: List<Int>, val fields: List<NestedField>) {
    /**
     * The dotted name of the field with [id], searching nested structs, lists and maps; `null` when
     * the field is gone from this schema.
     */
    fun fieldName(id: Int): String? = findName(fields, id, prefix = "")

    private fun findName(fields: List<NestedField>, id: Int, prefix: String): String? {
        for (field in fields) {
            val name = prefix + field.name
            if (field.id == id) return name
            findInType(field.type, id, name)?.let { return it }
        }
        return null
    }

    private fun findInType(type: IcebergType, id: Int, name: String): String? = when (type) {
        is IcebergType.Struct -> findName(type.fields, id, "$name.")
        is IcebergType.ListType -> if (type.elementId == id) "$name.element" else findInType(type.element, id, "$name.element")
        is IcebergType.MapType -> when (id) {
            type.keyId -> "$name.key"
            type.valueId -> "$name.value"
            else -> findInType(type.key, id, "$name.key") ?: findInType(type.value, id, "$name.value")
        }
        else -> null
    }

    companion object {
        fun parse(element: JsonElement): Schema {
            val obj = element.asObject()
            return Schema(
                schemaId = obj.int("schema-id"),
                identifierFieldIds = obj.intList("identifier-field-ids"),
                fields = (obj["fields"] ?: error("schema has no fields")).asArray().map { NestedField.parse(it.asObject()) },
            )
        }
    }
}

/** A partition field; v3 transforms may take several source columns. */
data class PartitionField(val sourceIds: List<Int>, val fieldId: Int?, val name: String, val transform: String)

data class PartitionSpec(val specId: Int?, val fields: List<PartitionField>) {
    companion object {
        fun parse(element: JsonElement): PartitionSpec {
            val obj = element.asObject()
            return PartitionSpec(obj.int("spec-id"), parseFields(obj["fields"] ?: error("spec has no fields")))
        }

        fun parseFields(element: JsonElement): List<PartitionField> = element.asArray().map {
            val f = it.asObject()
            PartitionField(
                sourceIds = sourceIds(f),
                fieldId = f.int("field-id"),
                name = f.str("name") ?: "?",
                transform = f.str("transform") ?: "?",
            )
        }
    }
}

data class SortField(val sourceIds: List<Int>, val transform: String, val direction: String, val nullOrder: String)

data class SortOrder(val orderId: Int?, val fields: List<SortField>) {
    companion object {
        fun parse(element: JsonElement): SortOrder {
            val obj = element.asObject()
            return SortOrder(
                orderId = obj.int("order-id"),
                fields = (obj["fields"] ?: error("sort order has no fields")).asArray().map {
                    val f = it.asObject()
                    SortField(
                        sourceIds = sourceIds(f),
                        transform = f.str("transform") ?: "?",
                        direction = f.str("direction") ?: "?",
                        nullOrder = f.str("null-order") ?: "?",
                    )
                },
            )
        }
    }
}

private fun sourceIds(field: JsonObject): List<Int> =
    field.intList("source-ids").ifEmpty { listOfNotNull(field.int("source-id")) }

data class Snapshot(
    val snapshotId: Long,
    val parentSnapshotId: Long?,
    val sequenceNumber: Long?,
    val timestampMs: Long?,
    val manifestList: String?,
    val schemaId: Int?,
    val summary: Map<String, String>,
) {
    val operation: String? get() = summary["operation"]

    companion object {
        fun parse(element: JsonElement): Snapshot {
            val obj = element.asObject()
            return Snapshot(
                snapshotId = obj.long("snapshot-id") ?: error("snapshot has no id"),
                parentSnapshotId = obj.long("parent-snapshot-id"),
                sequenceNumber = obj.long("sequence-number"),
                timestampMs = obj.long("timestamp-ms"),
                manifestList = obj.str("manifest-list"),
                schemaId = obj.int("schema-id"),
                summary = obj["summary"]?.asStringMap().orEmpty(),
            )
        }
    }
}

data class SnapshotRef(val snapshotId: Long, val type: String)

/**
 * Table metadata as the server returned it.
 *
 * Scalars are read directly; each section the UI shows on its own is a [Section], so one part the
 * client does not understand leaves the others readable. Metadata written in format v1 (a single
 * `schema` and `partition-spec`) is read too.
 */
class TableMetadata(val raw: JsonObject) {
    val formatVersion: Int? = raw.int("format-version")
    val tableUuid: String? = raw.str("table-uuid")
    val location: String? = raw.str("location")
    val lastUpdatedMs: Long? = raw.long("last-updated-ms")
    val lastSequenceNumber: Long? = raw.long("last-sequence-number")
    val nextRowId: Long? = raw.long("next-row-id")
    val currentSchemaId: Int? = raw.int("current-schema-id")
    val defaultSpecId: Int? = raw.int("default-spec-id")
    val defaultSortOrderId: Int? = raw.int("default-sort-order-id")

    /** `null` for a table with no snapshot; v1 writers used -1 for that. */
    val currentSnapshotId: Long? = raw.long("current-snapshot-id")?.takeIf { it != -1L }

    val properties: Section<Map<String, String>> =
        if (raw["properties"] == null) Section.Parsed(emptyMap()) else section(raw["properties"]) { it.asStringMap() }

    val schemas: Section<List<Schema>> = when {
        raw["schemas"] != null -> section(raw["schemas"]) { el -> el.asArray().map(Schema::parse) }
        else -> section(raw["schema"]) { listOf(Schema.parse(it)) }
    }

    val partitionSpecs: Section<List<PartitionSpec>> = when {
        raw["partition-specs"] != null -> section(raw["partition-specs"]) { el -> el.asArray().map(PartitionSpec::parse) }
        raw["partition-spec"] != null -> section(raw["partition-spec"]) {
            listOf(PartitionSpec(0, PartitionSpec.parseFields(it)))
        }
        else -> Section.Parsed(emptyList())
    }

    val sortOrders: Section<List<SortOrder>> =
        if (raw["sort-orders"] == null) Section.Parsed(emptyList()) else section(raw["sort-orders"]) { el -> el.asArray().map(SortOrder::parse) }

    val snapshots: Section<List<Snapshot>> =
        if (raw["snapshots"] == null) Section.Parsed(emptyList()) else section(raw["snapshots"]) { el -> el.asArray().map(Snapshot::parse) }

    val refs: Section<Map<String, SnapshotRef>> =
        if (raw["refs"] == null) Section.Parsed(emptyMap()) else section(raw["refs"]) { el ->
            el.asObject().mapValues { (_, v) ->
                val r = v.asObject()
                SnapshotRef(r.long("snapshot-id") ?: error("ref has no snapshot id"), r.str("type") ?: "?")
            }
        }

    /** The schema in force; for v1 metadata without ids, the only one. */
    val currentSchema: Schema?
        get() = schemas.valueOrNull()?.let { all -> all.firstOrNull { it.schemaId == currentSchemaId } ?: all.singleOrNull() }

    val defaultSpec: PartitionSpec?
        get() = partitionSpecs.valueOrNull()?.let { all -> all.firstOrNull { it.specId == defaultSpecId } ?: all.singleOrNull() }

    val defaultSortOrder: SortOrder?
        get() = sortOrders.valueOrNull()?.firstOrNull { it.orderId == defaultSortOrderId }

    /** Snapshots newest first; a snapshot without a timestamp sorts last. */
    val snapshotsNewestFirst: List<Snapshot>
        get() = snapshots.valueOrNull().orEmpty().sortedByDescending { it.timestampMs ?: Long.MIN_VALUE }

    /** The name of a field of any schema this table had, newest schema first; `null` when none has it. */
    fun fieldName(id: Int): String? {
        val all = schemas.valueOrNull().orEmpty()
        val ordered = listOfNotNull(currentSchema) + all.filter { it !== currentSchema }.sortedByDescending { it.schemaId ?: -1 }
        return ordered.firstNotNullOfOrNull { it.fieldName(id) }
    }

}
