package com.reely.relay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches the Google TV launcher (com.google.android.apps.tv.launcherx) and
 * the Fire TV launcher (com.amazon.tv.launcher) for the user picking a movie
 * or show, finds its title, looks it up on TMDB, and offers to open it in
 * Reely Player instead of whatever the launcher would normally do (see
 * TitleHandler for that pipeline and ReelyPlayer for the deep link it ends
 * in).
 *
 * The title comes from one of two places on Google TV:
 *  - the click on the recommendation card, when the card carries it in its
 *    content-desc - immediate, so the button appears as the page opens;
 *  - otherwise the launcher's own detail page, which the click (or a voice
 *    search) leads to and which always shows the title - see
 *    LauncherDetailPage and handleDetailPage().
 * Reading the screen off pixels (OCR) is only a last resort for when
 * neither works.
 *
 * Every other click is left alone - normal launcher behavior (app icons,
 * the "Your apps" row, etc.) is untouched.
 */
public class RelayAccessibilityService extends AccessibilityService {

    private static final String TAG = "ReelyRelay";

    private static final String GOOGLE_TV_LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String AMAZON_LAUNCHER_PACKAGE = "com.amazon.tv.launcher";

    // Every locale-dependent card-shape marker (title/subscription/YouTube
    // markers, the sponsored-ad label) lives in res/values/arrays.xml
    // (+ values-es, values-da, values-fr), resolved through
    // LauncherLocale.systemResources() - see that class and those files for
    // the markers themselves and why they're not plain Java constants.

    // The launcher's detail page for a movie or show. Confirmed on a Google
    // TV Streamer (2026-11-11): launcherx/.entity.EntityActivity, the same
    // for a card click and a voice search.
    private static final String DETAIL_PAGE_CLASS_MARKER = "EntityActivity";

    // When to look for the title after the detail page opens, in ms from
    // the window-state event. The page fills in a moment after it appears;
    // the last attempt is a generous margin for a slow box.
    private static final long[] DETAIL_PAGE_READ_DELAYS_MS = {0, 200, 500, 1000, 1800, 3000};

    // A click that carried its own title opens the detail page within this
    // window; the page's title is then the same title, already being
    // looked up - see onDetailPageTitle().
    private static final long CLICK_TO_DETAIL_PAGE_MS = 3000;

    // The title of the detail page last seen, so the page is offered once
    // per visit, not again each time focus returns to it (our own button
    // hiding or closing, coming back from Reely Player). Cleared when the
    // user is back on the home screen. Main thread only.
    private String lastDetailPageTitle;

    // Bumped by every new detail page or return home, so a read still
    // retrying for an earlier page stops. Main thread only.
    private int detailPageReadGeneration;

    private static final String FIRE_TV_MAIN_IMAGE_ID = "com.amazon.tv.launcher:id/main_image";

    // The launcher's home screen - see onLauncherHome(). Confirmed on a
    // Google TV Streamer (2026-11-11): its HOME activity is
    // launcherx/.home.HomeActivity. Matched with contains(), as is
    // DETAIL_PAGE_CLASS_MARKER, so a short or fully-qualified class name
    // both work. If this ever stops matching, a pending button is still
    // dropped by WatchNowOverlay's MAX_LIFETIME_MS cap, which logs it.
    private static final String LOBBY_CLASS_MARKER = "HomeActivity";

    // Avoids re-handling the same title repeatedly.
    private static final long DEBOUNCE_MS = 4000;
    private volatile String lastHandledTitle;
    private volatile long lastHandledAtMillis;

    // Separate, time-only debounce for the OCR fallback (see
    // triggerOcrCapture()) - there's no title yet at trigger time, so the
    // title-based debounce above doesn't apply.
    private static final long OCR_DEBOUNCE_MS = 3000;
    private volatile long lastOcrTriggerAtMillis;

    // Cooldown after ANY of our own overlay transitions (WatchNowOverlay
    // showing/hiding/abandoning, MatchTrayOverlay opening/closing) before a
    // window-state event is trusted as the user opening a detail page - see
    // onDetailPageTitle(). Every one of those transitions hands window focus
    // back to the launcher's detail page, which fires a window-state event
    // that looks exactly like the page opening. Confirmed real bug this
    // fixes (2026-08-25, on-device): overlay.hasPendingMatch() alone isn't
    // enough - pressing OK on an ambiguous match hands off to
    // MatchTrayOverlay (abandoning WatchNowOverlay first), and dismissing
    // THAT tray (Back) hands focus back to the launcher's EntityActivity,
    // producing another lookalike window-state event with nothing pending
    // on either overlay by then. (2026-08-26 update: with the reappear
    // setting on, that handoff no longer abandons WatchNowOverlay - see
    // its concealForHandoff() - so hasPendingMatch() now also covers that
    // specific sequence; this cooldown still covers the reappear-off path,
    // where the handoff does still abandon, plus every other transition.)
    // Every UI transition either overlay makes
    // is a plausible cause of a spurious focus-change event, not just the
    // ones this project happened to catch on-device first - so this is
    // marked broadly (see markOwnOverlayActivity()'s call sites) rather
    // than trying to enumerate exactly which transitions can trigger it.
    private static final long OVERLAY_TRANSITION_COOLDOWN_MS = 2000;
    private volatile long lastOwnOverlayActivityAtMillis;

    private void markOwnOverlayActivity() {
        lastOwnOverlayActivityAtMillis = System.currentTimeMillis();
    }

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WatchNowOverlay overlay;
    private OcrCaptureManager ocrCaptureManager;
    // Shared resolve -> confirm-overlay -> launch pipeline (also owns the
    // ambiguous-match chooser tray) - see TitleHandler. Extracted so the
    // Fire TV UsageStats+OCR path (FireTvWatcherService) runs the same logic.
    private TitleHandler titleHandler;
    private YouTubeClickGate youTubeClickGate;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        overlay = new WatchNowOverlay(this);

        // The same settings as res/xml/accessibility_service_config.xml,
        // applied again in code. Upstream TVRelay relied on this alone: its
        // manifest named the config meta-data wrongly, so Android never read
        // that file - which is also why reading window content and taking
        // screenshots both failed for it, and why it fell back on OCR. The
        // two must agree; canRetrieveWindowContent itself can only come from
        // the file.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        // Window-state events, always: they are how the launcher's detail
        // page is noticed (handleDetailPage()), how a return home abandons a
        // pending button (onLauncherHome()), and how
        // YouTubeClickGate confirms a click opened a YouTube video. They are
        // rare - one per screen change in the packages below.
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        // The YouTube app is here only for its window-state events - see
        // YouTubeClickGate. Its clicks are never handled.
        info.packageNames = new String[]{GOOGLE_TV_LAUNCHER_PACKAGE, AMAZON_LAUNCHER_PACKAGE,
                YouTubeClickGate.YOUTUBE_TV_PACKAGE};
        // View ids are how the detail page's title is found
        // (LauncherDetailPage) and the Fire TV card lookup below works.
        // Interactive windows let the launcher's window be read even while
        // our own button holds focus - see launcherWindowRoot().
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        ocrCaptureManager = new OcrCaptureManager(this, overlay);
        youTubeClickGate = new YouTubeClickGate(this, mainHandler, this::launchYouTubeRedirect);

        // The coordinator records overlay transitions for the detail-page
        // cooldown (markOwnOverlayActivity()). Passed the service (this) as
        // the tray host so the chooser can use the permission-free
        // TYPE_ACCESSIBILITY_OVERLAY - see MatchTrayOverlay.
        titleHandler = new TitleHandler(this, overlay, backgroundExecutor, mainHandler,
                this::markOwnOverlayActivity);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence eventPackage = event.getPackageName();
        if (eventPackage != null && YouTubeClickGate.YOUTUBE_TV_PACKAGE.contentEquals(eventPackage)) {
            // Subscribed only for its window events (YouTubeClickGate) -
            // clicks inside the YouTube app itself are not launcher cards.
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && youTubeClickGate != null) {
                youTubeClickGate.onYouTubeWindow();
            }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(event);
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }

        if (!Preferences.isDisclosureAccepted(this)) {
            // Play policy requires explicit consent before this service
            // acts; also a sane default outside Play - sideloaded users
            // still have to get past the disclosure screen on first run.
            return;
        }

        // Unconditional diagnostic log: needed because every branch below
        // returns silently when a click doesn't match the expected card
        // format - without this there is no way to see what a real card's
        // event actually looks like on a given device/locale/screen. Remove
        // once title extraction is confirmed working across real devices.
        AccessibilityNodeInfo diagSource = event.getSource();
        Log.d(TAG, "Click event: pkg=" + event.getPackageName()
                + " class=" + event.getClassName()
                + " contentDesc=[" + event.getContentDescription() + "]"
                + " text=" + event.getText()
                + " srcViewId=" + (diagSource != null ? diagSource.getViewIdResourceName() : null));

        CharSequence packageName = event.getPackageName();
        if (packageName != null && AMAZON_LAUNCHER_PACKAGE.contentEquals(packageName)) {
            String title = extractFireTvTitle(event);
            if (title != null && !title.trim().isEmpty()) {
                Log.d(TAG, "Movie/show detected (Fire TV): " + title);
                handleMovieClick(title);
            }
            return;
        }

        // The row card's content-desc travels on event.getContentDescription(),
        // NOT event.getSource().getContentDescription() (always null for
        // these cards).
        CharSequence descCharSeq = event.getContentDescription();
        String desc = descCharSeq != null ? descCharSeq.toString() : null;
        if (desc != null && !desc.trim().isEmpty()) {
            if (isYouTubeCard(event, desc)) {
                String title = extractTitle(desc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "YouTube-looking card, awaiting confirmation: " + title);
                    handleYouTubeClick(title);
                }
                return;
            }
            if (isMovieOrShowCard(event, desc)) {
                String title = extractTitle(desc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "Movie/show detected: " + title);
                    handleMovieClick(title);
                }
                return;
            }
            // Neither card check matched - but a hero banner can carry a
            // content-desc AND its own [Title, subtitle, synopsis, CTA]
            // text at the same time, and extractHeroTitle()'s shape match
            // is locale agnostic where the two checks above are not. Before
            // this existed the branch returned unconditionally here and
            // such a card was dropped silently, which is the confirmed
            // root cause of GitHub issue #3's reported symptom (a Danish
            // ViewGroup hero card whose title sat in text[0] the whole
            // time). Passing desc in makes extractHeroTitle() apply its
            // stricter corroboration rules - see its javadoc.
            String heroTitleWithDesc = extractHeroTitle(event, desc);
            if (heroTitleWithDesc != null) {
                Log.d(TAG, "Movie/show detected (hero banner, with content-desc): " + heroTitleWithDesc);
                handleMovieClick(heroTitleWithDesc);
            }
            return;
        }

        // Big autoplay hero banner (top row of Home): title travels in
        // event.getText(), not contentDescription. Format:
        // [Title, subtitle, synopsis, CTA]. Sponsored entries lead with a
        // sponsored label (R.array.sponsored_labels) and are ignored - not
        // real recommendations.
        String heroTitle = extractHeroTitle(event, null);
        if (heroTitle != null) {
            Log.d(TAG, "Movie/show detected (hero banner): " + heroTitle);
            handleMovieClick(heroTitle);
            return;
        }

        // Navbar tabs ("Apps"/"Home"/"Live") have a null contentDescription
        // too, but carry their own label in event.getText() - a genuinely
        // blank "Top picks" card has both empty. Without this check, a
        // navbar click fell into the source==null branch below like a
        // blank card, spuriously triggering OCR (confirmed on-device).
        List<CharSequence> text = event.getText();
        if (text != null && !text.isEmpty()) {
            Log.d(TAG, "Ignoring chrome click with a text label but no contentDesc: " + text);
            return;
        }

        // A card with no title in its click at all - every card, on a
        // Google TV Streamer in French (2026-11-11). Nothing to do here:
        // the click opens the launcher's detail page, which does show the
        // title, and handleDetailPage() takes it from there.
        //
        // Some rows populate content-desc a moment after the click, though,
        // so the card itself gets one more look shortly after.
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            Log.d(TAG, "No title in this click - waiting for the detail page");
            return;
        }
        mainHandler.postDelayed(() -> {
            source.refresh();
            CharSequence delayed = source.getContentDescription();
            String delayedDesc = delayed != null ? delayed.toString() : null;
            if (delayedDesc == null || delayedDesc.trim().isEmpty()) {
                Log.d(TAG, "No title in this click - waiting for the detail page");
                return;
            }
            if (isYouTubeCard(event, delayedDesc)) {
                String title = extractTitle(delayedDesc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "YouTube-looking card, awaiting confirmation (delayed): " + title);
                    handleYouTubeClick(title);
                }
                return;
            }
            if (isMovieOrShowCard(event, delayedDesc)) {
                String title = extractTitle(delayedDesc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "Movie/show detected (delayed): " + title);
                    handleMovieClick(title);
                }
                return;
            }
            // Same hero-banner fallback as the immediate branch above -
            // this branch had the identical "give up if isMovieOrShowCard
            // fails" gap. NOTE: `event` may already have been recycled by
            // the framework by the time this runs on pre-API-33 devices (a
            // pre-existing condition, not introduced by this fallback), in
            // which case getClassName()/getText() come back empty and this
            // is simply a no-op - never a wrong detection.
            String delayedHeroTitle = extractHeroTitle(event, delayedDesc);
            if (delayedHeroTitle != null) {
                Log.d(TAG, "Movie/show detected (hero banner, with content-desc, delayed): "
                        + delayedHeroTitle);
                handleMovieClick(delayedHeroTitle);
            }
        }, 600);
    }

    /**
     * Every TYPE_WINDOW_STATE_CHANGED from the Google TV launcher: the user
     * opening a detail page, or coming back to the home screen.
     */
    private void handleWindowStateChanged(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !GOOGLE_TV_LAUNCHER_PACKAGE.contentEquals(packageName)) {
            return;
        }
        CharSequence className = event.getClassName();
        String windowClass = className != null ? className.toString() : "";
        if (windowClass.contains(LOBBY_CLASS_MARKER)) {
            onLauncherHome(windowClass);
        } else if (windowClass.contains(DETAIL_PAGE_CLASS_MARKER)) {
            handleDetailPage();
        }
    }

    /**
     * Back on the launcher's home screen: whatever detail page was open is
     * done with, and a button still pending for it is dropped for good
     * rather than reappearing over the home screen.
     */
    private void onLauncherHome(String windowClass) {
        detailPageReadGeneration++;
        lastDetailPageTitle = null;
        if (overlay != null && overlay.hasPendingMatch()) {
            Log.d(TAG, "Back on the launcher home screen (" + windowClass + ") - dropping the pending button");
            overlay.notifyLauncherLobby();
        }
    }

    /**
     * The launcher opened a detail page - from a card click or a voice
     * search alike. Its title is read off the page (LauncherDetailPage),
     * retrying while the page fills in; see onDetailPageTitle() for when it
     * is actually offered.
     */
    private void handleDetailPage() {
        if (!Preferences.isDisclosureAccepted(this)) {
            return;
        }
        readDetailPage(++detailPageReadGeneration, 0);
    }

    /** One attempt at reading the page; schedules the next until DETAIL_PAGE_READ_DELAYS_MS runs out. */
    private void readDetailPage(int generation, int attempt) {
        if (generation != detailPageReadGeneration) {
            return; // A newer page, or a return home, superseded this one.
        }
        LauncherDetailPage page;
        try {
            page = LauncherDetailPage.read(launcherWindowRoot());
        } catch (RuntimeException e) {
            // A node can go stale mid-read when the page changes under it.
            // Never let that take the service down - just try again.
            Log.w(TAG, "Could not read the detail page", e);
            page = null;
        }
        if (page != null) {
            onDetailPageTitle(page);
            return;
        }
        int next = attempt + 1;
        if (next < DETAIL_PAGE_READ_DELAYS_MS.length) {
            mainHandler.postDelayed(() -> readDetailPage(generation, next),
                    DETAIL_PAGE_READ_DELAYS_MS[next] - DETAIL_PAGE_READ_DELAYS_MS[attempt]);
            return;
        }
        onDetailPageWithoutTitle();
    }

    /**
     * The launcher's own window, looked up among all on-screen windows
     * rather than taken as "the active window": while our Watch now button
     * is up, the button is the active window.
     */
    private AccessibilityNodeInfo launcherWindowRoot() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            CharSequence rootPackage = root != null ? root.getPackageName() : null;
            if (rootPackage != null && GOOGLE_TV_LAUNCHER_PACKAGE.contentEquals(rootPackage)) {
                return root;
            }
        }
        return getRootInActiveWindow();
    }

    /**
     * Offers the title read off a detail page - unless this page has already
     * been dealt with. The launcher fires the same window-state event every
     * time focus comes back to the page, which happens a lot: our own
     * button appearing, hiding or closing, the chooser closing, the user
     * coming back from Reely Player. So a page is offered once per visit:
     *
     *  - the same title as the page last seen is a return to that page, not
     *    a new one (cleared on the home screen - see onLauncherHome());
     *  - a title taken from the click moments ago is this page's own title,
     *    already being looked up;
     *  - anything right after our own overlay changed is a side effect of
     *    that change, not the user.
     *
     * That last guard comes from TVRelay, where every version without it
     * looped or dropped the button (2026-08-25: confirmed on-device, three
     * times over - the button hiding, the chooser closing, and the button
     * closing all hand focus back to this page). There is no complete list
     * of which overlay transitions cause it, hence one cooldown bumped by
     * all of them (markOwnOverlayActivity()) rather than per-case checks.
     */
    private void onDetailPageTitle(LauncherDetailPage page) {
        if (page.title.equals(lastDetailPageTitle)) {
            return;
        }
        lastDetailPageTitle = page.title;
        String seen = "Detail page: " + page.title
                + (page.year != LauncherDetailPage.UNKNOWN_YEAR ? " (" + page.year + ")" : "");
        if (titleHandler.millisSinceLastHandled() < CLICK_TO_DETAIL_PAGE_MS) {
            // The click carried the title and its lookup is under way, but
            // the page knows the year, which settles same-name titles - the
            // click's lookup would ask "which Carrie?" where the page knows
            // it's the 2026 one. Worth redoing as long as only the button is
            // waiting on it: with no button, the click's lookup may already
            // have opened Reely Player.
            if (page.year != LauncherDetailPage.UNKNOWN_YEAR
                    && overlay != null && overlay.hasPendingMatch() && !titleHandler.isChooserShowing()) {
                Log.d(TAG, seen + " - redoing the click's lookup with the year");
                titleHandler.handle(page.title, page.year);
            } else {
                Log.d(TAG, seen + " - already taken from the click");
            }
            return;
        }
        if ((System.currentTimeMillis() - lastOwnOverlayActivityAtMillis) < OVERLAY_TRANSITION_COOLDOWN_MS
                || titleHandler.isChooserShowing()) {
            Log.d(TAG, seen + " - focus back from our own overlay, ignored");
            return;
        }
        Log.d(TAG, "Movie/show detected (" + seen + ")");
        titleHandler.handle(page.title, page.year);
    }

    /**
     * A detail page whose title couldn't be read - a launcher build that
     * changed the title's id, or a device that still won't let the service
     * read window content. Reading the screen's pixels is all that's left,
     * and only if the user turned that on (triggerOcrCapture() checks).
     * Skipped when the click already found the title.
     */
    private void onDetailPageWithoutTitle() {
        long readWindowMs = DETAIL_PAGE_READ_DELAYS_MS[DETAIL_PAGE_READ_DELAYS_MS.length - 1];
        if ((overlay != null && overlay.hasPendingMatch())
                || titleHandler.isChooserShowing()
                || titleHandler.millisSinceLastHandled() < CLICK_TO_DETAIL_PAGE_MS + readWindowMs
                || (System.currentTimeMillis() - lastOwnOverlayActivityAtMillis) < OVERLAY_TRANSITION_COOLDOWN_MS) {
            return;
        }
        Log.d(TAG, "Detail page without a readable title - falling back to reading the screen");
        triggerOcrCapture();
    }

    /**
     * Last resort for a detail page whose title couldn't be read from its
     * nodes - see onDetailPageWithoutTitle(). Captures the screen, extracts
     * a title via on-device OCR (OcrCaptureManager, itself gated behind its
     * own explicit consent screen - see Preferences.isOcrFallbackEnabled())
     * and feeds the result into the exact same resolve/confirm/launch
     * pipeline a normal click already uses, rather than a second, parallel
     * path. A no-op unless the user turned the fallback on.
     */
    private void triggerOcrCapture() {
        if (!Preferences.isOcrFallbackEnabled(this)) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - lastOcrTriggerAtMillis) < OCR_DEBOUNCE_MS) {
            return;
        }
        lastOcrTriggerAtMillis = now;

        boolean confirmFirst = overlay != null && overlay.isPermissionGranted();
        if (confirmFirst) {
            overlay.showLoading();
            markOwnOverlayActivity();
        }

        ocrCaptureManager.requestTitleCapture(new OcrCaptureManager.Callback() {
            @Override
            public void onTitlesExtracted(java.util.List<String> titles) {
                Log.d(TAG, "OCR-detected titles: " + titles);
                titleHandler.handleOcr(titles);
            }

            @Override
            public void onFailure(OcrCaptureManager.FailureReason reason) {
                Log.w(TAG, "OCR fallback failed: " + reason);
                if (confirmFirst) {
                    // abandon(), not hide(): nothing was ever resolved, so
                    // there's no pending match to offer back later - same
                    // reasoning as the resolution-failure paths in
                    // handleMovieClick() below.
                    overlay.abandon();
                    markOwnOverlayActivity();
                }
            }
        });
    }

    private String extractFireTvTitle(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return null;
        }
        return findFireTvMainImageDescription(source);
    }

    private String findFireTvMainImageDescription(AccessibilityNodeInfo node) {
        if (FIRE_TV_MAIN_IMAGE_ID.equals(node.getViewIdResourceName())) {
            CharSequence desc = node.getContentDescription();
            if (desc != null && !desc.toString().trim().isEmpty()) {
                return desc.toString();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            String result = findFireTvMainImageDescription(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Title out of the big autoplay hero banner's own
     * [Title, subtitle, synopsis, CTA] text list.
     *
     * @param contentDesc the click event's content-desc, or null if it had
     *        none. Null means the caller reached here on the original
     *        "no content-desc at all" path, where the ViewGroup + non-empty
     *        text shape is signal enough on its own (verified live on the
     *        ONN: "Mayday" resolved correctly through exactly this path).
     *        Non-null means the caller already failed both card checks and
     *        is trying this as a last resort, where that shape alone is NOT
     *        enough: a launcher banner/ad ("Netflix, Watch now") is also a
     *        ViewGroup with text, and would otherwise be handed to the
     *        metadata resolver as the title "WATCH NOW". Two extra
     *        corroborations are required in that case, both satisfied by
     *        the real issue #3 card
     *        (text=[Underverden, Anbefalet til dig, En kirurg..., Se nu],
     *        contentDesc="Anbefalet til dig. Underverden. En kirurg... Se nu.")
     *        and both failed by the "Netflix, Watch now" banner shape
     *        (one text entry, and its CTA text is not a substring of the
     *        content-desc). Failing these means falling back to exactly
     *        today's behavior - the click is dropped - so this path can
     *        only ever ADD detections, never change an existing one.
     */
    private String extractHeroTitle(AccessibilityEvent event, String contentDesc) {
        CharSequence className = event.getClassName();
        if (className == null || !"android.view.ViewGroup".contentEquals(className)) {
            return null;
        }
        List<CharSequence> parts = event.getText();
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        CharSequence firstRaw = parts.get(0);
        if (firstRaw == null) {
            return null;
        }
        String first = firstRaw.toString().trim();
        if (first.isEmpty()) {
            return null;
        }
        // Sponsored/ad banners lead with a sponsored label instead of a
        // title - see R.array.sponsored_labels. equalsIgnoreCase is
        // locale-independent in Java (per-character), which is what we want
        // here since the label may be in any language.
        for (String sponsored : LauncherLocale.systemResources(this)
                .getStringArray(R.array.sponsored_labels)) {
            if (first.equalsIgnoreCase(sponsored)) {
                return null;
            }
        }
        if (contentDesc != null && (parts.size() < 2 || !contentDesc.contains(first))) {
            return null;
        }
        return first;
    }

    private boolean isYouTubeCard(AccessibilityEvent event, String contentDesc) {
        for (String marker : LauncherLocale.systemResources(this)
                .getStringArray(R.array.youtube_markers)) {
            // The isEmpty() guard is not cosmetic: contains("") is always
            // true, so one blank <item> would make every card a YouTube card.
            if (!marker.isEmpty() && contentDesc.contains(marker)) {
                return true;
            }
        }
        // Real fallback, confirmed 2026-08-25 - see R.array.youtube_markers' comment.
        // A YouTube video card is the same android.view.View/empty-text
        // shape as a real movie/show card (see isMovieOrShowCard() below),
        // but with a BARE title and no comma-separated segment at all -
        // every real movie/show card seen so far always has at least one
        // (a price, rating, provider, or synopsis fragment after a comma).
        // Mutually exclusive with isMovieOrShowCard() by construction - a
        // comma-less content-desc could never have matched there anyway
        // (it requires one), so this can't misclassify a real movie/show
        // card regardless of which check runs first.
        CharSequence className = event.getClassName();
        if (className == null || !"android.view.View".contentEquals(className)) {
            return false;
        }
        List<CharSequence> text = event.getText();
        if (text != null && !text.isEmpty()) {
            return false;
        }
        return !contentDesc.contains(",");
    }

    private boolean isMovieOrShowCard(AccessibilityEvent event, String contentDesc) {
        Resources res = LauncherLocale.systemResources(this);
        for (String marker : res.getStringArray(R.array.title_markers)) {
            if (!marker.isEmpty() && contentDesc.contains(marker)) {
                return true;
            }
        }
        // "requires {Provider} subscription" - a variable provider name
        // between two fixed phrases, so this is two substrings that must
        // both appear rather than a plain marker. Replaces the old
        // Pattern.compile("requires .+ subscription") - equivalent for
        // every real content-desc seen so far, and marginally more
        // permissive (it also matches "requires subscription" with no
        // provider, and tolerates a line break in the middle), which is
        // the right direction for a positive detector. See
        // res/values/arrays.xml for why it is not a regex resource.
        String subscriptionPrefix = res.getString(R.string.subscription_marker_prefix);
        String subscriptionSuffix = res.getString(R.string.subscription_marker_suffix);
        if (!subscriptionPrefix.isEmpty()
                && contentDesc.contains(subscriptionPrefix)
                && (subscriptionSuffix.isEmpty() || contentDesc.contains(subscriptionSuffix))) {
            return true;
        }

        // Other formats with no price/rating marker:
        //  - Big "Google TV" posters for Movies/Shows: "{Title}, {synopsis}".
        //  - Free platforms, e.g. RTVE Play: "{Title}, RTVE Play".
        // Both are "{Title}, {rest}" on a real android.view.View card with
        // no text of its own. Banners/ads (e.g. "Netflix, Watch now") are
        // android.view.ViewGroup and DO have text ("WATCH NOW") - that's how
        // they're told apart without needing a list of known platforms.
        CharSequence className = event.getClassName();
        if (className == null || !"android.view.View".contentEquals(className)) {
            return false;
        }
        List<CharSequence> text = event.getText();
        if (text != null && !text.isEmpty()) {
            return false;
        }
        int commaIndex = contentDesc.indexOf(',');
        if (commaIndex <= 0) {
            return false;
        }
        return !contentDesc.substring(commaIndex + 1).trim().isEmpty();
    }

    private String extractTitle(String contentDesc) {
        // Once isMovieOrShowCard has confirmed this is a real card, the
        // title is always the text before the first comma - confirmed
        // against real content-desc strings, including ones with one or
        // more optional segments (provider name, subscription note) between
        // the title and the terminal price/rating marker, e.g.
        // "REACHER, requires Prime Video subscription, fresh rating: 95%..."
        // -> "REACHER". Earlier logic tried to split at the marker position
        // instead, which over-captured everything up to the marker
        // (including those intermediate segments) whenever more than one
        // existed. Trade-off: a title that itself contains a literal comma
        // would get truncated here - not seen in testing so far, and this
        // fixes clearly more real cases than it risks breaking.
        int commaIndex = contentDesc.indexOf(',');
        return (commaIndex >= 0 ? contentDesc.substring(0, commaIndex) : contentDesc).trim();
    }

    private void handleMovieClick(String title) {
        // Shared resolve -> confirm-overlay -> launch pipeline, also
        // used by the Fire TV watcher - see TitleHandler.
        titleHandler.handle(title);
    }

    /**
     * YouTube-redirect toggle for a YouTube video recommendation card - see
     * R.array.youtube_markers and PlayerLauncher.openYouTubeRedirect(). Independent
     * of handleMovieClick(): this fires (or doesn't) based only on
     * Preferences.isSmartTubeEnabled(). No MetadataResolver call - it is
     * just a YouTube search, nothing to resolve.
     *
     * Targets SmartTube or TizenTube Cobalt (io.gh.reisxd.tizentube.cobalt -
     * a real, separate Android TV port of the well-known "TizenTube" ad-block
     * mod - see PlayerLauncher's TIZENTUBE_COBALT_* constants for the full
     * evidence), per the user's
     * explicit Settings choice (Preferences.getYouTubeRedirectTarget(), a
     * dropdown - see PlayerLauncher.openYouTubeRedirect()), not
     * auto-detected install state. The preference name/method
     * (isSmartTubeEnabled) predates TizenTube Cobalt support and was kept
     * as-is rather than renamed - see Preferences.isSmartTubeEnabled()'s
     * javadoc - but the Settings strings shown to the user
     * (settings_smarttube_redirect / settings_smarttube_status_enabled) now
     * describe both targets, not just SmartTube, so the toggle's own
     * description doesn't lie about what it does.
     *
     * Unlike handleMovieClick(), this never shows the "Watch now"/confirm
     * overlay - launches straight to the target app's search, per explicit
     * user request (2026-08-25). A YouTube redirect is always just a search,
     * never a direct "open this exact thing" the way a resolved movie/show
     * match can be (see PlayerLauncher.openYouTubeRedirect()'s javadoc -
     * there's no video-id deep link attempted for either target), so it
     * doesn't carry the same "an accidental click silently opens the wrong
     * title" risk the confirm step exists to guard against for
     * handleMovieClick() - worst case here is an unwanted search opening,
     * not an unwanted title playing.
     *
     * isYouTubeCard() only produces candidates: an Apps-tab app suggestion
     * has the exact same payload shape as a YouTube video card. The launch
     * waits for YouTubeClickGate to confirm the click opened the official
     * YouTube app (launchYouTubeRedirect() is its callback).
     */
    private void handleYouTubeClick(String title) {
        if (!Preferences.isSmartTubeEnabled(this)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (title.equals(lastHandledTitle) && (now - lastHandledAtMillis) < DEBOUNCE_MS) {
            return;
        }
        lastHandledTitle = title;
        lastHandledAtMillis = now;

        youTubeClickGate.onCandidateClick(title);
    }

    private void launchYouTubeRedirect(String title) {
        PlayerLauncher.openYouTubeRedirect(this, title);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
        if (overlay != null) {
            overlay.abandon();
        }
        if (titleHandler != null) {
            titleHandler.shutdown();
        }
        if (youTubeClickGate != null) {
            youTubeClickGate.shutdown();
        }
        if (ocrCaptureManager != null) {
            ocrCaptureManager.shutdown();
        }
    }
}
