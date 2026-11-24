package io.github.alexey_samodurov.polaris.tree

import io.github.alexey_samodurov.polaris.client.Namespace
import io.github.alexey_samodurov.polaris.client.TableIdentifier

/**
 * A node of the browser tree. Nodes are values, so a refreshed tree finds the same nodes again and
 * keeps what was expanded and selected.
 */
sealed interface BrowserNode {
    val label: String
    val isLeaf: Boolean get() = false
}

object RootNode : BrowserNode {
    override val label: String = "Polaris"
}

data class ConnectionNode(val connectionId: String, override val label: String) : BrowserNode

data class CatalogNode(val connectionId: String, val catalog: String) : BrowserNode {
    override val label: String get() = catalog
}

data class NamespaceNode(val connectionId: String, val catalog: String, val namespace: Namespace) : BrowserNode {
    override val label: String get() = namespace.name
}

data class TableNode(val connectionId: String, val catalog: String, val table: TableIdentifier) : BrowserNode {
    override val label: String get() = table.name
    override val isLeaf: Boolean get() = true

    /** `catalog.namespace.table`, for display. */
    val qualifiedName: String get() = "$catalog.$table"

    /** The name as Spark SQL takes it: parts that are not plain identifiers are back-quoted. */
    val sqlName: String get() = sqlName(catalog, table.namespace.levels, table.name)
}

data class ViewNode(val connectionId: String, val catalog: String, val view: TableIdentifier) : BrowserNode {
    override val label: String get() = view.name
    override val isLeaf: Boolean get() = true
    val qualifiedName: String get() = "$catalog.$view"
    val sqlName: String get() = sqlName(catalog, view.namespace.levels, view.name)
}

private val PLAIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun sqlName(catalog: String, namespace: List<String>, name: String): String =
    (listOf(catalog) + namespace + name).joinToString(".") { part ->
        if (PLAIN_IDENTIFIER.matches(part)) part else "`" + part.replace("`", "``") + "`"
    }

/**
 * A leaf that says something instead of listing children: empty, an error, a missing secret.
 * [scope] names the parent, so two "Empty" leaves in different places are different nodes.
 */
data class MessageNode(
    val kind: Kind,
    override val label: String,
    val connectionId: String? = null,
    val scope: String = "",
) : BrowserNode {
    override val isLeaf: Boolean get() = true

    enum class Kind { INFO, ERROR, MISSING_SECRET }
}
