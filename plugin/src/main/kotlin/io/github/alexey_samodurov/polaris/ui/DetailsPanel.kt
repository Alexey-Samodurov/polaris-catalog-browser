package io.github.alexey_samodurov.polaris.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.alexey_samodurov.polaris.client.IcebergType
import io.github.alexey_samodurov.polaris.client.LoadedTable
import io.github.alexey_samodurov.polaris.client.LoadedView
import io.github.alexey_samodurov.polaris.client.NestedField
import io.github.alexey_samodurov.polaris.client.Schema
import io.github.alexey_samodurov.polaris.client.Section
import io.github.alexey_samodurov.polaris.client.SnapshotMode
import io.github.alexey_samodurov.polaris.client.TableMetadata
import io.github.alexey_samodurov.polaris.client.maskProperties
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * Shows one table or view. The Snapshots tab asks for the full history only when it is first
 * opened, through [onFullHistoryWanted]; until then it shows the snapshots branches and tags hold.
 */
class DetailsPanel : JPanel(CardLayout()) {
    private val message = JBLabel("", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    private val tabs = JBTabbedPane()
    private var snapshotsTabIndex = -1
    private var fullHistoryRequested = false
    private var historyComplete = false

    /** Called once per table when the Snapshots tab is opened. */
    var onFullHistoryWanted: (() -> Unit)? = null

    /** The Data tab of the table shown, disposed when something else is shown. */
    private var data: DataPreviewPanel? = null

    init {
        add(message, CARD_MESSAGE)
        add(tabs, CARD_TABS)
        tabs.addChangeListener {
            val shown = data
            if (shown != null && tabs.selectedComponent === shown) shown.activate()
            requestHistoryIfShown()
        }
        showMessage("Select a table or a view")
    }

    /** Asks for the full history once per table, when the Snapshots tab is the one shown. */
    private fun requestHistoryIfShown() {
        if (snapshotsTabIndex < 0 || tabs.selectedIndex != snapshotsTabIndex) return
        if (fullHistoryRequested || historyComplete) return
        fullHistoryRequested = true
        onFullHistoryWanted?.invoke()
    }

    private fun dropData() {
        data?.let { com.intellij.openapi.util.Disposer.dispose(it) }
        data = null
    }

    fun showMessage(text: String) {
        dropData()
        message.text = text
        (layout as CardLayout).show(this, CARD_MESSAGE)
    }

    fun showTable(qualifiedName: String, loaded: LoadedTable, dataPanel: DataPreviewPanel? = null) {
        val keep = tabs.selectedIndex
        dropData()
        // No history request while the tabs are rebuilt; the one below decides.
        snapshotsTabIndex = -1
        tabs.removeAll()
        fullHistoryRequested = false
        historyComplete = loaded.mode == SnapshotMode.ALL
        val m = loaded.metadata
        tabs.addTab("Overview", keyValues(tableOverview(qualifiedName, loaded)))
        tabs.addTab("Schema", sectionOr(m.schemas) { schemaTable(m.currentSchema) })
        tabs.addTab("Partition spec", sectionOr(m.partitionSpecs) { partitionTable(m) })
        tabs.addTab("Sort order", sectionOr(m.sortOrders) { sortTable(m) })
        tabs.addTab("Properties", sectionOr(m.properties) { keyValues(maskProperties(it).toSortedMap().toList()) })
        tabs.addTab("Snapshots", sectionOr(m.snapshots) { snapshotTable(m, complete = historyComplete) })
        val snapshotsIndex = tabs.tabCount - 1
        if (dataPanel != null) {
            data = dataPanel
            tabs.addTab("Data", dataPanel)
        }
        if (keep in 0 until tabs.tabCount) tabs.selectedIndex = keep
        snapshotsTabIndex = snapshotsIndex
        (layout as CardLayout).show(this, CARD_TABS)
        if (dataPanel != null && tabs.selectedComponent === dataPanel) dataPanel.activate()
        // A table opened on the Snapshots tab needs its history straight away; exactly one request.
        requestHistoryIfShown()
    }

    /** Replaces the Snapshots tab with the full history, keeping the rest. */
    fun showFullHistory(loaded: LoadedTable) {
        if (snapshotsTabIndex !in 0 until tabs.tabCount) return
        val m = loaded.metadata
        tabs.setComponentAt(snapshotsTabIndex, sectionOr(m.snapshots) { snapshotTable(m, complete = true) })
        historyComplete = true
    }

    /** The full history could not be loaded: says so in the Snapshots tab only, the rest stays. */
    fun showHistoryError(text: String) {
        if (snapshotsTabIndex !in 0 until tabs.tabCount) return
        tabs.setComponentAt(snapshotsTabIndex, text("The full history could not be loaded: $text"))
        fullHistoryRequested = false
    }

    fun showView(qualifiedName: String, loaded: LoadedView) {
        dropData()
        snapshotsTabIndex = -1
        tabs.removeAll()
        val m = loaded.metadata
        val version = m.currentVersion
        tabs.addTab(
            "Overview",
            keyValues(
                listOf(
                    "Name" to qualifiedName,
                    "Metadata location" to (loaded.metadataLocation ?: ""),
                    "Location" to (m.location ?: ""),
                    "Format version" to (m.formatVersion?.toString() ?: ""),
                    "View UUID" to (m.viewUuid ?: ""),
                    "Current version" to (m.currentVersionId?.toString() ?: ""),
                    "Version created" to time(version?.timestampMs),
                    "Default namespace" to version?.defaultNamespace.orEmpty().joinToString("."),
                ) + version?.summary.orEmpty().toSortedMap().map { (k, v) -> "Summary: $k" to v },
            ),
        )
        tabs.addTab("Schema", sectionOr(m.schemas) { schemaTable(m.currentSchema) })
        val sql = version?.representations.orEmpty()
        if (sql.isEmpty()) {
            tabs.addTab("SQL", text("No SQL representation"))
        } else {
            sql.forEach { tabs.addTab("SQL (${it.dialect ?: it.type})", text(it.sql ?: "")) }
        }
        tabs.addTab("Properties", sectionOr(m.properties) { keyValues(maskProperties(it).toSortedMap().toList()) })
        (layout as CardLayout).show(this, CARD_TABS)
    }

    private fun tableOverview(name: String, loaded: LoadedTable): List<Pair<String, String>> {
        val m = loaded.metadata
        return listOf(
            "Name" to name,
            "Metadata location" to (loaded.metadataLocation ?: ""),
            "Location" to (m.location ?: ""),
            "Format version" to (m.formatVersion?.toString() ?: ""),
            "Table UUID" to (m.tableUuid ?: ""),
            "Last updated" to time(m.lastUpdatedMs),
            "Current snapshot" to (m.currentSnapshotId?.toString() ?: "none"),
            "Last sequence number" to (m.lastSequenceNumber?.toString() ?: ""),
            "Next row id" to (m.nextRowId?.toString() ?: ""),
            "Current schema id" to (m.currentSchemaId?.toString() ?: ""),
            "Default spec id" to (m.defaultSpecId?.toString() ?: ""),
            "Default sort order id" to (m.defaultSortOrderId?.toString() ?: ""),
        )
    }

    private fun schemaTable(schema: Schema?): JComponent {
        if (schema == null) return text("No current schema")
        val rows = mutableListOf<Array<Any>>()
        fun walk(fields: List<NestedField>, prefix: String) {
            for (f in fields) {
                val name = prefix + f.name
                val key = if (f.id != null && f.id in schema.identifierFieldIds) "yes" else ""
                rows += arrayOf(
                    f.id?.toString() ?: "", name, f.type.render(), if (f.required) "yes" else "", key,
                    f.doc ?: "", f.initialDefault?.toString() ?: "", f.writeDefault?.toString() ?: "",
                )
                nested(f.type, name).forEach { (children, childPrefix) -> walk(children, childPrefix) }
            }
        }
        walk(schema.fields, "")
        return table(arrayOf("Id", "Name", "Type", "Required", "Identifier", "Doc", "Initial default", "Write default"), rows)
    }

    /** Struct fields reachable under a field, map keys included, with the dotted prefix their names take. */
    private fun nested(type: IcebergType, name: String): List<Pair<List<NestedField>, String>> = when (type) {
        is IcebergType.Struct -> listOf(type.fields to "$name.")
        is IcebergType.ListType -> nested(type.element, "$name.element")
        is IcebergType.MapType -> nested(type.key, "$name.key") + nested(type.value, "$name.value")
        else -> emptyList()
    }

    private fun partitionTable(m: TableMetadata): JComponent {
        val spec = m.defaultSpec ?: return text("Unpartitioned")
        if (spec.fields.isEmpty()) return text("Unpartitioned (spec ${spec.specId})")
        val rows = spec.fields.map { f ->
            arrayOf<Any>(f.fieldId?.toString() ?: "", f.name, f.transform, sources(m, f.sourceIds))
        }
        return table(arrayOf("Field id", "Name", "Transform", "Source"), rows, "Spec ${spec.specId}")
    }

    private fun sortTable(m: TableMetadata): JComponent {
        val order = m.defaultSortOrder ?: return text("Unsorted")
        if (order.fields.isEmpty()) return text("Unsorted")
        val rows = order.fields.map { f -> arrayOf<Any>(sources(m, f.sourceIds), f.transform, f.direction, f.nullOrder) }
        return table(arrayOf("Source", "Transform", "Direction", "Null order"), rows, "Order ${order.orderId}")
    }

    /** Field names from the current schema; a field dropped since is named from older schemas, marked. */
    private fun sources(m: TableMetadata, ids: List<Int>): String = ids.joinToString(", ") { id ->
        m.currentSchema?.fieldName(id) ?: m.fieldName(id)?.let { "$it (dropped, id $id)" } ?: "id $id"
    }

    private fun snapshotTable(m: TableMetadata, complete: Boolean): JComponent {
        val refsBySnapshot = m.refs.valueOrNull().orEmpty().entries.groupBy({ it.value.snapshotId }, { it.key })
        val rows = m.snapshotsNewestFirst.map { s ->
            val refs = refsBySnapshot[s.snapshotId].orEmpty().sorted().joinToString(", ")
            arrayOf<Any>(
                s.snapshotId.toString(), time(s.timestampMs), s.operation ?: "", refs,
                s.parentSnapshotId?.toString() ?: "", s.sequenceNumber?.toString() ?: "",
                s.summary.filterKeys { it != "operation" }.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" },
            )
        }
        val note = if (complete) "${rows.size} snapshots" else "Snapshots held by branches and tags; loading the full history..."
        return table(arrayOf("Snapshot id", "Committed", "Operation", "Refs", "Parent", "Sequence", "Summary"), rows, note)
    }

    private fun <T> sectionOr(section: Section<T>, render: (T) -> JComponent): JComponent = when (section) {
        is Section.Parsed -> render(section.value)
        is Section.Raw -> text("This part could not be read (${section.error}). As the server sent it:\n\n${section.json ?: "missing"}")
    }

    private fun keyValues(pairs: List<Pair<String, String>>): JComponent =
        table(arrayOf("Key", "Value"), pairs.map { arrayOf<Any>(it.first, it.second) })

    private fun table(columns: Array<String>, rows: List<Array<Any>>, caption: String? = null): JComponent {
        val model = object : DefaultTableModel(rows.toTypedArray(), columns) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val table = JBTable(model).apply {
            setShowGrid(false)
            emptyText.text = "Nothing"
        }
        table.rowSorter = javax.swing.table.TableRowSorter(model).apply {
            // A column whose cells are all numbers sorts as numbers (1, 2, 10), not as text.
            for (c in columns.indices) {
                val values = rows.mapNotNull { (it[c] as? String)?.takeIf { v -> v.isNotEmpty() } }
                if (values.isNotEmpty() && values.all { it.toBigDecimalOrNull() != null }) {
                    setComparator(c, Comparator<Any?> { a, b ->
                        compareValues((a as? String)?.toBigDecimalOrNull(), (b as? String)?.toBigDecimalOrNull())
                    })
                }
            }
        }
        val panel = JPanel(BorderLayout())
        caption?.let { panel.add(JBLabel(it).apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH) }
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun text(value: String): JComponent = JBScrollPane(
        JTextArea(value).apply {
            isEditable = false
            lineWrap = false
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
            border = JBUI.Borders.empty(6)
        },
    )

    private fun time(ms: Long?): String = Formats.time(ms)

    private companion object {
        const val CARD_MESSAGE = "message"
        const val CARD_TABS = "tabs"
    }
}
