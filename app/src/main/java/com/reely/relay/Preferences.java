package com.reely.relay;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Thin SharedPreferences wrapper shared between the settings UI (where
 * values are set) and the accessibility service (where they're read).
 */
public final class Preferences {

    private static final String PREFS_NAME = "reelyrelay_prefs";

    private static final String KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted";
    private static final String KEY_SETUP_COMPLETED = "setup_completed";
    private static final String KEY_TMDB_API_KEY = "tmdb_api_key";
    private static final String KEY_SHOW_CHOOSER = "show_match_chooser";
    private static final String KEY_HIDE_ALTERNATE_TITLES = "hide_alternate_title_matches";
    private static final String KEY_SMARTTUBE_ENABLED = "smarttube_redirect_enabled";
    private static final String KEY_OCR_FALLBACK_ENABLED = "ocr_fallback_enabled";
    private static final String KEY_OCR_DISCLOSURE_ACCEPTED = "ocr_disclosure_accepted";
    private static final String KEY_FIRE_TV_MODE_ENABLED = "fire_tv_mode_enabled";
    private static final String KEY_YOUTUBE_REDIRECT_TARGET = "youtube_redirect_target";
    private static final String KEY_OVERLAY_REAPPEAR_ENABLED = "overlay_reappear_enabled";
    private static final String KEY_OVERLAY_LONG_PRESS_ONLY = "overlay_long_press_only";
    private static final String KEY_OVERLAY_REAPPEAR_DELAY = "overlay_reappear_delay_ms";
    private static final String KEY_UPDATE_CHECKED_AT = "update_checked_at";
    private static final String KEY_UPDATE_LATEST_VERSION = "update_latest_version";
    private static final String KEY_UPDATE_APK_URL = "update_apk_url";

    private Preferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isDisclosureAccepted(Context context) {
        return prefs(context).getBoolean(KEY_DISCLOSURE_ACCEPTED, false);
    }

    public static void setDisclosureAccepted(Context context, boolean accepted) {
        prefs(context).edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted).apply();
    }

    /**
     * Whether the first-run setup has been walked through to its last
     * screen (see SetupDoneStepFragment). Only decides which screen the app
     * opens on - MainActivity shows the setup steps until this is true, the
     * home screen afterwards. It says nothing about whether the permissions
     * are still granted; the home screen reads those live.
     */
    public static boolean isSetupCompleted(Context context) {
        return prefs(context).getBoolean(KEY_SETUP_COMPLETED, false);
    }

    public static void setSetupCompleted(Context context, boolean completed) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply();
    }

    /**
     * The user's own personal TMDB API key, exactly as typed into Settings -
     * empty if they haven't entered one. Used only for the editable Settings
     * field itself; actual lookups go through getEffectiveTmdbApiKey()
     * instead, so this stays blank (rather than showing the bundled default
     * pre-filled) until the user deliberately overrides it.
     */
    public static String getTmdbApiKey(Context context) {
        return prefs(context).getString(KEY_TMDB_API_KEY, "");
    }

    public static void setTmdbApiKey(Context context, String apiKey) {
        prefs(context).edit().putString(KEY_TMDB_API_KEY, apiKey).apply();
    }

    /**
     * The key actual TMDB lookups should use: the user's own, if they've set
     * one, otherwise the app's bundled default (BuildConfig.TMDB_API_KEY,
     * from TMDB_API_KEY in local.properties). A user's own key is still
     * fully supported and takes priority - e.g. if the shared default ever
     * gets rate-limited. Empty when neither exists, in which case no title
     * can be looked up at all - see hasTmdbApiKey().
     */
    public static String getEffectiveTmdbApiKey(Context context) {
        String own = getTmdbApiKey(context).trim();
        return !own.isEmpty() ? own : BuildConfig.TMDB_API_KEY.trim();
    }

    /**
     * False only for a build made without TMDB_API_KEY on a device where no
     * personal key has been entered - the home screen surfaces that, since
     * every lookup would otherwise fail silently.
     */
    public static boolean hasTmdbApiKey(Context context) {
        return !getEffectiveTmdbApiKey(context).isEmpty();
    }

    /** Whether an ambiguous title match shows a "which one did you mean?" chooser instead of guessing. */
    public static boolean isChooserEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_CHOOSER, true);
    }

    public static void setChooserEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHOW_CHOOSER, enabled).apply();
    }

    /**
     * Whether a candidate with a distinct alternate/original title (what the
     * chooser shows as an "aka <name>" line - see TitleCandidate.akaTitle,
     * ExactMatchPicker's constructor) is excluded from matching entirely,
     * rather than merely sorted after same-language matches - see
     * ExactMatchPicker.ranked()'s javadoc for the "Begin Again" case this
     * demotion (and this stronger opt-in exclusion) was built from. Default
     * off: aka matches are shown, just demoted, unless a user explicitly
     * decides they'd rather never see them at all.
     */
    public static boolean isAlternateTitlesHidden(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_ALTERNATE_TITLES, false);
    }

    public static void setAlternateTitlesHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_HIDE_ALTERNATE_TITLES, hidden).apply();
    }

    /**
     * Whether a YouTube video recommendation card (as opposed to a
     * movie/show card) gets redirected to a sideloaded YouTube TV client
     * instead of being left alone - which client is a separate, explicit
     * choice (see getYouTubeRedirectTarget() below), not auto-detected.
     * See RelayAccessibilityService's YouTube-marker detection and
     * PlayerLauncher.openYouTubeRedirect(). Independent of opening movies
     * and shows in Reely Player. Kept as "SmartTube" in the method/preference-key name for
     * minimal churn (this predates TizenTube Cobalt support and nothing
     * depends on renaming it) - only the user-facing strings and this
     * comment describe the broadened, multi-target behavior.
     *
     * Default OFF (changed from on, 2026-08-26, by explicit user request):
     * the redirect targets sideloaded apps most users won't have installed,
     * so it's opt-in now. Note the default only governs installs where the
     * user never touched the toggle - the key is only ever written by
     * setSmartTubeEnabled(), so a device where it was explicitly turned on
     * keeps it on.
     */
    public static boolean isSmartTubeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SMARTTUBE_ENABLED, false);
    }

    public static void setSmartTubeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SMARTTUBE_ENABLED, enabled).apply();
    }

    /**
     * Which sideloaded YouTube TV client the toggle above targets when it's
     * on - see YouTubeRedirectTarget and PlayerLauncher.openYouTubeRedirect().
     * An explicit Settings dropdown, not auto-detected install-state
     * guessing (the original 2026-08-24 design auto-preferred SmartTube
     * whenever it was installed - replaced 2026-08-25 by user request, since a user with
     * both apps installed had no way to actually pick TizenTube Cobalt over
     * the auto-preferred SmartTube).
     */
    public static YouTubeRedirectTarget getYouTubeRedirectTarget(Context context) {
        String stored = prefs(context).getString(KEY_YOUTUBE_REDIRECT_TARGET, null);
        if (stored == null) {
            return YouTubeRedirectTarget.SMARTTUBE;
        }
        try {
            return YouTubeRedirectTarget.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return YouTubeRedirectTarget.SMARTTUBE;
        }
    }

    public static void setYouTubeRedirectTarget(Context context, YouTubeRedirectTarget target) {
        prefs(context).edit().putString(KEY_YOUTUBE_REDIRECT_TARGET, target.name()).apply();
    }

    /**
     * Whether the screen-reading (MediaProjection + on-device OCR) fallback
     * is armed - see OcrCaptureManager and RelayAccessibilityService's
     * two trigger points (an empty click payload, and a voice-search result
     * with no title - neither carries a readable title on this device, a
     * platform limitation, not a ReelyRelay bug). Deliberately gated behind its own disclosure flag
     * (isOcrDisclosureAccepted), separate from the base
     * isDisclosureAccepted() - MediaProjection is a materially bigger,
     * more visible grant (a persistent system recording indicator) than
     * the base accessibility-only disclosure, so it gets its own explicit
     * opt-in screen rather than riding along with the first one.
     */
    public static boolean isOcrFallbackEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OCR_FALLBACK_ENABLED, false);
    }

    public static void setOcrFallbackEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OCR_FALLBACK_ENABLED, enabled).apply();
    }

    /** Whether the user has accepted OcrDisclosureStepFragment's separate MediaProjection consent screen. */
    public static boolean isOcrDisclosureAccepted(Context context) {
        return prefs(context).getBoolean(KEY_OCR_DISCLOSURE_ACCEPTED, false);
    }

    public static void setOcrDisclosureAccepted(Context context, boolean accepted) {
        prefs(context).edit().putBoolean(KEY_OCR_DISCLOSURE_ACCEPTED, accepted).apply();
    }

    /**
     * Whether Fire TV mode is armed - the UsageStats + OCR detection path
     * for Fire TV, where the accessibility-click pipeline gets no events at
     * all (see FireTvWatcherService). Default
     * OFF, same reasoning as isOcrFallbackEnabled()/isSmartTubeEnabled(): it
     * needs external grants (usage access + screen recording) and only
     * applies to one platform, so it never turns itself on. Unlike a plain
     * toggle this reflects intent, not live running state - the watcher is
     * activated (and re-activated after a reboot) explicitly from
     * FireTvModeStepFragment, since screen-recording consent doesn't survive
     * a reboot. Also gates PlayerLauncher.stopOcrSessionIfRunning() (which
     * becomes a no-op while on, so the capture session - and the watcher
     * process's priority - stays alive across launches).
     */
    public static boolean isFireTvModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FIRE_TV_MODE_ENABLED, false);
    }

    public static void setFireTvModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_FIRE_TV_MODE_ENABLED, enabled).apply();
    }

    /**
     * Whether WatchNowOverlay.hide() (a Back/D-pad dismiss) conceals the
     * confirm button and lets it reappear a few seconds later - see
     * WatchNowOverlay's class doc - or, when this is off, discards the
     * pending match outright, matching this app's original (pre-2026-08-25)
     * behavior where any dismissal was permanent. Default on.
     */
    public static boolean isOverlayReappearEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_REAPPEAR_ENABLED, true);
    }

    public static void setOverlayReappearEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_REAPPEAR_ENABLED, enabled).apply();
    }

    // The grid the Settings stepper walks and WatchNowOverlay schedules on.
    // DEFAULT is the value that used to be hardcoded in WatchNowOverlay, so
    // an existing install behaves identically until the user changes it.
    static final long OVERLAY_REAPPEAR_DELAY_MIN_MS = 500L;
    static final long OVERLAY_REAPPEAR_DELAY_MAX_MS = 15_000L;
    static final long OVERLAY_REAPPEAR_DELAY_STEP_MS = 500L;
    static final long OVERLAY_REAPPEAR_DELAY_DEFAULT_MS = 4_000L;

    /**
     * How long WatchNowOverlay keeps a dismissed confirm button hidden
     * before bringing it back (only relevant while Reappear is on). Always
     * clamped to [MIN, MAX] and snapped to the STEP grid, so a hand-edited
     * or out-of-range stored value can never post a 0 ms or multi-minute
     * timer. Read on every conceal, so it's kept cheap. Default 4 s.
     */
    public static long getOverlayReappearDelayMs(Context context) {
        long stored = prefs(context).getLong(KEY_OVERLAY_REAPPEAR_DELAY, OVERLAY_REAPPEAR_DELAY_DEFAULT_MS);
        return snapOverlayReappearDelay(stored);
    }

    public static void setOverlayReappearDelayMs(Context context, long delayMillis) {
        prefs(context).edit()
                .putLong(KEY_OVERLAY_REAPPEAR_DELAY, snapOverlayReappearDelay(delayMillis))
                .apply();
    }

    /** The neighbouring grid value in the given direction (+1/-1), clamped at both ends. */
    static long stepOverlayReappearDelay(long currentMs, int direction) {
        return snapOverlayReappearDelay(
                snapOverlayReappearDelay(currentMs) + direction * OVERLAY_REAPPEAR_DELAY_STEP_MS);
    }

    private static long snapOverlayReappearDelay(long ms) {
        long steps = Math.round((double) ms / OVERLAY_REAPPEAR_DELAY_STEP_MS);
        long snapped = steps * OVERLAY_REAPPEAR_DELAY_STEP_MS;
        return Math.max(OVERLAY_REAPPEAR_DELAY_MIN_MS,
                Math.min(OVERLAY_REAPPEAR_DELAY_MAX_MS, snapped));
    }

    /**
     * Raw stored value of the "Long press only" toggle - only meant for the
     * Settings row's own checkbox state (OverlaySettingsStepFragment). Every
     * runtime caller (WatchNowOverlay) must go through
     * isOverlayLongPressOnlyReady() instead, so a stale "on" value can't
     * keep changing button behavior after Reappear is turned back off.
     * Default off.
     */
    public static boolean isOverlayLongPressOnlyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_LONG_PRESS_ONLY, false);
    }

    public static void setOverlayLongPressOnlyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_LONG_PRESS_ONLY, enabled).apply();
    }

    /**
     * The single gate WatchNowOverlay actually checks before requiring a
     * long press to confirm - true only when the toggle is on AND Reappear
     * is also on. Deliberately doesn't also check the
     * overlay permission here: WatchNowOverlay.ensureButton() already bails
     * out with no button at all when that's missing, so there's nothing to
     * wire either way, and this is read on every confirm - not worth a
     * Settings.canDrawOverlays() binder call each time.
     */
    public static boolean isOverlayLongPressOnlyReady(Context context) {
        return isOverlayLongPressOnlyEnabled(context) && isOverlayReappearEnabled(context);
    }

    /**
     * Timestamp (millis) of the last time GithubReleaseClient was actually
     * queried for a newer release - 0 if never. Throttles that network call
     * (see HomeStepFragment) so opening Settings repeatedly doesn't hit
     * the GitHub API every time.
     */
    public static long getUpdateCheckedAt(Context context) {
        return prefs(context).getLong(KEY_UPDATE_CHECKED_AT, 0L);
    }

    public static void setUpdateCheckedAt(Context context, long timestampMillis) {
        prefs(context).edit().putLong(KEY_UPDATE_CHECKED_AT, timestampMillis).apply();
    }

    /**
     * The newest version GithubReleaseClient has seen on GitHub so far -
     * empty if no check has ever found one newer than what's installed.
     * Cached (rather than re-derived from a fresh network call every time)
     * so the "Update available" Settings row can render immediately from the
     * last known result while the next throttled check runs in the
     * background - see UpdateStepFragment/getUpdateApkUrl().
     */
    public static String getUpdateLatestVersion(Context context) {
        return prefs(context).getString(KEY_UPDATE_LATEST_VERSION, "");
    }

    public static String getUpdateApkUrl(Context context) {
        return prefs(context).getString(KEY_UPDATE_APK_URL, "");
    }

    public static void setUpdateAvailable(Context context, String version, String apkUrl) {
        prefs(context).edit()
                .putString(KEY_UPDATE_LATEST_VERSION, version)
                .putString(KEY_UPDATE_APK_URL, apkUrl)
                .apply();
    }
}
