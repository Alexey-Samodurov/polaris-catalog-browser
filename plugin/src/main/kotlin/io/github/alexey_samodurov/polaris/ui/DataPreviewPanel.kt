package io.github.alexey_samodurov.polaris.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.alexey_samodurov.polaris.PolarisClients
import io.github.alexey_samodurov.polaris.client.LoadedTable
import io.github.alexey_samodurov.polaris.client.TableIdentifier
import io.github.alexey_samodurov.polaris.preview.ColumnFilter
import io.github.alexey_samodurov.polaris.preview.FilterOp
import io.github.alexey_samodurov.polaris.preview.MAX_PREVIEW_ROWS
import io.github.alexey_samodurov.polaris.preview.PREVIEW_PAGE
import io.github.alexey_samodurov.polaris.preview.PreviewColumn
import io.github.alexey_samodurov.polaris.preview.PreviewRequest
import io.github.alexey_samodurov.polaris.preview.PreviewResult
import io.github.alexey_samodurov.polaris.preview.PreviewRows
import io.github.alexey_samodurov.polaris.preview.PreviewSession
import io.github.alexey_samodurov.polaris.preview.PreviewTableInfo
import io.github.alexey_samodurov.polaris.preview.SnapshotSelector
import io.github.alexey_samodurov.polaris.settings.ConnectionSettings
import io.github.alexey_samodurov.polaris.settings.PolarisSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The Data tab: rows of one table, read through a [PreviewSession] so "Load More" continues the
 * same scan. Nothing is requested until [activate] is called, when the tab is first opened; the
 * choices (columns, partition columns, refs, history) come from the table's full metadata, which
 * [history] loads through the browser's own client and cache.
 */
class DataPreviewPanel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val settings: ConnectionSettings,
    private val catalog: String,
    private val table: TableIdentifier,
    private val history: suspend () -> LoadedTable,
) : JPanel(BorderLayout()), Disposable {
    private val snapshotBox = ComboBox<Any>().apply { isEditable = true; toolTipText = "Current, a branch or tag, or a snapshot id" }
    private val columnsButton = JButton("Columns").apply { icon = AllIcons.General.Filter }
    private val runButton = JButton("Run", AllIcons.Actions.Execute)
    private val moreButton = JButton("Load More").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel", AllIcons.Actions.Suspend).apply { isEnabled = false }
    private val copyButton = JButton("Copy", AllIcons.Actions.Copy).apply { toolTipText = "Copy the selected rows, or all, with the header" }
    private val secretButton = JButton("Enter S3 Secret...").apply { isVisible = false }
    private val filtersPanel = JPanel(GridBagLayout())
    private val status = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground() }
    private val view = PreviewGrid()
    private val model = view.model
    private val grid = view.table

    private var info: PreviewTableInfo? = null

    /** `null`: every column, so each snapshot reads the columns of its own schema. */
    private var chosenColumns: List<String>? = null
    private val filterRows = mutableListOf<FilterRow>()
    private var session: PreviewSession? = null
    private var job: Job? = null
    private var loadingInfo = false

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("At:"))
            add(snapshotBox)
            add(columnsButton)
            add(runButton)
            add(moreButton)
            add(cancelButton)
            add(copyButton)
        }
        val north = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(filtersPanel, BorderLayout.CENTER)
        }
        val south = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(status)
            add(secretButton)
        }
        add(north, BorderLayout.NORTH)
        add(view, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        runButton.addActionListener { if (info == null) activate() else run() }
        moreButton.addActionListener { more() }
        cancelButton.addActionListener { cancel() }
        copyButton.addActionListener { copy() }
        columnsButton.addActionListener { chooseColumns() }
        secretButton.addActionListener { askForS3Secret() }
        setControlsEnabled(false)
        runButton.isEnabled = true
        status.text = "Open this tab to read rows"
    }

    /**
     * Called when the tab is shown: loads the choices and reads the first page. Does nothing while
     * that is under way or once it succeeded; after a failure, the next call (or Run) tries again.
     */
    fun activate() {
        if (loadingInfo || info != null) return
        loadingInfo = true
        busy("Reading the table's columns and history...")
        job = scope.launch {
            val loaded = attempt { withContext(Dispatchers.IO) { PreviewTableInfo.of(history().metadata) } }
            withContext(Dispatchers.EDT) {
                loadingInfo = false
                idle()
                if (loaded != null) {
                    setUp(loaded)
                    run()
                } else {
                    runButton.isEnabled = true
                }
            }
        }
    }

    /** Runs [body]; a failure, a library's stray CancellationException included, is shown and gives `null`. */
    private suspend fun <T> attempt(body: suspend () -> T): T? = try {
        body()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        report(e)
        null
    } catch (e: Exception) {
        report(e)
        null
    }

    private suspend fun report(e: Throwable) = withContext(Dispatchers.EDT) {
        status.text = e.message ?: e.javaClass.simpleName
    }

    private fun setUp(value: PreviewTableInfo) {
        info = value
        chosenColumns = null
        snapshotBox.removeAllItems()
        snapshotBox.addItem(Choice("Current", SnapshotSelector.Current))
        value.refs.forEach { (name, id) -> snapshotBox.addItem(Choice("$name ($id)", SnapshotSelector.Ref(name))) }
        value.snapshots
            .filter { s -> s.snapshotId !in value.refs.values }
            .forEach { s -> snapshotBox.addItem(Choice("${s.snapshotId}  ${Formats.time(s.timestampMs)}", SnapshotSelector.Snapshot(s.snapshotId))) }
        snapshotBox.selectedIndex = 0

        filterRows.clear()
        filtersPanel.removeAll()
        value.partitionSourceColumns.forEach { addFilterRow(it) }
        updateColumnsLabel()
        setControlsEnabled(true)
        revalidate()
        repaint()
    }

    /** One condition on a partition source column; "+" adds another on the same column, for a range. */
    private fun addFilterRow(column: PreviewColumn) {
        val op = ComboBox(FilterOp.entries.toTypedArray()).apply { selectedItem = FilterOp.EQ }
        val value = JBTextField(24).apply { emptyText.text = example(column.type) }
        val more = JButton(AllIcons.General.Add).apply {
            toolTipText = "Another condition on ${column.name}"
            addActionListener { addFilterRow(column) }
        }
        val row = filterRows.size
        val c = GridBagConstraints().apply { gridy = row; insets = JBUI.insets(1, 2); anchor = GridBagConstraints.WEST }
        filtersPanel.add(JBLabel("${column.name}:"), c.apply { gridx = 0 })
        filtersPanel.add(op, c.apply { gridx = 1 })
        filtersPanel.add(value, c.apply { gridx = 2; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 })
        filtersPanel.add(more, c.apply { gridx = 3; fill = GridBagConstraints.NONE; weightx = 0.0 })
        filterRows += FilterRow(column, op, value)
        filtersPanel.border = JBUI.Borders.empty(0, 6, 4, 6)
        filtersPanel.revalidate()
        filtersPanel.repaint()
    }

    private fun example(type: String): String = when {
        type == "timestamptz" || type == "timestamptz_ns" -> "2026-11-02T00:00:00+00:00"
        type.startsWith("timestamp") -> "2026-11-02T00:00:00"
        type == "date" -> "2026-11-02"
        else -> "$type; in: comma-separated"
    } + "  (partition filter)"

    private fun selector(): SnapshotSelector? = when (val item = snapshotBox.selectedItem) {
        is Choice -> item.selector
        is String -> item.trim().let { text ->
            if (text.isEmpty() || text.equals("current", ignoreCase = true)) {
                SnapshotSelector.Current
            } else {
                text.toLongOrNull()?.let { SnapshotSelector.Snapshot(it) }
                    ?: info?.refs?.keys?.firstOrNull { it == text }?.let { SnapshotSelector.Ref(it) }
            }
        }
        else -> SnapshotSelector.Current
    }

    private fun filters(): List<ColumnFilter> = filterRows.mapNotNull { r ->
        val text = r.value.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val op = r.op.selectedItem as FilterOp
        val values = if (op == FilterOp.IN) text.split(',').map { it.trim() }.filter { it.isNotEmpty() } else listOf(text)
        ColumnFilter(r.column.name, op, values)
    }

    private fun run() {
        val selector = selector() ?: run {
            status.text = "Not a snapshot id, branch or tag: ${snapshotBox.selectedItem}"
            return
        }
        job?.cancel()
        closeSession()
        val request = PreviewRequest(selector = selector, columns = chosenColumns.orEmpty(), filters = filters(), limit = PREVIEW_PAGE)
        model.reset(emptyList())
        secretButton.isVisible = false
        busy("Reading...")
        job = scope.launch {
            val clients = PolarisClients.getInstance()
            val lacksSecret = attempt { withContext(Dispatchers.IO) { clients.lacksS3Secret(settings) } }
            if (lacksSecret == true) {
                withContext(Dispatchers.EDT) {
                    idle()
                    status.text = "The S3 secret key for ${settings.s3AccessKeyId} is not stored."
                    secretButton.isVisible = true
                }
                return@launch
            }
            val first = attempt { readFirstPage(clients, request) }
            withContext(Dispatchers.EDT) {
                if (first != null) show(first, append = false) else idle()
            }
        }
    }

    private suspend fun readFirstPage(clients: PolarisClients, request: PreviewRequest): PreviewResult<PreviewRows> =
        when (val opened = clients.preview(settings).open(catalog, table, request)) {
            is PreviewResult.Ok -> {
                val s = opened.value
                try {
                    withContext(Dispatchers.EDT) { session = s }
                    s.next(PREVIEW_PAGE)
                } catch (e: Throwable) {
                    // Cancelled or failed between opening and the first page: release the keys now.
                    s.close()
                    throw e
                }
            }
            is PreviewResult.NoPermission -> opened
            is PreviewResult.Failed -> opened
        }

    private fun more() {
        val s = session?.takeUnless { it.isClosed } ?: return
        busy("Reading more...")
        job = scope.launch {
            val page = attempt { s.next(PREVIEW_PAGE) }
            withContext(Dispatchers.EDT) { if (page != null) show(page, append = true) else idle() }
        }
    }

    private fun show(result: PreviewResult<PreviewRows>, append: Boolean) {
        idle()
        when (result) {
            is PreviewResult.Ok -> {
                val rows = result.value
                if (append) model.append(rows.rows) else model.reset(rows.columns, rows.rows)
                moreButton.isEnabled = rows.hasMore
                val end = when {
                    rows.hasMore -> ""
                    model.rowCount >= MAX_PREVIEW_ROWS -> " · limit of $MAX_PREVIEW_ROWS rows reached"
                    else -> " · all rows"
                }
                status.text = "${model.rowCount} rows · snapshot ${rows.snapshotId ?: "none (empty table)"} · ${rows.elapsedMillis} ms$end"
            }
            is PreviewResult.NoPermission -> fail(result.message)
            is PreviewResult.Failed -> fail(result.message)
        }
    }

    private fun fail(message: String) {
        closeSession()
        moreButton.isEnabled = false
        status.text = message
    }

    private fun cancel() {
        job?.cancel()
        closeSession()
        loadingInfo = false
        idle()
        if (info == null) runButton.isEnabled = true
        moreButton.isEnabled = false
        status.text = "Cancelled"
    }

    private fun busy(text: String) {
        status.text = text
        setControlsEnabled(false)
        runButton.isEnabled = false
        moreButton.isEnabled = false
        cancelButton.isEnabled = true
    }

    private fun idle() {
        setControlsEnabled(true)
        cancelButton.isEnabled = false
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val ready = enabled && info != null
        listOf(snapshotBox, columnsButton, runButton, copyButton).forEach { it.isEnabled = ready }
        filterRows.forEach { it.op.isEnabled = ready; it.value.isEnabled = ready }
    }

    /** Asks for the S3 secret key; stored in the password safe, and kept for this session in any case. */
    private fun askForS3Secret() {
        val secret = Messages.showPasswordDialog(
            project,
            "S3 secret access key for ${settings.s3AccessKeyId}:",
            "S3 Secret Key",
            null,
        )?.takeIf { it.isNotEmpty() } ?: return
        val remember = Messages.showYesNoDialog(
            project,
            "Store the key in the password safe? Choose No to keep it only until the IDE is closed.",
            "S3 Secret Key",
            null,
        ) == Messages.YES
        scope.launch {
            attempt {
                withContext(Dispatchers.IO) {
                    if (remember) PolarisSecrets.setS3(settings.id, settings.s3AccessKeyId.trim(), secret)
                    PolarisClients.getInstance().rememberS3ForSession(settings, secret)
                }
            }
            withContext(Dispatchers.EDT) { run() }
        }
    }

    /** A checklist of the columns, with "All" on top to tick or clear every one at once. */
    private fun chooseColumns() {
        val all = info?.columns ?: return
        val chosen = chosenColumns ?: all.map { it.name }
        val list = CheckBoxList<String>()
        all.forEach { list.addItem(it.name, "${it.name}: ${it.type}", it.name in chosen) }
        val allBox = JBCheckBox("All", chosen.size == all.size)
        var syncing = false
        allBox.addActionListener {
            if (syncing) return@addActionListener
            syncing = true
            all.forEach { list.setItemSelected(it.name, allBox.isSelected) }
            list.repaint()
            syncing = false
        }
        list.setCheckBoxListListener { _, _ ->
            if (!syncing) {
                syncing = true
                allBox.isSelected = all.all { list.isItemSelected(it.name) }
                syncing = false
            }
        }
        val content = JPanel(BorderLayout()).apply {
            add(allBox.apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        JBPopupFactory.getInstance().createComponentPopupBuilder(content, list)
            .setTitle("Columns to Read")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    val picked = all.map { it.name }.filter { list.isItemSelected(it) }
                    // All columns (or none) mean "every column of the snapshot read", not this list.
                    chosenColumns = picked.takeIf { it.isNotEmpty() && it.size < all.size }
                    updateColumnsLabel()
                }
            })
            .createPopup()
            .showUnderneathOf(columnsButton)
    }

    private fun updateColumnsLabel() {
        val total = info?.columns?.size ?: 0
        columnsButton.text = "Columns (${chosenColumns?.size ?: total}/$total)"
    }

    private fun copy() {
        val rows = grid.selectedRows.takeIf { it.isNotEmpty() }?.map { grid.convertRowIndexToModel(it) } ?: (0 until model.rowCount).toList()
        val header = model.columns.joinToString("\t") { it.name }
        val body = rows.joinToString("\n") { r -> model.row(r).joinToString("\t") { it ?: "" } }
        CopyPasteManager.getInstance().setContents(StringSelection(if (body.isEmpty()) header else "$header\n$body"))
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        scope.launch(Dispatchers.IO) { s.close() }
    }

    override fun dispose() {
        job?.cancel()
        closeSession()
    }

    private data class Choice(val label: String, val selector: SnapshotSelector) {
        override fun toString(): String = label
    }

    private class FilterRow(val column: PreviewColumn, val op: ComboBox<FilterOp>, val value: JBTextField)
}
