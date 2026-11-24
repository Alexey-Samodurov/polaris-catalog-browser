package io.github.alexey_samodurov.polaris.preview

/** Most rows a preview ever shows. */
const val MAX_PREVIEW_ROWS: Int = 1000

/** Rows a preview reads at a time. */
const val PREVIEW_PAGE: Int = 100

/** Which state of the table to read. */
sealed interface SnapshotSelector {
    data object Current : SnapshotSelector

    data class Snapshot(val snapshotId: Long) : SnapshotSelector

    /** A branch or a tag. */
    data class Ref(val name: String) : SnapshotSelector
}

enum class FilterOp(val symbol: String) {
    EQ("="), NOT_EQ("!="), LT("<"), LT_EQ("<="), GT(">"), GT_EQ(">="), IN("in");

    override fun toString(): String = symbol
}

/**
 * A condition on one column. Values are text as the user typed it; they are converted to the
 * column's type when the scan is built, and a value that does not convert is reported by name.
 */
data class ColumnFilter(val column: String, val op: FilterOp, val values: List<String>)

data class PreviewRequest(
    val selector: SnapshotSelector = SnapshotSelector.Current,
    /** Top-level columns to read; empty means all. */
    val columns: List<String> = emptyList(),
    val filters: List<ColumnFilter> = emptyList(),
    /** Rows of the first page. */
    val limit: Int = PREVIEW_PAGE,
) {
    init {
        require(limit in 1..MAX_PREVIEW_ROWS) { "limit must be 1..$MAX_PREVIEW_ROWS" }
    }
}

data class PreviewColumn(val name: String, val type: String)

/** A snapshot the Data tab offers to read at. */
data class PreviewSnapshot(val snapshotId: Long, val timestampMs: Long?)

/**
 * What the Data tab needs to offer choices, built from the metadata the browser already loaded
 * (no extra request): columns, partition source columns, refs and the snapshot history.
 */
data class PreviewTableInfo(
    val columns: List<PreviewColumn>,
    /** Top-level columns some partition field of the current spec is derived from. */
    val partitionSourceColumns: List<PreviewColumn>,
    val refs: Map<String, Long>,
    /** Newest first. */
    val snapshots: List<PreviewSnapshot>,
) {
    companion object {
        fun of(metadata: io.github.alexey_samodurov.polaris.client.TableMetadata): PreviewTableInfo {
            val fields = metadata.currentSchema?.fields.orEmpty()
            val columns = fields.map { PreviewColumn(it.name, it.type.render()) }
            val sources = metadata.defaultSpec?.fields.orEmpty().flatMap { it.sourceIds }.toSet()
            return PreviewTableInfo(
                columns = columns,
                partitionSourceColumns = fields.filter { it.id in sources }.map { PreviewColumn(it.name, it.type.render()) },
                refs = metadata.refs.valueOrNull().orEmpty().mapValues { it.value.snapshotId }.toSortedMap(),
                snapshots = metadata.snapshotsNewestFirst.map { PreviewSnapshot(it.snapshotId, it.timestampMs) },
            )
        }
    }
}

data class PreviewRows(
    val columns: List<PreviewColumn>,
    /** Each value rendered as text; `null` for SQL NULL. */
    val rows: List<List<String?>>,
    /** Whether more rows exist after these. */
    val hasMore: Boolean,
    val snapshotId: Long?,
    val elapsedMillis: Long,
)

/** The outcome of a preview call; failures carry a message for the UI, never a credential. */
sealed interface PreviewResult<out T> {
    data class Ok<T>(val value: T) : PreviewResult<T>

    /** The principal may not read this table's data (no `TABLE_READ_DATA`). */
    data class NoPermission(val message: String) : PreviewResult<Nothing>

    data class Failed(val message: String) : PreviewResult<Nothing>
}
