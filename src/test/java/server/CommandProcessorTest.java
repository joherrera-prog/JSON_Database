package server;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Uses a mocked database to test the JSON protocol in isolation. */
class CommandProcessorTest {
    private Database database;
    private CommandProcessor processor;

    /** Gives each test a fresh database mock. */
    @BeforeEach
    void setUp() {
        database = mock(Database.class);
        processor = new CommandProcessor(database);
    }

    /** Malformed requests never reach the database. */
    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{}", "{\"type\":\"get\"}",
            "{\"type\":1,\"key\":\"table\"}", "{\"type\":\"get\",\"key\":false}",
            "{\"type\":\"get\",\"key\":[]}", "{\"type\":\"get\",\"key\":[\"table\",2]}",
            "{\"type\":\"set\",\"key\":\"table\"}", "{\"type\":\"other\",\"key\":\"table\"}"})
    void rejectsInvalidRequests(String request) {
        assertResponse("{\"response\":\"ERROR\",\"reason\":\"Invalid request\"}", request);
        verifyNoInteractions(database);
    }

    /** A set request forwards an immutable path and the original JSON value. */
    @Test
    void forwardsNestedSetAndJsonNull() throws IOException {
        List<String> path = List.of("table", "note");
        when(database.set(path, JsonNull.INSTANCE)).thenReturn(true);

        assertResponse("{\"response\":\"OK\"}",
                "{\"type\":\"set\",\"key\":[\"table\",\"note\"],\"value\":null}");
        verify(database).set(path, JsonNull.INSTANCE);
    }

    /** A failed set returns a protocol error instead of replacing a parent. */
    @Test
    void reportsInvalidPath() {
        assertResponse("{\"response\":\"ERROR\",\"reason\":\"Invalid path\"}",
                "{\"type\":\"set\",\"key\":[\"table\",\"note\"],\"value\":true}");
    }

    /** A get response preserves an array containing multiple JSON types. */
    @Test
    void returnsTheOriginalJsonType() throws IOException {
        var value = JsonParser.parseString("[250,false,null]");
        when(database.get(List.of("bill"))).thenReturn(Optional.of(value));

        assertResponse("{\"response\":\"OK\",\"value\":[250,false,null]}",
                "{\"type\":\"get\",\"key\":\"bill\"}");
    }

    /** Missing values are reported consistently by get and delete. */
    @Test
    void reportsMissingKeys() throws IOException {
        when(database.get(List.of("missing"))).thenReturn(Optional.empty());
        String response = "{\"response\":\"ERROR\",\"reason\":\"No such key\"}";

        assertResponse(response, "{\"type\":\"get\",\"key\":\"missing\"}");
        assertResponse(response, "{\"type\":\"delete\",\"key\":\"missing\"}");
    }

    /** A successful delete returns OK. */
    @Test
    void deletesTheRequestedPath() throws IOException {
        List<String> path = List.of("table", "bill");
        when(database.delete(path)).thenReturn(true);

        assertResponse("{\"response\":\"OK\"}",
                "{\"type\":\"delete\",\"key\":[\"table\",\"bill\"]}");
        verify(database).delete(path);
    }

    /** A storage exception becomes a JSON error response. */
    @Test
    void reportsDatabaseFailure() throws IOException {
        when(database.get(List.of("bill"))).thenThrow(new IOException("Disk unavailable"));

        assertResponse("{\"response\":\"ERROR\",\"reason\":\"Database I/O error\"}",
                "{\"type\":\"get\",\"key\":\"bill\"}");
    }

    /** The exit request asks the server to close without using storage. */
    @Test
    void signalsServerShutdown() {
        CommandProcessor.Result result = processor.execute("{\"type\":\"exit\"}");
        assertEquals("{\"response\":\"OK\"}", result.response());
        assertTrue(result.stopServer());
        verifyNoInteractions(database);
    }

    /** Compares JSON structure independently of spacing or field order. */
    private void assertResponse(String expected, String request) {
        CommandProcessor.Result result = processor.execute(request);
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(result.response()));
        assertFalse(result.stopServer());
    }
}
