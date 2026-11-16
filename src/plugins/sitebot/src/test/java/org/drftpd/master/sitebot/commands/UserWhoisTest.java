package org.drftpd.master.sitebot.commands;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.ImproperUsageException;
import org.drftpd.master.sitebot.ServiceCommand;
import org.drftpd.master.sitebot.SiteBot;
import org.drftpd.master.usermanager.User;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserWhoisTest {
    private final ServiceCommand session = mock(ServiceCommand.class);
    private final SiteBot bot = mock(SiteBot.class);
    private final User actor = mock(User.class);
    private CommandRequest request(String nick) {
        when(session.getUserNull("admin")).thenReturn(actor);
        when(actor.isMemberOf("siteop")).thenReturn(true);
        when(session.getBot()).thenReturn(bot);
        var request = mock(CommandRequest.class);
        when(request.getSession()).thenReturn(session);
        when(request.getUser()).thenReturn("admin");
        when(request.getArgument()).thenReturn(nick);
        return request;
    }

    @Test void returnsCurrentPrimaryGroupForKnownAccount() throws Exception {
        var request = request("puff");
        when(bot.getKnownFtpUser("puff")).thenReturn("G6");
        var global = mock(GlobalContext.class, RETURNS_DEEP_STUBS);
        var target = mock(User.class, RETURNS_DEEP_STUBS);
        when(global.getUserManager().getUserByName("G6")).thenReturn(target);
        when(target.getName()).thenReturn("G6");
        when(target.getGroup().getName()).thenReturn("ROSiN");
        try (var context = mockStatic(GlobalContext.class)) {
            context.when(GlobalContext::getGlobalContext).thenReturn(global);
            new UserHandler().doWhois(request);
            verify(session).printOutput("WHOIS: puff -> G6/ROSiN");
            when(target.getGroup().getName()).thenReturn("NewGroup");
            new UserHandler().doWhois(request);
            verify(session).printOutput("WHOIS: puff -> G6/NewGroup");
            when(target.isDeleted()).thenReturn(true);
            new UserHandler().doWhois(request);
            verify(session).printOutput("WHOIS: puff -> No active DrFTPD account linked.");
        }
    }

    @Test void unknownNickDoesNotGuessAccount() throws Exception {
        new UserHandler().doWhois(request("unknown"));
        verify(session).printOutput("WHOIS: unknown -> No identified DrFTPD account known to this SiteBot.");
    }

    @Test void rejectsNonAdminsEvenIfCommandPermissionsAreMisconfigured() throws Exception {
        var request = request("puff");
        when(actor.isMemberOf("siteop")).thenReturn(false);
        new UserHandler().doWhois(request);
        verify(session).printOutput("WHOIS: Permission denied (siteop only).");
        verifyNoInteractions(bot);
    }

    @Test void rejectsMissingExtraAndControlArguments() {
        for (String argument : new String[]{"", "puff another", "puff\r\n", "x".repeat(129)}) {
            assertThrows(ImproperUsageException.class, () -> new UserHandler().doWhois(request(argument)));
        }
    }
}
