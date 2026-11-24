package io.github.alexey_samodurov.polaris.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import io.github.alexey_samodurov.polaris.PolarisClients
import io.github.alexey_samodurov.polaris.PolarisProjectScope
import io.github.alexey_samodurov.polaris.client.SnapshotMode
import io.github.alexey_samodurov.polaris.client.TableIdentifier
import io.github.alexey_samodurov.polaris.settings.PolarisSettings
import io.github.alexey_samodurov.polaris.tree.TableNode
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * A table's data preview as an editor tab, the way Big Data Tools opens a file. Nothing is on disk:
 * the file only names the table, and the tab does not survive a restart.
 */
class TablePreviewFile(val connectionId: String, val catalog: String, val table: TableIdentifier, name: String) :
    LightVirtualFile(name) {
    init {
        isWritable = false
    }
}

/** One preview file per table and project, so opening a table again selects its tab. */
@Service(Service.Level.PROJECT)
class TablePreviewFiles(private val project: Project) {
    private val files = ConcurrentHashMap<Triple<String, String, TableIdentifier>, TablePreviewFile>()

    fun open(node: TableNode) {
        val file = files.computeIfAbsent(Triple(node.connectionId, node.catalog, node.table)) {
            TablePreviewFile(node.connectionId, node.catalog, node.table, node.qualifiedName)
        }
        OpenFileDescriptor(project, file).navigate(true)
    }
}

class TablePreviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is TablePreviewFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TablePreviewEditor(project, file as TablePreviewFile)

    override fun getEditorTypeId(): String = "io.github.alexey_samodurov.polaris.table-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** The tab's content: the same Data panel as in the table's details, started right away. */
class TablePreviewEditor(project: Project, private val file: TablePreviewFile) : UserDataHolderBase(), FileEditor {
    private val component: JComponent

    init {
        val settings = PolarisSettings.getInstance().connection(file.connectionId)
        if (settings == null) {
            component = JBLabel("The connection of this table was removed", SwingConstants.CENTER)
        } else {
            val data = DataPreviewPanel(
                project = project,
                scope = project.getService(PolarisProjectScope::class.java).scope,
                settings = settings,
                catalog = file.catalog,
                table = file.table,
                history = { PolarisClients.getInstance().get(settings).catalog(file.catalog).loadTable(file.table, SnapshotMode.ALL) },
            )
            Disposer.register(this, data)
            data.activate()
            component = data
        }
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent? = null

    override fun getName(): String = "Data"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit
}

/** The table icon on the preview's tab. */
class TablePreviewIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?) =
        if (file is TablePreviewFile) AllIcons.Nodes.DataTables else null
}
