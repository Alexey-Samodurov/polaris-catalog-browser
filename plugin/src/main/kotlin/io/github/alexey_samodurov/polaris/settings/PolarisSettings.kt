package io.github.alexey_samodurov.polaris.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import io.github.alexey_samodurov.polaris.client.DEFAULT_SCOPE
import java.util.UUID

/**
 * One Polaris connection as it is stored. The secret is not here: it lives in the password safe,
 * keyed by [id].
 */
@Tag("connection")
class ConnectionSettings {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var baseUrl: String = ""
    var realm: String = ""
    var scope: String = DEFAULT_SCOPE
    var clientId: String = ""

    /** The user's own S3 key id for previews; empty asks Polaris for vended credentials. */
    var s3AccessKeyId: String = ""

    /** S3 endpoint for previews; empty takes the one Polaris returns. */
    var s3Endpoint: String = ""

    /** `PATH_STYLE`, `VIRTUAL_HOST`, or empty to take what Polaris returns. */
    var s3Addressing: String = ""

    var s3Region: String = ""

    /** Catalogs the user named by hand, for a principal that may not list catalogs. */
    @XCollection(style = XCollection.Style.v2)
    var namedCatalogs: MutableList<String> = mutableListOf()

    fun copy(): ConnectionSettings = ConnectionSettings().also {
        it.id = id
        it.name = name
        it.baseUrl = baseUrl
        it.realm = realm
        it.scope = scope
        it.clientId = clientId
        it.s3AccessKeyId = s3AccessKeyId
        it.s3Endpoint = s3Endpoint
        it.s3Addressing = s3Addressing
        it.s3Region = s3Region
        it.namedCatalogs = namedCatalogs.toMutableList()
    }

    fun sameAs(other: ConnectionSettings): Boolean =
        id == other.id && name == other.name && baseUrl == other.baseUrl && realm == other.realm &&
            scope == other.scope && clientId == other.clientId && namedCatalogs == other.namedCatalogs &&
            s3AccessKeyId == other.s3AccessKeyId && s3Endpoint == other.s3Endpoint &&
            s3Addressing == other.s3Addressing && s3Region == other.s3Region

    val displayName: String get() = name.ifBlank { baseUrl }
}

class PolarisSettingsState {
    @XCollection(style = XCollection.Style.v2)
    var connections: MutableList<ConnectionSettings> = mutableListOf()
}

/** Connections, per machine: the URLs and client ids are not roamed between computers. */
@Service(Service.Level.APP)
@State(name = "PolarisCatalogBrowser", storages = [Storage(value = "polarisCatalogBrowser.xml", roamingType = RoamingType.DISABLED)])
class PolarisSettings : PersistentStateComponent<PolarisSettingsState> {
    private var state = PolarisSettingsState()

    override fun getState(): PolarisSettingsState = state

    override fun loadState(state: PolarisSettingsState) {
        this.state = state
    }

    val connections: List<ConnectionSettings> get() = state.connections.map { it.copy() }

    fun connection(id: String): ConnectionSettings? = state.connections.firstOrNull { it.id == id }?.copy()

    /**
     * Replaces every connection and tells listeners which ones changed or went away, plus
     * [secretsChanged]: a new secret changes no setting but must reach the clients and the tree.
     */
    fun replaceAll(connections: List<ConnectionSettings>, secretsChanged: Set<String> = emptySet()) {
        val before = state.connections.associateBy { it.id }
        state.connections = connections.map { it.copy() }.toMutableList()
        val after = state.connections.associateBy { it.id }
        val changed = (before.keys + after.keys).filter { id ->
            val old = before[id]
            val new = after[id]
            old == null || new == null || !old.sameAs(new)
        }.toSet()
        publish(changed + secretsChanged.filter { it in after })
    }

    fun addNamedCatalog(connectionId: String, catalog: String) {
        val connection = state.connections.firstOrNull { it.id == connectionId } ?: return
        if (catalog in connection.namedCatalogs) return
        connection.namedCatalogs.add(catalog)
        connection.namedCatalogs.sort()
        publish(setOf(connectionId), credentialsChanged = false)
    }

    private fun publish(ids: Set<String>, credentialsChanged: Boolean = true) {
        if (ids.isEmpty()) return
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).connectionsChanged(ids, credentialsChanged)
    }

    fun interface Listener {
        /**
         * [ids] were added, changed or removed. [credentialsChanged] is false when only the list of
         * named catalogs changed, so clients and their tokens may be kept.
         */
        fun connectionsChanged(ids: Set<String>, credentialsChanged: Boolean)
    }

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): PolarisSettings = service()
    }
}
