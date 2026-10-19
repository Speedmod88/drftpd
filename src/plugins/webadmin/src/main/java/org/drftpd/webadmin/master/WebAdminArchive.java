package org.drftpd.webadmin.master;

import org.drftpd.archive.master.Archive;
import org.drftpd.archive.master.archivetypes.ArchiveType;
import org.drftpd.master.GlobalContext;
import java.io.IOException;
import java.util.*;

final class WebAdminArchive {
    record Rule(int id, String section, String destination, String template, boolean previewOnly) { }
    private static Archive plugin() throws IOException {
        for (var plugin : GlobalContext.getGlobalContext().getPlugins())
            if (plugin instanceof Archive archive) return archive;
        throw new IOException("Archive plugin is not loaded");
    }
    static List<Rule> rules() throws IOException {
        return rules(plugin().getProperties());
    }
    static List<Rule> rules(Properties props) {
        List<Rule> rules = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.matches("[0-9]+\\.type")) continue;
            int id = Integer.parseInt(key.substring(0, key.indexOf('.')));
            if (!props.getProperty(key).trim().equals("MoveReleaseToSpecificFolder")
                    || !Boolean.parseBoolean(props.getProperty(id + ".recursive", "false").trim())) continue;
            rules.add(new Rule(id, props.getProperty(id + ".section", "").trim(),
                    props.getProperty(id + ".todirectory", "").trim(),
                    props.getProperty(id + ".todirectorytype", "").trim(),
                    Boolean.parseBoolean(props.getProperty(id + ".preview", "true").trim())));
        }
        rules.sort(Comparator.comparingInt(Rule::id));
        return rules;
    }
    static List<ArchiveType.ReorganizationRow> preview(String username, int id, int limit) throws Exception {
        var user = WebAdminDupe.user(username);
        Archive archive = plugin();
        Properties props = new Properties();
        props.putAll(archive.getProperties());
        Rule rule = rules(props).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Recursive archive rule not found"));
        var section = GlobalContext.getGlobalContext().getSectionManager().getSection(rule.section());
        if (section.getName().isEmpty()) throw new IllegalArgumentException("Archive section not found");
        WebAdminDupe.directory(user, section.getBaseDirectory().getPath());
        ArchiveType type = archive.getArchiveType(id, "MoveReleaseToSpecificFolder", section, props);
        if (type == null) throw new IOException("Unable to load archive rule");
        return type.previewReorganization(limit, user);
    }
}
