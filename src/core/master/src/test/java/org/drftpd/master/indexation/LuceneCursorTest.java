package org.drftpd.master.indexation;

import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.drftpd.master.vfs.DirectoryHandle;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LuceneCursorTest {
    @Test void exclusiveCursorVisitsEveryPathOnceDespiteForegroundSortChanges() throws Exception {
        try (RAMDirectory directory = new RAMDirectory();
             IndexWriter writer = new IndexWriter(directory,
                     new IndexWriterConfig(Version.LUCENE_36, new KeywordAnalyzer()))) {
            for (String path : List.of("/B/file", "/a/file", "/A/file", "/a/other")) {
                Document doc = new Document();
                doc.add(new Field("fullPath", path, Field.Store.YES, Field.Index.NOT_ANALYZED));
                doc.add(new Field("type", "f", Field.Store.YES, Field.Index.NOT_ANALYZED));
                writer.addDocument(doc);
            }
            LuceneEngine engine = new LuceneEngine();
            var field = LuceneEngine.class.getDeclaredField("_iWriter");
            field.setAccessible(true); field.set(engine, writer);
            DirectoryHandle root = new DirectoryHandle("/");
            List<String> seen = new ArrayList<>();
            String cursor = "";
            for (int i = 0; i < 3; i++) {
                AdvancedSearchParams foreground = new AdvancedSearchParams();
                foreground.setInodeType(AdvancedSearchParams.InodeType.FILE);
                foreground.setLimit(1); foreground.setSortOrder(true);
                engine.advancedFind(root, foreground, "test-foreground");
                AdvancedSearchParams params = new AdvancedSearchParams();
                params.setInodeType(AdvancedSearchParams.InodeType.FILE);
                params.setLimit(2); params.setAfterPath(cursor);
                var page = engine.advancedFind(root, params, "test-cursor");
                seen.addAll(page.keySet());
                if (!page.isEmpty()) cursor = seen.get(seen.size() - 1);
            }
            assertEquals(List.of("/A/file", "/B/file", "/a/file", "/a/other"), seen);
        }
    }
}
