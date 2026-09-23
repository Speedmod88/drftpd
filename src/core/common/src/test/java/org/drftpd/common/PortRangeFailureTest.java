package org.drftpd.common;

import org.drftpd.common.util.PortRange;
import org.junit.jupiter.api.Test;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortRangeFailureTest {
    @Test
    void failedBindsCloseEverySocketWithoutRetryingForever() throws Exception {
        FailingFactory factory = new FailingFactory(new BindException("Address in use"));
        assertThrows(BindException.class, () -> new PortRange(40000, 40002, 0).getPort(factory, null));
        assertEquals(3, factory.sockets.size());
        assertTrue(factory.sockets.stream().allMatch(ServerSocket::isClosed));
    }

    @Test
    void nonBindIoFailureIsPreservedAndClosesSocket() throws Exception {
        IOException failure = new SocketException("Too many open files");
        FailingFactory factory = new FailingFactory(failure);
        assertSame(failure, assertThrows(IOException.class,
                () -> new PortRange(40000, 40002, 0).getPort(factory, null)));
        assertEquals(1, factory.sockets.size());
        assertTrue(factory.sockets.get(0).isClosed());
    }

    private static class FailingFactory extends ServerSocketFactory {
        final List<ServerSocket> sockets = new ArrayList<>();
        final IOException failure;

        FailingFactory(IOException failure) { this.failure = failure; }

        @Override
        public ServerSocket createServerSocket() throws IOException {
            ServerSocket socket = new ServerSocket() {
                @Override
                public void bind(SocketAddress endpoint, int backlog) throws IOException { throw failure; }
            };
            sockets.add(socket);
            return socket;
        }

        @Override
        public ServerSocket createServerSocket(int port) { throw new UnsupportedOperationException(); }
        @Override
        public ServerSocket createServerSocket(int port, int backlog) { throw new UnsupportedOperationException(); }
        @Override
        public ServerSocket createServerSocket(int port, int backlog, InetAddress address) {
            throw new UnsupportedOperationException();
        }
    }
}
