package com.reely.relay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;

import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Shows a QR code linking to docs/pair.html so the user can paste their
 * TMDB key on a phone instead of typing 32 characters with a remote - see
 * PhonePairing for how the phone's submission actually reaches the TV. A
 * received key is written straight to Preferences; TmdbKeyStepFragment
 * picks it up when this screen pops back to it.
 */
public class PhonePairingStepFragment extends RelayStepFragment {

    private static final long ACTION_CANCEL = 1;
    private static final long ACTION_AUTO_CLOSE_NOTE = 2;
    private static final long ACTION_NETWORK_WARNING = 3;
    private static final long TIMEOUT_MS = 5 * 60 * 1000;
    private static final int QR_SIZE_PX = 600;

    private final PhonePairing.Session session = new PhonePairing.Session();
    private String topic;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Set before super.onCreate(): GuidedStepSupportFragment.onCreate()
        // calls onCreateActions() internally, which starts the wait on it.
        topic = PhonePairing.newTopic();
        super.onCreate(savedInstanceState);
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String label = getString(R.string.tmdb_key_field);
        Bitmap qr = QrCodeGenerator.generate(PhonePairing.pairingUrl(topic, label), QR_SIZE_PX);
        Drawable icon = qr != null ? new BitmapDrawable(getResources(), qr) : null;
        return new GuidanceStylist.Guidance(
                getString(R.string.pairing_title),
                getString(R.string.pairing_description),
                label,
                icon);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_CANCEL)
                .title(getString(R.string.pairing_cancel))
                .build());
        // Sets expectations before the network warning below it - otherwise
        // there's no way to tell "still waiting normally" from "something's
        // wrong" while this screen just sits there.
        //
        // Both info rows put their text in description(), not title(): a
        // GuidedAction's title renders single-line and silently clips,
        // while description wraps. title() is left as an empty string
        // rather than never called, to avoid depending on how leanback's
        // binding code handles a null title across versions.
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_AUTO_CLOSE_NOTE)
                .title("")
                .description(getString(R.string.pairing_auto_close_note))
                .infoOnly(true)
                .focusable(false)
                .build());
        // Exists because of a real case: a user's router/DNS filter was
        // silently blocking ntfy.sh (the relay this whole flow depends on -
        // see PhonePairing), so every poll timed out and the screen just
        // sat here for its full five minutes, with no way to tell "this
        // will never work on this network" from "still waiting".
        SpannableString warning = new SpannableString(getString(R.string.pairing_network_warning));
        warning.setSpan(new ForegroundColorSpan(RelayStyle.color(context, R.color.warning)),
                0, warning.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_NETWORK_WARNING)
                .title("")
                .description(warning)
                .infoOnly(true)
                .focusable(false)
                .build());
        startWaiting();
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_CANCEL) {
            session.cancel();
            getFragmentManager().popBackStack();
        }
    }

    private void startWaiting() {
        Context appContext = requireContext().getApplicationContext();
        String pairingTopic = topic;
        PhonePairing.Session pairingSession = session;
        new Thread(() -> {
            String value = PhonePairing.awaitValue(pairingTopic, TIMEOUT_MS, pairingSession);
            if (pairingSession.isCancelled() || !isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (pairingSession.isCancelled() || !isAdded()) {
                    return;
                }
                if (value == null) {
                    Toast.makeText(appContext, R.string.pairing_timed_out, Toast.LENGTH_LONG).show();
                    getFragmentManager().popBackStack();
                    return;
                }
                Preferences.setTmdbApiKey(appContext, value.trim());
                Toast.makeText(appContext, R.string.pairing_received, Toast.LENGTH_SHORT).show();
                getFragmentManager().popBackStack();
            });
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // cancel(), not just marking cancelled: aborts an in-flight poll
        // immediately (Call.cancel()) instead of leaving it to run out its
        // connect timeout - see PhonePairing.Session's javadoc for the real
        // leak this closes.
        session.cancel();
    }
}
