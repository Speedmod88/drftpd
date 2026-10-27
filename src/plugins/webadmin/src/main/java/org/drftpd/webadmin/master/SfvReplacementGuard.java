package org.drftpd.webadmin.master;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict validation of proposed SFVs; no payload is changed by this check. */
final class SfvReplacementGuard {
    private static final Pattern ENTRY = Pattern.compile("^(.+?) +([0-9a-fA-F]{8})$");
    private static final Pattern VOLUME = Pattern.compile("(?i).+\\.(rar|[r-z][0-9]{2})$");
    interface Check { long checksum(String filename) throws Exception; }

    static Map<String, Long> parse(byte[] bytes) throws IOException {
        Map<String, Long> entries = new LinkedHashMap<>();
        for (String raw : new String(bytes, StandardCharsets.ISO_8859_1).split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";")) continue;
            var match = ENTRY.matcher(line);
            if (!match.matches()) throw new IOException("Malformed proposed SFV entry");
            String name = match.group(1);
            if (name.contains("/") || name.contains("\\") || name.contains(":")
                    || name.equals(".") || name.equals("..") || name.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IOException("Unsafe proposed SFV filename: " + name);
            String key = name.toLowerCase(Locale.ROOT);
            if (entries.putIfAbsent(key, Long.parseUnsignedLong(match.group(2), 16)) != null)
                throw new IOException("Duplicate proposed SFV filename: " + name);
        }
        if (entries.isEmpty()) throw new IOException("Proposed SFV has no entries");
        return entries;
    }

    static void verify(byte[] proposed, Collection<String> localNames, Check fresh) throws Exception {
        Map<String, Long> entries = parse(proposed);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String name : localNames) {
            if (actual.putIfAbsent(name.toLowerCase(Locale.ROOT), name) != null)
                throw new IOException("Ambiguous case-sensitive local filename: " + name);
            if (VOLUME.matcher(name).matches() && !entries.containsKey(name.toLowerCase(Locale.ROOT)))
                throw new IOException("Proposed SFV omits local RAR volume: " + name);
        }
        for (var entry : entries.entrySet()) {
            String filename = actual.get(entry.getKey());
            if (filename == null) throw new IOException("Proposed SFV references missing file: " + entry.getKey());
            if (fresh.checksum(filename) != entry.getValue())
                throw new IOException("Payload CRC differs from proposed SFV: " + filename);
        }
    }

    static org.drftpd.zipscript.common.sfv.SFVInfo metadata(byte[] proposed, String name, long crc) throws IOException {
        var entries = new org.drftpd.common.vfs.CaseInsensitiveTreeMap<String, Long>();
        entries.putAll(parse(proposed));
        var info = new org.drftpd.zipscript.common.sfv.SFVInfo();
        info.setEntries(entries);
        info.setSFVFileName(name);
        info.setChecksum(crc);
        return info;
    }
}
