package org.drftpd.zipscript.master.audit;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

record IntegrityConfig(boolean enabled, long idleMillis, long intervalMillis, long minimumAgeMillis,
                       int steps, int scanBatch, Set<String> slaves, List<String> paths, Pattern exclude,
                       boolean backfill, int backfillBatch) {
    static IntegrityConfig read(Properties p) {
        List<String> paths = Arrays.stream(p.getProperty("audit.paths", "/").split(","))
                .map(String::trim).map(path -> {
                    if (!path.startsWith("/") || path.contains("\\") || path.contains("..")) {
                        throw new IllegalArgumentException("audit.paths requires absolute VFS directories");
                    }
                    return path.equals("/") || path.endsWith("/") ? path : path + "/";
                }).toList();
        String names = p.getProperty("audit.slaves", "").trim();
        return new IntegrityConfig(Boolean.parseBoolean(p.getProperty("audit.enabled", "false")),
                number(p, "audit.idle.seconds", 300, 1, 86400) * 1000L,
                number(p, "audit.interval.days", 90, 1, 3650) * 86400000L,
                number(p, "audit.minimum.age.minutes", 60, 1, 525600) * 60000L,
                number(p, "audit.steps.per.tick", 8, 1, 32),
                number(p, "audit.scan.batch", 32, 1, 256),
                names.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(names.split("\\s+"))), paths,
                Pattern.compile(p.getProperty("audit.exclude.regex", "(?i)^/PRE(?:/|$)")),
                Boolean.parseBoolean(p.getProperty("catalogue.backfill.enabled", "true")),
                number(p, "catalogue.backfill.batch", 32, 1, 256));
    }
    boolean includes(String path) {
        return paths.stream().anyMatch(path::startsWith) && !exclude.matcher(path).find();
    }
    private static int number(Properties p, String name, int value, int min, int max) {
        int result = Integer.parseInt(p.getProperty(name, Integer.toString(value)));
        if (result < min || result > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        return result;
    }
}
