package io.github.alexey_samodurov.polaris.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.treeStructure.Tree
import io.github.alexey_samodurov.polaris.MissingSecretException
import io.github.alexey_samodurov.polaris.PolarisClients
import io.github.alexey_samodurov.polaris.PolarisIcons
import io.github.alexey_samodurov.polaris.PolarisProjectScope
import io.github.alexey_samodurov.polaris.client.PolarisException
import io.github.alexey_samodurov.polaris.client.SnapshotMode
import io.github.alexey_samodurov.polaris.settings.PolarisConfigurable
import io.github.alexey_samodurov.polaris.settings.PolarisSecrets
import io.github.alexey_samodurov.polaris.settings.PolarisSettings
import io.github.alexey_samodurov.polaris.tree.BrowserNode
import io.github.alexey_samodurov.polaris.tree.BrowserTreeModel
import io.github.alexey_samodurov.polaris.tree.CatalogNode
import io.github.alexey_samodurov.polaris.tree.ConnectionNode
import io.github.alexey_samodurov.polaris.tree.MessageNode
import io.github.alexey_samodurov.polaris.tree.NamespaceNode
import io.github.alexey_samodurov.polaris.tree.PolarisBrowserSource
import io.github.alexey_samodurov.polaris.tree.RootNode
import io.github.alexey_samodurov.polaris.tree.TableNode
import io.github.alexey_samodurov.polaris.tree.ViewNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** The tool window's content: the catalog tree above, the selected table or view below. */
class BrowserPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val model = BrowserTreeModel(PolarisBrowserSource())
    private val tree = Tree(AsyncTreeModel(model, this))
    private val details = DetailsPanel()
    private val scope = project.getService(PolarisProjectScope::class.java).scope
    private var detailsJob: Job? = null
    private var historyJob: Job? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree, true) { (it.lastPathComponent as? BrowserNode)?.label ?: it.lastPathComponent.toString() }
        tree.addTreeSelectionListener { showSelection(refresh = false) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(tree)
        tree.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })

        val splitter = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = details
        }
        setContent(splitter)
        toolbar = ActionManager.getInstance().createActionToolbar("PolarisBrowser", actions(), true)
            .also { it.targetComponent = this }.component

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(PolarisSettings.TOPIC, PolarisSettings.Listener { ids, credentialsChanged ->
                if (credentialsChanged) model.refresh(null) else ids.forEach { refreshConnection(it) }
            })
    }

    private fun selected(): BrowserNode? = tree.selectionPath?.lastPathComponent as? BrowserNode

    /** Double-click or Enter: a table opens its data preview in an editor tab; a missing secret is asked for. */
    private fun openSelected(): Boolean = when (val node = selected()) {
        is TableNode -> {
            project.getService(TablePreviewFiles::class.java).open(node)
            true
        }
        is MessageNode -> if (node.kind == MessageNode.Kind.MISSING_SECRET && node.connectionId != null) {
            askForSecret(node.connectionId)
            true
        } else {
            false
        }
        else -> false
    }

    private fun connectionIdOf(node: BrowserNode?): String? = when (node) {
        is ConnectionNode -> node.connectionId
        is CatalogNode -> node.connectionId
        is NamespaceNode -> node.connectionId
        is TableNode -> node.connectionId
        is ViewNode -> node.connectionId
        is MessageNode -> node.connectionId
        else -> null
    }

    private fun refreshConnection(connectionId: String) {
        val settings = PolarisSettings.getInstance().connection(connectionId) ?: return model.refresh(null)
        model.refresh(TreePath(arrayOf<Any>(RootNode, ConnectionNode(settings.id, settings.displayName))))
    }

    private fun showSelection(refresh: Boolean) {
        detailsJob?.cancel()
        historyJob?.cancel()
        when (val node = selected()) {
            is TableNode -> loadTable(node, refresh)
            is ViewNode -> loadView(node)
            else -> details.showMessage("Select a table or a view")
        }
    }

    private fun loadTable(node: TableNode, revalidate: Boolean) {
        val settings = PolarisSettings.getInstance().connection(node.connectionId) ?: return
        details.showMessage("Loading ${node.qualifiedName}...")
        detailsJob = scope.launch {
            val session = PolarisClients.getInstance().get(settings).catalog(node.catalog)
            val loaded = failing(node.qualifiedName) {
                withContext(Dispatchers.IO) { session.loadTable(node.table, SnapshotMode.REFS, revalidate) }
            } ?: return@launch
            withContext(Dispatchers.EDT) {
                details.onFullHistoryWanted = { loadFullHistory(node) }
                val data = DataPreviewPanel(
                    project = project,
                    scope = scope,
                    settings = settings,
                    catalog = node.catalog,
                    table = node.table,
                    history = { session.loadTable(node.table, SnapshotMode.ALL) },
                )
                details.showTable(node.qualifiedName, loaded, data)
            }
        }
    }

    /**
     * Loads the full snapshot history of [node] into its Snapshots tab. Its own job, cancelled with
     * the selection; a failure is reported in that tab only, and only while [node] is still shown.
     */
    private fun loadFullHistory(node: TableNode) {
        val settings = PolarisSettings.getInstance().connection(node.connectionId) ?: return
        historyJob?.cancel()
        historyJob = scope.launch {
            val result = runCatching {
                val session = PolarisClients.getInstance().get(settings).catalog(node.catalog)
                withContext(Dispatchers.IO) { session.loadTable(node.table, SnapshotMode.ALL, revalidate = true) }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) currentCoroutineContext().ensureActive() }
            withContext(Dispatchers.EDT) {
                if (selected() != node) return@withContext
                result.fold(
                    onSuccess = { details.showFullHistory(it) },
                    onFailure = { details.showHistoryError(it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun loadView(node: ViewNode) {
        val settings = PolarisSettings.getInstance().connection(node.connectionId) ?: return
        details.showMessage("Loading ${node.qualifiedName}...")
        detailsJob = scope.launch {
            val session = PolarisClients.getInstance().get(settings).catalog(node.catalog)
            val loaded = failing(node.qualifiedName) { withContext(Dispatchers.IO) { session.loadView(node.view) } } ?: return@launch
            withContext(Dispatchers.EDT) { details.showView(node.qualifiedName, loaded) }
        }
    }

    /** Runs [body]; a failure is shown in the details area and gives `null`. */
    private suspend fun <T> failing(name: String, body: suspend () -> T): T? = try {
        body()
    } catch (e: CancellationException) {
        // Only this job's own cancellation propagates; one thrown by a library is a failure.
        currentCoroutineContext().ensureActive()
        show(name, e)
    } catch (e: Exception) {
        show(name, e)
    }

    private suspend fun show(name: String, e: Exception): Nothing? {
        val text = when (e) {
            is MissingSecretException -> "The client secret is not stored: double-click the connection's message to enter it"
            is PolarisException.NotFound -> "$name no longer exists. Refresh its namespace."
            is IllegalArgumentException -> "Invalid connection settings: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
        withContext(Dispatchers.EDT) { details.showMessage(text) }
        return null
    }

    private fun askForSecret(connectionId: String) {
        val settings = PolarisSettings.getInstance().connection(connectionId) ?: return
        val secret = Messages.showPasswordDialog(
            project,
            "Client secret for ${settings.clientId} at ${settings.baseUrl}:",
            "Polaris Client Secret",
            null,
        )?.takeIf { it.isNotEmpty() } ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { PolarisSecrets.set(connectionId, settings.clientId, secret) }
            PolarisClients.getInstance().rememberForSession(settings, secret)
            withContext(Dispatchers.EDT) { refreshConnection(connectionId) }
        }
    }

    private fun actions() = DefaultActionGroup(
        action("Refresh", "Reload the selected node, or every connection", AllIcons.Actions.Refresh) {
            val path = tree.selectionPath
            when (val node = path?.lastPathComponent as? BrowserNode) {
                is TableNode, is ViewNode -> showSelection(refresh = true)
                is MessageNode -> model.refresh(path.parentPath)
                null -> model.refresh(null)
                else -> {
                    connectionIdOf(node)?.let { id ->
                        PolarisSettings.getInstance().connection(id)?.let { s ->
                            if (node is CatalogNode) PolarisClients.getInstance().get(s).catalog(node.catalog).invalidate()
                        }
                    }
                    model.refresh(path)
                }
            }
        },
        action("Add Catalog by Name", "Browse a catalog this principal may not list", AllIcons.General.Add, enabled = { connectionIdOf(selected()) != null }) {
            val id = connectionIdOf(selected()) ?: return@action
            val name = Messages.showInputDialog(project, "Catalog (warehouse) name:", "Add Catalog by Name", null)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@action
            PolarisSettings.getInstance().addNamedCatalog(id, name)
        },
        action("Open Data Preview", "Open the table's rows in an editor tab (double-click or Enter)", AllIcons.Actions.Preview, enabled = { selected() is TableNode }) {
            (selected() as? TableNode)?.let { project.getService(TablePreviewFiles::class.java).open(it) }
        },
        action("Copy Qualified Name", "Copy catalog.namespace.name as Spark SQL takes it", AllIcons.Actions.Copy, enabled = { selected() is TableNode || selected() is ViewNode }) {
            val name = when (val node = selected()) {
                is TableNode -> node.sqlName
                is ViewNode -> node.sqlName
                else -> return@action
            }
            CopyPasteManager.getInstance().setContents(StringSelection(name))
        },
        action("Settings", "Polaris connections", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PolarisConfigurable::class.java)
        },
    )

    private fun action(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        enabled: () -> Boolean = { true },
        perform: () -> Unit,
    ) = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    override fun dispose() {
        detailsJob?.cancel()
        historyJob?.cancel()
        details.showMessage("")
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? BrowserNode ?: return append(value?.toString() ?: "", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            icon = when (node) {
                is ConnectionNode -> PolarisIcons.Polaris
                is CatalogNode -> AllIcons.Nodes.DataSchema
                is NamespaceNode -> AllIcons.Nodes.Folder
                is TableNode -> AllIcons.Nodes.DataTables
                is ViewNode -> AllIcons.Nodes.Template
                is MessageNode -> when (node.kind) {
                    MessageNode.Kind.INFO -> AllIcons.General.Information
                    MessageNode.Kind.ERROR -> AllIcons.General.Error
                    MessageNode.Kind.MISSING_SECRET -> AllIcons.General.Warning
                }
                RootNode -> null
            }
            val attributes = if (node is MessageNode) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            append(node.label, attributes)
            if (node is ViewNode) append("  view", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
