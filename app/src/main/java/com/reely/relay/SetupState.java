package com.reely.relay;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * What is and isn't in place for ReelyRelay to work, read live from the
 * system every time - never cached, since every one of these is granted or
 * revoked outside the app (system Settings, an update, an uninstall).
 * Shared by the first-run steps and the home screen so both always agree.
 */
final class SetupState {

    private SetupState() {
    }

    /** Whether ReelyRelay's accessibility service is switched on in system Settings. */
    static boolean isAccessibilityServiceEnabled(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }
        ComponentName target = new ComponentName(context, RelayAccessibilityService.class);
        for (String flattened : enabledServices.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(flattened))) {
                return true;
            }
        }
        return false;
    }

    /** Fire TV mode is on and still holds the usage-access grant it can't work without. */
    static boolean isFireTvModeReady(Context context) {
        return Preferences.isFireTvModeEnabled(context) && FireTvSupport.hasUsageAccess(context);
    }

    /**
     * Whether ReelyRelay can find out which recommendation was picked at
     * all. On Google TV that is the accessibility service. On Fire TV it is
     * normally Fire TV mode instead (the current Fire TV home screen sends
     * no accessibility events - see FireTvSupport), though older Fire OS
     * launchers still do, so either one counts there.
     */
    static boolean isDetectionReady(Context context) {
        if (isAccessibilityServiceEnabled(context)) {
            return true;
        }
        return FireTvSupport.isFireTv(context) && isFireTvModeReady(context);
    }

    /** "Display over other apps" - what the Watch now button needs. Optional: without it titles open directly. */
    static boolean isOverlayGranted(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /** True once a picked recommendation would actually end up in Reely Player. */
    static boolean isWorking(Context context) {
        return ReelyPlayer.isInstalled(context)
                && isDetectionReady(context)
                && Preferences.hasTmdbApiKey(context);
    }

    /**
     * One sentence for the home screen: what a pick will do once everything
     * is in place, or else the single most important thing still missing.
     * Checks in the same order as isWorking(), so the two never disagree.
     */
    static String summary(Context context) {
        if (!ReelyPlayer.isInstalled(context)) {
            return context.getString(R.string.home_needs_reely, Links.REELY_PLAYER_SITE);
        }
        if (!isDetectionReady(context)) {
            return context.getString(FireTvSupport.isFireTv(context)
                    ? R.string.home_needs_fire_tv
                    : R.string.home_needs_accessibility);
        }
        if (!Preferences.hasTmdbApiKey(context)) {
            return context.getString(R.string.home_needs_tmdb_key);
        }
        return context.getString(isOverlayGranted(context)
                ? R.string.home_ready
                : R.string.home_ready_no_button);
    }
}
