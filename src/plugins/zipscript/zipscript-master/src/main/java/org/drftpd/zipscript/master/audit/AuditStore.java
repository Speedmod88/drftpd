package org.drftpd.zipscript.master.audit;

import com.google.gson.Gson;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Separate durable audit database; never exported into the FTP VFS. */
final class AuditStore implements AutoCloseable {
    private final Gson gson = new Gson();
    private final Directory directory;
    private final IndexWriter writer;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private IndexReader reader;
    private IndexSearcher searcher;

    AuditStore(Path path) throws IOException {
        Files.createDirectories(path);
        directory = FSDirectory.open(path.toFile());
        try {
            writer = new IndexWriter(directory, new IndexWriterConfig(Version.LUCENE_36, new KeywordAnalyzer())
                    .setRAMBufferSizeMB(4));
        } catch (IOException | RuntimeException e) { directory.close(); throw e; }
    }

    <T> T get(String id, Class<T> type) throws IOException {
        String updated = pending.get(id);
        if (updated != null) return gson.fromJson(updated, type);
        if (reader == null) {
            reader = IndexReader.open(writer, true);
            searcher = new IndexSearcher(reader);
        }
        var hits = searcher.search(new TermQuery(new Term("id", id)), 1);
        return hits.totalHits == 0 ? null : gson.fromJson(searcher.doc(hits.scoreDocs[0].doc).get("data"), type);
    }

    void put(String id, Object value) throws IOException {
        pending.put(id, gson.toJson(value));
        if (pending.size() >= 512) commit();
    }

    void commit() throws IOException {
        if (pending.isEmpty()) return;
        for (var entry : pending.entrySet()) {
            Document doc = new Document();
            doc.add(new Field("id", entry.getKey(), Field.Store.YES, Field.Index.NOT_ANALYZED));
            doc.add(new Field("data", entry.getValue(), Field.Store.YES, Field.Index.NO));
            writer.updateDocument(new Term("id", entry.getKey()), doc);
        }
        writer.commit();
        closeReader();
        pending.clear();
    }

    private void closeReader() throws IOException {
        if (reader != null) {
            try { searcher.close(); } finally { reader.close(); reader = null; searcher = null; }
        }
    }

    public void close() throws IOException {
        try { commit(); }
        finally {
            try { closeReader(); }
            finally { try { writer.close(); } finally { directory.close(); } }
        }
    }
}
