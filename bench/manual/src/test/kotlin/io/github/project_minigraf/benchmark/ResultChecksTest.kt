package io.github.project_minigraf.benchmark

import org.junit.Assert.assertThrows
import org.junit.Test

class ResultChecksTest {
    private val expected = listOf(0L, 1L, 2L, 3L)

    @Test fun acceptsCompleteUnorderedResult() {
        ResultChecks.numbers(listOf(3L, 1L, 0L, 2L), expected)
    }

    @Test fun rejectsMissingRows() {
        assertThrows(IllegalArgumentException::class.java) {
            ResultChecks.numbers(emptyList(), expected)
        }
    }

    @Test fun rejectsWrongValuesDespiteEqualCountAndSum() {
        assertThrows(IllegalArgumentException::class.java) {
            ResultChecks.numbers(listOf(0L, 0L, 3L, 3L), expected)
        }
    }

    @Test fun rejectsPlausibleWrongPointAndAggregate() {
        for (answer in listOf(42L, 499500L)) {
            assertThrows(IllegalArgumentException::class.java) {
                ResultChecks.numbers(listOf(answer + 1), listOf(answer))
            }
        }
    }

    @Test fun rejectsWrongSetWithCorrectCountAndSum() {
        assertThrows(IllegalArgumentException::class.java) {
            ResultChecks.numbers(listOf(0L, 3L), listOf(1L, 2L))
        }
    }

    @Test fun rejectsPlausibleWrongScanAndPredicateWithCorrectCountAndSum() {
        // These oracles also cover reopened writes, read-only opens, and
        // values from fact logs: count and sum alone must never pass a run.
        for (rows in listOf((0L until 1000L).toList(), (0L until 1000L step 2).toList())) {
            val wrong = rows.toMutableList()
            wrong[0] = rows[1]
            wrong[wrong.lastIndex] = rows[rows.lastIndex - 1]
            assertThrows(IllegalArgumentException::class.java) {
                ResultChecks.numbers(wrong, rows)
            }
        }
    }
}
