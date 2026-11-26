package org.drftpd.master.sitebot.announce;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.event.DirectoryFtpEvent;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.sections.conf.DatedSection;
import org.drftpd.master.sitebot.AnnounceWriter;
import org.drftpd.master.sitebot.config.AnnounceConfig;
import org.drftpd.master.sitebot.config.PathMatcher;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.vfs.DirectoryHandle;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.ListResourceBundle;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WipeDatedirAnnouncementTest {
    @Test void retainsActualDateFolderForAllDatedSectionsAndLegacyDateFormats() {
        for (String sectionName : List.of("EBOOKS-FRENCH", "MP3-US", "FLAC-FR", "0DAYS")) {
            var section = mock(DatedSection.class);
            when(section.getBaseDirectory()).thenReturn(new DirectoryHandle("/" + sectionName));
            var writer = new AnnounceWriter(new PathMatcher("/" + sectionName + "/****-****/*", false), new ArrayList<>(), sectionName);
            for (String date : List.of("2023-10-25", "2023-1027", "2023-1108")) {
                var dir = new DirectoryHandle("/" + sectionName + "/" + date);
                assertEquals(date, BasicAnnouncer.directoryEventPath(new DirectoryFtpEvent(null, "WIPE", dir), writer, section));
            }
        }
    }

    @Test void preservesReleaseAndOtherEventFormatting() {
        var section = mock(DatedSection.class);
        when(section.getBaseDirectory()).thenReturn(new DirectoryHandle("/MP3-US"));
        var writer = new AnnounceWriter(new PathMatcher("/MP3-US/****-****/*", false), new ArrayList<>(), "MP3-US");
        var release = new DirectoryHandle("/MP3-US/2023-1027/Release-GRP");
        for (String command : List.of("WIPE", "MKD", "RMD"))
            assertEquals("Release-GRP", BasicAnnouncer.directoryEventPath(new DirectoryFtpEvent(null, command, release), writer, section));
    }

    @Test void emptyAndOvertruncatedCustomPathsFallbackOnlyForWipe() {
        var section = mock(SectionInterface.class);
        var writer = mock(AnnounceWriter.class);
        var dir = new DirectoryHandle("/SECTION/2023-1027");
        var wipe = new DirectoryFtpEvent(null, "WIPE", dir);
        when(writer.getPath(dir)).thenReturn("");
        assertEquals("2023-1027", BasicAnnouncer.directoryEventPath(wipe, writer, section));
        assertEquals("", BasicAnnouncer.directoryEventPath(new DirectoryFtpEvent(null, "MKD", dir), writer, section));
        when(writer.getPath(dir)).thenThrow(new StringIndexOutOfBoundsException());
        assertEquals("2023-1027", BasicAnnouncer.directoryEventPath(wipe, writer, section));
        assertThrows(StringIndexOutOfBoundsException.class, () -> BasicAnnouncer.directoryEventPath(new DirectoryFtpEvent(null, "MKD", dir), writer, section));
    }

    @Test void completedWipeEventRendersDateNameWithoutReopeningDeletedDirectory() throws Exception {
        var dir = new DirectoryHandle("/EBOOKS-FRENCH/2023-1027");
        var section = mock(DatedSection.class);
        when(section.getBaseDirectory()).thenReturn(new DirectoryHandle("/EBOOKS-FRENCH"));
        when(section.getColor()).thenReturn("03");
        var actor = mock(User.class, RETURNS_DEEP_STUBS);
        when(actor.getName()).thenReturn("ftp_username");
        when(actor.getGroup().toString()).thenReturn("group");
        var writer = mock(AnnounceWriter.class);
        when(writer.getSectionName(dir)).thenReturn("EBOOKS-FRENCH");
        var config = mock(AnnounceConfig.class);
        when(config.getPathWriter("wipe", dir)).thenReturn(writer);
        var announcer = new Capture();
        var field = BasicAnnouncer.class.getDeclaredField("_config");
        field.setAccessible(true); field.set(announcer, config);
        announcer.setResourceBundle(new ListResourceBundle() {
            protected Object[][] getContents() {
                return new Object[][]{{"wipe", "WIPE in ${section}: -> ${path} was wiped by ${user}/${group}."}};
            }
        });
        var global = mock(GlobalContext.class, RETURNS_DEEP_STUBS);
        when(global.getSectionManager().lookup(dir)).thenReturn(section);
        try (var context = mockStatic(GlobalContext.class)) {
            context.when(GlobalContext::getGlobalContext).thenReturn(global);
            announcer.onDirectoryFtpEvent(new DirectoryFtpEvent(actor, "WIPE", dir));
        }
        assertEquals("WIPE in EBOOKS-FRENCH: -> 2023-1027 was wiped by ftp_username/group.", announcer.output);
        verify(writer, never()).getPath(dir);
    }

    private static class Capture extends BasicAnnouncer {
        String output;
        @Override protected void sayOutput(String message, AnnounceWriter writer) { output = message; }
    }
}
