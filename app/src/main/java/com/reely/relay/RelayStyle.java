package com.reely.relay;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

/**
 * Shared look for the views this app builds in code - the floating "Watch
 * now" button (WatchNowOverlay) and the match chooser (MatchTrayView). The
 * settings screens get the same palette and typeface from the theme instead
 * (res/values/themes.xml, res/values/colors.xml).
 *
 * Overlay views are sized off screen resolution (uiScale below), not
 * dp/density: a first on-device pass sized everything in dp and came out
 * oversized, which is consistent with TV boxes not always reporting a
 * density that matches their actual pixel ratio the way phones do. Scaling
 * raw "reference pixels at a 1920x1080 screen" renders consistently across
 * TV resolutions regardless of what a given box's density happens to claim.
 */
final class RelayStyle {

    private RelayStyle() {
    }

    /** How many actual pixels a "reference pixel at 1920x1080" maps to on this screen. */
    static float uiScale(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float scale = Math.min(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f);
        return Math.max(scale, 0.65f);
    }

    static int px(float scale, int referencePixels) {
        return Math.round(referencePixels * scale);
    }

    static int color(Context context, int colorRes) {
        return ContextCompat.getColor(context, colorRes);
    }

    static Typeface medium(Context context) {
        return font(context, R.font.plus_jakarta_sans_medium, Typeface.DEFAULT);
    }

    static Typeface semiBold(Context context) {
        return font(context, R.font.plus_jakarta_sans_semibold, Typeface.DEFAULT_BOLD);
    }

    /**
     * Never throws and never returns null: a font that fails to load (a
     * stripped-down vendor build, an out-of-memory blip) must cost a
     * typeface, not an overlay the user is waiting on.
     */
    private static Typeface font(Context context, int fontRes, Typeface fallback) {
        try {
            Typeface typeface = ResourcesCompat.getFont(context, fontRes);
            return typeface != null ? typeface : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * The Reely mark, tinted and sized to sit next to text of the given
     * height. Returns null if the drawable can't be loaded.
     */
    static Drawable mark(Context context, int tint, int heightPx) {
        Drawable mark = ContextCompat.getDrawable(context, R.drawable.ic_reely_mark);
        if (mark == null) {
            return null;
        }
        mark = mark.mutate();
        mark.setTint(tint);
        int intrinsicHeight = Math.max(1, mark.getIntrinsicHeight());
        int widthPx = Math.round(heightPx * (mark.getIntrinsicWidth() / (float) intrinsicHeight));
        mark.setBounds(0, 0, widthPx, heightPx);
        return mark;
    }
}
