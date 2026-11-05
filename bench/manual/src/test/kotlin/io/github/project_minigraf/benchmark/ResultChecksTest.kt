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
}
