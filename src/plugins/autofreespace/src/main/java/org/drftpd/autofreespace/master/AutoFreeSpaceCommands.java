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

import org.drftpd.common.util.Bytes;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandInterface;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.CommandResponse;
import org.drftpd.master.commands.ImproperUsageException;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.usermanager.User;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AutoFreeSpaceCommands extends CommandInterface {

    public CommandResponse doSITE_DUPE2(CommandRequest request) throws ImproperUsageException {
        if (!request.hasArgument()) {
            throw new ImproperUsageException();
        }

        String argument = request.getArgument().trim();
        int limit = Integer.parseInt(request.getProperties().getProperty("limit", "50"));
        int sectionLimit = Integer.parseInt(request.getProperties().getProperty("section.limit",
                String.valueOf(limit)));
        boolean observePrivPath = request.getProperties().getProperty("observe.privpath", "true")
                .equalsIgnoreCase("true");
        User user = request.getSession().getUserNull(request.getUser());

        SectionInterface section = findSection(argument);
        if (section != null) {
            return doSectionDupe2(section, sectionLimit, observePrivPath, user);
        }

        String releaseName = getReleaseName(argument);
        String key = Dupe2Utils.makeDupeKey(releaseName);
        if (key == null) {
            return new CommandResponse(550, "Unable to build Dupe2 key from: " + releaseName);
        }

        Map<String, List<Dupe2Utils.DupeCandidate>> candidatesByKey = Dupe2Utils.getAllSectionCandidates(true);
        List<Dupe2Utils.DupeCandidate> candidates = getVisibleCandidates(candidatesByKey.get(key),
                observePrivPath, user);

        CommandResponse response = new CommandResponse(200, "Dupe2 complete");
        response.addComment("Dupe2 key: " + key);
        if (candidates.isEmpty()) {
            response.addComment("No releases found for this key.");
            return response;
        }

        addCandidateGroup(response, candidates, limit);
        return response;
    }

    private CommandResponse doSectionDupe2(SectionInterface section, int limit, boolean observePrivPath, User user) {
        Map<String, List<Dupe2Utils.DupeCandidate>> sectionCandidates =
                Dupe2Utils.getSectionCandidates(section, true);
        Map<String, List<Dupe2Utils.DupeCandidate>> allCandidates = Dupe2Utils.getAllSectionCandidates(true);

        CommandResponse response = new CommandResponse(200, "Dupe2 section scan complete");
        response.addComment("Dupe2 section: " + section.getName());
        if (sectionCandidates.isEmpty()) {
            response.addComment("No releases found in this section.");
            return response;
        }

        List<String> keys = new ArrayList<>(sectionCandidates.keySet());
        Collections.sort(keys);
        int groups = 0;
        for (String key : keys) {
            if (groups >= limit) {
                response.addComment("Section result limit reached (" + limit + " group(s)).");
                break;
            }
            List<Dupe2Utils.DupeCandidate> candidates = getVisibleCandidates(allCandidates.get(key),
                    observePrivPath, user);
            if (candidates.size() < 2) {
                continue;
            }
            response.addComment("Dupe2 key: " + key);
            addCandidateGroup(response, candidates, 0);
            groups++;
        }

        if (groups == 0) {
            response.addComment("No duplicate groups found from this section.");
        }
        return response;
    }

    private List<Dupe2Utils.DupeCandidate> getVisibleCandidates(List<Dupe2Utils.DupeCandidate> candidates,
                                                                boolean observePrivPath, User user) {
        List<Dupe2Utils.DupeCandidate> visibleCandidates = new ArrayList<>();
        if (candidates == null) {
            return visibleCandidates;
        }
        for (Dupe2Utils.DupeCandidate candidate : candidates) {
            try {
                if (candidate.getDirectory().isHidden(observePrivPath ? user : null)) {
                    continue;
                }
                visibleCandidates.add(candidate);
            } catch (FileNotFoundException e) {
                // Ignore entries that disappeared while the command was running.
            }
        }
        return visibleCandidates;
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
}
