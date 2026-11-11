package com.reely.relay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Opens the system Settings screens the app sends the user to. TV builds of
 * Android are inconsistent about which of these exist - a screen that is
 * missing must cost a toast, not a crash - so each one tries the most
 * specific Intent first and falls back to something nearby.
 */
final class SystemScreens {

    private static final String TAG = "SystemScreens";

    private SystemScreens() {
    }

    /** The system Accessibility list, where the service is switched on. */
    static void openAccessibility(Context context) {
        open(context, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
    }

    /** "Display over other apps" for this app; the app's own info screen if that isn't available. */
    static void openOverlayPermission(Context context) {
        open(context,
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                appInfoIntent(context));
    }

    /** This app's system info screen - where "Allow restricted setting" lives on devices that have it. */
    static void openAppInfo(Context context) {
        open(context, appInfoIntent(context), new Intent(Settings.ACTION_SETTINGS));
    }

    private static Intent appInfoIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context));
    }

    private static Uri packageUri(Context context) {
        return Uri.parse("package:" + context.getPackageName());
    }

    private static void open(Context context, Intent... candidates) {
        for (Intent candidate : candidates) {
            try {
                context.startActivity(candidate);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Not available on this device: " + candidate, e);
            }
        }
        Toast.makeText(context, R.string.system_screen_unavailable, Toast.LENGTH_LONG).show();
    }
}
