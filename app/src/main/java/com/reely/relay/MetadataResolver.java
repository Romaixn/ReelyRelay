package com.reely.relay;

import android.content.Context;

import java.util.List;

/** Resolves a title to its TMDB candidates, and says when that result is ambiguous. */
final class MetadataResolver {

    private MetadataResolver() {
    }

    /**
     * All plausible matches for a title, most-likely first - see
     * ExactMatchPicker.ranked(). Empty (never null) if nothing usable came
     * back. Blocking network call(s) - call only from a background thread.
     */
    static List<TitleCandidate> resolveCandidates(Context context, String title) {
        return TmdbClient.findCandidates(context, title);
    }

    /**
     * True when a candidate list is genuinely ambiguous: two or more
     * candidates whose title matched the search exactly (see
     * TitleCandidate.isExactMatch). A single exact match, or none at all
     * (top-relevance fallback), isn't ambiguous - candidates.get(0) is used
     * directly in that case.
     */
    static boolean isAmbiguous(List<TitleCandidate> candidates) {
        int exactCount = 0;
        for (TitleCandidate candidate : candidates) {
            if (candidate.isExactMatch && ++exactCount >= 2) {
                return true;
            }
        }
        return false;
    }
}
