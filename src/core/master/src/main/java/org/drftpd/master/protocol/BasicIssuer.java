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
package org.drftpd.master.protocol;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.exceptions.SSLUnavailableException;
import org.drftpd.common.network.AsyncCommand;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.slave.TransferIndex;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.slavemanagement.RemoteSlave;


/**
 * @author fr0w
 * @version $Id$
 * @see AbstractBasicIssuer
 */
public class BasicIssuer extends AbstractBasicIssuer {

	private static final Logger logger = LogManager.getLogger(BasicIssuer.class);

    @Override
    public String getProtocolName() {
        return "BasicProtocol";
    }

    public String issueChecksumToSlave(RemoteSlave rslave, String path) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "checksum", path));

        logger.info("!! issueChecksumToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueConnectToSlave(RemoteSlave rslave, String ip, int port,
                                      boolean encryptedDataChannel, boolean useSSLClientHandshake) throws SlaveUnavailableException, SSLUnavailableException {

        boolean sslReady = rslave.getTransientKeyedMap().getObjectBoolean(RemoteSlave.SSL);
        if (!sslReady && encryptedDataChannel) {
            // althought ssl was requested the slave does not support ssl.
            throw new SSLUnavailableException("Encryption was requested but '" + rslave.getName() + "' doesn't support it");
        }

        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "connect",
                new String[]{ip + ":" + port, String.valueOf(encryptedDataChannel), String.valueOf(useSSLClientHandshake)}));

        logger.info("!! issueConnectToSlave done with cmd index '{}'", index);

        return index;
    }

    /**
     * @return String index, needs to be used to fetch the response
     */
    public String issueDeleteToSlave(RemoteSlave rslave, String sourceFile) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "delete", sourceFile));

        logger.info("!! issueDeleteToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueListenToSlave(RemoteSlave rslave, boolean isSecureTransfer,
                                     boolean useSSLClientMode, boolean useLanIP) throws SlaveUnavailableException, SSLUnavailableException {

        boolean sslReady = rslave.getTransientKeyedMap().getObjectBoolean(RemoteSlave.SSL);
        if (!sslReady && isSecureTransfer) {
            // althought ssl was requested the slave does not support ssl.
            throw new SSLUnavailableException("The transfer needed SSL but '" + rslave.getName() + "' doesn't support it");
        }

        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "listen", ""
                + isSecureTransfer + ":" + useSSLClientMode + ":" + useLanIP));

        logger.info("!! issueListenToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueMaxPathToSlave(RemoteSlave rslave) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommand(index, "maxpath"));

        logger.info("!! issueMaxPathToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issuePingToSlave(RemoteSlave rslave) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommand(index, "ping"));

        logger.info("!! issuePingToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueReceiveToSlave(RemoteSlave rslave, String name, char c, long position,
                                      String inetAddress, TransferIndex tindex, long minSpeed, long maxSpeed) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "receive",
                new String[]{String.valueOf(c), String.valueOf(position),
                        tindex.toString(), inetAddress, name, String.valueOf(minSpeed), String.valueOf(maxSpeed)}));

        logger.info("!! issueReceiveToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueRenameToSlave(RemoteSlave rslave, String from, String toDirPath,
                                     String toName) throws SlaveUnavailableException {
        if (toDirPath.length() == 0) { // needed for files in root
            toDirPath = "/";
        }
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "rename",
                new String[]{from, toDirPath, toName}));

        logger.info("!! issueRenameToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueStatusToSlave(RemoteSlave rslave) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommand(index, "status"));

        logger.info("!! issueStatusToSlave done with cmd index '{}'", index);

        return index;
    }


    public String issueAbortToSlave(RemoteSlave rslave, TransferIndex transferIndex, String reason)
            throws SlaveUnavailableException {
        if (reason == null) {
            reason = "null";
        }
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "abort",
                new String[]{transferIndex.toString(), reason}));

        logger.info("!! issueAbortToSlave done with cmd index '{}'", index);
        return index;
    }


    public String issueSendToSlave(RemoteSlave rslave, String name, char c, long position,
                                   String inetAddress, TransferIndex tindex, long minSpeed, long maxSpeed) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "send",
                new String[]{String.valueOf(c), String.valueOf(position), tindex.toString(),
                        inetAddress, name, String.valueOf(minSpeed), String.valueOf(maxSpeed)}));

        logger.info("!! issueSendToSlave done with cmd index '{}'", index);

        return index;
    }

    public String issueRemergeToSlave(RemoteSlave rslave, String path, boolean partialRemerge, long skipAgeCutoff, long masterTime, boolean instantOnline)
            throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommandArgument(index, "remerge", new String[]{path,
                Boolean.toString(partialRemerge), Long.toString(skipAgeCutoff), Long.toString(masterTime), Boolean.toString(instantOnline)}));

        logger.info("!! issueRemergeToSlave done with cmd index '{}'", index);

        return index;
    }

    public void issueRemergePauseToSlave(RemoteSlave rslave) throws SlaveUnavailableException {
        rslave.sendCommand(new AsyncCommand("remergePause", "remergePause"));

        logger.info("!! issueRemergePauseToSlave done");

    }

    public void issueRemergeResumeToSlave(RemoteSlave rslave) throws SlaveUnavailableException {
        rslave.sendCommand(new AsyncCommand("remergeResume", "remergeResume"));

        logger.info("!! issueRemergeResumeToSlave done");

    }

    @Override
    public String issueCheckSSL(RemoteSlave rslave) throws SlaveUnavailableException {
        String index = rslave.fetchIndex();
        rslave.sendCommand(new AsyncCommand(index, "checkSSL"));

        logger.info("!! issueCheckSSL done with cmd index '{}'", index);

        return index;
    }
}
