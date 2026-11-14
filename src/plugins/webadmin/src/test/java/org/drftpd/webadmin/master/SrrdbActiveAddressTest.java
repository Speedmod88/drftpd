package org.drftpd.webadmin.master;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class SrrdbActiveAddressTest {
    private InetAddress ip(String address) throws Exception { return InetAddress.getByName(address); }

    @Test void loopbackControlDoesNotBecomeAdvertisedAddress() throws Exception {
        var local = ip("192.0.2.15");
        assertEquals(local, SrrdbActiveAddress.choose("", "127.0.0.1", List.of(ip("127.0.0.1"), local)));
    }

    @Test void assignedMasterAddressTakesPriorityButAmbiguousInterfacesFailClearly() throws Exception {
        var a = ip("192.0.2.15"); var b = ip("198.51.100.20");
        assertEquals(b, SrrdbActiveAddress.choose("", "198.51.100.20", List.of(a, b)));
        assertThrows(IOException.class, () -> SrrdbActiveAddress.choose("", "0.0.0.0", List.of(a, b)));
        assertEquals(a, SrrdbActiveAddress.choose("192.0.2.15", "", List.of(a, b)));
    }

    @Test void loopbackWildcardLinkLocalAndNonlocalBindingsAreRejected() throws Exception {
        for (String address : List.of("127.0.0.1", "0.0.0.0", "169.254.1.2", "2001:db8::1")) {
            var addr = ip(address);
            assertThrows(IOException.class, () -> SrrdbActiveAddress.choose(address, "", List.of(addr)));
        }
        assertThrows(IOException.class, () -> SrrdbActiveAddress.choose("192.0.2.15", "", List.of(ip("198.51.100.20"))));
    }

    @Test void advertisedAddressCanDifferForNatButMustBeIpv4() throws Exception {
        var local = (Inet4Address) ip("192.0.2.15");
        assertEquals(local, SrrdbActiveAddress.advertised("", local));
        assertEquals(ip("198.51.100.20"), SrrdbActiveAddress.advertised("198.51.100.20", local));
        assertThrows(IOException.class, () -> SrrdbActiveAddress.advertised("::1", local));
    }

    @Test void defaultModeAndPortRangeValidation() {
        assertFalse(new WebAdminSettings(new Properties()).srrdbFtpActive);
        for (String mode : List.of("broken", "")) {
            var p = new Properties(); p.setProperty("srrdb.ftp.mode", mode);
            assertThrows(IllegalArgumentException.class, () -> new WebAdminSettings(p));
        }
        for (String[] range : List.of(new String[]{"0", "50000"}, new String[]{"50001", "50000"})) {
            var p = new Properties(); p.setProperty("srrdb.ftp.active.port.min", range[0]);
            p.setProperty("srrdb.ftp.active.port.max", range[1]);
            assertThrows(IllegalArgumentException.class, () -> new WebAdminSettings(p));
        }
    }
}
