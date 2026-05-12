package org.drftpd.autofreespace.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.util.Bytes;
import org.drftpd.common.util.ConfigLoader;
import org.drftpd.common.util.PropertyHelper;
import org.drftpd.master.GlobalContext;
import org.drftpd.slave.exceptions.ObjectNotFoundException;

import java.util.*;

public class AutoFreeSpaceSettings {
    private static final Logger logger = LogManager.getLogger(AutoFreeSpaceSettings.class);
    public static String MODE_DISABLED = "Disabled";
    public static String MODE_DATE = "Date";
    public static String MODE_SPACE = "Space";
    private static final String DEFAULT_DUPE_MARKER_REGEX =
            "(?i)(^|[._ -])(MULTI|SUBFRENCH|FRENCH|TRUEFRENCH|VOSTFR|2160P|1080P|720P|480P|WEB[-.]?DL|WEBDL|WEB|BLURAY|BDRIP|HDRIP|DVDRIP|HDTV|UHD|HDR|DV|HEVC|H265|H264|X265|X264)([._ -]|$)";
    private static AutoFreeSpaceSettings ref;
    private Map<String, Section> _sections;
    private List<String> _excludeFiles;
    private List<String> _excludeSlaves;
    private List<ScoreRule> _dupeScoreRules;
    private List<KeepRule> _dupeKeepRules;
    private boolean _onlyAnnounce;
    private boolean _dupeKeepUnmatched;
    private String _mode;
    private long _minFreeSpace;
    private long _cycleTime;
    private int _maxIterations;
    private String _dupeMarkerRegex;

    private AutoFreeSpaceSettings() {
        // Set defaults (just in case)
        _sections = new HashMap<>();
        _excludeFiles = new ArrayList<>();
        _excludeSlaves = new ArrayList<>();
        _dupeScoreRules = new ArrayList<>();
        _dupeKeepRules = new ArrayList<>();
        _onlyAnnounce = true;
        _dupeKeepUnmatched = true;
        _mode = MODE_DISABLED;
        _minFreeSpace = 0L;
        _cycleTime = 10080L * 60000L;
        _maxIterations = 5;
        _dupeMarkerRegex = DEFAULT_DUPE_MARKER_REGEX;
        reload();
    }

    public static synchronized AutoFreeSpaceSettings getSettings() {
        if (ref == null) {
            // it's ok, we can call this constructor
            ref = new AutoFreeSpaceSettings();
        }
        return ref;
    }

    public void reload() {
        logger.debug("Loading configuration");
        Properties p = ConfigLoader.loadPluginConfig("autofreespace.conf");

        // Quickly set the ones that are single:
        _onlyAnnounce = p.getProperty("announce.only", "false").equalsIgnoreCase("true");
        _minFreeSpace = Bytes.parseBytes(p.getProperty("keepFree"));
        _cycleTime = Long.parseLong(p.getProperty("cycleTime")) * 60000L;
        _maxIterations = Integer.parseInt(p.getProperty("max.iterations"));

        // Handle operating mode
        String mode = p.getProperty("mode", MODE_DISABLED);
        if (mode.equalsIgnoreCase(MODE_SPACE)) {
            _mode = MODE_SPACE;
        } else if (mode.equalsIgnoreCase(MODE_DATE)) {
            _mode = MODE_DATE;
        } else {
            if (!mode.equalsIgnoreCase(MODE_DISABLED)) {
                logger.error("Incorrect mode [{}] detected for AutoFreeSpace, plugin disabled!!!", mode);
            }
            _mode = MODE_DISABLED;
        }

        List<String> excludeSlaves = new ArrayList<>();

        // Handle excludeSlaves
        if (p.getProperty("excluded.slaves") != null) {
            for (String slaveName : p.getProperty("excluded.slaves").trim().split("\\s")) {
                try {
                    GlobalContext.getGlobalContext().getSlaveManager().getRemoteSlave(slaveName);
                    excludeSlaves.add(slaveName);
                } catch (ObjectNotFoundException e) {
                    logger.error("Slave with name [{}] does not exist, config error", slaveName, e);
                }
            }
        }

        _excludeSlaves = excludeSlaves;
        logger.debug("excluded Slaves set to {}", _excludeSlaves.toString());

        Map<String, Section> sections = new HashMap<>();
        int id = 1;
        String name;

        // Handle sections
        while ((name = PropertyHelper.getProperty(p, id + ".section", null)) != null) {
            long wipeAfter = Long.parseLong(p.getProperty(id + ".wipeAfter", "0")) * 60000L;
            boolean dupeOnly = p.getProperty(id + ".dupeonly", "false").equalsIgnoreCase("true");
            for (String sectionName : name.split(",")) {
                sectionName = sectionName.trim();
                if (sectionName.equals("")) {
                    continue;
                }
                if (!GlobalContext.getGlobalContext().getSectionManager().getSection(sectionName).getName().equalsIgnoreCase(sectionName)) {
                    logger.error("Section [{}] Does not exist, not creating configuration items", sectionName);
                } else {
                    sections.put(sectionName, new Section(id, sectionName, wipeAfter, dupeOnly));
                    logger.debug("Loaded section {}, wipeAfter: {}, dupeonly: {}", sectionName, wipeAfter, dupeOnly);
                }
            }
            id++;
        }
        _sections = sections;

        ArrayList<String> excludeFiles = new ArrayList<>();
        // Handle excludeFiles
        for (int i = 1; ; i++) {
            String sec = p.getProperty("excluded.file." + i);
            if (sec == null)
                break;
            excludeFiles.add(sec);
        }
        excludeFiles.trimToSize();
        _excludeFiles = excludeFiles;
        logger.debug("excluded Files set to {}", _excludeFiles.toString());

        _dupeMarkerRegex = p.getProperty("dupe.marker.regex", DEFAULT_DUPE_MARKER_REGEX);
        _dupeKeepUnmatched = p.getProperty("dupe.keep.unmatched", "true").equalsIgnoreCase("true");
        _dupeScoreRules = loadDupeScoreRules(p);
        _dupeKeepRules = loadDupeKeepRules(p);
    }

    private List<ScoreRule> loadDupeScoreRules(Properties p) {
        ArrayList<ScoreRule> scoreRules = new ArrayList<>();
        for (int i = 1; ; i++) {
            String regex = p.getProperty("dupe.score." + i + ".regex");
            if (regex == null) {
                break;
            }
            int score = Integer.parseInt(p.getProperty("dupe.score." + i + ".points", "0"));
            scoreRules.add(new ScoreRule(regex, score));
        }
        if (scoreRules.isEmpty()) {
            scoreRules.add(new ScoreRule("(?i)(^|[._ -])MULTI([._ -]|$)", 200));
            scoreRules.add(new ScoreRule("(?i)(^|[._ -])FRENCH([._ -]|$)", 120));
            scoreRules.add(new ScoreRule("(?i)(^|[._ -])SUBFRENCH([._ -]|$)", 50));
            scoreRules.add(new ScoreRule("(?i)(^|[._ -])2160P([._ -]|$)", 300));
            scoreRules.add(new ScoreRule("(?i)(^|[._ -])1080P([._ -]|$)", 200));
            scoreRules.add(new ScoreRule("(?i)(^|[._ -])720P([._ -]|$)", 100));
        }
        scoreRules.trimToSize();
        return scoreRules;
    }

    private List<KeepRule> loadDupeKeepRules(Properties p) {
        ArrayList<KeepRule> keepRules = new ArrayList<>();
        for (int i = 1; ; i++) {
            String regex = p.getProperty("dupe.keep." + i + ".regex");
            if (regex == null) {
                break;
            }
            String name = p.getProperty("dupe.keep." + i + ".name", "keep" + i);
            keepRules.add(new KeepRule(name, regex));
        }
        keepRules.trimToSize();
        return keepRules;
    }

    public Map<String, Section> getSections() {
        return _sections;
    }

    public List<String> getExcludeFiles() {
        return _excludeFiles;
    }

    public List<String> getExcludeSlaves() {
        return _excludeSlaves;
    }

    public boolean getOnlyAnnounce() {
        return _onlyAnnounce;
    }

    public String getMode() {
        return _mode;
    }

    public long getMinFreeSpace() {
        return _minFreeSpace;
    }

    public long getCycleTime() {
        return _cycleTime;
    }

    public int getMaxIterations() { return _maxIterations; }

    public List<ScoreRule> getDupeScoreRules() {
        return _dupeScoreRules;
    }

    public List<KeepRule> getDupeKeepRules() {
        return _dupeKeepRules;
    }

    public boolean getDupeKeepUnmatched() {
        return _dupeKeepUnmatched;
    }

    public boolean hasDupeOnlySections() {
        for (Section section : _sections.values()) {
            if (section.isDupeOnly()) {
                return true;
            }
        }
        return false;
    }

    public boolean isDupeOnlySection(String sectionName) {
        Section section = _sections.get(sectionName);
        return section != null && section.isDupeOnly();
    }

    public String getDupeMarkerRegex() {
        return _dupeMarkerRegex;
    }

    static class Section {
        private final int id;
        private final String name;
        private final long wipeAfter;
        private final boolean dupeOnly;

        public Section(int id, String name, long wipeAfter, boolean dupeOnly) {
            this.id = id;
            this.name = name;
            this.wipeAfter = wipeAfter;
            this.dupeOnly = dupeOnly;
        }

        public int getId() {
            return this.id;
        }

        public String getName() {
            return this.name;
        }

        public long getWipeAfter() {
            return this.wipeAfter;
        }

        public boolean isDupeOnly() {
            return dupeOnly;
        }
    }

    static class ScoreRule {
        private final String regex;
        private final int points;

        public ScoreRule(String regex, int points) {
            this.regex = regex;
            this.points = points;
        }

        public String getRegex() {
            return regex;
        }

        public int getPoints() {
            return points;
        }
    }

    static class KeepRule {
        private final String name;
        private final String regex;

        public KeepRule(String name, String regex) {
            this.name = name;
            this.regex = regex;
        }

        public String getName() {
            return name;
        }

        public String getRegex() {
            return regex;
        }
    }
}

