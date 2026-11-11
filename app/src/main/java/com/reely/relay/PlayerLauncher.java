package com.reely.relay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.List;

/**
 * Opens a resolved title in Reely Player, and (separately) a YouTube video
 * recommendation in a sideloaded YouTube client when that optional redirect
 * is on.
 *
 * Takes a plain Context (not specifically AccessibilityService) so it can be
 * called both from the accessibility service and from a regular Activity
 * (the manual search fallback - see SearchStepFragment).
 */
final class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    // SmartTube: a sideloaded YouTube TV client - see openYouTubeRedirect()
    // and Preferences.isSmartTubeEnabled(). Two separate distributions
    // exist; try stable first, then beta.
    private static final String SMARTTUBE_LABEL = "SmartTube";
    private static final String SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable";
    private static final String SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta";

    // TizenTube Cobalt (reisxd/TizenTubeCobalt, https://tizentube.app): an
    // Android TV port of the "TizenTube" ad-block mod, built on Google's
    // open-source Cobalt runtime. Its manifest declares
    // ACTION_VIEW/MEDIA_PLAY_FROM_SEARCH for youtube.com URLs - the same URL
    // contract SmartTube uses (see buildYouTubeSearchUri()). One package
    // only, unlike SmartTube.
    private static final String TIZENTUBE_COBALT_LABEL = "TizenTube Cobalt";
    private static final String TIZENTUBE_COBALT_PACKAGE = "io.gh.reisxd.tizentube.cobalt";
    // Cobalt's single Activity - the stock Cobalt runtime's own class name,
    // which TizenTube Cobalt keeps. Targeted explicitly so the search lands
    // on the live instance; see openInTizenTubeCobalt().
    private static final String TIZENTUBE_COBALT_ACTIVITY = "dev.cobalt.app.MainActivity";

    // Default flags every launch gets - see openWithFallback() for why.
    private static final int CLEAN_START_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;

    // TizenTube Cobalt only: deliberately NO CLEAR_TASK - see
    // openInTizenTubeCobalt().
    private static final int COBALT_LIVE_INSTANCE_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

    private PlayerLauncher() {
    }

    /**
     * Opens a candidate's detail page in Reely Player and reports whether
     * an Activity was actually started (false when Reely Player isn't
     * installed). No network call - the candidate already carries the TMDB
     * id Reely's deep link takes (see ReelyPlayer) - so this is safe on any
     * thread.
     *
     * Tries every package in ReelyPlayer.PACKAGES in order, allowing a
     * packageless retry only past the last one - harmless for a custom
     * reely:// scheme that only Reely's own builds claim.
     */
    static boolean openInReely(Context context, TitleCandidate candidate) {
        Uri uri = ReelyPlayer.detailUri(candidate.type, candidate.tmdbId);
        List<String> packages = ReelyPlayer.PACKAGES;
        for (int i = 0; i < packages.size(); i++) {
            boolean isLastPackage = i == packages.size() - 1;
            // A fresh Intent per attempt: setPackage() mutates in place and
            // each attempt needs its own target.
            Intent attempt = new Intent(Intent.ACTION_VIEW, uri).setPackage(packages.get(i));
            if (openWithFallback(context, attempt, ReelyPlayer.LABEL, isLastPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Both YouTube clients take the exact same YouTube search URL
     * (https://www.youtube.com/results?search_query={title}), just in a
     * different package.
     */
    private static Uri buildYouTubeSearchUri(String rawTitle) {
        String title = TitleCleanup.stripTrailingParentheticals(rawTitle);
        return Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(title));
    }

    /**
     * The YouTube redirect for a YouTube video recommendation card - an
     * independent, opt-in toggle (Preferences.isSmartTubeEnabled()),
     * unrelated to opening movies and shows in Reely Player. See
     * RelayAccessibilityService's YouTube-marker detection, which decides
     * when this gets called at all - this method doesn't check the setting.
     *
     * The target is an explicit Settings choice
     * (Preferences.getYouTubeRedirectTarget()), not install-state guessing.
     * If the chosen app isn't installed this doesn't silently substitute
     * the other one - it just reports failure.
     */
    static boolean openYouTubeRedirect(Context context, String rawTitle) {
        if (Preferences.getYouTubeRedirectTarget(context) == YouTubeRedirectTarget.TIZENTUBE_COBALT) {
            return openInTizenTubeCobalt(context, rawTitle);
        }
        return openInSmartTube(context, rawTitle);
    }

    /**
     * Opens a YouTube search for the title in SmartTube (no direct video-id
     * deep link - there's no reliable way to resolve a specific video id
     * from just a title), trying its stable package first, then beta. No
     * packageless fallback for either attempt: a bare YouTube search URL
     * would otherwise silently open in a browser or the official YouTube
     * app instead, which defeats the point of this feature.
     */
    private static boolean openInSmartTube(Context context, String rawTitle) {
        Uri uri = buildYouTubeSearchUri(rawTitle);
        Intent stable = new Intent(Intent.ACTION_VIEW, uri).setPackage(SMARTTUBE_STABLE_PACKAGE);
        if (openWithFallback(context, stable, SMARTTUBE_LABEL, false)) {
            return true;
        }
        Intent beta = new Intent(Intent.ACTION_VIEW, uri).setPackage(SMARTTUBE_BETA_PACKAGE);
        return openWithFallback(context, beta, SMARTTUBE_LABEL, false);
    }

    /**
     * Same search URL as SmartTube, but NOT the same action - confirmed
     * real bug (2026-08-25): Intent.ACTION_VIEW with this URL does launch
     * TizenTube Cobalt, but the app just opens to its own home feed and
     * silently ignores the search_query param.
     * "android.media.action.MEDIA_PLAY_FROM_SEARCH" with the identical data
     * URI is what actually works.
     *
     * Launch flags: NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS, never the
     * CLEAR_TASK every other launch gets (see openWithFallback()). With
     * CLEAR_TASK the search only worked on a cold start: once Cobalt was
     * already running, the same Intent landed on its home feed with the
     * query dropped. Cobalt only reads a new query on its live instance
     * (onNewIntent), and CLEAR_TASK tears that instance down first.
     * Addressing its Activity explicitly with these flags hands the search
     * to the live instance instead; a cold start works either way.
     *
     * If a future Cobalt build renames its Activity, the explicit attempt
     * fails and this retries scoped to the package only (same flags) -
     * which still opens Cobalt, just possibly back on the cold-start-only
     * path.
     */
    private static boolean openInTizenTubeCobalt(Context context, String rawTitle) {
        Uri uri = buildYouTubeSearchUri(rawTitle);
        Intent explicit = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH", uri)
                .setClassName(TIZENTUBE_COBALT_PACKAGE, TIZENTUBE_COBALT_ACTIVITY);
        if (openWithFallback(context, explicit, TIZENTUBE_COBALT_LABEL, false,
                COBALT_LIVE_INSTANCE_FLAGS)) {
            return true;
        }
        Intent packageOnly = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH", uri)
                .setPackage(TIZENTUBE_COBALT_PACKAGE);
        return openWithFallback(context, packageOnly, TIZENTUBE_COBALT_LABEL, false,
                COBALT_LIVE_INSTANCE_FLAGS);
    }

    /**
     * @param allowGenericFallback whether to retry once more with no
     *                             package restriction if the targeted
     *                             launch fails - appropriate for a custom
     *                             URI scheme only one app handles (reely://),
     *                             not for a plain https Intent that could
     *                             land somewhere unhelpful (a browser, the
     *                             official YouTube app).
     * @return whether an Activity was actually started.
     */
    private static boolean openWithFallback(Context context, Intent intent, String appLabel, boolean allowGenericFallback) {
        return openWithFallback(context, intent, appLabel, allowGenericFallback, CLEAN_START_FLAGS);
    }

    /**
     * Same as above with caller-chosen task flags - only TizenTube Cobalt
     * needs anything other than CLEAN_START_FLAGS (see
     * openInTizenTubeCobalt()). Must include FLAG_ACTIVITY_NEW_TASK.
     */
    private static boolean openWithFallback(Context context, Intent intent, String appLabel,
                                            boolean allowGenericFallback, int taskFlags) {
        // NEW_TASK is required regardless of caller, since
        // Context.startActivity needs it when the calling context isn't
        // itself an Activity.
        //
        // CLEAR_TASK is what makes a link land reliably in Reely Player.
        // Its MainActivity is singleTop and can take a link on a running
        // instance (onNewIntent -> its "reely/deep_links" channel), but that
        // hand-off only reaches the app while its Flutter side is already
        // listening. When Android has killed Reely's process and kept its
        // task - routine on low-memory TV boxes - the activity is recreated
        // with its ORIGINAL launch intent and the new link is delivered
        // before anything listens, so Reely would just open on its home
        // screen. CLEAR_TASK makes the link the activity's launch intent
        // every time, which is Reely's cold-start path (getInitialDeepLink)
        // and always ends on the title. The cost is that an already-running
        // Reely restarts its UI instead of navigating in place.
        intent.addFlags(taskFlags);

        try {
            context.startActivity(intent);
            Log.d(TAG, "Opening " + appLabel + ": " + intent);
            stopOcrSessionIfRunning(context);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not open " + appLabel + ": " + intent, e);
            if (!allowGenericFallback) {
                return false;
            }
            try {
                Intent fallback = new Intent(intent);
                fallback.setPackage(null);
                context.startActivity(fallback);
                stopOcrSessionIfRunning(context);
                return true;
            } catch (Exception fallbackFailure) {
                Log.e(TAG, "Fallback failed too. Is " + appLabel + " installed?", fallbackFailure);
                return false;
            }
        }
    }

    /**
     * Tears down the OCR screen-capture session (see OcrCaptureManager),
     * if one happens to be running, whenever a launch actually succeeds -
     * this is the one place every real launch path funnels through
     * (openWithFallback() above), so it's the most reliable available
     * signal that the user is leaving the launcher lobby for another app,
     * without needing to widen RelayAccessibilityService's event
     * filtering to watch every package (a real battery/perf/privacy cost).
     *
     * Routed through OcrCaptureManager.stopActiveSessionAfterLaunch()
     * rather than calling Context.stopService() directly here - confirmed
     * real bug the first version of this had (2026-08-25): the OCR session
     * is BOTH started AND bound (OcrCaptureManager holds a live
     * ServiceConnection - see its class doc), and a service that's still
     * bound does not actually get destroyed by stopService() alone, even
     * though the call itself doesn't throw or report failure - the
     * recording indicator stayed visible with the service and its
     * MediaProjection still fully alive, confirmed via dumpsys. Only
     * OcrCaptureManager's own instance can properly unbind AND stop it
     * together (see stopSessionIfActive()) - PlayerLauncher, a static
     * utility with no reference to that instance, reaches it via the same
     * kind of static bridge already used for OcrConsentActivity's result
     * callback.
     *
     * The real cost of this: the next OCR trigger after returning to the
     * lobby needs a fresh screen-capture consent grant, since Android's
     * MediaProjection consent is single-use per session (see
     * OcrCaptureManager's class doc) - accepted as the correct trade-off
     * for not leaving the system's persistent recording indicator showing
     * while the user is off watching something in a different app.
     *
     * Exception: in Fire TV mode (Preferences.isFireTvModeEnabled) this is a
     * no-op. There the capture session is deliberately long-lived - it's
     * what keeps FireTvWatcherService's process at foreground priority while
     * it polls, and re-requesting consent on every single card selection
     * would be far worse than leaving the recording indicator up. The
     * session is torn down instead when the user turns Fire TV mode off
     * (FireTvWatcherService.onDestroy -> OcrCaptureManager.shutdown()).
     */
    private static void stopOcrSessionIfRunning(Context context) {
        if (Preferences.isFireTvModeEnabled(context)) {
            return;
        }
        OcrCaptureManager.stopActiveSessionAfterLaunch();
    }
}
