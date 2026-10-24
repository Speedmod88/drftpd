package org.drftpd.zipscript.master;

import com.cedarsoftware.util.io.JsonReader;
import com.cedarsoftware.util.io.JsonWriter;
import org.drftpd.common.dynamicdata.Key;
import org.drftpd.common.dynamicdata.KeyedMap;
import org.drftpd.common.vfs.CaseInsensitiveTreeMap;
import org.drftpd.master.vfs.VirtualFileSystemFile;
import org.drftpd.zipscript.common.sfv.SFVInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionPersistenceTest {
    @Test void sfvMetadataAndCrcSurviveVfsJsonRoundTrip() throws Exception {
        SFVInfo info = new SFVInfo();
        info.setSFVFileName("release.sfv");
        info.setChecksum(123L);
        var entries = new CaseInsensitiveTreeMap<String, Long>();
        entries.put("release.rar", 456L);
        info.setEntries(entries);
        KeyedMap<Key<?>, Object> metadata = new KeyedMap<>();
        metadata.setObject(SFVInfo.SFVINFO, info);
        metadata.setObject(VirtualFileSystemFile.CRC, 123L);
        @SuppressWarnings("unchecked")
        KeyedMap<Key<?>, Object> restored = (KeyedMap<Key<?>, Object>)
                JsonReader.jsonToJava(JsonWriter.objectToJson(metadata));
        assertEquals(123L, restored.getObjectLong(VirtualFileSystemFile.CRC));
        SFVInfo sfv = restored.getObject(SFVInfo.SFVINFO);
        assertEquals("release.sfv", sfv.getSFVFileName());
        assertEquals(123L, sfv.getChecksum());
        assertEquals(456L, sfv.getEntries().get("release.rar"));
    }
}
