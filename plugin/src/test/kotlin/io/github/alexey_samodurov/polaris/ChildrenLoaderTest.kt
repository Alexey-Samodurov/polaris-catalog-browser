package io.github.alexey_samodurov.polaris

import io.github.alexey_samodurov.polaris.client.Namespace
import io.github.alexey_samodurov.polaris.client.PolarisException
import io.github.alexey_samodurov.polaris.client.TableIdentifier
import io.github.alexey_samodurov.polaris.settings.ConnectionSettings
import io.github.alexey_samodurov.polaris.tree.BrowserSource
import io.github.alexey_samodurov.polaris.tree.CatalogNode
import io.github.alexey_samodurov.polaris.tree.ChildrenLoader
import io.github.alexey_samodurov.polaris.tree.ConnectionNode
import io.github.alexey_samodurov.polaris.tree.MessageNode
import io.github.alexey_samodurov.polaris.tree.NOT_PERMITTED_TO_LIST
import io.github.alexey_samodurov.polaris.tree.NamespaceNode
import io.github.alexey_samodurov.polaris.tree.RootNode
import io.github.alexey_samodurov.polaris.tree.TableNode
import io.github.alexey_samodurov.polaris.tree.ViewNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChildrenLoaderTest {
    private val connection = ConnectionSettings().apply {
        id = "c1"
        name = "local"
        baseUrl = "https://polaris.example"
        clientId = "reader"
        namedCatalogs = mutableListOf("bronze")
    }

    private open inner class FakeSource : BrowserSource {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        override fun connections() = listOf(connection)

        override fun connection(id: String) = connection.takeIf { it.id == id }

        override suspend fun listCatalogs(connection: ConnectionSettings): List<String> = listOf("lakehouse", "bronze")

        override suspend fun listNamespaces(connection: ConnectionSettings, catalog: String, parent: Namespace?): List<Namespace> =
            tracked { if (parent == null) listOf(Namespace.of("a")) else listOf(Namespace.of("a", "b")) }

        override suspend fun listTables(connection: ConnectionSettings, catalog: String, namespace: Namespace): List<TableIdentifier> =
            tracked { listOf(TableIdentifier(namespace, "events")) }

        override suspend fun listViews(connection: ConnectionSettings, catalog: String, namespace: Namespace): List<TableIdentifier> =
            tracked { listOf(TableIdentifier(namespace, "recent")) }

        suspend fun <T> tracked(body: () -> T): T {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            delay(100)
            inFlight.decrementAndGet()
            return body()
        }
    }

    @Test
    fun `listed and named catalogs are merged without duplicates`() = runBlocking {
        val children = ChildrenLoader(FakeSource()).children(ConnectionNode("c1", "local"))
        assertEquals(listOf(CatalogNode("c1", "bronze"), CatalogNode("c1", "lakehouse")), children)
    }

    @Test
    fun `a principal that may not list catalogs sees its named ones and why`() = runBlocking {
        val source = object : FakeSource() {
            override suspend fun listCatalogs(connection: ConnectionSettings): List<String> =
                throw PolarisException.Forbidden("GET /api/management/v1/catalogs: 403")
        }
        val children = ChildrenLoader(source).children(ConnectionNode("c1", "local"))
        assertEquals(CatalogNode("c1", "bronze"), children[0])
        val message = children[1] as MessageNode
        assertEquals(MessageNode.Kind.INFO, message.kind)
        assertEquals(NOT_PERMITTED_TO_LIST, message.label)
    }

    @Test
    fun `a namespace loads its three lists in parallel`() = runBlocking {
        val source = FakeSource()
        val children = ChildrenLoader(source).children(NamespaceNode("c1", "bronze", Namespace.of("a")))
        assertEquals(
            listOf(
                NamespaceNode("c1", "bronze", Namespace.of("a", "b")),
                TableNode("c1", "bronze", TableIdentifier(Namespace.of("a"), "events")),
                ViewNode("c1", "bronze", TableIdentifier(Namespace.of("a"), "recent")),
            ),
            children,
        )
        assertEquals(3, source.maxInFlight.get())
    }

    @Test
    fun `a failing list is reported in place and the others still show`() = runBlocking {
        val source = object : FakeSource() {
            override suspend fun listTables(connection: ConnectionSettings, catalog: String, namespace: Namespace): List<TableIdentifier> =
                throw PolarisException.Server(500, "GET tables: 500")
        }
        val children = ChildrenLoader(source).children(NamespaceNode("c1", "bronze", Namespace.of("a")))
        assertTrue(children.any { it is NamespaceNode })
        assertTrue(children.any { it is ViewNode })
        assertTrue(children.any { it is MessageNode && it.kind == MessageNode.Kind.ERROR && it.label.contains("500") })
    }

    @Test
    fun `a failure that is not Polaris' own is still reported in place`() = runBlocking {
        val source = object : FakeSource() {
            override suspend fun listViews(connection: ConnectionSettings, catalog: String, namespace: Namespace): List<TableIdentifier> =
                throw IllegalStateException("parser broke")
        }
        val children = ChildrenLoader(source).children(NamespaceNode("c1", "bronze", Namespace.of("a")))
        assertTrue(children.any { it is NamespaceNode })
        assertTrue(children.any { it is TableNode })
        assertTrue(children.any { it is MessageNode && it.label == "parser broke" })
    }

    @Test
    fun `a CancellationException a library throws is a failure, not a cancellation`() = runBlocking {
        val source = object : FakeSource() {
            override suspend fun listCatalogs(connection: ConnectionSettings): List<String> =
                throw java.util.concurrent.CancellationException("future cancelled")
        }
        val message = ChildrenLoader(source).children(ConnectionNode("c1", "local")).single() as MessageNode
        assertEquals(MessageNode.Kind.ERROR, message.kind)
    }

    @Test
    fun `qualified names are quoted for Spark where a part is not a plain identifier`() {
        val node = TableNode("c1", "bronze", TableIdentifier(Namespace.of("ingest", "raw-events"), "orders"))
        assertEquals("bronze.ingest.`raw-events`.orders", node.sqlName)
        assertEquals("bronze.ingest.raw-events.orders", node.qualifiedName)
    }

    @Test
    fun `a missing secret asks for it instead of failing`() = runBlocking {
        val source = object : FakeSource() {
            override suspend fun listCatalogs(connection: ConnectionSettings): List<String> = throw MissingSecretException("c1")
        }
        val message = ChildrenLoader(source).children(ConnectionNode("c1", "local")).single() as MessageNode
        assertEquals(MessageNode.Kind.MISSING_SECRET, message.kind)
        assertEquals("c1", message.connectionId)
    }

    @Test
    fun `empty lists in different places are different nodes`() = runBlocking {
        val source = object : FakeSource() {
            override suspend fun listNamespaces(connection: ConnectionSettings, catalog: String, parent: Namespace?) = emptyList<Namespace>()
        }
        val loader = ChildrenLoader(source)
        val a = loader.children(CatalogNode("c1", "bronze")).single()
        val b = loader.children(CatalogNode("c1", "lakehouse")).single()
        assertEquals("Empty", (a as MessageNode).label)
        assertNotEquals(a, b)
    }

    @Test
    fun `the root lists connections`() = runBlocking {
        assertEquals(listOf(ConnectionNode("c1", "local")), ChildrenLoader(FakeSource()).children(RootNode))
    }
}
