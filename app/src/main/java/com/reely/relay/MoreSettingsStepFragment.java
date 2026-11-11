package com.reely.relay;

import android.content.Context;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** More: the TMDB key, and About & help. */
public class MoreSettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_TMDB_KEY = 1;
    private static final long ACTION_ABOUT = 2;

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_more;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_more_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TMDB_KEY)
                .title(getString(R.string.settings_tmdb_key))
                .description(getString(tmdbKeyStatus(context)))
                .build());
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ABOUT)
                .title(getString(R.string.settings_about))
                .build());
        return actions;
    }

    /** Which key lookups are actually using - see Preferences.getEffectiveTmdbApiKey(). */
    private static int tmdbKeyStatus(Context context) {
        if (!Preferences.getTmdbApiKey(context).trim().isEmpty()) {
            return R.string.settings_tmdb_key_own;
        }
        return Preferences.hasTmdbApiKey(context)
                ? R.string.settings_tmdb_key_builtin
                : R.string.settings_tmdb_key_missing;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_TMDB_KEY) {
            GuidedStepSupportFragment.add(getFragmentManager(), new TmdbKeyStepFragment());
        } else if (id == ACTION_ABOUT) {
            GuidedStepSupportFragment.add(getFragmentManager(), new AboutStepFragment());
        }
    }
}
