/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 */
package org.drftpd.webadmin.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.bushe.swing.event.annotation.EventSubscriber;
import org.drftpd.common.extensibility.PluginInterface;
import org.drftpd.master.event.ReloadEvent;

public class WebAdminPlugin implements PluginInterface {
    private static final Logger logger = LogManager.getLogger(WebAdminPlugin.class);

    private WebAdminServer server;

    @Override
    public synchronized void startPlugin() {
        AnnotationProcessor.process(this);
        reload();
    }

    @Override
    public synchronized void stopPlugin(String reason) {
        AnnotationProcessor.unprocess(this);
        stopServer();
        logger.info("HTTPS web administration plugin stopped: {}", reason);
    }

    @EventSubscriber
    public synchronized void onReloadEvent(ReloadEvent event) {
        logger.info("Received reload event, reloading HTTPS web administration plugin");
        reload();
    }

    private void reload() {
        stopServer();
        try {
            WebAdminSettings settings = WebAdminSettings.load();
            if (!settings.enabled) {
                logger.info("HTTPS web administration plugin is disabled");
                return;
            }
            server = new WebAdminServer(settings);
            server.start();
        } catch (RuntimeException e) {
            logger.error("Unable to start HTTPS web administration plugin", e);
            server = null;
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
