package io.github.project_minigraf.minigraf

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = jacksonObjectMapper()

/** Prepared queries (minigraf#181). */
class PreparedTest {

    private fun rows(s: String): List<List<Any?>> = json.readValue<Map<String, Any>>(s)["results"]
        .let { @Suppress("UNCHECKED_CAST") (it as List<List<Any?>>) }

    private fun entityA(db: MiniGrafDb): String =
        rows(db.execute("(query [:find ?e :where [?e :s \"x\"]])"))[0][0] as String

    private fun db(): MiniGrafDb {
        val db = MiniGrafDb.openInMemory()
        db.execute(
            "(transact [[:a :n 1] [:b :n 2] [:c :n 3] [:a :s \"x\"] [:a :f 2.5] [:a :w 2.0]" +
                " [:a :t true] [:a :k :kw] [:a :z nil]])",
        )
        return db
    }

    @Test
    fun preparedQueryRunsAgainWithNewBindings() {
        val pq = db().prepare("(query [:find ?x :where [?e :n \$n] [?e :n ?x]])")
        for (n in 1..3) assertEquals(listOf(listOf<Any?>(n)), rows(pq.execute("n" to n)))
        assertEquals(0, rows(pq.execute("n" to 9L)).size)
    }

    @Test
    fun everyPlainValueBinds() {
        val db = db()
        for ((attr, value) in listOf(
            ":n" to 1, ":n" to 1L, ":n" to 1.toShort(), ":n" to 1.toByte(),
            ":s" to "x", ":f" to 2.5, ":f" to 2.5f, ":w" to 2.0, ":t" to true, ":z" to null,
        )) {
            val pq = db.prepare("(query [:find ?e :where [?e $attr \$v]])")
            assertEquals(1, rows(pq.execute("v" to value)).size, "$attr $value")
        }
    }

    @Test
    fun uuidBindsAnEntityAnywhere() {
        val db = db()
        val a = UUID.fromString(entityA(db))
        assertEquals(1, rows(db.prepare("(query [:find ?x :where [\$e :n ?x]])").execute("e" to a)).size)
        db.execute("(transact [[:b :friend #uuid \"$a\"]])")
        db.execute("(rule [(friend-of ?x ?f) [?x :friend ?f]])")
        for (q in listOf("(query [:find ?x :where [?x :friend \$f]])", "(query [:find ?x :where (friend-of ?x \$f)])")) {
            assertEquals(1, rows(db.prepare(q).execute("f" to a)).size, q)
        }
    }

    @Test
    fun everyTypedValueBinds() {
        val db = db()
        val a = entityA(db)
        for ((q, value) in listOf(
            "(query [:find ?e :where [?e :k \$v]])" to MiniGrafValue.Keyword(":kw"),
            "(query [:find ?x :where [\$v :n ?x]])" to MiniGrafValue.Ref(a),
            "(query [:find ?x :where [\$v :n ?x]])" to MiniGrafBindValue.Entity(a),
            "(query [:find ?x :as-of \$v :where [?e :n ?x]])" to MiniGrafBindValue.TxCount(1),
            "(query [:find ?x :as-of \$v :where [?e :n ?x]])" to MiniGrafBindValue.Timestamp(Long.MAX_VALUE),
            "(query [:find ?x :valid-at \$v :where [:a :n ?x]])" to MiniGrafBindValue.AnyValidTime,
        )) {
            assertTrue(rows(db.prepare(q).execute("v" to value)).isNotEmpty(), q)
        }
    }

    @Test
    fun preparedCursorMatchesExecute() {
        val pq = db().prepare("(query [:find ?e ?n :where [?e :n ?n] [(>= ?n \$min)]])")
        val expected = rows(pq.execute("min" to 2))
        val seen = pq.query("min" to 2).use { c -> c.batches(1).sumOf { rows("{\"results\":$it}").size } }
        assertEquals(2, expected.size)
        assertEquals(expected.size, seen)
    }

    @Test
    fun unsupportedPlainValueIsIllegalArgument() {
        val pq = db().prepare("(query [:find ?e :where [?e :n \$n]])")
        assertThrows<IllegalArgumentException> { pq.execute("n" to listOf(1)) }
    }

    @Test
    fun bindMistakesAreApiErrors() {
        val db = db()
        val pq = db.prepare("(query [:find ?e :where [?e :n \$n]])")
        fun code(e: MiniGrafException.Api) = e.text.take(9)
        assertEquals("[API-022]", code(assertThrows<MiniGrafException.Api> { pq.execute() }))
        assertEquals("[API-022]", code(assertThrows<MiniGrafException.Api> { pq.query() }))
        assertEquals(
            "[API-024]",
            code(assertThrows<MiniGrafException.Api> { pq.execute("n" to MiniGrafBindValue.TxCount(1)) }),
        )
        assertEquals(
            "[API-023]",
            code(assertThrows<MiniGrafException.Api> { db.prepare("(query [:find ?v :where [?e \$a ?v]])") }),
        )
        assertEquals(
            "[API-017]",
            code(assertThrows<MiniGrafException.Api> { pq.execute("n" to MiniGrafBindValue.TxCount(-1)) }),
        )
    }

    @Test
    fun preparedQuerySeesLaterWrites() {
        val db = db()
        val pq = db.prepare("(query [:find ?e :where [?e :n \$n]])")
        assertEquals(0, rows(pq.execute("n" to 4)).size)
        db.execute("(transact [[:d :n 4]])")
        assertEquals(1, rows(pq.execute("n" to 4)).size)
    }
}
