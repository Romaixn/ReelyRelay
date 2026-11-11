package com.reely.relay;

import java.io.Serializable;

/**
 * A single TMDB search result - what a detected title resolves to, and what
 * the chooser lists when a title is ambiguous (see MatchTrayView /
 * MatchChooserStepFragment). Always launchable as-is: Reely Player's deep
 * link takes the TMDB id a search result already carries (see ReelyPlayer).
 *
 * Serializable rather than Parcelable: there are at most a handful of these
 * per lookup, they only ever travel in an Intent/Bundle extra between this
 * app's own components, and Serializable is one line instead of the
 * ~30 Parcelable normally needs.
 */
final class TitleCandidate implements Serializable {

    final String displayTitle;
    final String akaTitle; // non-null only when meaningfully different from displayTitle - see TmdbClient
    final int year; // Integer.MIN_VALUE when TMDB didn't give one
    final MediaType type;
    final boolean isExactMatch;
    final int tmdbId;

    TitleCandidate(String displayTitle, String akaTitle, int year, MediaType type,
                   boolean isExactMatch, int tmdbId) {
        this.displayTitle = displayTitle;
        this.akaTitle = akaTitle;
        this.year = year;
        this.type = type;
        this.isExactMatch = isExactMatch;
        this.tmdbId = tmdbId;
    }
}
