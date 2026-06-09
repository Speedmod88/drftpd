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

import java.util.Map;

public class Dupe2Option implements OptionInterface {

    private final Map<String, String> _options = Map.of(
            "dupe2", "[text] # Filter to completed duplicate release losers using AutoFreeSpace Dupe2 scoring"
    );

    @Override
    public Map<String, String> getOptions() {
        return _options;
    }

    @Override
    public void executeOption(String option, String[] args, AdvancedSearchParams params, FindSettings settings)
            throws ImproperUsageException {
        settings.setDupe2Enabled(true);
        String requiredText = args == null || args.length == 0 ? null : args[0].trim();
        settings.setDupe2RequiredText(requiredText == null || requiredText.equals("") ? null : requiredText);
        params.setInodeType(AdvancedSearchParams.InodeType.DIRECTORY);
    }
}
