/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.imdb.master;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.master.sitebot.SiteBot;
import org.drftpd.master.util.HttpUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieves IMDb metadata through the Tiffara JSON API.
 *
 * @author lh
 */
public class IMDBParser {
    private static final Logger logger = LogManager.getLogger(IMDBParser.class);
    private static final String IMDB_TITLE_URL = "https://www.imdb.com/title/";
    private static final Pattern IMDB_ID_PATTERN = Pattern.compile("(tt\\d+)", Pattern.CASE_INSENSITIVE);
    private static final int SEARCH_LIMIT = 10;

    private final String _apiBaseUrl;
    private final HttpRetriever _httpRetriever;

    private String _title;
    private Integer _year;
    private String _language;
    private String _country;
    private String _director;
    private String _genres;
    private String _plot;
    private Integer _rating;
    private Integer _votes;
    private String _url;
    private Integer _runtime;
    private String _searchString;
    private boolean _foundMovie;

    public IMDBParser() {
        this(IMDBConfig.getInstance().getTiffaraApiUrl(), HttpUtils::retrieveHttpAsString);
    }

    IMDBParser(String apiBaseUrl, HttpRetriever httpRetriever) {
        _apiBaseUrl = normalizeApiBaseUrl(apiBaseUrl);
        _httpRetriever = httpRetriever;
    }

    public String getTitle() { return foundMovie() ? _title : "N|A"; }

    public Integer getYear() { return foundMovie() ? _year : null; }

    public String getLanguage() { return foundMovie() ? _language : "N|A"; }

    public String getCountry() { return foundMovie() ? _country : "N|A"; }

    public String getDirector() { return foundMovie() ? _director : "N|A"; }

    public String getGenres() { return foundMovie() ? _genres : "N|A"; }

    public String getPlot() { return foundMovie() ? _plot : "N|A"; }

    public Integer getRating() { return foundMovie() ? _rating : null; }

    public Integer getVotes() { return foundMovie() ? _votes : null; }

    public String getURL() { return foundMovie() ? _url : "N|A"; }

    public Integer getRuntime() { return foundMovie() ? _runtime : null; }

    public boolean foundMovie() { return _foundMovie; }

    public void doSEARCH(String searchString) {
        resetResult();
        _searchString = searchString;
        if (searchString == null || searchString.isBlank()) {
            return;
        }

        String searchUrl = _apiBaseUrl + "/search/titles?query=" + searchString + "&limit=" + SEARCH_LIMIT;
        try {
            JsonObject response = retrieveJsonObject(searchUrl);
            JsonArray titles = getArray(response, "titles");
            if (titles == null) {
                return;
            }

            for (JsonElement element : titles) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = element.getAsJsonObject();
                if (!isMovieType(getString(candidate, "type"))) {
                    continue;
                }
                String id = getString(candidate, "id");
                if (isImdbId(id)) {
                    _foundMovie = loadTitle(id);
                    return;
                }
            }
        } catch (Exception e) {
            logLookupFailure(searchUrl, e);
        }
    }

    public void doNFO(String url) {
        resetResult();
        String id = extractImdbId(url);
        if (id == null) {
            logger.warn("Unable to extract an IMDb title ID from URL: {}", url);
            return;
        }
        _foundMovie = loadTitle(id);
    }

    private boolean loadTitle(String id) {
        id = id.toLowerCase(Locale.ROOT);
        String titleUrl = _apiBaseUrl + "/titles/" + id;
        try {
            JsonObject title = retrieveJsonObject(titleUrl);
            String type = getString(title, "type");
            if (!isMovieType(type)) {
                logger.warn("Request for IMDb info for unsupported title type '{}', ID: {}", type, id);
                return false;
            }

            _title = firstNonBlank(getString(title, "primaryTitle"), getString(title, "originalTitle"));
            if (_title == null) {
                logger.warn("Tiffara response did not contain a title for IMDb ID: {}", id);
                return false;
            }

            _url = IMDB_TITLE_URL + id.toLowerCase(Locale.ROOT);
            _year = getInteger(title, "startYear");
            _language = joinObjectNames(getArray(title, "spokenLanguages"));
            _country = joinObjectNames(getArray(title, "originCountries"));
            _director = joinObjectNames(getArray(title, "directors"));
            _genres = joinStrings(getArray(title, "genres"));
            _plot = valueOrNotAvailable(getString(title, "plot"));

            JsonObject rating = getObject(title, "rating");
            if (rating != null) {
                Double aggregateRating = getDouble(rating, "aggregateRating");
                if (aggregateRating != null) {
                    _rating = (int) Math.round(aggregateRating * 10);
                }
                _votes = getInteger(rating, "voteCount");
            }

            Integer runtimeSeconds = getInteger(title, "runtimeSeconds");
            if (runtimeSeconds != null && runtimeSeconds > 0) {
                _runtime = (int) Math.round(runtimeSeconds / 60.0);
            }
            return true;
        } catch (Exception e) {
            logLookupFailure(titleUrl, e);
            return false;
        }
    }

    public Map<String, Object> getEnv() {
        Map<String, Object> env = new HashMap<>(SiteBot.GLOBAL_ENV);
        env.put("title", getTitle());
        env.put("director", getDirector());
        env.put("genres", getGenres());
        env.put("language", getLanguage());
        env.put("country", getCountry());
        env.put("plot", getPlot());
        env.put("rating", getRating() != null ? getRating() / 10 + "." + getRating() % 10 : "0");
        env.put("votes", getVotes() != null ? getVotes() : "0");
        env.put("year", getYear() != null ? getYear() : "9999");
        env.put("url", getURL());
        env.put("runtime", getRuntime() != null ? getRuntime() : "0");
        env.put("searchstr", _searchString != null ? _searchString : "");
        return env;
    }

    private JsonObject retrieveJsonObject(String url) throws Exception {
        JsonElement response = JsonParser.parseString(_httpRetriever.retrieve(url));
        if (!response.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return response.getAsJsonObject();
    }

    private void resetResult() {
        _title = null;
        _year = null;
        _language = null;
        _country = null;
        _director = null;
        _genres = null;
        _plot = null;
        _rating = null;
        _votes = null;
        _url = null;
        _runtime = null;
        _searchString = null;
        _foundMovie = false;
    }

    private void logLookupFailure(String url, Exception e) {
        logger.warn("Tiffara IMDb lookup failed for {}: {}", url, e.getMessage());
        logger.debug("Tiffara IMDb lookup failure", e);
    }

    private static String normalizeApiBaseUrl(String apiBaseUrl) {
        String normalized = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Tiffara API URL cannot be empty");
        }
        return normalized;
    }

    private static String extractImdbId(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = IMDB_ID_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static boolean isImdbId(String value) {
        return value != null && IMDB_ID_PATTERN.matcher(value).matches();
    }

    private static boolean isMovieType(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.replace(" ", "").toLowerCase(Locale.ROOT)) {
            case "movie", "tvmovie", "video", "short" -> true;
            default -> false;
        };
    }

    private static String joinStrings(JsonArray values) {
        if (values == null) {
            return "N|A";
        }
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result.isEmpty() ? "N|A" : String.join("|", result);
    }

    private static String joinObjectNames(JsonArray values) {
        if (values == null) {
            return "N|A";
        }
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value != null && value.isJsonObject()) {
                String name = getString(value.getAsJsonObject(), "name");
                if (name == null) {
                    name = getString(value.getAsJsonObject(), "displayName");
                }
                if (name != null && !name.isBlank()) {
                    result.add(name);
                }
            }
        }
        return result.isEmpty() ? "N|A" : String.join("|", result);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String valueOrNotAvailable(String value) {
        return value == null || value.isBlank() ? "N|A" : value;
    }

    private static JsonArray getArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static JsonObject getObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String getString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static Integer getInteger(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static Double getDouble(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }

    @FunctionalInterface
    interface HttpRetriever {
        String retrieve(String url) throws Exception;
    }
}
