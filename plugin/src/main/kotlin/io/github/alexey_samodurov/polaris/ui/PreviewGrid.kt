package io.github.alexey_samodurov.polaris.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.alexey_samodurov.polaris.preview.PreviewColumn
import java.awt.Component
import java.awt.Dimension
import javax.swing.AbstractListModel
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/**
 * The rows of a preview the way Big Data Tools shows a file: row numbers, grid lines, a header
 * with each column's name and type, columns sized to what they hold, cells selectable for copying.
 */
internal class PreviewGrid : JBScrollPane() {
    val model = RowsModel()
    private var ready = false
    val table: JBTable = object : JBTable(model) {
        // Columns are recreated here when the rows of another read arrive; fit them right after.
        override fun createDefaultColumnsFromModel() {
            super.createDefaultColumnsFromModel()
            if (ready) fitColumns()
        }
    }
    private val rowNumbers: RowNumbers

    init {
        table.apply {
            rowHeight = getFontMetrics(font).height + JBUI.scale(8)
            setShowGrid(true)
            gridColor = JBColor.namedColor("TableCell.borderColor", JBColor.border())
            intercellSpacing = Dimension(1, 1)
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            // Single cells, ranges and whole rows: Ctrl+C copies what is selected.
            cellSelectionEnabled = true
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            tableHeader.reorderingAllowed = true
            tableHeader.defaultRenderer = HeaderRenderer(tableHeader.defaultRenderer)
            setDefaultRenderer(Any::class.java, CellRenderer())
            emptyText.text = "No rows"
        }
        rowNumbers = RowNumbers()
        setViewportView(table)
        setColumnHeaderView(table.tableHeader)
        setRowHeaderView(rowNumbers)
        setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, JLabel("#", SwingConstants.CENTER).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 1)
        })
        model.addTableModelListener {
            rowNumbers.refresh()
            // Appended rows keep the columns; widen any that the new rows need.
            if (it.type == javax.swing.event.TableModelEvent.INSERT) fitColumns()
        }
        ready = true
    }

    /**
     * Each column as wide as its header or its widest cell among the first rows, whichever is
     * wider, between a minimum and a cap; a column the user widened stays as wide.
     */
    private fun fitColumns() {
        val metrics = table.getFontMetrics(table.font)
        val bold = table.getFontMetrics(table.font.deriveFont(java.awt.Font.BOLD))
        val sample = minOf(model.rowCount, SAMPLE_ROWS)
        val columns = table.columnModel
        for (i in 0 until columns.columnCount) {
            val column = columns.getColumn(i)
            val c = column.modelIndex
            val header = maxOf(bold.stringWidth(model.columns[c].name), metrics.stringWidth(model.columns[c].type))
            var widest = header
            for (r in 0 until sample) {
                val text = model.getValueAt(r, c) as String? ?: "NULL"
                widest = maxOf(widest, metrics.stringWidth(text))
                if (widest >= MAX_WIDTH) break
            }
            val width = (widest + JBUI.scale(PADDING)).coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))
            if (column.preferredWidth < width) column.preferredWidth = width
        }
    }

    /** Row numbers beside the table, in its row height, scrolled with it. */
    private inner class RowNumbers : JBList<Int>() {
        private val numbers = object : AbstractListModel<Int>() {
            var count = 0
            override fun getSize(): Int = count
            override fun getElementAt(index: Int): Int = index + 1
            fun changed(newCount: Int) {
                val old = count
                count = newCount
                if (newCount > old) fireIntervalAdded(this, old, newCount - 1)
                if (newCount < old) fireIntervalRemoved(this, newCount, old - 1)
            }
        }

        init {
            model = numbers
            isFocusable = false
            background = UIUtil.getPanelBackground()
            cellRenderer = ListCellRenderer<Int> { _, value, _, _, _ ->
                JLabel(value.toString(), SwingConstants.RIGHT).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = table.font
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 1),
                        JBUI.Borders.empty(0, 6),
                    )
                }
            }
            emptyText.text = ""
            refresh()
        }

        fun refresh() {
            fixedCellHeight = table.rowHeight
            numbers.changed(table.model.rowCount)
            val digits = maxOf(numbers.count.toString().length, 2)
            fixedCellWidth = table.getFontMetrics(table.font).stringWidth("9".repeat(digits)) + JBUI.scale(14)
            revalidate()
            repaint()
        }
    }

    /** The name in bold over the type in grey, on the platform's header with its borders. */
    private class HeaderRenderer(private val base: TableCellRenderer) : TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val c = base.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val col = (table.model as? RowsModel)?.columns?.getOrNull(table.convertColumnIndexToModel(column)) ?: return c
            if (c is JLabel) {
                val grey = ColorUtil.toHex(UIUtil.getContextHelpForeground())
                c.text = "<html><b>${escape(col.name)}</b><br><span style='color:#$grey'>${escape(col.type)}</span></html>"
                c.horizontalAlignment = SwingConstants.LEFT
                c.toolTipText = "${col.name}: ${col.type}"
            }
            return c
        }

        private fun escape(s: String) = StringUtil.escapeXmlEntities(s)
    }

    /** SQL NULL greyed and italic, apart from the text "null"; numbers right-aligned. */
    private class CellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val type = (table.model as? RowsModel)?.columns?.getOrNull(table.convertColumnIndexToModel(column))?.type
            // The renderer is shared by every cell: set the alignment each time, NULL included.
            setTextAlign(if (value != null && type != null && type.isNumeric()) SwingConstants.RIGHT else SwingConstants.LEFT)
            if (value == null) append("NULL", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) else append(value.toString())
        }

        private fun String.isNumeric() =
            this in setOf("int", "long", "float", "double") || startsWith("decimal")
    }

    private companion object {
        const val SAMPLE_ROWS = 200
        const val MIN_WIDTH = 60
        const val MAX_WIDTH = 400
        const val PADDING = 24
    }
}

/** Rows as text. */
internal class RowsModel : AbstractTableModel() {
    var columns: List<PreviewColumn> = emptyList()
        private set
    private val rows = mutableListOf<List<String?>>()

    fun reset(columns: List<PreviewColumn>, rows: List<List<String?>> = emptyList()) {
        this.columns = columns
        this.rows.clear()
        this.rows += rows
        fireTableStructureChanged()
    }

    fun append(more: List<List<String?>>) {
        if (more.isEmpty()) return
        val first = rows.size
        rows += more
        fireTableRowsInserted(first, rows.size - 1)
    }

    fun row(index: Int): List<String?> = rows[index]

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column].name

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? = rows[rowIndex][columnIndex]
}
