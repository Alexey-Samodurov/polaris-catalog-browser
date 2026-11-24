package io.github.alexey_samodurov.polaris.preview

import org.apache.iceberg.aws.s3.S3FileIO

/**
 * `S3FileIO` whose settings from the connection win over the table config Polaris returns.
 *
 * The REST catalog merges the server's table config over the catalog's properties, so a key the
 * user set (the S3 keys, an endpoint reachable from the laptop) would lose to the server's value.
 * Settings passed under [PREFIX] survive that merge, because the server never sends such keys, and
 * are applied here last.
 */
class PreviewS3FileIO : S3FileIO() {
    override fun initialize(props: Map<String, String>) {
        val overrides = props.filterKeys { it.startsWith(PREFIX) }.mapKeys { it.key.removePrefix(PREFIX) }
        super.initialize(props.filterKeys { !it.startsWith(PREFIX) } + overrides)
    }

    companion object {
        const val PREFIX: String = "polaris-browser.override."
    }
}
