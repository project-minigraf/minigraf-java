// The consumer contract every binding checks (spec §4), from Java: in memory,
// file-backed across a reopen, and the error type for invalid Datalog.
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.project_minigraf.minigraf.MiniGrafDb;
import io.github.project_minigraf.minigraf.MiniGrafException;
import io.github.project_minigraf.minigraf.MiniGrafPreparedQuery;
import io.github.project_minigraf.minigraf.MiniGrafValue;
import io.github.project_minigraf.minigraf.Minigraf;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractTest {
    private static final String QUERY = "(query [:find ?n :where [?e :name ?n]])";

    @Test
    void inMemory() throws Exception {
        try (MiniGrafDb db = Minigraf.openInMemory()) {
            db.execute("(transact [[:alice :name \"Alice\"]])");
            String result = db.execute(QUERY);
            assertTrue(result.contains("[[\"Alice\"]]"), "query in memory returns [[\"Alice\"]]");
        }
    }

    @Test
    void fileBackedSurvivesReopen() throws Exception {
        Path dir = Files.createTempDirectory("minigraf-contract");
        String path = dir.resolve("test.graph").toString();
        try (MiniGrafDb db = Minigraf.open(path)) {
            db.execute("(transact [[:bob :name \"Bob\"]])");
            db.checkpoint();
        }
        try (MiniGrafDb db = Minigraf.open(path)) {
            assertTrue(db.execute(QUERY).contains("[[\"Bob\"]]"), "query after reopen returns [[\"Bob\"]]");
        }
    }

    @Test
    void preparedQueryRunsWithPlainAndTypedValues() throws Exception {
        try (MiniGrafDb db = Minigraf.openInMemory()) {
            db.execute("(transact [[:alice :name \"Alice\"] [:bob :name \"Bob\"]])");
            MiniGrafPreparedQuery pq =
                    db.prepare("(query [:find ?n :where [?e :name $name] [?e :name ?n]])");
            for (String name : new String[] {"Alice", "Bob"}) {
                String result = Minigraf.execute(pq, Map.of("name", name));
                assertTrue(result.contains("[[\"" + name + "\"]]"), "prepared query finds " + name);
            }
            String typed = Minigraf.execute(pq, Map.of("name", new MiniGrafValue.Text("Alice")));
            assertTrue(typed.contains("[[\"Alice\"]]"), "a typed value binds");
        }
    }

    @Test
    void invalidDatalogThrowsMiniGrafException() throws Exception {
        try (MiniGrafDb db = Minigraf.openInMemory()) {
            assertThrows(MiniGrafException.class, () -> db.execute("(query [:find"));
        }
    }
}
