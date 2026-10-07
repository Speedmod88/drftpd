package org.drftpd.mediainfo.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaInfoTest {
    @TempDir Path temporary;

    @Test void startupRequiresOnlyMediaInfo() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor()).thenReturn(0);
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            assertEquals(Arrays.asList("mediainfo", "--version"),
                    Arrays.asList((String[]) context.arguments().get(0)));
            when(builder.start()).thenReturn(process);
        })) {
            assertTrue(MediaInfo.hasWorkingMediaInfo());
            assertEquals(1, builders.constructed().size());
        }
    }

    @Test void missingMediaInfoStillFailsStartup() {
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) ->
                when(builder.start()).thenThrow(new IOException("not installed")))) {
            assertFalse(MediaInfo.hasWorkingMediaInfo());
            assertEquals(1, builders.constructed().size());
        }
    }

    @Test void failedMediaInfoVersionStillFailsStartup() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor()).thenReturn(1);
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) ->
                when(builder.start()).thenReturn(process))) {
            assertFalse(MediaInfo.hasWorkingMediaInfo());
            assertEquals(1, builders.constructed().size());
        }
    }

    private MediaInfo inspect(String output) throws Exception {
        Path sample = temporary.resolve("sample.mkv");
        Files.writeString(sample, "test sample");
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor()).thenReturn(0);
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            assertEquals(Arrays.asList("mediainfo", sample.toFile().getAbsolutePath()),
                    Arrays.asList((String[]) context.arguments().get(0)));
            when(builder.start()).thenReturn(process);
        })) {
            MediaInfo info = MediaInfo.getMediaInfoFromFile(sample.toFile());
            assertNotNull(info);
            assertEquals(1, builders.constructed().size(), "Must not invoke a second validator");
            assertTrue(Files.exists(sample), "Inspection must not add automatic deletion");
            return info;
        }
    }

    @Test void validMkvUsesOnlyMediaInfo() throws Exception {
        assertTrue(inspect("General\nFormat : Matroska\nConformance errors : 0\n").getSampleOk());
    }

    @Test void incompleteMkvUsesAllComplianceEntries() throws Exception {
        MediaInfo info = inspect("General\nFormat : Matroska\n"
                + "General compliance : File size is less than expected size at least 12345\n"
                + "General compliance : Another diagnostic\nVideo\nFormat : AVC\n");
        assertFalse(info.getSampleOk());
        assertEquals(12345L, info.getCalFileSize());
    }

    @Test void conformanceErrorsMarkMkvInvalid() throws Exception {
        assertFalse(inspect("General\nFormat : Matroska\nConformance errors : 1\n"
                + "Conformance errors\nDetail : Invalid structure\n").getSampleOk());
    }

    @Test void overflowingExpectedSizeDoesNotCrashInspection() throws Exception {
        assertFalse(inspect("General\nFormat : Matroska\nGeneral compliance : "
                + "File size is less than expected size at least 999999999999999999999999\n").getSampleOk());
    }
}
