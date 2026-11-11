package com.reely.relay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TMDB client - the title database. Uses
 * Preferences.getEffectiveTmdbApiKey(): the app's own bundled key
 * (BuildConfig.TMDB_API_KEY) unless the user has entered their own in
 * Settings, in which case theirs takes priority - e.g. if the shared
 * default is ever rate-limited. A bundled key removes the setup friction
 * of requiring every user to get their own TMDB key.
 *
 * One search call is the whole lookup: Reely Player's deep link takes the
 * TMDB id a search result already carries (see ReelyPlayer), so nothing
 * else has to be fetched before a title can be opened.
 */
final class TmdbClient {

    private static final String TAG = "TmdbClient";
    private static final OkHttpClient httpClient = new OkHttpClient();

    private TmdbClient() {
    }

    /**
     * Every plausible match for a title, most-likely first - see
     * ExactMatchPicker.ranked(). Empty (never null) if nothing usable came
     * back, including when no API key is set. Blocking network call(s) -
     * call only from a background thread.
     */
    static List<TitleCandidate> findCandidates(Context context, String title) {
        String apiKey = Preferences.getEffectiveTmdbApiKey(context);
        if (apiKey.isEmpty()) {
            Log.w(TAG, "No TMDB API key available (none bundled, none set in Settings)");
            return Collections.emptyList();
        }
        boolean hideAlternateTitles = Preferences.isAlternateTitlesHidden(context);
        return TitleSearchFallbacks.resolve(title, t -> resolveCandidates(apiKey, hideAlternateTitles, t));
    }

    private static List<TitleCandidate> resolveCandidates(String apiKey, boolean hideAlternateTitles, String title) {
        try {
            return search(apiKey, hideAlternateTitles, title);
        } catch (Exception e) {
            Log.e(TAG, "TMDB lookup failed for: " + title, e);
            return Collections.emptyList();
        }
    }

    private static List<TitleCandidate> search(String apiKey, boolean hideAlternateTitles, String title) throws IOException, JSONException {
        // Region-qualified, not just the bare language - confirmed real bug
        // (2026-09-08) via direct TMDB queries: Locale.getLanguage() alone
        // drops the country (e.g. "es" from an es-MX device), and TMDB's
        // Spanish translations are genuinely region-specific, not cosmetic
        // variants - Inception is "Origen" in Spain but "El Origen" in
        // Mexico. Querying just "es" returned neither as an exact match (in
        // fact "El Origen" didn't appear in the top 20 results at all);
        // querying "es-MX" returned "El Origen" as the #1 result, an exact
        // match against the launcher's own Mexico-localized card text. Same
        // confirmed live for a 2026 movie titled "Obsesión" in Spanish - the
        // dated (2026) entry was outside the top 10 under "es" but the #1
        // result under "es-MX". Falls back to the bare language when the
        // locale has no country (some generic locales carry none) - don't
        // "simplify" this back to Locale.getDefault().getLanguage() alone.
        Locale locale = Locale.getDefault();
        String language = locale.getCountry().isEmpty()
                ? locale.getLanguage()
                : locale.getLanguage() + "-" + locale.getCountry();
        String url = "https://api.themoviedb.org/3/search/multi?query=" + URLEncoder.encode(title, "UTF-8")
                + "&api_key=" + apiKey + "&language=" + language;
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "TMDB search failed: " + response.code());
                return Collections.emptyList();
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return Collections.emptyList();
            }
            JSONObject json = new JSONObject(body);
            JSONArray results = json.optJSONArray("results");
            if (results == null) {
                return Collections.emptyList();
            }

            // See ExactMatchPicker for why this exists: TMDB's search is
            // relevance-ranked and title collisions are common ("Obsession"
            // alone matches several distinct movies and shows).
            String normalizedQuery = ExactMatchPicker.normalize(title);
            ExactMatchPicker<TitleCandidate> picker = new ExactMatchPicker<>(hideAlternateTitles);

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                String mediaPath = result.optString("media_type", "");
                if (!"movie".equals(mediaPath) && !"tv".equals(mediaPath)) {
                    continue;
                }
                boolean isMovie = "movie".equals(mediaPath);
                MediaType mediaType = isMovie ? MediaType.MOVIE : MediaType.SERIES;
                String name = result.optString(isMovie ? "title" : "name", "");
                String originalName = result.optString(isMovie ? "original_title" : "original_name", "");
                if (name.isEmpty() && originalName.isEmpty()) {
                    continue;
                }
                boolean isExactMatch = normalizedQuery.equals(ExactMatchPicker.normalize(name))
                        || normalizedQuery.equals(ExactMatchPicker.normalize(originalName));
                String dateField = isMovie ? "release_date" : "first_air_date";
                int year = ExactMatchPicker.parseYear(result.optString(dateField, ""));
                // Kept (not temporary): confirmed real case where this
                // mattered - TMDB's own localized "title" for an obscure
                // French film ("Rendez-Vous", 2015) is literally "Obsession"
                // in English, so it legitimately passes the exact-match
                // check above. That's a TMDB catalog-data quirk, the same
                // class of issue as the "Backrooms" data-coverage gap
                // (a genuine data-coverage gap, not a bug in the matching
                // logic) - not something string matching can or
                // should second-guess. This log is what surfaced it.
                Log.d(TAG, "Candidate " + i + ": media_type=" + mediaPath
                        + " name=[" + name + "] original=[" + originalName + "]"
                        + " year=[" + year + "] isExactMatch=" + isExactMatch);
                String displayTitle = !name.isEmpty() ? name : originalName;
                // Shown as an "aka" hint in the chooser so a case like the
                // one above is at least visible to the user instead of
                // indistinguishable from a real "Obsession" - see
                // TitleCandidate/MatchTrayView.
                String akaTitle = !originalName.isEmpty()
                        && !ExactMatchPicker.normalize(originalName).equals(ExactMatchPicker.normalize(displayTitle))
                        ? originalName : null;
                TitleCandidate candidate = new TitleCandidate(
                        displayTitle, akaTitle, year, mediaType, isExactMatch, result.getInt("id"));
                picker.offer(candidate, isExactMatch, akaTitle != null, year);
            }

            List<TitleCandidate> ranked = picker.ranked();
            if (!ranked.isEmpty() && !picker.hasExactMatch()) {
                Log.d(TAG, "No exact title match for \"" + title + "\" - using top relevance result");
            }
            return ranked;
        }
    }

    /**
     * Reachability/key-validity check for the Settings "Test key" action.
     * Reuses the same /search/multi endpoint the real lookup uses (rather
     * than a separate, unverified endpoint) - a 200 confirms the key
     * works regardless of whether the query matches anything; TMDB
     * returns 401 for an invalid key.
     */
    static boolean testApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        try {
            String url = "https://api.themoviedb.org/3/search/multi?query=test&api_key=" + apiKey.trim();
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB key test failed", e);
            return false;
        }
    }
}
