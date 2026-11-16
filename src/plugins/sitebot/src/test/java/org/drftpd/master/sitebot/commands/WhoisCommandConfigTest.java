package org.drftpd.master.sitebot.commands;

import org.drftpd.master.GlobalContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhoisCommandConfigTest {
    @Test void ircRegistrationIsChannelOnlyAndHelpDocumentsBothCommands() {
        var irc = GlobalContext.loadCommandConfig("src/main/resources/master/config/commands/irc/");
        var whois = irc.get("whois");
        assertNotNull(whois);
        assertEquals("=siteop", whois.getProperty("perms"));
        assertEquals("#sysop", whois.getProperty("channel"));
        assertEquals("#sysop", whois.getProperty("input"));
        assertEquals("source", whois.getProperty("output"));
        assertTrue(whois.getProperty("help.specific").contains("IDENT/INVITE"));
        var ftp = GlobalContext.loadCommandConfig("src/main/resources/master/config/commands/ftp/");
        var ftpWhois = ftp.get("site whois");
        assertNotNull(ftpWhois);
        assertEquals("=siteop", ftpWhois.getProperty("perms"));
        assertTrue(ftpWhois.getProperty("help.specific").contains("[botname]"));
        assertTrue(ftpWhois.getProperty("help.specific").contains("ftp_username/group"));
    }
}
