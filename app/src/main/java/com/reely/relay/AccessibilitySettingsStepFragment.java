package com.reely.relay;

import android.content.Context;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** Accessibility: turning the service on, and help when Android blocks it. */
public class AccessibilitySettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_ENABLE_ACCESSIBILITY = 1;
    private static final long ACTION_RESTRICTED_SETTINGS_HELP = 2;

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_accessibility;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_accessibility_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_ACCESSIBILITY)
                .title(getString(R.string.settings_enable_accessibility))
                .description(getString(SetupState.isAccessibilityServiceEnabled(context)
                        ? R.string.settings_accessibility_status_enabled
                        : R.string.settings_accessibility_status_disabled))
                .build());
        if (shouldOfferRestrictedSettingsHelp(context)) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_RESTRICTED_SETTINGS_HELP)
                    .title(getString(R.string.settings_restricted_settings_help))
                    .description(getString(R.string.settings_restricted_settings_help_description))
                    .build());
        }
        return actions;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_ENABLE_ACCESSIBILITY) {
            SystemScreens.openAccessibility(requireContext());
        } else if (id == ACTION_RESTRICTED_SETTINGS_HELP) {
            GuidedStepSupportFragment.add(getFragmentManager(), new RestrictedSettingsStepFragment());
        }
    }

    /**
     * Whether to offer the Restricted Settings walkthrough as an extra
     * row. Always shown for any sideloaded install rather than only after
     * the user has already tried and failed to enable Accessibility - a
     * user who knows to look for it shouldn't have to trigger the failure
     * first. Hidden for a Play Store install, since Play is a trusted
     * installer and is never subject to this restriction in the first place.
     */
    private static boolean shouldOfferRestrictedSettingsHelp(Context context) {
        return !InstallSource.isPlayStoreInstall(context);
    }
}
