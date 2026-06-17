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
package org.drftpd.find.master.option;

import org.drftpd.find.master.FindSettings;
import org.drftpd.master.commands.ImproperUsageException;
import org.drftpd.master.indexation.AdvancedSearchParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TagOption implements OptionInterface {

    private final Map<String, String> _options = Map.of(
            "tag", "<tag> [replacement-tag ...] # With -dupe2, select duplicate releases with tag replaced by another tag"
    );

    @Override
    public Map<String, String> getOptions() {
        return _options;
    }

    @Override
    public void executeOption(String option, String[] args, AdvancedSearchParams params, FindSettings settings)
            throws ImproperUsageException {
        if (args == null || args.length == 0 || args[0].trim().equals("")) {
            throw new ImproperUsageException("Missing argument for " + option + " option");
        }
        settings.setDupe2RequiredText(args[0].trim());
        settings.setDupe2ReplacementTexts(getReplacementTexts(args));
        params.setInodeType(AdvancedSearchParams.InodeType.DIRECTORY);
    }

    private List<String> getReplacementTexts(String[] args) {
        List<String> replacementTexts = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String replacementText = args[i].trim();
            if (!replacementText.equals("")) {
                replacementTexts.add(replacementText);
            }
        }
        return replacementTexts;
    }
}
