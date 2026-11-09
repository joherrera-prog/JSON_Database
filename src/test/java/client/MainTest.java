package client;

import com.beust.jcommander.ParameterException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers client request construction and its wire format. */
class MainTest {
    @TempDir
    Path directory;

    /** The command-line value stays a JSON string even if it looks numeric. */
    @Test
    void buildsCliRequests() throws IOException {
        assertJson("{\"type\":\"set\",\"key\":\"table 1\",\"value\":\"250\"}",
                "-t", "set", "-k", "table 1", "-v", "250");
        assertJson("{\"type\":\"get\",\"key\":\"table 1\"}", "-t", "get", "-k", "table 1");
        assertJson("{\"type\":\"delete\",\"key\":\"table 1\"}", "-t", "delete", "-k", "table 1");
        assertJson("{\"type\":\"exit\"}", "-t", "exit");
    }

    /** File mode preserves the input text and its JSON value types. */
    @Test
    void readsRequestFileVerbatim() throws IOException {
        String request = """
                {"type":"set","key":["table","total"],"value":250}
                """;
        Files.writeString(directory.resolve("request.json"), request);
        assertEquals(request, Main.parseRequest(new String[]{"-in", "request.json"}, directory));
    }

    /** Rejects incomplete options and combinations of both input modes. */
    @Test
    void rejectsInvalidOptions() {
        for (String[] args : new String[][]{
                {}, {"-t", "get"}, {"-t", "set", "-k", "table"}, {"-t", "other"},
                {"-in", "file.json", "-t", "exit"}, {"-in", "file.json", "-k", "table"},
                {"-in", "file.json", "-v", "250"}}) {
            assertThrows(ParameterException.class, () -> Main.parseRequest(args, directory));
        }
    }

    /** File requests accept filenames within the configured input directory. */
    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "/tmp/request.json", "nested/request.json"})
    void rejectsInvalidFilenames(String filename) {
        assertThrows(ParameterException.class,
                () -> Main.parseRequest(new String[]{"-in", filename}, directory));
    }

    /** A missing request file produces an I/O error. */
    @Test
    void reportsUnreadableFile() {
        assertThrows(IOException.class,
                () -> Main.parseRequest(new String[]{"-in", "missing.json"}, directory));
    }

    /** Invalid input exits the command-line entry point before opening a socket. */
    @Test
    void handlesInputErrorsBeforeConnecting() throws IOException {
        Main.main(new String[0]);
        Main.main(new String[]{"-in", "nonexistent-test-file.json"});
    }

    /** Verifies that the client uses the same UTF framing as the server. */
    @Test
    void writesOneUtfFrameAndReadsTheResponse() throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (DataOutputStream writer = new DataOutputStream(response)) {
            writer.writeUTF("{\"response\":\"OK\"}");
        }

        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        Socket connection = mock(Socket.class);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(response.toByteArray()));
        when(connection.getOutputStream()).thenReturn(sent);

        String request = "{\"type\":\"exit\"}";
        Main.exchange(connection, request);

        try (DataInputStream reader = new DataInputStream(new ByteArrayInputStream(sent.toByteArray()))) {
            assertEquals(request, reader.readUTF());
            assertEquals(-1, reader.read());
        }
    }

    /** Compares request JSON independently of whitespace or key order. */
    private void assertJson(String expected, String... args) throws IOException {
        assertEquals(JsonParser.parseString(expected),
                JsonParser.parseString(Main.parseRequest(args, directory)));
    }
}
