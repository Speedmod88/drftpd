package org.drftpd.autofreespace.master;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.drftpd.common.dynamicdata.Key;
import org.drftpd.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.indexation.*;
import java.util.Set;
import static org.apache.lucene.search.BooleanClause.Occur.*;

public class ReleaseCatalogueQuery implements QueryTermExtensionInterface {
    private static final Key<Set<String>> KEYS = new Key<>(ReleaseCatalogueQuery.class, "keys");

    static void keys(AdvancedSearchParams params, Set<String> keys) {
        params.addExtensionData(KEYS, Set.copyOf(keys));
    }

    public void addQueryTerms(BooleanQuery query, AdvancedSearchParams params) {
        try {
            query.add(build(params.getExtensionData(KEYS),
                    ReleaseCatalogue.version(AutoFreeSpaceSettings.getSettings().getDupeMarkerRegex())), MUST);
        } catch (KeyNotFoundException ignored) { }
    }

    static Query build(Set<String> keys, String version) {
        BooleanQuery exactKeys = new BooleanQuery(), oldNames = new BooleanQuery();
        for (String key : keys) {
            exactKeys.add(new TermQuery(new Term(ReleaseCatalogue.KEY, key)), SHOULD);
            oldNames.add(LuceneUtils.analyze("name", new Term("name", ""), key.replace('.', ' ')), SHOULD);
        }
        exactKeys.setMinimumNumberShouldMatch(1); oldNames.setMinimumNumberShouldMatch(1);
        Query schema = new TermQuery(new Term(ReleaseCatalogue.SCHEMA, version));
        BooleanQuery current = new BooleanQuery(), legacy = new BooleanQuery(), combined = new BooleanQuery();
        current.add(schema, MUST); current.add(exactKeys, MUST);
        // Old documents or changed parsing rules still work before background backfill reaches them.
        legacy.add(schema, MUST_NOT); legacy.add(oldNames, MUST);
        combined.add(current, SHOULD); combined.add(legacy, SHOULD);
        combined.setMinimumNumberShouldMatch(1);
        return combined;
    }
}
