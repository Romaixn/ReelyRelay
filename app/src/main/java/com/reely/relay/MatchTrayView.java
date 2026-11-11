package com.reely.relay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/**
 * The "which one did you mean?" tray shown when a title search returns
 * several exact matches (see MetadataResolver.isAmbiguous). Built
 * programmatically, like every other screen in this app - there is no
 * layout XML anywhere in the project, see MainActivity for why.
 *
 * Host-agnostic on purpose - MatchTrayOverlay is the only host now (see
 * that class for why a translucent-Activity host was tried and dropped),
 * but nothing here assumes that.
 *
 * Sized off screen resolution, not dp/density - see RelayStyle.uiScale().
 * Colors and typeface come from RelayStyle too, so the tray matches the
 * "Watch now" button it follows and the settings screens.
 *
 * D-pad LEFT/RIGHT between cards, and UP from a card to Cancel, is
 * Android's own default focus search - nothing here handles those keys
 * specially. That's different from WatchNowOverlay's blanket
 * dismiss-on-any-arrow-key, which exists there only because its Button is
 * the single focusable view in its window; this tray always has more than
 * one focusable view (several cards plus Cancel), so that workaround
 * doesn't apply and would only get in the way of navigating the tray.
 *
 * BACK, however, IS handled here, per focusable view rather than once on
 * the root: a confirmed on-device finding is that a key listener set only
 * on the tray's root container did not reliably see BACK once a deeper
 * child (a card) held focus - unlike WatchNowOverlay, whose single Button
 * is simultaneously the root view AND the focused view, so the same
 * approach worked fine there. Attaching the same listener to every
 * focusable leaf (each card, Cancel) sidesteps the question of exactly how
 * key events bubble back up through a ViewGroup chain, since the listener
 * is then always on whichever view actually holds focus.
 */
final class MatchTrayView {

    private MatchTrayView() {
    }

    static View build(Context context, String queryTitle, List<TitleCandidate> candidates,
                       Consumer<TitleCandidate> onPick, Runnable onCancel) {
        float scale = RelayStyle.uiScale(context);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(scale, 36), px(scale, 28), px(scale, 36), px(scale, 32));
        content.setBackground(panelBackground(context, scale));

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.chooser_heading, queryTitle));
        title.setTextColor(RelayStyle.color(context, R.color.text_strong));
        title.setTypeface(RelayStyle.semiBold(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 34));
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxLines(1);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = new TextView(context);
        cancel.setText(context.getString(R.string.chooser_cancel));
        cancel.setTextColor(focusAwareText(context, R.color.on_accent, R.color.text_body));
        cancel.setTypeface(RelayStyle.medium(context));
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 24));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(px(scale, 24), px(scale, 12), px(scale, 24), px(scale, 12));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setBackground(cardBackground(context, scale));
        cancel.setOnClickListener(v -> onCancel.run());
        cancel.setOnKeyListener(backDismiss(onCancel));
        heading.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // With this off, cards asking for MATCH_PARENT height all stretch to
        // the tallest one (a card with an "aka" line is a line taller).
        row.setBaselineAligned(false);

        View firstCard = null;
        for (TitleCandidate candidate : candidates) {
            View card = buildCard(context, scale, candidate, onPick, onCancel);
            if (firstCard == null) {
                firstCard = card;
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            cardParams.rightMargin = px(scale, 16);
            row.addView(card, cardParams);
        }

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        // Scrollbar shown, not hidden - at up to MAX_RANKED (see
        // ExactMatchPicker) candidates, scrolling well past screen width is
        // now the normal case for an ambiguous title, not a rare edge case,
        // so a visible affordance that there's more to the right matters.
        scroller.setHorizontalScrollBarEnabled(true);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollerParams.topMargin = px(scale, 24);
        scroller.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroller, scrollerParams);

        // requestFocus() here would very likely be a no-op or, worse, get
        // silently overridden: the view isn't attached to a window yet
        // (the host adds it to WindowManager right after this method
        // returns), and once attached, a focus search from the still-
        // unfocused root would land on Cancel - it comes before the row of
        // cards in child order and is the first focusable view found. A
        // short delay, targeting the card directly, avoids both problems.
        if (firstCard != null) {
            View target = firstCard;
            new Handler(Looper.getMainLooper()).postDelayed(target::requestFocus, 150L);
        }
        return content;
    }

    private static View buildCard(Context context, float scale, TitleCandidate candidate,
                                   Consumer<TitleCandidate> onPick, Runnable onCancel) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(px(scale, 34), px(scale, 22), px(scale, 34), px(scale, 22));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(cardBackground(context, scale));
        card.setOnClickListener(v -> onPick.accept(candidate));
        card.setOnKeyListener(backDismiss(onCancel));

        TextView year = new TextView(context);
        year.setText(candidate.year != Integer.MIN_VALUE
                ? String.valueOf(candidate.year)
                : context.getString(R.string.chooser_unknown_year));
        year.setTextColor(focusAwareText(context, R.color.on_accent, R.color.text_strong));
        year.setTypeface(RelayStyle.semiBold(context));
        year.setDuplicateParentStateEnabled(true);
        year.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 38));
        year.setGravity(Gravity.CENTER);
        year.setMaxLines(1);
        card.addView(year);

        TextView type = new TextView(context);
        type.setText(context.getString(candidate.type == MediaType.MOVIE
                ? R.string.chooser_type_movie
                : R.string.chooser_type_series));
        type.setTextColor(focusAwareText(context, R.color.on_accent_muted, R.color.text_muted));
        type.setTypeface(RelayStyle.medium(context));
        type.setDuplicateParentStateEnabled(true);
        type.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 22));
        type.setGravity(Gravity.CENTER);
        type.setMaxLines(1);
        card.addView(type);

        // Surfaces a real confirmed case: TMDB's own localized title for an
        // obscure, unrelated film was itself literally the searched title,
        // so it's a legitimate exact match by the data - but showing its
        // real (original-language) title here at least lets the user spot
        // it isn't what they expected instead of it looking identical to a
        // genuine match. See TmdbClient.
        if (candidate.akaTitle != null) {
            TextView aka = new TextView(context);
            aka.setText(context.getString(R.string.chooser_aka, candidate.akaTitle));
            aka.setTextColor(focusAwareText(context, R.color.on_accent_muted, R.color.text_dim));
            aka.setTypeface(RelayStyle.medium(context));
            aka.setDuplicateParentStateEnabled(true);
            aka.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 18));
            aka.setGravity(Gravity.CENTER);
            aka.setMaxLines(1);
            aka.setEllipsize(android.text.TextUtils.TruncateAt.END);
            aka.setMaxWidth(px(scale, 300));
            card.addView(aka);
        }

        return card;
    }

    private static View.OnKeyListener backDismiss(Runnable onCancel) {
        return (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                onCancel.run();
                return true;
            }
            return false;
        };
    }

    /**
     * A card's year/type TextViews aren't themselves focusable (only the
     * card is), so they rely on setDuplicateParentStateEnabled(true) to
     * pick up state_focused from their parent - Cancel IS itself the
     * focusable view, so it needs no such flag. Without this, focused-card
     * text stayed the same light color against cardBackground()'s
     * near-white focused fill and became unreadable - confirmed on-device.
     */
    private static ColorStateList focusAwareText(Context context, int focusedColorRes, int normalColorRes) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{RelayStyle.color(context, focusedColorRes), RelayStyle.color(context, normalColorRes)});
    }

    /** Focused: filled with the accent color (the text flips dark - see focusAwareText()). Otherwise a raised surface. */
    private static StateListDrawable cardBackground(Context context, float scale) {
        int accent = RelayStyle.color(context, R.color.accent);
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused}, shape(scale, accent, accent));
        drawable.addState(new int[]{}, shape(scale,
                RelayStyle.color(context, R.color.surface_2), RelayStyle.color(context, R.color.border_strong)));
        return drawable;
    }

    private static GradientDrawable shape(float scale, int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(px(scale, 14));
        shape.setStroke(Math.max(1, px(scale, 1)), stroke);
        return shape;
    }

    private static GradientDrawable panelBackground(Context context, float scale) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(RelayStyle.color(context, R.color.bg_base));
        shape.setCornerRadius(px(scale, 24));
        shape.setStroke(Math.max(1, px(scale, 2)), RelayStyle.color(context, R.color.border_strong));
        return shape;
    }

    private static int px(float scale, int referencePixels) {
        return RelayStyle.px(scale, referencePixels);
    }
}
