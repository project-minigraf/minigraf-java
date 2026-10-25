// The consumer contract every binding checks (spec §4), from Java: in memory,
// file-backed across a reopen, and the error type for invalid Datalog.
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.project_minigraf.minigraf.MiniGrafAccumulator;
import io.github.project_minigraf.minigraf.MiniGrafDb;
import io.github.project_minigraf.minigraf.MiniGrafException;
import io.github.project_minigraf.minigraf.MiniGrafPreparedQuery;
import io.github.project_minigraf.minigraf.MiniGrafValue;
import io.github.project_minigraf.minigraf.Minigraf;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
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
    void setValuesComeBackAndBind() throws Exception {
        try (MiniGrafDb db = Minigraf.openInMemory()) {
            db.execute("(transact [[:alice :tags #{1 \"x\"}]])");
            String tags = db.execute("(query [:find ?t :where [?e :tags ?t]])");
            assertTrue(tags.contains("[[[\"x\",1]]]"), "a set value comes back as an array");
            MiniGrafPreparedQuery pq =
                    db.prepare("(query [:find ?t :where [?e :tags ?t] [(in? 1 $s)]])");
            String found = Minigraf.execute(pq, Map.of("s", Set.of(1, 2)));
            assertTrue(found.contains("[[[\"x\",1]]]"), "a set binds");
        }
    }

    @Test
    void userPredicateAndAggregate() throws Exception {
        try (MiniGrafDb db = Minigraf.openInMemory()) {
            db.execute("(transact [[:alice :name \"Alice\"] [:alice :age 30] [:bob :name \"Bob\"] [:bob :age 12]])");
            db.registerPredicate("adult?", v -> ((MiniGrafValue.Int64) v).getValue() >= 18);
            db.registerAggregate("product", () -> new MiniGrafAccumulator() {
                private long product = 1;

                @Override
                public void step(MiniGrafValue v) {
                    long n = ((MiniGrafValue.Int64) v).getValue();
                    if (n == 0) {
                        throw new IllegalArgumentException("zero");
                    }
                    product *= n;
                }

                @Override
                public MiniGrafValue finish(long count) {
                    return new MiniGrafValue.Int64(product);
                }
            });
            String adults = db.execute("(query [:find ?n :where [?e :age ?a] [(adult? ?a)] [?e :name ?n]])");
            assertTrue(adults.contains("\"results\":[[\"Alice\"]]"), "a user predicate filters rows");
            String product = db.execute("(query [:find (product ?a) :where [?e :age ?a]])");
            assertTrue(product.contains("\"results\":[[360]]"), "a user aggregate folds values");
            db.execute("(transact [[:carol :age 0]])");
            MiniGrafException e = assertThrows(MiniGrafException.Query.class,
                    () -> db.execute("(query [:find (product ?a) :where [?e :age ?a]])"));
            String msg = Minigraf.errorMessage(e);
            assertTrue(msg.startsWith("[QRY-014]") && msg.contains("zero"), "a throwing UDF is QRY-014");
        }
    }

    @Test
    void invalidDatalogThrowsMiniGrafException() throws Exception {
        try (MiniGrafDb db = Minigraf.openInMemory()) {
            assertThrows(MiniGrafException.class, () -> db.execute("(query [:find"));
        }
    }
}
