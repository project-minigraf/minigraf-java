// The consumer contract every binding checks (spec §4), from Java: in memory,
// file-backed across a reopen, and the error type for invalid Datalog.
// 2.x: the generated classes are in uniffi.minigraf_ffi, with no Java facade.
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uniffi.minigraf_ffi.MiniGrafDb;
import uniffi.minigraf_ffi.MiniGrafException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractTest {
    private static final String QUERY = "(query [:find ?n :where [?e :name ?n]])";

    @Test
    void inMemory() throws Exception {
        try (MiniGrafDb db = MiniGrafDb.Companion.openInMemory()) {
            db.execute("(transact [[:alice :name \"Alice\"]])");
            String result = db.execute(QUERY);
            assertTrue(result.contains("[[\"Alice\"]]"), "query in memory returns [[\"Alice\"]]");
        }
    }

    @Test
    void fileBackedSurvivesReopen() throws Exception {
        Path dir = Files.createTempDirectory("minigraf-contract");
        String path = dir.resolve("test.graph").toString();
        try (MiniGrafDb db = MiniGrafDb.Companion.open(path)) {
            db.execute("(transact [[:bob :name \"Bob\"]])");
            db.checkpoint();
        }
        try (MiniGrafDb db = MiniGrafDb.Companion.open(path)) {
            assertTrue(db.execute(QUERY).contains("[[\"Bob\"]]"), "query after reopen returns [[\"Bob\"]]");
        }
    }

    @Test
    void invalidDatalogThrowsMiniGrafException() throws Exception {
        try (MiniGrafDb db = MiniGrafDb.Companion.openInMemory()) {
            assertThrows(MiniGrafException.class, () -> db.execute("(query [:find"));
        }
    }
}
