/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 */
package org.drftpd.webadmin.master;

import org.drftpd.common.util.ConfigLoader;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class WebAdminSettings {
    private static final Set<String> DEFAULT_COMMANDS = Set.of(
            "site search", "site find", "site dupe2", "site nuke", "site unnuke",
            "site wipe", "site delete", "site addslave", "site slave", "site slaves", "site reload");

    final boolean enabled;
    final InetAddress bindAddress;
    final int port;
    final int requestThreads;
    final int requestQueue;
    final int commandThreads;
    final int commandQueue;
    final int sessionTimeoutMinutes;
    final int loginMaxAttempts;
    final int loginWindowSeconds;
    final int defaultLogLines;
    final int maximumLogLines;
    final int maximumConfigBytes;
    final Path configRoot;
    final Path logsRoot;
    final Path srrdbState;
    final int srrdbScanLimit;
    final int srrdbMaximumBytes;
    final Set<String> allowedCommands;

    WebAdminSettings(Properties properties) {
        enabled = booleanValue(properties, "enabled", false);
        port = intValue(properties, "port", 8443, 1, 65535);
        requestThreads = intValue(properties, "worker.threads", 8, 2, 64);
        requestQueue = intValue(properties, "worker.queue", 64, 8, 1024);
        commandThreads = intValue(properties, "command.threads", 2, 1, 16);
        commandQueue = intValue(properties, "command.queue", 32, 1, 512);
        sessionTimeoutMinutes = intValue(properties, "session.timeout.minutes", 1440, 5, 1440);
        loginMaxAttempts = intValue(properties, "login.max.attempts", 5, 1, 100);
        loginWindowSeconds = intValue(properties, "login.window.seconds", 60, 10, 3600);
        defaultLogLines = intValue(properties, "logs.default.lines", 500, 10, 2000);
        maximumLogLines = intValue(properties, "logs.maximum.lines", 2000, defaultLogLines, 10000);
        maximumConfigBytes = intValue(properties, "config.maximum.bytes", 2 * 1024 * 1024, 4096,
                16 * 1024 * 1024);
        configRoot = rootPath(properties.getProperty("config.root", "config"));
        logsRoot = rootPath(properties.getProperty("logs.root", "logs"));
        srrdbState = rootPath(properties.getProperty("srrdb.state.file", "userdata/webadmin/srrdb.json"));
        srrdbScanLimit = intValue(properties, "srrdb.scan.limit", 100, 1, 1000);
        srrdbMaximumBytes = intValue(properties, "srrdb.maximum.bytes", 1048576, 1024, 4194304);
        bindAddress = address(properties.getProperty("bind", "127.0.0.1"));
        allowedCommands = Collections.unmodifiableSet(loadAllowedCommands(properties));
    }

    static WebAdminSettings load() {
        return new WebAdminSettings(ConfigLoader.loadPluginConfig("webadmin.conf"));
    }

    private static Set<String> loadAllowedCommands(Properties properties) {
        Set<String> commands = new LinkedHashSet<>();
        for (int index = 1; ; index++) {
            String command = properties.getProperty("command.allowed." + index);
            if (command == null) {
                break;
            }
            command = normalizeCommand(command);
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        if (commands.isEmpty()) {
            commands.addAll(DEFAULT_COMMANDS);
        }
        return commands;
    }

    static String normalizeCommand(String command) {
        return command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Path rootPath(String value) {
        return Path.of(ConfigLoader.configPath(value.trim())).toAbsolutePath().normalize();
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value.trim());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid webadmin bind address: " + value, e);
        }
    }

    private static boolean booleanValue(Properties properties, String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(defaultValue)).trim());
    }

    private static int intValue(Properties properties, String key, int defaultValue, int minimum, int maximum) {
        String configured = properties.getProperty(key);
        int value;
        try {
            value = configured == null ? defaultValue : Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + configured, e);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
