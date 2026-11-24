package io.github.alexey_samodurov.polaris.client

/**
 * Keys whose values are never shown: secrets, passwords, tokens, access keys and credentials, S3
 * server-side encryption keys, Azure shared keys, SAS tokens and connection strings, private keys.
 */
val SENSITIVE_KEY: Regex =
    Regex("(?i)secret|password|token|access[.]?key|credential|sse[.]key|shared[.-]?key|private[.-]?key|connection[.-]?string|sas[.-]")

const val MASK: String = "********"

fun isSensitiveKey(key: String): Boolean = SENSITIVE_KEY.containsMatchIn(key)

/** [properties] with every sensitive value replaced by [MASK]. */
fun maskProperties(properties: Map<String, String>): Map<String, String> =
    properties.mapValues { (k, v) -> if (isSensitiveKey(k)) MASK else v }
