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
import org.drftpd.master.commands.CommandInterface;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.CommandResponse;
import org.drftpd.master.commands.ImproperUsageException;
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

        String releaseName = getReleaseName(request.getArgument().trim());
        String key = Dupe2Utils.makeDupeKey(releaseName);
        if (key == null) {
            return new CommandResponse(550, "Unable to build Dupe2 key from: " + releaseName);
        }

        int limit = Integer.parseInt(request.getProperties().getProperty("limit", "50"));
        boolean observePrivPath = request.getProperties().getProperty("observe.privpath", "true")
                .equalsIgnoreCase("true");
        User user = request.getSession().getUserNull(request.getUser());

        Map<String, List<Dupe2Utils.DupeCandidate>> candidatesByKey = Dupe2Utils.getAllSectionCandidates();
        List<Dupe2Utils.DupeCandidate> candidates = new ArrayList<>();
        if (candidatesByKey.containsKey(key)) {
            for (Dupe2Utils.DupeCandidate candidate : candidatesByKey.get(key)) {
                try {
                    if (candidate.getDirectory().isHidden(observePrivPath ? user : null)) {
                        continue;
                    }
                    candidates.add(candidate);
                } catch (FileNotFoundException e) {
                    // Ignore entries that disappeared while the command was running.
                }
            }
        }

        CommandResponse response = new CommandResponse(200, "Dupe2 complete");
        response.addComment("Dupe2 key: " + key);
        if (candidates.isEmpty()) {
            response.addComment("No completed releases found for this key.");
            return response;
        }

        Collections.sort(candidates, Collections.reverseOrder());
        Set<Dupe2Utils.DupeCandidate> keepers = Dupe2Utils.getDupeKeepers(candidates);
        response.addComment("Found " + candidates.size() + " completed release(s). Incomplete releases are ignored.");

        int count = 0;
        for (Dupe2Utils.DupeCandidate candidate : candidates) {
            if (count >= limit) {
                response.addComment("Result limit reached (" + limit + ").");
                break;
            }
            String state = keepers.contains(candidate) ? "KEEP" : "DUPE";
            response.addComment(state
                    + " score=" + candidate.getScore()
                    + " bucket=" + candidate.getDisplayBucket()
                    + " section=" + candidate.getSectionName()
                    + " size=" + Bytes.formatBytes(candidate.getSize())
                    + " path=" + candidate.getDirectory().getPath());
            count++;
        }
        return response;
    }

    private String getReleaseName(String argument) {
        int slash = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        if (slash >= 0 && slash < argument.length() - 1) {
            return argument.substring(slash + 1);
        }
        return argument;
    }
}
