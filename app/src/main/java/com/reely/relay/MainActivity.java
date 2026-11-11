package com.reely.relay;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.GuidedStepSupportFragment;

/**
 * Hosts every screen as a stack of GuidedStepSupportFragments. No activity
 * layout: GuidedStepSupportFragment.addAsRoot manages its own content view
 * directly on the activity's decor view.
 *
 * Opens on the first-run setup until it has been walked through once, and
 * on the home screen from then on.
 */
public class MainActivity extends FragmentActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, firstScreen(), android.R.id.content);
        }
    }

    private GuidedStepSupportFragment firstScreen() {
        if (Preferences.isSetupCompleted(this)) {
            return new HomeStepFragment();
        }
        // Left the app partway through setup: pick up where it stopped
        // rather than asking for consent a second time.
        return Preferences.isDisclosureAccepted(this)
                ? SetupFlow.afterWelcome(this)
                : new WelcomeStepFragment();
    }
}
