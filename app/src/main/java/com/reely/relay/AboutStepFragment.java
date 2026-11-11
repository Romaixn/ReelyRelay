package com.reely.relay;

import android.content.Context;
import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * What the app is, which version is installed, and the two notices it has
 * to carry: the upstream license (ReelyRelay is built on TVRelay, whose
 * PolyForm license asks that its terms and "Required Notice" line reach
 * everyone who gets a copy - and for most people the APK is the copy), and
 * the attribution TMDB's API terms require.
 */
public class AboutStepFragment extends RelayStepFragment {

    private static final long ACTION_ACCESSIBILITY_HELP = 1;
    private static final long ACTION_CLOSE = 2;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String description = getString(R.string.about_description, BuildConfig.VERSION_NAME)
                + "\n\n" + getString(R.string.about_license) + " " + getString(R.string.about_license_url)
                + "\n" + getString(R.string.about_required_notice)
                + "\n\n" + getString(R.string.about_tmdb_attribution);
        return new GuidanceStylist.Guidance(
                getString(R.string.about_title),
                description,
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        if (!InstallSource.isPlayStoreInstall(context)) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_ACCESSIBILITY_HELP)
                    .title(getString(R.string.settings_restricted_settings_help))
                    .description(getString(R.string.settings_restricted_settings_help_description))
                    .build());
        }
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_CLOSE)
                .title(getString(R.string.about_close))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_ACCESSIBILITY_HELP) {
            GuidedStepSupportFragment.add(getFragmentManager(), new RestrictedSettingsStepFragment());
        } else if (id == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
