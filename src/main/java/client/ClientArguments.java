package client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds JSON protocol text from command-line options or a file. */
final class ClientArguments {
    /** Base directory for the file input mode. */
    private final Path requestDirectory;

    // JCommander populates these fields before the request is built.
    /** Operation selected on the command line. */
    @Parameter(names = "-t", description = "Request type")
    private String type;

    /** Record key selected on the command line. */
    @Parameter(names = "-k", description = "Record key")
    private String key;

    /** Text value selected on the command line. */
    @Parameter(names = "-v", description = "Text value for set")
    private String value;

    /** Name of a file containing the request JSON. */
    @Parameter(names = "-in", description = "JSON request filename")
    private String inputFile;

    /**
     * Creates argument storage for requests in the given directory.
     *
     * @param inputDirectory the directory used by the file input mode
     */
    ClientArguments(final Path inputDirectory) {
        requestDirectory = inputDirectory;
    }

    /**
     * Builds a request from options or reads it from the selected file.
     *
     * @return the request text to send over TCP
     * @throws IOException if the selected file cannot be read
     * @throws ParameterException if the command-line options are invalid
     */
    String toRequest() throws IOException {
        if (inputFile != null) {
            if (type != null || key != null || value != null) {
                throw new ParameterException("Use either -in or -t/-k/-v.");
            }
            return readRequestFile();
        }

        if (type == null) {
            throw new ParameterException(
                    "Specify -t <operation> or -in <file.json>.");
        }

        final JsonObject request = new JsonObject();
        request.addProperty("type", type);

        switch (type) {
            case "exit" -> {
                // An exit request only needs the type field.
            }
            case "get", "delete" -> request.addProperty("key", requireKey());
            case "set" -> {
                request.addProperty("key", requireKey());
                if (value == null) {
                    throw new ParameterException("Set requires -v <value>.");
                }

                // CLI values are strings. Files can carry any JSON value.
                request.addProperty("value", value);
            }
            default -> throw new ParameterException(
                    "Unknown request type: " + type);
        }
        return request.toString();
    }

    /**
     * Reads the input file without changing its JSON.
     *
     * @return the original UTF-8 request text
     * @throws IOException if the request cannot be read
     */
    private String readRequestFile() throws IOException {
        final Path name = Path.of(inputFile);
        if (inputFile.isBlank() || name.isAbsolute() || name.getNameCount() != 1
                || ".".equals(inputFile) || "..".equals(inputFile)) {
            throw new ParameterException(
                    "-in requires a filename such as table-get.json.");
        }

        return Files.readString(requestDirectory.resolve(name));
    }

    /**
     * Requires a key for set, get, and delete.
     *
     * @return the supplied record key
     * @throws ParameterException if the key is missing
     */
    private String requireKey() {
        if (key == null) {
            throw new ParameterException(type + " requires -k <key>.");
        }
        return key;
    }
}
