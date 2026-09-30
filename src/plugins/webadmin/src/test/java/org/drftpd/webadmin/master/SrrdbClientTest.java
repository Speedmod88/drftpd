package org.drftpd.webadmin.master;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class SrrdbClientTest {
    static byte[] bytes(String content) { return content.getBytes(StandardCharsets.ISO_8859_1); }
    static SrrdbClient.RemoteFile file(String name, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return new SrrdbClient.RemoteFile(name, data.length, String.format("%08X", crc.getValue()));
    }
    static byte[] details(String release, SrrdbClient.RemoteFile file) {
        return bytes(new Gson().toJson(Map.of("name", release, "files",
                List.of(Map.of("name", file.name(), "size", file.size(), "crc", file.crc())))));
    }

    @Test void allowsOnlySafeMetadataPaths() {
        for (String name : List.of("release.nfo", "CD1/release.sfv", "Subs/RELEASE.SFV")) {
            assertTrue(SrrdbClient.metadataPath(name), name);
        }
        for (String name : List.of("../outside.sfv", "/absolute.nfo", "C:/drive.nfo",
                "a/../b.sfv", "a\\b.nfo", "a//b.nfo", "a/%2e%2e/b.sfv", "file.nfo:stream",
                "file.exe", "file.nfo ", "a/\nfile.nfo", "./file.nfo", "dir./file.nfo")) {
            assertFalse(SrrdbClient.metadataPath(name), name);
        }
    }
    @Test void requiresExactReleaseAndBoundedSize() throws Exception {
        byte[] data = bytes("file.rar AABBCCDD\n");
        var file = file("release.sfv", data);
        SrrdbClient client = new SrrdbClient(100, (uri, limit) -> details("Movie.2026-GRP", file));
        assertEquals(List.of(file), client.details("movie.2026-grp"));
        assertThrows(IOException.class, () -> new SrrdbClient(100,
                (uri, limit) -> details("Different.Movie-GRP", file)).details("Movie.2026-GRP"));
        assertTrue(new SrrdbClient(1, (uri, limit) -> details("Movie-GRP", file)).details("Movie-GRP").isEmpty());
    }
    @Test void validatesDownloadedBytesAndSfvStructure() throws Exception {
        byte[] data = bytes("; comment\nfile.rar AABBCCDD\n");
        var file = file("release.sfv", data);
        SrrdbClient.verify(file, data);
        assertThrows(IOException.class, () -> SrrdbClient.verify(file, bytes("truncated")));
        byte[] invalid = bytes("this is not an SFV");
        assertThrows(IOException.class, () -> SrrdbClient.verify(file("release.sfv", invalid), invalid));
        byte[] html = bytes("<html>access denied</html>");
        assertThrows(IOException.class, () -> SrrdbClient.verify(file("release.nfo", html), html));
        byte[] traversal = bytes("../other-release/file.rar AABBCCDD\n");
        assertThrows(IOException.class, () -> SrrdbClient.verify(file("release.sfv", traversal), traversal));
    }
    @Test void neverUsesAProvidedRemoteHost() throws Exception {
        byte[] data = bytes("valid nfo");
        var file = file("release.nfo", data);
        SrrdbClient client = new SrrdbClient(100, (uri, limit) -> {
            assertEquals("https", uri.getScheme());
            assertEquals("www.srrdb.com", uri.getHost());
            return data;
        });
        assertArrayEquals(data, client.download("Release#?&-GRP", file));
        assertNull(SrrdbClient.downloadUri("Release?foo", file.name()).getQuery());
        assertNull(SrrdbClient.downloadUri("Release#foo", file.name()).getFragment());
    }
}
