package org.drftpd.webadmin.master;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfvReplacementGuardTest {
    private byte[] sfv(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }

    @Test void checksEveryPayloadAgainstProposedSfv() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        SfvReplacementGuard.verify(sfv("; comment\r\nFile.rar 12345678\r\nfile.r00 00000000\r\n"),
                List.of("File.rar", "file.r00"), name -> {
                    checks.incrementAndGet();
                    return name.equals("File.rar") ? 0x12345678L : 0;
                });
        assertEquals(2, checks.get());
    }

    @Test void mismatchBlocksReplacement() {
        assertThrows(IOException.class, () -> SfvReplacementGuard.verify(sfv("file.rar 12345678"),
                List.of("file.rar"), name -> 1));
    }

    @Test void missingFileBlocksReplacement() {
        assertThrows(IOException.class, () -> SfvReplacementGuard.verify(sfv("file.rar 12345678"),
                List.of(), name -> { fail("Missing files must not be checked"); return 0; }));
    }

    @Test void omittedRarVolumeBlocksReplacement() {
        assertThrows(IOException.class, () -> SfvReplacementGuard.verify(sfv("file.rar 12345678"),
                List.of("file.rar", "file.r00"), name -> 0x12345678L));
    }

    @Test void unsafeAndAmbiguousReferencesAreRefused() {
        for (String text : List.of("", "; comments only", "../file.rar 12345678",
                "/file.rar 12345678", "dir\\file.rar 12345678", "file.rar BAD",
                "file.rar 12345678\nFILE.RAR 12345678")) {
            assertThrows(IOException.class, () -> SfvReplacementGuard.parse(sfv(text)), text);
        }
    }

    @Test void checksumFailureIsNotTreatedAsMatch() {
        assertThrows(IOException.class, () -> SfvReplacementGuard.verify(sfv("file.rar 12345678"),
                List.of("file.rar"), name -> { throw new IOException("Offline"); }));
    }

    @Test void cachedSfvHasVerifiedEntriesAndReferenceChecksum() throws Exception {
        var info = SfvReplacementGuard.metadata(sfv("file.rar 12345678\nfile.r00 00000000"), "new.sfv", 42);
        assertEquals(2, info.getSize());
        assertEquals(0x12345678L, info.getEntries().get("FILE.RAR"));
        assertEquals(42, info.getChecksum());
        assertEquals("new.sfv", info.getSFVFileName());
    }
}
