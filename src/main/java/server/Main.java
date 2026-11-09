package server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Accepts local TCP connections and delegates each request to a worker. */
public final class Main {
    /** Reports failures in individual client connections. */
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    /** Loopback port shared by the client and server. */
    private static final int PORT = 23456;
    /** Requested queue size for pending connections. */
    private static final int BACKLOG = 50;
    /** Maximum number of active worker tasks. */
    private static final int WORKERS = 4;
    /** Socket read timeout in milliseconds. */
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    /** Prevents construction of the application entry point. */
    private Main() {
    }

    /**
     * Starts the server and listens until it receives an exit request.
     *
     * @param args unused command-line arguments
     * @throws IOException if the listener or database cannot be initialized
     */
    // The protocol requires console output and an IPv4 loopback listener.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidUsingHardCodedIP"})
    public static void main(final String[] args) throws IOException {
        final InetAddress address = InetAddress.getByName("127.0.0.1");
        final Path databaseFile = Path.of("src", "server", "data", "db.json");
        final Database database = new Database(databaseFile);
        final CommandProcessor processor = new CommandProcessor(database);
        final ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try (ServerSocket server = new ServerSocket(PORT, BACKLOG, address)) {
            database.initialize();
            System.out.println("Server started!");
            acceptConnections(server, executor, processor);
        } finally {
            // Let already submitted tasks complete on Java 17.
            executor.shutdown();
        }
    }

    /**
     * Accepts connections and hands each socket to one executor task.
     *
     * @param server the listener shared by all workers
     * @param executor the worker pool
     * @param processor the shared request processor
     * @throws IOException if accepting fails while the listener is still open
     */
    // Socket ownership is transferred to the worker, which closes it.
    @SuppressWarnings("PMD.CloseResource")
    static void acceptConnections(final ServerSocket server,
            final ExecutorService executor, final CommandProcessor processor)
            throws IOException {
        while (!server.isClosed()) {
            final Socket client;
            try {
                client = server.accept();
            } catch (SocketException exception) {
                // An exit request closes the listener and wakes up accept().
                if (server.isClosed()) {
                    break;
                }
                throw exception;
            }
            executor.execute(() -> handleClient(client, processor, server));
        }
    }

    /**
     * Reads one request, sends one response, and closes the client connection.
     *
     * @param client the client socket owned by this worker
     * @param processor the shared request processor
     * @param server the listener to close for an exit request
     */
    // Only an exit request may close the shared listener.
    @SuppressWarnings("PMD.UseTryWithResources")
    static void handleClient(final Socket client,
            final CommandProcessor processor, final ServerSocket server) {
        try (client;
             DataInputStream input =
                     new DataInputStream(client.getInputStream());
             DataOutputStream output =
                     new DataOutputStream(client.getOutputStream())) {
            client.setSoTimeout(READ_TIMEOUT_MILLIS);
            final CommandProcessor.Result result =
                    processor.execute(input.readUTF());

            try {
                output.writeUTF(result.response());
                output.flush();
            } finally {
                // Try to send the reply before shutting down the listener.
                if (result.stopServer()) {
                    server.close();
                }
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Client connection failed.", exception);
        }
    }
}
