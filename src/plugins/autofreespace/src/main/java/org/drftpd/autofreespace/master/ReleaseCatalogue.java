package org.drftpd.autofreespace.master;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.drftpd.master.indexation.IndexDataExtensionInterface;
import org.drftpd.master.vfs.event.ImmutableInodeHandle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Persistent derived fields in userdata/index; no slave I/O on the indexing thread. */
public class ReleaseCatalogue implements IndexDataExtensionInterface {
    static final String KEY = "releaseKey", SCHEMA = "releaseSchema", TAGS = "releaseTags";
    private static volatile String markerRegex = AutoFreeSpaceSettings.DEFAULT_DUPE_MARKER_REGEX;
    private final Field key = new Field(KEY, "", Field.Store.YES, Field.Index.NOT_ANALYZED);
    private final Field schema = new Field(SCHEMA, "", Field.Store.YES, Field.Index.NOT_ANALYZED);
    private final Field tags = new Field(TAGS, "", Field.Store.YES, Field.Index.ANALYZED);
    private String lastRegex;
    private String version;
    private Pattern markerPattern;

    public void initializeFields(Document doc) { doc.add(key); doc.add(schema); doc.add(tags); }

    public void addData(Document doc, ImmutableInodeHandle inode) {
        key.setValue(""); schema.setValue(""); tags.setValue("");
        if (!inode.isDirectory()) return;
        // Do not initialize plugin settings (and SectionManager) from the indexing thread.
        String regex = markerRegex;
        if (!regex.equals(lastRegex)) {
            version = version(regex);
            markerPattern = compile(regex);
            lastRegex = regex;
        }
        String identity = identity(inode.getName(), markerPattern);
        key.setValue(identity == null ? "" : identity);
        schema.setValue(version);
        tags.setValue(inode.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " "));
    }

    static String identity(String name, String regex) {
        return identity(name, compile(regex));
    }

    private static Pattern compile(String regex) {
        try { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE); }
        catch (PatternSyntaxException ignored) { return null; }
    }

    private static String identity(String name, Pattern pattern) {
        String title = name;
        if (pattern != null) {
            var matcher = pattern.matcher(name);
            if (matcher.find()) title = name.substring(0, matcher.start());
        }
        String normalized = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "");
        return normalized.isEmpty() ? null : normalized;
    }

    static void configure(String regex) { markerRegex = regex; }

    static String version(String regex) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("release-key-v1:" + regex).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
