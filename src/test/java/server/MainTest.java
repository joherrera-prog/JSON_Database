package server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies server dispatch and framing without binding a TCP port. */
class MainTest {
    /** An accepted connection is passed to the executor and receives a reply. */
    @Test
    void delegatesAcceptedClientToExecutor() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        ExecutorService executor = mock(ExecutorService.class);
        CommandProcessor processor = mock(CommandProcessor.class);
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        String request = "{\"type\":\"get\",\"key\":\"table\"}";
        Socket client = clientWithRequest(request, reply);
        when(processor.execute(request)).thenReturn(new CommandProcessor.Result("reply", false));
        when(listener.isClosed()).thenReturn(false, true);
        when(listener.accept()).thenReturn(client);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        Main.acceptConnections(listener, executor, processor);

        verify(executor).execute(any(Runnable.class));
        verify(client).close();
        verify(listener, never()).close();
        assertEquals("reply", readFrame(reply));
    }

    /** An exit reply is written before the listener closes. */
    @Test
    void sendsExitReplyBeforeClosingListener() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        CommandProcessor processor = mock(CommandProcessor.class);
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        String request = "{\"type\":\"exit\"}";
        Socket client = clientWithRequest(request, reply);
        when(processor.execute(request)).thenReturn(new CommandProcessor.Result("OK", true));
        doAnswer(invocation -> {
            assertEquals("OK", readFrame(reply));
            return null;
        }).when(listener).close();

        Main.handleClient(client, processor, listener);

        verify(listener).close();
        verify(client).close();
    }

    /** Closing a listener while it waits is part of normal shutdown. */
    @Test
    void handlesListenerClosingWhileAccepting() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        when(listener.isClosed()).thenReturn(false, true);
        when(listener.accept()).thenThrow(new SocketException("Closed"));

        Main.acceptConnections(listener, mock(ExecutorService.class), mock(CommandProcessor.class));

        verify(listener).accept();
    }

    /** An unexpected listener error is still reported to the caller. */
    @Test
    void propagatesUnexpectedAcceptErrors() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        SocketException failure = new SocketException("Unexpected error");
        when(listener.accept()).thenThrow(failure);

        assertSame(failure, assertThrows(SocketException.class,
                () -> Main.acceptConnections(listener, mock(ExecutorService.class),
                        mock(CommandProcessor.class))));
    }

    /** A broken connection still releases the client socket. */
    @Test
    void closesClientWhenReadingFails() throws IOException {
        Socket client = mock(Socket.class);
        when(client.getInputStream()).thenThrow(new IOException("Disconnected"));

        Main.handleClient(client, mock(CommandProcessor.class), mock(ServerSocket.class));

        verify(client).close();
    }

    /** Builds a socket mock whose input contains one UTF-framed request. */
    private Socket clientWithRequest(String request, ByteArrayOutputStream reply) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(frame)) {
            output.writeUTF(request);
        }
        Socket socket = mock(Socket.class);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(frame.toByteArray()));
        when(socket.getOutputStream()).thenReturn(reply);
        return socket;
    }

    /** Reads one UTF frame from the bytes captured during a test. */
    private String readFrame(ByteArrayOutputStream bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readUTF();
        }
    }
}
