package org.drftpd.master.slavemanagement;

import org.drftpd.common.slave.DiskStatus;
import org.drftpd.master.commands.slavemanagement.SlaveManagement;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class UsableSlaveStatusTest {
    private SlaveStatus status(DiskStatus disk) {
        return new SlaveStatus(disk, 0, 0, 0, 0, 0, 0);
    }

    @Test void siteTotalsPreserveRawFreeButSumUsableSpaceAcrossMixedSlaves() {
        SlaveStatus full = status(new DiskStatus(100, 1000, true, "reserve", 0L));
        SlaveStatus partial = status(new DiskStatus(500, 2000, false, "reserve", 200L));
        SlaveStatus legacy = status(new DiskStatus(300, 1000));
        SlaveStatus total = new SlaveStatus().append(full).append(partial).append(legacy);
        assertEquals(900, total.getDiskSpaceAvailable());
        assertEquals(500, total.getDiskSpaceUsable());
        assertEquals(4000, total.getDiskSpaceCapacity());
        assertEquals(3100, total.getDiskSpaceUsed());
        assertFalse(total.isDiskFull());
        assertTrue(full.isDiskFull());
        assertFalse(new SlaveStatus().isDiskFull());
    }

    @Test void commandDisplayShowsFullAndZeroWhileKeepingPhysicalFreeAccessible() {
        SlaveStatus full = status(new DiskStatus(200, 1000, true, "reserve", 0L));
        var env = new HashMap<String, Object>();
        SlaveManagement.fillEnvWithUsableDiskSpace(env, full);
        assertEquals("0B", env.get("diskfree"));
        assertEquals("FULL", env.get("diskfull"));
        assertEquals("200B", env.get("diskfreephysical"));
        assertEquals("200B", env.get("diskreserved"));
        assertEquals("0%", env.get("diskfreepercent"));
        assertEquals(200, full.getDiskSpaceAvailable());
    }

    @Test void newFieldsAreBoundedAndOldThresholdOnlyReportsFallbackSafely() {
        assertEquals(100, new DiskStatus(100, 1000, false, null, 200L).getBytesUsable());
        assertEquals(0, new DiskStatus(100, 1000, false, null, -1L).getBytesUsable());
        assertEquals(0, new DiskStatus(100, 1000, true, "full").getBytesUsable());
        assertEquals(100, new DiskStatus(100, 1000).getBytesUsable());
    }
}
