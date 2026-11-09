package client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;

/** Sends one database request and prints the response. */
public final class Main {
    /** Loopback port used by the server. */
    private static final int PORT = 23456;
    /** Directory for JSON request files. */
    private static final Path REQUEST_DIRECTORY =
            Path.of("src", "client", "data");

    /** Prevents construction of the application entry point. */
    private Main() {
    }

    /**
     * Reads the request and connects to the local database server.
     *
     * @param args the request type, key and value, or the input filename
     * @throws IOException if the server cannot be reached or the exchange fails
     */
    // The CLI prints its status and connects to the IPv4 loopback listener.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidUsingHardCodedIP"})
    public static void main(final String[] args) throws IOException {
        final String request;
        try {
            request = parseRequest(args, REQUEST_DIRECTORY);
        } catch (ParameterException exception) {
            System.err.println(exception.getMessage());
            return;
        } catch (IOException exception) {
            System.err.println("Could not read the request: "
                    + exception.getMessage());
            return;
        }

        final InetAddress address = InetAddress.getByName("127.0.0.1");
        try (Socket connection = new Socket(address, PORT)) {
            System.out.println("Client started!");
            exchange(connection, request);
        }
    }

    /**
     * Parses the command line and builds the request.
     *
     * @param args the client options
     * @param requestDirectory the base directory for file requests
     * @return the request JSON as text
     * @throws IOException if a requested file cannot be read
     */
    static String parseRequest(final String[] args, final Path requestDirectory)
            throws IOException {
        final ClientArguments arguments = new ClientArguments(requestDirectory);
        final JCommander parser = JCommander.newBuilder()
                .addObject(arguments)
                .programName("client.Main")
                .build();
        parser.parse(args);
        return arguments.toRequest();
    }

    /**
     * Writes a UTF-framed request and prints the corresponding response.
     *
     * @param connection the socket connected to the server
     * @param request the request text
     * @throws IOException if the exchange fails
     */
    // Sent and Received are part of the CLI output contract.
    @SuppressWarnings("PMD.SystemPrintln")
    static void exchange(final Socket connection, final String request)
            throws IOException {
        try (DataInputStream input =
                     new DataInputStream(connection.getInputStream());
             DataOutputStream output =
                     new DataOutputStream(connection.getOutputStream())) {
            output.writeUTF(request);
            output.flush();
            System.out.println("Sent: " + request);

            final String response = input.readUTF();
            System.out.println("Received: " + response);
        }
    }
}
