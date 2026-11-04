package io.github.project_minigraf.benchmark

import io.github.project_minigraf.minigraf.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Source-build qualification for minigraf#839; no benchmark framework in the library. */
class Benchmark(private val mode: String, private val directory: File, private val provenance: File) {
    private val expected = (0L until 1000L).toList()
    private val scalarQuery = "(query [:find ?n :where [?e :bench/n ?n]])"
    private val pointQuery = "(query [:find ?n :where [?e :bench/n \$n] [?e :bench/n ?n]])"
    private val transaction = "(transact [" + (0 until 1000).joinToString(" ") {
        "[:bench/e$it :bench/n $it]"
    } + "])"
    private val output = File(directory, "minigraf-bench.jsonl")
    private var timed = false
    private val completed = mutableListOf<String>()

    private fun emit(record: JSONObject) {
        val line = record.toString()
        require(line.toByteArray(Charsets.UTF_8).size < 3800) { "log record too large" }
        output.appendText(line + "\n")
        println(line)
    }

    private fun integers(rows: JSONArray): List<Long> = (0 until rows.length()).map {
        val row = rows.getJSONArray(it)
        require(row.length() == 1) { "unexpected projection" }
        val value = row.get(0)
        require(value is Int || value is Long) { "expected integer result" }
        (value as Number).toLong()
    }

    private fun result(json: String) = integers(JSONObject(json).getJSONArray("results"))

    private fun drain(cursor: MiniGrafCursor, batch: Int): List<Long> = cursor.use {
        val values = mutableListOf<Long>()
        while (true) {
            val json = cursor.nextBatch(batch) ?: break
            val rows = JSONArray(json)
            require(rows.length() in 1..batch) { "invalid cursor batch size" }
            values += integers(rows)
        }
        values
    }

    private fun records(db: MiniGrafDb): List<FactRecord> =
        db.factLog(FactFilter(attributes = listOf(":bench/n"))).use { log ->
            val all = mutableListOf<FactRecord>()
            while (true) {
                val batch = log.nextBatch(256) ?: break
                require(batch.size in 1..256) { "invalid fact-log batch size" }
                all += batch
            }
            all
        }

    private fun checkRecords(rows: List<FactRecord>) {
        ResultChecks.numbers(rows.map { (it.value as MiniGrafValue.Int64).value }, expected)
        require(rows.all { it.asserted && it.attribute == ":bench/n" && it.txCount == 1L })
        require(rows.map { it.entity }.toSet().size == 1000)
    }

    private val even = object : MiniGrafPredicate {
        override fun test(value: MiniGrafValue) = (value as MiniGrafValue.Int64).value % 2 == 0L
    }
    private val sum = object : MiniGrafAggregate {
        override fun accumulator() = object : MiniGrafAccumulator {
            private var total = 0L
            override fun step(value: MiniGrafValue) { total += (value as MiniGrafValue.Int64).value }
            override fun finish(count: Long): MiniGrafValue {
                check(count == 1000L) { "unexpected aggregate callback count" }
                return MiniGrafValue.Int64(total)
            }
        }
    }

    // Each operation is timed independently. Validation, reporting, setup and
    // teardown run outside the timer. No synthetic timings are emitted in smoke mode.
    private fun <S, T> measure(
        name: String, iterations: Int = 20, setup: () -> S,
        action: (S) -> T, validate: (T) -> Unit, teardown: (S) -> Unit = {},
    ) {
        val id = "binding/jvm/$name"
        fun invoke(): Long {
            val state = setup()
            try {
                val start = if (timed) System.nanoTime() else 0L
                val value = action(state)
                val elapsed = if (timed) System.nanoTime() - start else 0L
                validate(value)
                return elapsed
            } finally { teardown(state) }
        }
        if (timed) {
            repeat(if (iterations == 1) 5 else 100) { invoke() }
            repeat(10) { sample ->
                var total = 0L
                repeat(iterations) { total += invoke() }
                emit(JSONObject().put("type", "sample").put("id", id)
                    .put("sample", sample).put("iterations", iterations)
                    .put("total_ns", total).put("ns_per_op", total.toDouble() / iterations))
            }
        } else { invoke() }
        completed += id
        emit(JSONObject().put("type", "checked").put("id", id))
    }

    private fun <T> memory(name: String, action: () -> T, validate: (T) -> Unit) =
        measure(name, setup = { Unit }, action = { action() }, validate = validate)

    fun verifyAndMeasure() {
        require(mode == "smoke" || mode == "benchmark") { "mode must be smoke or benchmark" }
        timed = mode == "benchmark"
        output.writeText("")
        completed.clear()
        val build = JSONObject(provenance.readText())
        emit(JSONObject().put("type", "environment").put("mode", mode).put("build", build)
            .put("vm", System.getProperty("java.vm.name"))
            .put("vm_version", System.getProperty("java.vm.version"))
            .put("java_version", System.getProperty("java.version"))
            .put("java_vendor", System.getProperty("java.vendor"))
            .put("jvm_arguments", JSONArray(java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments))
            .put("os", System.getProperty("os.name")).put("arch", System.getProperty("os.arch"))
            .put("warmup_memory", 100).put("warmup_storage", 5)
            .put("storage", "run-directory").put("rss_verified", false))

        val dir = File(directory, "scratch-${System.nanoTime()}")
        check(dir.mkdirs())
        try {
            MiniGrafDb.openInMemory().use { db ->
                db.execute(transaction)
                db.execute("(transact [[:bench/set :bench/tags #{1 2}]])")
                db.registerPredicate("bench-even?", even)
                db.registerAggregate("bench-sum", sum)
                db.prepare(pointQuery).use { point ->
                    memory("prepared_execute/1k", { result(point.execute("n" to 42L)) }) {
                        ResultChecks.numbers(it, listOf(42L))
                    }
                }
                db.prepare(scalarQuery).use { scan ->
                    for (batch in listOf(1, 256)) {
                        memory("cursor_batch_$batch/1k", { drain(scan.query(), batch) }) {
                            ResultChecks.numbers(it, expected)
                        }
                    }
                }
                db.prepare("(query [:find ?s :where [?e :bench/tags \$s] [?e :bench/tags ?s]])").use { set ->
                    memory("set_roundtrip/2", {
                        val rows = JSONObject(set.execute("s" to setOf(1L, 2L))).getJSONArray("results")
                        require(rows.length() == 1 && rows.getJSONArray(0).length() == 1)
                        val elements = rows.getJSONArray(0).getJSONArray(0)
                        (0 until elements.length()).map { elements.getLong(it) }
                    }) { ResultChecks.numbers(it, listOf(1L, 2L)) }
                }
                memory("predicate/1k", {
                    result(db.execute("(query [:find ?n :where [?e :bench/n ?n] [(bench-even? ?n)]])"))
                }) { ResultChecks.numbers(it, expected.filter { n -> n % 2 == 0L }) }
                memory("aggregate/1k", {
                    result(db.execute("(query [:find (bench-sum ?n) :where [?e :bench/n ?n]])"))
                }) { ResultChecks.numbers(it, listOf(499500L)) }
                memory("prepare/point", { db.prepare(pointQuery) }) { point ->
                    point.use { ResultChecks.numbers(result(it.execute("n" to 42L)), listOf(42L)) }
                }
                measure("register_predicate/first",
                    setup = { MiniGrafDb.openInMemory().apply { execute(transaction) } },
                    action = { fresh -> fresh.apply { registerPredicate("bench-even?", even) } },
                    validate = { fresh -> ResultChecks.numbers(result(fresh.execute(
                        "(query [:find ?n :where [?e :bench/n ?n] [(bench-even? ?n)]])"
                    )), expected.filter { n -> n % 2 == 0L }) },
                    teardown = { it.close() })
                measure("register_aggregate/first",
                    setup = { MiniGrafDb.openInMemory().apply { execute(transaction) } },
                    action = { fresh -> fresh.apply { registerAggregate("bench-sum", sum) } },
                    validate = { fresh -> ResultChecks.numbers(result(fresh.execute(
                        "(query [:find (bench-sum ?n) :where [?e :bench/n ?n]])"
                    )), listOf(499500L)) },
                    teardown = { it.close() })
                memory("fact_log_batch_256/1k", { records(db) }) { checkRecords(it) }
                memory("options_error/negative_cache", {
                    try {
                        MiniGrafDb.openWithOptions(File(dir, "invalid.graph").path,
                            OpenOptions(pageCacheSize = -1)).use { error("negative cache accepted") }
                    } catch (error: MiniGrafException.Api) { error.msg.take(9) }
                }) { require(it == "[API-017]") }

                val seed = File(dir, "seed.graph")
                MiniGrafDb.open(seed.path).use { it.execute(transaction); it.checkpoint() }
                memory("options_readonly_open/1k", {
                    MiniGrafDb.openWithOptions(seed.path, OpenOptions(readOnly = true,
                        pageCacheSize = 64, queryMemoryLimit = 16L * 1024 * 1024))
                }) { opened -> opened.use { ResultChecks.numbers(result(it.execute(scalarQuery)), expected) } }

                measure("file_transact/1k", iterations = 1,
                    setup = {
                        val path = File(dir, "write-${System.nanoTime()}.graph").path
                        path to MiniGrafDb.open(path)
                    },
                    action = { (_, writer) -> writer.execute(transaction) },
                    validate = { require(JSONObject(it).getLong("transacted") > 0L) },
                    teardown = { (path, writer) ->
                        writer.close()
                        MiniGrafDb.open(path).use { reopened ->
                            ResultChecks.numbers(result(reopened.execute(scalarQuery)), expected)
                        }
                    })

                val source = records(db)
                measure("log_writer_append_finish/1k", iterations = 1,
                    setup = {
                        val path = File(dir, "log-${System.nanoTime()}.graph").path
                        path to MiniGrafLogWriter.create(path, OpenOptions())
                    },
                    action = { (_, writer) -> writer.appendBatch(source); writer.finish() },
                    validate = {},
                    teardown = { (path, writer) ->
                        writer.close()
                        MiniGrafDb.open(path).use { reopened ->
                            require(records(reopened).sortedBy { it.entity } == source.sortedBy { it.entity }) {
                                "fact-log round trip changed records"
                            }
                            ResultChecks.numbers(result(reopened.execute(scalarQuery)), expected)
                        }
                    })
            }
            require(completed.size == 14 && completed.toSet().size == 14)
            emit(JSONObject().put("type", "complete").put("mode", mode)
                .put("ids", JSONArray(completed)).put("count", completed.size))
        } finally {
            check(dir.deleteRecursively()) { "scratch cleanup failed" }
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 3) { "usage: benchmark smoke|benchmark OUTPUT_DIRECTORY BUILD_JSON" }
    val directory = File(args[1])
    require(directory.mkdirs()) { "output directory must be fresh" }
    Benchmark(args[0], directory, File(args[2])).verifyAndMeasure()
}
