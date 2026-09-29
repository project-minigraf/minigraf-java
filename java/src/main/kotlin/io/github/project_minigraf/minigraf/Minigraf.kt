package io.github.project_minigraf.minigraf

/**
 * Static entry points for Java callers.
 *
 * The UniFFI-generated factories live on the Kotlin companion object, so from Java
 * they read `MiniGrafDb.Companion.open(path)`. This facade lets Java write
 * `Minigraf.open(path)` instead. Kotlin callers can use either.
 */
object Minigraf {
    /** Opens (or creates) a file-backed database at [path]. */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun open(path: String): MiniGrafDb = MiniGrafDb.open(path)

    /** Opens an ephemeral in-memory database. */
    @JvmStatic
    @Throws(MiniGrafException::class)
    fun openInMemory(): MiniGrafDb = MiniGrafDb.openInMemory()
}
