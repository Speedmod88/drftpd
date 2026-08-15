/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.master.master;

import com.cedarsoftware.util.io.JsonReader;
import com.cedarsoftware.util.io.JsonWriter;
import org.drftpd.common.network.AsyncResponse;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.Event;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.DummyRemoteSlave;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveManager;
import org.drftpd.master.tests.DummySlaveManager;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.slave.protocol.QueuedOperation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.*;


/**
 * @author mog
 * @version $Id$
 */
public class RemoteSlaveTest {

    @Test
    public void testEquals() {
        DummySlaveManager sm = new DummySlaveManager();
        GC gc = new GC();

        //sm.setGlobalContext(gc); -zubov
        gc.setSlaveManager(sm);

        RemoteSlave rslave1 = new DummyRemoteSlave("test1");
        RemoteSlave rslave2 = new DummyRemoteSlave("test1");
        RemoteSlave rslave3 = new DummyRemoteSlave("test2");
        assertEquals(rslave1, rslave1);
        assertEquals(rslave1, rslave2);
        assertNotEquals(rslave1, rslave3);
    }

    @Test
    public void testQueuedOperationsSurviveSerialization() {
        RemoteSlave original = new RemoteSlave("persisted");
        ConcurrentLinkedDeque<QueuedOperation> queue = new ConcurrentLinkedDeque<>();
        queue.add(new QueuedOperation("/source/delete", null));
        queue.add(new QueuedOperation("/source/rename", "/destination/rename"));
        original.setRenameQueue(queue);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.write(original);
        }

        RemoteSlave restored;
        try (JsonReader reader = new JsonReader(new ByteArrayInputStream(output.toByteArray()))) {
            restored = (RemoteSlave) reader.readObject();
        }

        assertEquals(2, restored.getRenameQueue().size());
        assertEquals("/source/delete", restored.getRenameQueue().peekFirst().getSource());
        assertEquals("/destination/rename", restored.getRenameQueue().peekLast().getDestination());
    }

    @Test
    public void testFailedQueuedOperationRemainsForRetry() throws Exception {
        RetryRemoteSlave slave = new RetryRemoteSlave("retry");
        slave.getRenameQueue().add(new QueuedOperation("/source/delete", null));

        assertThrows(IOException.class, slave::processQueue);
        assertEquals(1, slave.getRenameQueue().size());

        slave.setFail(false);
        slave.processQueue();
        assertTrue(slave.getRenameQueue().isEmpty());
    }

    public void testAddNetworkError()
            throws InterruptedException {
        DummySlaveManager sm = new DummySlaveManager();
        GC gc = new GC();
        //sm.setGlobalContext(gc); -zubov
        gc.setSlaveManager(sm);

        DummyRemoteSlave rslave = new DummyRemoteSlave("test");
        sm.addSlave(rslave);
        rslave.setProperty("errortimeout", "100");
        rslave.setProperty("maxerrors", "2");
        rslave.fakeConnect();
        rslave.setAvailable(true);
        assertTrue(rslave.isAvailable());
        rslave.addNetworkError(new SocketException());
        assertTrue(rslave.isAvailable());
        rslave.addNetworkError(new SocketException());
        assertTrue(rslave.isAvailable());
    }

    public static class GC extends GlobalContext {
        public GC() {
            _gctx = this;
        }

        public SlaveManager getSlaveManager() {
            return super.getSlaveManager();
        }

        public void setSlaveManager(SlaveManager sm) {
            _slaveManager = sm;
        }

        public void dispatchFtpEvent(Event event) { }

        public DirectoryHandle getRoot() {
            System.out.println("new lrf");

            return new DirectoryHandle("/");
        }
    }

    public static class RemergeRemoteSlave extends RemoteSlave {
        private HashSet<String> _filelist = null;

        public RemergeRemoteSlave(String name) {
            super(name);
        }

        /**
         * @param filelist
         */
        public void setFileList(HashSet<String> filelist) {
            _filelist = filelist;
        }

        public String issueDeleteToSlave(String sourceFile) {
            _filelist.remove(sourceFile);

            return null;
        }

        public String issueRenameToSlave(String from, String toDirPath,
                                         String toName) {
            _filelist.remove(from);
            _filelist.add(toDirPath + "/" + toName);

            return null;
        }

        public void simpleDelete(String path) {
            addQueueDelete(path);
        }

        public void simpleRename(String from, String toDirPath, String toName) {
            addQueueRename(from, toDirPath + "/" + toName);
        }

        public AsyncResponse fetchResponse(String index)
                throws SlaveUnavailableException {
            return null;
        }
    }

    private static class RetryRemoteSlave extends RemoteSlave {
        private boolean _fail = true;

        private RetryRemoteSlave(String name) {
            super(name);
        }

        private void setFail(boolean fail) {
            _fail = fail;
        }

        @Override
        protected void executeQueuedOperation(QueuedOperation item) throws IOException {
            if (_fail) {
                throw new IOException("transient failure");
            }
        }

        @Override
        public void commit() {
            // Keep this unit test independent from the on-disk slave manager.
        }
    }
}
