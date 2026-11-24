package io.github.alexey_samodurov.polaris.tree

import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.ui.tree.BaseTreeModel
import com.intellij.util.concurrency.Invoker
import com.intellij.util.concurrency.InvokerSupplier
import java.util.concurrent.ConcurrentHashMap
import javax.swing.tree.TreePath

/**
 * The tree's data, read lazily on a background invoker; wrap it in an `AsyncTreeModel` for the UI.
 *
 * Children of a node are loaded once and kept until [refresh]. Requests to Polaris never run on the
 * EDT; nodes load in parallel, and within one namespace its child namespaces, tables and views do too.
 */
class BrowserTreeModel(source: BrowserSource) : BaseTreeModel<BrowserNode>(), InvokerSupplier {
    private val loader = ChildrenLoader(source)
    // A pool, not one thread: a catalog that does not answer must not hold up every other node.
    private val invoker = Invoker.forBackgroundPoolWithoutReadAction(this)
    private val children = ConcurrentHashMap<BrowserNode, List<BrowserNode>>()

    override fun getInvoker(): Invoker = invoker

    override fun getRoot(): BrowserNode = RootNode

    override fun isLeaf(node: Any?): Boolean = (node as? BrowserNode)?.isLeaf ?: true

    override fun getChildren(parent: Any?): List<BrowserNode> {
        val node = parent as? BrowserNode ?: return emptyList()
        if (node.isLeaf) return emptyList()
        children[node]?.let { return it }
        val loaded = runBlockingMaybeCancellable { loader.children(node) }
        children[node] = loaded
        return loaded
    }

    /** Reloads [path]'s node and everything under it; the whole tree when [path] is null. */
    fun refresh(path: TreePath?) {
        invoker.invoke {
            val node = path?.lastPathComponent as? BrowserNode
            if (node == null || node == RootNode) {
                children.clear()
                treeStructureChanged(null, null, null)
            } else {
                forget(node)
                treeStructureChanged(path, null, null)
            }
        }
    }

    private fun forget(node: BrowserNode) {
        children.remove(node)?.forEach(::forget)
    }
}
