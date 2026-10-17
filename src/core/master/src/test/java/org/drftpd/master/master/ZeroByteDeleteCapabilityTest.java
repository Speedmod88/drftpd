package org.drftpd.master.master;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.network.AsyncResponse;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZeroByteDeleteCapabilityTest {
    @Test
    void legacySlaveKeepsQueuedDeleteWithoutReceivingUnknownCommand() {
        ProbeSlave slave = new ProbeSlave(4096);
        slave.setRemerging(true);
        slave.queueDelete();
        IOException error = assertThrows(IOException.class,
                () -> slave.prepareQueuedUploadRetry("/release/file.rar"));
        assertTrue(error.getMessage().contains("does not support"));
        assertEquals(1, slave.getRenameQueue().size());
        assertEquals(List.of("maxpath"), slave.commands);
    }

    @Test
    void onlyExplicitCapabilityIsAccepted() throws Exception {
        assertTrue(new ProbeSlave(-24003).probe());
        assertFalse(new ProbeSlave(-1).probe());
        assertFalse(new ProbeSlave(-24001).probe());
    }

    private static class ProbeSlave extends RemoteSlave {
        final int response;
        final List<String> commands = new ArrayList<>();
        ProbeSlave(int response) { super("probe"); this.response = response; }
        void queueDelete() { addQueueDelete("/release/file.rar"); }
        boolean probe() throws Exception { return supportsZeroByteDelete(); }
        @Override public String fetchIndex() { return "01"; }
        @Override public void sendCommand(AsyncCommandArgument command) {
            commands.add(command.getName());
            assertEquals("delete-zero-byte-v1", command.getArgs());
        }
        @Override public AsyncResponse fetchResponseWithoutDisconnect(String index, int wait) {
            return new AsyncResponseMaxPath(index, response);
        }
        @Override protected void scheduleQueuedOperationsCommit() { }
    }
}
