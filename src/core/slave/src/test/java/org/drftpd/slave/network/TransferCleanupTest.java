package org.drftpd.slave.network;

import org.drftpd.common.network.PassiveConnection;
import org.drftpd.common.slave.Connection;
import org.drftpd.common.slave.DiskStatus;
import org.drftpd.common.slave.TransferIndex;
import org.drftpd.common.util.PortRange;
import org.drftpd.slave.Slave;
import org.drftpd.slave.vfs.RootCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferCleanupTest {
    @TempDir
    Path directory;

    @Test
    void successfulUploadStillWritesAllBytesAndReportsChecksum() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3, 4, 5};
        Slave slave = mock(Slave.class);
        RootCollection roots = mock(RootCollection.class);
        when(slave.getRoots()).thenReturn(roots);
        when(roots.getFile("/release/file.rar")).thenThrow(new FileNotFoundException());
        when(roots.getARootFileDir("/release")).thenReturn(directory.toFile());
        when(slave.getUploadChecksums()).thenReturn(true);
        when(slave.getDiskStatus()).thenReturn(new DiskStatus(1, 2));
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        Connection connection = mock(Connection.class);
        when(connection.connect(null, null, 0)).thenReturn(socket);
        Transfer transfer = new Transfer(connection, slave, new TransferIndex());
        transfer.receiveFile("/release", 'I', "file.rar", 0, "*@*");
        assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("file.rar")));
        CRC32 crc = new CRC32();
        crc.update(bytes);
        assertEquals(crc.getValue(), transfer.getChecksum());
        assertTrue(transfer.isFinished());
        verify(slave).removeTransfer(transfer);
        verify(socket).close();
    }

    @Test
    void abortDuringSetupRetainsTransferUntilIoExits() throws Exception {
        Slave slave = mock(Slave.class);
        RootCollection roots = mock(RootCollection.class);
        when(slave.getRoots()).thenReturn(roots);
        when(roots.getFile("/release/file.rar")).thenThrow(new FileNotFoundException());
        Connection connection = mock(Connection.class);
        Transfer transfer = new Transfer(connection, slave, new TransferIndex());
        when(roots.getARootFileDir("/release")).thenAnswer(call -> {
            transfer.abort("Cancelled while selecting disk");
            verify(slave, never()).removeTransfer(transfer);
            throw new IOException("Aborted");
        });
        assertThrows(IOException.class, () -> transfer.receiveFile("/release", 'I', "file.rar", 0, "*@*"));
        verify(slave).removeTransfer(transfer);
    }

    @Test
    void rejectedUploadReleasesListenerAndTransfer() throws Exception {
        Slave slave = mock(Slave.class);
        RootCollection roots = mock(RootCollection.class);
        when(slave.getRoots()).thenReturn(roots);
        when(roots.getFile("/release/file.rar")).thenThrow(new FileNotFoundException());
        when(roots.getARootFileDir("/release")).thenThrow(new IOException("No upload disk satisfies minfreespace"));
        PassiveConnection connection = new PassiveConnection(null, new PortRange(0), false, null);
        int port = connection.getLocalPort();
        Transfer transfer = new Transfer(connection, slave, new TransferIndex());
        assertThrows(IOException.class, () -> transfer.receiveFile("/release", 'I', "file.rar", 0, "*@*"));
        verify(slave).removeTransfer(transfer);
        assertTrue(transfer.isFinished());
        try (ServerSocket reused = new ServerSocket(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }

    @Test
    void existingUploadFileAlsoReleasesListener() throws Exception {
        Slave slave = mock(Slave.class);
        when(slave.getRoots()).thenReturn(mock(RootCollection.class));
        PassiveConnection connection = new PassiveConnection(null, new PortRange(0), false, null);
        int port = connection.getLocalPort();
        Transfer transfer = new Transfer(connection, slave, new TransferIndex());
        assertThrows(IOException.class, () -> transfer.receiveFile("/release", 'I', "file.rar", 0, "*@*"));
        verify(slave).removeTransfer(transfer);
        try (ServerSocket reused = new ServerSocket(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }

    @Test
    void missingDownloadReleasesListener() throws Exception {
        Slave slave = mock(Slave.class);
        RootCollection roots = mock(RootCollection.class);
        when(slave.getRoots()).thenReturn(roots);
        when(roots.getFile("/missing.rar")).thenThrow(new FileNotFoundException());
        PassiveConnection connection = new PassiveConnection(null, new PortRange(0), false, null);
        int port = connection.getLocalPort();
        Transfer transfer = new Transfer(connection, slave, new TransferIndex());
        assertThrows(IOException.class, () -> transfer.sendFile("/missing.rar", 'I', 0, "*@*"));
        verify(slave).removeTransfer(transfer);
        try (ServerSocket reused = new ServerSocket(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }

    @Test
    void abortBeforeStorReleasesRegistryEntryAndListener() throws Exception {
        Slave slave = mock(Slave.class);
        PassiveConnection connection = new PassiveConnection(null, new PortRange(0), false, null);
        int port = connection.getLocalPort();
        Transfer transfer = new Transfer(connection, slave, new TransferIndex());
        transfer.abort("Client cancelled PASV");
        verify(slave).removeTransfer(transfer);
        try (ServerSocket reused = new ServerSocket(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }
}
