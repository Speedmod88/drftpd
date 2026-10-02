package org.drftpd.master.sitebot;

import org.drftpd.master.sitebot.config.AnnounceConfig;
import org.drftpd.master.sitebot.config.SiteBotConfig;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SiteBotRoutingStartupTest {
    private static void field(SiteBot bot, String name, Object value) throws Exception {
        Field field = SiteBot.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bot, value);
    }
    private static void call(SiteBot bot, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = SiteBot.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(bot, args);
    }

    @Test void channelsRefreshRoutingBeforeAnnouncersAreMarkedReady() throws Exception {
        SiteBot bot = spy(new SiteBot("unused/"));
        doReturn(true).when(bot).isConnected();
        SiteBotConfig config = mock(SiteBotConfig.class);
        when(config.getChannels()).thenReturn(new ArrayList<>());
        AnnounceConfig routes = mock(AnnounceConfig.class);
        AbstractAnnouncer announcer = mock(AbstractAnnouncer.class);
        field(bot, "_config", config);
        field(bot, "_announceConfig", routes);
        field(bot, "_announcers", new ArrayList<>(java.util.List.of(announcer)));
        field(bot, "_announcersLoaded", true);
        call(bot, "notifyAnnouncersConnected", new Class<?>[0]);
        verify(announcer, never()).onBotConnected();
        call(bot, "finishChannelJoin", new Class<?>[]{String.class}, "test");
        var order = inOrder(routes, announcer);
        order.verify(routes).reload();
        order.verify(announcer).onBotConnected();
        call(bot, "notifyAnnouncersConnected", new Class<?>[0]);
        verify(announcer, times(1)).onBotConnected();

        call(bot, "cancelDeferredChannelJoin", new Class<?>[0]);
        call(bot, "notifyAnnouncersConnected", new Class<?>[0]);
        verify(announcer, times(1)).onBotConnected();
        call(bot, "finishChannelJoin", new Class<?>[]{String.class}, "reconnect");
        verify(announcer, times(2)).onBotConnected();
    }

    @Test void channelSetupWaitsForAnnouncerInitializationMonitor() throws Exception {
        SiteBot bot = spy(new SiteBot("unused/"));
        doReturn(true).when(bot).isConnected();
        SiteBotConfig config = mock(SiteBotConfig.class);
        when(config.getChannels()).thenReturn(new ArrayList<>());
        field(bot, "_config", config);
        AnnounceConfig routes = mock(AnnounceConfig.class);
        CountDownLatch started = new CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread join;
        synchronized (bot) {
            join = new Thread(() -> {
                started.countDown();
                try { call(bot, "finishChannelJoin", new Class<?>[]{String.class}, "test"); }
                catch (Throwable e) { error.set(e); }
            });
            join.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            field(bot, "_announceConfig", routes);
        }
        join.join(3000);
        assertFalse(join.isAlive());
        assertNull(error.get());
        verify(routes).reload();
    }
}
