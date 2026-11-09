package server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persists a JSON object and coordinates access by server workers.
 *
 * <p>A path contains at least one property name. Intermediate properties must
 * be JSON objects. The final property can hold any JSON value.</p>
 */
public final class Database {
    /** File containing the complete JSON document. */
    private final Path databaseFile;
    /** Shared lock for read operations. */
    private final Lock readLock;
    /** Exclusive lock for read-modify-write operations. */
    private final Lock writeLock;

    /**
     * Creates a database backed by the given file.
     *
     * @param file the path to the JSON database
     */
    public Database(final Path file) {
        databaseFile = file.toAbsolutePath().normalize();

        // Both locks belong to the same coordinator. Writers wait for readers.
        final ReadWriteLock lock = new ReentrantReadWriteLock();
        readLock = lock.readLock();
        writeLock = lock.writeLock();
    }

    /**
     * Creates an empty database if the file is missing.
     *
     * @throws IOException if the file cannot be created
     */
    void initialize() throws IOException {
        // The server calls this before starting its worker tasks.
        if (Files.notExists(databaseFile)) {
            writeRecords(new JsonObject());
        }
    }

    /**
     * Reads a value at the given path.
     *
     * @param path a nonempty sequence of property names
     * @return the value, including JSON null, or empty if the path is absent
     * @throws IOException if the file cannot be read or parsed
     */
    public Optional<JsonElement> get(final List<String> path)
            throws IOException {
        readLock.lock();
        try {
            final JsonObject parent =
                    findParent(readRecords(), path, false);
            if (parent == null) {
                return Optional.empty();
            }

            // A missing property returns Java null. Gson represents a stored
            // JSON null as a JsonNull object, so the Optional stays present.
            return Optional.ofNullable(parent.get(lastKey(path)));
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Stores a value, creating intermediate objects as needed.
     *
     * @param path a nonempty sequence of property names
     * @param value the JSON value to store
     * @return whether the value could be stored at the requested path
     * @throws IOException if the database cannot be read or written
     */
    public boolean set(final List<String> path, final JsonElement value)
            throws IOException {
        // The write lock covers the whole read-modify-write sequence.
        writeLock.lock();
        try {
            final JsonObject records = readRecords();
            final JsonObject parent = findParent(records, path, true);
            if (parent == null) {
                return false;
            }

            final String key = lastKey(path);
            final JsonElement previous = parent.get(key);
            // Compare serialized values to distinguish 1 from 1.0.
            if (previous != null
                    && previous.toString().equals(value.toString())) {
                return true;
            }

            parent.add(key, value);
            writeRecords(records);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes only the final property, keeping its parent object.
     *
     * @param path a nonempty sequence of property names
     * @return whether a property was removed
     * @throws IOException if the database cannot be read or written
     */
    public boolean delete(final List<String> path) throws IOException {
        writeLock.lock();
        try {
            final JsonObject records = readRecords();
            final JsonObject parent = findParent(records, path, false);
            if (parent == null || parent.remove(lastKey(path)) == null) {
                return false;
            }

            writeRecords(records);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Walks the path up to, but excluding, the final property.
     *
     * @param root the root JSON object
     * @param path the requested property path
     * @param createMissing whether to create missing intermediate objects
     * @return the parent object, or null if the path cannot be traversed
     */
    private static JsonObject findParent(final JsonObject root,
            final List<String> path, final boolean createMissing) {
        JsonObject current = root;
        for (int index = 0; index < path.size() - 1; index++) {
            final String key = path.get(index);
            final JsonElement child = current.get(key);

            if (child == null && createMissing) {
                final JsonObject newParent = new JsonObject();
                current.add(key, newParent);
                current = newParent;
            } else if (child != null && child.isJsonObject()) {
                current = child.getAsJsonObject();
            } else {
                // Preserve an existing value that cannot contain properties.
                return null;
            }
        }
        return current;
    }

    /**
     * Returns the property name that the operation will act on.
     *
     * @param path the validated property path
     * @return the final property name
     */
    private static String lastKey(final List<String> path) {
        return path.get(path.size() - 1);
    }

    /**
     * Loads the JSON object while the caller holds a database lock.
     *
     * @return the current JSON object or an empty object for a missing file
     * @throws IOException if the file is unreadable or its root is invalid
     */
    private JsonObject readRecords() throws IOException {
        final String json;
        try {
            json = Files.readString(databaseFile);
        } catch (NoSuchFileException exception) {
            return new JsonObject();
        }

        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new IOException("The database contains invalid JSON.",
                    exception);
        }

        if (!parsed.isJsonObject()) {
            throw new IOException("The database must contain a JSON object.");
        }
        return parsed.getAsJsonObject();
    }

    /**
     * Writes a complete snapshot and atomically replaces the previous file.
     *
     * @param records the database snapshot to persist
     * @throws IOException if the snapshot cannot be written or moved
     */
    private void writeRecords(final JsonObject records) throws IOException {
        final Path directory = Objects.requireNonNull(databaseFile.getParent(),
                "The database path must have a parent directory.");
        Files.createDirectories(directory);

        // Keep the temporary file on the same filesystem for the atomic move.
        final Path temporaryFile =
                Files.createTempFile(directory, "db-", ".tmp");
        try {
            Files.writeString(temporaryFile, records.toString());
            Files.move(temporaryFile, databaseFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
