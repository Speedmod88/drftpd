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
package org.drftpd.master.sitebot.announce;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.bushe.swing.event.annotation.EventSubscriber;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.slavemanagement.SlaveManagement;
import org.drftpd.master.event.DirectoryFtpEvent;
import org.drftpd.master.event.MasterEvent;
import org.drftpd.master.event.SlaveEvent;
import org.drftpd.master.exceptions.SlaveUnavailableException;
import org.drftpd.master.sitebot.AbstractAnnouncer;
import org.drftpd.master.sitebot.AnnounceWriter;
import org.drftpd.master.sitebot.SiteBot;
import org.drftpd.master.sitebot.config.AnnounceConfig;
import org.drftpd.master.sitebot.config.ChannelConfig;
import org.drftpd.master.sitebot.event.InviteEvent;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.slavemanagement.SlaveStatus;
import org.drftpd.master.util.ReplacerUtils;
import org.drftpd.master.vfs.DirectoryHandle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * @author djb61
 * @version $Id$
 */
public class BasicAnnouncer extends AbstractAnnouncer {

    private static final Logger logger = LogManager.getLogger(BasicAnnouncer.class);
    private static final int MAX_PENDING_SLAVE_EVENTS = 1024;

    private AnnounceConfig _config;

    private ResourceBundle _bundle;

    private final Object _slaveEventLock = new Object();

    private final PendingSlaveEventQueue _pendingSlaveEvents =
            new PendingSlaveEventQueue(MAX_PENDING_SLAVE_EVENTS);

    private final Map<String, String> _announcedSlaveStates = new HashMap<>();

    private boolean _slaveEventsReady;

    public void initialise(AnnounceConfig config, ResourceBundle bundle) {
        _config = config;
        _bundle = bundle;

        // Subscribe to events
        AnnotationProcessor.process(this);
        GlobalContext.registerSiteBotSlaveEventConsumer(this::onSlaveEvent);
    }

    public void stop() {
        onBotDisconnected();
        AnnotationProcessor.unprocess(this);
        synchronized (_slaveEventLock) {
            _pendingSlaveEvents.clear();
            _announcedSlaveStates.clear();
        }
    }

    public String[] getEventTypes() {
        return new String[]{"mkdir", "rmdir", "wipe", "addslave",
                "delslave", "msgslave", "msgmaster", "invite"};
    }

    public void setResourceBundle(ResourceBundle bundle) {
        _bundle = bundle;
    }

    @EventSubscriber(eventServiceName = GlobalContext.SERVICE_NAME_EVENT_BUS_PRIORITY_SITEBOT)
    public void onDirectoryFtpEvent(DirectoryFtpEvent direvent) {
        if ("MKD".equals(direvent.getCommand())) {
            outputDirectoryEvent(direvent, "mkdir");
        } else if ("RMD".equals(direvent.getCommand())) {
            outputDirectoryEvent(direvent, "rmdir");
        } else if ("WIPE".equals(direvent.getCommand())) {
            if (direvent.getDirectory().isDirectory()) {
                outputDirectoryEvent(direvent, "wipe");
            }
        }
    }

    @EventSubscriber(eventServiceName = GlobalContext.SERVICE_NAME_EVENT_BUS_SITEBOT_SLAVE)
    public void onSlaveEvent(SlaveEvent event) {
        synchronized (_slaveEventLock) {
            if (!_slaveEventsReady || !_config.getBot().isConnected()) {
                _slaveEventsReady = false;
                queueSlaveEvent(event);
                return;
            }
        }
        outputSlaveEvent(event);
    }

    @Override
    protected void onBotConnected() {
        int queuedEvents = 0;
        while (_config.getBot().isConnected()) {
            List<SlaveEvent> pending;
            synchronized (_slaveEventLock) {
                if (_pendingSlaveEvents.isEmpty()) {
                    _slaveEventsReady = true;
                    break;
                }
                pending = _pendingSlaveEvents.drain();
            }
            queuedEvents += pending.size();
            for (SlaveEvent event : pending) {
                outputSlaveEvent(event);
            }
        }

        if (!_config.getBot().isConnected()) {
            onBotDisconnected();
            return;
        }

        int reconciledSlaves = reconcileAvailableSlaves();
        logger.info("SiteBot {} slave announcements ready: drained={}, reconciled={}",
                _config.getBot().getBotName(), queuedEvents, reconciledSlaves);
    }

    @Override
    protected void onBotDisconnected() {
        synchronized (_slaveEventLock) {
            _slaveEventsReady = false;
        }
    }

    private void queueSlaveEvent(SlaveEvent event) {
        SlaveEvent dropped = _pendingSlaveEvents.add(event);
        if (dropped != null) {
            logger.warn("SiteBot {} slave announcement queue full; dropping oldest {} event for {}",
                    _config.getBot().getBotName(), dropped.getCommand(),
                    dropped.getRSlave().getName());
        }
        logger.debug("Queued {} event for slave {} while SiteBot {} is not ready; queue={}",
                event.getCommand(), event.getRSlave().getName(),
                _config.getBot().getBotName(), _pendingSlaveEvents.size());
    }

    private int reconcileAvailableSlaves() {
        int reconciled = 0;
        for (RemoteSlave slave : GlobalContext.getGlobalContext().getSlaveManager().getSlaves()) {
            if (!slave.isAvailable()) {
                continue;
            }
            try {
                if (outputSlaveEvent(new SlaveEvent("ADDSLAVE", slave,
                        slave.getSlaveStatusAvailable()))) {
                    reconciled++;
                }
            } catch (SlaveUnavailableException e) {
                logger.debug("Slave {} changed state during SiteBot reconciliation", slave.getName());
            }
        }
        return reconciled;
    }

    private boolean outputSlaveEvent(SlaveEvent event) {
        String command = event.getCommand();
        String slaveName = event.getRSlave().getName();
        if (PendingSlaveEventQueue.isStateEvent(event)) {
            synchronized (_slaveEventLock) {
                if (command.equals(_announcedSlaveStates.get(slaveName))) {
                    logger.debug("Skipping duplicate {} announcement for slave {}", command, slaveName);
                    return false;
                }
                _announcedSlaveStates.put(slaveName, command);
            }
        }

        logger.info("Processing IRC slave event: command={}, slave={}", command, slaveName);
        Map<String, Object> env = new HashMap<>(SiteBot.GLOBAL_ENV);
        env.put("slave", slaveName);
        env.put("message", event.getMessage());

        switch (command) {
            case "ADDSLAVE" -> {
                SlaveStatus status = event.getSlaveStatus();
                if (status == null) {
                    try {
                        status = event.getRSlave().getSlaveStatusAvailable();
                    } catch (SlaveUnavailableException e) {
                        logger.warn("Unable to announce ADDSLAVE for {} because no status snapshot is available",
                                slaveName, e);
                        synchronized (_slaveEventLock) {
                            _announcedSlaveStates.remove(slaveName, command);
                        }
                        return false;
                    }
                }
                SlaveManagement.fillEnvWithSlaveStatus(env, status);
                outputSimpleEvent(ReplacerUtils.jprintf("addslave", env, _bundle), "addslave");
            }
            case "DELSLAVE" -> outputSimpleEvent(ReplacerUtils.jprintf("delslave", env, _bundle), "delslave");
            case "MSGSLAVE" -> outputSimpleEvent(ReplacerUtils.jprintf("msgslave", env, _bundle), "msgslave");
        }
        return true;
    }

    @EventSubscriber
    public void onMasterEvent(MasterEvent event) {
        Map<String, Object> env = new HashMap<>(SiteBot.GLOBAL_ENV);
        env.put("message", event.getMessage());

        outputSimpleEvent(ReplacerUtils.jprintf("msgmaster", env, _bundle), "msgmaster");
    }

    @EventSubscriber
    public void onInviteEvent(InviteEvent event) {
        if (_config.getBot().getBotName().equalsIgnoreCase(event.getTargetBot())) {
            Map<String, Object> env = new HashMap<>(SiteBot.GLOBAL_ENV);
            env.put("user", event.getUser().getName());
            env.put("nick", event.getIrcNick());
            env.put("group", event.getUser().getGroup());
            if (event.getCommand().equals("INVITE")) {
                outputSimpleEvent(ReplacerUtils.jprintf("invite.success", env, _bundle), "invite");
                for (ChannelConfig chan : _config.getBot().getConfig().getChannels()) {
                    if (chan.isPermitted(event.getUser())) {
                        _config.getBot().sendInvite(event.getIrcNick(), chan.getName());
                    }
                }
            } else if (event.getCommand().equals("BINVITE")) {
                outputSimpleEvent(ReplacerUtils.jprintf("invite.failed", env, _bundle), "invite");
            }
        }
    }

    private void outputDirectoryEvent(DirectoryFtpEvent direvent, String type) {
        AnnounceWriter writer = _config.getPathWriter(type, direvent.getDirectory());
        // Check we got a writer back, if it is null do nothing and ignore the event
        if (writer != null) {
            Map<String, Object> env = new HashMap<>(SiteBot.GLOBAL_ENV);
            fillEnvSection(env, direvent, writer);
            sayOutput(ReplacerUtils.jprintf(type, env, _bundle), writer);
        }
    }

    private void outputSimpleEvent(String output, String type) {
        AnnounceWriter writer = _config.getSimpleWriter(type);
        // Check we got a writer back, if it is null do nothing and ignore the event
        if (writer != null) {
            sayOutput(output, writer);
        }
    }

    private void fillEnvSection(Map<String, Object> env,
                                DirectoryFtpEvent direvent, AnnounceWriter writer) {
        DirectoryHandle dir = direvent.getDirectory();
        env.put("user", direvent.getUser().getName());
        env.put("group", direvent.getUser().getGroup());
        env.put("section", writer.getSectionName(dir));
        env.put("sectioncolor", GlobalContext.getGlobalContext().getSectionManager().lookup(dir).getColor());
        env.put("path", writer.getPath(dir));
    }
}
