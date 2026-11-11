package com.reely.relay;

import android.content.Context;

import androidx.leanback.app.GuidedStepSupportFragment;

/**
 * The order of the first-run steps: welcome, then how ReelyRelay finds out
 * what was picked (step 1), then the Watch now button (step 2), then done.
 * A step whose permission is already in place is skipped, so re-running
 * setup - or resuming it after leaving the app - goes straight to whatever
 * is still missing.
 */
final class SetupFlow {

    /** How many numbered steps there are, for the "Step 1 of 2" breadcrumb. */
    static final int STEP_COUNT = 2;

    private SetupFlow() {
    }

    static GuidedStepSupportFragment afterWelcome(Context context) {
        return SetupState.isDetectionReady(context)
                ? afterDetection(context)
                : new SetupDetectionStepFragment();
    }

    static GuidedStepSupportFragment afterDetection(Context context) {
        return SetupState.isOverlayGranted(context)
                ? new SetupDoneStepFragment()
                : new SetupOverlayStepFragment();
    }

    static GuidedStepSupportFragment afterOverlay(Context context) {
        return new SetupDoneStepFragment();
    }
}
