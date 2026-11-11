package com.reely.relay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Walkthrough for Android 13+'s "Restricted settings" block - a protection
 * for apps that weren't installed from a store, which switches the
 * accessibility toggle straight back off. Reached from the setup step and
 * the Accessibility screen.
 *
 * This screen deliberately only ever opens documented system Settings
 * screens, or shows the one ADB command that lifts the block. It never
 * writes Settings.Secure itself and never calls AppOpsManager to grant
 * anything - WRITE_SECURE_SETTINGS is signature/system-only and
 * unobtainable by a normal app - so it documents the restriction honestly
 * and offers only what's actually possible, rather than pretend a bypass
 * exists.
 *
 * The ADB command lives in the guidance text, not in a row: there is
 * nothing a click could do with it, it's something to read (and type on a
 * computer), so it belongs with the explanation rather than looking like a
 * button. The QR code links to the same walkthrough on the project site,
 * where the command can be copied.
 */
public class RestrictedSettingsStepFragment extends RelayStepFragment {

    private static final int QR_SIZE_PX = 280;

    private static final long ACTION_OPEN_APP_INFO = 1;
    private static final long ACTION_RETRY_ACCESSIBILITY = 2;
    private static final long ACTION_CLOSE = 3;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        Bitmap qr = QrCodeGenerator.generate(Links.ACCESSIBILITY_HELP, QR_SIZE_PX);
        Drawable icon = qr != null ? new BitmapDrawable(getResources(), qr) : null;
        // Guidance only takes a plain String; the styled version replaces
        // it in onViewCreated().
        return new GuidanceStylist.Guidance(
                getString(R.string.restricted_settings_help_title),
                buildDescription().toString(),
                "",
                icon);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setGuidanceDescription(buildDescription());
    }

    private CharSequence buildDescription() {
        Context context = requireContext();
        SpannableStringBuilder builder = new SpannableStringBuilder()
                .append(getString(R.string.restricted_settings_help_intro))
                .append("\n\n");
        int start = builder.length();
        // The package name is filled in rather than written into the string
        // so the command is always right for this exact build.
        builder.append(getString(R.string.restricted_settings_adb_fix_command, context.getPackageName()));
        int end = builder.length();
        builder.setSpan(new ForegroundColorSpan(RelayStyle.color(context, R.color.warning)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append("\n\n").append(getString(R.string.restricted_settings_scan_more));
        return builder;
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_OPEN_APP_INFO)
                .title(getString(R.string.restricted_settings_open_app_info))
                .description(getString(R.string.restricted_settings_open_app_info_description))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_RETRY_ACCESSIBILITY)
                .title(getString(R.string.restricted_settings_retry_accessibility))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_CLOSE)
                .title(getString(R.string.about_close))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_OPEN_APP_INFO) {
            SystemScreens.openAppInfo(requireContext());
        } else if (id == ACTION_RETRY_ACCESSIBILITY) {
            SystemScreens.openAccessibility(requireContext());
        } else if (id == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
