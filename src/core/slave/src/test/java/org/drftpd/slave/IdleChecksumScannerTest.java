package org.drftpd.slave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class IdleChecksumScannerTest {
    @TempDir Path temp;

    private long crc(byte[] data) { CRC32 crc = new CRC32(); crc.update(data); return crc.getValue(); }

    @Test void boundedStepsReturnWholeFileCrc() throws Exception {
        byte[] data = new byte[200000];
        new java.util.Random(1).nextBytes(data);
        Path file = Files.write(temp.resolve("data"), data);
        IdleChecksumScanner scanner = new IdleChecksumScanner();
        assertEquals(IdleChecksumScanner.MORE, scanner.step(file, data.length, 100000, () -> true, () -> 0));
        assertEquals(crc(data), scanner.step(file, data.length, 100000, () -> true, () -> 0));
        Files.delete(file); // No open descriptor is retained between steps.
    }

    @Test void busyDoesNotEvenOpenTarget() throws Exception {
        assertEquals(IdleChecksumScanner.BUSY, new IdleChecksumScanner().step(
                temp.resolve("not-there"), 100, 100, () -> false, () -> 0));
    }

    @Test void transferActivityRestartsPartialChecksum() throws Exception {
        byte[] data = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Path file = Files.write(temp.resolve("data"), data);
        AtomicLong activity = new AtomicLong();
        IdleChecksumScanner scanner = new IdleChecksumScanner();
        assertEquals(IdleChecksumScanner.MORE, scanner.step(file, 9, 5, () -> true, activity::get));
        activity.incrementAndGet();
        assertEquals(IdleChecksumScanner.MORE, scanner.step(file, 9, 5, () -> true, activity::get));
        assertEquals(0xcbf43926L, scanner.step(file, 9, 5, () -> true, activity::get));
    }

    @Test void changedSizeCannotProduceAResult() throws Exception {
        Path file = Files.write(temp.resolve("data"), new byte[10]);
        IdleChecksumScanner scanner = new IdleChecksumScanner();
        scanner.step(file, 10, 5, () -> true, () -> 0);
        Files.write(file, new byte[9]);
        assertThrows(java.io.IOException.class, () -> scanner.step(file, 10, 5, () -> true, () -> 0));
    }

    @Test void yieldsInsideStepWhenActivityStarts() throws Exception {
        Path file = Files.write(temp.resolve("data"), new byte[200000]);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(IdleChecksumScanner.BUSY, new IdleChecksumScanner().step(
                file, 200000, 200000, () -> checks.incrementAndGet() < 3, () -> 0));
        Files.delete(file);
    }

    @Test void legitimateZeroCrcIsNotABusySentinel() throws Exception {
        Path file = Files.write(temp.resolve("empty"), new byte[0]);
        assertEquals(0, new IdleChecksumScanner().step(file, 0, 1024, () -> true, () -> 0));
    }

    @Test void differentFileDoesNotReusePreviousPartialCrc() throws Exception {
        IdleChecksumScanner scanner = new IdleChecksumScanner();
        Path first = Files.write(temp.resolve("first"), new byte[20]);
        byte[] data = {1, 2, 3, 4};
        Path second = Files.write(temp.resolve("second"), data);
        scanner.step(first, 20, 5, () -> true, () -> 0);
        assertEquals(crc(data), scanner.step(second, 4, 5, () -> true, () -> 0));
    }
}
