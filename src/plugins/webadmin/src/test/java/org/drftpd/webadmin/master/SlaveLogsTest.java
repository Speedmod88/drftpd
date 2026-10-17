package org.drftpd.webadmin.master;

import org.drftpd.common.network.*;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.slave.RemoteLogReader;
import org.drftpd.slave.network.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SlaveLogsTest {
    static class Slave extends RemoteSlave {
        final List<String> commands = new ArrayList<>();
        final int capability;
        Slave(int capability) { super("test"); this.capability=capability; }
        @Override public boolean isOnline() { return true; }
        @Override public String fetchIndex() { return "1"; }
        @Override public void sendCommand(AsyncCommandArgument command) { commands.add(command.getName()); }
        @Override public AsyncResponse fetchResponseWithoutDisconnect(String index, int timeout) {
            assertEquals(10000, timeout);
            if (commands.get(commands.size()-1).equals("maxpath")) return new AsyncResponseMaxPath(index, capability);
            return new AsyncResponseLog(index, List.of("slave.log"), "safe log", null);
        }
    }
    @Test void oldOrDisabledSlaveNeverReceivesNewCommand() {
        for (int capability : new int[]{255, 4096, -1, RemoteLogReader.DISABLED}) {
            Slave slave = new Slave(capability);
            assertThrows(java.io.IOException.class, () -> new SlaveLogs().request(slave, "list"));
            assertEquals(List.of("maxpath"), slave.commands);
        }
    }
    @Test void supportedSlaveUsesBoundedNonDisconnectingExchange() throws Exception {
        Slave slave = new Slave(RemoteLogReader.SUPPORTED);
        assertEquals(List.of("slave.log"), new SlaveLogs().request(slave, "list").getFiles());
        assertEquals(List.of("maxpath", "remotelog"), slave.commands);
    }
}
