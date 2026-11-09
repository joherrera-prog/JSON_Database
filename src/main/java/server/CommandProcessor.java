package server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts JSON protocol messages into database operations and responses.
 */
public final class CommandProcessor {
    /** Reports unexpected storage failures. */
    private static final Logger LOGGER =
            Logger.getLogger(CommandProcessor.class.getName());
    /** Reason returned for an invalid protocol request. */
    private static final String INVALID_REQUEST = "Invalid request";
    /** Reason returned for a missing record. */
    private static final String NO_SUCH_KEY = "No such key";
    /** JSON property used for record values. */
    private static final String VALUE = "value";
    /** Operation that closes the listener after its response. */
    private static final String EXIT = "exit";
    /** Shared persistent database. */
    private final Database database;

    /**
     * Creates a processor for the server's shared database.
     *
     * @param storage the database used by this processor
     */
    public CommandProcessor(final Database storage) {
        database = storage;
    }

    /**
     * Holds a serialized response and a request to close the server.
     *
     * @param response the JSON response to send to the client
     * @param stopServer whether to stop accepting connections
     */
    record Result(String response, boolean stopServer) {
    }

    /**
     * Validates and processes one request.
     *
     * @param requestText the JSON received from the client
     * @return the response and whether the server should close
     */
    Result execute(final String requestText) {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(requestText);
        } catch (JsonParseException exception) {
            return result(error(INVALID_REQUEST));
        }
        if (!parsed.isJsonObject()) {
            return result(error(INVALID_REQUEST));
        }

        final JsonObject request = parsed.getAsJsonObject();
        final String type = readString(request.get("type"));

        // An exit request is complete without a key.
        if (EXIT.equals(type)) {
            return new Result(ok().toString(), true);
        }

        final List<String> path = readPath(request.get("key"));
        if (type == null || path.isEmpty()) {
            return result(error(INVALID_REQUEST));
        }

        try {
            return result(executeDatabaseOperation(request, type, path));
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Database I/O error.", exception);
            return result(error("Database I/O error"));
        }
    }

    /**
     * Applies a validated operation to the requested path.
     *
     * @param request the parsed request
     * @param type the operation name
     * @param path the property path
     * @return the protocol response before serialization
     * @throws IOException if database I/O fails
     */
    private JsonObject executeDatabaseOperation(final JsonObject request,
            final String type, final List<String> path) throws IOException {
        return switch (type) {
            case "set" -> {
                // Java null indicates a missing field. JSON null is valid.
                final JsonElement value = request.get(VALUE);
                if (value == null) {
                    yield error(INVALID_REQUEST);
                }
                yield database.set(path, value) ? ok() : error("Invalid path");
            }
            case "get" -> database.get(path)
                    .map(value -> {
                        final JsonObject response = ok();
                        // Add the element directly to preserve its JSON type.
                        response.add(VALUE, value);
                        return response;
                    })
                    .orElseGet(() -> error(NO_SUCH_KEY));
            case "delete" -> database.delete(path) ? ok() : error(NO_SUCH_KEY);
            default -> error(INVALID_REQUEST);
        };
    }

    /**
     * Normalizes a string key or an array of strings to a property path.
     *
     * @param key the JSON key from the client
     * @return an immutable path, or an empty list for an invalid key
     */
    private static List<String> readPath(final JsonElement key) {
        final String singleKey = readString(key);
        if (singleKey != null) {
            return List.of(singleKey);
        }
        if (key == null || !key.isJsonArray()) {
            return List.of();
        }

        final List<String> segments = new ArrayList<>();
        for (final JsonElement item : key.getAsJsonArray()) {
            final String segment = readString(item);
            if (segment == null) {
                return List.of();
            }
            segments.add(segment);
        }
        return List.copyOf(segments);
    }

    /**
     * Reads a JSON string without converting numbers or booleans.
     *
     * @param value the JSON element to inspect
     * @return the string value, or null if the element is not a string
     */
    private static String readString(final JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    /**
     * Serializes a response for an operation that keeps the server running.
     *
     * @param response the response object
     * @return the serialized response and a false shutdown flag
     */
    private static Result result(final JsonObject response) {
        return new Result(response.toString(), false);
    }

    /**
     * Creates a successful response.
     *
     * @return a response with status OK
     */
    private static JsonObject ok() {
        final JsonObject response = new JsonObject();
        response.addProperty("response", "OK");
        return response;
    }

    /**
     * Creates an error response.
     *
     * @param reason the protocol error message
     * @return a response with status ERROR and its reason
     */
    private static JsonObject error(final String reason) {
        final JsonObject response = new JsonObject();
        response.addProperty("response", "ERROR");
        response.addProperty("reason", reason);
        return response;
    }
}
