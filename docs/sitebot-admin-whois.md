# Admin IRC account lookup

An identified siteop can run !whois irc_nickname in a channel or private message.
The default response is private: WHOIS: irc_nickname -> ftp_username/example_group.
The group is the linked FTP account's current primary group, not a slave name.

Lookup uses the receiving SiteBot's existing IDENT/INVITE association. It does
not infer identity from nick spelling or issue IRC server WHOIS. Unknown nicks
are reported as unlinked. Silent users after a bot restart may need to identify
again before the bot knows their account. This is not an invitation history or
proof of the person controlling the IRC client. Associations follow existing bot
nick/quit handling. Deleted FTP accounts are not reported as active identities.

Deploy the updated SiteBot master JAR with the master stopped. Add the whois
block from config/commands/irc/sitebot.conf to the running file without replacing
other custom permissions/commands, then restart. No slave or master.conf changes.
The handler also enforces siteop membership if command permissions are loosened.
