package com.reely.relay;

import android.content.Context;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Setup step 2: the "Display over other apps" permission behind the Watch
 * now button. Optional - without it a recognized pick opens Reely Player
 * directly (see TitleHandler) - so skipping is a real choice here, labeled
 * with what it leads to.
 */
public class SetupOverlayStepFragment extends SetupStepFragment {

    @Override
    protected int stepNumber() {
        return 2;
    }

    @Override
    protected int titleRes() {
        return R.string.setup_overlay_title;
    }

    @Override
    protected int descriptionRes() {
        return R.string.setup_overlay_description;
    }

    @Override
    protected boolean isSatisfied(Context context) {
        return SetupState.isOverlayGranted(context);
    }

    @Override
    protected GuidedStepSupportFragment nextStep(Context context) {
        return SetupFlow.afterOverlay(context);
    }

    @Override
    protected List<GuidedAction> buildActions(Context context, boolean satisfied) {
        List<GuidedAction> actions = new ArrayList<>();
        if (satisfied) {
            actions.add(continueAction(context));
        }
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_OPEN)
                .title(getString(R.string.setup_overlay_open))
                .description(getString(satisfied
                        ? R.string.settings_summary_enabled
                        : R.string.setup_overlay_open_description))
                .build());
        if (!satisfied) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_SKIP)
                    .title(getString(R.string.setup_overlay_skip))
                    .build());
        }
        return actions;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_CONTINUE || id == ACTION_SKIP) {
            advance();
        } else if (id == ACTION_OPEN) {
            awaitGrant();
            SystemScreens.openOverlayPermission(requireContext());
        }
    }
}
