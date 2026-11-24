package io.github.alexey_samodurov.polaris.preview

import org.apache.iceberg.data.Record
import java.nio.ByteBuffer

/** Turns values the generic reader returns into text for a grid cell. */
object ValueRendering {
    private const val MAX_TEXT = 1000
    private const val MAX_BYTES = 32

    /** `null` stays `null`, so the grid can show SQL NULL apart from the string "null". */
    fun render(value: Any?): String? = value?.let { cut(text(it)) }

    private fun text(value: Any): String = when (value) {
        is CharSequence -> value.toString()
        // BigDecimal.toString switches to scientific notation (1E-7) for small values.
        is java.math.BigDecimal -> value.toPlainString()
        is ByteBuffer -> bytes(value)
        is ByteArray -> bytes(ByteBuffer.wrap(value))
        is Record -> value.struct().fields().indices.joinToString(", ", "{", "}") { i ->
            "${value.struct().fields()[i].name()}: ${nested(value.get(i))}"
        }
        is List<*> -> value.joinToString(", ", "[", "]") { nested(it) }
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${nested(it.key)}: ${nested(it.value)}" }
        else -> value.toString()
    }

    private fun nested(value: Any?): String = when (value) {
        null -> "null"
        is CharSequence -> "\"$value\""
        else -> text(value)
    }

    private fun bytes(buffer: ByteBuffer): String {
        val b = buffer.duplicate()
        val size = b.remaining()
        val shown = ByteArray(minOf(size, MAX_BYTES)).also { b.get(it) }
        val hex = shown.joinToString("") { "%02x".format(it) }
        return if (size > MAX_BYTES) "0x$hex… ($size bytes)" else "0x$hex"
    }

    private fun cut(text: String): String = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "… (${text.length} chars)" else text
}
