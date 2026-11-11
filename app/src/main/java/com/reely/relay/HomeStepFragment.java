package com.reely.relay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's home screen once setup has been walked through: a status label
 * (working or not), one sentence on what happens next or the one thing
 * still missing (see SetupState.summary()), then a short menu where each
 * group opens its own screen (the SettingsSubStepFragment subclasses).
 *
 * The rows that can break the whole thing - Accessibility (or Fire TV
 * mode) and the Watch now button - come first and carry their live state,
 * because that state is changed outside the app and would otherwise be
 * hidden a click away.
 */
public class HomeStepFragment extends RelayStepFragment {

    private static final long ACTION_UPDATE_AVAILABLE = 1;
    private static final long ACTION_ACCESSIBILITY = 2;
    private static final long ACTION_FIRE_TV_MODE = 3;
    private static final long ACTION_OVERLAY = 4;
    private static final long ACTION_SEARCH = 5;
    private static final long ACTION_DETECTION = 6;
    private static final long ACTION_YOUTUBE = 7;
    private static final long ACTION_MORE = 8;

    /** Throttle for the GitHub release check kicked off from onResume() - see maybeCheckForUpdate(). */
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** See onResume() - skips its rebuild on the very first call. */
    private boolean firstResume = true;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.app_name),
                SetupState.summary(requireContext()),
                "",
                brandTile());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        // The adapter exists only once the view does. See ActionListDiff for why
        // rebuilt lists need it.
        ActionListDiff.install(this);
        return view;
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.addAll(buildActions(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = requireContext();
        // Everything shown here can change while this screen is away - the
        // accessibility toggle and overlay permission in system Settings,
        // Reely Player being installed - and is only ever seen again once
        // the user comes back.
        showStatusLabel();
        setGuidanceDescription(SetupState.summary(context));
        // The row rebuild is skipped on the very first onResume() (right
        // after onCreateActions() already built the identical list): it has
        // nothing new to reflect yet, but can still replace the list's views
        // out from under a fast first click.
        if (!firstResume) {
            setActions(buildActions(context));
        }
        firstResume = false;
        maybeCheckForUpdate();
    }

    /**
     * Throttled GitHub release check - see UPDATE_CHECK_INTERVAL_MS and
     * Preferences.getUpdateCheckedAt(). Only meaningful for a sideloaded
     * install (a Play install updates through Play instead). Silent no-op
     * on failure - this is a background convenience check, not a
     * user-initiated action that needs its own error feedback. A check that
     * succeeds DOES say so either way, though - a toast for "already
     * current" and a separate one for "a newer version is now available" -
     * because without them "no update row appeared" was indistinguishable
     * from "the check never ran or failed". Both are passive: they steal no
     * focus and interfere with nothing.
     */
    private void maybeCheckForUpdate() {
        Context context = requireContext();
        if (InstallSource.isPlayStoreInstall(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - Preferences.getUpdateCheckedAt(context) < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        Preferences.setUpdateCheckedAt(context, now);
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            GithubReleaseClient.ReleaseInfo release = GithubReleaseClient.fetchLatest();
            if (release == null) {
                return;
            }
            if (!GithubReleaseClient.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                // Posted via the main Looper with the application context
                // (not runOnUiThread/requireContext()) so it still shows if
                // the user has already left this screen by the time the
                // network call finishes.
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext,
                        appContext.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_LONG).show());
                return;
            }
            Preferences.setUpdateAvailable(appContext, release.version, release.apkUrl);
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext,
                    appContext.getString(R.string.update_available_toast, release.version),
                    Toast.LENGTH_LONG).show());
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) {
                    setActions(buildActions(requireContext()));
                }
            });
        }).start();
    }

    /** Whether a cached, still-newer-than-installed release is on hand - see maybeCheckForUpdate(). */
    private static boolean isUpdateAvailable(Context context) {
        if (InstallSource.isPlayStoreInstall(context)) {
            return false;
        }
        String latest = Preferences.getUpdateLatestVersion(context);
        return !latest.isEmpty() && GithubReleaseClient.isNewer(latest, BuildConfig.VERSION_NAME);
    }

    private List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();

        // Above every other row, so it's the first thing seen. The toast
        // maybeCheckForUpdate() also shows is a one-time heads-up; this row
        // is what persists.
        if (isUpdateAvailable(context)) {
            actions.add(row(context, ACTION_UPDATE_AVAILABLE, R.string.settings_update_available,
                    getString(R.string.settings_update_available_description,
                            Preferences.getUpdateLatestVersion(context))));
        }

        boolean fireTv = FireTvSupport.isFireTv(context);
        boolean accessibilityOn = SetupState.isAccessibilityServiceEnabled(context);
        boolean detectionReady = SetupState.isDetectionReady(context);

        if (fireTv) {
            // Fire TV's own way of detecting a pick - see FireTvSupport. On
            // Google TV the accessibility service does the job and this
            // would only be a worse, permission-heavier duplicate.
            boolean fireTvReady = SetupState.isFireTvModeReady(context);
            actions.add(row(context, ACTION_FIRE_TV_MODE, R.string.settings_fire_tv_mode,
                    getString(fireTvReady
                            ? R.string.settings_fire_tv_status_enabled
                            : detectionReady
                                    ? R.string.settings_summary_not_enabled
                                    : R.string.settings_summary_required)));
        }
        actions.add(row(context, ACTION_ACCESSIBILITY, R.string.settings_section_accessibility,
                getString(accessibilityOn
                        ? R.string.settings_summary_enabled
                        // On Fire TV the service is only a second route, so
                        // its being off is never what's "needed".
                        : detectionReady || fireTv
                                ? R.string.settings_summary_not_enabled
                                : R.string.settings_summary_required)));
        actions.add(row(context, ACTION_OVERLAY, R.string.settings_section_overlay,
                getString(SetupState.isOverlayGranted(context)
                        ? R.string.settings_summary_enabled
                        : R.string.settings_summary_not_enabled)));

        actions.add(row(context, ACTION_SEARCH, R.string.settings_search_manually,
                getString(R.string.settings_search_manually_description)));
        actions.add(row(context, ACTION_DETECTION, R.string.settings_section_detection, null));
        actions.add(row(context, ACTION_YOUTUBE, R.string.settings_section_youtube, null));
        actions.add(row(context, ACTION_MORE, R.string.settings_section_more, null));

        return actions;
    }

    /** A row that opens another screen; description is an optional one-line status. */
    private GuidedAction row(Context context, long id, int titleRes, String description) {
        GuidedAction.Builder builder = new GuidedAction.Builder(context)
                .id(id)
                .title(getString(titleRes));
        if (description != null) {
            builder.description(description);
        }
        return builder.build();
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_ACCESSIBILITY) {
            GuidedStepSupportFragment.add(getFragmentManager(), new AccessibilitySettingsStepFragment());
        } else if (id == ACTION_FIRE_TV_MODE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new FireTvModeStepFragment());
        } else if (id == ACTION_OVERLAY) {
            GuidedStepSupportFragment.add(getFragmentManager(), new OverlaySettingsStepFragment());
        } else if (id == ACTION_SEARCH) {
            startActivity(new Intent(requireContext(), SearchActivity.class));
        } else if (id == ACTION_DETECTION) {
            GuidedStepSupportFragment.add(getFragmentManager(), new DetectionSettingsStepFragment());
        } else if (id == ACTION_YOUTUBE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new YouTubeSettingsStepFragment());
        } else if (id == ACTION_MORE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new MoreSettingsStepFragment());
        } else if (id == ACTION_UPDATE_AVAILABLE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new UpdateWarningStepFragment());
        }
    }
}
