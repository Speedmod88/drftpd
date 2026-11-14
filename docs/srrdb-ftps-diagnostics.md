# srrDB FTPS diagnostics

Recovery uploads now write INFO-level trace records under the existing
org.drftpd.webadmin.srrdb logger, normally logs/srrdb.log. Each attempt has a UUID
trace ID and elapsed times. No configuration changes or DEBUG logging are needed.

Records include automatic/configured master endpoint selection, configured
timeouts, control TCP endpoints and negotiated TLS, FTP command names and safe
arguments, reply codes and command durations, DrFTPD's selected-slave PASV comment,
advertised data endpoint, data-socket preparation and negotiated TLS, verification,
failure stage and deadline cancellation. Local data endpoints are unknown until
a socket has been established; control loopback addresses are not presented as
the data connection's source.

Interpret command ordering carefully. A PASV 227 followed by failure with no
STOR command points to data connection setup before STOR. If STOR was sent, TCP
setup got further and its reply/handshake must be examined. Commons Net calls
the data preparation hook after TCP connection AND the preliminary STOR/RETR
reply, before data TLS. dataSocketReady=false alone does not prove TCP never
connected. data-ready confirms the data socket/handshake returned successfully.

USER/PASS/ACCT/ADAT and unrecognized command arguments are never logged. Server
reply bodies are omitted except PASV 227 (for slave selection and endpoint).
This avoids dumping greetings, authentication challenges or CWD NFO contents.
Trace text redacts the configured password, removes control characters and is
length-limited. No file payloads or WebAdmin tokens are recorded. Server/slave
addresses and release paths are diagnostics and may still be private; redact
those when sharing logs publicly.

Only the WebAdmin master JAR changes. Stop the master, replace it and restart.
No slave changes, retries, route overrides, credential reuse, timeout adjustments,
selection changes or changes to PRET/PASV/PROT P transfer behavior are introduced.
