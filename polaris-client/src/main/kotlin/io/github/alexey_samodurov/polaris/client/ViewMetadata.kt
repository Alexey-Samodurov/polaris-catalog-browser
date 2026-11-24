package io.github.alexey_samodurov.polaris.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The SQL of a view in one dialect. */
data class ViewRepresentation(val type: String, val dialect: String?, val sql: String?)

data class ViewVersion(
    val versionId: Int?,
    val timestampMs: Long?,
    val schemaId: Int?,
    val summary: Map<String, String>,
    val representations: List<ViewRepresentation>,
    val defaultNamespace: List<String>,
    val defaultCatalog: String?,
) {
    companion object {
        fun parse(element: JsonElement): ViewVersion {
            val obj = element.asObject()
            return ViewVersion(
                versionId = obj.int("version-id"),
                timestampMs = obj.long("timestamp-ms"),
                schemaId = obj.int("schema-id"),
                summary = obj["summary"]?.asStringMap().orEmpty(),
                representations = obj["representations"]?.asArray().orEmpty().map {
                    val r = it.asObject()
                    ViewRepresentation(type = r.str("type") ?: "?", dialect = r.str("dialect"), sql = r.str("sql"))
                },
                defaultNamespace = obj["default-namespace"]?.asArray().orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                defaultCatalog = obj.str("default-catalog"),
            )
        }
    }
}

/** View metadata as the server returned it; sections fail independently, as in [TableMetadata]. */
class ViewMetadata(val raw: JsonObject) {
    val viewUuid: String? = raw.str("view-uuid")
    val formatVersion: Int? = raw.int("format-version")
    val location: String? = raw.str("location")
    val currentVersionId: Int? = raw.int("current-version-id")

    val properties: Section<Map<String, String>> =
        if (raw["properties"] == null) Section.Parsed(emptyMap()) else section(raw["properties"]) { it.asStringMap() }

    val schemas: Section<List<Schema>> = section(raw["schemas"]) { el -> el.asArray().map(Schema::parse) }

    val versions: Section<List<ViewVersion>> = section(raw["versions"]) { el -> el.asArray().map(ViewVersion::parse) }

    val currentVersion: ViewVersion?
        get() = versions.valueOrNull()?.firstOrNull { it.versionId == currentVersionId }

    val currentSchema: Schema?
        get() = currentVersion?.schemaId?.let { id -> schemas.valueOrNull()?.firstOrNull { it.schemaId == id } }
}
