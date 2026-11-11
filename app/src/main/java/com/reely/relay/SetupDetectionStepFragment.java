package com.reely.relay;

import android.content.Context;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Setup step 1: how ReelyRelay finds out which recommendation was picked.
 * On Google TV that is switching its accessibility service on. On Fire TV
 * it is Fire TV mode instead - the current Fire TV home screen sends no
 * accessibility events at all (see FireTvSupport) - which has its own
 * screen for the two grants it needs.
 */
public class SetupDetectionStepFragment extends SetupStepFragment {

    @Override
    protected int stepNumber() {
        return 1;
    }

    @Override
    protected int titleRes() {
        return isFireTv() ? R.string.setup_fire_tv_title : R.string.setup_accessibility_title;
    }

    @Override
    protected int descriptionRes() {
        return isFireTv() ? R.string.setup_fire_tv_description : R.string.setup_accessibility_description;
    }

    @Override
    protected boolean isSatisfied(Context context) {
        return SetupState.isDetectionReady(context);
    }

    @Override
    protected GuidedStepSupportFragment nextStep(Context context) {
        return SetupFlow.afterDetection(context);
    }

    @Override
    protected List<GuidedAction> buildActions(Context context, boolean satisfied) {
        List<GuidedAction> actions = new ArrayList<>();
        if (satisfied) {
            actions.add(continueAction(context));
        }
        if (isFireTv()) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_OPEN)
                    .title(getString(R.string.setup_fire_tv_open))
                    .description(getString(satisfied
                            ? R.string.settings_summary_enabled
                            : R.string.settings_summary_not_enabled))
                    .build());
        } else {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_OPEN)
                    .title(getString(R.string.setup_accessibility_open))
                    .description(getString(satisfied
                            ? R.string.settings_summary_enabled
                            : R.string.setup_accessibility_open_description))
                    .build());
            if (!satisfied && !InstallSource.isPlayStoreInstall(context)) {
                // Android 13+ switches the toggle straight back off for an
                // app that wasn't installed from a store. Offered up front,
                // not only after a failed attempt - see onReturnedWithoutGrant().
                actions.add(new GuidedAction.Builder(context)
                        .id(ACTION_HELP)
                        .title(getString(R.string.settings_restricted_settings_help))
                        .description(getString(R.string.settings_restricted_settings_help_description))
                        .build());
            }
        }
        if (!satisfied) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_SKIP)
                    .title(getString(R.string.setup_skip))
                    .build());
        }
        return actions;
    }

    @Override
    protected void onReturnedWithoutGrant() {
        // Back from Accessibility with the service still off: on a
        // hand-installed app that almost always means Android's restricted
        // settings block, so put the fix under the user's thumb.
        focusAction(ACTION_HELP);
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_CONTINUE || id == ACTION_SKIP) {
            advance();
        } else if (id == ACTION_OPEN) {
            awaitGrant();
            if (isFireTv()) {
                GuidedStepSupportFragment.add(getFragmentManager(), new FireTvModeStepFragment());
            } else {
                SystemScreens.openAccessibility(requireContext());
            }
        } else if (id == ACTION_HELP) {
            // The fix happens outside the app (app info, or ADB); coming
            // back with the service on should move on just the same.
            awaitGrant();
            GuidedStepSupportFragment.add(getFragmentManager(), new RestrictedSettingsStepFragment());
        }
    }

    private boolean isFireTv() {
        return FireTvSupport.isFireTv(requireContext());
    }
}
