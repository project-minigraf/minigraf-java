package io.github.project_minigraf.minigraf

import java.util.UUID

/**
 * Static entry points for Java callers.
 *
 * The UniFFI-generated factories live on the Kotlin companion object, so from Java
 * they read `MiniGrafDb.Companion.open(path)`. This facade lets Java write
 * `Minigraf.open(path)` instead. Kotlin callers can use either.
 */
object Minigraf {
    /** `valid_to` of a fact that is valid forever. */
    const val VALID_TIME_FOREVER: Long = Long.MAX_VALUE

    /**
     * `walCheckpointThreshold` that never checkpoints: no automatic checkpoint and
     * none when the handle closes. Call `checkpoint()` yourself.
     */
    const val WAL_CHECKPOINT_NEVER: Long = Long.MAX_VALUE

    /** Opens (or creates) a file-backed database at [path]. */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun open(path: String): MiniGrafDb = MiniGrafDb.open(path)

    /** Opens an ephemeral in-memory database. */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun openInMemory(): MiniGrafDb = MiniGrafDb.openInMemory()

    /**
     * Opens a file-backed database with [options], built with [options()].
     * `Minigraf.openWithOptions(path, Minigraf.options().readOnly(true).build())`.
     */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun openWithOptions(path: String, options: OpenOptions): MiniGrafDb =
        MiniGrafDb.openWithOptions(path, options)

    /** A builder for [OpenOptions]; an option left unset keeps its default. */
    @JvmStatic
    fun options(): OpenOptionsBuilder = OpenOptionsBuilder()

    /** A builder for [FactFilter]; a filter left unset does not filter. */
    @JvmStatic
    fun filter(): FactFilterBuilder = FactFilterBuilder()

    /**
     * Starts building a new database at [path] from fact records. Use it in
     * try-with-resources: `close()` abandons the build unless `finish()` ran.
     */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun createLogWriter(path: String, options: OpenOptions): MiniGrafLogWriter =
        MiniGrafLogWriter.create(path, options)

    /** The cursor's batches (JSON arrays of rows), [batchSize] rows each. */
    @JvmStatic
    fun batches(cursor: MiniGrafCursor, batchSize: Int): Iterable<String> = cursor.batches(batchSize)

    /**
     * The error's text, starting with its code, such as `[API-014] database is open
     * read-only; ...`. (`getMessage()` prefixes it with `msg=`.)
     */
    @JvmStatic
    fun errorMessage(e: MiniGrafException): String = e.text

    /** The log's records, read [batchSize] at a time. */
    @JvmStatic
    fun records(log: MiniGrafFactLog, batchSize: Int): Iterable<FactRecord> = log.records(batchSize)

    /**
     * Runs [prepared] with [bindings] (slot names without the `$`) and returns its
     * result as JSON. Plain values bind as they are; see [bindValue].
     */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun execute(prepared: MiniGrafPreparedQuery, bindings: Map<String, Any?>): String =
        prepared.execute(bindValues(bindings))

    /** Runs [prepared] with [bindings], as [execute] does, and returns a cursor. */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun query(prepared: MiniGrafPreparedQuery, bindings: Map<String, Any?>): MiniGrafCursor =
        prepared.query(bindValues(bindings))

    /** Binds an entity slot (it also fits a value slot as a ref). */
    @JvmStatic
    fun entity(uuid: UUID): MiniGrafBindValue = MiniGrafBindValue.Entity(uuid.toString())

    /** Binds `:as-of $slot` to the transaction counter [n]. */
    @JvmStatic
    fun txCount(n: Long): MiniGrafBindValue = MiniGrafBindValue.TxCount(n)

    /** Binds `:as-of $slot` or `:valid-at $slot` to Unix milliseconds. */
    @JvmStatic
    fun timestamp(ms: Long): MiniGrafBindValue = MiniGrafBindValue.Timestamp(ms)

    /** Binds `:valid-at $slot` to every valid time, like `:any-valid-time`. */
    @JvmField
    val ANY_VALID_TIME: MiniGrafBindValue = MiniGrafBindValue.AnyValidTime
}

/**
 * [v] as a bind value. `Boolean`; `Byte`, `Short`, `Int` and `Long` as integers;
 * `Float` and `Double` as floats; `String`; `null`; and `java.util.UUID` (an
 * entity, which also fits value and rule-argument slots) bind as they are, as do
 * a [MiniGrafValue] and a [MiniGrafBindValue]. A `Set` binds a set value
 * (minigraf#316): its elements are any of those plain values or a [MiniGrafValue],
 * and a `UUID` element is a ref. Any other type, or a set element that is not a
 * value, is [IllegalArgumentException].
 */
fun bindValue(name: String, v: Any?): MiniGrafBindValue = when (v) {
    null -> MiniGrafBindValue.Value(MiniGrafValue.Null)
    is MiniGrafBindValue -> v
    is MiniGrafValue -> MiniGrafBindValue.Value(v)
    is Boolean -> MiniGrafBindValue.Value(MiniGrafValue.Bool(v))
    is Byte, is Short, is Int, is Long -> MiniGrafBindValue.Value(MiniGrafValue.Int64((v as Number).toLong()))
    is Float, is Double -> MiniGrafBindValue.Value(MiniGrafValue.Float64((v as Number).toDouble()))
    is String -> MiniGrafBindValue.Value(MiniGrafValue.Text(v))
    is UUID -> MiniGrafBindValue.Entity(v.toString())
    is kotlin.collections.Set<*> -> MiniGrafBindValue.Value(MiniGrafValue.Set(v.map { setElement(name, it) }))
    else -> throw IllegalArgumentException(
        "slot \$$name: cannot bind a ${v::class.java.name}; use Boolean, an integer or " +
            "floating type, String, null, UUID, Set, a MiniGrafValue or a MiniGrafBindValue",
    )
}

/** An element of a `Set` bind value. A set element is passed on, for core's API-021. */
private fun setElement(name: String, e: Any?): MiniGrafValue = when (e) {
    is UUID -> MiniGrafValue.Ref(e.toString())
    else -> when (val b = bindValue(name, e)) {
        is MiniGrafBindValue.Value -> b.value
        else -> throw IllegalArgumentException("slot \$$name: a set element must be a value")
    }
}

private fun bindValues(bindings: Map<String, Any?>): Map<String, MiniGrafBindValue> =
    bindings.mapValues { (name, v) -> bindValue(name, v) }

/** Runs the query with [bindings] (`"slot" to value`, no `$`) and returns its JSON. */
fun MiniGrafPreparedQuery.execute(vararg bindings: Pair<String, Any?>): String =
    execute(bindValues(bindings.toMap()))

/** Runs the query with [bindings] and returns a cursor over its rows. */
fun MiniGrafPreparedQuery.query(vararg bindings: Pair<String, Any?>): MiniGrafCursor =
    query(bindValues(bindings.toMap()))

/** Builds [OpenOptions] fluently, for Java. */
class OpenOptionsBuilder internal constructor() {
    private var options = OpenOptions()

    fun readOnly(value: Boolean) = apply { options = options.copy(readOnly = value) }
    fun pageCacheSize(pages: Long) = apply { options = options.copy(pageCacheSize = pages) }
    fun allowUnlocked(value: Boolean) = apply { options = options.copy(allowUnlocked = value) }
    fun walCheckpointThreshold(entries: Long) = apply { options = options.copy(walCheckpointThreshold = entries) }
    fun maxDerivedFacts(n: Long) = apply { options = options.copy(maxDerivedFacts = n) }
    /** Deprecated alias of [maxDerivedFacts]; the lower of the two applies. */
    fun maxResults(n: Long) = apply { options = options.copy(maxResults = n) }
    fun synchronous(mode: SyncMode) = apply { options = options.copy(synchronous = mode) }
    /**
     * The most bytes one query may hold; a query that would hold more fails with
     * QRY-012. `Long.MAX_VALUE` means no limit, the default.
     */
    fun queryMemoryLimit(bytes: Long) = apply { options = options.copy(queryMemoryLimit = bytes) }
    fun build(): OpenOptions = options
}

/** Builds a [FactFilter] fluently, for Java. */
class FactFilterBuilder internal constructor() {
    private var filter = FactFilter()

    fun attributes(vararg idents: String) = apply {
        filter = filter.copy(attributes = (filter.attributes ?: emptyList()) + idents)
    }
    fun attributePrefix(prefix: String) = apply {
        filter = filter.copy(attributePrefixes = (filter.attributePrefixes ?: emptyList()) + prefix)
    }
    fun entities(vararg uuids: String) = apply {
        filter = filter.copy(entities = (filter.entities ?: emptyList()) + uuids)
    }
    /** Keep records with `from <= txCount <= to`. */
    fun txRange(from: Long, to: Long) = apply { filter = filter.copy(txFrom = from, txTo = to) }
    fun order(order: FactOrder) = apply { filter = filter.copy(order = order) }
    fun window(records: Long) = apply { filter = filter.copy(window = records) }
    fun build(): FactFilter = filter
}

/** The cursor's batches (JSON arrays of rows), [batchSize] rows each. */
fun MiniGrafCursor.batches(batchSize: Int = 1000): Iterable<String> = Iterable {
    generateSequence { nextBatch(batchSize) }.iterator()
}

/** The log's records, read [batchSize] at a time. */
fun MiniGrafFactLog.records(batchSize: Int = 1000): Iterable<FactRecord> = Iterable {
    generateSequence { nextBatch(batchSize) }.flatten().iterator()
}

/** The error's text, starting with its code, such as `[API-014]`. */
val MiniGrafException.text: String
    get() = when (this) {
        is MiniGrafException.Storage -> msg
        is MiniGrafException.Query -> msg
        is MiniGrafException.Parse -> msg
        is MiniGrafException.Other -> msg
        is MiniGrafException.Api -> msg
    }
