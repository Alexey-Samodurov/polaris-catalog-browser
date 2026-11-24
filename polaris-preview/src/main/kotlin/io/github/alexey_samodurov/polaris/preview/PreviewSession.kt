package io.github.alexey_samodurov.polaris.preview

import io.github.alexey_samodurov.polaris.client.TableIdentifier
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.Record
import org.apache.iceberg.io.CloseableIterable
import org.apache.iceberg.io.CloseableIterator
import org.apache.iceberg.rest.RESTCatalog
import org.apache.iceberg.util.SnapshotUtil

/** Thrown when Polaris vended no credentials and the connection has no S3 keys. */
internal class MissingStorageCredentials : RuntimeException(PolarisPreview.NO_CREDENTIALS)

/**
 * One open scan of one table: pages continue the same iterator, so no row is repeated or skipped.
 *
 * Holds the catalog, its FileIO and the scan until [close]; it closes itself once
 * [MAX_PREVIEW_ROWS] rows were read or the scan is exhausted, and after any failure or
 * cancellation. Pages are read one at a time. [close] may be called from any thread at any time:
 * while a page is being read it only marks the session, and the reading page releases the
 * resources when it stops.
 */
class PreviewSession private constructor(
    private val rest: RESTCatalog,
    private val table: TableIdentifier,
    val columns: List<PreviewColumn>,
    val snapshotId: Long?,
    private val records: CloseableIterable<Record>?,
) : AutoCloseable {
    private val pages = Mutex()
    private val state = Any()
    private var iterator: CloseableIterator<Record>? = null
    private var shown = 0
    private var reading = false
    private var released = false

    @Volatile
    var isClosed: Boolean = false
        private set

    /** The next up to [limit] rows, never past [MAX_PREVIEW_ROWS] in total. */
    suspend fun next(limit: Int = PREVIEW_PAGE): PreviewResult<PreviewRows> = PolarisPreview.guarded(table) {
        pages.withLock {
            synchronized(state) {
                check(!isClosed) { "the preview session is closed" }
                reading = true
            }
            val started = System.nanoTime()
            val take = minOf(limit, MAX_PREVIEW_ROWS - shown)
            val rows = mutableListOf<List<String?>>()
            var hasMore = false
            try {
                if (records != null) {
                    runInterruptible {
                        val it = iterator ?: records.iterator().also { created -> iterator = created }
                        while (rows.size < take && it.hasNext()) {
                            if (Thread.currentThread().isInterrupted || isClosed) throw InterruptedException()
                            val record = it.next()
                            rows += columns.map { c -> ValueRendering.render(record.getField(c.name)) }
                        }
                        hasMore = it.hasNext()
                    }
                }
            } catch (e: Throwable) {
                synchronized(state) { isClosed = true }
                throw e
            } finally {
                val release = synchronized(state) {
                    reading = false
                    isClosed
                }
                if (release) release()
            }
            shown += rows.size
            val more = hasMore && shown < MAX_PREVIEW_ROWS
            if (!more) close()
            PreviewResult.Ok(PreviewRows(columns, rows, more, snapshotId, (System.nanoTime() - started) / 1_000_000))
        }
    }

    override fun close() {
        val releaseNow = synchronized(state) {
            if (isClosed && (reading || released)) return
            isClosed = true
            !reading
        }
        if (releaseNow) release()
    }

    /** Closes the scan, the catalog and its FileIO, once. */
    private fun release() {
        synchronized(state) {
            if (released) return
            released = true
        }
        // Closing reaches Iceberg and AWS SDK code that loads classes through the context loader.
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = PreviewSession::class.java.classLoader
        try {
            runCatching { iterator?.close() }
            runCatching { records?.close() }
            runCatching { rest.close() }
        } finally {
            thread.contextClassLoader = previous
        }
    }

    internal companion object {
        fun start(rest: RESTCatalog, table: Table, id: TableIdentifier, request: PreviewRequest): PreviewSession {
            // Iceberg's rules: the current state and a branch read with the table's schema, so a
            // column added since the last commit reads as null; a tag or an older snapshot reads
            // with the schema it was written with.
            val (snapshotId, schema) = when (val s = request.selector) {
                SnapshotSelector.Current -> table.currentSnapshot()?.snapshotId() to table.schema()
                is SnapshotSelector.Snapshot -> {
                    require(table.snapshot(s.snapshotId) != null) { "snapshot ${s.snapshotId} is not in the table's history any more" }
                    s.snapshotId to SnapshotUtil.schemaFor(table, s.snapshotId)
                }
                is SnapshotSelector.Ref -> {
                    val ref = table.refs()[s.name] ?: throw IllegalArgumentException("no branch or tag named ${s.name}")
                    ref.snapshotId() to SnapshotUtil.schemaFor(table, s.name)
                }
            }
            val names = request.columns.ifEmpty { schema.columns().map { it.name() } }
            names.forEach { require(schema.findField(it) != null) { "column $it does not exist at this snapshot" } }
            val projection: Schema = schema.select(names)
            val columns = names.map { PreviewColumn(it, projection.findField(it)!!.type().toString()) }
            val filter = PolarisPreview.filterExpression(schema, request.filters)
            if (snapshotId == null) return PreviewSession(rest, id, columns, null, null)

            // Planned once, lazily, by the reader itself; the explicit projection keeps the
            // schema chosen above instead of the snapshot's own.
            val records = IcebergGenerics.read(table).useSnapshot(snapshotId).project(projection).where(filter).build()
            return PreviewSession(rest, id, columns, snapshotId, records)
        }
    }
}
