package org.drftpd.webadmin.master;

import org.apache.commons.net.ProtocolCommandEvent;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SrrdbFtpTraceTest {
    @Test void neverLogsAuthenticationArgumentsOrReplyBodies() {
        var lines = new ArrayList<String>();
        var trace = new SrrdbFtpTrace("secret-password", lines::add);
        for (String command : new String[]{"USER", "PASS", "ACCT", "ADAT", "OTHER"}) {
            trace.protocolCommandSent(new ProtocolCommandEvent(this, command, command + " private-token\r\n"));
            trace.protocolReplyReceived(new ProtocolCommandEvent(this, 331, "331 private-token secret-password\r\n"));
        }
        String log = String.join("\n", lines);
        assertFalse(log.contains("private-token"));
        assertFalse(log.contains("secret-password"));
        assertTrue(log.contains("PASS [arguments omitted]"));
        assertTrue(log.contains("lastReply=331"));
    }

    @Test void recordsPretPasvSelectedSlaveAndCommandStateWithoutNfoContents() {
        var lines = new ArrayList<String>();
        var trace = new SrrdbFtpTrace("secret-password", lines::add);
        trace.protocolCommandSent(new ProtocolCommandEvent(this, "CWD", "CWD /SECTION/Release-GRP\r\n"));
        trace.protocolReplyReceived(new ProtocolCommandEvent(this, 250, "250- PRIVATE NFO CONTENT\r\n250 OK\r\n"));
        trace.protocolCommandSent(new ProtocolCommandEvent(this, "PRET", "PRET STOR release.sfv\r\n"));
        trace.protocolReplyReceived(new ProtocolCommandEvent(this, 200, "200 OK\r\n"));
        trace.protocolCommandSent(new ProtocolCommandEvent(this, "PASV", "PASV\r\n"));
        trace.protocolReplyReceived(new ProtocolCommandEvent(this, 227,
                "227- Using test-slave for upcoming transfer\r\n227 Entering Passive Mode (192,0,2,10,195,80).\r\n"));
        trace.event("failed", trace.state() + " secret-password\r\nforged-entry");
        String log = String.join("\n", lines);
        assertTrue(log.contains("PRET STOR release.sfv"));
        assertTrue(log.contains("Using test-slave"));
        assertTrue(log.contains("192,0,2,10,195,80"));
        assertTrue(log.contains("lastCommand=PASV lastReply=227"));
        assertFalse(log.contains("PRIVATE NFO CONTENT"));
        assertFalse(log.contains("secret-password"));
        assertTrue(log.contains("[REDACTED]"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\r") || line.contains("\n")));
        String id = lines.get(0).split("id=")[1].split(" ")[0];
        assertTrue(lines.stream().allMatch(line -> line.contains("id=" + id)));
    }

    @Test void boundsUntrustedTextAndKeepsAttemptIdsDistinct() {
        var a = new ArrayList<String>(); var b = new ArrayList<String>();
        var first = new SrrdbFtpTrace("", a::add);
        var second = new SrrdbFtpTrace("", b::add);
        first.event("test", "x".repeat(10000)); second.event("test", "ok");
        assertTrue(a.get(0).length() < 1200);
        assertTrue(a.get(0).contains("[truncated]"));
        assertNotEquals(a.get(0).split("id=")[1].split(" ")[0], b.get(0).split("id=")[1].split(" ")[0]);
    }
}
