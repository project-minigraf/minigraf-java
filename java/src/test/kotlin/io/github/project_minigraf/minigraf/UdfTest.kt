package io.github.project_minigraf.minigraf

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = jacksonObjectMapper()

private fun rows(s: String): List<List<Any?>> = json.readValue<Map<String, Any>>(s)["results"]
    .let { @Suppress("UNCHECKED_CAST") (it as List<List<Any?>>) }
    .sortedBy { it.toString() }

private object IsEven : MiniGrafPredicate {
    override fun test(value: MiniGrafValue): Boolean = when (value) {
        is MiniGrafValue.Int64 -> value.value % 2 == 0L
        else -> throw MiniGrafUdfException.Failed("not an integer")
    }
}

/** Sums integers; `step` throws on [failOn]. */
private class Total(private val failOn: Long? = null) : MiniGrafAggregate {
    override fun accumulator(): MiniGrafAccumulator = object : MiniGrafAccumulator {
        private var sum = 0L

        override fun step(value: MiniGrafValue) {
            val n = (value as MiniGrafValue.Int64).value
            if (n == failOn) throw MiniGrafUdfException.Failed("refused $n")
            sum += n
        }

        override fun finish(count: Long): MiniGrafValue = MiniGrafValue.Int64(sum)
    }
}

/** User functions (minigraf#180): predicates and aggregates written in Kotlin. */
class UdfTest {

    private fun db(): MiniGrafDb {
        val db = MiniGrafDb.openInMemory()
        db.execute(
            "(transact [[:a :n 1] [:b :n 2] [:c :n 3] [:d :n 4]" +
                " [:a :g 1] [:b :g 1] [:c :g 2] [:d :g 2]])",
        )
        return db
    }

    private fun queryError(block: () -> Unit): String =
        (assertThrows<MiniGrafException.Query> { block() }).text

    @Test
    fun predicateFiltersRows() {
        val db = db()
        db.registerPredicate("even?", IsEven)
        assertEquals(listOf(listOf<Any?>(2), listOf<Any?>(4)), rows(db.execute("(query [:find ?n :where [?e :n ?n] [(even? ?n)]])")))
    }

    @Test
    fun aggregateInFindAndOver() {
        val db = db()
        db.registerAggregate("total", Total())
        assertEquals(listOf(listOf<Any?>(10)), rows(db.execute("(query [:find (total ?n) :where [?e :n ?n]])")))
        val over = db.execute(
            "(query [:find ?n (total ?n :over (:partition-by ?g :order-by ?n))" +
                " :where [?e :n ?n] [?e :g ?g]])",
        )
        // A running total per group: 1, 1+2 in group 1; 3, 3+4 in group 2.
        assertEquals(listOf(listOf<Any?>(1, 1), listOf<Any?>(2, 3), listOf<Any?>(3, 3), listOf<Any?>(4, 7)), rows(over))
    }

    @Test
    fun udfExceptionFailsTheQueryWithQry014() {
        val db = db()
        db.registerPredicate("even?", IsEven)
        db.execute("(transact [[:e :n \"x\"]])")
        val msg = queryError { db.execute("(query [:find ?n :where [?e :n ?n] [(even? ?n)]])") }
        assertTrue(msg.startsWith("[QRY-014]"), "starts with QRY-014")
        assertTrue(msg.contains("not an integer"), "carries the callback's message")
    }

    @Test
    fun anyExceptionFailsTheQueryWithQry014() {
        val db = db()
        db.registerPredicate(
            "broken?",
            object : MiniGrafPredicate {
                override fun test(value: MiniGrafValue): Boolean = throw IllegalStateException("own exception")
            },
        )
        val msg = queryError { db.execute("(query [:find ?n :where [?e :n ?n] [(broken? ?n)]])") }
        assertEquals("[QRY-014] user function `broken?` failed: java.lang.IllegalStateException: own exception", msg)
    }

    @Test
    fun aggregateStepFailureIsQry014() {
        val db = db()
        db.registerAggregate("total", Total(failOn = 2))
        val msg = queryError { db.execute("(query [:find (total ?n) :where [?e :n ?n]])") }
        assertTrue(msg.startsWith("[QRY-014]"), "starts with QRY-014")
        assertTrue(msg.contains("refused 2"), "carries the step's message")
    }

    @Test
    fun badRefFromFinishIsQry014() {
        val db = db()
        db.registerAggregate(
            "bad",
            object : MiniGrafAggregate {
                override fun accumulator(): MiniGrafAccumulator = object : MiniGrafAccumulator {
                    override fun step(value: MiniGrafValue) {}
                    override fun finish(count: Long): MiniGrafValue = MiniGrafValue.Ref("nope")
                }
            },
        )
        val msg = queryError { db.execute("(query [:find (bad ?n) :where [?e :n ?n]])") }
        assertTrue(msg.startsWith("[QRY-014]"), "starts with QRY-014")
    }

    @Test
    fun predicateMayQueryTheSameDatabase() {
        val db = db()
        db.registerPredicate(
            "g2?",
            object : MiniGrafPredicate {
                override fun test(value: MiniGrafValue): Boolean {
                    val n = (value as MiniGrafValue.Int64).value
                    return rows(db.execute("(query [:find ?e :where [?e :n $n] [?e :g 2]])")).isNotEmpty()
                }
            },
        )
        assertEquals(listOf(listOf<Any?>(3), listOf<Any?>(4)), rows(db.execute("(query [:find ?n :where [?e :n ?n] [(g2? ?n)]])")))
    }

    @Test
    fun cursorsAndPreparedQueriesCallUserFunctions() {
        val db = db()
        db.registerPredicate("even?", IsEven)
        db.registerAggregate("total", Total())
        db.query("(query [:find ?n :where [?e :n ?n] [(even? ?n)]])").use { cursor ->
            val found = cursor.batches(1).flatMap { json.readValue<List<List<Any?>>>(it) }.sortedBy { it.toString() }
            assertEquals(listOf(listOf<Any?>(2), listOf<Any?>(4)), found)
        }
        val pq = db.prepare("(query [:find (total ?n) :where [?e :g \$g] [?e :n ?n]])")
        assertEquals(listOf(listOf<Any?>(3)), rows(pq.execute("g" to 1)))
        pq.query("g" to 2).use { cursor -> assertEquals(listOf("[[7]]"), cursor.batches(10).toList()) }
    }

    @Test
    fun aPreparedQuerySeesFunctionsRegisteredBeforeEachRun() {
        val db = db()
        val pq = db.prepare("(query [:find ?n :where [?e :n ?n] [(even? ?n)]])")
        assertThrows<MiniGrafException> { pq.execute() }
        db.registerPredicate("even?", IsEven)
        assertEquals(listOf(listOf<Any?>(2), listOf<Any?>(4)), rows(pq.execute()))
    }

    @Test
    fun userFunctionFailureInACursorIsQry014() {
        val db = db()
        db.registerAggregate("total", Total(failOn = 2))
        val msg = queryError {
            db.query("(query [:find (total ?n) :where [?e :n ?n]])").use { it.batches(10).toList() }
        }
        assertTrue(msg.startsWith("[QRY-014]"), "starts with QRY-014")
    }

    @Test
    fun aNameRegistersOnce() {
        val db = db()
        db.registerPredicate("even?", IsEven)
        val e = assertThrows<MiniGrafException> { db.registerPredicate("even?", IsEven) }
        assertTrue(e.text.startsWith("[INT-040]"), "duplicate name is INT-040")
    }

    @Test
    fun twoQueriesAreInOnePredicateAtOnce() {
        // Each call waits until a call from the other thread arrives: this only
        // finishes if neither query holds a binding lock while the core runs.
        val db = db()
        val barrier = CyclicBarrier(2)
        db.registerPredicate(
            "meet?",
            object : MiniGrafPredicate {
                override fun test(value: MiniGrafValue): Boolean {
                    barrier.await(10, TimeUnit.SECONDS)
                    return true
                }
            },
        )
        val results = ConcurrentLinkedQueue<List<List<Any?>>>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (1..2).map {
            thread {
                try {
                    results.add(rows(db.execute("(query [:find ?n :where [:a :n ?n] [(meet? ?n)]])")))
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), "no thread failed")
        assertEquals(listOf(listOf(listOf<Any?>(1)), listOf(listOf<Any?>(1))), results.toList())
    }
}
