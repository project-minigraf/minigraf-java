package io.github.project_minigraf.minigraf

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val txMapper = jacksonObjectMapper()

/** Write transactions (minigraf#824). */
class TransactionTest {
    @TempDir
    lateinit var dir: Path

    private val names = "(query [:find ?n :where [?e :person/name ?n]])"
    private val setup = "(transact [[:alice :person/name \"Alice\"] [:bob :person/name \"Bob\"]])"

    private fun namesOf(json: String): List<String> =
        (txMapper.readValue<Map<String, Any>>(json)["results"] as List<*>)
            .map { (it as List<*>)[0] as String }
            .sorted()

    private fun code(e: MiniGrafException): String = e.text.substring(1, 8)

    private fun memDb(): MiniGrafDb = Minigraf.openInMemory().also { it.execute(setup) }

    @Test
    fun commitReadsItsOwnWritesAndSurvivesReopen() {
        val path = dir.resolve("g.graph").toString()
        Minigraf.open(path).use { db ->
            db.execute(setup)
            val tx = db.beginWrite()
            assertEquals("{\"ok\":true}", tx.execute("(transact [[:carol :person/name \"Carol\"]])"))
            tx.execute("(retract [[:bob :person/name \"Bob\"]])")
            assertEquals(listOf("Alice", "Carol"), namesOf(tx.execute(names)))
            var outside: List<String>? = null
            thread { outside = namesOf(db.execute(names)) }.join(30_000)
            assertEquals(listOf("Alice", "Bob"), outside, "another thread reads the committed state")
            tx.commit()
            assertFalse(tx.isOpen())
            tx.close()
            assertEquals(listOf("Alice", "Carol"), namesOf(db.execute(names)))
        }
        Minigraf.open(path).use { db ->
            assertEquals(listOf("Alice", "Carol"), namesOf(db.execute(names)), "after reopening")
        }
    }

    @Test
    fun useRollsBackUnlessCommitted() {
        val db = memDb()
        db.beginWrite().use { tx -> tx.execute("(transact [[:carol :person/name \"Carol\"]])") }
        assertEquals(listOf("Alice", "Bob"), namesOf(db.execute(names)), "no commit on a normal exit")
        assertThrows<IllegalStateException> {
            db.beginWrite().use { tx ->
                tx.execute("(transact [[:dave :person/name \"Dave\"]])")
                throw IllegalStateException("boom")
            }
        }
        assertEquals(listOf("Alice", "Bob"), namesOf(db.execute(names)), "rolled back on an exception")
        db.execute("(transact [[:erin :person/name \"Erin\"]])") // the lock is free
        db.beginWrite().use { tx ->
            tx.execute("(transact [[:frank :person/name \"Frank\"]])")
            tx.commit()
        }
        assertEquals(listOf("Alice", "Bob", "Erin", "Frank"), namesOf(db.execute(names)))
    }

    @Test
    fun rollbackAndFinishedTransaction() {
        val db = memDb()
        val tx = db.beginWrite()
        tx.execute("(transact [[:carol :person/name \"Carol\"]])")
        tx.rollback()
        assertEquals(listOf("Alice", "Bob"), namesOf(db.execute(names)))
        assertEquals("API-018", code(assertThrows<MiniGrafException> { tx.execute(names) }))
        assertEquals("API-018", code(assertThrows<MiniGrafException> { tx.commit() }))
        tx.rollback() // does nothing
        tx.close()
    }

    @Test
    fun failedStatementStaysOpen() {
        val db = memDb()
        db.beginWrite().use { tx ->
            assertEquals("PRS-004", code(assertThrows<MiniGrafException> { tx.execute("(transact [[:a") }))
            tx.execute("(transact [[:carol :person/name \"Carol\"]])")
            tx.commit()
        }
        assertEquals(listOf("Alice", "Bob", "Carol"), namesOf(db.execute(names)))
    }

    @Test
    fun beginThreadGetsInt001AndOtherThreadsWait() {
        val db = memDb()
        val tx = db.beginWrite()
        assertEquals("INT-001", code(assertThrows<MiniGrafException> { db.execute(names) }))
        var wrote = false
        val writer = thread { db.execute("(transact [[:w :person/name \"W\"]])"); wrote = true }
        writer.join(300)
        assertFalse(wrote, "a write from another thread waits")
        tx.commit()
        writer.join(30_000)
        assertEquals(listOf("Alice", "Bob", "W"), namesOf(db.execute(names)))
    }

    @Test
    fun options() {
        val db = memDb()
        assertEquals(
            "API-017",
            code(assertThrows<MiniGrafException> { db.beginWrite(Minigraf.txOptions().chunkFacts(-1).build()) }),
        )
        val path = dir.resolve("chunk.graph").toString()
        Minigraf.open(path).use { file ->
            file.beginWrite(TxOptions(synchronous = SyncMode.NORMAL, chunkFacts = 2)).use { tx ->
                for (i in 0 until 5) tx.execute("(transact [[:p$i :person/name \"P$i\"]])")
                tx.commit()
            }
        }
        Minigraf.open(path).use { file -> assertEquals(5, namesOf(file.execute(names)).size) }
        val ro = Minigraf.openWithOptions(path, Minigraf.options().readOnly(true).build())
        assertEquals("API-014", code(assertThrows<MiniGrafException> { ro.beginWrite() }))
        ro.close()
    }

    /** minigraf#886: such a call waited on the transaction forever. */
    @Test
    fun userFunctionCallingItsOwnTransactionGetsInt001() {
        val db = memDb()
        val tx = db.beginWrite()
        val seen = mutableListOf<String>()
        db.registerPredicate(
            "calls-tx?",
            object : MiniGrafPredicate {
                override fun test(value: MiniGrafValue): Boolean {
                    val calls: List<() -> Unit> = listOf({ tx.execute(names) }, { tx.commit() }, { tx.rollback() })
                    for (call in calls) {
                        seen += try {
                            call()
                            "no error"
                        } catch (e: MiniGrafException) {
                            code(e)
                        }
                    }
                    seen += tx.isOpen().toString()
                    return true
                }
            },
        )
        val rows = tx.execute("(query [:find ?n :where [?e :person/name ?n] [(calls-tx? ?n)]])")
        assertEquals(listOf("Alice", "Bob"), namesOf(rows))
        val perRow = listOf("INT-001", "INT-001", "INT-001", "true")
        assertEquals(perRow + perRow, seen)
        // The refused commit and rollback left it open.
        assertEquals(true, tx.isOpen())
        tx.execute("(transact [[:carol :person/name \"Carol\"]])")
        tx.commit()
        assertEquals(listOf("Alice", "Bob", "Carol"), namesOf(db.execute(names)))
    }
}
