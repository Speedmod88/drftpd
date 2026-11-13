package org.drftpd.master.indexation;

import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.*;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IndexPathPageTest {
    private void add(IndexWriter writer, String path, String type) throws Exception {
        Document doc = new Document();
        doc.add(new Field("fullPath", path, Field.Store.YES, Field.Index.NOT_ANALYZED));
        doc.add(new Field("type", type, Field.Store.YES, Field.Index.NOT_ANALYZED));
        writer.addDocument(doc);
    }

    @Test void emptyBatchesAdvanceWithoutSkippingMatchesOrDeletedTerms() throws Exception {
        try (var dir = new RAMDirectory();
             var writer = new IndexWriter(dir, new IndexWriterConfig(Version.LUCENE_36, new KeywordAnalyzer()))) {
            for (int i = 0; i < 100; i++) add(writer, String.format("/a/%03d.rar", i), "f");
            add(writer, "/b/folder.nfo", "d");
            add(writer, "/c/deleted.nfo", "f");
            writer.deleteDocuments(new Term("fullPath", "/c/deleted.nfo"));
            add(writer, "/d/one.NFO", "f");
            add(writer, "/e/two.nfo", "f");
            writer.commit();
            try (var reader = IndexReader.open(dir, true)) {
                var first = IndexPathPage.read(reader, "", ".nfo", 1, 7);
                assertTrue(first.paths().isEmpty());
                assertFalse(first.complete());
                assertEquals("/a/006.rar", first.afterPath());
                String cursor = first.afterPath();
                List<String> found = new ArrayList<>();
                boolean complete = false;
                for (int i = 0; i < 30 && !complete; i++) {
                    var page = IndexPathPage.read(reader, cursor, ".nfo", 1, 7);
                    assertTrue(page.paths().size() <= 1);
                    found.addAll(page.paths());
                    cursor = page.afterPath();
                    complete = page.complete();
                }
                assertTrue(complete);
                assertEquals(List.of("/d/one.NFO", "/e/two.nfo"), found);
                assertThrows(IllegalArgumentException.class, () -> IndexPathPage.read(reader, "", ".nfo", 0, 7));
            }
        }
    }
}
