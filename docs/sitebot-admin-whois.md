# Admin IRC account lookup

An identified siteop can run !whois irc_nickname only in the configured sysop channel.
The response stays in that channel: WHOIS: irc_nickname -> ftp_username/example_group.
The group is the linked FTP account's current primary group, not a slave name.

Lookup uses the receiving SiteBot's existing IDENT/INVITE association. It does
not infer identity from nick spelling or issue IRC server WHOIS. Unknown nicks
are reported as unlinked. Silent users after a bot restart may need to identify
again before the bot knows their account. This is not an invitation history or
proof of the person controlling the IRC client. Associations follow existing bot
nick/quit handling. Deleted FTP accounts are not reported as active identities.

Set channel and input in the IRC whois block to the actual sysop channel (the
distributed placeholder is #sysop), and keep output source. The handler enforces
that source and sole output destination match channel; PMs and other channels
cannot disclose identities even if input is accidentally broadened.

FTP siteops can run SITE WHOIS irc_nickname, or SITE WHOIS irc_nickname BotName when multiple
bots are loaded. The result is returned on the FTP control connection, not IRC.

Deploy the updated SiteBot master JAR with the master stopped. Merge the whois
blocks from config/commands/irc/sitebot.conf and config/commands/ftp/sitebot.conf
into the running files without replacing other custom permissions/commands.
Updated help.specific lines for fixlinks are in config/commands/irc/core.conf
and config/commands/ftp/core.conf. Then restart. No slave or master.conf changes.
The handler enforces siteop membership independently of command permissions.
