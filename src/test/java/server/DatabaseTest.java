package server;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the database against an isolated file for every test. */
class DatabaseTest {
    @TempDir
    Path directory;

    /** Verifies initialization, nested writes, updates, and reopening the file. */
    @Test
    void storesAndReopensNestedJson() throws IOException {
        Path file = directory.resolve("data/db.json");
        Database database = new Database(file);
        List<String> totalPath = List.of("table 1", "bill", "total");
        assertTrue(database.get(totalPath).isEmpty());
        database.initialize();
        assertEquals("{}", Files.readString(file));

        assertTrue(database.set(totalPath, new JsonPrimitive(250)));
        assertTrue(database.set(totalPath, new JsonPrimitive(250)));
        assertTrue(database.set(totalPath, new JsonPrimitive(275)));
        assertTrue(database.set(List.of("table 1", "bill", "paid"), new JsonPrimitive(false)));
        database.initialize();

        Database reopened = new Database(file);
        assertEquals(new JsonPrimitive(275), reopened.get(totalPath).orElseThrow());
        assertEquals(JsonParser.parseString("{\"total\":275,\"paid\":false}"),
                reopened.get(List.of("table 1", "bill")).orElseThrow());
    }

    /** Verifies that deleting JSON null keeps its parent and sibling records. */
    @Test
    void distinguishesJsonNullFromMissingAndPreservesParents() throws IOException {
        Database database = new Database(directory.resolve("db.json"));
        List<String> notePath = List.of("table 1", "bill", "note");
        assertTrue(database.set(notePath, JsonNull.INSTANCE));
        assertEquals(JsonNull.INSTANCE, database.get(notePath).orElseThrow());
        assertTrue(database.delete(notePath));
        assertTrue(database.get(notePath).isEmpty());
        assertFalse(database.delete(notePath));
        assertFalse(database.delete(List.of("missing", "note")));
        assertEquals(JsonParser.parseString("{}"),
                database.get(List.of("table 1", "bill")).orElseThrow());
    }

    /** A path cannot descend through an array or another non-object value. */
    @Test
    void rejectsBlockedPathsWithoutChangingTheFile() throws IOException {
        Path file = directory.resolve("db.json");
        Database database = new Database(file);
        assertTrue(database.set(List.of("order"), JsonParser.parseString("[\"tacos\", 2]")));
        String original = Files.readString(file);

        List<String> blocked = List.of("order", "quantity");
        assertFalse(database.set(blocked, new JsonPrimitive(3)));
        assertFalse(database.delete(blocked));
        assertTrue(database.get(blocked).isEmpty());
        assertEquals(original, Files.readString(file));
    }

    /** Checks both invalid JSON syntax and a valid JSON value with the wrong root type. */
    @Test
    void rejectsInvalidDatabaseContents() throws IOException {
        Path file = directory.resolve("db.json");
        Database database = new Database(file);
        for (String content : List.of("{", "[]")) {
            Files.writeString(file, content);
            assertThrows(IOException.class,
                    () -> database.set(List.of("table"), new JsonPrimitive(true)));
            assertEquals(content, Files.readString(file));
        }
    }

    /** Concurrent updates must survive in a single persisted snapshot. */
    @Test
    void retainsConcurrentUpdates() throws Exception {
        Database database = new Database(directory.resolve("db.json"));
        var workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> writes = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int table = index;
                writes.add(workers.submit(() -> database.set(
                        List.of("tables", "table " + table), new JsonPrimitive(table))));
            }
            for (Future<Boolean> write : writes) {
                assertTrue(write.get(5, TimeUnit.SECONDS));
            }
            assertEquals(20, database.get(List.of("tables")).orElseThrow()
                    .getAsJsonObject().size());
        } finally {
            workers.shutdownNow();
        }
    }
}
