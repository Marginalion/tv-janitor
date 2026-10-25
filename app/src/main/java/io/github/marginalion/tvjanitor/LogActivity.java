package io.github.marginalion.tvjanitor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class LogActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#101714")));
        TextView t = new TextView(this);
        t.setText(Ops.readLog(this)); t.setTextSize(13); t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(Color.parseColor("#C9D6CF")); t.setPadding(48, 32, 48, 32); t.setFocusable(true);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Color.parseColor("#101714")); sv.addView(t);
        setContentView(sv);
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
