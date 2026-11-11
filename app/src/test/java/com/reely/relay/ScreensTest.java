package com.reely.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.provider.Settings;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Opens every screen the way a TV would (960x540dp, landscape, driven by a
 * remote) and checks that it comes up and that its text fits - a wrong
 * theme attribute, a missing string, a fragment reading state too early, or
 * a translation too long for its panel all fail here instead of on a device.
 *
 * Each screen is also drawn to app/build/screenshots/, which is the quickest
 * way to see what a change did without a TV at hand.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = ScreensTest.TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {

    static final String TV = "w960dp-h540dp-land-xhdpi";

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        // A TV is driven by a remote: rows take focus, and show it.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        // Keep the home screen from calling GitHub during a test run.
        Preferences.setUpdateCheckedAt(app, System.currentTimeMillis());
    }

    // --- first run -------------------------------------------------------

    @Test
    public void firstRunStartsOnWelcome() {
        assertTrue(current(launch()) instanceof WelcomeStepFragment);
    }

    @Test
    public void setupResumesWhereItStopped() {
        // Consent given, then the app was left: no second consent screen.
        Preferences.setDisclosureAccepted(app, true);
        assertTrue(current(launch()) instanceof SetupDetectionStepFragment);
    }

    @Test
    public void setupSkipsStepsThatAreAlreadyGranted() {
        assertTrue(SetupFlow.afterWelcome(app) instanceof SetupDetectionStepFragment);
        enableAccessibility();
        assertTrue(SetupFlow.afterWelcome(app) instanceof SetupOverlayStepFragment);
        ShadowSettings.setCanDrawOverlays(true);
        assertTrue(SetupFlow.afterWelcome(app) instanceof SetupDoneStepFragment);
    }

    @Test
    public void reachingTheLastStepCompletesSetup() {
        MainActivity activity = launch();
        assertFalse(Preferences.isSetupCompleted(app));
        show(activity, new SetupDoneStepFragment());
        assertTrue(Preferences.isSetupCompleted(app));
        assertTrue(current(launch()) instanceof HomeStepFragment);
    }

    @Test
    public void grantingAccessibilityMovesSetupOnByItself() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        settle();
        MainActivity activity = controller.get();
        SetupDetectionStepFragment step = new SetupDetectionStepFragment();
        show(activity, step);

        step.onGuidedActionClicked(step.findActionById(SetupStepFragment.ACTION_OPEN));
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                shadowOf(activity).getNextStartedActivity().getAction());

        // Back from Android's settings without switching it on: stay put.
        controller.pause().resume();
        settle();
        assertEquals(step, current(activity));

        // Back with it on: no "now press Continue" in between.
        step.onGuidedActionClicked(step.findActionById(SetupStepFragment.ACTION_OPEN));
        enableAccessibility();
        controller.pause().resume();
        settle();
        assertTrue(current(activity) instanceof SetupOverlayStepFragment);

        // Pressing Back on the next step must not bounce straight forward again.
        activity.getSupportFragmentManager().popBackStackImmediate();
        settle();
        assertEquals(step, current(activity));
    }

    // --- what the home screen reports -------------------------------------

    @Test
    public void homeReportsTheFirstThingMissing() {
        assertFalse(SetupState.isWorking(app));
        assertEquals(app.getString(R.string.home_needs_reely, Links.REELY_PLAYER_SITE),
                SetupState.summary(app));

        installReelyPlayer();
        assertEquals(app.getString(R.string.home_needs_accessibility), SetupState.summary(app));

        enableAccessibility();
        // Test builds are made without a bundled TMDB key.
        assertEquals(app.getString(R.string.home_needs_tmdb_key), SetupState.summary(app));

        Preferences.setTmdbApiKey(app, "0123456789abcdef0123456789abcdef");
        assertTrue(SetupState.isWorking(app));
        assertEquals(app.getString(R.string.home_ready_no_button), SetupState.summary(app));

        ShadowSettings.setCanDrawOverlays(true);
        assertEquals(app.getString(R.string.home_ready), SetupState.summary(app));
    }

    @Test
    public void aDevBuildOfReelyPlayerCounts() {
        PackageInfo dev = new PackageInfo();
        dev.packageName = "com.reely.reely.debug";
        shadowOf(app.getPackageManager()).installPackage(dev);
        assertTrue(ReelyPlayer.isInstalled(app));
    }

    // --- opening a title ---------------------------------------------------

    @Test
    public void deepLinksAreTheOnesReelyPlayerAccepts() {
        // Reely only lets /detail/(movie|series)/<digits> through.
        assertEquals("reely://detail/movie/1726",
                ReelyPlayer.detailUri(MediaType.MOVIE, 1726).toString());
        assertEquals("reely://detail/series/1396",
                ReelyPlayer.detailUri(MediaType.SERIES, 1396).toString());
    }

    @Test
    public void openingATitleTargetsReelyPlayer() {
        installReelyPlayer();
        PlayerLauncher.openInReely(app,
                new TitleCandidate("Iron Man", null, 2008, MediaType.MOVIE, true, 1726));

        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull("nothing was launched", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("reely://detail/movie/1726", started.getDataString());
        assertEquals("com.reely.reely", started.getPackage());
    }

    @Test
    public void watchNowButtonGoesFromLoadingToConfirm() throws IOException {
        ShadowSettings.setCanDrawOverlays(true);
        WatchNowOverlay overlay = new WatchNowOverlay(app);
        boolean[] confirmed = {false};

        overlay.showLoading();
        settle();
        Button button = overlayButton();
        assertEquals(app.getString(R.string.watch_now_loading), button.getText().toString());
        capture(button, "20-watch-now-loading");

        overlay.showConfirm(() -> confirmed[0] = true);
        settle();
        assertEquals("Watch now in Reely Player", button.getText().toString());
        capture(button, "21-watch-now-confirm");

        button.performClick();
        assertTrue(confirmed[0]);
        assertFalse("confirming settles the match", overlay.hasPendingMatch());
    }

    @Test
    public void matchChooserTray() throws IOException {
        List<TitleCandidate> candidates = Arrays.asList(
                new TitleCandidate("Obsession", null, 2026, MediaType.MOVIE, true, 1),
                new TitleCandidate("Obsession", null, 2023, MediaType.SERIES, true, 2),
                new TitleCandidate("Obsession", "Rendez-vous", 2015, MediaType.MOVIE, true, 3));
        View tray = MatchTrayView.build(app, "Obsession", candidates, picked -> { }, () -> { });
        tray.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST));
        tray.layout(0, 0, tray.getMeasuredWidth(), tray.getMeasuredHeight());
        capture(tray, "22-match-chooser");
    }

    // --- every screen, in every language the app ships ---------------------

    @Test
    public void everyScreenOpensAndFits() throws IOException {
        walkThroughEveryScreen("");
    }

    @Test
    @Config(qualifiers = "fr-" + TV)
    public void everyScreenOpensAndFitsInFrench() throws IOException {
        walkThroughEveryScreen("fr-");
    }

    @Test
    @Config(qualifiers = "es-" + TV)
    public void everyScreenOpensAndFitsInSpanish() throws IOException {
        walkThroughEveryScreen("es-");
    }

    private void walkThroughEveryScreen(String prefix) throws IOException {
        MainActivity activity = launch();
        capture(activity, prefix + "01-welcome");
        show(activity, new SetupDetectionStepFragment());
        capture(activity, prefix + "02-setup-accessibility");
        show(activity, new SetupOverlayStepFragment());
        capture(activity, prefix + "03-setup-overlay");

        show(activity, new HomeStepFragment());
        capture(activity, prefix + "04-home-not-ready");

        installReelyPlayer();
        enableAccessibility();
        ShadowSettings.setCanDrawOverlays(true);
        Preferences.setTmdbApiKey(app, "0123456789abcdef0123456789abcdef");

        show(activity, new SetupDoneStepFragment());
        capture(activity, prefix + "05-setup-done");
        show(activity, new HomeStepFragment());
        capture(activity, prefix + "06-home-ready");

        show(activity, new AccessibilitySettingsStepFragment());
        capture(activity, prefix + "07-accessibility");
        show(activity, new RestrictedSettingsStepFragment());
        capture(activity, prefix + "08-accessibility-help");
        show(activity, new OverlaySettingsStepFragment());
        capture(activity, prefix + "09-watch-now-button");
        show(activity, new DetectionSettingsStepFragment());
        capture(activity, prefix + "10-title-matching");
        show(activity, new OcrDisclosureStepFragment());
        capture(activity, prefix + "11-read-from-screen");
        show(activity, new YouTubeSettingsStepFragment());
        capture(activity, prefix + "12-youtube");
        show(activity, new MoreSettingsStepFragment());
        capture(activity, prefix + "13-more");
        show(activity, new TmdbKeyStepFragment());
        capture(activity, prefix + "14-tmdb-key");
        show(activity, new AboutStepFragment());
        capture(activity, prefix + "15-about");
        show(activity, new SearchStepFragment());
        capture(activity, prefix + "16-search");
        show(activity, MatchChooserStepFragment.create("Obsession", Arrays.asList(
                new TitleCandidate("Obsession", null, 2026, MediaType.MOVIE, true, 1),
                new TitleCandidate("Obsession", "Rendez-vous", 2015, MediaType.MOVIE, true, 3))));
        capture(activity, prefix + "17-search-chooser");
        show(activity, new FireTvModeStepFragment());
        capture(activity, prefix + "18-fire-tv-mode");
        show(activity, new UpdateWarningStepFragment());
        capture(activity, prefix + "19-update-warning");
    }

    // --- helpers ---------------------------------------------------------

    private MainActivity launch() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        return activity;
    }

    private void show(MainActivity activity, GuidedStepSupportFragment fragment) {
        GuidedStepSupportFragment.add(activity.getSupportFragmentManager(), fragment);
        settle();
        assertEquals(fragment, current(activity));
    }

    private static Fragment current(MainActivity activity) {
        Fragment fragment = GuidedStepSupportFragment.getCurrentGuidedStepSupportFragment(
                activity.getSupportFragmentManager());
        assertNotNull(fragment);
        return fragment;
    }

    /** Lets fragment transactions, layout passes and leanback's entrance animations run to the end. */
    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
    }

    private void installReelyPlayer() {
        PackageInfo reely = new PackageInfo();
        reely.packageName = "com.reely.reely";
        shadowOf(app.getPackageManager()).installPackage(reely);
    }

    private void enableAccessibility() {
        Settings.Secure.putString(app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(app, RelayAccessibilityService.class).flattenToString());
    }

    /** The Watch now button is its own window, outside any activity. */
    private static Button overlayButton() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (root instanceof Button) {
                return (Button) root;
            }
        }
        throw new AssertionError("no Watch now button on screen");
    }

    private static void capture(MainActivity activity, String name) throws IOException {
        assertTextFits(activity, name);
        capture(activity.getWindow().getDecorView(), name);
    }

    /**
     * Nothing on a screen scrolls sideways or within itself, so text that
     * doesn't fit is text the user never sees: the guidance must show every
     * line (see RelayStepFragment's shrink-to-fit), and no row on screen may
     * be cut short.
     */
    private static void assertTextFits(MainActivity activity, String name) {
        TextView description = activity.findViewById(androidx.leanback.R.id.guidance_description);
        assertNotNull(name + ": no guidance", description);
        if (description.getVisibility() == View.VISIBLE) {
            int room = description.getHeight()
                    - description.getCompoundPaddingTop() - description.getCompoundPaddingBottom();
            assertTrue(name + ": guidance text is cut off", description.getLayout().getHeight() <= room);
        }

        ViewGroup rows = activity.findViewById(androidx.leanback.R.id.guidedactions_list);
        assertNotNull(name + ": no action list", rows);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            assertWhole(row.findViewById(androidx.leanback.R.id.guidedactions_item_title), name);
            assertWhole(row.findViewById(androidx.leanback.R.id.guidedactions_item_description), name);
        }
    }

    private static void assertWhole(TextView text, String name) {
        if (text == null || text.getVisibility() != View.VISIBLE || text.getLayout() == null) {
            return;
        }
        Layout layout = text.getLayout();
        int lastLine = layout.getLineCount() - 1;
        assertEquals(name + ": \"" + text.getText() + "\" is cut off",
                0, lastLine < 0 ? 0 : layout.getEllipsisCount(lastLine));
    }

    private static void capture(View root, String name) throws IOException {
        assertTrue(name + " was never laid out", root.getWidth() > 0 && root.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File("build/screenshots", name + ".png");
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
