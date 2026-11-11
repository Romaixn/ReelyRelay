package com.reely.relay;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Everything ReelyRelay knows about Reely Player: which packages it ships
 * as, and the deep link that opens a title in it.
 *
 * Reely's content link is {@code reely://detail/{movie|series}/{tmdbId}} -
 * its MainActivity turns that into the app route /detail/:type/:tmdbId, and
 * DeepLinkService only lets it through when the type is exactly "movie" or
 * "series" and the id is all digits. There is no IMDb-based form (the
 * app's /detail/imdb/... route isn't on that allowlist), which is why TMDB
 * is the only title database here: a TMDB search result already carries
 * the one id Reely accepts, so opening a title needs no second lookup.
 */
final class ReelyPlayer {

    /** Brand name - never translated. */
    static final String LABEL = "Reely Player";

    /**
     * Tried in order - see PlayerLauncher.openInReely(). The release build
     * first, then the side-by-side dev build (Reely's debug/dev variants
     * add an applicationIdSuffix of ".debug"), so a device that only has a
     * dev build installed still works. Both must also be listed under
     * {@code <queries>} in AndroidManifest.xml to be visible on Android 11+.
     */
    static final List<String> PACKAGES = Collections.unmodifiableList(
            Arrays.asList("com.reely.reely", "com.reely.reely.debug"));

    private static final String MOVIE_URI = "reely://detail/movie/";
    private static final String SERIES_URI = "reely://detail/series/";

    private ReelyPlayer() {
    }

    static Uri detailUri(MediaType type, int tmdbId) {
        return Uri.parse((type == MediaType.SERIES ? SERIES_URI : MOVIE_URI) + tmdbId);
    }

    /** Whether any Reely Player build is installed on this device. */
    static boolean isInstalled(Context context) {
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : PACKAGES) {
            try {
                packageManager.getPackageInfo(packageName, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // Not this one - try the next.
            }
        }
        return false;
    }
}
