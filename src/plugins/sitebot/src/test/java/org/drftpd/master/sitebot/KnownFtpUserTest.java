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
        when(details.getFtpUser()).thenReturn("G6");
        users.put("puff", details);
        assertEquals("G6", bot.getKnownFtpUser("PUFF"));
        assertNull(bot.getKnownFtpUser("unknown"));
        assertNull(new SiteBot("another/").getKnownFtpUser("puff"));
        users.remove("puff");
        assertNull(bot.getKnownFtpUser("puff"));
    }
}
