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
package org.drftpd.master.sitebot.announce;

import org.bushe.swing.event.annotation.AnnotationProcessor;
import org.bushe.swing.event.annotation.EventSubscriber;
import org.drftpd.master.sections.conf.DatedSectionEvent;
import org.drftpd.master.sitebot.AbstractAnnouncer;
import org.drftpd.master.sitebot.AnnounceWriter;
import org.drftpd.master.sitebot.SiteBot;
import org.drftpd.master.sitebot.config.AnnounceConfig;
import org.drftpd.master.util.ReplacerUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Announces dated section symlink updates.
 */
public class SymlinkAnnouncer extends AbstractAnnouncer {

    private AnnounceConfig _config;

    private ResourceBundle _bundle;

    public void initialise(AnnounceConfig config, ResourceBundle bundle) {
        _config = config;
        _bundle = bundle;

        AnnotationProcessor.process(this);
    }

    public void stop() {
        AnnotationProcessor.unprocess(this);
    }

    public String[] getEventTypes() {
        return new String[]{"symlink"};
    }

    public void setResourceBundle(ResourceBundle bundle) {
        _bundle = bundle;
    }

    @EventSubscriber
    public void onDatedSectionEvent(DatedSectionEvent event) {
        AnnounceWriter writer = _config.getPathWriter("symlink", event.getDirectory());
        if (writer != null) {
            Map<String, Object> env = new HashMap<>(SiteBot.GLOBAL_ENV);
            env.put("section", event.getSection().getName());
            env.put("sectioncolor", event.getSection().getColor());
            env.put("dirname", event.getDirectory().getName());
            env.put("dirpath", event.getDirectory().getPath());
            env.put("linkname", event.getLink().getName());
            env.put("linkpath", event.getLink().getPath());
            env.put("date", event.getDate());
            sayOutput(ReplacerUtils.jprintf("symlink", env, _bundle), writer);
        }
    }
}
