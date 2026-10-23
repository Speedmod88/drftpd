package org.drftpd.master.sitebot.config;

import org.drftpd.master.sitebot.AnnounceWriter;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnounceConfigExplicitWriterTest {
    @Test void administrativeOutputDoesNotFallBackToPublic() throws Exception {
        AnnounceConfig config = mock(AnnounceConfig.class, CALLS_REAL_METHODS);
        AnnounceWriter publicWriter = mock(AnnounceWriter.class);
        AnnounceWriter sysopWriter = mock(AnnounceWriter.class);
        HashMap<String, AnnounceWriter> writers = new HashMap<>();
        writers.put("default", publicWriter);
        var field = AnnounceConfig.class.getDeclaredField("_simpleWriters");
        field.setAccessible(true);
        field.set(config, writers);
        assertSame(publicWriter, config.getSimpleWriter("sysop"));
        assertNull(config.getExplicitSimpleWriter("sysop"));
        writers.put("sysop", sysopWriter);
        assertSame(sysopWriter, config.getExplicitSimpleWriter("sysop"));
    }
}
