package org.drftpd.webadmin.master;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Active listeners must bind a real local address, never the loopback control peer. */
final class SrrdbActiveAddress {
    static Inet4Address choose(WebAdminSettings settings, Properties master) throws IOException {
        Set<InetAddress> local = new LinkedHashSet<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface nic : interfaces == null ? List.<NetworkInterface>of() : Collections.list(interfaces)) {
            if (nic.isUp() && !nic.isLoopback()) local.addAll(Collections.list(nic.getInetAddresses()));
        }
        return choose(settings.srrdbFtpActiveAddress, master.getProperty("master.ip", "").trim(), local);
    }

    static Inet4Address advertised(String host, Inet4Address local) throws IOException {
        if (host.isEmpty()) return local;
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address instanceof Inet4Address v4 && !v4.isAnyLocalAddress() && !v4.isLoopbackAddress()
                    && !v4.isMulticastAddress() && !v4.isLinkLocalAddress()) return v4;
        }
        throw new IOException("srrdb.ftp.active.advertised.host must resolve to a reachable non-loopback IPv4 address");
    }

    static Inet4Address choose(String configured, String masterAddress, Collection<InetAddress> addresses) throws IOException {
        List<Inet4Address> candidates = addresses.stream().filter(a -> a instanceof Inet4Address
                        && !a.isLoopbackAddress() && !a.isAnyLocalAddress() && !a.isLinkLocalAddress())
                .map(a -> (Inet4Address) a).distinct().toList();
        if (!configured.isEmpty()) {
            InetAddress chosen = InetAddress.getByName(configured);
            if (!(chosen instanceof Inet4Address) || !candidates.contains(chosen))
                throw new IOException("srrdb.ftp.active.address must identify a non-loopback IPv4 address assigned to this master/container");
            return (Inet4Address) chosen;
        }
        if (!masterAddress.isEmpty()) {
            for (InetAddress address : InetAddress.getAllByName(masterAddress)) {
                if (candidates.contains(address)) return (Inet4Address) address;
            }
        }
        if (candidates.size() == 1) return candidates.get(0);
        throw new IOException("Cannot choose an unambiguous active FTPS address; configure srrdb.ftp.active.address "
                + "with the master's reachable local IPv4 address (candidates=" + candidates + ")");
    }
}
