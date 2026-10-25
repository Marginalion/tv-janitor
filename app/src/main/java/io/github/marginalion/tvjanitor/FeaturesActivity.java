package io.github.marginalion.tvjanitor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** First run in three short steps (welcome and risk, what you use, sharing). Later: only "what you use". */
public class FeaturesActivity extends Activity {
    private Store store;
    private boolean first;
    private final List<CheckBox> boxes = new ArrayList<>();
    private boolean shareOn, shareOwnOn;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#101714")));
        store = new Store(this);
        first = !store.has("features");
        shareOn = store.flag("shareConsent", false);
        shareOwnOn = store.flag("shareOwn", false);
        if (first) welcome(); else features();
    }

    private LinearLayout page(String step, String title, String lead) {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Style.BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(96, 56, 96, 56);
        sv.addView(root);
        if (step != null) root.addView(text(step, 18, Style.DIM));
        root.addView(text(title, 36, Style.ACCENT));
        if (lead != null) { TextView l = text(lead, 22, Style.TEXT); l.setPadding(0, 12, 0, 24); l.setLineSpacing(6, 1); root.addView(l); }
        setContentView(sv);
        return root;
    }

    private void welcome() {
        LinearLayout root = page(T.t("Шаг 1 из 3"), T.t("Добро пожаловать в Уборщика"),
                T.t("Он отключит на телевизоре лишнее, чтобы тот работал быстрее. Что оставить — решаете вы."));
        TextView risk = text(T.t("⚠  Уборщик меняет системные настройки. Он бережёт ядро Android и умеет всё вернуть, ")
                + T.t("но каждый телевизор устроен по-своему: вы пользуетесь им на свой риск."), 20, Style.WARN);
        risk.setLineSpacing(6, 1); risk.setPadding(0, 0, 0, 28);
        root.addView(risk);
        Button go = button(T.t("Принимаю, дальше"));
        root.addView(go);
        go.setOnClickListener(v -> features());
        go.requestFocus();
    }

    private void features() {
        LinearLayout root = page(first ? T.t("Шаг 2 из 3") : null, T.t("Чем вы пользуетесь?"),
                T.t("Отмеченное Уборщик не тронет. Неотмеченное «Рекомендуемый» режим предложит отключить."));
        Set<String> used = store.set("features");
        boxes.clear();
        for (Features.F f : Features.ALL) {
            CheckBox c = new CheckBox(this);
            c.setText(Style.twoLines(f.title, f.sub)); c.setTextSize(24); c.setPadding(24, 18, 24, 18);
            c.setChecked(first || used.contains(f.id));
            c.setTag(f.id);
            Style.focus(c, Style.ACCENT, Style.TEXT, Style.DIM);
            root.addView(c); boxes.add(c);
        }
        Button next = button(first ? T.t("Дальше") : T.t("Готово"));
        root.addView(next);
        next.setOnClickListener(v -> {
            Set<String> s = new HashSet<>();
            for (CheckBox c : boxes) if (c.isChecked()) s.add((String) c.getTag());
            store.setSet("features", s);
            if (first) share(); else finishTo();
        });
        boxes.get(0).requestFocus();
    }

    private void share() {
        LinearLayout root = page(T.t("Шаг 3 из 3"), T.t("Помочь другим?"),
                T.t("Ваш опыт подскажет владельцам таких же телевизоров, что можно отключать. Без вашей галочки ничего не собирается."));
        CheckBox share = new CheckBox(this);
        share.setText(Style.twoLines(T.t("Да, делиться моим опытом"), T.t("модель, прошивка и системные пакеты; весь текст виден в «Поделиться опытом»")));
        share.setTextSize(24); share.setPadding(24, 18, 24, 18); share.setChecked(shareOn);
        Style.focus(share, Style.ACCENT, Style.TEXT, Style.DIM); root.addView(share);
        CheckBox own = new CheckBox(this);
        own.setText(Style.twoLines(T.t("…и мои приложения тоже"), T.t("кинотеатры и прочее, что вы отключили или «усыпили»")));
        own.setTextSize(24); own.setPadding(24, 18, 24, 18); own.setChecked(shareOwnOn); own.setEnabled(shareOn);
        Style.focus(own, Style.ACCENT, Style.TEXT, Style.DIM); root.addView(own);
        share.setOnCheckedChangeListener((v, on) -> { own.setEnabled(on); if (!on) own.setChecked(false); });
        root.addView(text(T.t("Передумать можно в любой момент."), 18, Style.DIM));
        Button done = button(T.t("Готово"));
        root.addView(done);
        done.setOnClickListener(v -> {
            store.setFlag("disclaimerAccepted", true);
            store.setFlag("shareConsent", share.isChecked());
            store.setFlag("shareOwn", share.isChecked() && own.isChecked());
            if (share.isChecked()) Report.saveIfConsented(this);
            finishTo();
        });
        share.requestFocus();
    }

    private void finishTo() {
        startActivity(new Intent(this, MainActivity.class).putExtra("fromFeatures", true));
        finish();
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t;
    }

    private Button button(String s) {
        Button x = new Button(this); x.setText(s); x.setTextSize(24); x.setAllCaps(false); x.setPadding(48, 20, 48, 20);
        Style.focus(x, Style.ACCENT, Style.TEXT, Style.DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(6, 28, 6, 6); lp.gravity = Gravity.START;
        x.setLayoutParams(lp);
        return x;
    }
}
