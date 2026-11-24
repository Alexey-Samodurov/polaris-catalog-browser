package io.github.alexey_samodurov.polaris.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Client secrets and S3 secret keys in the IDE's password safe, one entry of each per connection.
 *
 * Each entry is stored with the id it belongs to (the client id, the S3 key id) and read back only
 * for that id, so a secret is not reused after the id beside it changed. Reads and writes may block
 * on the OS keychain, so callers stay off the EDT.
 */
object PolarisSecrets {
    private fun attributes(connectionId: String) =
        CredentialAttributes(generateServiceName("Polaris Catalog Browser", connectionId))

    private fun s3Attributes(connectionId: String) =
        CredentialAttributes(generateServiceName("Polaris Catalog Browser S3", connectionId))

    /** The client secret stored for [clientId] on this connection, if any. */
    fun get(connectionId: String, clientId: String): String? = read(attributes(connectionId), clientId)

    fun set(connectionId: String, clientId: String, secret: String?) = write(attributes(connectionId), clientId, secret)

    /** The S3 secret key stored for [accessKeyId] on this connection, if any. */
    fun getS3(connectionId: String, accessKeyId: String): String? = read(s3Attributes(connectionId), accessKeyId)

    fun setS3(connectionId: String, accessKeyId: String, secret: String?) = write(s3Attributes(connectionId), accessKeyId, secret)

    fun remove(connectionId: String) {
        PasswordSafe.instance.set(attributes(connectionId), null)
        PasswordSafe.instance.set(s3Attributes(connectionId), null)
    }

    private fun read(attributes: CredentialAttributes, id: String): String? =
        PasswordSafe.instance.get(attributes)
            ?.takeIf { it.userName == id }
            ?.getPasswordAsString()
            ?.takeIf { it.isNotEmpty() }

    private fun write(attributes: CredentialAttributes, id: String, secret: String?) {
        PasswordSafe.instance.set(attributes, secret?.takeIf { it.isNotEmpty() }?.let { Credentials(id, it) })
    }
}
