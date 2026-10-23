package org.drftpd.master.sitebot.announce;

import org.drftpd.master.slavemanagement.RemoteSlave;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class SiteFullReminderTest {
    @Test void announcesInitiallyAndEverySixHoursAndRearmsAfterRecovery() {
        RemoteSlave slave = mock(RemoteSlave.class);
        when(slave.isAvailable()).thenReturn(true);
        when(slave.isUploadBlockedByCapacity()).thenReturn(true);
        Runnable output = mock(Runnable.class);
        SiteFullReminder reminder = new SiteFullReminder();
        reminder.check(List.of(slave), 0, output);
        reminder.check(List.of(slave), SiteFullReminder.INTERVAL - 1, output);
        verify(output, times(1)).run();
        reminder.check(List.of(slave), SiteFullReminder.INTERVAL, output);
        verify(output, times(2)).run();
        when(slave.isUploadBlockedByCapacity()).thenReturn(false);
        reminder.check(List.of(slave), SiteFullReminder.INTERVAL + 1, output);
        when(slave.isUploadBlockedByCapacity()).thenReturn(true);
        reminder.check(List.of(slave), SiteFullReminder.INTERVAL + 2, output);
        verify(output, times(3)).run();
    }

    @Test void emptyOfflineAndUnknownDoNotMeanSiteFull() {
        SiteFullReminder reminder = new SiteFullReminder();
        Runnable output = mock(Runnable.class);
        RemoteSlave full = mock(RemoteSlave.class);
        when(full.isAvailable()).thenReturn(true);
        when(full.isUploadBlockedByCapacity()).thenReturn(true);
        RemoteSlave unknown = mock(RemoteSlave.class);
        reminder.check(List.of(), 0, output);
        reminder.check(List.of(full, unknown), 1, output);
        when(unknown.isAvailable()).thenReturn(true);
        reminder.check(List.of(full, unknown), 2, output);
        verifyNoInteractions(output);
    }

    @Test void failedSendIsRetried() {
        RemoteSlave full = mock(RemoteSlave.class);
        when(full.isAvailable()).thenReturn(true);
        when(full.isUploadBlockedByCapacity()).thenReturn(true);
        Runnable output = mock(Runnable.class);
        doThrow(new IllegalStateException()).doNothing().when(output).run();
        SiteFullReminder reminder = new SiteFullReminder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> reminder.check(List.of(full), 0, output));
        reminder.check(List.of(full), 1, output);
        verify(output, times(2)).run();
    }
}
