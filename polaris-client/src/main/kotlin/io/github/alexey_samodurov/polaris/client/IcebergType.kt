package io.github.alexey_samodurov.polaris.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * An Iceberg type as the schema JSON spells it.
 *
 * Any string is a [Primitive], whatever it says (`decimal(10,2)`, `variant`, `geometry(...)`, a type
 * a later spec adds), so a new primitive never breaks parsing. Nested types the client does not
 * recognise are kept as [Unknown] with their raw JSON.
 */
sealed interface IcebergType {
    /** One-line rendering for a table cell. */
    fun render(): String

    data class Primitive(val name: String) : IcebergType {
        override fun render(): String = name
    }

    data class Struct(val fields: List<NestedField>) : IcebergType {
        override fun render(): String = "struct<${fields.joinToString(", ") { "${it.name}: ${it.type.render()}" }}>"
    }

    data class ListType(val elementId: Int?, val element: IcebergType, val elementRequired: Boolean) : IcebergType {
        override fun render(): String = "list<${element.render()}>"
    }

    data class MapType(
        val keyId: Int?,
        val key: IcebergType,
        val valueId: Int?,
        val value: IcebergType,
        val valueRequired: Boolean,
    ) : IcebergType {
        override fun render(): String = "map<${key.render()}, ${value.render()}>"
    }

    data class Unknown(val raw: JsonElement) : IcebergType {
        override fun render(): String = raw.toString()
    }

    companion object {
        fun parse(element: JsonElement?): IcebergType = when (element) {
            null -> Unknown(JsonNull)
            is JsonPrimitive -> if (element.isString) Primitive(element.content) else Unknown(element)
            is JsonObject -> parseObject(element)
            else -> Unknown(element)
        }

        private fun parseObject(obj: JsonObject): IcebergType = runCatching {
            when ((obj["type"] as? JsonPrimitive)?.content) {
                "struct" -> Struct(obj["fields"]?.jsonArray.orEmpty().map { NestedField.parse(it as JsonObject) })
                "list" -> ListType(
                    elementId = obj["element-id"]?.jsonPrimitive?.intOrNull,
                    element = parse(obj["element"]),
                    elementRequired = obj["element-required"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                "map" -> MapType(
                    keyId = obj["key-id"]?.jsonPrimitive?.intOrNull,
                    key = parse(obj["key"]),
                    valueId = obj["value-id"]?.jsonPrimitive?.intOrNull,
                    value = parse(obj["value"]),
                    valueRequired = obj["value-required"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                else -> Unknown(obj)
            }
        }.getOrElse { Unknown(obj) }
    }
}

/** A field of a schema or of a struct. */
data class NestedField(
    val id: Int?,
    val name: String,
    val required: Boolean,
    val type: IcebergType,
    val doc: String?,
    val initialDefault: JsonElement?,
    val writeDefault: JsonElement?,
) {
    companion object {
        fun parse(obj: JsonObject): NestedField = NestedField(
            id = obj["id"]?.jsonPrimitive?.intOrNull,
            name = (obj["name"] as? JsonPrimitive)?.content ?: "?",
            required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
            type = IcebergType.parse(obj["type"]),
            doc = (obj["doc"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            initialDefault = obj["initial-default"],
            writeDefault = obj["write-default"],
        )
    }
}
