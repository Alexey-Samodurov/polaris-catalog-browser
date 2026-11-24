package io.github.alexey_samodurov.polaris.preview

import io.github.alexey_samodurov.polaris.client.DEFAULT_SCOPE
import io.github.alexey_samodurov.polaris.client.TableIdentifier
import io.github.alexey_samodurov.polaris.client.normalizeBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.exceptions.ForbiddenException
import org.apache.iceberg.exceptions.NoSuchTableException
import org.apache.iceberg.exceptions.NotAuthorizedException
import org.apache.iceberg.exceptions.RESTException
import org.apache.iceberg.exceptions.ValidationException
import org.apache.iceberg.expressions.Expression
import org.apache.iceberg.expressions.Expressions
import org.apache.iceberg.rest.RESTCatalog
import kotlin.coroutines.coroutineContext
import org.apache.iceberg.catalog.TableIdentifier as IcebergIdentifier

/** How the client addresses S3; `null` takes what Polaris returns for the table. */
enum class S3Addressing { PATH_STYLE, VIRTUAL_HOST }

/**
 * The user's own S3 keys, for catalogs where Polaris vends none (STS off). [endpoint],
 * [addressing] and [region] override what Polaris returns when set.
 */
class S3Access(
    val accessKeyId: String,
    val secretAccessKey: () -> String,
    val endpoint: String? = null,
    val addressing: S3Addressing? = null,
    val region: String? = null,
) {
    override fun toString(): String = "S3Access(accessKeyId=$accessKeyId, endpoint=$endpoint)"
}

/** Where Polaris is and who reads; secrets are read once per preview call. */
class PreviewConnection(
    baseUrl: String,
    val clientId: String,
    val secret: () -> String,
    val realm: String? = null,
    val scope: String = DEFAULT_SCOPE,
    /** `null`: ask Polaris for vended credentials. */
    val s3: S3Access? = null,
) {
    val baseUri: String = normalizeBaseUrl(baseUrl).toString()

    override fun toString(): String = "PreviewConnection($baseUri, clientId=$clientId, s3=$s3)"
}

/**
 * Reads a table's rows with the Iceberg Java SDK.
 *
 * Storage access uses the connection's own S3 keys when it has them, and otherwise credentials
 * Polaris vends; when it vends none the call fails before the AWS SDK could fall back to keys found
 * elsewhere on the machine. Deletion vectors, position deletes and equality deletes are applied by
 * the SDK's generic reader.
 */
class PolarisPreview(private val connection: PreviewConnection) {
    /**
     * Opens a reading session for [request]: the catalog, its FileIO and the scan stay open until
     * the session is closed, so "load more" continues where the last page ended. The scan's order
     * is not stable between two scans of the same snapshot, so paging by re-reading would repeat
     * and skip rows.
     */
    suspend fun open(catalog: String, table: TableIdentifier, request: PreviewRequest): PreviewResult<PreviewSession> {
        // A session created just as the caller is cancelled would be dropped by withContext with
        // its catalog, S3 client and keys still open; keep a handle to close it.
        var created: PreviewSession? = null
        try {
            return guarded(table) {
                val rest = openCatalog(catalog)
                try {
                    PreviewResult.Ok(PreviewSession.start(rest, load(rest, table), table, request).also { created = it })
                } catch (e: Throwable) {
                    rest.close()
                    throw e
                }
            }
        } catch (e: CancellationException) {
            created?.close()
            throw e
        }
    }

    /** One page and done: opens a session, reads [PreviewRequest.limit] rows, closes it. */
    suspend fun read(catalog: String, table: TableIdentifier, request: PreviewRequest): PreviewResult<PreviewRows> =
        when (val opened = open(catalog, table, request)) {
            is PreviewResult.Ok -> opened.value.use { it.next(request.limit) }
            is PreviewResult.NoPermission -> opened
            is PreviewResult.Failed -> opened
        }

    private fun load(rest: RESTCatalog, table: TableIdentifier): Table {
        val loaded = rest.loadTable(IcebergIdentifier.of(Namespace.of(*table.namespace.levels.toTypedArray()), table.name))
        val scheme = loaded.location().substringBefore("://", missingDelimiterValue = "").lowercase()
        require(scheme in S3_SCHEMES) {
            "The preview reads tables on S3 only; this table's data is at ${scheme.ifEmpty { "a local path" }}://"
        }
        if (connection.s3 == null && !hasStorageCredentials(loaded)) throw MissingStorageCredentials()
        return loaded
    }

    private fun openCatalog(warehouse: String): RESTCatalog {
        val s3 = connection.s3
        val props = buildMap {
            put("uri", "${connection.baseUri}/api/catalog")
            put("credential", "${connection.clientId}:${connection.secret()}")
            put("scope", connection.scope)
            put("warehouse", warehouse)
            connection.realm?.let { put("header.Polaris-Realm", it) }
            put("io-impl", PreviewS3FileIO::class.java.name)
            put("http-client.type", "urlconnection")
            // A default for servers that return no region; theirs wins when they do.
            put("client.region", "us-east-1")
            if (s3 == null) {
                put("header.X-Iceberg-Access-Delegation", "vended-credentials")
            } else {
                val o = PreviewS3FileIO.PREFIX
                put("${o}s3.access-key-id", s3.accessKeyId)
                put("${o}s3.secret-access-key", s3.secretAccessKey())
                s3.endpoint?.takeIf { it.isNotBlank() }?.let { put("${o}s3.endpoint", it.trim()) }
                s3.addressing?.let { put("${o}s3.path-style-access", (it == S3Addressing.PATH_STYLE).toString()) }
                s3.region?.takeIf { it.isNotBlank() }?.let { put("${o}client.region", it.trim()) }
            }
        }
        return RESTCatalog().also { it.initialize("polaris-preview", props) }
    }

    companion object {
        /** Runs [body] on the IO dispatcher and turns failures into results the UI can show. */
        internal suspend fun <T> guarded(table: TableIdentifier, body: suspend () -> PreviewResult<T>): PreviewResult<T> =
            withContext(Dispatchers.IO + OwnClassLoader) {
                try {
                    body()
                } catch (e: CancellationException) {
                    // Only a cancellation of this coroutine propagates; one thrown by a library
                    // (java.util.concurrent's has the same class) is a failure to report.
                    if (!coroutineContext.isActive) throw e
                    failure(e, table)
                } catch (e: InterruptedException) {
                    if (!coroutineContext.isActive) throw CancellationException("preview cancelled").apply { initCause(e) }
                    failure(e, table)
                } catch (e: Exception) {
                    failure(e, table)
                } catch (e: LinkageError) {
                    // A class the bundled readers need is missing: a packaging fault, reported, not thrown.
                    PreviewResult.Failed("The plugin lacks a class this table's files need: ${e.message}")
                }
            }

        /** A failure as the UI shows it; no message carries a key or a secret. */
        internal fun failure(e: Throwable, table: TableIdentifier): PreviewResult<Nothing> = when {
            e is MissingStorageCredentials || "no credentials are available" in (e.message ?: "") ->
                PreviewResult.Failed(NO_CREDENTIALS)
            e is ForbiddenException ->
                PreviewResult.NoPermission("No permission to read this table's data: the principal needs TABLE_READ_DATA")
            e is NotAuthorizedException -> PreviewResult.Failed("Not authorized: ${e.message}")
            e is NoSuchTableException -> PreviewResult.Failed("$table no longer exists")
            e is software.amazon.awssdk.services.s3.model.S3Exception && e.statusCode() == 403 ->
                PreviewResult.NoPermission("S3 refused access (403): the S3 keys may not read this table's location")
            e is software.amazon.awssdk.services.s3.model.S3Exception ->
                PreviewResult.Failed("S3: ${e.awsErrorDetails()?.errorMessage() ?: e.message}")
            e is IllegalArgumentException || e is ValidationException -> PreviewResult.Failed(e.message ?: "invalid request")
            e is RESTException -> PreviewResult.Failed("Polaris: ${e.message}")
            else -> {
                val s3 = generateSequence(e) { it.cause }.filterIsInstance<software.amazon.awssdk.services.s3.model.S3Exception>().firstOrNull()
                if (s3 != null) failure(s3, table) else PreviewResult.Failed(describeFailure(e))
            }
        }

        private val S3_SCHEMES = setOf("s3", "s3a", "s3n")

        const val NO_CREDENTIALS: String =
            "Polaris vended no storage credentials for this table (STS is off for its catalog). " +
                "Enter S3 keys in the connection's settings to preview data."

        /** Whether the table's FileIO got keys from Polaris, either as properties or as storage credentials. */
        private fun hasStorageCredentials(table: Table): Boolean {
            val io = table.io()
            if (!io.properties()["s3.access-key-id"].isNullOrEmpty()) return true
            return (io as? org.apache.iceberg.io.SupportsStorageCredentials)?.credentials()?.isNotEmpty() == true
        }

        /**
         * One `and` of every filter. Values are passed as text and converted by Iceberg when the
         * expression is bound to the schema, which knows dates, timestamps, decimals and UUIDs;
         * numbers and booleans are converted here so `5` compares as a number.
         */
        fun filterExpression(schema: Schema, filters: List<ColumnFilter>): Expression {
            var result: Expression = Expressions.alwaysTrue()
            for (f in filters) {
                val field = schema.findField(f.column) ?: throw IllegalArgumentException("column ${f.column} does not exist")
                require(f.values.isNotEmpty()) { "${f.column}: no value" }
                val values = f.values.map { literal(f.column, field.type(), it) }
                val e = when (f.op) {
                    FilterOp.EQ -> Expressions.equal(f.column, values.single())
                    FilterOp.NOT_EQ -> Expressions.notEqual(f.column, values.single())
                    FilterOp.LT -> Expressions.lessThan(f.column, values.single())
                    FilterOp.LT_EQ -> Expressions.lessThanOrEqual(f.column, values.single())
                    FilterOp.GT -> Expressions.greaterThan(f.column, values.single())
                    FilterOp.GT_EQ -> Expressions.greaterThanOrEqual(f.column, values.single())
                    FilterOp.IN -> Expressions.`in`(f.column, *values.toTypedArray())
                }
                result = Expressions.and(result, e)
            }
            // Binding converts the text literals now, so a bad value fails before any file is read.
            try {
                org.apache.iceberg.expressions.Binder.bind(schema.asStruct(), result, true)
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("Filter does not fit the column types: ${e.message}")
            }
            return result
        }

        private fun literal(column: String, type: org.apache.iceberg.types.Type, text: String): Any {
            val v = text.trim()
            fun bad(): Nothing = throw IllegalArgumentException("$column: '$v' is not a $type")
            return when (type.typeId()) {
                org.apache.iceberg.types.Type.TypeID.INTEGER -> v.toIntOrNull() ?: bad()
                org.apache.iceberg.types.Type.TypeID.LONG -> v.toLongOrNull() ?: bad()
                org.apache.iceberg.types.Type.TypeID.FLOAT -> v.toFloatOrNull() ?: bad()
                org.apache.iceberg.types.Type.TypeID.DOUBLE -> v.toDoubleOrNull() ?: bad()
                org.apache.iceberg.types.Type.TypeID.BOOLEAN -> v.lowercase().toBooleanStrictOrNull() ?: bad()
                else -> v
            }
        }

        private fun describeFailure(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.toList()
            val tls = chain.firstOrNull { it is javax.net.ssl.SSLException }
            if (tls != null) return "TLS failed: ${tls.message}. A Polaris or S3 endpoint behind an internal CA needs that CA in the IDE's JDK trust store."
            val root = chain.last()
            return listOfNotNull(e.message, root.takeIf { it !== e }?.message).distinct().joinToString(": ").ifEmpty { e.javaClass.simpleName }
        }
    }
}
