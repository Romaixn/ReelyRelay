package com.reely.relay;

import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;

/**
 * Base for every screen in the app. Each one is a leanback GuidedStep -
 * guidance on the left, actions on the right; this swaps in the app's own
 * guidance panel (res/layout/guidance.xml) and gives screens a way to
 * change that panel's text after it has been created, which leanback itself
 * only allows once, up front, and only as a plain String.
 */
abstract class RelayStepFragment extends GuidedStepSupportFragment {

    @Override
    public GuidanceStylist onCreateGuidanceStylist() {
        return new RelayGuidanceStylist();
    }

    /** The app's icon as a tile - the guidance image of the screens that speak for the app itself. */
    protected Drawable brandTile() {
        return ContextCompat.getDrawable(requireContext(), R.drawable.ic_brand_tile);
    }

    /**
     * Shows whether ReelyRelay is working in the small label above the
     * title, colored to match - for the home screen, whose job is to
     * report that. No-op before the view exists.
     */
    protected void showStatusLabel() {
        TextView view = getGuidanceStylist().getBreadcrumbView();
        if (view == null) {
            return;
        }
        boolean working = SetupState.isWorking(requireContext());
        view.setTextColor(RelayStyle.color(requireContext(), working ? R.color.positive : R.color.warning));
        setGuidanceText(view, getString(working ? R.string.home_status_ready : R.string.home_status_incomplete));
    }

    /** Replaces the guidance title. No-op before the view exists. */
    protected void setGuidanceTitle(CharSequence title) {
        setGuidanceText(getGuidanceStylist().getTitleView(), title);
    }

    /**
     * Replaces the guidance description - also how a screen shows styled
     * (Spanned) text there. No-op before the view exists.
     */
    protected void setGuidanceDescription(CharSequence description) {
        setGuidanceText(getGuidanceStylist().getDescriptionView(), description);
    }

    private static void setGuidanceText(TextView view, CharSequence text) {
        if (view == null) {
            return;
        }
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Uses res/layout/guidance.xml, and keeps its text inside the panel whatever the language. */
    private static final class RelayGuidanceStylist extends GuidanceStylist {

        /** The description never shrinks below this - small, but still readable from the couch. */
        private static final float MIN_DESCRIPTION_SP = 12f;

        @Override
        public int onProvideLayoutId() {
            return R.layout.guidance;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Guidance guidance) {
            View view = super.onCreateView(inflater, container, guidance);
            // An empty line still takes its height - drop the ones a screen
            // didn't fill in, so the rest stays centered.
            collapseIfEmpty(getBreadcrumbView());
            collapseIfEmpty(getDescriptionView());
            TextView description = getDescriptionView();
            if (description != null) {
                description.addOnLayoutChangeListener(
                        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> shrinkToFit());
            }
            return view;
        }

        private static void collapseIfEmpty(TextView view) {
            if (view != null && view.length() == 0) {
                view.setVisibility(View.GONE);
            }
        }

        /**
         * The panel has a fixed height and nothing in it scrolls, so a long
         * explanation - a wordier translation, a QR code above it - gets
         * less room than its text needs and would simply lose its last
         * lines. When that happens, step the text size down until every
         * line is back. Each step triggers another layout pass, which lands
         * back here.
         */
        private void shrinkToFit() {
            TextView description = getDescriptionView();
            Layout text = description != null ? description.getLayout() : null;
            if (text == null || description.getVisibility() != View.VISIBLE) {
                return;
            }
            int room = description.getHeight()
                    - description.getCompoundPaddingTop() - description.getCompoundPaddingBottom();
            float scaledDensity = description.getResources().getDisplayMetrics().scaledDensity;
            float currentSp = description.getTextSize() / scaledDensity;
            if (text.getHeight() > room && currentSp > MIN_DESCRIPTION_SP) {
                float nextSp = Math.max(MIN_DESCRIPTION_SP, currentSp - 1f);
                // Posted: changing text size requests a layout, which must
                // not happen from inside the layout pass reporting this one.
                description.post(() -> description.setTextSize(TypedValue.COMPLEX_UNIT_SP, nextSp));
            }
        }
    }
}
