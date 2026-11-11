package com.reely.relay;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Manual fallback for when the launcher's recommendation card carries no
 * usable accessibility text at all (confirmed on some devices/launcher
 * builds - see RelayAccessibilityService and WatchNowOverlay). The user
 * types the title themselves; everything past that point (TMDB lookup,
 * opening Reely Player) is identical to the automatic path.
 */
public class SearchStepFragment extends RelayStepFragment {

    private static final String TAG = "SearchStepFragment";

    private static final long ACTION_TITLE = 1;
    private static final long ACTION_SEARCH = 2;

    private String title = "";
    private boolean searching = false;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.search_title),
                getString(R.string.search_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TITLE)
                .title(getString(R.string.search_field_title))
                .description(title)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT)
                .build());
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SEARCH)
                .title(getString(R.string.search_action))
                .build());
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        if (action.getId() == ACTION_TITLE) {
            title = action.getDescription() != null ? action.getDescription().toString() : "";
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_SEARCH) {
            performSearch();
        }
    }

    private void performSearch() {
        String query = title.trim();
        if (query.isEmpty() || searching) {
            return;
        }
        searching = true;
        Context appContext = requireContext().getApplicationContext();
        Toast.makeText(appContext, R.string.search_searching, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            List<TitleCandidate> candidates = MetadataResolver.resolveCandidates(appContext, query);
            Log.d(TAG, "Search for \"" + query + "\" -> " + candidates.size() + " candidate(s)");
            if (!isAdded()) {
                return;
            }
            if (candidates.isEmpty()) {
                requireActivity().runOnUiThread(() -> {
                    searching = false;
                    Toast.makeText(appContext, R.string.search_not_found, Toast.LENGTH_LONG).show();
                });
                return;
            }
            if (Preferences.isChooserEnabled(appContext) && MetadataResolver.isAmbiguous(candidates)) {
                requireActivity().runOnUiThread(() -> {
                    searching = false;
                    GuidedStepSupportFragment.add(getFragmentManager(),
                            MatchChooserStepFragment.create(query, candidates));
                });
                return;
            }
            boolean opened = PlayerLauncher.openInReely(appContext, candidates.get(0));
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                searching = false;
                if (!opened) {
                    Toast.makeText(appContext,
                            appContext.getString(R.string.open_failed, ReelyPlayer.LABEL),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                requireActivity().finish();
            });
        }).start();
    }
}
