package io.github.alexey_samodurov.polaris.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One rendering of commit times across the plugin: local time with its zone. */
internal object Formats {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    fun time(ms: Long?): String = ms?.let { TIME.format(Instant.ofEpochMilli(it)) } ?: ""
}
