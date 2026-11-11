package com.reely.relay;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Turns a detected title into a launch, via the shared
 * resolve -> confirm-overlay -> launch pipeline every trigger funnels
 * through. Shared so the Google TV accessibility path and the Fire TV
 * UsageStats+OCR path (FireTvWatcherService) run identical logic instead of
 * maintaining two copies.
 *
 * The only thing that differs between the two callers is what needs to
 * happen around an overlay-state change: the accessibility service has to
 * keep TYPE_WINDOW_STATE_CHANGED live-conditional on whether a match is
 * pending (refreshEventTypes()) and record its own overlay transitions for
 * the voice-search cooldown (markOwnOverlayActivity()); the Fire TV watcher
 * needs neither. Those two hooks are the Coordinator; the Fire TV watcher
 * passes no-ops.
 *
 * All public methods must be called on the main thread - handle() posts its
 * own background work to the executor and marshals UI back via the main
 * Handler.
 */
final class TitleHandler {

    private static final String TAG = "TitleHandler";

    /** Avoids re-handling the same title repeatedly. */
    private static final long DEBOUNCE_MS = 4000;

    /**
     * The two overlay-related side effects the accessibility service needs
     * and the Fire TV watcher doesn't - see class doc. Both are called on
     * the main thread.
     */
    interface Coordinator {
        void onOverlayStateChanged();
        void onOverlayActivity();
    }

    private final Context appContext;
    // The host used to build MatchTrayOverlay - NOT the application context.
    // The accessibility service passes itself here so the tray can use the
    // permission-free TYPE_ACCESSIBILITY_OVERLAY; the Fire TV watcher passes
    // its own Service, where that overlay type isn't available and the tray
    // falls back to TYPE_APPLICATION_OVERLAY (see MatchTrayOverlay.show()).
    private final Context trayHostContext;
    private final WatchNowOverlay overlay;
    private final ExecutorService backgroundExecutor;
    private final Handler mainHandler;
    private final Coordinator coordinator;

    private MatchTrayOverlay trayOverlay;
    private volatile String lastHandledTitle;
    private volatile long lastHandledAtMillis;

    TitleHandler(Context context, WatchNowOverlay overlay, ExecutorService backgroundExecutor,
                 Handler mainHandler, Coordinator coordinator) {
        this.trayHostContext = context;
        this.appContext = context.getApplicationContext();
        this.overlay = overlay;
        this.backgroundExecutor = backgroundExecutor;
        this.mainHandler = mainHandler;
        this.coordinator = coordinator;
    }

    /** Whether the ambiguous-match chooser tray is currently up - see RelayAccessibilityService.handleVoiceSearchDetailPage(). */
    boolean isChooserShowing() {
        return trayOverlay != null && trayOverlay.isShowing();
    }

    /** Tear down the chooser tray, if any - call from the owner's onDestroy(). */
    void shutdown() {
        if (trayOverlay != null) {
            trayOverlay.hide();
        }
    }

    void handle(String title) {
        handleInternal(java.util.Collections.singletonList(title), false);
    }

    /**
     * For a title read off the screen by OCR. Takes every distinct reading
     * the recognizer produced (best first) because any single read can be a
     * letter off. Each is searched in turn and the first that yields a
     * trustworthy match wins - see OcrMatchFilter for what "trustworthy"
     * means and why a plain search hit is not enough (OCR read "94%" for
     * "Talk to Me" and the top-relevance guess opened "Toshkent 94",
     * confirmed 2026-09-21). Typed or click-payload titles keep the looser
     * behavior via handle().
     */
    void handleOcr(List<String> titles) {
        handleInternal(titles, true);
    }

    private void handleInternal(List<String> titles, boolean fromOcr) {
        String title = titles.get(0);
        long now = System.currentTimeMillis();
        if (title.equals(lastHandledTitle) && (now - lastHandledAtMillis) < DEBOUNCE_MS) {
            return;
        }
        lastHandledTitle = title;
        lastHandledAtMillis = now;

        if (!ReelyPlayer.isInstalled(appContext)) {
            // Nothing to open the title in. Stepping aside entirely beats
            // offering a button that can only fail - the launcher's own
            // click goes ahead untouched, and the app's home screen says
            // what's missing. An OCR trigger may already have put the
            // loading state up for this very title, so clear that.
            Log.w(TAG, ReelyPlayer.LABEL + " isn't installed - leaving this click alone: " + title);
            if (overlay != null && overlay.hasPendingMatch()) {
                abandonPending();
            }
            return;
        }

        // Without the overlay permission, fall back to launching directly -
        // the user's explicit choice over doing nothing at all in that case.
        // With it, a confirm button appears as soon as possible (a loading
        // state now, swapped for "Watch now in Reely Player" once resolved)
        // and only pressing it launches anything - accidental-click
        // protection, which requires holding off on the launch until then.
        boolean confirmFirst = overlay != null && overlay.isPermissionGranted();
        if (confirmFirst) {
            overlay.showLoading();
            coordinator.onOverlayStateChanged();
            coordinator.onOverlayActivity();
        }

        backgroundExecutor.execute(() -> {
            final ResolvedTitle found = fromOcr
                    ? resolveOcrTitles(titles)
                    : new ResolvedTitle(title, MetadataResolver.resolveCandidates(appContext, title));
            final String usedTitle = found.title;
            final List<TitleCandidate> candidates = found.candidates;
            if (candidates.isEmpty()) {
                Log.w(TAG, "No TMDB match for: " + usedTitle);
                if (confirmFirst) {
                    // Resolution failed outright, so there's no pending
                    // match to ever offer back.
                    mainHandler.post(this::abandonPending);
                }
                return;
            }

            if (Preferences.isChooserEnabled(appContext) && MetadataResolver.isAmbiguous(candidates)) {
                Log.d(TAG, "Ambiguous match for " + usedTitle + " (" + candidates.size() + " candidates)");
                if (confirmFirst) {
                    mainHandler.post(() -> overlay.showConfirmAmbiguous(() -> showChooser(usedTitle, candidates)));
                } else {
                    mainHandler.post(() -> showChooser(usedTitle, candidates));
                }
                return;
            }

            TitleCandidate best = candidates.get(0);
            Log.d(TAG, "Resolved " + usedTitle + " -> tmdb:" + best.tmdbId + " (" + best.type + ")");

            if (confirmFirst) {
                mainHandler.post(() -> overlay.showConfirm(() -> open(best)));
            } else {
                open(best);
            }
        });
    }

    /**
     * Opens a settled candidate in Reely Player. A failure here is rare (the
     * app was uninstalled between the click and the confirm) but used to be
     * completely silent, so it now says so. Safe on any thread.
     */
    private void open(TitleCandidate candidate) {
        if (PlayerLauncher.openInReely(appContext, candidate)) {
            return;
        }
        mainHandler.post(() -> Toast.makeText(appContext,
                appContext.getString(R.string.open_failed, ReelyPlayer.LABEL),
                Toast.LENGTH_LONG).show());
    }

    /** Discards whatever the confirm button is holding. Main thread only; overlay must be non-null. */
    private void abandonPending() {
        overlay.abandon();
        coordinator.onOverlayStateChanged();
        coordinator.onOverlayActivity();
    }

    /** A search result together with the exact string that produced it (for the chooser's header and logs). */
    private static final class ResolvedTitle {
        final String title;
        final List<TitleCandidate> candidates;

        ResolvedTitle(String title, List<TitleCandidate> candidates) {
            this.title = title;
            this.candidates = candidates;
        }
    }

    /**
     * Tries each OCR reading in order (best first) and returns the first one
     * OcrMatchFilter trusts, or an empty result - never a guess. Runs on
     * backgroundExecutor (network).
     */
    private ResolvedTitle resolveOcrTitles(List<String> titles) {
        for (String reading : titles) {
            List<TitleCandidate> accepted =
                    OcrMatchFilter.accept(reading, MetadataResolver.resolveCandidates(appContext, reading));
            if (!accepted.isEmpty()) {
                Log.d(TAG, "OCR reading accepted: \"" + reading + "\" -> " + accepted.get(0).displayTitle);
                return new ResolvedTitle(reading, accepted);
            }
            Log.d(TAG, "OCR reading has no trustworthy match: \"" + reading + "\"");
        }
        return new ResolvedTitle(titles.get(0), java.util.Collections.<TitleCandidate>emptyList());
    }

    /** Runs on the main thread - see the mainHandler.post() call sites above. */
    private void showChooser(String title, List<TitleCandidate> candidates) {
        if (trayOverlay == null) {
            trayOverlay = new MatchTrayOverlay(trayHostContext, new MatchTrayOverlay.Listener() {
                @Override
                public void onDismissed() {
                    coordinator.onOverlayActivity();
                }

                @Override
                public void onCancelled() {
                    // Backing out of the chooser is a temporary dismissal,
                    // same as BACK on the confirm button itself: the match is
                    // still pending on the (handoff-concealed) WatchNowOverlay,
                    // so resume its normal reappear cycle. No-op when nothing
                    // is concealed - see WatchNowOverlay.resumeAfterHandoff().
                    if (overlay != null) {
                        overlay.resumeAfterHandoff();
                    }
                }

                @Override
                public void onPicked(TitleCandidate candidate) {
                    // A real pick settles the pending match for good - the
                    // concealed confirm button must not reappear over the
                    // player that's about to launch.
                    if (overlay != null) {
                        overlay.abandon();
                        coordinator.onOverlayStateChanged();
                    }
                }
            });
        }
        trayOverlay.show(title, candidates);
        coordinator.onOverlayActivity();
    }
}
