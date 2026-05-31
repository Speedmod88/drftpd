/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.autofreespace.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.util.Bytes;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandInterface;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.CommandResponse;
import org.drftpd.master.commands.ImproperUsageException;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.indexation.IndexEngineInterface;
import org.drftpd.master.indexation.IndexException;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoFreeSpaceCommands extends CommandInterface {
    private static final Logger logger = LogManager.getLogger(AutoFreeSpaceCommands.class);

    private static final Pattern LIMIT_OPTION_PATTERN =
            Pattern.compile("(?i)^(.*)\\s+(?:-l|-limit|--limit)(?:\\s+|=)(\\d+)\\s*$");

    public CommandResponse doSITE_DUPE2(CommandRequest request) throws ImproperUsageException {
        if (!request.hasArgument()) {
            logger.info("DUPE2 command rejected: missing argument user={} command={}",
                    request.getUser(), request.getCommand());
            throw new ImproperUsageException();
        }

        int defaultLimit = Integer.parseInt(request.getProperties().getProperty("limit", "50"));
        int defaultSectionLimit = Integer.parseInt(request.getProperties().getProperty("section.limit",
                String.valueOf(defaultLimit)));
        String rawArgument = request.getArgument().trim();
        ParsedArgument parsedArgument = parseArgument(rawArgument, defaultLimit, defaultSectionLimit);
        String argument = parsedArgument.getQuery();
        boolean observePrivPath = request.getProperties().getProperty("observe.privpath", "true")
                .equalsIgnoreCase("true");
        User user = request.getSession().getUserNull(request.getUser());
        logger.info("DUPE2 command start: user={} command={} rawArgument=[{}] query=[{}] limit={} sectionLimit={} observePrivPath={}",
                request.getUser(), request.getCommand(), rawArgument, argument, parsedArgument.getLimit(),
                parsedArgument.getSectionLimit(), observePrivPath);

        SectionInterface section = findSection(argument);
        if (section != null) {
            logger.info("DUPE2 command detected section query: user={} section={} sectionLimit={}",
                    request.getUser(), section.getName(), parsedArgument.getSectionLimit());
            return doSectionDupe2(section, parsedArgument.getSectionLimit(), observePrivPath, user);
        }

        String releaseName = getReleaseName(argument);
        String key = Dupe2Utils.makeDupeKey(releaseName);
        if (key == null) {
            logger.warn("DUPE2 command failed to build key: user={} releaseName=[{}] argument=[{}]",
                    request.getUser(), releaseName, argument);
            return new CommandResponse(550, "Unable to build Dupe2 key from: " + releaseName);
        }
        logger.info("DUPE2 command detected release query: user={} releaseName=[{}] key={}",
                request.getUser(), releaseName, key);

        IndexedCandidates indexedCandidates;
        try {
            indexedCandidates = getIndexedCandidatesForKey(key);
        } catch (IndexException | IllegalArgumentException e) {
            logger.warn("DUPE2 indexed release scan failed: user={} key={} error={}",
                    request.getUser(), key, e.getMessage(), e);
            return new CommandResponse(550, e.getMessage());
        }
        VisibleCandidates visibleCandidates = getVisibleCandidates(indexedCandidates.getCandidates(), observePrivPath, user);
        List<Dupe2Utils.DupeCandidate> candidates = visibleCandidates.getCandidates();
        logger.info("DUPE2 indexed release scan complete: user={} key={} indexedDirs={} rawMatches={} nonReleaseSkipped={} keyMismatches={} missingMatches={} visibleMatches={} hiddenMatches={} resultLimit={}",
                request.getUser(), key, indexedCandidates.getIndexedDirectories(), indexedCandidates.getCandidates().size(),
                indexedCandidates.getNonReleaseSkipped(), indexedCandidates.getKeyMismatches(),
                indexedCandidates.getMissingCount(), candidates.size(), visibleCandidates.getHiddenCount(),
                parsedArgument.getLimit());

        CommandResponse response = new CommandResponse(200, "Dupe2 complete");
        response.addComment("Dupe2 key: " + key);
        if (candidates.isEmpty()) {
            logger.info("DUPE2 release scan found no visible matches: user={} key={} releaseName=[{}]",
                    request.getUser(), key, releaseName);
            response.addComment("No releases found for this key.");
            return response;
        }

        addCandidateGroup(response, candidates, parsedArgument.getLimit());
        return response;
    }

    private CommandResponse doSectionDupe2(SectionInterface section, int limit, boolean observePrivPath, User user) {
        logger.info("DUPE2 section scan start: user={} section={} groupLimit={} observePrivPath={}",
                user == null ? null : user.getName(), section.getName(), limit, observePrivPath);
        IndexedSectionKeys sectionKeys;
        try {
            sectionKeys = getIndexedSectionKeys(section);
        } catch (IndexException | IllegalArgumentException e) {
            logger.warn("DUPE2 indexed section scan failed: user={} section={} error={}",
                    user == null ? null : user.getName(), section.getName(), e.getMessage(), e);
            return new CommandResponse(550, e.getMessage());
        }
        logger.info("DUPE2 indexed section scan loaded keys: user={} section={} indexedDirs={} releaseDirs={} keys={} nonReleaseSkipped={} missingDirs={}",
                user == null ? null : user.getName(), section.getName(), sectionKeys.getIndexedDirectories(),
                sectionKeys.getReleaseDirectories(), sectionKeys.getKeys().size(), sectionKeys.getNonReleaseSkipped(),
                sectionKeys.getMissingCount());

        CommandResponse response = new CommandResponse(200, "Dupe2 section scan complete");
        response.addComment("Dupe2 section: " + section.getName());
        if (sectionKeys.getKeys().isEmpty()) {
            logger.info("DUPE2 section scan found no section candidates: user={} section={}",
                    user == null ? null : user.getName(), section.getName());
            response.addComment("No releases found in this section.");
            return response;
        }

        List<String> keys = new ArrayList<>(sectionKeys.getKeys());
        Collections.sort(keys);
        int groups = 0;
        for (String key : keys) {
            if (limit > 0 && groups >= limit) {
                response.addComment("Section result limit reached (" + limit + " group(s)).");
                break;
            }
            IndexedCandidates indexedCandidates;
            try {
                indexedCandidates = getIndexedCandidatesForKey(key);
            } catch (IndexException | IllegalArgumentException e) {
                logger.warn("DUPE2 indexed duplicate lookup failed during section scan: user={} section={} key={} error={}",
                        user == null ? null : user.getName(), section.getName(), key, e.getMessage(), e);
                response.addComment("Dupe2 key " + key + " skipped: " + e.getMessage());
                continue;
            }
            VisibleCandidates visibleCandidates = getVisibleCandidates(indexedCandidates.getCandidates(), observePrivPath, user);
            List<Dupe2Utils.DupeCandidate> candidates = visibleCandidates.getCandidates();
            if (candidates.size() < 2) {
                logger.debug("DUPE2 indexed section scan skipped non-duplicate key: section={} key={} indexedDirs={} rawMatches={} visibleMatches={} hiddenMatches={} missingMatches={}",
                        section.getName(), key, indexedCandidates.getIndexedDirectories(),
                        indexedCandidates.getCandidates().size(), candidates.size(), visibleCandidates.getHiddenCount(),
                        visibleCandidates.getMissingCount() + indexedCandidates.getMissingCount());
                continue;
            }
            logger.info("DUPE2 indexed section scan duplicate group: user={} section={} key={} indexedDirs={} rawMatches={} visibleMatches={} hiddenMatches={} missingMatches={}",
                    user == null ? null : user.getName(), section.getName(), key,
                    indexedCandidates.getIndexedDirectories(), indexedCandidates.getCandidates().size(), candidates.size(),
                    visibleCandidates.getHiddenCount(), visibleCandidates.getMissingCount() + indexedCandidates.getMissingCount());
            response.addComment("Dupe2 key: " + key);
            addCandidateGroup(response, candidates, 0);
            groups++;
        }

        if (groups == 0) {
            logger.info("DUPE2 section scan found no duplicate groups: user={} section={} checkedKeys={}",
                    user == null ? null : user.getName(), section.getName(), sectionKeys.getKeys().size());
            response.addComment("No duplicate groups found from this section.");
        } else {
            logger.info("DUPE2 section scan complete: user={} section={} groups={}",
                    user == null ? null : user.getName(), section.getName(), groups);
        }
        return response;
    }

    private VisibleCandidates getVisibleCandidates(List<Dupe2Utils.DupeCandidate> candidates,
                                                   boolean observePrivPath, User user) {
        List<Dupe2Utils.DupeCandidate> visibleCandidates = new ArrayList<>();
        int hiddenCount = 0;
        int missingCount = 0;
        if (candidates == null) {
            return new VisibleCandidates(visibleCandidates, hiddenCount, missingCount);
        }
        for (Dupe2Utils.DupeCandidate candidate : candidates) {
            try {
                if (candidate.getDirectory().isHidden(observePrivPath ? user : null)) {
                    hiddenCount++;
                    continue;
                }
                visibleCandidates.add(candidate);
            } catch (FileNotFoundException e) {
                missingCount++;
                logger.debug("DUPE2 command candidate disappeared while filtering visibility: {}",
                        candidate.getDirectory().getPath());
            }
        }
        return new VisibleCandidates(visibleCandidates, hiddenCount, missingCount);
    }

    private IndexedCandidates getIndexedCandidatesForKey(String key)
            throws IndexException, IllegalArgumentException {
        Map<String, String> indexedDirectories = getIndexedDirectories(
                GlobalContext.getGlobalContext().getRoot(), getIndexSearchText(key), "DUPE2-release");
        List<Dupe2Utils.DupeCandidate> candidates = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        Map<String, Set<String>> releaseParentPathCache = new HashMap<>();
        int nonReleaseSkipped = 0;
        int keyMismatches = 0;
        int missingCount = 0;

        for (Map.Entry<String, String> item : indexedDirectories.entrySet()) {
            if (!"d".equals(item.getValue())) {
                continue;
            }
            String path = stripIndexedDirectoryPath(item.getKey());
            if (!seenPaths.add(path)) {
                continue;
            }
            DirectoryHandle directory = new DirectoryHandle(path);
            SectionInterface section = getReleaseSection(directory, releaseParentPathCache);
            if (section == null) {
                nonReleaseSkipped++;
                continue;
            }
            try {
                Dupe2Utils.DupeCandidate candidate =
                        Dupe2Utils.makeDupeCandidate(directory, section.getName(), true);
                if (candidate == null) {
                    nonReleaseSkipped++;
                    continue;
                }
                if (!key.equals(candidate.getKey())) {
                    keyMismatches++;
                    continue;
                }
                candidates.add(candidate);
            } catch (RuntimeException e) {
                missingCount++;
                logger.debug("DUPE2 indexed release candidate disappeared or failed while loading: {}", path, e);
            }
        }

        return new IndexedCandidates(candidates, indexedDirectories.size(), nonReleaseSkipped, keyMismatches,
                missingCount);
    }

    private IndexedSectionKeys getIndexedSectionKeys(SectionInterface section)
            throws IndexException, IllegalArgumentException {
        Map<String, String> indexedDirectories = getIndexedDirectories(
                section.getBaseDirectory(), null, "DUPE2-section");
        Set<String> keys = new HashSet<>();
        Map<String, Set<String>> releaseParentPathCache = new HashMap<>();
        int releaseDirectories = 0;
        int nonReleaseSkipped = 0;
        int missingCount = 0;

        for (Map.Entry<String, String> item : indexedDirectories.entrySet()) {
            if (!"d".equals(item.getValue())) {
                continue;
            }
            String path = stripIndexedDirectoryPath(item.getKey());
            DirectoryHandle directory = new DirectoryHandle(path);
            if (!isReleaseDirectoryInSection(directory, section, releaseParentPathCache)) {
                nonReleaseSkipped++;
                continue;
            }
            try {
                if (Dupe2Utils.isExcludedReleaseName(directory.getName())) {
                    nonReleaseSkipped++;
                    continue;
                }
                String key = Dupe2Utils.makeDupeKey(directory.getName());
                if (key != null) {
                    keys.add(key);
                    releaseDirectories++;
                }
            } catch (RuntimeException e) {
                missingCount++;
                logger.debug("DUPE2 indexed section candidate disappeared or failed while loading: {}", path, e);
            }
        }

        return new IndexedSectionKeys(keys, indexedDirectories.size(), releaseDirectories, nonReleaseSkipped,
                missingCount);
    }

    private Map<String, String> getIndexedDirectories(DirectoryHandle startNode, String searchText, String caller)
            throws IndexException, IllegalArgumentException {
        AdvancedSearchParams params = new AdvancedSearchParams();
        params.setInodeType(AdvancedSearchParams.InodeType.DIRECTORY);
        params.setLimit(0);
        if (searchText != null && !searchText.equals("")) {
            params.setName(searchText);
        }
        IndexEngineInterface indexEngine = GlobalContext.getGlobalContext().getIndexEngine();
        return indexEngine.advancedFind(startNode, params, caller);
    }

    private SectionInterface getReleaseSection(DirectoryHandle directory,
                                               Map<String, Set<String>> releaseParentPathCache) {
        SectionInterface section = GlobalContext.getGlobalContext().getSectionManager().lookup(directory);
        if (section == null || section.getName().equals("")) {
            return null;
        }
        if (!isReleaseDirectoryInSection(directory, section, releaseParentPathCache)) {
            return null;
        }
        return section;
    }

    private boolean isReleaseDirectoryInSection(DirectoryHandle directory, SectionInterface section,
                                                Map<String, Set<String>> releaseParentPathCache) {
        Set<String> releaseParentPaths = releaseParentPathCache.computeIfAbsent(section.getName(),
                key -> getReleaseParentPaths(section));
        return releaseParentPaths.contains(directory.getParent().getPath());
    }

    private Set<String> getReleaseParentPaths(SectionInterface section) {
        Set<String> releaseParentPaths = new HashSet<>();
        if (section.getCurrentDirectory().getPath().equals(section.getBaseDirectory().getPath())) {
            releaseParentPaths.add(section.getBaseDirectory().getPath());
        } else {
            for (DirectoryHandle directory : section.getDirectories()) {
                releaseParentPaths.add(directory.getPath());
            }
        }
        return releaseParentPaths;
    }

    private String getIndexSearchText(String key) {
        return key.replace('.', ' ');
    }

    private String stripIndexedDirectoryPath(String path) {
        if (path.endsWith("/") && path.length() > 1) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void addCandidateGroup(CommandResponse response, List<Dupe2Utils.DupeCandidate> candidates, int limit) {
        Collections.sort(candidates, Collections.reverseOrder());
        List<Dupe2Utils.DupeCandidate> completedCandidates = Dupe2Utils.getCompletedCandidates(candidates);
        Set<Dupe2Utils.DupeCandidate> keepers = Dupe2Utils.getDupeKeepers(completedCandidates);
        response.addComment("Found " + candidates.size() + " release(s), completed="
                + completedCandidates.size() + ", incomplete=" + (candidates.size() - completedCandidates.size()));

        int count = 0;
        for (Dupe2Utils.DupeCandidate candidate : candidates) {
            if (limit > 0 && count >= limit) {
                response.addComment("Result limit reached (" + limit + ").");
                break;
            }
            response.addComment(formatCandidate(candidate, keepers));
            count++;
        }
    }

    private String formatCandidate(Dupe2Utils.DupeCandidate candidate, Set<Dupe2Utils.DupeCandidate> keepers) {
        String state = candidate.isComplete()
                ? (keepers.contains(candidate) ? "KEEP" : "DUPE")
                : "SKIP";
        return state
                + " status=" + candidate.getStatus()
                + " score=" + candidate.getScore()
                + " bucket=" + candidate.getDisplayBucket()
                + " section=" + candidate.getSectionName()
                + " size=" + Bytes.formatBytes(candidate.getSize())
                + " path=" + candidate.getDirectory().getPath();
    }

    private SectionInterface findSection(String name) {
        for (SectionInterface section : GlobalContext.getGlobalContext().getSectionManager().getSections()) {
            if (section.getName().equalsIgnoreCase(name)) {
                return section;
            }
        }
        return null;
    }

    private String getReleaseName(String argument) {
        int slash = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        if (slash >= 0 && slash < argument.length() - 1) {
            return argument.substring(slash + 1);
        }
        return argument;
    }

    private ParsedArgument parseArgument(String argument, int defaultLimit, int defaultSectionLimit)
            throws ImproperUsageException {
        Matcher matcher = LIMIT_OPTION_PATTERN.matcher(argument);
        if (!matcher.matches()) {
            return new ParsedArgument(argument, defaultLimit, defaultSectionLimit);
        }

        String query = matcher.group(1).trim();
        if (query.equals("")) {
            throw new ImproperUsageException();
        }

        int overrideLimit = Integer.parseInt(matcher.group(2));
        return new ParsedArgument(query, overrideLimit, overrideLimit);
    }

    private static class IndexedCandidates {
        private final List<Dupe2Utils.DupeCandidate> candidates;
        private final int indexedDirectories;
        private final int nonReleaseSkipped;
        private final int keyMismatches;
        private final int missingCount;

        private IndexedCandidates(List<Dupe2Utils.DupeCandidate> candidates, int indexedDirectories,
                                  int nonReleaseSkipped, int keyMismatches, int missingCount) {
            this.candidates = candidates;
            this.indexedDirectories = indexedDirectories;
            this.nonReleaseSkipped = nonReleaseSkipped;
            this.keyMismatches = keyMismatches;
            this.missingCount = missingCount;
        }

        private List<Dupe2Utils.DupeCandidate> getCandidates() {
            return candidates;
        }

        private int getIndexedDirectories() {
            return indexedDirectories;
        }

        private int getNonReleaseSkipped() {
            return nonReleaseSkipped;
        }

        private int getKeyMismatches() {
            return keyMismatches;
        }

        private int getMissingCount() {
            return missingCount;
        }
    }

    private static class IndexedSectionKeys {
        private final Set<String> keys;
        private final int indexedDirectories;
        private final int releaseDirectories;
        private final int nonReleaseSkipped;
        private final int missingCount;

        private IndexedSectionKeys(Set<String> keys, int indexedDirectories, int releaseDirectories,
                                   int nonReleaseSkipped, int missingCount) {
            this.keys = keys;
            this.indexedDirectories = indexedDirectories;
            this.releaseDirectories = releaseDirectories;
            this.nonReleaseSkipped = nonReleaseSkipped;
            this.missingCount = missingCount;
        }

        private Set<String> getKeys() {
            return keys;
        }

        private int getIndexedDirectories() {
            return indexedDirectories;
        }

        private int getReleaseDirectories() {
            return releaseDirectories;
        }

        private int getNonReleaseSkipped() {
            return nonReleaseSkipped;
        }

        private int getMissingCount() {
            return missingCount;
        }
    }

    private static class VisibleCandidates {
        private final List<Dupe2Utils.DupeCandidate> candidates;
        private final int hiddenCount;
        private final int missingCount;

        private VisibleCandidates(List<Dupe2Utils.DupeCandidate> candidates, int hiddenCount, int missingCount) {
            this.candidates = candidates;
            this.hiddenCount = hiddenCount;
            this.missingCount = missingCount;
        }

        private List<Dupe2Utils.DupeCandidate> getCandidates() {
            return candidates;
        }

        private int getHiddenCount() {
            return hiddenCount;
        }

        private int getMissingCount() {
            return missingCount;
        }
    }

    private static class ParsedArgument {
        private final String query;
        private final int limit;
        private final int sectionLimit;

        private ParsedArgument(String query, int limit, int sectionLimit) {
            this.query = query;
            this.limit = limit;
            this.sectionLimit = sectionLimit;
        }

        private String getQuery() {
            return query;
        }

        private int getLimit() {
            return limit;
        }

        private int getSectionLimit() {
            return sectionLimit;
        }
    }
}
