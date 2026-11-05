package io.github.project_minigraf.benchmark

internal object ResultChecks {
    fun numbers(actual: List<Long>, expected: List<Long>) {
        require(actual.size == expected.size) { "unexpected row count" }
        require(actual.sum() == expected.sum()) { "unexpected row sum" }
        require(actual.sorted() == expected.sorted()) { "unexpected row values" }
    }
}
