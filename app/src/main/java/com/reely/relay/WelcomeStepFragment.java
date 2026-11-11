package com.reely.relay;

import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * First screen of the first run: what ReelyRelay does, and the consent it
 * needs before doing it. Play policy requires prominent disclosure and
 * explicit consent before an AccessibilityService can act for a
 * non-accessibility purpose; RelayAccessibilityService itself also refuses
 * to act until Preferences.isDisclosureAccepted() is true, so this gate
 * holds regardless of distribution channel.
 */
public class WelcomeStepFragment extends RelayStepFragment {

    private static final long ACTION_ACCEPT = 1;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.welcome_title),
                getString(R.string.welcome_description),
                "",
                brandTile());
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_ACCEPT)
                .title(getString(R.string.welcome_accept))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_ACCEPT) {
            Preferences.setDisclosureAccepted(requireContext(), true);
            GuidedStepSupportFragment.add(getFragmentManager(), SetupFlow.afterWelcome(requireContext()));
        }
    }
}
