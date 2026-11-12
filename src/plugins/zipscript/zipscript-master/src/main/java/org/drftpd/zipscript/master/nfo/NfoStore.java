package org.drftpd.zipscript.master.nfo;

import com.google.gson.Gson;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.drftpd.slave.NfoReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Private master-side store. Accessed only by the NFO worker, never indexed for text search. */
final class NfoStore implements AutoCloseable {
    private final Gson gson = new Gson();
    private final Directory directory;
    private final IndexWriter writer;
    private final Map<String, Document> pending = new LinkedHashMap<>();
    private IndexReader reader;
    private IndexSearcher searcher;
    private long version, readerVersion = -1;
    private boolean dirty;

    NfoStore(Path path) throws IOException {
        Files.createDirectories(path);
        directory = FSDirectory.open(path.toFile());
        try {
            writer = new IndexWriter(directory, new IndexWriterConfig(Version.LUCENE_36, new KeywordAnalyzer())
                    .setRAMBufferSizeMB(4));
        } catch (IOException | RuntimeException error) { directory.close(); throw error; }
    }

    private void refresh() throws IOException {
        if (reader != null && readerVersion == version) return;
        closeReader();
        reader = IndexReader.open(writer, true);
        searcher = new IndexSearcher(reader);
        readerVersion = version;
    }

    private Document find(String id) throws IOException {
        Document updated = pending.get(id);
        if (updated != null) return updated;
        refresh();
        var hits = searcher.search(new TermQuery(new Term("id", id)), 1);
        return hits.totalHits == 0 ? null : searcher.doc(hits.scoreDocs[0].doc);
    }

    String get(NfoDisplay.Key key) throws IOException {
        Document doc = find("nfo:" + key.path());
        if (doc == null || !gson.toJson(key).equals(doc.get("identity"))) return null;
        byte[] compressed = doc.getBinaryValue("text");
        if (compressed == null) throw new IOException("NFO store record has no text");
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            // CP437 source characters can require three bytes each when stored as UTF-8.
            byte[] bytes = gzip.readNBytes(NfoReader.MAX_BYTES * 3 + 1);
            if (bytes.length > NfoReader.MAX_BYTES * 3) throw new IOException("Stored NFO exceeds size limit");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() > NfoReader.MAX_BYTES) throw new IOException("Stored NFO exceeds text limit");
            return text;
        }
    }

    void put(NfoDisplay.Key key, String text) throws IOException {
        if (text.length() > NfoReader.MAX_BYTES) throw new IOException("NFO exceeds text limit");
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(text.getBytes(StandardCharsets.UTF_8)); }
        Document doc = new Document();
        doc.add(new Field("id", "nfo:" + key.path(), Field.Store.NO, Field.Index.NOT_ANALYZED));
        doc.add(new Field("path", key.path(), Field.Store.YES, Field.Index.NOT_ANALYZED));
        doc.add(new Field("identity", gson.toJson(key), Field.Store.YES, Field.Index.NO));
        doc.add(new Field("text", bytes.toByteArray(), Field.Store.YES));
        stage("nfo:" + key.path(), doc);
    }

    void removeTree(String path) throws IOException {
        if (path.equals("/")) { pending.clear(); writer.deleteAll(); }
        else {
            pending.values().removeIf(doc -> doc.get("path") != null
                    && (doc.get("path").equals(path) || doc.get("path").startsWith(path + "/")));
            writer.deleteDocuments(new TermQuery(new Term("path", path)),
                    new PrefixQuery(new Term("path", path + "/")));
        }
        changed();
    }

    List<NfoDisplay.Key> page(String after, int limit) throws IOException {
        flushPending();
        refresh();
        var hits = searcher.search(new TermRangeQuery("path", after, null, false, false), limit,
                new Sort(new SortField("path", SortField.STRING)));
        List<NfoDisplay.Key> results = new ArrayList<>();
        for (var hit : hits.scoreDocs) results.add(gson.fromJson(searcher.doc(hit.doc).get("identity"), NfoDisplay.Key.class));
        return results;
    }

    String cursor(String name) throws IOException {
        Document doc = find("cursor:" + name);
        return doc == null ? "" : doc.get("value");
    }

    void cursor(String name, String value) throws IOException {
        if (value.equals(cursor(name))) return;
        Document doc = new Document();
        doc.add(new Field("id", "cursor:" + name, Field.Store.NO, Field.Index.NOT_ANALYZED));
        doc.add(new Field("value", value, Field.Store.YES, Field.Index.NO));
        stage("cursor:" + name, doc);
    }

    private void stage(String id, Document doc) throws IOException {
        pending.put(id, doc);
        dirty = true;
        if (pending.size() >= 128) commit();
    }

    private void flushPending() throws IOException {
        if (pending.isEmpty()) return;
        for (var entry : pending.entrySet()) writer.updateDocument(new Term("id", entry.getKey()), entry.getValue());
        pending.clear();
        version++;
    }

    private void changed() { version++; dirty = true; }

    void commit() throws IOException { if (dirty) { flushPending(); writer.commit(); dirty = false; } }

    private void closeReader() throws IOException {
        if (reader != null) {
            try { searcher.close(); }
            finally { reader.close(); reader = null; searcher = null; }
        }
    }

    @Override public void close() throws IOException {
        try { commit(); }
        finally {
            try { closeReader(); }
            finally { try { writer.close(); } finally { directory.close(); } }
        }
    }
}
