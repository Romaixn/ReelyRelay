package com.reely.relay;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * The optional personal TMDB key. Lookups work out of the box with the key
 * bundled at build time (see Preferences.getEffectiveTmdbApiKey); entering
 * one here overrides it, e.g. if the shared one is ever rate-limited.
 */
public class TmdbKeyStepFragment extends RelayStepFragment {

    private static final long ACTION_TMDB_KEY = 1;
    private static final long ACTION_PAIR_PHONE = 2;
    private static final long ACTION_TEST_KEY = 3;
    private static final long ACTION_SAVE = 4;

    private String tmdbApiKey;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Load before super.onCreate(): GuidedStepSupportFragment.onCreate()
        // calls onCreateActions() internally, so fields it reads must
        // already be set.
        tmdbApiKey = Preferences.getTmdbApiKey(requireContext());
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Picks up a value just received via phone pairing -
        // PhonePairingStepFragment writes straight to Preferences and pops
        // back to this screen, rather than passing a result back through
        // any fragment-to-fragment API.
        String refreshed = Preferences.getTmdbApiKey(requireContext());
        if (!refreshed.equals(tmdbApiKey)) {
            tmdbApiKey = refreshed;
            GuidedAction keyAction = findActionById(ACTION_TMDB_KEY);
            if (keyAction != null) {
                keyAction.setDescription(tmdbApiKey);
                notifyActionChanged(findActionPositionById(ACTION_TMDB_KEY));
            }
        }
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        // A build made without TMDB_API_KEY can't look anything up until a
        // key is entered here, so say that instead of "works straight away".
        boolean hasBundledKey = !BuildConfig.TMDB_API_KEY.trim().isEmpty();
        return new GuidanceStylist.Guidance(
                getString(R.string.tmdb_key_title),
                getString(hasBundledKey ? R.string.tmdb_key_description : R.string.tmdb_key_description_missing),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TMDB_KEY)
                .title(getString(R.string.tmdb_key_field))
                .description(tmdbApiKey)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PAIR_PHONE)
                .title(getString(R.string.tmdb_key_pair_phone))
                .description(getString(R.string.tmdb_key_pair_phone_description))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TEST_KEY)
                .title(getString(R.string.tmdb_key_test))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SAVE)
                .title(getString(R.string.tmdb_key_save))
                .build());
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        if (action.getId() == ACTION_TMDB_KEY) {
            tmdbApiKey = action.getDescription() != null ? action.getDescription().toString().trim() : "";
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_PAIR_PHONE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new PhonePairingStepFragment());
        } else if (id == ACTION_TEST_KEY) {
            testKey();
        } else if (id == ACTION_SAVE) {
            Preferences.setTmdbApiKey(context, tmdbApiKey);
            Toast.makeText(context, R.string.tmdb_key_saved, Toast.LENGTH_SHORT).show();
            getFragmentManager().popBackStack();
        }
    }

    private void testKey() {
        Context appContext = requireContext().getApplicationContext();
        // Blank field -> test the bundled default instead of unconditionally
        // failing, since that's what lookups actually use in that case (see
        // Preferences.getEffectiveTmdbApiKey).
        String keyToTest = !tmdbApiKey.trim().isEmpty() ? tmdbApiKey.trim() : BuildConfig.TMDB_API_KEY.trim();
        new Thread(() -> {
            boolean ok = TmdbClient.testApiKey(keyToTest);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> Toast.makeText(
                    appContext,
                    ok ? R.string.tmdb_key_test_success : R.string.tmdb_key_test_failure,
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
