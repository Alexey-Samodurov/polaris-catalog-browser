package io.github.alexey_samodurov.polaris

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import io.github.alexey_samodurov.polaris.client.DEFAULT_SCOPE
import io.github.alexey_samodurov.polaris.client.PolarisClient
import io.github.alexey_samodurov.polaris.client.PolarisConnection
import io.github.alexey_samodurov.polaris.preview.PolarisPreview
import io.github.alexey_samodurov.polaris.preview.PreviewConnection
import io.github.alexey_samodurov.polaris.preview.S3Access
import io.github.alexey_samodurov.polaris.preview.S3Addressing
import io.github.alexey_samodurov.polaris.settings.ConnectionSettings
import io.github.alexey_samodurov.polaris.settings.PolarisSecrets
import io.github.alexey_samodurov.polaris.settings.PolarisSettings
import java.util.concurrent.ConcurrentHashMap

/** The secret of a connection is in neither the password safe nor this session. */
class MissingSecretException(val connectionId: String) : Exception("The client secret is not stored")

/** The connection has an S3 key id but its secret is in neither the password safe nor this session. */
class MissingS3SecretException(val connectionId: String) : Exception("The S3 secret access key is not stored")

/**
 * One [PolarisClient] per connection, shared by every tool window so its token, catalog configs and
 * table cache are reused. A cached client is used only for the settings it was built from: a call
 * with other settings (changed since, or an older copy still in flight) gets a client of its own.
 */
@Service(Service.Level.APP)
class PolarisClients : Disposable {
    private class Cached(val settings: ConnectionSettings, val client: PolarisClient)

    private val clients = ConcurrentHashMap<String, Cached>()

    /** Secrets entered for this session only, when the password safe keeps nothing; keyed by connection and id. */
    private val sessionSecrets = ConcurrentHashMap<Pair<String, String>, String>()
    private val sessionS3Secrets = ConcurrentHashMap<Pair<String, String>, String>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(PolarisSettings.TOPIC, PolarisSettings.Listener { ids, credentialsChanged ->
                if (credentialsChanged) ids.forEach(clients::remove)
            })
    }

    fun get(settings: ConnectionSettings): PolarisClient {
        val snapshot = settings.copy()
        return clients.compute(settings.id) { _, cached ->
            if (cached != null && cached.settings.sameAs(snapshot)) cached else Cached(snapshot, PolarisClient(connection(snapshot)))
        }!!.client
    }

    /** Keeps [secret] for this IDE session; used when the password safe does not remember it. */
    fun rememberForSession(settings: ConnectionSettings, secret: String) {
        sessionSecrets[settings.id to settings.clientId] = secret
        clients.remove(settings.id)
    }

    /** Keeps an S3 secret key for this IDE session, when the password safe does not remember it. */
    fun rememberS3ForSession(settings: ConnectionSettings, secret: String) {
        sessionS3Secrets[settings.id to settings.s3AccessKeyId.trim()] = secret
    }

    /** Whether the connection has an S3 key id whose secret is nowhere to be found. Blocks on the keychain. */
    fun lacksS3Secret(settings: ConnectionSettings): Boolean {
        val keyId = settings.s3AccessKeyId.trim()
        if (keyId.isEmpty()) return false
        return PolarisSecrets.getS3(settings.id, keyId) == null && sessionS3Secrets[settings.id to keyId] == null
    }

    /** The Polaris connection for [settings]: the IDE's certificates, proxy and proxy login. */
    fun connection(settings: ConnectionSettings, secret: () -> String = clientSecret(settings)): PolarisConnection =
        PolarisConnection(
            baseUrl = settings.baseUrl,
            clientId = settings.clientId,
            secret = secret,
            realm = settings.realm.ifBlank { null },
            scope = settings.scope.ifBlank { DEFAULT_SCOPE },
            sslContext = CertificateManager.getInstance().sslContext,
            proxySelector = JdkProxyProvider.getInstance().proxySelector,
            authenticator = JdkProxyProvider.getInstance().authenticator,
        )

    /**
     * A previewer for [settings]: the connection's own S3 keys when it has a key id, otherwise
     * credentials Polaris vends. Created per use; it holds nothing between previews.
     */
    fun preview(settings: ConnectionSettings): PolarisPreview {
        val id = settings.id
        val s3 = settings.s3AccessKeyId.trim().takeIf { it.isNotEmpty() }?.let { keyId ->
            S3Access(
                accessKeyId = keyId,
                secretAccessKey = {
                    PolarisSecrets.getS3(id, keyId) ?: sessionS3Secrets[id to keyId] ?: throw MissingS3SecretException(id)
                },
                endpoint = settings.s3Endpoint.ifBlank { null },
                addressing = settings.s3Addressing.takeIf { it.isNotBlank() }?.let { runCatching { S3Addressing.valueOf(it) }.getOrNull() },
                region = settings.s3Region.ifBlank { null },
            )
        }
        val polaris = connection(settings)
        return PolarisPreview(
            PreviewConnection(
                baseUrl = settings.baseUrl,
                clientId = polaris.clientId,
                secret = polaris.secret,
                realm = polaris.realm,
                scope = polaris.scope,
                s3 = s3,
            ),
        )
    }

    private fun clientSecret(settings: ConnectionSettings): () -> String {
        val id = settings.id
        val clientId = settings.clientId
        return { PolarisSecrets.get(id, clientId) ?: sessionSecrets[id to clientId] ?: throw MissingSecretException(id) }
    }

    override fun dispose() {
        clients.clear()
        sessionSecrets.clear()
        sessionS3Secrets.clear()
    }

    companion object {
        fun getInstance(): PolarisClients = service()
    }
}
