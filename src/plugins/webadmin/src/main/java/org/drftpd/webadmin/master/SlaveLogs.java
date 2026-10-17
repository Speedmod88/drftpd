package org.drftpd.webadmin.master;

import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.network.AsyncResponse;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.slave.RemoteLogReader;
import org.drftpd.slave.network.AsyncResponseLog;
import org.drftpd.slave.network.AsyncResponseMaxPath;
import java.io.IOException;
import java.util.concurrent.Semaphore;

final class SlaveLogs {
    private final Semaphore slots = new Semaphore(2);

    AsyncResponseLog request(RemoteSlave slave, String... args) throws Exception {
        if (!slots.tryAcquire()) throw new IOException("Remote log reader busy; retry shortly");
        try {
            if (!slave.isOnline()) throw new IOException("Slave is offline");
            long generation = slave.getConnectionGeneration();
            AsyncResponse capability = send(slave, "maxpath", RemoteLogReader.PROBE);
            if (!(capability instanceof AsyncResponseMaxPath result)) throw new IOException("Invalid capability response");
            requireSupported(result.getMaxPath());
            if (generation != slave.getConnectionGeneration()) throw new IOException("Slave reconnected; retry");
            AsyncResponse response = send(slave, "remotelog", args);
            if (!(response instanceof AsyncResponseLog log)) throw new IOException("Invalid log response");
            if (log.getError() != null) throw new IOException(log.getError());
            return log;
        } finally { slots.release(); }
    }

    static void requireSupported(int capability) throws IOException {
        if (capability == RemoteLogReader.DISABLED) throw new IOException("Enable remote.logs.enabled in slave.conf and restart the slave");
        if (capability != RemoteLogReader.SUPPORTED) throw new IOException("This slave needs the remote-log-capable slave JAR");
    }

    private AsyncResponse send(RemoteSlave slave, String command, String... args) throws Exception {
        String index = slave.fetchIndex();
        slave.sendCommand(new AsyncCommandArgument(index, command, args));
        return slave.fetchResponseWithoutDisconnect(index, 10000);
    }
}
