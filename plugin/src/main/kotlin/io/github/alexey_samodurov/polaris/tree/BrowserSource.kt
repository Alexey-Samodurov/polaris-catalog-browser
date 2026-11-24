package io.github.alexey_samodurov.polaris.tree

import io.github.alexey_samodurov.polaris.MissingSecretException
import io.github.alexey_samodurov.polaris.PolarisClients
import io.github.alexey_samodurov.polaris.client.Namespace
import io.github.alexey_samodurov.polaris.client.PolarisException
import io.github.alexey_samodurov.polaris.client.TableIdentifier
import io.github.alexey_samodurov.polaris.settings.ConnectionSettings
import io.github.alexey_samodurov.polaris.settings.PolarisSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What the tree reads; the plugin uses Polaris, tests use a fake. */
interface BrowserSource {
    fun connections(): List<ConnectionSettings>

    fun connection(id: String): ConnectionSettings?

    suspend fun listCatalogs(connection: ConnectionSettings): List<String>

    suspend fun listNamespaces(connection: ConnectionSettings, catalog: String, parent: Namespace?): List<Namespace>

    suspend fun listTables(connection: ConnectionSettings, catalog: String, namespace: Namespace): List<TableIdentifier>

    suspend fun listViews(connection: ConnectionSettings, catalog: String, namespace: Namespace): List<TableIdentifier>
}

class PolarisBrowserSource : BrowserSource {
    private fun client(c: ConnectionSettings) = PolarisClients.getInstance().get(c)

    override fun connections(): List<ConnectionSettings> = PolarisSettings.getInstance().connections

    override fun connection(id: String): ConnectionSettings? = PolarisSettings.getInstance().connection(id)

    override suspend fun listCatalogs(connection: ConnectionSettings): List<String> =
        client(connection).listCatalogs().map { it.name }

    override suspend fun listNamespaces(connection: ConnectionSettings, catalog: String, parent: Namespace?) =
        client(connection).catalog(catalog).listNamespaces(parent)

    override suspend fun listTables(connection: ConnectionSettings, catalog: String, namespace: Namespace) =
        client(connection).catalog(catalog).listTables(namespace)

    override suspend fun listViews(connection: ConnectionSettings, catalog: String, namespace: Namespace) =
        client(connection).catalog(catalog).listViews(namespace)
}

const val NOT_PERMITTED_TO_LIST =
    "Not permitted to list catalogs: use Add Catalog by Name"

/**
 * Children of a node. Failures become [MessageNode]s, so a node that cannot load still shows why,
 * and a namespace whose tables fail still shows its child namespaces.
 */
class ChildrenLoader(private val source: BrowserSource) {
    suspend fun children(node: BrowserNode): List<BrowserNode> =
        load(node).map { if (it is MessageNode) it.copy(scope = node.toString()) else it }

    private suspend fun load(node: BrowserNode): List<BrowserNode> = when (node) {
        RootNode -> source.connections().map { ConnectionNode(it.id, it.displayName) }
            .ifEmpty { listOf(MessageNode(MessageNode.Kind.INFO, "No connections: add one in Settings | Tools | Polaris")) }
        is ConnectionNode -> withConnection(node.connectionId) { connectionChildren(it) }
        is CatalogNode -> withConnection(node.connectionId) { c ->
            guarded(c) {
                source.listNamespaces(c, node.catalog, null).map { NamespaceNode(c.id, node.catalog, it) }.orEmptyMessage()
            }
        }
        is NamespaceNode -> withConnection(node.connectionId) { namespaceChildren(it, node) }
        is TableNode, is ViewNode, is MessageNode -> emptyList()
    }

    private suspend fun withConnection(id: String, body: suspend (ConnectionSettings) -> List<BrowserNode>): List<BrowserNode> {
        val connection = source.connection(id) ?: return listOf(MessageNode(MessageNode.Kind.ERROR, "Connection was removed"))
        return body(connection)
    }

    private suspend fun connectionChildren(c: ConnectionSettings): List<BrowserNode> = guarded(c) {
        val named = c.namedCatalogs.map { CatalogNode(c.id, it) }
        try {
            val listed = source.listCatalogs(c).map { CatalogNode(c.id, it) }
            (listed + named).distinct().sortedBy { it.catalog }.orEmptyMessage()
        } catch (e: PolarisException.Forbidden) {
            named + MessageNode(MessageNode.Kind.INFO, NOT_PERMITTED_TO_LIST, c.id)
        }
    }

    private suspend fun namespaceChildren(c: ConnectionSettings, node: NamespaceNode): List<BrowserNode> = guarded(c) {
        coroutineScope {
            val namespaces = async { part { source.listNamespaces(c, node.catalog, node.namespace).map { NamespaceNode(c.id, node.catalog, it) } } }
            val tables = async { part { source.listTables(c, node.catalog, node.namespace).map { TableNode(c.id, node.catalog, it) } } }
            val views = async { part { source.listViews(c, node.catalog, node.namespace).map { ViewNode(c.id, node.catalog, it) } } }
            (namespaces.await() + tables.await() + views.await()).orEmptyMessage()
        }
    }

    /**
     * One list of a namespace; any failure of it is reported in place and the others still show.
     * A missing secret is the connection's problem, not this list's, and goes up.
     */
    private suspend fun part(body: suspend () -> List<BrowserNode>): List<BrowserNode> = try {
        body()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        listOf(errorNode(e))
    } catch (e: MissingSecretException) {
        throw e
    } catch (e: Exception) {
        listOf(errorNode(e))
    }

    private fun errorNode(e: Throwable) = MessageNode(MessageNode.Kind.ERROR, e.message ?: e.javaClass.simpleName)

    private suspend fun guarded(c: ConnectionSettings, body: suspend () -> List<BrowserNode>): List<BrowserNode> = try {
        body()
    } catch (e: CancellationException) {
        // Only this load's own cancellation propagates; one thrown by a library is a failure.
        currentCoroutineContext().ensureActive()
        listOf(errorNode(e))
    } catch (e: MissingSecretException) {
        listOf(MessageNode(MessageNode.Kind.MISSING_SECRET, "Client secret not stored: double-click to enter it", c.id))
    } catch (e: PolarisException) {
        listOf(MessageNode(MessageNode.Kind.ERROR, e.message ?: e.javaClass.simpleName))
    } catch (e: IllegalArgumentException) {
        listOf(MessageNode(MessageNode.Kind.ERROR, "Invalid connection settings: ${e.message}"))
    } catch (e: Exception) {
        listOf(errorNode(e))
    }

    private fun List<BrowserNode>.orEmptyMessage(): List<BrowserNode> =
        ifEmpty { listOf(MessageNode(MessageNode.Kind.INFO, "Empty")) }
}
