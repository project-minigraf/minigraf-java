package io.github.project_minigraf.minigraf

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
}

/** Builds [OpenOptions] fluently, for Java. */
class OpenOptionsBuilder internal constructor() {
    private var options = OpenOptions()

    fun readOnly(value: Boolean) = apply { options = options.copy(readOnly = value) }
    fun pageCacheSize(pages: Long) = apply { options = options.copy(pageCacheSize = pages) }
    fun allowUnlocked(value: Boolean) = apply { options = options.copy(allowUnlocked = value) }
    fun walCheckpointThreshold(entries: Long) = apply { options = options.copy(walCheckpointThreshold = entries) }
    fun maxDerivedFacts(n: Long) = apply { options = options.copy(maxDerivedFacts = n) }
    fun maxResults(n: Long) = apply { options = options.copy(maxResults = n) }
    fun synchronous(mode: SyncMode) = apply { options = options.copy(synchronous = mode) }
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
    }
