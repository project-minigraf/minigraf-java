package io.github.project_minigraf.minigraf

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val mapper = jacksonObjectMapper()

/** Override list, transactionChunkFacts and maxIterations, and the prepared limit setters (minigraf#872). */
class OverridesTest {
    @TempDir
    lateinit var dir: Path

    private val reach = "(query [:find ?x ?y :where (reach ?x ?y)])"
    private val grouped = "(query [:find ?v (count ?e) :where [?e :v ?v]])"
    private val derivedKeyword = "(query [:find ?x ?y :where (reach ?x ?y) :max-derived-facts 100])"
    private val memoryKeyword = "(query [:find ?x ?y :where (reach ?x ?y) :query-memory-limit 1000000])"

    private fun rows(s: String): Int =
        (mapper.readValue<Map<String, Any>>(s)["results"] as List<*>).size

    private fun code(e: MiniGrafException): String = e.text.substring(1, 8)

    private fun reachDb(name: String, options: OpenOptionsBuilder = Minigraf.options()): MiniGrafDb {
        val db = Minigraf.openWithOptions(dir.resolve("$name.graph").toString(), options.build())
        db.execute(
            "(transact [[:a :edge :b] [:b :edge :c] [:c :edge :d] [:d :edge :e]" +
                " [:p :v 1] [:q :v 1] [:r :v 2]])",
        )
        db.execute("(rule [(reach ?x ?y) [?x :edge ?y]])")
        db.execute("(rule [(reach ?x ?y) [?x :edge ?z] (reach ?z ?y)])")
        return db
    }

    @Test
    fun defaultsAreReadFromCore() {
        assertEquals(
            listOf(Overridable.MAX_DERIVED_FACTS, Overridable.MAX_RESULTS, Overridable.MAX_ITERATIONS),
            Minigraf.OVERRIDABLE_DEFAULTS,
        )
    }

    @Test
    fun allowOverridesReplacesTheDefaults() {
        val cases = listOf(
            Triple("absent", Minigraf.options(), true to false),
            Triple("defaults", Minigraf.options().allowOverrides(Minigraf.OVERRIDABLE_DEFAULTS), true to false),
            Triple("empty", Minigraf.options().allowOverrides(), false to false),
            Triple(
                "memory",
                Minigraf.options().allowOverrides(Overridable.QUERY_MEMORY_LIMIT, Overridable.QUERY_MEMORY_LIMIT),
                false to true,
            ),
            Triple("all", Minigraf.options().allowOverrides(Overridable.entries), true to true),
        )
        for ((name, options, ok) in cases) {
            val db = reachDb(name, options)
            for ((q, allowed) in listOf(derivedKeyword to ok.first, memoryKeyword to ok.second)) {
                if (allowed) {
                    assertEquals(10, rows(db.execute(q)), name)
                    db.query(q).use { }
                    db.prepare(q).use { }
                } else {
                    assertEquals("API-025", code(assertThrows<MiniGrafException.Api> { db.execute(q) }), name)
                    assertEquals("API-025", code(assertThrows<MiniGrafException.Api> { db.query(q) }), name)
                    assertEquals("API-025", code(assertThrows<MiniGrafException.Api> { db.prepare(q) }), name)
                }
            }
            db.close()
        }
    }

    @Test
    fun transactionChunkFactsCommitsALargeTransaction() {
        val path = dir.resolve("chunk.graph").toString()
        Minigraf.openWithOptions(path, Minigraf.options().transactionChunkFacts(2).build()).use { db ->
            db.execute("(transact [" + (0 until 10).joinToString("") { "[:e$it :n $it]" } + "])")
        }
        Minigraf.open(path).use { db ->
            assertEquals(10, rows(db.execute("(query [:find ?n :where [?e :n ?n]])")))
        }
        val e = assertThrows<MiniGrafException.Api> {
            Minigraf.openWithOptions(
                dir.resolve("bad.graph").toString(),
                Minigraf.options().transactionChunkFacts(-1).build(),
            )
        }
        assertEquals("API-017", code(e))
    }

    @Test
    fun maxIterationsOption() {
        assertEquals(10, rows(reachDb("default").execute(reach)))
        val e = assertThrows<MiniGrafException> { reachDb("one", Minigraf.options().maxIterations(1)).execute(reach) }
        assertEquals("INT-020", code(e))
        assertEquals(10, rows(reachDb("max", Minigraf.options().maxIterations(Long.MAX_VALUE)).execute(reach)))
        val bad = assertThrows<MiniGrafException.Api> { reachDb("bad", Minigraf.options().maxIterations(-1)) }
        assertEquals("API-017", code(bad))
    }

    @Test
    fun logWriterTakesTheNewOptions() {
        val out = dir.resolve("out.graph")
        val options = Minigraf.options().allowOverrides().transactionChunkFacts(2).maxIterations(5).build()
        Minigraf.createLogWriter(out.toString(), options).use { it.finish() }
        assertTrue(Files.exists(out))
    }

    @Test
    fun settersDeriveNewQueries() {
        // The setters are not gated: every override keyword is refused here.
        val db = reachDb("setters", Minigraf.options().allowOverrides())
        val cases = listOf<Triple<String, (MiniGrafPreparedQuery, Long) -> MiniGrafPreparedQuery, Pair<Long, String>>>(
            Triple(reach, { q, n -> q.withMaxDerivedFacts(n) }, 1L to "INT-020"),
            Triple(reach, { q, n -> q.withMaxIterations(n) }, 1L to "INT-020"),
            Triple(grouped, { q, n -> q.withQueryMemoryLimit(n) }, 16L to "QRY-012"),
        )
        for ((query, setter, limit) in cases) {
            val pq = db.prepare(query)
            val before = rows(pq.execute())
            val derived = setter(pq, limit.first)
            assertEquals(limit.second, code(assertThrows<MiniGrafException> { derived.execute() }))
            assertEquals(
                limit.second,
                code(assertThrows<MiniGrafException> { derived.query().use { it.batches().toList() } }),
            )
            assertEquals(before, rows(pq.execute()))
            assertEquals(before, rows(setter(derived, Long.MAX_VALUE).execute()))
            assertEquals("API-017", code(assertThrows<MiniGrafException.Api> { setter(pq, -1) }))
        }
    }

    @Test
    fun chainedSettersKeepEarlierLimits() {
        val pq = reachDb("chain").prepare(reach)
        val chained = pq.withMaxDerivedFacts(1).withMaxIterations(1).withMaxIterations(Long.MAX_VALUE)
        assertEquals("INT-020", code(assertThrows<MiniGrafException> { chained.execute() }))
        assertEquals(10, rows(chained.withMaxDerivedFacts(Long.MAX_VALUE).execute()))
    }

    @Test
    fun derivedQueryKeepsItsDatabaseOpen() {
        val db = reachDb("alive")
        val pq = db.prepare(reach)
        val derived = pq.query().use { cursor ->
            val d = pq.withMaxIterations(10)
            assertEquals(10, cursor.batches(3).sumOf { mapper.readValue<List<Any>>(it).size })
            d
        }
        pq.close()
        db.close()
        assertEquals(10, rows(derived.execute()))
    }
}
