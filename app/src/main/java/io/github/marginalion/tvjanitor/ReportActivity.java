package io.github.marginalion.tvjanitor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Shows the whole report before anything is saved: nothing leaves the TV unseen. */
public class ReportActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#101714")));
        int accent = Color.parseColor("#F2C14E"), text = Color.parseColor("#E8EFEA"), dim = Color.parseColor("#93A69C");
        Store store = new Store(this);
        final String[] report = {Report.build(this, store.flag("shareOwn", false))};
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(64, 32, 64, 32);
        root.setBackgroundColor(Color.parseColor("#101714"));
        TextView t = new TextView(this); t.setText(T.t("Поделиться опытом")); t.setTextSize(28); t.setTextColor(accent); root.addView(t);
        TextView h = new TextView(this);
        h.setText(T.t("Ниже — весь текст отчёта, ничего больше в нём нет: модель, прошивка и системные пакеты. Ваши собственные приложения попадают, только если вы отметите это ниже. ")
                + T.t("Уборщик ничего не отправляет сам: «Сохранить» кладёт файл на телевизор, а вы решаете, отправить ли его в CleanYourTV, чтобы помочь владельцам таких же телевизоров."));
        android.widget.CheckBox own = new android.widget.CheckBox(this);
        own.setText(T.t("Включить в отчёт мои приложения\nустановленные вами кинотеатры и прочее, что вы отключили или «усыпили». Помогает другим, но это личное — решать вам"));
        own.setTextSize(16); own.setChecked(store.flag("shareOwn", false)); own.setPadding(12, 10, 12, 10);
        h.setTextSize(15); h.setTextColor(dim); h.setPadding(0, 6, 0, 12); root.addView(h);
        TextView status = new TextView(this); status.setTextSize(16); status.setTextColor(accent);
        Button save = new Button(this); save.setText(T.t("Сохранить файл")); save.setAllCaps(false); save.setTextSize(18);
        Style.focus(save, accent, text, dim);
        save.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        TextView body = new TextView(this);
        Style.focus(own, accent, text, dim);
        root.addView(own);
        android.widget.CheckBox consent = new android.widget.CheckBox(this);
        consent.setText(T.t("Делиться опытом автоматически\nотчёт обновляется сам после изменений; когда появится общий сервер — будет отправляться анонимно"));
        consent.setTextSize(16); consent.setPadding(12, 10, 12, 10); consent.setChecked(store.flag("shareConsent", false));
        Style.focus(consent, accent, text, dim);
        root.addView(consent);
        consent.setOnCheckedChangeListener((v, on) -> {
            store.setFlag("shareConsent", on);
            if (!on) { store.setFlag("shareOwn", false); new File(getExternalFilesDir(null), "report.txt").delete(); status.setText(T.t("Согласие отозвано, отчёт удалён с телевизора")); }
            else { store.setFlag("shareOwn", own.isChecked()); Report.saveIfConsented(this); status.setText(T.t("Спасибо! Отчёт будет обновляться сам")); }
        });
        root.addView(save); root.addView(status);
        body.setText(report[0]);
        own.setOnCheckedChangeListener((v, on) -> {
            report[0] = Report.build(this, on); body.setText(report[0]); status.setText("");
            if (store.flag("shareConsent", false)) { store.setFlag("shareOwn", on); Report.saveIfConsented(this); }
        }); body.setTextSize(13); body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(text); body.setFocusable(true);
        ScrollView sv = new ScrollView(this); sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        save.setOnClickListener(v -> {
            try {
                File f = new File(getExternalFilesDir(null), "report.txt");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(report[0].getBytes(StandardCharsets.UTF_8)); }
                // The hint about our own test script is for debug builds only, not for a release.
                boolean debug = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                status.setText(T.t("Сохранено: ") + f.getAbsolutePath() + (debug ? T.t(". С ноутбука его можно забрать скриптом get_report.bat.") : ""));
            } catch (Exception e) { status.setText(T.t("Не сохранилось: ") + e.getMessage()); }
        });
        setContentView(root);
        save.requestFocus();
    }
}
