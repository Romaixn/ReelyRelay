package com.reely.relay;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title (and, when shown, the year) of the Google TV launcher's own
 * detail page for a movie or show - the screen it opens after a
 * recommendation is selected, from a click or from voice search.
 *
 * This is where the title reliably is. A click on a recommendation card
 * often carries none (confirmed 2026-11-11 on a Google TV Streamer in
 * French: every card click had an empty content-desc), but the detail page
 * always shows it, in a TextView with a stable, non-obfuscated id that
 * doesn't depend on the language. The line right under it is the
 * metadata summary ("..., SF, 2026, 1 h 48 min, ..."), which gives the
 * year - enough to pick between same-named titles without asking.
 *
 * Captured layout (launcherx 837595, Android 14):
 *
 *   GridView
 *     TextView    id=entity_details_title_row   text="Supergirl"
 *     LinearLayout                               desc="Film considéré comme ... SF, 2026, 1 h 48 min, ..."
 *     ...
 *
 * Needs the service to be able to read window content
 * (canRetrieveWindowContent) and report view ids (flagReportViewIds).
 */
final class LauncherDetailPage {

    static final String TITLE_VIEW_ID =
            "com.google.android.apps.tv.launcherx:id/entity_details_title_row";

    /** Same sentinel TitleCandidate.year uses for "no year". */
    static final int UNKNOWN_YEAR = Integer.MIN_VALUE;

    // A standalone 19xx/20xx: not part of a longer number like a price or a
    // runtime in minutes.
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");

    final String title;
    final int year;

    LauncherDetailPage(String title, int year) {
        this.title = title;
        this.year = year;
    }

    /**
     * Reads the page from the launcher window's root node, or returns null
     * if this isn't a detail page or its title hasn't been laid out yet
     * (the page fills in a moment after it opens - callers retry).
     */
    static LauncherDetailPage read(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(TITLE_VIEW_ID);
        if (found == null || found.isEmpty()) {
            return null;
        }
        return fromTitleNode(found.get(0));
    }

    /** The page as described by its title node and the metadata line that follows it. */
    static LauncherDetailPage fromTitleNode(AccessibilityNodeInfo titleNode) {
        String title = firstNonBlank(titleNode.getText(), titleNode.getContentDescription());
        if (title == null) {
            return null;
        }
        return new LauncherDetailPage(title, yearBelow(titleNode));
    }

    /** The year from the metadata line right after the title, or UNKNOWN_YEAR. */
    private static int yearBelow(AccessibilityNodeInfo titleNode) {
        AccessibilityNodeInfo parent = titleNode.getParent();
        if (parent == null) {
            return UNKNOWN_YEAR;
        }
        int count = parent.getChildCount();
        for (int i = 0; i < count - 1; i++) {
            if (titleNode.equals(parent.getChild(i))) {
                AccessibilityNodeInfo metadata = parent.getChild(i + 1);
                if (metadata == null) {
                    return UNKNOWN_YEAR;
                }
                int year = parseYear(metadata.getContentDescription());
                return year != UNKNOWN_YEAR ? year : parseYear(metadata.getText());
            }
        }
        return UNKNOWN_YEAR;
    }

    /** The first standalone four-digit year (1900-2099) in the text, or UNKNOWN_YEAR. */
    static int parseYear(CharSequence text) {
        if (text == null) {
            return UNKNOWN_YEAR;
        }
        Matcher matcher = YEAR.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : UNKNOWN_YEAR;
    }

    private static String firstNonBlank(CharSequence first, CharSequence second) {
        if (first != null && first.toString().trim().length() > 0) {
            return first.toString().trim();
        }
        if (second != null && second.toString().trim().length() > 0) {
            return second.toString().trim();
        }
        return null;
    }
}
