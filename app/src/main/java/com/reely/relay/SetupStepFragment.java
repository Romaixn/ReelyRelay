package com.reely.relay;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Shared base for the two numbered first-run steps. Each one asks for a
 * single permission that is granted on a system screen outside the app, so
 * what they have in common is the trip there and back:
 *
 *  - the step sends the user off (awaitGrant()), and when they come back
 *    with the permission in place it moves on by itself - no "now press
 *    Continue" in between;
 *  - if they come back without it, the step stays put and offers its
 *    fallbacks (help, skip);
 *  - returning to a step that's already satisfied - with Back, from a later
 *    step - does NOT move on again, or Back could never get past it. It
 *    shows a Continue row instead.
 */
abstract class SetupStepFragment extends RelayStepFragment {

    protected static final long ACTION_CONTINUE = 1;
    protected static final long ACTION_OPEN = 2;
    protected static final long ACTION_HELP = 3;
    protected static final long ACTION_SKIP = 4;

    /** True only between sending the user to a system screen and their return. */
    private boolean awaitingGrant;

    /** See onResume() - skips its rebuild on the very first call. */
    private boolean firstResume = true;

    /** 1-based position among the numbered steps - see SetupFlow.STEP_COUNT. */
    protected abstract int stepNumber();

    protected abstract int titleRes();

    protected abstract int descriptionRes();

    /** Whether what this step asks for is already in place. */
    protected abstract boolean isSatisfied(Context context);

    protected abstract GuidedStepSupportFragment nextStep(Context context);

    /** The rows for the current state; called on create and on every later resume. */
    protected abstract List<GuidedAction> buildActions(Context context, boolean satisfied);

    /**
     * Called when the user returned from the system screen without what was
     * asked for - a chance to point them at the next best thing.
     */
    protected void onReturnedWithoutGrant() {
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(titleRes()),
                getString(descriptionRes()),
                getString(R.string.setup_step, stepNumber(), SetupFlow.STEP_COUNT),
                null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        // Rebuilt lists must re-bind rows to their new action objects - see ActionListDiff.
        ActionListDiff.install(this);
        return view;
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.addAll(buildActions(context, isSatisfied(context)));
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = requireContext();
        boolean satisfied = isSatisfied(context);
        boolean wasAwaitingGrant = awaitingGrant;
        awaitingGrant = false;
        if (wasAwaitingGrant && satisfied) {
            advance();
            return;
        }
        // Skipped on the very first onResume(), right after
        // onCreateActions() built the identical list - a redundant rebuild
        // there can replace the rows out from under a fast first click.
        if (!firstResume) {
            setActions(buildActions(context, satisfied));
        }
        firstResume = false;
        if (wasAwaitingGrant) {
            onReturnedWithoutGrant();
        }
    }

    /** Call right before sending the user to the system screen (or sub-screen) that grants this step. */
    protected void awaitGrant() {
        awaitingGrant = true;
    }

    protected void advance() {
        GuidedStepSupportFragment.add(getFragmentManager(), nextStep(requireContext()));
    }

    /** Moves D-pad focus to the row with this id, if it is on screen. */
    protected void focusAction(long actionId) {
        int position = findActionPositionById(actionId);
        if (position >= 0) {
            setSelectedActionPosition(position);
        }
    }

    protected GuidedAction continueAction(Context context) {
        return new GuidedAction.Builder(context)
                .id(ACTION_CONTINUE)
                .title(getString(R.string.setup_continue))
                .build();
    }
}
