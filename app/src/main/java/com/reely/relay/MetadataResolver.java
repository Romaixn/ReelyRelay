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

    /**
     * The exact title match released in the given year, or null when there
     * is none or the year is unknown. The launcher's detail page shows the
     * year (see LauncherDetailPage), which settles most same-name cases -
     * "Supergirl" the 2026 film rather than the 2015 series - without
     * asking. A launcher year that doesn't match TMDB's (a regional release
     * date) just returns null, leaving the normal chooser to decide.
     */
    static TitleCandidate exactMatchFromYear(List<TitleCandidate> candidates, int year) {
        if (year == LauncherDetailPage.UNKNOWN_YEAR) {
            return null;
        }
        for (TitleCandidate candidate : candidates) {
            if (candidate.isExactMatch && candidate.year == year) {
                return candidate;
            }
        }
        return null;
    }
}
