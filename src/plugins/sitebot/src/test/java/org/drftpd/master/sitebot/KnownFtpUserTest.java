package org.drftpd.master.sitebot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnownFtpUserTest {
    @Test void looksUpOnlyExistingBotScopedNickAssociationsCaseInsensitively() throws Exception {
        var bot = new SiteBot("unused/");
        var field = SiteBot.class.getDeclaredField("_users");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var users = (java.util.Map<String, UserDetails>) field.get(bot);
        var details = mock(UserDetails.class);
        when(details.getFtpUser()).thenReturn("ftp_username");
        users.put("irc_nickname", details);
        assertEquals("ftp_username", bot.getKnownFtpUser("irc_nickname"));
        assertNull(bot.getKnownFtpUser("unknown"));
        assertNull(new SiteBot("another/").getKnownFtpUser("irc_nickname"));
        users.remove("irc_nickname");
        assertNull(bot.getKnownFtpUser("irc_nickname"));
    }
}
