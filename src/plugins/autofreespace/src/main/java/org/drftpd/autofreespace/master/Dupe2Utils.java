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
import org.drftpd.master.GlobalContext;
import org.drftpd.master.indexation.AdvancedSearchParams;
import org.drftpd.master.indexation.IndexEngineInterface;
import org.drftpd.master.indexation.IndexException;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.zipscript.master.sfv.vfs.ZipscriptVFSDataSFV;
import org.drftpd.zipscript.master.zip.vfs.ZipscriptVFSDataZip;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class Dupe2Utils {
    private static final Logger logger = LogManager.getLogger(Dupe2Utils.class);

    private Dupe2Utils() {
    }

    public static Set<String> getDupeLoserPaths(Collection<String> candidateIndexedPaths, String requiredText)
            throws IndexException {
        return getDupeLoserPaths(candidateIndexedPaths, requiredText, Collections.emptyList());
    }

    public static Set<String> getDupeLoserPaths(Collection<String> candidateIndexedPaths, String requiredText,
                                                Collection<String> replacementTexts)
            throws IndexException {
        Map<String, List<DupeCandidate>> selectedCandidatesByKey = getSelectedCandidatesByKey(
                candidateIndexedPaths, requiredText);
        if (selectedCandidatesByKey.isEmpty()) {
            return Collections.emptySet();
        }

        Map<String, List<DupeCandidate>> allCandidatesByKey =
                getIndexedCandidatesForKeys(selectedCandidatesByKey.keySet());
        Set<String> selectedPaths = getCandidatePaths(selectedCandidatesByKey);
        Set<String> loserPaths = new HashSet<>();

        for (String key : selectedCandidatesByKey.keySet()) {
            List<DupeCandidate> candidates = allCandidatesByKey.getOrDefault(key, Collections.emptyList());
            List<DupeCandidate> completedCandidates = getCompletedCandidates(candidates);
            if (completedCandidates.size() < 2) {
                continue;
            }
            if (requiredText == null) {
                Set<String> keeperPaths = getCandidatePaths(getDupeKeepers(completedCandidates));
                for (DupeCandidate candidate : completedCandidates) {
                    String path = candidate.getDirectory().getPath();
                    if (selectedPaths.contains(path) && !keeperPaths.contains(path)) {
                        loserPaths.add(path);
                    }
                }
            } else if (hasCompletedReplacementCandidate(completedCandidates, requiredText, replacementTexts)) {
                for (DupeCandidate candidate : selectedCandidatesByKey.getOrDefault(key, Collections.emptyList())) {
                    loserPaths.add(candidate.getDirectory().getPath());
                }
            }
        }

        return loserPaths;
    }

    static Map<String, List<DupeCandidate>> getAllSectionCandidates() {
        return getAllSectionCandidates(false);
    }

    static Map<String, List<DupeCandidate>> getAllSectionCandidates(boolean includeIncomplete) {
        Map<String, List<DupeCandidate>> candidatesByKey = new HashMap<>();
        for (SectionInterface section : GlobalContext.getGlobalContext().getSectionManager().getSections()) {
            addSectionCandidates(candidatesByKey, section, includeIncomplete);
        }
        return candidatesByKey;
    }

    static Set<DupeCandidate> getDupeKeepers(List<DupeCandidate> candidates) {
        Map<String, DupeCandidate> keepersByBucket = new HashMap<>();
        boolean hasKeepRules = !AutoFreeSpaceSettings.getSettings().getDupeKeepRules().isEmpty();
        for (DupeCandidate candidate : candidates) {
            if (candidate.getBucket() == null) {
                if (hasKeepRules && !AutoFreeSpaceSettings.getSettings().getDupeKeepUnmatched()) {
                    continue;
                }
                candidate.setBucket("default");
            }
            DupeCandidate current = keepersByBucket.get(candidate.getBucket());
            if (current == null || candidate.compareTo(current) > 0) {
                keepersByBucket.put(candidate.getBucket(), candidate);
            }
        }
        if (keepersByBucket.isEmpty() && !candidates.isEmpty()) {
            keepersByBucket.put("fallback", candidates.get(0));
        }
        return new HashSet<>(keepersByBucket.values());
    }

    static Map<String, List<DupeCandidate>> getSectionCandidates(SectionInterface section, boolean includeIncomplete) {
        Map<String, List<DupeCandidate>> candidatesByKey = new HashMap<>();
        addSectionCandidates(candidatesByKey, section, includeIncomplete);
        return candidatesByKey;
    }

    static List<DupeCandidate> getCompletedCandidates(List<DupeCandidate> candidates) {
        List<DupeCandidate> completed = new ArrayList<>();
        for (DupeCandidate candidate : candidates) {
            if (candidate.isComplete()) {
                completed.add(candidate);
            }
        }
        return completed;
    }

    private static Map<String, List<DupeCandidate>> getSelectedCandidatesByKey(Collection<String> candidateIndexedPaths,
                                                                               String requiredText) {
        Map<String, List<DupeCandidate>> selectedCandidatesByKey = new HashMap<>();
        Map<String, Set<String>> releaseParentPathCache = new HashMap<>();
        Set<String> seenPaths = new HashSet<>();

        for (String candidateIndexedPath : candidateIndexedPaths) {
            String path = stripIndexedDirectoryPath(candidateIndexedPath);
            if (!seenPaths.add(path)) {
                continue;
            }
            DirectoryHandle directory = new DirectoryHandle(path);
            SectionInterface section = getReleaseSection(directory, releaseParentPathCache);
            if (section == null) {
                continue;
            }
            DupeCandidate candidate = makeDupeCandidate(directory, section.getName(), true);
            if (candidate == null || !candidate.isComplete()) {
                continue;
            }
            if (requiredText != null && !matchesRequiredText(directory.getName(), requiredText)) {
                continue;
            }
            selectedCandidatesByKey.computeIfAbsent(candidate.getKey(), k -> new ArrayList<>()).add(candidate);
        }

        return selectedCandidatesByKey;
    }

    private static Map<String, List<DupeCandidate>> getIndexedCandidatesForKeys(Set<String> keys)
            throws IndexException {
        AdvancedSearchParams params = new AdvancedSearchParams();
        params.setInodeType(AdvancedSearchParams.InodeType.DIRECTORY);
        params.setLimit(0);
        IndexEngineInterface indexEngine = GlobalContext.getGlobalContext().getIndexEngine();
        Map<String, String> indexedDirectories = indexEngine.advancedFind(
                GlobalContext.getGlobalContext().getRoot(), params, "FIND-DUPE2");
        Map<String, List<DupeCandidate>> candidatesByKey = new HashMap<>();
        Map<String, Set<String>> releaseParentPathCache = new HashMap<>();
        Set<String> seenPaths = new HashSet<>();

        for (Map.Entry<String, String> item : indexedDirectories.entrySet()) {
            if (!"d".equals(item.getValue())) {
                continue;
            }
            String path = stripIndexedDirectoryPath(item.getKey());
            if (!seenPaths.add(path)) {
                continue;
            }
            DirectoryHandle directory = new DirectoryHandle(path);
            if (isExcludedReleaseName(directory.getName())) {
                continue;
            }
            String key = makeDupeKey(directory.getName());
            if (key == null || !keys.contains(key)) {
                continue;
            }
            SectionInterface section = getReleaseSection(directory, releaseParentPathCache);
            if (section == null) {
                continue;
            }
            DupeCandidate candidate = makeDupeCandidate(directory, section.getName(), true);
            if (candidate != null) {
                candidatesByKey.computeIfAbsent(candidate.getKey(), k -> new ArrayList<>()).add(candidate);
            }
        }

        return candidatesByKey;
    }

    private static SectionInterface getReleaseSection(DirectoryHandle directory,
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

    private static boolean isReleaseDirectoryInSection(DirectoryHandle directory, SectionInterface section,
                                                       Map<String, Set<String>> releaseParentPathCache) {
        Set<String> releaseParentPaths = releaseParentPathCache.computeIfAbsent(section.getName(),
                key -> getReleaseParentPaths(section));
        return releaseParentPaths.contains(directory.getParent().getPath());
    }

    private static Set<String> getReleaseParentPaths(SectionInterface section) {
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

    private static boolean hasCompletedReplacementCandidate(List<DupeCandidate> candidates, String requiredText,
                                                            Collection<String> replacementTexts) {
        for (DupeCandidate candidate : candidates) {
            String releaseName = candidate.getDirectory().getName();
            if (matchesRequiredText(releaseName, requiredText)) {
                continue;
            }
            if (replacementTexts == null || replacementTexts.isEmpty() || matchesAnyRequiredText(releaseName, replacementTexts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyRequiredText(String releaseName, Collection<String> requiredTexts) {
        for (String requiredText : requiredTexts) {
            if (matchesRequiredText(releaseName, requiredText)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRequiredText(String releaseName, String requiredText) {
        return Pattern.compile("(^|[._ -])" + Pattern.quote(requiredText) + "([._ -]|$)", Pattern.CASE_INSENSITIVE)
                .matcher(releaseName)
                .find();
    }

    private static Set<String> getCandidatePaths(Map<String, List<DupeCandidate>> candidatesByKey) {
        Set<String> paths = new HashSet<>();
        for (List<DupeCandidate> candidates : candidatesByKey.values()) {
            paths.addAll(getCandidatePaths(candidates));
        }
        return paths;
    }

    private static Set<String> getCandidatePaths(Collection<DupeCandidate> candidates) {
        Set<String> paths = new HashSet<>();
        for (DupeCandidate candidate : candidates) {
            paths.add(candidate.getDirectory().getPath());
        }
        return paths;
    }

    private static String stripIndexedDirectoryPath(String path) {
        if (path.endsWith("/") && path.length() > 1) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    static String makeDupeKey(String releaseName) {
        int markerIndex = findMarkerIndex(releaseName);
        String title = markerIndex >= 0 ? releaseName.substring(0, markerIndex) : releaseName;
        title = normalizeKeyToken(title);
        return title.equals("") ? null : title;
    }

    static String getKeeperNames(Set<DupeCandidate> keepers) {
        List<String> names = new ArrayList<>();
        for (DupeCandidate keeper : keepers) {
            names.add(keeper.getDirectory().getName() + "=" + keeper.getScore() + "/" + keeper.getDisplayBucket());
        }
        Collections.sort(names);
        return names.toString();
    }

    private static void addSectionCandidates(Map<String, List<DupeCandidate>> candidatesByKey, SectionInterface section,
                                             boolean includeIncomplete) {
        for (DirectoryHandle baseDirectory : section.getDirectories()) {
            try {
                for (DirectoryHandle release : baseDirectory.getDirectoriesUnchecked()) {
                    DupeCandidate candidate = makeDupeCandidate(release, section.getName(), includeIncomplete);
                    if (candidate != null) {
                        candidatesByKey.computeIfAbsent(candidate.getKey(), k -> new ArrayList<>()).add(candidate);
                    }
                }
            } catch (FileNotFoundException e) {
                logger.warn("DUPE2: Section directory disappeared while scanning duplicates: {}", baseDirectory.getPath());
            }
        }
    }

    static DupeCandidate makeDupeCandidate(DirectoryHandle release, String sectionName,
                                           boolean includeIncomplete) {
        String key = makeDupeKey(release.getName());
        if (key == null) {
            return null;
        }
        if (isExcludedReleaseName(release.getName())) {
            return null;
        }
        boolean complete = isComplete(release);
        if (!includeIncomplete && !complete) {
            logger.debug("DUPE2: Skipping incomplete duplicate candidate {}", release.getPath());
            return null;
        }
        try {
            int score = scoreRelease(release.getName());
            String bucket = findKeepBucket(release.getName());
            return new DupeCandidate(key, release, sectionName, bucket, score, complete, release.getSize(), release.creationTime());
        } catch (FileNotFoundException e) {
            logger.warn("DUPE2: Duplicate candidate disappeared while scanning: {}", release.getPath());
            return null;
        }
    }

    static boolean isExcludedReleaseName(String name) {
        for (String regex : AutoFreeSpaceSettings.getSettings().getExcludeFiles()) {
            if (name.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private static String findKeepBucket(String releaseName) {
        for (AutoFreeSpaceSettings.KeepRule keepRule : AutoFreeSpaceSettings.getSettings().getDupeKeepRules()) {
            try {
                if (Pattern.compile(keepRule.getRegex(), Pattern.CASE_INSENSITIVE).matcher(releaseName).find()) {
                    return keepRule.getName();
                }
            } catch (PatternSyntaxException e) {
                logger.error("DUPE2: Invalid duplicate keep regex [{}], skipping", keepRule.getRegex(), e);
            }
        }
        return null;
    }

    private static int findMarkerIndex(String releaseName) {
        try {
            Matcher matcher = Pattern.compile(AutoFreeSpaceSettings.getSettings().getDupeMarkerRegex(),
                    Pattern.CASE_INSENSITIVE).matcher(releaseName);
            if (matcher.find()) {
                return matcher.start();
            }
        } catch (PatternSyntaxException e) {
            logger.error("DUPE2: Invalid dupe.marker.regex, using full release name as key", e);
        }
        return -1;
    }

    private static String normalizeKeyToken(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "");
    }

    private static int scoreRelease(String releaseName) {
        int score = 0;
        for (AutoFreeSpaceSettings.ScoreRule scoreRule : AutoFreeSpaceSettings.getSettings().getDupeScoreRules()) {
            try {
                if (Pattern.compile(scoreRule.getRegex(), Pattern.CASE_INSENSITIVE).matcher(releaseName).find()) {
                    score += scoreRule.getPoints();
                }
            } catch (PatternSyntaxException e) {
                logger.error("DUPE2: Invalid duplicate score regex [{}], skipping", scoreRule.getRegex(), e);
            }
        }
        return score;
    }

    private static boolean isComplete(DirectoryHandle release) {
        try {
            if (new ZipscriptVFSDataSFV(release).getSFVStatus().isFinished()) {
                return true;
            }
        } catch (Exception ignored) {
            // Not an SFV release, try zip/diz below.
        }
        try {
            return new ZipscriptVFSDataZip(release).getDizStatus().isFinished();
        } catch (Exception ignored) {
            return false;
        }
    }

    static class DupeCandidate implements Comparable<DupeCandidate> {
        private final String key;
        private final DirectoryHandle directory;
        private final String sectionName;
        private String bucket;
        private final int score;
        private final boolean complete;
        private final long size;
        private final long creationTime;

        private DupeCandidate(String key, DirectoryHandle directory, String sectionName, String bucket, int score,
                              boolean complete, long size, long creationTime) {
            this.key = key;
            this.directory = directory;
            this.sectionName = sectionName;
            this.bucket = bucket;
            this.score = score;
            this.complete = complete;
            this.size = size;
            this.creationTime = creationTime;
        }

        String getKey() {
            return key;
        }

        DirectoryHandle getDirectory() {
            return directory;
        }

        String getSectionName() {
            return sectionName;
        }

        String getBucket() {
            return bucket;
        }

        void setBucket(String bucket) {
            this.bucket = bucket;
        }

        String getDisplayBucket() {
            return bucket == null ? "-" : bucket;
        }

        int getScore() {
            return score;
        }

        boolean isComplete() {
            return complete;
        }

        String getStatus() {
            return complete ? "completed" : "incomplete";
        }

        long getSize() {
            return size;
        }

        long getCreationTime() {
            return creationTime;
        }

        public int compareTo(DupeCandidate other) {
            int result = Integer.compare(score, other.score);
            if (result != 0) {
                return result;
            }
            result = Long.compare(size, other.size);
            if (result != 0) {
                return result;
            }
            return Long.compare(creationTime, other.creationTime);
        }
    }
}
