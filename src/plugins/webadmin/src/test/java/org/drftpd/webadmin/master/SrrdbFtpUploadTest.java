package org.drftpd.webadmin.master;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPSClient;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrrdbFtpUploadTest {
    private SrrdbFtpUpload.Client passiveClient(String... replies) throws Exception {
        var client = spy(new SrrdbFtpUpload.Client(javax.net.ssl.SSLContext.getDefault()));
        client.setIpAddressFromPasvResponse(true);
        client.setPassiveNatWorkaroundStrategy(null);
        doReturn(replies).when(client).getReplyStrings();
        return client;
    }

    @Test void parsesDrftpdMultilinePassiveReplyInsteadOfSlaveComment() throws Exception {
        var client = passiveClient("227- Using EvoI for upcoming transfer",
                "227 Entering Passive Mode (192,0,2,10,195,80).");
        client._parsePassiveModeReply("227- Using EvoI for upcoming transfer");
        assertEquals("192.0.2.10", client.getPassiveHost());
        assertEquals(50000, client.getPassivePort());
    }

    @Test void preservesSingleLinePassiveReplySupport() throws Exception {
        String reply = "227 Entering Passive Mode (192,0,2,11,195,81).";
        var client = passiveClient(reply);
        client._parsePassiveModeReply(reply);
        assertEquals("192.0.2.11", client.getPassiveHost());
        assertEquals(50001, client.getPassivePort());
    }

    @Test void rejectsMalformedFinalPassiveReplyEvenIfCommentContainsAnEndpoint() throws Exception {
        String comment = "227- Using slave (192,0,2,12,195,82) for upcoming transfer";
        var client = passiveClient(comment, "227 No passive endpoint available");
        assertThrows(org.apache.commons.net.MalformedServerReplyException.class,
                () -> client._parsePassiveModeReply(comment));
    }

    private final byte[] content = SrrdbClientTest.bytes("approved metadata");
    private final SrrdbClient.RemoteFile remote = SrrdbClientTest.file("release.nfo", content);

    private FTPSClient connected() throws Exception {
        FTPSClient ftp = mock(FTPSClient.class);
        when(ftp.getReplyCode()).thenReturn(220);
        when(ftp.login("evo", "password")).thenReturn(true);
        when(ftp.setFileType(FTP.BINARY_FILE_TYPE)).thenReturn(true);
        when(ftp.changeWorkingDirectory("/SECTION/Release-GRP")).thenReturn(true);
        when(ftp.sendCommand("PRET", "STOR release.nfo")).thenReturn(200);
        when(ftp.storeFile(eq("release.nfo"), any(InputStream.class))).thenAnswer(call -> {
            assertArrayEquals(content, ((InputStream) call.getArgument(1)).readAllBytes()); return true;
        });
        when(ftp.getSize("release.nfo")).thenReturn(Long.toString(content.length));
        when(ftp.sendCommand("XCRC", "release.nfo")).thenReturn(250);
        when(ftp.getReplyString()).thenReturn("250 XCRC Successful. " + remote.crc() + "\r\n");
        return ftp;
    }
    private SrrdbFtpUpload uploader(FTPSClient ftp) {
        return new SrrdbFtpUpload(new WebAdminSettings(new Properties()),
                user -> new RecoveryCredentials.Login(user, "password".toCharArray()), () -> ftp);
    }
    @Test void usesNormalProtectedFtpCommandsAndDistributedPassiveAddress() throws Exception {
        FTPSClient ftp = connected();
        try (var uploader = uploader(ftp)) {
            uploader.install("evo", "/SECTION/Release-GRP/release.nfo", remote, content, new Properties());
        }
        verify(ftp).setUseEPSVwithIPv4(false);
        verify(ftp).setIpAddressFromPasvResponse(true);
        verify(ftp).setPassiveNatWorkaroundStrategy(null);
        verify(ftp).setRemoteVerificationEnabled(false);
        var order = inOrder(ftp);
        order.verify(ftp).connect(InetAddress.getByName("127.0.0.1"), 2121);
        order.verify(ftp).login("evo", "password");
        order.verify(ftp).execPBSZ(0);
        order.verify(ftp).execPROT("P");
        order.verify(ftp).setFileType(FTP.BINARY_FILE_TYPE);
        order.verify(ftp).enterLocalPassiveMode();
        order.verify(ftp).changeWorkingDirectory("/SECTION/Release-GRP");
        order.verify(ftp).sendCommand("PRET", "STOR release.nfo");
        order.verify(ftp).storeFile(eq("release.nfo"), any(InputStream.class));
        order.verify(ftp).getSize("release.nfo");
        order.verify(ftp).sendCommand("XCRC", "release.nfo");
        verify(ftp, never()).deleteFile(anyString());
        verify(ftp).disconnect();
    }
    @Test void passiveTimeoutIdentifiesUploadStageAndEndpoint() throws Exception {
        FTPSClient ftp = connected();
        when(ftp.getPassiveHost()).thenReturn("192.0.2.10");
        when(ftp.getPassivePort()).thenReturn(50000);
        when(ftp.storeFile(anyString(), any(InputStream.class))).thenThrow(new SocketTimeoutException("Connect timed out"));
        try (var uploader = uploader(ftp)) {
            var error = assertThrows(IOException.class, () -> uploader.install("evo", "/SECTION/Release-GRP/release.nfo", remote, content, new Properties()));
            assertTrue(error.getMessage().contains("srrDB download succeeded"));
            assertTrue(error.getMessage().contains("STOR"));
            assertTrue(error.getMessage().contains("192.0.2.10:50000"));
        }
        verify(ftp, never()).deleteFile(anyString());
    }
    @Test void ftpRefusalAndBadChecksumDoNotDeleteExistingFiles() throws Exception {
        FTPSClient ftp = connected();
        when(ftp.storeFile(anyString(), any(InputStream.class))).thenReturn(false);
        when(ftp.getReplyString()).thenReturn("553 File exists");
        try (var uploader = uploader(ftp)) {
            assertTrue(assertThrows(IOException.class, () -> uploader.install("evo", "/SECTION/Release-GRP/release.nfo", remote, content, new Properties()))
                    .getMessage().contains("553 File exists"));
        }
        verify(ftp, never()).deleteFile(anyString());
        ftp = connected();
        when(ftp.getReplyString()).thenReturn("250 XCRC Successful. 00000000");
        FTPSClient corrupt = ftp;
        try (var uploader = uploader(corrupt)) {
            assertTrue(assertThrows(IOException.class, () -> uploader.install("evo", "/SECTION/Release-GRP/release.nfo", remote, content, new Properties()))
                    .getMessage().contains("XCRC differs"));
        }
        verify(corrupt, never()).deleteFile(anyString());
    }
    @Test void endpointDefaultsToActualMasterPortAndRejectsExternalHosts() throws Exception {
        Properties master = new Properties();
        master.setProperty("master.ip", "0.0.0.0"); master.setProperty("master.port", "2021");
        var endpoint = SrrdbFtpUpload.endpoint(new WebAdminSettings(new Properties()), master);
        assertTrue(endpoint.getAddress().isLoopbackAddress());
        assertEquals(2021, endpoint.getPort());
        Properties config = new Properties(); config.setProperty("srrdb.ftp.host", "192.0.2.45");
        assertThrows(IOException.class, () -> SrrdbFtpUpload.endpoint(new WebAdminSettings(config), master));
    }

    @Test void explicitlyConfiguredArchivesxHostnameUsesRequestedPort() throws Exception {
        Properties config = new Properties();
        config.setProperty("srrdb.ftp.host", "Archivesx.ddns.net");
        config.setProperty("srrdb.ftp.port", "2133");
        InetAddress address = InetAddress.getByName("192.0.2.45");
        SrrdbFtpUpload.Hosts resolver = host -> {
            assertEquals("Archivesx.ddns.net", host);
            return new InetAddress[]{address};
        };
        var endpoint = SrrdbFtpUpload.endpoint(new WebAdminSettings(config), new Properties(), resolver);
        assertEquals(address, endpoint.getAddress());
        assertEquals(2133, endpoint.getPort());
        config.setProperty("srrdb.ftp.host", "ARCHIVESX.DDNS.NET");
        assertEquals(address, SrrdbFtpUpload.endpoint(new WebAdminSettings(config), new Properties(),
                host -> new InetAddress[]{address}).getAddress());
        config.setProperty("srrdb.ftp.host", "Archivesx.ddns.net.attacker.invalid");
        assertThrows(IOException.class, () -> SrrdbFtpUpload.endpoint(new WebAdminSettings(config), new Properties(),
                host -> new InetAddress[]{address}));
        Properties master = new Properties();
        master.setProperty("master.ip", "Archivesx.ddns.net");
        assertThrows(IOException.class, () -> SrrdbFtpUpload.endpoint(new WebAdminSettings(new Properties()), master,
                host -> new InetAddress[]{address}));
    }

    @Test void expiredWebLoginFailsBeforeConnectingAndFilesWithSpacesUseVerifiedFtpRetr() throws Exception {
        FTPSClient ftp = connected();
        try (var uploader = new SrrdbFtpUpload(new WebAdminSettings(new Properties()),
                user -> { throw new IOException("Sign in again"); }, () -> ftp)) {
            assertThrows(IOException.class, () -> uploader.install("evo", "/SECTION/Release-GRP/release.nfo", remote, content, new Properties()));
            verify(ftp, never()).connect(any(InetAddress.class), anyInt());
        }
        FTPSClient spaced = connected();
        var file = SrrdbClientTest.file("release notes.nfo", content);
        when(spaced.sendCommand("PRET", "STOR release notes.nfo")).thenReturn(200);
        when(spaced.storeFile(eq("release notes.nfo"), any(InputStream.class))).thenReturn(true);
        when(spaced.getSize("release notes.nfo")).thenReturn(Long.toString(content.length));
        when(spaced.sendCommand("PRET", "RETR release notes.nfo")).thenReturn(200);
        when(spaced.retrieveFile(eq("release notes.nfo"), any(OutputStream.class))).thenAnswer(call -> {
            ((OutputStream) call.getArgument(1)).write(content); return true;
        });
        try (var uploader = uploader(spaced)) {
            uploader.install("evo", "/SECTION/Release-GRP/release notes.nfo", file, content, new Properties());
        }
        verify(spaced, never()).sendCommand("XCRC", "release notes.nfo");
        verify(spaced).retrieveFile(eq("release notes.nfo"), any(OutputStream.class));
    }
}
