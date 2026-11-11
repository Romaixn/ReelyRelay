package com.reely.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * Reading a title and year off the Google TV launcher's detail page, and
 * using that year to pick between same-named titles. The node layout and
 * strings are the ones captured from a real Google TV Streamer in French
 * (2026-11-11) - see LauncherDetailPage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DetailPageTest {

    private static final String CAPTURED_METADATA = "Film considéré comme \"pourri\" : 52 % sur Rotten "
            + "Tomatoes, Classification du contenu : T, SF, 2026, 1 h 48 min, Audiodescription "
            + "disponible, Sous-titres disponibles dans certaines langues";

    @Test
    public void readsTheTitleAndTheYearFromTheLineBelow() {
        AccessibilityNodeInfo title = detailPage("Supergirl", CAPTURED_METADATA);

        LauncherDetailPage page = LauncherDetailPage.fromTitleNode(title);

        assertEquals("Supergirl", page.title);
        assertEquals(2026, page.year);
    }

    @Test
    public void aPageWithoutAYearStillGivesItsTitle() {
        LauncherDetailPage page = LauncherDetailPage.fromTitleNode(detailPage("Supergirl", "SF, 1 h 48 min"));

        assertEquals("Supergirl", page.title);
        assertEquals(LauncherDetailPage.UNKNOWN_YEAR, page.year);
    }

    @Test
    public void aTitleNotYetFilledInIsNoPage() {
        assertNull(LauncherDetailPage.fromTitleNode(detailPage("  ", CAPTURED_METADATA)));
    }

    @Test
    public void yearsAreOnlyStandaloneFourDigitNumbers() {
        assertEquals(2026, LauncherDetailPage.parseYear(CAPTURED_METADATA));
        assertEquals(2015, LauncherDetailPage.parseYear("Série, 2015 – 2021, 6 saisons"));
        assertEquals(LauncherDetailPage.UNKNOWN_YEAR, LauncherDetailPage.parseYear("Acheter : 11,99 €"));
        assertEquals(LauncherDetailPage.UNKNOWN_YEAR, LauncherDetailPage.parseYear("12026 vues, 3000 m"));
        assertEquals(LauncherDetailPage.UNKNOWN_YEAR, LauncherDetailPage.parseYear(null));
    }

    @Test
    public void theLaunchersYearPicksBetweenSameNamedTitles() {
        TitleCandidate film1984 = new TitleCandidate("Supergirl", null, 1984, MediaType.MOVIE, true, 9651);
        TitleCandidate series = new TitleCandidate("Supergirl", null, 2015, MediaType.SERIES, true, 62688);
        TitleCandidate film2026 = new TitleCandidate("Supergirl", null, 2026, MediaType.MOVIE, true, 1081003);
        List<TitleCandidate> candidates = Arrays.asList(film2026, series, film1984);

        assertSame(series, MetadataResolver.exactMatchFromYear(candidates, 2015));
        assertSame(film1984, MetadataResolver.exactMatchFromYear(candidates, 1984));
        // No exact match that year (a regional release date): leave it to the chooser.
        assertNull(MetadataResolver.exactMatchFromYear(candidates, 2025));
        assertNull(MetadataResolver.exactMatchFromYear(candidates, LauncherDetailPage.UNKNOWN_YEAR));
    }

    @Test
    public void onlyExactTitleMatchesArePickedByYear() {
        TitleCandidate other = new TitleCandidate("Supergirl: Woman of Tomorrow", null, 2026,
                MediaType.MOVIE, false, 1);

        assertNull(MetadataResolver.exactMatchFromYear(Arrays.asList(other), 2026));
    }

    /** The captured shape: a container holding the title, then the metadata line, then more. */
    private static AccessibilityNodeInfo detailPage(String title, String metadata) {
        AccessibilityNodeInfo container = AccessibilityNodeInfo.obtain();
        container.setClassName("android.widget.GridView");

        AccessibilityNodeInfo titleNode = AccessibilityNodeInfo.obtain();
        titleNode.setClassName("android.widget.TextView");
        titleNode.setViewIdResourceName(LauncherDetailPage.TITLE_VIEW_ID);
        titleNode.setText(title);

        AccessibilityNodeInfo metadataNode = AccessibilityNodeInfo.obtain();
        metadataNode.setClassName("android.widget.LinearLayout");
        metadataNode.setContentDescription(metadata);

        AccessibilityNodeInfo synopsis = AccessibilityNodeInfo.obtain();
        synopsis.setClassName("android.widget.TextView");
        synopsis.setText("Released in 1999, remastered in 2004.");

        shadow(container).addChild(titleNode);
        shadow(container).addChild(metadataNode);
        shadow(container).addChild(synopsis);
        return titleNode;
    }

    private static org.robolectric.shadows.ShadowAccessibilityNodeInfo shadow(AccessibilityNodeInfo node) {
        return org.robolectric.Shadows.shadowOf(node);
    }
}
