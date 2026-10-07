package io.github.project_minigraf.minigraf

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = jacksonObjectMapper()

private const val QUERY = "(query [:find ?e ?n :where [?e :n ?n]])"
private const val REF = "00000000-0000-4000-8000-000000000001"

/** Cursors (#462), open options (#465), the fact log and the log writer (#467). */
class EtlTest {
    @TempDir
    lateinit var dir: File

    private fun path(name: String) = File(dir, name).absolutePath

    private fun code(e: MiniGrafException): String = e.text

    private fun rows(s: String): List<List<Any>> = json.readValue(s)

    private fun numbered(path: String, n: Int): MiniGrafDb {
        val db = MiniGrafDb.open(path)
        for (i in 0 until n) db.execute("(transact [[:e$i :n $i]])")
        return db
    }

    private fun source(path: String): MiniGrafDb {
        val db = MiniGrafDb.open(path)
        db.execute(
            """(transact {:valid-from "2024-01-01" :valid-to "2025-01-01"} [[:alice :name "Alice"] [:alice :tag :t/admin]])""",
        )
        db.execute("""(transact [[:bob :friend #uuid "$REF"] [:bob :score 2.5]])""")
        db.execute("""(retract [[:alice :name "Alice"]])""")
        return db
    }

    private fun log(db: MiniGrafDb, filter: FactFilter = FactFilter()): List<FactRecord> =
        db.factLog(filter).use { it.records(100).toList() }

    @Test
    fun cursorBatchesMatchExecute() {
        val db = numbered(path("c.graph"), 25)
        @Suppress("UNCHECKED_CAST")
        val expected = (json.readValue<Map<String, Any>>(db.execute(QUERY))["results"] as List<List<Any>>)
            .map { it.toString() }.sorted()
        for (size in listOf(1, 7, 1000)) {
            db.query(QUERY).use { cursor ->
                assertEquals(listOf("?e", "?n"), cursor.vars())
                val got = cursor.batches(size).flatMap { batch ->
                    rows(batch).also { assertTrue(it.isNotEmpty() && it.size <= size) }
                }
                assertEquals(expected, got.map { it.toString() }.sorted())
            }
        }
    }

    @Test
    fun cursorIsFixedAtOpenAndReleases() {
        val db = numbered(path("c.graph"), 1)
        val cursor = db.query(QUERY)
        db.execute("(transact [[:late :n 99]])")
        assertEquals(listOf(0), cursor.batches(10).flatMap { rows(it) }.map { it[1] })

        val second = db.query(QUERY)
        second.release()
        assertNull(second.nextBatch(1))
        val e = assertThrows<MiniGrafException> { db.query("(transact [[:a :n 1]])") }
        assertTrue(code(e).startsWith("[API-012]"))
    }

    @Test
    fun readOnlyOpen() {
        val p = path("ro.graph")
        numbered(p, 3).use { it.checkpoint() }
        val ro = Minigraf.openWithOptions(p, Minigraf.options().readOnly(true).pageCacheSize(16).build())
        assertEquals(3, ro.query(QUERY).batches(10).flatMap { rows(it) }.size)
        val e = assertThrows<MiniGrafException> { ro.execute("(transact [[:x :n 9]])") }
        assertTrue(code(e).startsWith("[API-014]"))

        val ro2 = MiniGrafDb.openWithOptions(p, OpenOptions(readOnly = true))
        assertEquals(3L, ro2.currentTxCount())
        val locked = assertThrows<MiniGrafException> { MiniGrafDb.open(p) }
        assertTrue(code(locked).startsWith("[STG-02"))

        val missing = path("missing.graph")
        val m = assertThrows<MiniGrafException> { MiniGrafDb.openWithOptions(missing, OpenOptions(readOnly = true)) }
        assertTrue(code(m).startsWith("[STG-042]"))
        assertFalse(File(missing).exists())
    }

    @Test
    fun factLogRecordsAndFilters() {
        val db = source(path("s.graph"))
        val records = log(db)
        assertEquals(5, records.size)
        assertEquals(records.map { it.txCount }.sorted(), records.map { it.txCount })
        assertEquals(MiniGrafValue.Ref(REF), records.first { it.attribute == ":friend" }.value)
        assertEquals(MiniGrafValue.Keyword(":t/admin"), records.first { it.attribute == ":tag" }.value)
        assertEquals(Minigraf.VALID_TIME_FOREVER, records.first { it.attribute == ":score" }.validTo)
        assertEquals(1, records.count { !it.asserted })

        assertEquals(setOf(":name"), log(db, Minigraf.filter().attributes(":name").build()).map { it.attribute }.toSet())
        assertEquals(setOf(2L), log(db, Minigraf.filter().txRange(2, 2).build()).map { it.txCount }.toSet())
        assertEquals(5, log(db, Minigraf.filter().order(FactOrder.STORAGE).build()).size)
        val e = assertThrows<MiniGrafException> { db.factLog(Minigraf.filter().entities("alice").build()) }
        assertTrue(code(e).startsWith("[API-017]"))
    }

    @Test
    fun logWriterRoundTripAndHole() {
        val src = source(path("s.graph"))
        val records = log(src)
        val out = path("out.graph")
        Minigraf.createLogWriter(out, OpenOptions()).use { w ->
            w.append(records[0])
            w.appendBatch(records.drop(1))
            w.advanceTxCount(src.currentTxCount())
            assertEquals(3L, w.txCount())
            w.finish()
        }
        val copy = MiniGrafDb.openWithOptions(out, OpenOptions(readOnly = true))
        assertEquals(src.currentTxCount(), copy.currentTxCount())
        assertEquals(records, log(copy))

        val hole = path("hole.graph")
        MiniGrafLogWriter.create(hole, OpenOptions()).use { w ->
            w.appendBatch(records.filter { it.txCount != 2L })
            w.advanceTxCount(3)
            w.finish()
        }
        val h = MiniGrafDb.openWithOptions(hole, OpenOptions(readOnly = true))
        assertEquals(setOf(1L, 3L), log(h).map { it.txCount }.toSet())
        assertEquals(
            h.execute("(query [:find ?a :as-of 1 :any-valid-time :where [?e ?a _]])"),
            h.execute("(query [:find ?a :as-of 2 :any-valid-time :where [?e ?a _]])"),
        )
    }

    @Test
    fun logWriterErrorsAndAbandon() {
        val records = log(source(path("s.graph")))
        val out = path("err.graph")
        val w = MiniGrafLogWriter.create(out, OpenOptions())
        w.append(records.last())
        val order = assertThrows<MiniGrafException> { w.appendBatch(listOf(records.last(), records.first())) }
        assertTrue(code(order).startsWith("[API-015]") && code(order).endsWith("(batch index 1)"))
        val bad = records.first().copy(entity = "alice", txCount = 9)
        assertTrue(code(assertThrows<MiniGrafException> { w.append(bad) }).startsWith("[API-017]"))
        w.finish()
        assertTrue(code(assertThrows<MiniGrafException> { w.finish() }).startsWith("[API-018]"))
        w.close()

        assertTrue(code(assertThrows<MiniGrafException> { MiniGrafLogWriter.create(out, OpenOptions()) }).startsWith("[STG-043]"))

        val abandoned = path("abandoned.graph")
        MiniGrafLogWriter.create(abandoned, OpenOptions()).use { it.appendBatch(records) }
        assertFalse(File(abandoned).exists())
        assertFalse(File("$abandoned.partial").exists())

        val explicit = path("explicit.graph")
        val w2 = MiniGrafLogWriter.create(explicit, OpenOptions())
        w2.appendBatch(records)
        w2.abandon()
        assertFalse(w2.isOpen())
        assertFalse(File("$explicit.partial").exists())
    }
}
