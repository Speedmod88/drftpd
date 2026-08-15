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
package org.drftpd.master.cron;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.config.ConfigInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author zubov
 * @version $Id$
 */
public class TimeManager {

    private static final Logger logger = LogManager.getLogger(TimeManager.class);

    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = MINUTE * 60;
    private static final long WATCHDOG_PERIOD = MINUTE * 5;
    private static final long WATCHDOG_GRACE = MINUTE * 10;

    private final ArrayList<TimeEventInterface> _timedEvents;
    private final Object _timerLock = new Object();
    private final ScheduledExecutorService _watchdog;

    private TimerTask _processHour;
    private volatile long _lastCompletedReset = System.currentTimeMillis();
    private volatile long _nextExpectedReset = -1;

    public TimeManager() {
        this(Calendar.getInstance());
    }

    public TimeManager(Calendar cal) {
        _timedEvents = new ArrayList<>();
        _watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "TimeManagerWatchdog");
            thread.setDaemon(true);
            return thread;
        });
        // setup the next time we need to run an event
        // roll the calendar to the next Hour
        cal.add(Calendar.HOUR, 1);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        scheduleProcessHour(cal);
        startWatchdog();
    }

    private TimerTask newProcessHourTask() {
        return new TimerTask() {
            public void run() {
                try {
                    doReset(Calendar.getInstance());
                } catch (Throwable t) {
                    logger.error("TimeManager hourly reset failed", t);
                } finally {
                    _lastCompletedReset = System.currentTimeMillis();
                    _nextExpectedReset = nextTopOfHour(_lastCompletedReset);
                }
            }
        };
    }

    private void scheduleProcessHour(Calendar cal) {
        synchronized (_timerLock) {
            _processHour = newProcessHourTask();
            Timer timer = GlobalContext.getGlobalContext().getTimer();
            Date nextReset = cal.getTime();
            _nextExpectedReset = nextReset.getTime();
            try {
                timer.scheduleAtFixedRate(_processHour, nextReset, HOUR);
                logger.info("TimeManager scheduled the next reset to be at {}", nextReset);
            } catch (IllegalStateException e) {
                logger.error("TimeManager schedule error", e);
                GlobalContext.getGlobalContext().reloadTimer();
                timer = GlobalContext.getGlobalContext().getTimer();
                _processHour = newProcessHourTask();
                try {
                    timer.scheduleAtFixedRate(_processHour, nextReset, HOUR);
                    logger.info("TimeManager scheduled the next reset to be at {}", nextReset);
                } catch (IllegalStateException e2) {
                    logger.error("TimeManager schedule error 2", e2);
                }
            }
        }
    }

    private void startWatchdog() {
        try {
            _watchdog.scheduleWithFixedDelay(this::watchdogCheck, WATCHDOG_PERIOD, WATCHDOG_PERIOD, TimeUnit.MILLISECONDS);
            logger.info("TimeManager watchdog scheduled every {} minutes", WATCHDOG_PERIOD / MINUTE);
        } catch (RuntimeException e) {
            logger.error("TimeManager watchdog schedule error", e);
        }
    }

    private void watchdogCheck() {
        try {
            long now = System.currentTimeMillis();
            long nextExpected = _nextExpectedReset;
            if (nextExpected > 0 && now > nextExpected + WATCHDOG_GRACE) {
                logger.warn("TimeManager watchdog detected stale hourly reset; lastCompleted={}, expected={}, now={}. Reloading timer and rescheduling.",
                        new Date(_lastCompletedReset), new Date(nextExpected), new Date(now));
                GlobalContext.getGlobalContext().reloadTimer();
                Calendar next = Calendar.getInstance();
                next.setTimeInMillis(now);
                next.add(Calendar.HOUR, 1);
                next.set(Calendar.MINUTE, 0);
                next.set(Calendar.SECOND, 0);
                next.set(Calendar.MILLISECOND, 0);
                scheduleProcessHour(next);
            }
        } catch (Throwable t) {
            logger.error("TimeManager watchdog failed", t);
        }
    }

    private long nextTopOfHour(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        cal.add(Calendar.HOUR, 1);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public void doReset(Calendar cal) {
        logger.debug("doReset called - {}", cal.toString());
        // Check if EuropeanCalendar and change if needed
        if (isEuropeanCalendar()) {
            cal.setFirstDayOfWeek(Calendar.MONDAY);
        }

        int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        int hourOfDay = cal.get(Calendar.HOUR_OF_DAY);
        int minuteOfHour = cal.get(Calendar.MINUTE);
        int monthOfYear = cal.get(Calendar.MONTH);

        if (minuteOfHour != 0) {
            logger.warn("TimeManager reset called outside the first minute of the hour, normalizing time from {}", cal.getTime());
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
        }

        if (hourOfDay == 0) {
            // we have started a new day
            if (dayOfWeek == cal.getFirstDayOfWeek()) {
                // we have started a new week
                doMethodOnTimeEvents("resetWeek", cal.getTime());
            }
            if (dayOfMonth == 1) {
                // we have started a new month
                if (monthOfYear == 0) { // january is the 0 month, I dunno...
                    doMethodOnTimeEvents("resetYear", cal.getTime());
                    return;
                }
                doMethodOnTimeEvents("resetMonth", cal.getTime());
                return;
            }
            doMethodOnTimeEvents("resetDay", cal.getTime());
            return;
        }
        doMethodOnTimeEvents("resetHour", cal.getTime());
    }

    private void doMethodOnTimeEvents(String methodName, Date d) {
        List<TimeEventInterface> tempList;
        synchronized (this) {
            tempList = new ArrayList<>(_timedEvents);
        }
        Class<?>[] classArg = new Class<?>[1];
        classArg[0] = Date.class;
        Date[] dateArg = new Date[1];
        dateArg[0] = d;
        for (TimeEventInterface event : tempList) {
            try {
                Method m = TimeEventInterface.class.getDeclaredMethod(methodName,
                        classArg);
                m.invoke(event, d);
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                logger.error("{} does not properly implement TimeEventInterface", event.getClass().getName(), e);
            } catch (RuntimeException e) {
                logger.error("{} had an error processing {}", event.getClass().getName(), methodName, e);
            }
        }
    }

    /*
     * Checks conf file to see if european calendar is being used.
     */
    public static boolean isEuropeanCalendar() {
        ConfigInterface config = GlobalContext.getConfig();
        // Complain about this situation as it should not happen!
        if (config == null) {
            logger.error("Config from GlobalContext is null, this should not be possible!");
            return false;
        }
        return config.getMainProperties().getProperty("european.cal", "false").equalsIgnoreCase("true");
    }

    public void processTimeEventsSinceDate(Date date) {
        processTimeEventsBetweenDates(date, new Date(System.currentTimeMillis()));
    }

    /**
     * Should be called on startup after the appropriate TimeEventInterfaces have been added
     *
     * @param oldDate The start date
     * @param newDate The end date
     */
    public void processTimeEventsBetweenDates(Date oldDate, Date newDate) {
        Calendar oldCal = Calendar.getInstance();
        Calendar newCal = Calendar.getInstance();
        oldCal.setTime(oldDate);
        newCal.setTime(newDate);
        while (true) {
            if (oldCal.after(newCal)) {
                return;
            }
            doReset(oldCal);
            oldCal.add(Calendar.HOUR, 1);
        }
    }

    public synchronized void addTimeEvent(TimeEventInterface event) {
        _timedEvents.add(event);
    }

    public synchronized void removeTimeEvent(TimeEventInterface event) {
        _timedEvents.remove(event);
    }
}
