package io.github.alexey_samodurov.polaris.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** A catalog as the management API describes it. */
data class CatalogInfo(
    val name: String,
    val type: String?,
    val storageType: String?,
    val properties: Map<String, String>,
)

/** How many snapshots a table load asks for. */
enum class SnapshotMode(val query: String) {
    /** Only snapshots some branch or tag points at: small and fast. */
    REFS("refs"),

    /** The whole history. */
    ALL("all"),
}

/** A loaded table: where its metadata lives, the metadata, and the tag to revalidate it. */
class LoadedTable(
    val identifier: TableIdentifier,
    val metadataLocation: String?,
    val metadata: TableMetadata,
    val etag: String?,
    val mode: SnapshotMode,
)

class LoadedView(val identifier: TableIdentifier, val metadataLocation: String?, val metadata: ViewMetadata)

/** Endpoints a catalog serves, as `/v1/config` lists them. */
class Endpoints internal constructor(private val listed: Set<String>?) {
    /**
     * Whether the server serves [endpoint] (`"GET /v1/{prefix}/views"`). A server that lists no
     * endpoints serves the specification's default set, which has no views.
     */
    fun supports(endpoint: String): Boolean = listed?.contains(endpoint) ?: (endpoint in DEFAULT)

    companion object {
        const val LIST_NAMESPACES = "GET /v1/{prefix}/namespaces"
        const val LIST_TABLES = "GET /v1/{prefix}/namespaces/{namespace}/tables"
        const val LOAD_TABLE = "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}"
        const val LIST_VIEWS = "GET /v1/{prefix}/namespaces/{namespace}/views"
        const val LOAD_VIEW = "GET /v1/{prefix}/namespaces/{namespace}/views/{view}"

        private val DEFAULT = setOf(LIST_NAMESPACES, LIST_TABLES, LOAD_TABLE)
    }
}

/**
 * Read-only client for one Polaris server: the management API's catalog list and, per catalog,
 * the Iceberg REST catalog API.
 *
 * Writes nothing, and never asks for vended storage credentials.
 */
class PolarisClient(connection: PolarisConnection, clock: Clock = Clock.systemUTC()) {
    internal val transport = HttpTransport(connection, clock)
    private val catalogs = ConcurrentHashMap<String, CatalogSession>()

    /** Catalogs the principal may list; [PolarisException.Forbidden] when it may not list any. */
    suspend fun listCatalogs(): List<CatalogInfo> {
        val path = "/api/management/v1/catalogs"
        val body = HttpTransport.parseObject("GET", path, transport.get(path).body)
        val items = body["catalogs"] as? JsonArray ?: throw PolarisException.BadResponse("GET $path: no catalogs array")
        return items.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            CatalogInfo(
                name = name,
                type = obj.str("type"),
                storageType = (obj["storageConfigInfo"] as? JsonObject)?.str("storageType"),
                properties = (obj["properties"] as? JsonObject)?.asStringMap().orEmpty(),
            )
        }.sortedBy { it.name }
    }

    /** Requests a new token, to check the URL and the credentials. */
    suspend fun testConnection() {
        transport.authenticate()
    }

    /** The Iceberg REST view of [warehouse]; one session per catalog, reused. */
    fun catalog(warehouse: String): CatalogSession = catalogs.computeIfAbsent(warehouse) { CatalogSession(transport, it) }
}

/**
 * One catalog through the Iceberg REST API.
 *
 * The `/v1/config` answer is fetched once and kept. Loaded tables are kept with their `ETag`, so a
 * refresh costs a 304 when nothing changed.
 */
class CatalogSession internal constructor(private val transport: HttpTransport, val warehouse: String) {
    private val configLock = Mutex()

    @Volatile
    private var resolved: Pair<String, Endpoints>? = null

    /** The most recently used tables, at most [MAX_CACHED_TABLES]; the eldest is dropped first. */
    private val tables: MutableMap<Pair<TableIdentifier, SnapshotMode>, LoadedTable> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Pair<TableIdentifier, SnapshotMode>, LoadedTable>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TableIdentifier, SnapshotMode>, LoadedTable>) =
                size > MAX_CACHED_TABLES
        },
    )

    /** Path prefix (`/api/catalog/v1/<prefix>`) and endpoints, from `/v1/config`. */
    private suspend fun config(): Pair<String, Endpoints> {
        resolved?.let { return it }
        return configLock.withLock {
            resolved ?: run {
                val path = "/api/catalog/v1/config?warehouse=${encodeSegment(warehouse)}"
                val body = HttpTransport.parseObject("GET", path, transport.get(path).body)
                val prefix = (body["overrides"] as? JsonObject)?.str("prefix")
                    ?: (body["defaults"] as? JsonObject)?.str("prefix")
                // The server hands the prefix out ready for a path, and Iceberg's REST client inserts
                // it as is; encoding it again would turn a %20 into %2520.
                val base = if (prefix.isNullOrEmpty()) "/api/catalog/v1" else "/api/catalog/v1/$prefix"
                val listed = (body["endpoints"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
                (base to Endpoints(listed)).also { resolved = it }
            }
        }
    }

    suspend fun endpoints(): Endpoints = config().second

    /** Top-level namespaces, or the children of [parent]. */
    suspend fun listNamespaces(parent: Namespace? = null): List<Namespace> {
        val (base, _) = config()
        val query = parent?.let { "parent=${encodeNamespace(it)}" }
        return paged("$base/namespaces", query, "namespaces") { el ->
            val levels = (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            levels?.takeIf { it.isNotEmpty() }?.let(::Namespace)
        }.sortedBy { it.toString() }
    }

    suspend fun listTables(namespace: Namespace): List<TableIdentifier> {
        val (base, _) = config()
        return paged("$base/namespaces/${encodeNamespace(namespace)}/tables", null, "identifiers", ::identifier)
            .sortedBy { it.name }
    }

    /** Views of [namespace]; empty when the server does not serve views or refuses the call. */
    suspend fun listViews(namespace: Namespace): List<TableIdentifier> {
        val (base, endpoints) = config()
        if (!endpoints.supports(Endpoints.LIST_VIEWS)) return emptyList()
        return try {
            paged("$base/namespaces/${encodeNamespace(namespace)}/views", null, "identifiers", ::identifier)
                .sortedBy { it.name }
        } catch (_: PolarisException.Forbidden) {
            emptyList()
        } catch (_: PolarisException.NotFound) {
            emptyList()
        }
    }

    /**
     * Loads [table]. A cached copy is returned as it is unless [revalidate] is set; then the server
     * is asked with `If-None-Match` and the copy is kept on 304.
     */
    suspend fun loadTable(table: TableIdentifier, mode: SnapshotMode = SnapshotMode.REFS, revalidate: Boolean = false): LoadedTable {
        val key = table to mode
        val cached = tables[key]
        if (cached != null && !revalidate) return cached
        val (base, _) = config()
        val path = "$base/namespaces/${encodeNamespace(table.namespace)}/tables/${encodeSegment(table.name)}?snapshots=${mode.query}"
        val headers = cached?.etag?.let { mapOf("If-None-Match" to it) }.orEmpty()
        val response = try {
            transport.get(path, headers, passStatuses = setOf(304))
        } catch (e: PolarisException.NotFound) {
            synchronized(tables) { tables.keys.removeIf { it.first == table } }
            throw e
        }
        if (response.status == 304 && cached != null) return cached
        val body = HttpTransport.parseObject("GET", path, response.body)
        // Only the metadata location and the metadata are kept: `config` and
        // `storage-credentials` may carry credentials and are dropped here.
        val metadata = body["metadata"] as? JsonObject ?: throw PolarisException.BadResponse("GET $path: no metadata")
        val loaded = LoadedTable(table, body.str("metadata-location"), TableMetadata(metadata), response.header("ETag"), mode)
        tables[key] = loaded
        return loaded
    }

    suspend fun loadView(view: TableIdentifier): LoadedView {
        val (base, _) = config()
        val path = "$base/namespaces/${encodeNamespace(view.namespace)}/views/${encodeSegment(view.name)}"
        val body = HttpTransport.parseObject("GET", path, transport.get(path).body)
        val metadata = body["metadata"] as? JsonObject ?: throw PolarisException.BadResponse("GET $path: no metadata")
        return LoadedView(view, body.str("metadata-location"), ViewMetadata(metadata))
    }

    /** Forgets cached tables and config, so the next calls go to the server. */
    fun invalidate() {
        tables.clear()
        resolved = null
    }

    private fun identifier(element: kotlinx.serialization.json.JsonElement): TableIdentifier? {
        val obj = element as? JsonObject ?: return null
        val levels = (obj["namespace"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        val name = obj.str("name") ?: return null
        if (levels.isNullOrEmpty()) return null
        return TableIdentifier(Namespace(levels), name)
    }

    /**
     * Follows `next-page-token` until the server stops returning one. A server with pagination
     * off answers everything at once with no token, which ends the loop on the first page.
     */
    private suspend fun <T> paged(
        path: String,
        query: String?,
        field: String,
        parse: (kotlinx.serialization.json.JsonElement) -> T?,
    ): List<T> {
        val result = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var pageToken: String? = null
        while (true) {
            val params = listOfNotNull(query, pageToken?.let { "pageToken=${encodeSegment(it)}" })
            val url = if (params.isEmpty()) path else "$path?${params.joinToString("&")}"
            val body = HttpTransport.parseObject("GET", path, transport.get(url).body)
            val items = body[field] as? JsonArray ?: throw PolarisException.BadResponse("GET $path: no $field array")
            items.mapNotNullTo(result, parse)
            pageToken = body.str("next-page-token")?.takeIf { it.isNotEmpty() } ?: return result
            if (!seen.add(pageToken)) throw PolarisException.BadResponse("GET $path: the server repeated page token")
            if (seen.size > MAX_PAGES) throw PolarisException.BadResponse("GET $path: more than $MAX_PAGES pages")
        }
    }

    private companion object {
        const val MAX_PAGES = 10_000
        const val MAX_CACHED_TABLES = 64
    }
}
