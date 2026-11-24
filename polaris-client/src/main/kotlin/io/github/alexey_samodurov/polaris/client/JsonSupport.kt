package io.github.alexey_samodurov.polaris.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * One part of a server response that the UI shows on its own.
 *
 * A part the client cannot read becomes [Raw] with the JSON and the reason, so the rest of the
 * table still opens and the user sees what the server sent.
 */
sealed interface Section<out T> {
    data class Parsed<T>(val value: T) : Section<T>

    data class Raw(val json: JsonElement?, val error: String) : Section<Nothing>

    fun valueOrNull(): T? = (this as? Parsed<T>)?.value
}

internal inline fun <T> section(element: JsonElement?, parse: (JsonElement) -> T): Section<T> {
    if (element == null) return Section.Raw(null, "missing")
    return try {
        Section.Parsed(parse(element))
    } catch (e: Exception) {
        Section.Raw(element, e.message ?: e.javaClass.simpleName)
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: error("expected an object, got $this")

internal fun JsonElement.asArray(): JsonArray = this as? JsonArray ?: error("expected an array, got $this")

/** A JSON object of scalars as a string map; a non-string value keeps its JSON text. */
internal fun JsonElement.asStringMap(): Map<String, String> = asObject().mapValues { (_, v) ->
    if (v is JsonPrimitive && v.isString) v.content else v.toString()
}

internal fun JsonObject.intList(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
