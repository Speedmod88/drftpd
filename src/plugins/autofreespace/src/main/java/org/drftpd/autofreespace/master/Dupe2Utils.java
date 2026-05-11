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
import org.drftpd.master.exceptions.NoAvailableSlaveException;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.slavemanagement.RemoteSlave;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.InodeHandle;
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

final class Dupe2Utils {
    private static final Logger logger = LogManager.getLogger(Dupe2Utils.class);

    private Dupe2Utils() {
    }

    static Map<String, List<DupeCandidate>> getAllSectionCandidates() {
        Map<String, List<DupeCandidate>> candidatesByKey = new HashMap<>();
        for (SectionInterface section : GlobalContext.getGlobalContext().getSectionManager().getSections()) {
            addSectionCandidates(candidatesByKey, section);
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

    static String makeDupeKey(String releaseName) {
        String body = stripReleaseGroup(releaseName);
        int markerIndex = findMarkerIndex(body);
        String title = markerIndex >= 0 ? body.substring(0, markerIndex) : body;
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

    static RemoteSlave findEligibleSlave(InodeHandle release, Collection<RemoteSlave> availableSlaves)
            throws NoAvailableSlaveException, FileNotFoundException {
        for (RemoteSlave slave : availableSlaves) {
            if (AutoFreeSpaceSettings.getSettings().getExcludeSlaves().contains(slave.getName())) {
                continue;
            }
            if (gotFilesOn(release, slave)) {
                return slave;
            }
        }
        return null;
    }

    private static void addSectionCandidates(Map<String, List<DupeCandidate>> candidatesByKey, SectionInterface section) {
        for (DirectoryHandle baseDirectory : section.getDirectories()) {
            try {
                for (DirectoryHandle release : baseDirectory.getDirectoriesUnchecked()) {
                    DupeCandidate candidate = makeDupeCandidate(release, section.getName());
                    if (candidate != null) {
                        candidatesByKey.computeIfAbsent(candidate.getKey(), k -> new ArrayList<>()).add(candidate);
                    }
                }
            } catch (FileNotFoundException e) {
                logger.warn("DUPE2: Section directory disappeared while scanning duplicates: {}", baseDirectory.getPath());
            }
        }
    }

    private static DupeCandidate makeDupeCandidate(DirectoryHandle release, String sectionName) {
        String key = makeDupeKey(release.getName());
        if (key == null) {
            return null;
        }
        if (checkInvalidName(release.getName())) {
            return null;
        }
        if (!isComplete(release)) {
            logger.debug("DUPE2: Skipping incomplete duplicate candidate {}", release.getPath());
            return null;
        }
        try {
            int score = scoreRelease(release.getName());
            String bucket = findKeepBucket(release.getName());
            return new DupeCandidate(key, release, sectionName, bucket, score, release.getSize(), release.creationTime());
        } catch (FileNotFoundException e) {
            logger.warn("DUPE2: Duplicate candidate disappeared while scanning: {}", release.getPath());
            return null;
        }
    }

    private static boolean checkInvalidName(String name) {
        for (String regex : AutoFreeSpaceSettings.getSettings().getExcludeFiles()) {
            if (name.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private static String findKeepBucket(String releaseName) {
        String body = stripReleaseGroup(releaseName);
        for (AutoFreeSpaceSettings.KeepRule keepRule : AutoFreeSpaceSettings.getSettings().getDupeKeepRules()) {
            try {
                if (Pattern.compile(keepRule.getRegex(), Pattern.CASE_INSENSITIVE).matcher(body).find()) {
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

    private static String stripReleaseGroup(String releaseName) {
        try {
            return releaseName.replaceFirst(AutoFreeSpaceSettings.getSettings().getDupeGroupRegex(), "");
        } catch (PatternSyntaxException e) {
            logger.error("DUPE2: Invalid dupe.group.regex, keeping release name unmodified", e);
            return releaseName;
        }
    }

    private static String normalizeKeyToken(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "");
    }

    private static int scoreRelease(String releaseName) {
        int score = 0;
        String body = stripReleaseGroup(releaseName);
        for (AutoFreeSpaceSettings.ScoreRule scoreRule : AutoFreeSpaceSettings.getSettings().getDupeScoreRules()) {
            try {
                if (Pattern.compile(scoreRule.getRegex(), Pattern.CASE_INSENSITIVE).matcher(body).find()) {
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

    private static boolean gotFilesOn(InodeHandle inode, RemoteSlave slave)
            throws NoAvailableSlaveException, FileNotFoundException {
        if (inode.isFile()) {
            return ((FileHandle) inode).getAvailableSlaves().contains(slave);
        } else if (inode.isDirectory()) {
            for (FileHandle file : ((DirectoryHandle) inode).getAllFilesRecursiveUnchecked()) {
                if (file.getAvailableSlaves().contains(slave)) {
                    return true;
                }
            }
        }
        return false;
    }

    static class DupeCandidate implements Comparable<DupeCandidate> {
        private final String key;
        private final DirectoryHandle directory;
        private final String sectionName;
        private String bucket;
        private final int score;
        private final long size;
        private final long creationTime;

        private DupeCandidate(String key, DirectoryHandle directory, String sectionName, String bucket, int score,
                              long size, long creationTime) {
            this.key = key;
            this.directory = directory;
            this.sectionName = sectionName;
            this.bucket = bucket;
            this.score = score;
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
