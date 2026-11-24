package io.github.alexey_samodurov.polaris.client

/** Separator of namespace levels in a URL, as the Iceberg REST specification defines it. */
private const val NAMESPACE_SEPARATOR = '\u001F'

private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * Percent-encodes one path segment or query value (RFC 3986).
 *
 * Only unreserved characters stay as they are, so `/`, space, `+` and the namespace separator
 * are all encoded; `java.net.URLEncoder` is not used because it turns a space into `+`.
 */
fun encodeSegment(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val unreserved = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        if (unreserved) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

/** A multi-level namespace as one encoded path segment: levels joined by `%1F`. */
fun encodeNamespace(namespace: Namespace): String {
    require(namespace.levels.isNotEmpty()) { "namespace is empty" }
    return encodeSegment(namespace.levels.joinToString(NAMESPACE_SEPARATOR.toString()))
}
