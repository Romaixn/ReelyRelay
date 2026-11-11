package com.reely.relay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Last screen of the first run. Reaching it is what marks setup as done -
 * from here on the app opens on the home screen (see MainActivity) - whether
 * or not every step was granted: a skipped step is then one row away on the
 * home screen, which says plainly what is still missing.
 */
public class SetupDoneStepFragment extends RelayStepFragment {

    private static final long ACTION_FINISH = 1;
    private static final long ACTION_SETTINGS = 2;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Preferences.setSetupCompleted(requireContext(), true);
        super.onCreate(savedInstanceState);
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        Context context = requireContext();
        return new GuidanceStylist.Guidance(title(context), SetupState.summary(context), "", brandTile());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reely Player may have been installed, or a permission granted,
        // while this screen was in the background.
        Context context = requireContext();
        setGuidanceTitle(title(context));
        setGuidanceDescription(SetupState.summary(context));
    }

    private String title(Context context) {
        return getString(SetupState.isWorking(context)
                ? R.string.setup_done_title
                : R.string.setup_done_title_incomplete);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_FINISH)
                .title(getString(R.string.setup_done_finish))
                .build());
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SETTINGS)
                .title(getString(R.string.setup_done_settings))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_FINISH) {
            // Back to the TV's home screen, which is where the next thing
            // to do actually is.
            requireActivity().finish();
        } else if (id == ACTION_SETTINGS) {
            // Restart on a clean stack rather than pushing the home screen
            // on top of the setup steps - Back from it should leave the
            // app, not walk back through setup.
            startActivity(new Intent(requireContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
    }
}
