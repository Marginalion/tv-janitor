package io.github.marginalion.tvjanitor;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Readable from the sofa: focused = yellow fill with dark text and a dark tick, slightly bigger. */
final class Style {
    static final int BG = Color.parseColor("#101714"), ACCENT = Color.parseColor("#F2C14E"),
            TEXT = Color.parseColor("#F1F5F2"), DIM = Color.parseColor("#A9BAB1"), DARK = Color.parseColor("#101714"),
            WARN = Color.parseColor("#FF9E80");

    static void focus(TextView v, int accent, int text, int dim) {
        GradientDrawable on = new GradientDrawable(); on.setColor(accent); on.setCornerRadius(16f); on.setStroke(5, Color.WHITE);
        GradientDrawable off = new GradientDrawable(); off.setColor(Color.parseColor("#22302A")); off.setCornerRadius(16f); off.setStroke(2, Color.parseColor("#3A4A42"));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{android.R.attr.state_pressed}, on);
        bg.addState(new int[]{}, off);
        v.setBackground(bg);
        // Text and tick follow focus through state lists, so the colour is right even before any focus event.
        ColorStateList colors = new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {-android.R.attr.state_enabled}, {}},
                new int[]{DARK, DARK, dim, text});
        v.setTextColor(colors);
        if (v instanceof CompoundButton) ((CompoundButton) v).setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{DARK, accent}));
        v.setOnFocusChangeListener((view, has) ->
                view.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.03f : 1f).setDuration(120).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(6, 8, 6, 8);
        v.setLayoutParams(lp);
    }

    /** Two-line label: a big title and a smaller, quieter line under it. */
    static CharSequence twoLines(String title, String sub) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title);
        if (sub != null && !sub.isEmpty()) {
            int start = b.length() + 1;
            b.append('\n').append(sub);
            b.setSpan(new android.text.style.RelativeSizeSpan(0.78f), start, b.length(), 0);
        }
        return b;
    }
}
