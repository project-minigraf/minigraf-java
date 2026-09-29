package io.github.project_minigraf.minigraf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Checks the API reads naturally from Java, without the Kotlin Companion. */
class JavaInteropTest {

    @Test
    void openInMemoryFromJava() throws Exception {
        MiniGrafDb db = Minigraf.openInMemory();
        String result = db.execute("(transact [[:alice :name \"Alice\"]])");
        assertTrue(result.contains("transacted"), result);
    }
}
