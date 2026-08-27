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
package org.drftpd.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bushe.swing.event.EventServiceExistsException;
import org.bushe.swing.event.EventServiceLocator;
import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.drftpd.common.extensibility.CommandHook;
import org.drftpd.common.extensibility.PluginDependencies;
import org.drftpd.common.extensibility.PluginInterface;
import org.drftpd.common.network.SSLGetContext;
import org.drftpd.common.util.PortRange;
import org.drftpd.common.util.PropertyHelper;
import org.drftpd.master.commands.CommandManagerInterface;
import org.drftpd.master.config.ConfigInterface;
import org.drftpd.master.config.ConfigManager;
import org.drftpd.master.cron.TimeEventInterface;
import org.drftpd.master.cron.TimeManager;
import org.drftpd.master.event.AsyncThreadSafeEventService;
import org.drftpd.master.event.KeyedAsyncThreadSafeEventService;
import org.drftpd.master.event.MessageEvent;
import org.drftpd.master.event.SlaveEvent;
import org.drftpd.master.exceptions.FatalException;
import org.drftpd.master.exceptions.SlaveFileException;
import org.drftpd.master.indexation.IndexEngineInterface;
import org.drftpd.master.sections.SectionManagerInterface;
import org.drftpd.master.slavemanagement.SlaveManager;
import org.drftpd.master.slaveselection.SlaveSelectionManagerInterface;
import org.drftpd.master.usermanager.AbstractUserManager;
import org.drftpd.master.usermanager.UserManager;
import org.drftpd.master.vfs.CommitManager;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.VirtualFileSystem;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.drftpd.common.util.ConfigLoader.configPath;

/**
 * @author mog
 * @author zubov
 * @version $Id$
 */

public class GlobalContext {

    public static final String VERSION = "DrFTPD 4.0.12-git";
    private static final Logger logger = LogManager.getLogger(GlobalContext.class);
    public static final String SERVICE_NAME_EVENT_BUS_PRIORITY_SITEBOT = "EventBusSiteBot";
    public static final String SERVICE_NAME_EVENT_BUS_SITEBOT_FUNCTIONAL = "EventBusSiteBotFunctional";
    public static final String SERVICE_NAME_EVENT_BUS_SITEBOT_SLAVE = "EventBusSiteBotSlave";
    public static final String SERVICE_NAME_EVENT_BUS_SLOWEST = "EventBusSlowest";
    protected static GlobalContext _gctx;
    private static final DirectoryHandle root = new DirectoryHandle(VirtualFileSystem.separator);
    private static final AsyncThreadSafeEventService eventService = new AsyncThreadSafeEventService();
    private static final KeyedAsyncThreadSafeEventService eventService2 =
            new KeyedAsyncThreadSafeEventService("ReleaseEvent");
    private static final AsyncThreadSafeEventService siteBotFunctionalEventService =
            new AsyncThreadSafeEventService();
    private static final AsyncThreadSafeEventService eventService3 = new AsyncThreadSafeEventService();
    private static final AsyncThreadSafeEventService siteBotSlaveEventService = new AsyncThreadSafeEventService();
    private static final int SITEBOT_SLAVE_EVENT_QUEUE_CAPACITY = 1024;
    private static final Object siteBotSlaveEventLock = new Object();
    private static final ArrayDeque<SlaveEvent> pendingSiteBotSlaveEvents = new ArrayDeque<>();
    private static final List<Consumer<SlaveEvent>> siteBotSlaveEventConsumers = new ArrayList<>();
    private static int expectedSiteBotSlaveEventConsumers = 1;
    private static boolean siteBotSlaveEventDeliveryReady;
    private static Set<Method> hooksMethods;
    protected SectionManagerInterface _sectionManager;
    protected SlaveManager _slaveManager;
    protected AbstractUserManager _usermanager;
    protected SlaveSelectionManagerInterface _slaveSelectionManager;
    private ConfigInterface _config;
    private final List<PluginInterface> _plugins = new ArrayList<>();
    private String _shutdownMessage = null;
    private final AtomicInteger _timerThreadCounter = new AtomicInteger();
    private final ScheduledThreadPoolExecutor _scheduledTimerExecutor = new ScheduledThreadPoolExecutor(8, runnable -> {
        Thread thread = new Thread(runnable, "RegisteredTimer-" + _timerThreadCounter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, RegisteredTimer> _registeredTimers = new ConcurrentHashMap<>();
    // Retained for compatibility with external plugins. Internal tasks use the registered scheduler below.
    private Timer _timer = new Timer("GlobalContextTimer");
    private SSLContext _sslContext;
    private TimeManager _timeManager;
    private IndexEngineInterface _indexEngine;

    /**
     * If you're creating a GlobalContext object and it's not part of a TestCase
     * you're not doing it correctly, GlobalContext is a Singleton
     */
    protected GlobalContext() {
        _scheduledTimerExecutor.setRemoveOnCancelPolicy(true);
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("org.drftpd"))
                .setScanners(new MethodAnnotationsScanner()));

        hooksMethods = reflections.getMethodsAnnotatedWith(CommandHook.class);
        logger.debug("We have annotated (found) [{}] hook methods", hooksMethods.size());
    }

    public static Set<Method> getHooksMethods() {
        return hooksMethods;
    }

    public static Master getConnectionManager() {
        return Master.getConnectionManager();
    }

    public static ConfigInterface getConfig() {
        return getGlobalContext()._config;
    }

    public static GlobalContext getGlobalContext() {
        if (_gctx == null) {
            _gctx = new GlobalContext();
            try {

                EventServiceLocator.setEventService(EventServiceLocator.SERVICE_NAME_EVENT_BUS, eventService);
                EventServiceLocator.setEventService(SERVICE_NAME_EVENT_BUS_PRIORITY_SITEBOT, eventService2);
                EventServiceLocator.setEventService(SERVICE_NAME_EVENT_BUS_SITEBOT_FUNCTIONAL,
                        siteBotFunctionalEventService);
                EventServiceLocator.setEventService(SERVICE_NAME_EVENT_BUS_SITEBOT_SLAVE, siteBotSlaveEventService);
                EventServiceLocator.setEventService(SERVICE_NAME_EVENT_BUS_SLOWEST, eventService3);
            } catch (EventServiceExistsException e) {
                logger.error("Error setting event service, likely something using the event bus before GlobalContext is instantiated", e);
            }
        }
        return _gctx;
    }

    public static HashMap<String, Properties> loadCommandConfig(String confDirectory) {
        String configurationPath = configPath(confDirectory);
        HashMap<String, Properties> commandsConfig = new HashMap<>();
        LineNumberReader reader = null;
        try {
            Path targetPath = new File(configurationPath).toPath();
            Stream<Path> pathStream = Files.walk(targetPath);
            List<Path> confFiles = pathStream.filter(f -> f.getFileName().toString().endsWith(".conf")).collect(Collectors.toList());
            for (Path confFile : confFiles) {
                reader = new LineNumberReader(new FileReader(confFile.toFile()));
                String curLine;

                while (reader.ready()) {
                    curLine = reader.readLine();
                    if (curLine != null) {
                        curLine = curLine.trim();
                        if (curLine.startsWith("#") || curLine.equals("") || curLine.startsWith("skip")) {
                            // comment or blank line, ignore
                            continue;
                        }
                        if (curLine.endsWith("{")) {
                            // internal loop
                            String cmdName = curLine.substring(0, curLine.lastIndexOf("{") - 1).toLowerCase();
                            if (commandsConfig.containsKey(cmdName)) {
                                throw new FatalException(cmdName + " is already mapped on line " + reader.getLineNumber());
                            }
                            Properties p = getPropertiesUntilClosed(reader);
                            logger.trace("Adding command {}", cmdName);

                            commandsConfig.put(cmdName, p);
                        } else {
                            throw new FatalException("Expected line to end with \"{\" at line " + reader.getLineNumber());
                        }
                    }
                }
            }
            // done reading for new commands, must be finished
        } catch (IOException e) {
            throw new FatalException("Error loading " + confDirectory, e);
        } catch (Exception e) {
            if (reader != null) {
                logger.error("Error reading line {} in {}", reader.getLineNumber(), confDirectory);
            }
            throw new FatalException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return commandsConfig;
    }

    private static Properties getPropertiesUntilClosed(LineNumberReader reader) throws IOException {
        Properties p = new Properties();
        String curLine;
        while (reader.ready()) {
            curLine = reader.readLine();
            if (curLine != null) {
                curLine = curLine.trim();
                if (curLine.startsWith("#") || curLine.equals("")) {
                    // comment or blank line, ignore
                    continue;
                }
                if (curLine.equals("}")) {
                    // end of this block
                    return p;
                }
                // internal loop
                int spaceIndex = curLine.indexOf(" ");
                if (spaceIndex == -1) {
                    throw new FatalException("Line " + reader.getLineNumber() + " is not formatted properly");
                }
                String propName = curLine.substring(0, spaceIndex);
                String value = curLine.substring(spaceIndex).trim();
                String concatenate = p.getProperty(propName);
                if (concatenate == null) {
                    p.put(propName, value);
                } else {
                    p.put(propName, concatenate + "\n" + value);
                }
            }
        }
        throw new FatalException("Premature end of file, not enough \"}\" characters exist.");
    }

    public static AsyncThreadSafeEventService getEventService() {
        return eventService;
    }

    public static AsyncThreadSafeEventService getEventServiceSiteBotPriority() {
        return eventService2;
    }

    public static AsyncThreadSafeEventService getEventServiceSiteBotFunctional() {
        return siteBotFunctionalEventService;
    }

    /**
     * Publishes release events to the concurrent announcement bus and to the
     * serial functional bus used by link-maintenance plugins.
     */
    public static void publishSiteBotPriorityEvent(Object event) {
        getEventServiceSiteBotPriority().publishAsync(event);
        getEventServiceSiteBotFunctional().publishAsync(event);
    }

    public static AsyncThreadSafeEventService getEventServiceSlowest() {
        return eventService3;
    }

    public static AsyncThreadSafeEventService getSiteBotSlaveEventService() {
        return siteBotSlaveEventService;
    }

    public static void publishSlaveEvent(SlaveEvent event) {
        getEventService().publishAsync(event);
        synchronized (siteBotSlaveEventLock) {
            if (!siteBotSlaveEventDeliveryReady) {
                if (pendingSiteBotSlaveEvents.size() >= SITEBOT_SLAVE_EVENT_QUEUE_CAPACITY) {
                    SlaveEvent dropped = pendingSiteBotSlaveEvents.removeFirst();
                    logger.warn("SiteBot startup slave event queue full; dropping oldest {} event for {}",
                            dropped.getCommand(), dropped.getRSlave().getName());
                }
                pendingSiteBotSlaveEvents.addLast(event);
                return;
            }
            getSiteBotSlaveEventService().publishAsync(event);
        }
    }

    public static void prepareSiteBotSlaveEventDelivery(int expectedConsumers) {
        if (expectedConsumers < 1) {
            throw new IllegalArgumentException("expectedConsumers must be positive");
        }
        synchronized (siteBotSlaveEventLock) {
            siteBotSlaveEventDeliveryReady = false;
            expectedSiteBotSlaveEventConsumers = expectedConsumers;
            siteBotSlaveEventConsumers.clear();
        }
    }

    public static void registerSiteBotSlaveEventConsumer(Consumer<SlaveEvent> queuedEventConsumer) {
        Objects.requireNonNull(queuedEventConsumer, "queuedEventConsumer");
        synchronized (siteBotSlaveEventLock) {
            if (siteBotSlaveEventDeliveryReady) {
                return;
            }
            siteBotSlaveEventConsumers.add(queuedEventConsumer);
            if (siteBotSlaveEventConsumers.size() < expectedSiteBotSlaveEventConsumers) {
                logger.info("SiteBot slave event consumer registered ({}/{}); waiting before replay",
                        siteBotSlaveEventConsumers.size(), expectedSiteBotSlaveEventConsumers);
                return;
            }
            logger.info("SiteBot slave event delivery ready with {} consumer(s); replaying {} queued event(s)",
                    siteBotSlaveEventConsumers.size(), pendingSiteBotSlaveEvents.size());
            for (SlaveEvent event : pendingSiteBotSlaveEvents) {
                for (Consumer<SlaveEvent> consumer : siteBotSlaveEventConsumers) {
                    try {
                        consumer.accept(event);
                    } catch (RuntimeException e) {
                        logger.error("SiteBot consumer failed while replaying {} event for {}",
                                event.getCommand(), event.getRSlave().getName(), e);
                    }
                }
            }
            pendingSiteBotSlaveEvents.clear();
            siteBotSlaveEventConsumers.clear();
            siteBotSlaveEventDeliveryReady = true;
        }
    }

    public static void pauseSiteBotSlaveEventDelivery() {
        synchronized (siteBotSlaveEventLock) {
            siteBotSlaveEventDeliveryReady = false;
            expectedSiteBotSlaveEventConsumers = 1;
            siteBotSlaveEventConsumers.clear();
        }
    }

    public void reloadFtpConfig() {
        _config.reload();
    }

    private void loadSlaveSelectionManager(Properties cfg) {
        String desiredSL = PropertyHelper.getProperty(cfg, "slaveselection");
        try {
            Class<?> aClass = Class.forName(desiredSL);
            _slaveSelectionManager = (SlaveSelectionManagerInterface) aClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new FatalException("Unable to load the slaveselection plugin, check config.", e);
        }
    }

    public List<PluginInterface> getPlugins() {
        return new ArrayList<>(_plugins);
    }

    public SectionManagerInterface getSectionManager() {
        if (_sectionManager == null) {
            throw new NullPointerException();
        }

        return _sectionManager;
    }

    public String getShutdownMessage() {
        return _shutdownMessage;
    }

    public SlaveManager getSlaveManager() {
        if (_slaveManager == null) {
            throw new NullPointerException();
        }

        return _slaveManager;
    }

    public IndexEngineInterface getIndexEngine() {
        return _indexEngine;
    }

    public UserManager getUserManager() {
        if (_usermanager == null) {
            throw new NullPointerException();
        }

        return _usermanager;
    }

    public boolean isShutdown() {
        return _shutdownMessage != null;
    }

    public CommandManagerInterface createCommandManager() {
        Properties cfg = GlobalContext.getConfig().getMainProperties();
        String desiredCm = PropertyHelper.getProperty(cfg, "commandmanager");
        try {
            Class<?> aClass = Class.forName(desiredCm);
            return (CommandManagerInterface) aClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new FatalException(
                    "Cannot create instance of commandmanager, check 'commandmanager' in the configuration file",
                    e);
        }
    }

    private void loadPlugins() {
        Set<Class<? extends PluginInterface>> plugins = new Reflections("org.drftpd").getSubTypesOf(PluginInterface.class);
        logger.debug("We have found [{}] PluginInterface SubTypes", plugins.size());
        List<String> alreadyResolved = new ArrayList<>();
        try {
            boolean allResolve = false;
            while (!allResolve) {
                for (Class<? extends PluginInterface> plugin : plugins) {
                    PluginDependencies annotation = plugin.getAnnotation(PluginDependencies.class);
                    List<Class<? extends PluginInterface>> dependencies = annotation != null ?
                            Arrays.asList(annotation.refs()) : new ArrayList<>();
                    List<String> depNames = dependencies.stream().map(Class::getName).collect(Collectors.toList());
                    boolean alreadyInstantiate = alreadyResolved.contains(plugin.getName());
                    if (alreadyResolved.containsAll(depNames) && !alreadyInstantiate) {
                        PluginInterface pluginInterface = plugin.getConstructor().newInstance();
                        pluginInterface.startPlugin();
                        _plugins.add(pluginInterface);
                        logger.info("Loaded plugin {}", plugin.getName());
                        alreadyResolved.add(plugin.getName());
                    }
                    if (plugins.size() == _plugins.size()) {
                        allResolve = true;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load plugins for master extension point 'Plugin', possibly the master extension " +
                    "point definition has changed in the plugin.xml", e);
        }
    }

    private void loadSectionManager(Properties cfg) {
        String desiredSm = PropertyHelper.getProperty(cfg, "sectionmanager");
        try {
            Class<?> aClass = Class.forName(desiredSm);
            _sectionManager = (SectionManagerInterface) aClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new FatalException("Cannot create instance of SectionManager, check 'sectionmanager' in config file", e);
        }
    }

    private void loadIndexingEngine(Properties cfg) {
        String desiredIe = PropertyHelper.getProperty(cfg, "indexingengine");
        try {
            Class<?> aClass = Class.forName(desiredIe);
            _indexEngine = (IndexEngineInterface) aClass.getConstructor().newInstance();
            _indexEngine.init();
        } catch (Exception e) {
            throw new FatalException("Cannot create instance of IndexingEngine, check 'indexingengine' in config file", e);
        }
    }

    /**
     * Depends on root loaded if any slaves connect early.
     */
    private void loadSlaveManager() throws SlaveFileException {
        // register slavemanager
        _slaveManager = new SlaveManager();
    }

    private void listenForSlaves() {
        new Thread(_slaveManager, "Listening for slave connections - " + _slaveManager.toString()).start();
    }

    protected void loadUserManager(Properties cfg) {
        String desiredUm = PropertyHelper.getProperty(cfg, "usermanager");
        try {
            Class<?> aClass = Class.forName(desiredUm);
            _usermanager = (AbstractUserManager) aClass.getConstructor().newInstance();
            _usermanager.init();
        } catch (Exception e) {
            throw new FatalException("Cannot create instance of usermanager, check 'usermanager' in the configuration file", e);
        }
    }

    /**
     * Doesn't close connections like ConnectionManager.close() does
     * ConnectionManager.close() calls this method.
     */
    public void shutdown(String message) {
        _shutdownMessage = message;
        CommitManager.getCommitManager().enableQueueDrain();
        getEventService().publish(new MessageEvent("SHUTDOWN", message));
        getEventServiceSiteBotPriority().publish(new MessageEvent("SHUTDOWN", message));
        getEventServiceSlowest().publish(new MessageEvent("SHUTDOWN", message));
        shutdownTimers();
        getConnectionManager().shutdownPrivate(message);
        new Thread(new Shutdown()).start();
    }

    public Timer getTimer() {
        return _timer;
    }

    public void reloadTimer() {
        _timer.purge();
        _timer.cancel();
        _timer = new Timer("GlobalContextTimer");
    }

    public void scheduleTimer(String name, String owner, Runnable task, long delay, long period) {
        scheduleTimer(name, owner, task, delay, period, false);
    }

    public void scheduleTimerAtFixedRate(String name, String owner, Runnable task, long delay, long period) {
        scheduleTimer(name, owner, task, delay, period, true);
    }

    private synchronized void scheduleTimer(String name, String owner, Runnable task, long delay, long period,
                                            boolean fixedRate) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Timer name cannot be blank");
        }
        if (delay < 0 || period < 0) {
            throw new IllegalArgumentException("Timer delay and period cannot be negative");
        }

        cancelTimer(name);
        RegisteredTimer registration = new RegisteredTimer(name, owner, delay, period, fixedRate);
        _registeredTimers.put(name, registration);
        Runnable guardedTask = () -> runRegisteredTimer(registration, task);
        try {
            ScheduledFuture<?> future;
            if (period == 0) {
                future = _scheduledTimerExecutor.schedule(guardedTask, delay, TimeUnit.MILLISECONDS);
            } else if (fixedRate) {
                future = _scheduledTimerExecutor.scheduleAtFixedRate(
                        guardedTask, delay, period, TimeUnit.MILLISECONDS);
            } else {
                future = _scheduledTimerExecutor.scheduleWithFixedDelay(
                        guardedTask, delay, period, TimeUnit.MILLISECONDS);
            }
            registration.setFuture(future);
            logger.info("Registered timer [{}] owner=[{}] delay={}ms period={}ms fixedRate={}",
                    name, owner, delay, period, fixedRate);
        } catch (RuntimeException e) {
            _registeredTimers.remove(name, registration);
            logger.error("Unable to register timer [{}] for owner [{}]", name, owner, e);
            throw e;
        }
    }

    private void runRegisteredTimer(RegisteredTimer registration, Runnable task) {
        registration.setRunning(true);
        registration.setLastRun(System.currentTimeMillis());
        try {
            task.run();
            registration.setLastError(null);
        } catch (Throwable t) {
            registration.setLastError(t.toString());
            logger.error("Registered timer [{}] owned by [{}] failed", registration.getName(),
                    registration.getOwner(), t);
        } finally {
            registration.setRunning(false);
            if (registration.getPeriod() == 0) {
                registration.setEnabled(false);
                _registeredTimers.remove(registration.getName(), registration);
            }
        }
    }

    public synchronized boolean cancelTimer(String name) {
        RegisteredTimer registration = _registeredTimers.remove(name);
        if (registration == null) {
            return false;
        }
        registration.cancel();
        logger.info("Cancelled timer [{}] owned by [{}]", name, registration.getOwner());
        return true;
    }

    public List<TimerStatus> getTimerStatuses() {
        List<TimerStatus> statuses = new ArrayList<>();
        for (RegisteredTimer registration : _registeredTimers.values()) {
            statuses.add(registration.snapshot());
        }
        statuses.sort(Comparator.comparing(TimerStatus::getName));
        return statuses;
    }

    private synchronized void shutdownTimers() {
        for (RegisteredTimer registration : _registeredTimers.values()) {
            registration.cancel();
        }
        _registeredTimers.clear();
        _scheduledTimerExecutor.shutdownNow();
        _timer.cancel();
    }

    private static class RegisteredTimer {
        private final String _name;
        private final String _owner;
        private final long _delay;
        private final long _period;
        private final boolean _fixedRate;
        private volatile long _lastRun;
        private volatile String _lastError;
        private volatile boolean _enabled = true;
        private volatile boolean _running;
        private volatile ScheduledFuture<?> _future;

        private RegisteredTimer(String name, String owner, long delay, long period, boolean fixedRate) {
            _name = name;
            _owner = owner;
            _delay = delay;
            _period = period;
            _fixedRate = fixedRate;
        }

        private void cancel() {
            _enabled = false;
            ScheduledFuture<?> future = _future;
            if (future != null) {
                future.cancel(false);
            }
        }

        private TimerStatus snapshot() {
            return new TimerStatus(_name, _owner, _delay, _period, _fixedRate, _lastRun, _lastError,
                    _enabled, _running);
        }

        private String getName() { return _name; }
        private String getOwner() { return _owner; }
        private long getPeriod() { return _period; }
        private void setFuture(ScheduledFuture<?> future) { _future = future; }
        private void setLastRun(long lastRun) { _lastRun = lastRun; }
        private void setLastError(String lastError) { _lastError = lastError; }
        private void setEnabled(boolean enabled) { _enabled = enabled; }
        private void setRunning(boolean running) { _running = running; }
    }

    public static final class TimerStatus {
        private final String _name;
        private final String _owner;
        private final long _delay;
        private final long _period;
        private final boolean _fixedRate;
        private final long _lastRun;
        private final String _lastError;
        private final boolean _enabled;
        private final boolean _running;

        private TimerStatus(String name, String owner, long delay, long period, boolean fixedRate, long lastRun,
                            String lastError, boolean enabled, boolean running) {
            _name = name;
            _owner = owner;
            _delay = delay;
            _period = period;
            _fixedRate = fixedRate;
            _lastRun = lastRun;
            _lastError = lastError;
            _enabled = enabled;
            _running = running;
        }

        public String getName() { return _name; }
        public String getOwner() { return _owner; }
        public long getDelay() { return _delay; }
        public long getPeriod() { return _period; }
        public boolean isFixedRate() { return _fixedRate; }
        public long getLastRun() { return _lastRun; }
        public String getLastError() { return _lastError; }
        public boolean isEnabled() { return _enabled; }
        public boolean isRunning() { return _running; }
    }

    public SlaveSelectionManagerInterface getSlaveSelectionManager() {
        return _slaveSelectionManager;
    }

    public void addTimeEvent(TimeEventInterface timeEvent) {
        _timeManager.addTimeEvent(timeEvent);
    }

    public void removeTimeEvent(TimeEventInterface timeEvent) {
        _timeManager.removeTimeEvent(timeEvent);
    }

    public void replaceTimeEvents(Class<?> eventType,
                                  Collection<? extends TimeEventInterface> replacements) {
        _timeManager.replaceTimeEvents(eventType, replacements);
    }

    public PortRange getPortRange() {
        return getConfig().getPortRange();
    }

    public DirectoryHandle getRoot() {
        return root;
    }

    public void init() {
        _config = new ConfigManager();
        _config.reload();
        eventService2.configure(getConfig().getMainProperties(),
                "event.release.core.threads", "event.release.max.threads");

        CommitManager.getCommitManager().start();
        _timeManager = new TimeManager();
        loadUserManager(getConfig().getMainProperties());
        addTimeEvent(getUserManager());

        try {
            _sslContext = SSLGetContext.getSSLContext();
        } catch (IOException e) {
            logger.warn("Couldn't load SSLContext, SSL/TLS disabled - {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("Couldn't load SSLContext, SSL/TLS disabled", e);
        }

        try {
            loadSlaveManager();
        } catch (SlaveFileException e) {
            throw new RuntimeException(e);
        }
        listenForSlaves();
        loadSlaveSelectionManager(getConfig().getMainProperties());
        loadSectionManager(getConfig().getMainProperties());
        loadIndexingEngine(getConfig().getMainProperties());
        loadPlugins();

        // Subscribe to events
        AnnotationProcessor.process(this);
    }

    public SSLContext getSSLContext() {
        return _sslContext;
    }

    static class Shutdown implements Runnable {

        public void run() {
            Thread.currentThread().setName("Shutdown Thread");
            while (GlobalContext.getConnectionManager().getConnections().size() > 0) {
                logger.info("Waiting for connections to be shutdown...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            while (GlobalContext.getEventService().getQueueSize() > 0) {
                logger.info("Waiting for queued events to be processed 1 - {} remaining", GlobalContext.getEventService().getQueueSize());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            while (GlobalContext.getEventServiceSiteBotPriority().getQueueSize() > 0) {
                logger.info("Waiting for queued events to be processed 2 - {} remaining", GlobalContext.getEventServiceSiteBotPriority().getQueueSize());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            while (GlobalContext.getEventServiceSiteBotFunctional().getQueueSize() > 0) {
                logger.info("Waiting for functional SiteBot events to be processed - {} remaining",
                        GlobalContext.getEventServiceSiteBotFunctional().getQueueSize());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            while (GlobalContext.getEventServiceSlowest().getQueueSize() > 0) {
                logger.info("Waiting for queued events to be processed 3 - {} remaining", GlobalContext.getEventServiceSlowest().getQueueSize());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            while (GlobalContext.getSiteBotSlaveEventService().getQueueSize() > 0) {
                logger.info("Waiting for SiteBot slave events to be processed - {} remaining",
                        GlobalContext.getSiteBotSlaveEventService().getQueueSize());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            while (CommitManager.getCommitManager().getQueueSize() > 0) {
                logger.info("Waiting for queued commits to be drained - {} remaining", CommitManager.getCommitManager().getQueueSize());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            logger.info("Shutdown complete, exiting");
            System.exit(0);
        }
    }
}
