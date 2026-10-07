package io.github.project_minigraf.minigraf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the API reads naturally from Java, without the Kotlin Companion. */
class JavaInteropTest {

    @Test
    void openInMemoryFromJava() throws Exception {
        MiniGrafDb db = Minigraf.openInMemory();
        String result = db.execute("(transact [[:alice :name \"Alice\"]])");
        assertTrue(result.contains("transacted"), result);
    }

    @TempDir
    Path dir;

    @Test
    void cursorFactLogAndLogWriterFromJava() throws Exception {
        String src = dir.resolve("src.graph").toString();
        try (MiniGrafDb db = Minigraf.open(src)) {
            db.execute("(transact [[:a :n 1] [:b :n 2]])");
            db.execute("(retract [[:a :n 1]])");
            db.checkpoint();
        }

        MiniGrafDb ro = Minigraf.openWithOptions(src, Minigraf.options().readOnly(true).build());
        int rows = 0;
        try (MiniGrafCursor cursor = ro.query("(query [:find ?n :where [?e :n ?n]])")) {
            for (String batch : Minigraf.batches(cursor, 1)) {
                assertTrue(batch.startsWith("[["), batch);
                rows++;
            }
        }
        assertEquals(1, rows);
        MiniGrafException e = assertThrows(MiniGrafException.class, () -> ro.execute("(transact [[:c :n 3]])"));
        assertTrue(Minigraf.errorMessage(e).startsWith("[API-014]"));

        List<FactRecord> records = new ArrayList<>();
        try (MiniGrafFactLog log = ro.factLog(Minigraf.filter().attributes(":n").build())) {
            for (FactRecord r : Minigraf.records(log, 100)) {
                records.add(r);
            }
        }
        assertEquals(3, records.size());
        assertEquals(2L, records.get(records.size() - 1).getTxCount());
        assertEquals(Minigraf.VALID_TIME_FOREVER, records.get(0).getValidTo());

        String out = dir.resolve("out.graph").toString();
        try (MiniGrafLogWriter w = Minigraf.createLogWriter(out, Minigraf.options().build())) {
            w.appendBatch(records);
            w.advanceTxCount(ro.currentTxCount());
            w.finish();
        }
        MiniGrafDb copy = Minigraf.openWithOptions(out, Minigraf.options().readOnly(true).build());
        assertEquals(ro.currentTxCount(), copy.currentTxCount());

        String abandoned = dir.resolve("abandoned.graph").toString();
        try (MiniGrafLogWriter w = Minigraf.createLogWriter(abandoned, Minigraf.options().build())) {
            w.append(new FactRecord(records.get(0).getEntity(), ":n",
                    new MiniGrafValue.Int64(7L), 1L, 1L, 0L, Minigraf.VALID_TIME_FOREVER, true));
        }
        assertFalse(new File(abandoned).exists());
        assertFalse(new File(abandoned + ".partial").exists());
    }
}
