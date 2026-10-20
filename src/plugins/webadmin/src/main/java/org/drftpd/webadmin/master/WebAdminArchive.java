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
            rules.add(new Rule(id, props.getProperty(id + ".section", "").trim(),
                    props.getProperty(id + ".todirectory", "").trim(),
                    props.getProperty(id + ".todirectorytype", "").trim(),
                    Boolean.parseBoolean(props.getProperty(id + ".preview", "true").trim())));
        }
        rules.sort(Comparator.comparingInt(Rule::id));
        return rules;
    }
    static List<ArchiveType.ReorganizationRow> preview(String username, int id, int limit) throws Exception {
        return preview(username, id, limit, null, null, null);
    }

    static Properties previewProperties(Properties original, int id, String section, String destination, String template) {
        Properties props = new Properties();
        props.putAll(original);
        if (id < 1 || props.getProperty(id + ".type") == null) throw new IllegalArgumentException("Archive rule not found");
        if (section != null && !section.isBlank()) props.setProperty(id + ".section", section.trim());
        if (destination != null) props.setProperty(id + ".todirectory", destination.trim());
        if (template != null) props.setProperty(id + ".todirectorytype", template.trim());
        if (props.getProperty(id + ".section", "").isBlank() || props.getProperty(id + ".todirectory", "").isBlank())
            throw new IllegalArgumentException("Choose a source section and destination for folder preview");
        // Preview directory organization only: never schedule or change the saved rule.
        props.setProperty(id + ".type", "MoveReleaseToSpecificFolder");
        props.setProperty(id + ".recursive", "true");
        props.setProperty(id + ".preview", "true");
        return props;
    }

    static List<ArchiveType.ReorganizationRow> preview(String username, int id, int limit,
            String sectionOverride, String destination, String template) throws Exception {
        var user = WebAdminDupe.user(username);
        Archive archive = plugin();
        Properties original = archive.getProperties();
        if (id == 0) {
            original = new Properties();
            id = 1;
            original.setProperty("1.type", "MoveReleaseToSpecificFolder");
        }
        final int ruleId = id;
        Properties props = previewProperties(original, id, sectionOverride, destination, template);
        Rule rule = rules(props).stream().filter(item -> item.id() == ruleId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Recursive archive rule not found"));
        var section = GlobalContext.getGlobalContext().getSectionManager().getSection(rule.section());
        if (section.getName().isEmpty()) throw new IllegalArgumentException("Archive section not found");
        WebAdminDupe.directory(user, section.getBaseDirectory().getPath());
        ArchiveType type = archive.getArchiveType(ruleId, "MoveReleaseToSpecificFolder", section, props);
        if (type == null) throw new IOException("Unable to load archive rule");
        return type.previewReorganization(limit, user);
    }
}
