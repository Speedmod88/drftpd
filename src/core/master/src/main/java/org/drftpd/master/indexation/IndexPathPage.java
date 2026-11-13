package org.drftpd.master.indexation;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A bounded dictionary walk, not a range query or a sorted search of all remaining paths. */
public record IndexPathPage(List<String> paths, String afterPath, boolean complete) {
    public IndexPathPage { paths = List.copyOf(paths); }

    static IndexPathPage read(IndexReader reader, String after, String suffix, int limit, int budget)
            throws IOException {
        if (limit < 1 || budget < 1) throw new IllegalArgumentException("Positive scan limits required");
        String cursor = after;
        String ending = suffix.toLowerCase(Locale.ROOT);
        List<String> paths = new ArrayList<>();
        TermEnum terms = reader.terms(new Term("fullPath", after));
        TermDocs docs = reader.termDocs();
        try {
            int visited = 0;
            Term term = terms.term();
            if (term != null && term.field().equals("fullPath") && term.text().equals(after)) {
                terms.next();
                term = terms.term();
            }
            while (term != null && term.field().equals("fullPath")
                    && visited < budget && paths.size() < limit) {
                cursor = term.text();
                visited++;
                if (cursor.toLowerCase(Locale.ROOT).endsWith(ending)) {
                    docs.seek(term);
                    while (docs.next()) {
                        if ("f".equals(reader.document(docs.doc()).get("type"))) {
                            paths.add(cursor);
                            break;
                        }
                    }
                }
                terms.next();
                term = terms.term();
            }
            return new IndexPathPage(paths, cursor, term == null || !term.field().equals("fullPath"));
        } finally {
            try { docs.close(); } finally { terms.close(); }
        }
    }
}
