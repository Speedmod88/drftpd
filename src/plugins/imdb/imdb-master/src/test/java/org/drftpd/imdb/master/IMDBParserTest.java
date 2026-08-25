package org.drftpd.imdb.master;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IMDBParserTest {
    private static final String TITLE_RESPONSE = """
            {
              "id": "tt0111161",
              "type": "movie",
              "primaryTitle": "The Shawshank Redemption",
              "originalTitle": null,
              "startYear": 1994,
              "runtimeSeconds": 8520,
              "genres": ["Drama", "Crime"],
              "plot": "A banker is sent to prison.",
              "rating": {
                "aggregateRating": 9.3,
                "voteCount": 3229001
              },
              "directors": [{"displayName": "Frank Darabont"}],
              "originCountries": [{"name": "United States"}],
              "spokenLanguages": [{"name": "English"}, {"name": "Italian"}]
            }
            """;

    @Test
    void searchesTiffaraAndMapsMovieDetails() {
        List<String> requestedUrls = new ArrayList<>();
        IMDBParser parser = new IMDBParser("https://metadata.example/", url -> {
            requestedUrls.add(url);
            if (url.contains("/search/titles")) {
                return """
                        {
                          "titles": [
                            {"id":"tt0903747", "type":"tvSeries", "primaryTitle":"Breaking Bad"},
                            {"id":"tt0111161", "type":"movie", "primaryTitle":"The Shawshank Redemption"}
                          ]
                        }
                        """;
            }
            return TITLE_RESPONSE;
        });

        parser.doSEARCH("The+Shawshank+Redemption");

        assertTrue(parser.foundMovie());
        assertEquals("The Shawshank Redemption", parser.getTitle());
        assertEquals(1994, parser.getYear());
        assertEquals("English|Italian", parser.getLanguage());
        assertEquals("United States", parser.getCountry());
        assertEquals("Frank Darabont", parser.getDirector());
        assertEquals("Drama|Crime", parser.getGenres());
        assertEquals("A banker is sent to prison.", parser.getPlot());
        assertEquals(93, parser.getRating());
        assertEquals(3229001, parser.getVotes());
        assertEquals(142, parser.getRuntime());
        assertEquals("https://www.imdb.com/title/tt0111161", parser.getURL());
        assertEquals("https://metadata.example/search/titles?query=The+Shawshank+Redemption&limit=10", requestedUrls.get(0));
        assertEquals("https://metadata.example/titles/tt0111161", requestedUrls.get(1));
    }

    @Test
    void resolvesAnImdbUrlDirectlyThroughConfiguredApi() {
        List<String> requestedUrls = new ArrayList<>();
        IMDBParser parser = new IMDBParser("https://new-api.example/v1", url -> {
            requestedUrls.add(url);
            return TITLE_RESPONSE;
        });

        parser.doNFO("http://imdb.com/title/TT0111161/reference");

        assertTrue(parser.foundMovie());
        assertEquals(List.of("https://new-api.example/v1/titles/tt0111161"), requestedUrls);
    }

    @Test
    void rejectsSearchResultsWithoutMovieTypes() {
        IMDBParser parser = new IMDBParser("https://metadata.example", url -> """
                {"titles":[{"id":"tt0903747", "type":"tvSeries", "primaryTitle":"Breaking Bad"}]}
                """);

        parser.doSEARCH("Breaking+Bad");

        assertFalse(parser.foundMovie());
    }
}
