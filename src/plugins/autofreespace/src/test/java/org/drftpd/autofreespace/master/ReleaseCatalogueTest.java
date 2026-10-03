package org.drftpd.autofreespace.master;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseCatalogueTest {
    private static final String REGEX = AutoFreeSpaceSettings.DEFAULT_DUPE_MARKER_REGEX;

    @Test void preservesEpisodesYearsAndTitlesButNotVariantTags() {
        assertEquals("the.bold.type.2017.s01e01",
                ReleaseCatalogue.identity("The.Bold.Type.2017.S01E01.FRENCH.DVDRIP.X264-UPRiSiNG", REGEX));
        assertEquals("show.e001", ReleaseCatalogue.identity("Show.E001.MULTI.1080p.WEB-GRP", REGEX));
        assertNotEquals(ReleaseCatalogue.identity("Show.S01E01.MULTI-GRP", REGEX),
                ReleaseCatalogue.identity("Show.S01E02.MULTI-GRP", REGEX));
        assertEquals("batman.2", ReleaseCatalogue.identity("Batman.2.SUBFRENCH.2160p-GRP", REGEX));
        assertEquals("game.title", ReleaseCatalogue.identity("Game.Title.MULTI-GRP", REGEX));
        assertNull(ReleaseCatalogue.identity("1080P", REGEX));
    }

    @Test void invalidMarkerDoesNotBreakIndexing() {
        assertEquals("show.multi.grp", ReleaseCatalogue.identity("Show.MULTI-GRP", "["));
        assertNotEquals(ReleaseCatalogue.version(REGEX), ReleaseCatalogue.version(REGEX + "|TEST"));
    }

    private static Document document(String name, String key, String version) {
        Document doc = new Document();
        doc.add(new Field("name", name, Field.Store.YES, Field.Index.ANALYZED));
        if (key != null) doc.add(new Field(ReleaseCatalogue.KEY, key, Field.Store.YES, Field.Index.NOT_ANALYZED));
        if (version != null) doc.add(new Field(ReleaseCatalogue.SCHEMA, version, Field.Store.YES, Field.Index.NOT_ANALYZED));
        return doc;
    }

    @Test void exactKeysExcludeSequelsWhileLegacyDocumentsStillMatch() throws Exception {
        String version = ReleaseCatalogue.version(REGEX);
        try (RAMDirectory directory = new RAMDirectory();
             IndexWriter writer = new IndexWriter(directory,
                     new IndexWriterConfig(Version.LUCENE_36, new StandardAnalyzer(Version.LUCENE_36)))) {
            writer.addDocument(document("batman multi", "batman", version));
            writer.addDocument(document("batman 2 multi", "batman.2", version));
            writer.addDocument(document("batman french", null, null));
            writer.addDocument(document("batman subfrench", "old-key", "old-schema"));
            try (IndexReader reader = IndexReader.open(writer, true)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                assertEquals(3, searcher.search(ReleaseCatalogueQuery.build(Set.of("batman"), version), 10).totalHits);
                assertEquals(4, searcher.search(ReleaseCatalogueQuery.build(Set.of("batman", "batman.2"), version), 10).totalHits);
                searcher.close();
            }
        }
    }
}
