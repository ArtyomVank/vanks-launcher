package os.vanks.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * «Настройки Vanks» — отдельное приложение со своим значком. Сгруппированные
 * списки, цветные значки, страницы выезжают справа. Свои разделы (оформление,
 * обои, главный экран, пункт управления, жесты, отпечаток) меняют настройки
 * лаунчера; остальные открывают нужный экран настроек Android.
 */
public final class SettingsActivity extends Activity {
    // фон и группы — тёмные, с оттенком темы цвета (у светлых — того же тона)
    private int BG = 0xFF000000, GROUP = 0xFF1C1C1F;
    private static final int SEP = 0x24FFFFFF, SUB = 0x99EBEBF5;
    private static final int BLUE = 0xFF0A84FF, GREEN = 0xFF30D158, RED = 0xFFFF453A, PINK = 0xFFFF375F,
            INDIGO = 0xFF5E5CE6, ORANGE = 0xFFFF9F0A, TEAL = 0xFF64D2FF, GREY = 0xFF8E8E93;

    interface Icon { void draw(Canvas c, float cx, float cy, float s, int color); }

    /** Группа строк: заголовок, коробка и строки с разделителями — для поиска. */
    private static final class Group {
        TextView header;
        LinearLayout box;
        final ArrayList<View> rows = new ArrayList<>(), seps = new ArrayList<>();
    }

    private FrameLayout stack;
    private final ArrayList<View> pages = new ArrayList<>();
    private final ArrayList<Group> mainGroups = new ArrayList<>();
    private boolean animating;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Hz.apply(this);
        Ui.init(this);
        if (Apps.all.isEmpty()) {
            Apps.load(this);
            Apps.renderIcons(this, Math.round(Ui.dpf(56) * Prefs.iconScale));
        }
        int base = Look.surface();
        if (Look.light()) {
            float[] h = Look.hsl(base);
            base = Look.fromHsl(h[0], Math.min(0.4f, h[1]), 0.1f);
        }
        BG = Ui.mix(base, 0xFF000000, 0.55f);
        GROUP = Ui.mix(base, 0xFFFFFFFF, 0.08f);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        stack = new FrameLayout(this);
        stack.setBackground(Look.pageBackground(BG));
        stack.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top;
                bottom = s.bottom;
            } else {
                top = in.getSystemWindowInsetTop();
                bottom = in.getSystemWindowInsetBottom();
            }
            stack.setPadding(0, top, 0, bottom);
            return in;
        });
        setContentView(stack);
        push(mainPage(), false);
        String page = getIntent().getStringExtra("page");
        if (page != null) {
            View p = pageFor(page);
            if (p != null) push(p, false);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (editor != null) editor.back();
        else if (pages.size() > 1) pop();
        else finish();
    }

    private View pageFor(String id) {
        switch (id) {
            case "look": return lookPage();
            case "wall": return wallPage();
            case "lockwall": return lockWallPage();
            case "home": return homePage();
            case "control": return controlPage();
            case "gestures": return gesturePage();
            case "island": return islandPage();
            case "lock": return lockPage();
            case "edge": return edgePage();
            case "feel": return feelPage();
            case "about": return aboutPage();
            case "update": return updatePage();
        }
        return null;
    }

    // ── стопка страниц ────────────────────────────────────────────────────

    private void push(View p, boolean animate) {
        if (animating) return;
        View prev = pages.isEmpty() ? null : pages.get(pages.size() - 1);
        pages.add(p);
        stack.addView(p, new FrameLayout.LayoutParams(-1, -1));
        if (!animate || prev == null) {
            if (prev != null) prev.setVisibility(View.GONE);
            return;
        }
        animating = true;
        float w = stack.getWidth();
        Anim.run(0, 1, 360, Anim.EMPH, k -> {
            p.setTranslationX((1 - k) * w);
            prev.setTranslationX(-k * w * 0.3f);
            prev.setAlpha(1 - k * 0.5f);
        }, () -> {
            prev.setVisibility(View.GONE);
            prev.setTranslationX(0);
            prev.setAlpha(1);
            animating = false;
        });
    }

    private void pop() {
        if (animating || pages.size() < 2) return;
        View top = pages.remove(pages.size() - 1);
        View under = pages.get(pages.size() - 1);
        under.setVisibility(View.VISIBLE);
        animating = true;
        float w = stack.getWidth();
        Anim.run(1, 0, 300, Anim.EMPH, k -> {
            top.setTranslationX((1 - k) * w);
            under.setTranslationX(-k * w * 0.3f);
            under.setAlpha(1 - k * 0.5f);
        }, () -> {
            stack.removeView(top);
            under.setTranslationX(0);
            under.setAlpha(1);
            animating = false;
            refreshMain();
        });
    }

    /** Страница: строка «назад», большой заголовок и прокручиваемый столбец. */
    private LinearLayout page(String title, String backTo, View[] outPage) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        // стиль и тема меняют и «системные приложения»: фон и группы — материалом темы
        outer.setBackground(Look.pageBackground(BG));
        outer.setClickable(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(8), 0, Ui.dp(16), 0);
        if (backTo != null) {
            LinearLayout back = new LinearLayout(this);
            back.setGravity(Gravity.CENTER_VERTICAL);
            back.setPadding(0, 0, Ui.dp(12), 0);
            View ch = Views.drawn(this, (c, w, h) -> Glyph.chevronLeft(c, w / 2, h / 2, Ui.dpf(26), Ui.accentLight()));
            back.addView(ch, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(44)));
            back.addView(Views.label(this, backTo, 17, Ui.regular, Ui.accentLight()));
            back.setOnClickListener(v -> back());
            bar.addView(back);
        }
        outer.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(44)));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(40));
        TextView t = Views.label(this, Look.is("nothing") ? title.toUpperCase(new Locale("ru")) : title, Look.is("nothing") ? 26 : 32, Look.titleFont(Ui.bold), 0xFFFFFFFF);
        t.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(10));
        col.addView(t);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        outPage[0] = outer;
        return col;
    }

    private Group group(LinearLayout col, String header, String footer) {
        Group g = new Group();
        if (header != null) {
            // как в ColorOS: обычные буквы, тише текста; у Nothing — свои красные заглавные
            boolean nt = Look.is("nothing");
            g.header = Views.label(this, nt ? header.toUpperCase(new Locale("ru")) : header, nt ? 13 : 14,
                    nt ? Look.titleFont(Ui.regular) : Ui.medium, nt ? 0xFFD71921 : SUB);
            g.header.setPadding(Ui.dp(36), Ui.dp(18), Ui.dp(20), Ui.dp(7));
            col.addView(g.header);
        }
        g.box = new LinearLayout(this);
        g.box.setOrientation(LinearLayout.VERTICAL);
        g.box.setBackground(Look.box(GROUP));
        g.box.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = lp.rightMargin = Ui.dp(16);
        lp.topMargin = header == null ? Ui.dp(18) : 0;
        col.addView(g.box, lp);
        if (footer != null) {
            TextView f = Views.label(this, footer, 13, Ui.regular, SUB);
            f.setPadding(Ui.dp(36), Ui.dp(7), Ui.dp(28), 0);
            col.addView(f);
        }
        return g;
    }

    private void add(Group g, View row) {
        if (!g.rows.isEmpty()) {
            View s = new View(this);
            s.setBackgroundColor(SEP);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(0.5f)));
            sp.leftMargin = Ui.dp(60);
            g.box.addView(s, sp);
            g.seps.add(s);
        } else {
            g.seps.add(null);
        }
        g.box.addView(row);
        g.rows.add(row);
    }

    private View iconView(int color, Icon icon) {
        // форма — от стиля, материал — от темы
        return Views.drawn(this, (c, w, h) -> Look.rowIcon(c, w / 2, h / 2, Ui.dpf(30), color, icon::draw));
    }

    /** Строка: цветной значок, название, значение справа и шеврон, если ведёт дальше. */
    private LinearLayout row(int color, Icon icon, String title, String sub, String value, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(sub == null ? 50 : 62));
        r.setPadding(Ui.dp(icon == null ? 16 : 14), 0, Ui.dp(14), 0);
        r.setTag((sub == null ? title : title + " " + sub).toLowerCase(new Locale("ru")));
        if (icon != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32));
            ip.rightMargin = Ui.dp(14);
            r.addView(iconView(color, icon), ip);
        }
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        TextView t = Views.label(this, title, 16, Ui.regular, 0xFFFFFFFF);
        tx.addView(t);
        if (sub != null) tx.addView(Views.label(this, sub, 13, Ui.regular, SUB));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) {
            TextView v = Views.label(this, value, 16, Ui.regular, SUB);
            v.setTag("value");
            v.setPadding(Ui.dp(8), 0, Ui.dp(4), 0);
            r.addView(v);
        }
        if (click != null) {
            View ch = Views.drawn(this, (c, w, h) -> Glyph.chevronRight(c, w / 2, h / 2, Ui.dpf(20), 0x66EBEBF5));
            r.addView(ch, new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(24)));
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
            r.setOnClickListener(v -> click.run());
        }
        return r;
    }

    private static void setValue(View row, String value) {
        View v = row.findViewWithTag("value");
        if (v instanceof TextView) ((TextView) v).setText(value);
    }

    /** Переключатель как в iOS: 51×31, зелёный… здесь — цвета акцента. */
    private static final class Toggle extends View {
        boolean on;
        private float k = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(android.content.Context c, boolean on) {
            super(c);
            this.on = on;
        }

        @Override
        protected void onDraw(Canvas c) {
            float target = on ? 1 : 0;
            if (k < 0) k = target;
            k += (target - k) * 0.3f;
            if (Math.abs(target - k) > 0.01f) postInvalidateOnAnimation(); else k = target;
            float w = Ui.dpf(51), h = Ui.dpf(31), x = getWidth() - w, y = (getHeight() - h) / 2f;
            Look.toggle(c, x, y, w, h, k);
        }
    }

    interface OnToggle { void set(boolean on); }

    private LinearLayout toggleRow(int color, Icon icon, String title, String sub, boolean on, OnToggle change) {
        LinearLayout r = row(color, icon, title, sub, null, null);
        Toggle t = new Toggle(this, on);
        r.addView(t, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(40)));
        r.setOnClickListener(v -> {
            t.on = !t.on;
            t.invalidate();
            change.set(t.on);
        });
        r.setTag(R.id.toggle_tag, t);
        return r;
    }

    private static void setToggle(View row, boolean on) {
        Object t = row.getTag(R.id.toggle_tag);
        if (t instanceof Toggle) {
            ((Toggle) t).on = on;
            ((Toggle) t).invalidate();
        }
    }

    /** Сегменты: несколько вариантов в одной полоске. */
    private LinearLayout segments(String[] names, int selected, java.util.function.IntConsumer pick) {
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
        seg.setBackground(Look.segBack());
        TextView[] items = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Views.label(this, names[i], 14, Ui.medium, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setOnClickListener(v -> {
                for (int j = 0; j < items.length; j++) {
                    items[j].setBackground(j == idx ? Look.segPick() : null);
                    items[j].setTextColor(j == idx ? Look.segPickText() : 0xFFFFFFFF);
                }
                pick.accept(idx);
            });
            t.setBackground(i == selected ? Look.segPick() : null);
            t.setTextColor(i == selected ? Look.segPickText() : 0xFFFFFFFF);
            items[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(32), 1));
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        wrap.addView(seg, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private void open(String action) {
        open(new Intent(action));
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                toast("Не удалось открыть");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void save() {
        Prefs.save();
    }

    // ── главная страница ──────────────────────────────────────────────────

    private View wifiRow, btRow, lookRow, wallRow, lockRow;

    private View mainPage() {
        View[] out = new View[1];
        LinearLayout col = page("Настройки", null, out);
        mainGroups.clear();

        EditText search = new EditText(this);
        search.setHint("Найти настройку");
        search.setHintTextColor(SUB);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(17);
        search.setTypeface(Ui.regular);
        search.setSingleLine(true);
        search.setBackground(Views.round(GROUP, 23));
        search.setPadding(Ui.dp(46), 0, Ui.dp(16), 0);
        FrameLayout sf = new FrameLayout(this);
        sf.addView(search, new FrameLayout.LayoutParams(-1, Ui.dp(46)));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(Ui.dp(46), Ui.dp(46));
        gp.leftMargin = Ui.dp(2);
        sf.addView(Views.drawn(this, (c, w, h) -> Glyph.search(c, w / 2, h / 2, Ui.dpf(18), SUB)), gp);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(46));
        sp.leftMargin = sp.rightMargin = Ui.dp(16);
        col.addView(sf, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) { filter(s.toString()); }
        });

        // карточка лаунчера
        Group me = group(col, null, null);
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        View logo = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, Ui.accentLight(), Ui.accentDark(), android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(w / 2, h / 2, w / 2, p);
            Paint t = Ui.text(Ui.bold, 26, 0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText("V", w / 2, h / 2 + Ui.dpf(9), t);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(60), Ui.dp(60));
        lp.rightMargin = Ui.dp(14);
        card.addView(logo, lp);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Views.label(this, "Vanks", 21, Ui.medium, 0xFFFFFFFF));
        tx.addView(Views.label(this, "Лаунчер, вход по отпечатку, оформление", 13, Ui.regular, SUB));
        card.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(Views.drawn(this, (c, w, h) -> Glyph.chevronRight(c, w / 2, h / 2, Ui.dpf(20), 0x66EBEBF5)),
                new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(24)));
        card.setTag("vanks о лаунчере");
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        card.setOnClickListener(v -> push(aboutPage(), true));
        add(me, card);
        mainGroups.add(me);

        // самое нужное — крупными плитками, как в ColorOS
        quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.VERTICAL);
        LinearLayout q1 = new LinearLayout(this), q2 = new LinearLayout(this);
        q1.addView(quickCard(TEAL, Glyph::image, "Обои", "Стол и блокировка", () -> push(lockWallPage(), true)), quickLp(true));
        q1.addView(quickCard(Ui.accent(), Glyph::palette, "Оформление", "Стиль и цвет", () -> push(lookPage(), true)), quickLp(false));
        q2.addView(quickCard(INDIGO, Glyph::grid, "Главный экран", "Значки и сетка", () -> push(homePage(), true)), quickLp(true));
        q2.addView(quickCard(RED, (c, x, y, sz, cl) -> Glyph.fingerprint(c, x, y, sz, cl, 1f), "Блокировка", "Отпечаток и вход",
                () -> push(lockPage(), true)), quickLp(false));
        quick.addView(q1);
        LinearLayout.LayoutParams q2p = new LinearLayout.LayoutParams(-1, -2);
        q2p.topMargin = Ui.dp(10);
        quick.addView(q2, q2p);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.leftMargin = qp.rightMargin = Ui.dp(16);
        qp.topMargin = Ui.dp(14);
        col.addView(quick, qp);

        Group vanks = group(col, "Vanks", null);
        lookRow = row(Ui.accent(), Glyph::palette, "Оформление", "Стиль окон, тема, цвет", "", () -> push(lookPage(), true));
        add(vanks, lookRow);
        wallRow = row(TEAL, Glyph::image, "Экран блокировки и обои", "Часы, виджеты, картинка", "", () -> push(lockWallPage(), true));
        add(vanks, wallRow);
        add(vanks, row(INDIGO, Glyph::grid, "Главный экран", "Значки, папки, сетка, док", null, () -> push(homePage(), true)));
        lockRow = row(RED, (c, x, y, s, col2) -> Glyph.fingerprint(c, x, y, s, col2, 1f), "Отпечаток и вход", "Свой экран блокировки, датчик", "", () -> push(lockPage(), true));
        add(vanks, lockRow);
        add(vanks, row(GREEN, Glyph::rotate, "Обновление", Updater.known != null ? "Доступна " + Updater.known.version : "Лаунчер и «Игры Vanks»",
                Updater.current(this), () -> push(updatePage(), true)));
        mainGroups.add(vanks);

        Group more = group(col, "Удобство", null);
        add(more, row(GREY, Glyph::sliders, "Пункт управления", "Плитки: свет, звук, Wi‑Fi", null, () -> push(controlPage(), true)));
        add(more, row(ORANGE, Glyph::swipe, "Жесты", "Свайпы и жесты Android", null, () -> push(gesturePage(), true)));
        add(more, row(INDIGO, (c, x, y, s, col2) -> Glyph.play(c, x, y, s, col2, true), "Динамический остров", "Звук, такси, доставка, музыка", null, () -> push(islandPage(), true)));
        add(more, row(TEAL, Glyph::grid, "Боковая панель", "10 приложений у края экрана", null, () -> push(edgePage(), true)));
        add(more, row(PINK, Glyph::speaker, "Звуки и отклик", "Звуки Vanks, вибрация, скорость", null, () -> push(feelPage(), true)));
        mainGroups.add(more);

        Group net = group(col, "Связь", null);
        wifiRow = row(BLUE, Glyph::wifi, "Wi‑Fi", null, "", () -> open(Settings.ACTION_WIFI_SETTINGS));
        add(net, wifiRow);
        btRow = row(BLUE, Glyph::bluetooth, "Bluetooth", null, "", () -> open(Settings.ACTION_BLUETOOTH_SETTINGS));
        add(net, btRow);
        add(net, row(GREEN, Glyph::signal, "Сеть и интернет", "SIM, мобильные данные", null, () -> open(Settings.ACTION_WIRELESS_SETTINGS)));
        add(net, row(ORANGE, Glyph::plane, "Авиарежим", null, null, () -> open(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
        mainGroups.add(net);

        Group snd = group(col, "Звук и уведомления", null);
        add(snd, row(RED, Glyph::bell, "Уведомления", null, null, () -> open("android.settings.NOTIFICATION_SETTINGS")));
        add(snd, row(PINK, Glyph::speaker, "Звуки и вибрация", "Громкость, мелодия", null, () -> open(Settings.ACTION_SOUND_SETTINGS)));
        add(snd, row(INDIGO, (c, x, y, s, col2) -> Glyph.moon(c, x, y, s, col2, true), "Не беспокоить", "Тишина по расписанию", null,
                () -> open("android.settings.ZEN_MODE_SETTINGS")));
        mainGroups.add(snd);

        Group dev = group(col, "Телефон", null);
        add(dev, row(BLUE, Glyph::sun, "Экран и яркость", "Частота, яркость", null, () -> push(displayPage(), true)));
        add(dev, row(GREEN, (c, x, y, s, col2) -> Glyph.battery(c, x, y, s, col2, 0.8f), "Батарея", null, null,
                () -> open(Intent.ACTION_POWER_USAGE_SUMMARY)));
        add(dev, row(INDIGO, Glyph::grid, "Приложения", "Удалить, разрешения", null, () -> open(Settings.ACTION_APPLICATION_SETTINGS)));
        add(dev, row(GREY, Glyph::storage, "Хранилище", "Сколько места занято", null, () -> open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        mainGroups.add(dev);

        Group priv = group(col, "Защита", null);
        add(priv, row(BLUE, Glyph::shield, "Конфиденциальность", null, null, () -> open(Settings.ACTION_PRIVACY_SETTINGS)));
        add(priv, row(GREY, (c, x, y, s, col2) -> Glyph.lock(c, x, y, s, col2, false), "Безопасность", "PIN-код, отпечатки Android", null,
                () -> open(Settings.ACTION_SECURITY_SETTINGS)));
        mainGroups.add(priv);

        Group sys = group(col, "Система", null);
        add(sys, row(GREY, Glyph::phone, "Лаунчер по умолчанию", "Чтобы «домой» открывал Vanks", null, () -> open(Settings.ACTION_HOME_SETTINGS)));
        add(sys, row(GREY, Glyph::info, "О телефоне", null, null, () -> open(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        add(sys, row(GREY, Glyph::gear, "Все настройки Android", null, null, () -> open(Settings.ACTION_SETTINGS)));
        mainGroups.add(sys);

        refreshMain();
        return out[0];
    }

    private LinearLayout quick;

    private LinearLayout.LayoutParams quickLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(96), 1);
        if (left) lp.rightMargin = Ui.dp(5);
        else lp.leftMargin = Ui.dp(5);
        return lp;
    }

    /** Крупная плитка: значок в цветном круге, название и что внутри. */
    private View quickCard(int color, Icon icon, String title, String hint, Runnable click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        card.setBackground(Look.box(GROUP));
        card.setClipToOutline(true);
        card.setForeground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
        int col = color | 0xFF000000;
        card.addView(Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Ui.alpha(col, 0.2f));
            c.drawCircle(h / 2, h / 2, h / 2, p);
            icon.draw(c, h / 2, h / 2, h * 0.56f, Ui.mix(col, 0xFFFFFFFF, 0.25f));
        }), new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
        View gap = new View(this);
        card.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
        card.addView(Views.label(this, title, 16, Ui.medium, 0xFFFFFFFF));
        card.addView(Views.label(this, hint, 12, Ui.regular, SUB));
        card.setOnClickListener(v -> {
            Feel.buzz(v, Feel.TICK);
            click.run();
        });
        return card;
    }

    /** Значения справа на главной: что сейчас выбрано. */
    private void refreshMain() {
        if (wifiRow == null) return;
        boolean wifi = false, bt = false;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            wifi = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }
        try {
            android.bluetooth.BluetoothManager bm = getSystemService(android.bluetooth.BluetoothManager.class);
            bt = bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
        } catch (Exception ignored) {
        }
        setValue(wifiRow, wifi ? "Подключено" : "Нет");
        setValue(btRow, bt ? "Вкл." : "Выкл.");
        setValue(lookRow, Look.UI_NAMES[indexOf(Look.UI_IDS, Prefs.ui)] + " · " + Look.tone().name);
        setValue(wallRow, Walls.current().title);
        setValue(lockRow, Prefs.lockMain ? "Свой" : Prefs.fingerprint ? "Вкл." : "Выкл.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMain();
    }

    @Override
    protected void onPause() {
        super.onPause();
        FingerSensor.cancel();
    }

    private void filter(String q) {
        String s = q.trim().toLowerCase(new Locale("ru"));
        if (quick != null) quick.setVisibility(s.isEmpty() ? View.VISIBLE : View.GONE);
        for (Group g : mainGroups) {
            boolean any = false;
            boolean first = true;
            for (int i = 0; i < g.rows.size(); i++) {
                View r = g.rows.get(i);
                Object tag = r.getTag();
                boolean show = s.isEmpty() || (tag instanceof String && ((String) tag).contains(s));
                r.setVisibility(show ? View.VISIBLE : View.GONE);
                View sep = g.seps.get(i);
                if (sep != null) sep.setVisibility(show && !first ? View.VISIBLE : View.GONE);
                if (show) { any = true; first = false; }
            }
            g.box.setVisibility(any ? View.VISIBLE : View.GONE);
            if (g.header != null) g.header.setVisibility(any ? View.VISIBLE : View.GONE);
        }
    }

    // ── оформление ────────────────────────────────────────────────────────

    /** Превью: полоска обоев, на ней панель в текущей теме и несколько значков. */
    private final class Preview extends View {
        private final ArrayList<Bitmap> icons = new ArrayList<>();
        private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final RectF r = new RectF();

        Preview() {
            super(SettingsActivity.this);
            refresh();
        }

        void refresh() {
            icons.clear();
            ArrayList<String> keys = new ArrayList<>(Prefs.dock);
            keys.addAll(Prefs.home);
            List<Apps.App> list = Apps.resolve(keys);
            int px = Math.round(Ui.dpf(50) * Prefs.iconScale);
            for (int i = 0; i < Math.min(4, list.size()); i++) icons.add(Apps.render(SettingsActivity.this, list.get(i), px));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawBitmap(Ui.wall, Ui.wallMatrix(Ui.wall, w, h, 1f, m), bp);
            float pad = Ui.dpf(14);
            r.set(pad, h - Ui.dpf(86), w - pad, h - pad);
            Look.panel(c, r, Math.min(Look.radius(22), r.height() / 2), 0, 0, w, h);
            ClockView.draw(c, getContext(), Look.clock(), Ui.dpf(20), Ui.dpf(10), 0.55f, false);
            if (icons.isEmpty()) return;
            float cell = r.width() / 4f;
            for (int i = 0; i < icons.size(); i++) {
                Bitmap b = icons.get(i);
                float cx = r.left + cell * (i + 0.5f), cy = r.centerY();
                c.drawBitmap(b, cx - b.getWidth() / 2f, cy - b.getHeight() / 2f, bp);
            }
        }
    }

    /** Строки-варианты с галочкой у выбранного. */
    private void choices(Group g, String[] names, String[] notes, int selected, java.util.function.IntConsumer pick) {
        View[] checks = new View[names.length];
        int[] sel = {selected};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout r = row(0, null, names[i], notes == null ? null : notes[i], null, null);
            View ch = Views.drawn(this, (c, w, h) -> {
                if (sel[0] != idx) return;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(2.4f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(Ui.accentLight());
                float cx = w / 2, cy = h / 2;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(cx - Ui.dpf(7), cy);
                path.lineTo(cx - Ui.dpf(2), cy + Ui.dpf(5));
                path.lineTo(cx + Ui.dpf(8), cy - Ui.dpf(6));
                c.drawPath(path, p);
            });
            checks[i] = ch;
            r.addView(ch, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
            r.setOnClickListener(v -> {
                sel[0] = idx;
                for (View x : checks) x.invalidate();
                pick.accept(idx);
            });
            add(g, r);
        }
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;
    }

    /** Применить и перестроить «Настройки» с тем же экраном (цвета страниц от темы цвета). */
    private void restartOn(String page) {
        save();
        getIntent().putExtra("page", page);
        recreate();
    }

    private View lookPage() {
        View[] out = new View[1];
        LinearLayout col = page("Оформление", "Настройки", out);
        Preview pv = new Preview();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(190));
        pp.leftMargin = pp.rightMargin = Ui.dp(16);
        pp.topMargin = Ui.dp(8);
        pv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(16));
            }
        });
        pv.setClipToOutline(true);
        col.addView(pv, pp);

        Group ui = group(col, "Стиль", "Как устроены кнопки и плитки — как стили Vanks OS на компьютере.");
        choices(ui, Look.UI_NAMES, Look.UI_NOTES, indexOf(Look.UI_IDS, Prefs.ui), i -> {
            Prefs.ui = Look.UI_IDS[i];
            save();
            pv.invalidate();
            // стиль меняет и сами «Настройки» — перестроить их
            stack.postDelayed(() -> restartOn("look"), 220);
        });

        Group mat = group(col, "Тема", "Из чего сделаны панели, «Настройки», переключатели и значки: стекло, акрил или сплошной цвет.");
        choices(mat, Look.MAT_NAMES, Look.MAT_NOTES, indexOf(Look.MAT_IDS, Look.mat()), i -> {
            Look.applyMaterial(Look.MAT_IDS[i]);
            save();
            pv.invalidate();
            stack.postDelayed(() -> restartOn("look"), 220);
        });

        Group tones = group(col, "Цвет", "Цвет стекла, панелей и «Настроек». «Цвет обоев» берёт его из обоев.");
        add(tones, new ToneGrid(() -> restartOn("look")));

        Group acc = group(col, "Акцент", null);
        View dots = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                int n = Ui.ACCENTS.length;
                float cell = getWidth() / (float) n;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                Paint t = Ui.text(Ui.regular, 10, SUB);
                t.setTextAlign(Paint.Align.CENTER);
                for (int i = 0; i < n; i++) {
                    float cx = cell * (i + 0.5f), cy = Ui.dpf(28);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Ui.ACCENTS[i]);
                    c.drawCircle(cx, cy, Ui.dpf(14), p);
                    if (Ui.ACCENTS[i] == Ui.accent()) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(Ui.dpf(2.5f));
                        p.setColor(0xFFFFFFFF);
                        c.drawCircle(cx, cy, Ui.dpf(19), p);
                    }
                    c.drawText(Ui.ACCENT_NAMES[i], cx, Ui.dpf(64), t);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    int i = (int) (e.getX() / (getWidth() / (float) Ui.ACCENTS.length));
                    Prefs.accent = Ui.ACCENTS[Math.max(0, Math.min(Ui.ACCENTS.length - 1, i))];
                    save();
                    invalidate();
                    pv.refresh();
                }
                return true;
            }
        };
        dots.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(78)));
        add(acc, dots);

        // какие значки: свои Vanks, как в системе или из пака
        java.util.LinkedHashMap<String, String> packs = IconPacks.installed(this);
        ArrayList<String> iconIds = new ArrayList<>(java.util.Arrays.asList("vanks", "system"));
        ArrayList<String> iconNames = new ArrayList<>(java.util.Arrays.asList("Vanks", "Как в системе"));
        ArrayList<String> iconNotes = new ArrayList<>(java.util.Arrays.asList(
                "В форме ниже, с бликом; можно в тон акцента", "Как их рисует телефон, с темой значков прошивки"));
        for (java.util.Map.Entry<String, String> e : packs.entrySet()) {
            iconIds.add("pack:" + e.getKey());
            iconNames.add(e.getValue());
            iconNotes.add("Пак значков");
        }
        Group ic = group(col, "Значки", packs.isEmpty()
                ? "Паки значков для лаунчеров (формат ADW / Nova) из Google Play появятся здесь после установки."
                : "Если в паке нет значка приложения, он собирается из подложки пака или рисуется как в Vanks.");
        int icSel = Math.max(0, iconIds.indexOf(Prefs.icons));
        choices(ic, iconNames.toArray(new String[0]), iconNotes.toArray(new String[0]), icSel, i -> {
            Prefs.icons = iconIds.get(i);
            save();
            pv.refresh();
        });
        add(ic, row(GREEN, Glyph::search, "Найти паки значков", null, null, () -> {
            Intent m = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=icon%20pack&c=apps"));
            if (getPackageManager().resolveActivity(m, 0) == null)
                m = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=icon%20pack&c=apps"));
            open(m);
        }));

        Group shape = group(col, "Форма значков", "Для всех значков: Vanks, системных и из пака. До конца вправо — круг. «Как в системе» на HyperOS берёт значки из темы самой прошивки, форма обрезает и их.");
        add(shape, slider("Скругление", 0, 100, Math.round(Prefs.iconRound * 100), v -> Prefs.iconRound = v / 100f, () -> {
            save();
            pv.refresh();
        }));

        Group size = group(col, "Размер значков", "Для стола, дока, папок и поиска. Крупные значки не выходят за свою клетку.");
        TextView sizeVal = Views.label(this, Math.round(Prefs.iconScale * 100) + "%", 15, Ui.medium, SUB);
        LinearLayout sl = slider("Размер", 60, 140, Math.round(Prefs.iconScale * 100), v -> {
            Prefs.iconScale = v / 100f;
            sizeVal.setText(v + "%");
        }, () -> {
            save();
            pv.refresh();
        });
        sl.addView(sizeVal, new LinearLayout.LayoutParams(Ui.dp(48), -2));
        sizeVal.setGravity(Gravity.END);
        add(size, sl);

        Group th = group(col, null, "Одноцветные значки в цвет акцента. Лучше всего выглядит на Android 13 и новее — там у приложений есть одноцветные значки.");
        add(th, toggleRow(INDIGO, Glyph::palette, "Значки в тон", null, Prefs.themed, on -> {
            Prefs.themed = on;
            save();
            pv.refresh();
        }));
        return out[0];
    }

    /** Цвета Delta кружками: тёмные, светлые и от обоев. Сверху — имя выбранного. */
    private final class ToneGrid extends View {
        private static final int COLS = 6;
        private final Runnable changed;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ToneGrid(Runnable changed) {
            super(SettingsActivity.this);
            this.changed = changed;
            int rows = (Look.TONES.length + COLS - 1) / COLS;
            setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(40 + rows * 52 + 6)));
        }

        @Override
        protected void onDraw(Canvas c) {
            Paint t = Ui.text(Ui.medium, 15, 0xFFFFFFFF);
            c.drawText(Look.tone().name, Ui.dpf(16), Ui.dpf(28), t);
            float cell = getWidth() / (float) COLS;
            for (int i = 0; i < Look.TONES.length; i++) {
                Look.Tone tn = Look.TONES[i];
                float cx = cell * (i % COLS + 0.5f), cy = Ui.dpf(40 + 26) + (i / COLS) * Ui.dpf(52);
                float rr = Ui.dpf(17);
                p.setStyle(Paint.Style.FILL);
                if (tn.wall) {
                    // половина — цвет обоев, половина — сама тема
                    p.setColor(Look.wallSeed);
                    c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, 90, 180, true, p);
                    p.setColor(tn.light ? 0xFFF2F2F5 : 0xFF141418);
                    c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, 270, 180, true, p);
                } else {
                    p.setColor(tn.surface);
                    c.drawCircle(cx, cy, rr, p);
                    if (tn.accent != 0) {
                        p.setColor(tn.accent);
                        c.drawCircle(cx + rr * 0.55f, cy + rr * 0.55f, rr * 0.36f, p);
                    }
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1));
                p.setColor(0x40FFFFFF);
                c.drawCircle(cx, cy, rr, p);
                if (tn == Look.tone()) {
                    p.setStrokeWidth(Ui.dpf(2.5f));
                    p.setColor(Ui.accentLight());
                    c.drawCircle(cx, cy, rr + Ui.dpf(4.5f), p);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int colI = (int) (e.getX() / (getWidth() / (float) COLS));
                int rowI = (int) ((e.getY() - Ui.dpf(40)) / Ui.dpf(52));
                int i = rowI * COLS + Math.max(0, Math.min(COLS - 1, colI));
                if (rowI >= 0 && i < Look.TONES.length && Look.TONES[i] != Look.tone()) {
                    Look.applyTone(Look.TONES[i]);
                    changed.run();
                }
            }
            return true;
        }
    }

    // ── обои ──────────────────────────────────────────────────────────────

    /** Карточки обоев одной группы, по три в ряд. */
    private final class WallGrid extends View {
        private static final int COLS = 3;
        private final List<Walls.W> walls;
        private final Bitmap[] thumbs;
        private final Runnable picked;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final RectF r = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        WallGrid(List<Walls.W> walls, Runnable picked) {
            super(SettingsActivity.this);
            this.walls = walls;
            this.picked = picked;
            thumbs = new Bitmap[walls.size()];
            for (int i = 0; i < thumbs.length; i++) thumbs[i] = Ui.wallThumb(SettingsActivity.this, walls.get(i));
        }

        private float gap() { return Ui.dpf(10); }
        private float cardW() { return (getWidth() - Ui.dpf(16) * 2 - gap() * (COLS - 1)) / COLS; }
        private float cardH() { return cardW() * 2.05f; }
        private float rowH() { return cardH() + Ui.dpf(32); }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            float cw = (w - Ui.dpf(16) * 2 - gap() * (COLS - 1)) / COLS;
            int rows = (walls.size() + COLS - 1) / COLS;
            setMeasuredDimension(w, Math.round(rows * (cw * 2.05f + Ui.dpf(32)) + Ui.dpf(6)));
        }

        @Override
        protected void onDraw(Canvas c) {
            Paint t = Ui.text(Ui.medium, 12, 0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            Walls.W cur = Walls.current();
            for (int i = 0; i < walls.size(); i++) {
                Walls.W w = walls.get(i);
                float x = Ui.dpf(16) + (i % COLS) * (cardW() + gap()), y = Ui.dpf(6) + (i / COLS) * rowH();
                r.set(x, y, x + cardW(), y + cardH());
                c.save();
                clip.reset();
                clip.addRoundRect(r, Ui.dpf(12), Ui.dpf(12), android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                Bitmap b = thumbs[i];
                float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                m.setScale(s, s);
                m.postTranslate(r.centerX() - b.getWidth() * s / 2, r.centerY() - b.getHeight() * s / 2);
                c.drawBitmap(b, m, p);
                c.restore();
                if (w.kind != Walls.PHOTO) {
                    // значок «живые»: три точки-волны в углу
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x66000000);
                    c.drawCircle(r.left + Ui.dpf(15), r.top + Ui.dpf(15), Ui.dpf(10), p);
                    p.setColor(0xFFFFFFFF);
                    for (int k = -1; k <= 1; k++) c.drawCircle(r.left + Ui.dpf(15) + k * Ui.dpf(4.5f), r.top + Ui.dpf(15), Ui.dpf(1.6f), p);
                }
                if (w == cur) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dpf(3));
                    p.setColor(Ui.accent());
                    r.inset(-Ui.dpf(3.5f), -Ui.dpf(3.5f));
                    c.drawRoundRect(r, Ui.dpf(15), Ui.dpf(15), p);
                    r.inset(Ui.dpf(3.5f), Ui.dpf(3.5f));
                    p.setStyle(Paint.Style.FILL);
                }
                c.drawText(w.title, r.centerX(), r.bottom + Ui.dpf(20), t);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int colI = (int) ((e.getX() - Ui.dpf(16)) / (cardW() + gap()));
                int rowI = (int) ((e.getY() - Ui.dpf(6)) / rowH());
                int i = rowI * COLS + Math.max(0, Math.min(COLS - 1, colI));
                if (rowI >= 0 && i < walls.size()) applyWall(walls.get(i), picked);
            }
            return true;
        }
    }

    private View wallPage() {
        View[] out = new View[1];
        LinearLayout col = page("Обои", "Настройки", out);
        TextView note = Views.label(this, WallView.supported()
                ? "Супер обои: на экране входа — вид издалека, при входе камера летит к ним. Живые: при входе рисунок доигрывает и замирает."
                : "На этом Android (до 13) живые и супер обои показываются готовым кадром — шейдерам нужен Android 13.", 13, Ui.regular, SUB);
        note.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(4));
        col.addView(note);
        ArrayList<WallGrid> grids = new ArrayList<>();
        Runnable picked = () -> {
            for (WallGrid g : grids) g.invalidate();
        };
        for (java.util.Map.Entry<String, List<Walls.W>> e : Walls.groups().entrySet()) {
            TextView h = Views.label(this, e.getKey().toUpperCase(new Locale("ru")), 13, Ui.regular, SUB);
            h.setPadding(Ui.dp(36), Ui.dp(18), Ui.dp(20), Ui.dp(4));
            col.addView(h);
            WallGrid g = new WallGrid(e.getValue(), picked);
            grids.add(g);
            col.addView(g);
        }
        Group mv = group(col, null, "Обычно рисунок замирает после входа — так не тратится батарея.");
        add(mv, toggleRow(INDIGO, Glyph::image, "Двигаются и на рабочем столе", null, Prefs.wallAnimate, on -> {
            Prefs.wallAnimate = on;
            save();
        }));
        Group d = group(col, null, "На экране входа часы прячутся за гору. Работает с фото «Вершина».");
        add(d, toggleRow(TEAL, Glyph::image, "Эффект глубины", null, Prefs.depth, on -> {
            Prefs.depth = on;
            save();
        }));
        return out[0];
    }

    // ── экран блокировки и обои ───────────────────────────────────────────

    /**
     * Как в ColorOS: сверху — стопка обоев (листается вбок), на каждой —
     * экран блокировки с часами. Ниже — часы (все виды часов Delta), цвет,
     * размер, глубина, движение обоев; в конце — отпечаток, эффекты и
     * главный экран.
     */
    private View lockWallPage() {
        View[] out = new View[1];
        LinearLayout col = page("Экран блокировки и обои", "Настройки", out);
        WallStack stack = new WallStack();
        TextView name = Views.label(this, "", 17, Ui.medium, 0xFFFFFFFF);
        name.setGravity(Gravity.CENTER);
        TextView where = Views.label(this, "", 13, Ui.regular, SUB);
        where.setGravity(Gravity.CENTER);
        Runnable info = () -> {
            Walls.W w = stack.current();
            name.setText(w.title);
            String lockId = Prefs.lockWallpaper.isEmpty() ? Prefs.wallpaper : Prefs.lockWallpaper;
            boolean h = w.id.equals(Prefs.wallpaper), l = w.id.equals(lockId);
            String kind = w.kind == Walls.SHADER ? "Супер обои" : w.kind == Walls.FLOW ? "Живые обои" : "Фото";
            where.setText(kind + (h && l ? " · стоят на обоих экранах" : h ? " · на главном экране" : l ? " · на экране блокировки" : ""));
        };
        stack.changed = info;
        col.addView(stack, new LinearLayout.LayoutParams(-1, Ui.dp(440)));
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));
        col.addView(where, new LinearLayout.LayoutParams(-1, -2));

        // быстрый переход по группам
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(4));
        int idx = 0;
        for (java.util.Map.Entry<String, List<Walls.W>> e : Walls.groups().entrySet()) {
            final int first = idx;
            TextView t = Views.label(this, e.getKey(), 13, Ui.medium, 0xFFFFFFFF);
            t.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
            t.setBackground(Views.round(0x3D767680, 14));
            t.setOnClickListener(v -> stack.go(first));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(6);
            chips.addView(t, lp);
            idx += e.getValue().size();
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        col.addView(hs);

        TextView apply = Views.label(this, "Применить", 16, Ui.medium, 0xFFFFFFFF);
        apply.setGravity(Gravity.CENTER);
        apply.setBackground(Views.round(Ui.accent(), 22));
        apply.setOnClickListener(v -> applyWall(stack.current(), () -> {
            stack.invalidate();
            info.run();
        }));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, Ui.dp(46));
        ap.setMargins(Ui.dp(40), Ui.dp(12), Ui.dp(40), Ui.dp(4));
        col.addView(apply, ap);
        info.run();

        // часы
        Group cl = group(col, "Часы", "Все виды часов Delta с компьютера. Нажмите на вид — стопка сверху сразу покажет, как это будет.");
        add(cl, clockStrip(stack::invalidate));
        add(cl, segments(ClockView.TINT_NAMES, Math.max(0, indexOf(ClockView.TINTS, Prefs.lockClockColor)), i -> {
            Prefs.lockClockColor = ClockView.TINTS[i];
            save();
            stack.invalidate();
        }));
        add(cl, slider("Размер", 70, 130, Math.round(Prefs.lockClockSize * 100), v -> {
            Prefs.lockClockSize = v / 100f;
            stack.invalidate();
        }, () -> save()));
        add(cl, toggleRow(ORANGE, Glyph::grid, "Часы переезжают на главный экран", "При входе часы с экрана блокировки летят в часы на столе и там остаются такими же", Prefs.clockToHome, on -> {
            Prefs.clockToHome = on;
            save();
        }));
        add(cl, row(BLUE, Glyph::sliders, "Изменить экран блокировки", "Размер, ширина и высота часов, виджеты", null,
                () -> openLockEditor(stack.current(), stack::invalidate)));
        add(cl, toggleRow(TEAL, Glyph::image, "Глубина", "Гора перед цифрами (фото «Вершина»)", Prefs.depth, on -> {
            Prefs.depth = on;
            save();
            stack.invalidate();
        }));
        add(cl, toggleRow(INDIGO, Glyph::image, "Анимировать обои", "Живые и супер обои двигаются и на рабочем столе. Тратит батарею", Prefs.wallAnimate, on -> {
            Prefs.wallAnimate = on;
            save();
        }));

        // отпечаток
        Group fp = group(col, "Сканер отпечатка", null);
        String[] fpIds = {"pulse", "ripple", "glow", "none"};
        add(fp, segments(new String[]{"Пульс", "Волна", "Свечение", "Нет"}, Math.max(0, indexOf(fpIds, Prefs.fpEffect)), i -> {
            Prefs.fpEffect = fpIds[i];
            save();
            stack.invalidate();
        }));
        add(fp, row(RED, (c, x, y, s, c2) -> Glyph.fingerprint(c, x, y, s, c2, 1f), "Отпечаток и вход", null, Prefs.fingerprint ? "Вкл." : "Выкл.",
                () -> push(lockPage(), true)));

        // эффекты
        Group fx = group(col, "Эффекты", "Волна — как при входе в Delta на компьютере: по экрану проходит стекло.");
        add(fx, toggleRow(BLUE, Glyph::swipe, "Стеклянная волна при входе", null, Prefs.unlockWave, on -> {
            Prefs.unlockWave = on;
            save();
        }));
        add(fx, row(PINK, Glyph::speaker, "Звуки и отклик", null, null, () -> push(feelPage(), true)));

        // главный экран
        Group hm = group(col, "Главный экран", null);
        add(hm, row(INDIGO, Glyph::grid, "Главный экран", "Сетка, подписи, скорость анимаций", null, () -> push(homePage(), true)));
        add(hm, row(TEAL, Glyph::image, "Все обои сеткой", null, null, () -> push(wallPage(), true)));
        Group sy = group(col, null, "Иначе под экраном блокировки, в «Недавних» и при открытии приложений иногда видны обои, которые стоят в Android.");
        add(sy, toggleRow(GREY, Glyph::image, "Те же обои в системе", null, Prefs.syncSystemWall, on -> {
            Prefs.syncSystemWall = on;
            save();
            SysWall.sync(this);
        }));
        return out[0];
    }

    /** Куда поставить обои: на главный, на экран блокировки или на оба. */
    private void applyWall(Walls.W w, Runnable done) {
        String[] items = {"На главный экран", "На экран блокировки", "На оба экрана"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(w.title)
                .setItems(items, (d, which) -> {
                    String oldHome = Prefs.wallpaper;
                    switch (which) {
                        case 0:
                            // экран блокировки шёл за главным — пусть останется, как был
                            if (Prefs.lockWallpaper.isEmpty()) Prefs.lockWallpaper = oldHome;
                            Prefs.wallpaper = w.id;
                            break;
                        case 1:
                            Prefs.lockWallpaper = w.id;
                            break;
                        default:
                            Prefs.wallpaper = w.id;
                            Prefs.lockWallpaper = "";
                    }
                    if (Prefs.lockWallpaper.equals(Prefs.wallpaper)) Prefs.lockWallpaper = "";
                    save();
                    Ui.loadWall(this);
                    Ui.loadLockWall(this);
                    SysWall.sync(this);
                    toast("Обои поставлены");
                    if (done != null) done.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private LinearLayout slider(String title, int min, int max, int value, java.util.function.IntConsumer change, Runnable done) {
        LinearLayout sl = new LinearLayout(this);
        sl.setGravity(Gravity.CENTER_VERTICAL);
        sl.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
        sl.addView(Views.label(this, title, 16, Ui.regular, 0xFFFFFFFF));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setProgressTintList(ColorStateList.valueOf(Ui.accent()));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) change.accept(v + min);
            }

            public void onStartTrackingTouch(SeekBar s) {}

            public void onStopTrackingTouch(SeekBar s) {
                done.run();
                Feel.sound(SettingsActivity.this, "tick");
            }
        });
        sl.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(44), 1));
        return sl;
    }

    private static final int PICK_FP = 41;

    /** Своё фото для отпечатка: из галереи, обрезается в круг. */
    private void pickFingerPhoto() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, PICK_FP);
        } catch (Exception e) {
            toast("Нет приложения для выбора фото");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FP || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            int side = Math.min(o.outWidth, o.outHeight);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (side / (o.inSampleSize * 2) >= 320) o.inSampleSize *= 2;
            Bitmap b;
            try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
                b = android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) throw new java.io.IOException();
            int s = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
            Bitmap out = Bitmap.createScaledBitmap(sq, 256, 256, true);
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(LockFace.fpFile(this))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
            LockFace.fpChanged();
            Prefs.fpIcon = "photo";
            save();
            if (editor != null) editor.invalidateAll();
            toast("Фото отпечатка поставлено");
        } catch (Exception e) {
            toast("Не удалось открыть фото");
        }
    }

    /** Цвета для частей часов: 0 — акцент. */
    private static final int[] SWATCHES = {0xFFFFFFFF, 0, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A, 0xFFFF375F, 0xFFBF5AF2,
            0xFF5E5CE6, 0xFF0A84FF, 0xFF64D2FF, 0xFF30D158, 0xFF8E8E93, 0xFF1C1C1E};

    /** Полоска кружков-цветов с подписью. */
    private View swatches(String title, int current, java.util.function.IntConsumer pick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = Views.label(this, title, 13, Ui.regular, SUB);
        h.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), 0);
        col.addView(h);
        int[] sel = {current};
        View v = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                float r = Ui.dpf(13), step = Ui.dpf(38), y = getHeight() / 2f;
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int i = 0; i < SWATCHES.length; i++) {
                    float x = Ui.dpf(20) + r + i * step;
                    int col = SWATCHES[i] == 0 ? Ui.accent() : SWATCHES[i];
                    q.setStyle(Paint.Style.FILL);
                    q.setColor(col);
                    c.drawCircle(x, y, r, q);
                    if (SWATCHES[i] == 0) {
                        // акцент — с буквой «А»
                        Paint t = Ui.text(Ui.bold, 11, 0xFFFFFFFF);
                        t.setTextAlign(Paint.Align.CENTER);
                        c.drawText("А", x, y + Ui.dpf(4), t);
                    }
                    q.setStyle(Paint.Style.STROKE);
                    q.setStrokeWidth(Ui.dpf(1));
                    q.setColor(0x40FFFFFF);
                    c.drawCircle(x, y, r, q);
                    if (SWATCHES[i] == sel[0]) {
                        q.setStrokeWidth(Ui.dpf(2.2f));
                        q.setColor(0xFFFFFFFF);
                        c.drawCircle(x, y, r + Ui.dpf(4), q);
                    }
                }
            }

            @Override
            protected void onMeasure(int wm, int hm) {
                setMeasuredDimension(Math.round(Ui.dpf(40) + SWATCHES.length * Ui.dpf(38)), Ui.dp(46));
            }
        };
        v.setOnTouchListener((x, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int i = Math.round((e.getX() - Ui.dpf(33)) / Ui.dpf(38));
                if (i >= 0 && i < SWATCHES.length) {
                    sel[0] = SWATCHES[i];
                    Feel.buzz(x, Feel.TICK);
                    pick.accept(SWATCHES[i]);
                    x.invalidate();
                }
            }
            return true;
        });
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(v);
        col.addView(hs);
        return col;
    }

    /** Настройки «Длинных» часов: вид цифр, цвет часов и минут. */
    private LinearLayout posterBlock(Runnable changed) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        String[] ids = {"hollow", "mixed", "glass", "solid"};
        // пустые внутри · часы стеклянные, минуты обычные · всё стекло · обычные без стекла
        col.addView(segments(new String[]{"Пустые", "Часы стекло", "Стекло", "Обычные"}, Math.max(0, indexOf(ids, Prefs.posterKind)), k -> {
            Prefs.posterKind = ids[k];
            save();
            changed.run();
        }));
        col.addView(slider("Ширина цифр", 60, 160, Math.round(Prefs.longDigitW * 100), v -> {
            Prefs.longDigitW = v / 100f;
            changed.run();
        }, this::save));
        col.addView(slider("Толщина линии", 60, 170, Math.round(Prefs.longStroke * 100), v -> {
            Prefs.longStroke = v / 100f;
            changed.run();
        }, this::save));
        col.addView(swatches("Цвет часов", Prefs.posterFront, v -> {
            Prefs.posterFront = v;
            save();
            changed.run();
        }));
        col.addView(swatches("Цвет минут", Prefs.posterBack, v -> {
            Prefs.posterBack = v;
            save();
            changed.run();
        }));
        return col;
    }

    /** Виды часов плитками: «обычные» и все видов Delta. */
    private View clockStrip(Runnable changed) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        String[] ids = ClockView.STYLES, names = ClockView.STYLE_NAMES;
        ArrayList<View> tiles = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i], nm = names[i];
            View t = new View(this) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas c) {
                    float w = getWidth(), h = getHeight();
                    RectF r = new RectF(Ui.dpf(2), Ui.dpf(2), w - Ui.dpf(2), h - Ui.dpf(22));
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFF2A2B33);
                    c.drawRoundRect(r, Ui.dpf(14), Ui.dpf(14), p);
                    String st = id.isEmpty() ? Look.clock() : id;
                    float k = Math.min(0.34f, (r.height() - Ui.dpf(8)) / Ui.dpf(ClockView.heightDp(st)));
                    c.save();
                    c.clipRect(r);
                    float top = r.centerY() - Ui.dpf(ClockView.heightDp(st)) * k / 2;
                    ClockView.draw(c, getContext(), st, Prefs.lockClockColor, r.centerX(), top, k, true);
                    c.restore();
                    if (id.equals(LockFace.style())) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(Ui.dpf(2.5f));
                        p.setColor(Ui.accent());
                        c.drawRoundRect(r, Ui.dpf(14), Ui.dpf(14), p);
                    }
                    Paint tp = Ui.text(Ui.medium, 12, id.equals(LockFace.style()) ? Ui.accentLight() : SUB);
                    tp.setTextAlign(Paint.Align.CENTER);
                    c.drawText(nm, w / 2, h - Ui.dpf(5), tp);
                }
            };
            t.setOnClickListener(v -> {
                Prefs.lockClock = id;
                save();
                Feel.buzz(v, Feel.TICK);
                for (View o : tiles) o.invalidate();
                changed.run();
            });
            tiles.add(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(104), Ui.dp(100));
            lp.rightMargin = Ui.dp(6);
            row.addView(t, lp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    /**
     * Стопка обоев: посередине — крупная карточка экрана блокировки, по
     * бокам — соседние, меньше и темнее, уходят назад. Листается пальцем с
     * пружиной; нажатие на боковую — к ней, на среднюю — «Применить».
     */
    private final class WallStack extends View {
        final List<Walls.W> walls = Walls.all();
        float pos;
        Runnable changed;
        private final Bitmap[] small;
        private final java.util.HashMap<Integer, Bitmap> sharp = new java.util.HashMap<>();
        private Bitmap peakFg;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final android.graphics.Path clip = new android.graphics.Path();
        private final RectF r = new RectF();
        private float x0, y0, pos0;
        private boolean dragging;
        private int last = -1;
        private android.view.VelocityTracker vt;
        private android.animation.ValueAnimator anim;

        WallStack() {
            super(SettingsActivity.this);
            small = new Bitmap[walls.size()];
            String start = Prefs.lockWallpaper.isEmpty() ? Prefs.wallpaper : Prefs.lockWallpaper;
            for (int i = 0; i < walls.size(); i++) if (walls.get(i).id.equals(start)) pos = i;
            last = Math.round(pos);
        }

        Walls.W current() {
            return walls.get(Math.max(0, Math.min(walls.size() - 1, Math.round(pos))));
        }

        void go(int i) {
            if (anim != null) anim.cancel();
            anim = Anim.spring(pos, Math.max(0, Math.min(walls.size() - 1, i)), 0, 200, 0.85f, this::setPos, null);
        }

        private void setPos(float v) {
            pos = v;
            int i = Math.round(pos);
            if (i != last && i >= 0 && i < walls.size()) {
                last = i;
                Feel.buzz(this, Feel.TICK);
                if (changed != null) changed.run();
            }
            invalidate();
        }

        private Bitmap thumb(int i, boolean big) {
            if (big) {
                Bitmap b = sharp.get(i);
                if (b == null) {
                    if (sharp.size() > 2) sharp.clear();
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inScaled = false;
                    o.inSampleSize = 2;
                    b = android.graphics.BitmapFactory.decodeResource(getResources(), walls.get(i).still, o);
                    sharp.put(i, b);
                }
                return b;
            }
            if (small[i] == null) small[i] = Ui.wallThumb(SettingsActivity.this, walls.get(i));
            return small[i];
        }

        private float cardH() { return getHeight() - Ui.dpf(24); }

        private float cardW() { return cardH() * 0.46f; }

        @Override
        protected void onDraw(Canvas c) {
            float cw = cardW(), ch = cardH(), cx = getWidth() / 2f, cy = getHeight() / 2f;
            // дальние — первыми, средняя — сверху
            Integer[] order = new Integer[walls.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Float.compare(Math.abs(b - pos), Math.abs(a - pos)));
            boolean anim2 = false;
            for (int i : order) {
                float d = i - pos, ad = Math.abs(d);
                if (ad > 3.2f) continue;
                float off = (ad <= 1 ? ad : 1 + (ad - 1) * 0.45f) * cw * 0.58f * Math.signum(d);
                float s = 1 - 0.13f * Math.min(ad, 2.6f);
                c.save();
                c.translate(cx + off, cy);
                c.scale(s, s);
                r.set(-cw / 2, -ch / 2, cw / 2, ch / 2);
                clip.reset();
                clip.addRoundRect(r, Ui.dpf(22), Ui.dpf(22), android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                Bitmap b = thumb(i, ad < 0.6f);
                float k = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                m.setScale(k, k);
                m.postTranslate(-b.getWidth() * k / 2, -b.getHeight() * k / 2);
                c.drawBitmap(b, m, p);
                if (ad < 1.5f) anim2 |= drawLock(c, walls.get(i), r);
                if (ad > 0.01f) c.drawColor(Ui.alpha(0xFF000000, 0.42f * Math.min(ad, 1.4f)));
                c.restore();
                if (ad < 0.5f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dpf(1));
                    p.setColor(0x40FFFFFF);
                    c.drawRoundRect(r, Ui.dpf(22), Ui.dpf(22), p);
                    p.setStyle(Paint.Style.FILL);
                }
                c.restore();
            }
            if (anim2) postInvalidateDelayed(33);
        }

        /** Экран блокировки в карточке: часы, глубина, отпечаток. true — отпечаток живой. */
        private boolean drawLock(Canvas c, Walls.W w, RectF card) {
            float sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
            float k = card.width() / sw;
            c.save();
            c.translate(card.left, card.top);
            c.scale(k, card.height() / sh);
            c.drawColor(0x2E000000);
            LockFace.drawClock(c, getContext(), (int) sw, (int) sh, Ui.dp(32));
            LockFace.drawWidgets(c, getContext(), (int) sw, (int) sh, Ui.dp(32));
            if (Prefs.depth && w.fg != 0) {
                if (peakFg == null) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inScaled = false;
                    o.inSampleSize = 2;
                    peakFg = android.graphics.BitmapFactory.decodeResource(getResources(), w.fg, o);
                }
                c.drawBitmap(peakFg, Ui.wallMatrix(peakFg, (int) sw, (int) sh, 1f, m), p);
            }
            float fy = sh - Ui.dpf(150);
            LockView.fingerEffect(c, sw / 2f, fy, android.os.SystemClock.uptimeMillis(), Ui.accentLight(), Prefs.fpEffect);
            LockFace.drawFinger(c, sw / 2f, fy, Ui.accentLight());
            c.restore();
            return !"none".equals(Prefs.fpEffect);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = android.view.VelocityTracker.obtain();
            vt.addMovement(e);
            float step = cardW() * 0.58f;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (anim != null) anim.cancel();
                    x0 = e.getX();
                    y0 = e.getY();
                    pos0 = pos;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - x0;
                    if (!dragging && Math.abs(dx) > Ui.dpf(8) && Math.abs(dx) > Math.abs(e.getY() - y0)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        float v = pos0 - dx / step;
                        float max = walls.size() - 1;
                        if (v < 0) v *= 0.3f;
                        else if (v > max) v = max + (v - max) * 0.3f;
                        setPos(v);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                    if (dragging) {
                        float target = Math.round(pos - vx / step * 0.18f);
                        target = Math.max(0, Math.min(walls.size() - 1, target));
                        if (anim != null) anim.cancel();
                        anim = Anim.spring(pos, target, -vx / step, 200, 0.86f, this::setPos, null);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        // нажатие: боковая — к ней, средняя — применить
                        float d = (e.getX() - getWidth() / 2f) / step;
                        // средняя — в редактор экрана блокировки с этими обоями
                        if (Math.abs(d) < 0.8f) openLockEditor(current(), () -> {
                            invalidate();
                            if (changed != null) changed.run();
                        });
                        else go(Math.round(pos + Math.signum(d) * (Math.abs(d) < 1.6f ? 1 : 2)));
                    }
                    dragging = false;
                    return true;
                }
            }
            return true;
        }
    }

    // ── редактор экрана блокировки ────────────────────────────────────────

    private LockEditor editor;

    private void openLockEditor(Walls.W w, Runnable done) {
        if (editor != null) return;
        editor = new LockEditor(w, done);
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        decor.addView(editor, new ViewGroup.LayoutParams(-1, -1));
        editor.open();
    }

    /**
     * Редактор экрана блокировки, как в ColorOS и iOS: на чёрном — карточка
     * экрана блокировки, по бокам выглядывают соседние обои (листаются).
     * Вокруг часов и виджетов — тонкие рамки: нажатие открывает снизу лист
     * с настройками, часы можно тащить, а за ручки — менять ширину, высоту
     * и размер. Внизу — круглые кнопки: «Часы», «Виджеты», «Эффекты»,
     * «Глубина».
     */
    private final class LockEditor extends FrameLayout {
        private final List<Walls.W> walls = Walls.all();
        private final Runnable done;
        private float pos;
        private final Card card;
        private final LinearLayout buttons;
        private Sheet sheet;
        private boolean closing, clockActive;
        private final int sw, sh;
        private View depthBtn;
        // что было до правки — для «Отмены»
        private final String oClock = Prefs.lockClock, oColor = Prefs.lockClockColor, oWidgets = Prefs.lockWidgets, oPos = Prefs.lockWidgetsPos;
        private final float oSize = Prefs.lockClockSize, oW = Prefs.lockClockW, oH = Prefs.lockClockH, oX = Prefs.lockClockX, oY = Prefs.lockClockY;

        LockEditor(Walls.W w, Runnable done) {
            super(SettingsActivity.this);
            this.done = done;
            setBackgroundColor(0xFF000000);
            setClickable(true);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            sw = dm.widthPixels;
            sh = Math.max(dm.heightPixels, stack.getHeight());
            for (int i = 0; i < walls.size(); i++) if (walls.get(i).id.equals(w.id)) pos = i;

            card = new Card();
            addView(card, new LayoutParams(-1, -1));

            // сверху: «Отмена» · «Экран блокировки» · «Применить»
            FrameLayout bar = new FrameLayout(SettingsActivity.this);
            bar.setPadding(Ui.dp(14), insetTop() + Ui.dp(6), Ui.dp(14), 0);
            bar.addView(pill("Отмена", false, v -> {
                Prefs.lockClock = oClock;
                Prefs.lockClockColor = oColor;
                Prefs.lockWidgets = oWidgets;
                Prefs.lockWidgetsPos = oPos;
                Prefs.lockClockSize = oSize;
                Prefs.lockClockW = oW;
                Prefs.lockClockH = oH;
                Prefs.lockClockX = oX;
                Prefs.lockClockY = oY;
                save();
                close();
            }), new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
            TextView title = Views.label(SettingsActivity.this, "Экран блокировки", 16, Ui.medium, 0xFFFFFFFF);
            bar.addView(title, new LayoutParams(-2, -2, Gravity.CENTER));
            bar.addView(pill("Применить", true, v -> applyWall(current(), this::close)),
                    new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
            addView(bar, new LayoutParams(-1, insetTop() + Ui.dp(50), Gravity.TOP));

            // снизу: круглые кнопки
            buttons = new LinearLayout(SettingsActivity.this);
            buttons.setPadding(Ui.dp(12), 0, Ui.dp(12), insetBottom() + Ui.dp(10));
            buttons.addView(roundButton("Часы", (c, x, y, s, col) -> {
                Paint q = Ui.text(Ui.bold, s / Ui.density * 0.42f, col);
                q.setTextAlign(Paint.Align.CENTER);
                c.drawText("12", x, y + s * 0.15f, q);
            }, () -> false, () -> openSheet(0)));
            buttons.addView(roundButton("Виджеты", Glyph::grid, () -> false, () -> openSheet(1)));
            buttons.addView(roundButton("Эффекты", (c, x, y, s, col) -> Glyph.fingerprint(c, x, y, s, col, 1f), () -> false, () -> openSheet(2)));
            depthBtn = roundButton("Глубина", Glyph::image, () -> Prefs.depth, () -> {
                Prefs.depth = !Prefs.depth;
                save();
                depthBtn.invalidate();
                card.invalidate();
            });
            buttons.addView(depthBtn);
            addView(buttons, new LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        Walls.W current() {
            return walls.get(Math.max(0, Math.min(walls.size() - 1, Math.round(pos))));
        }

        private int insetTop() {
            return stack.getPaddingTop();
        }

        private int insetBottom() {
            return stack.getPaddingBottom();
        }

        private View pill(String text, boolean accent, View.OnClickListener l) {
            TextView t = Views.label(SettingsActivity.this, text, 14, Ui.medium, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(16), Ui.dp(7), Ui.dp(16), Ui.dp(7));
            t.setBackground(Views.round(accent ? Ui.accent() : 0x38FFFFFF, 18));
            t.setOnClickListener(v -> {
                Feel.buzz(v, Feel.CLICK);
                l.onClick(v);
            });
            return t;
        }

        private View roundButton(String label, Icon icon, java.util.function.BooleanSupplier on, Runnable click) {
            View v = new View(SettingsActivity.this) {
                @Override
                protected void onDraw(Canvas c) {
                    float r = Ui.dpf(27), cx = getWidth() / 2f, cy = Ui.dpf(29);
                    boolean active = on.getAsBoolean();
                    Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                    q.setColor(active ? 0xFFF2F2F7 : 0x2EFFFFFF);
                    c.drawCircle(cx, cy, r, q);
                    if (!active) {
                        q.setStyle(Paint.Style.STROKE);
                        q.setStrokeWidth(Ui.dpf(1));
                        q.setColor(0x26FFFFFF);
                        c.drawCircle(cx, cy, r, q);
                    }
                    icon.draw(c, cx, cy, Ui.dpf(26), active ? 0xFF16171D : 0xFFFFFFFF);
                    Paint t = Ui.text(Ui.medium, 12, 0xE6FFFFFF);
                    t.setTextAlign(Paint.Align.CENTER);
                    c.drawText(label, cx, Ui.dpf(76), t);
                }
            };
            v.setOnClickListener(x -> {
                Feel.buzz(x, Feel.CLICK);
                click.run();
            });
            v.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(84), 1));
            return v;
        }

        // ── листы снизу ───────────────────────────────────────────────────

        private void openSheet(int which) {
            if (sheet != null) {
                if (sheet.which == which) return;
                sheet.close(() -> openSheet(which));
                return;
            }
            clockActive = which == 0;
            sheet = new Sheet(which);
            addView(sheet, new LayoutParams(-1, -2, Gravity.BOTTOM));
            sheet.open();
            card.invalidate();
        }

        /** Лист с настройками: часы, виджеты или эффекты. Карточка над ним уменьшается. */
        private final class Sheet extends LinearLayout {
            final int which;
            private boolean gone;

            Sheet(int which) {
                super(SettingsActivity.this);
                this.which = which;
                setWillNotDraw(false);
                setOrientation(VERTICAL);
                setClickable(true);
                setPadding(0, Ui.dp(14), 0, insetBottom() + Ui.dp(12));
                String[] titles = {"Часы", "Виджеты", "Эффекты"};
                FrameLayout head = new FrameLayout(SettingsActivity.this);
                head.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(14), Ui.dp(6));
                head.addView(Views.label(SettingsActivity.this, titles[which], 18, Ui.bold, 0xFFFFFFFF), new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
                View x = Views.drawn(SettingsActivity.this, (c, w, h) -> {
                    Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                    q.setColor(0x33FFFFFF);
                    c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, q);
                    Glyph.close(c, w / 2, h / 2, Ui.dpf(16), 0xFFFFFFFF);
                });
                x.setOnClickListener(v -> close(null));
                head.addView(x, new FrameLayout.LayoutParams(Ui.dp(30), Ui.dp(30), Gravity.END | Gravity.CENTER_VERTICAL));
                addView(head, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout body = new LinearLayout(SettingsActivity.this);
                body.setOrientation(VERTICAL);
                switch (which) {
                    case 0: {
                        LinearLayout poster = posterBlock(card::invalidate);
                        poster.setVisibility("long".equals(LockFace.style()) ? VISIBLE : GONE);
                        body.addView(clockStrip(() -> {
                            poster.setVisibility("long".equals(LockFace.style()) ? VISIBLE : GONE);
                            card.invalidate();
                        }));
                        body.addView(poster);
                        body.addView(segments(ClockView.TINT_NAMES, Math.max(0, indexOf(ClockView.TINTS, Prefs.lockClockColor)), k -> {
                            Prefs.lockClockColor = ClockView.TINTS[k];
                            save();
                            card.invalidate();
                        }));
                        body.addView(slider("Размер", 60, 150, Math.round(Prefs.lockClockSize * 100), k -> {
                            Prefs.lockClockSize = k / 100f;
                            card.invalidate();
                        }, SettingsActivity.this::save));
                        body.addView(slider("Ширина", 60, 160, Math.round(Prefs.lockClockW * 100), k -> {
                            Prefs.lockClockW = k / 100f;
                            card.invalidate();
                        }, SettingsActivity.this::save));
                        body.addView(slider("Высота", 60, 240, Math.round(Prefs.lockClockH * 100), k -> {
                            Prefs.lockClockH = k / 100f;
                            card.invalidate();
                        }, SettingsActivity.this::save));
                        body.addView(toggleRow(ORANGE, Glyph::grid, "Часы переезжают на главный экран", null, Prefs.clockToHome, on -> {
                            Prefs.clockToHome = on;
                            save();
                        }));
                        break;
                    }
                    case 1: {
                        String[] ids = {"under", "side"};
                        body.addView(segments(new String[]{"Под часами", "Сбоку"}, Math.max(0, indexOf(ids, Prefs.lockWidgetsPos)), k -> {
                            Prefs.lockWidgetsPos = ids[k];
                            save();
                            card.invalidate();
                        }));
                        body.addView(widgetTiles(), new LinearLayout.LayoutParams(-1, Ui.dp(120)));
                        TextView n = Views.label(SettingsActivity.this, "До четырёх виджетов. Нажмите, чтобы добавить или убрать.", 13, Ui.regular, SUB);
                        n.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(4));
                        body.addView(n);
                        break;
                    }
                    default: {
                        TextView fh = Views.label(SettingsActivity.this, "Вид отпечатка", 13, Ui.regular, SUB);
                        fh.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), 0);
                        body.addView(fh);
                        body.addView(segments(LockFace.FP_NAMES, Math.max(0, indexOf(LockFace.FP_IDS, Prefs.fpIcon)), k -> {
                            if (LockFace.FP_IDS[k].equals("photo")) {
                                // своё фото — выбрать в галерее
                                pickFingerPhoto();
                                return;
                            }
                            Prefs.fpIcon = LockFace.FP_IDS[k];
                            save();
                            card.invalidate();
                        }));
                        String[] fpIds = {"pulse", "ripple", "glow", "none"};
                        TextView h = Views.label(SettingsActivity.this, "Сканер отпечатка", 13, Ui.regular, SUB);
                        h.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), 0);
                        body.addView(h);
                        body.addView(segments(new String[]{"Пульс", "Волна", "Свечение", "Нет"}, Math.max(0, indexOf(fpIds, Prefs.fpEffect)), k -> {
                            Prefs.fpEffect = fpIds[k];
                            save();
                            card.invalidate();
                        }));
                        body.addView(toggleRow(BLUE, Glyph::swipe, "Стеклянная волна при входе", null, Prefs.unlockWave, on -> {
                            Prefs.unlockWave = on;
                            save();
                        }));
                        body.addView(toggleRow(INDIGO, Glyph::image, "Анимировать обои", "Живые и супер обои двигаются и на столе", Prefs.wallAnimate, on -> {
                            Prefs.wallAnimate = on;
                            save();
                        }));
                    }
                }
                // лист не выше половины экрана — остальное листается
                android.widget.ScrollView sv = new android.widget.ScrollView(SettingsActivity.this) {
                    @Override
                    protected void onMeasure(int wm, int hm) {
                        super.onMeasure(wm, MeasureSpec.makeMeasureSpec(Math.round(sh * 0.46f), MeasureSpec.AT_MOST));
                    }
                };
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(body);
                addView(sv, new LinearLayout.LayoutParams(-1, -2));
            }

            @Override
            protected void onDraw(Canvas c) {
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setColor(0xF2191A21);
                float rad = Ui.dpf(28);
                c.drawRoundRect(0, 0, getWidth(), getHeight() + rad, rad, rad, q);
                q.setColor(0x40FFFFFF);
                c.drawRoundRect(getWidth() / 2f - Ui.dpf(18), Ui.dpf(6), getWidth() / 2f + Ui.dpf(18), Ui.dpf(10), Ui.dpf(2), Ui.dpf(2), q);
            }

            void open() {
                setTranslationY(sh);
                post(() -> {
                    float h0 = getHeight();
                    Anim.spring(1, 0, 0, 420, 0.9f, t -> {
                        setTranslationY(h0 * t);
                        card.fit(h0 * (1 - t));
                    }, null);
                });
                Feel.sound(SettingsActivity.this, "swoosh");
            }

            void close(Runnable then) {
                if (gone) return;
                gone = true;
                float h0 = getHeight();
                Anim.run(0, 1, 240, Anim.EMPH, t -> {
                    setTranslationY(h0 * t);
                    card.fit(h0 * (1 - t));
                }, () -> {
                    LockEditor.this.removeView(this);
                    if (sheet == this) sheet = null;
                    clockActive = false;
                    card.invalidate();
                    if (then != null) then.run();
                });
            }
        }

        /** Виджеты экрана блокировки: нажатие — добавить или убрать (до четырёх). */
        private View widgetTiles() {
            LinearLayout row = new LinearLayout(SettingsActivity.this);
            row.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), 0);
            ArrayList<View> tiles = new ArrayList<>();
            for (int i = 0; i < LockFace.WIDGETS.length; i++) {
                String id = LockFace.WIDGETS[i], nm = LockFace.WIDGET_NAMES[i];
                View t = new View(SettingsActivity.this) {
                    @Override
                    protected void onDraw(Canvas c) {
                        float s = Ui.dpf(60);
                        RectF r = new RectF(getWidth() / 2f - s / 2, Ui.dpf(6), getWidth() / 2f + s / 2, Ui.dpf(6) + s);
                        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                        q.setColor(0xFF2E3240);
                        c.drawRoundRect(r, s * 0.3f, s * 0.3f, q);
                        LockFace.drawWidget(c, getContext(), id, r);
                        boolean on = LockFace.widgets().contains(id);
                        float bx = r.right - Ui.dpf(2), by = r.top + Ui.dpf(2);
                        q.setColor(on ? Ui.accent() : 0xFF4A4F5E);
                        c.drawCircle(bx, by, Ui.dpf(9), q);
                        if (on) Glyph.close(c, bx, by, Ui.dpf(11), 0xFFFFFFFF);
                        else Glyph.plus(c, bx, by, Ui.dpf(13), 0xFFFFFFFF);
                        Paint tp = Ui.text(Ui.medium, 12, on ? Ui.accentLight() : SUB);
                        tp.setTextAlign(Paint.Align.CENTER);
                        c.drawText(nm, getWidth() / 2f, r.bottom + Ui.dpf(20), tp);
                    }
                };
                t.setOnClickListener(x -> {
                    List<String> l = LockFace.widgets();
                    if (!l.remove(id)) l.add(id);
                    Prefs.lockWidgets = String.join(",", l);
                    save();
                    Feel.buzz(x, Feel.CONFIRM);
                    Feel.sound(SettingsActivity.this, "snap-in");
                    for (View o : tiles) o.invalidate();
                    card.invalidate();
                });
                tiles.add(t);
                row.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(110), 1));
            }
            return row;
        }

        // ── карточки ──────────────────────────────────────────────────────

        /**
         * Карусель экранов блокировки. Посередине — редактируемый, по бокам —
         * соседние обои. Палец по часам — двигает их, по ручкам — меняет
         * размер, по пустому — листает обои.
         */
        private final class Card extends View {
            private final java.util.HashMap<Integer, Bitmap> bmps = new java.util.HashMap<>();
            private final java.util.HashMap<Integer, Bitmap> fgs = new java.util.HashMap<>();
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final android.graphics.Matrix m = new android.graphics.Matrix();
            private final android.graphics.Path clip = new android.graphics.Path();
            private float reserved;          // сколько снизу занял лист
            private int mode;                // 0 — листаем, 1 — часы, 2 — ширина, 3 — высота, 4 — размер, 5 — виджеты
            private float downX, downY, startA, startB, pos0;
            private boolean moved;
            private android.view.VelocityTracker vt;
            private android.animation.ValueAnimator anim;

            Card() {
                super(SettingsActivity.this);
            }

            void fit(float sheetH) {
                reserved = sheetH;
                invalidate();
            }

            /** Где карточка: f — масштаб экрана в ней. */
            private RectF cardRect(RectF out) {
                float top = insetTop() + Ui.dpf(58);
                float bottom = getHeight() - Math.max(insetBottom() + Ui.dpf(100), reserved + Ui.dpf(12));
                float hh = Math.max(Ui.dpf(160), bottom - top);
                float ww = hh * sw / (float) sh;
                if (ww > getWidth() * 0.78f) {
                    ww = getWidth() * 0.78f;
                    hh = ww * sh / (float) sw;
                }
                out.set(getWidth() / 2f - ww / 2, top, getWidth() / 2f + ww / 2, top + hh);
                return out;
            }

            private Bitmap wall(int i) {
                Bitmap b = bmps.get(i);
                if (b == null) {
                    if (bmps.size() > 4) bmps.clear();
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inScaled = false;
                    o.inSampleSize = 2;
                    b = android.graphics.BitmapFactory.decodeResource(getResources(), walls.get(i).still, o);
                    bmps.put(i, b);
                }
                return b;
            }

            private Bitmap fg(int i) {
                Walls.W w = walls.get(i);
                if (w.fg == 0) return null;
                Bitmap b = fgs.get(i);
                if (b == null) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inScaled = false;
                    o.inSampleSize = 2;
                    b = android.graphics.BitmapFactory.decodeResource(getResources(), w.fg, o);
                    fgs.put(i, b);
                }
                return b;
            }

            @Override
            protected void onDraw(Canvas c) {
                RectF cr = cardRect(new RectF());
                float step = cr.width() + Ui.dpf(16);
                for (int i = Math.max(0, (int) Math.floor(pos) - 2); i <= Math.min(walls.size() - 1, (int) Math.ceil(pos) + 2); i++) {
                    float d = i - pos;
                    float s = 1 - 0.08f * Math.min(1, Math.abs(d));
                    c.save();
                    c.translate(d * step, 0);
                    c.scale(s, s, cr.centerX(), cr.centerY());
                    drawScreen(c, i, cr, Math.abs(d) < 0.5f);
                    if (Math.abs(d) > 0.01f) {
                        p.setColor(Ui.alpha(0xFF000000, 0.45f * Math.min(1, Math.abs(d))));
                        c.drawRoundRect(cr, Ui.dpf(30), Ui.dpf(30), p);
                    }
                    c.restore();
                }
            }

            /** Экран блокировки i в карточке r. edit — рамки часов и виджетов. */
            private void drawScreen(Canvas c, int i, RectF r, boolean edit) {
                float f = r.width() / sw;
                c.save();
                clip.reset();
                clip.addRoundRect(r, Ui.dpf(30), Ui.dpf(30), android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.translate(r.left, r.top);
                c.scale(f, f);
                Bitmap b = wall(i);
                float k = Math.max(sw / (float) b.getWidth(), sh / (float) b.getHeight()) * 1.06f;
                m.setScale(k, k);
                m.postTranslate((sw - b.getWidth() * k) / 2f, (sh - b.getHeight() * k) / 2f);
                c.drawBitmap(b, m, p);
                c.drawColor(0x2E000000);
                LockFace.drawClock(c, getContext(), sw, sh, insetTop());
                Bitmap fg = fg(i);
                if (Prefs.depth && fg != null) c.drawBitmap(fg, m, p);
                LockFace.drawWidgets(c, getContext(), sw, sh, insetTop());
                // отпечаток внизу
                float fy = sh - insetBottom() - Ui.dpf(118);
                LockFace.drawFinger(c, sw / 2f, fy, Ui.accentLight());
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1));
                p.setColor(0x33FFFFFF);
                c.drawRoundRect(r, Ui.dpf(30), Ui.dpf(30), p);
                p.setStyle(Paint.Style.FILL);
                if (edit) frames(c, r, f);
            }

            /** Тонкие рамки вокруг часов и виджетов (в координатах карточки). */
            private void frames(Canvas c, RectF r, float f) {
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(Ui.dpf(1.4f));
                RectF clock = toCard(LockFace.clockRect(sw, sh, insetTop()), r, f);
                clock.inset(-Ui.dpf(6), -Ui.dpf(4));
                q.setColor(clockActive || mode >= 1 && mode <= 4 ? 0xE6FFFFFF : 0x80FFFFFF);
                c.drawRoundRect(clock, Ui.dpf(12), Ui.dpf(12), q);
                RectF zone = toCard(LockFace.widgetZone(sw, sh, insetTop()), r, f);
                q.setColor(0x66FFFFFF);
                c.drawRoundRect(zone, Ui.dpf(12), Ui.dpf(12), q);
                // пустые места виджетов — плюсики
                int n = LockFace.widgets().size();
                if (n < 4) {
                    q.setStyle(Paint.Style.FILL);
                    float s = Ui.dpf(64) * f, gap = Ui.dpf(12) * f;
                    for (int k = n; k < 4; k++) {
                        float cx, cy;
                        if ("side".equals(Prefs.lockWidgetsPos)) {
                            cx = zone.centerX();
                            cy = zone.top + Ui.dpf(6) * f + s / 2 + k * (s + gap);
                        } else {
                            cx = zone.left + Ui.dpf(6) * f + s / 2 + k * (s + gap);
                            cy = zone.centerY();
                        }
                        q.setColor(0x26FFFFFF);
                        c.drawCircle(cx, cy, s * 0.32f, q);
                        Glyph.plus(c, cx, cy, s * 0.4f, 0xCCFFFFFF);
                    }
                }
                if (clockActive || mode >= 1 && mode <= 4) {
                    q.setStyle(Paint.Style.FILL);
                    handle(c, q, clock.right, clock.centerY(), false);
                    handle(c, q, clock.centerX(), clock.bottom, false);
                    handle(c, q, clock.right, clock.bottom, true);
                }
            }

            private void handle(Canvas c, Paint q, float x, float y, boolean corner) {
                q.setColor(0x55000000);
                c.drawCircle(x, y + Ui.dpf(1), Ui.dpf(corner ? 11 : 7.5f), q);
                q.setColor(corner ? Ui.accent() : 0xFFFFFFFF);
                c.drawCircle(x, y, Ui.dpf(corner ? 10 : 6.5f), q);
                if (corner) Glyph.resize(c, x, y, Ui.dpf(12), 0xFFFFFFFF);
            }

            private RectF toCard(RectF s, RectF r, float f) {
                return new RectF(r.left + s.left * f, r.top + s.top * f, r.left + s.right * f, r.top + s.bottom * f);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                RectF cr = cardRect(new RectF());
                float f = cr.width() / sw;
                if (vt == null) vt = android.view.VelocityTracker.obtain();
                vt.addMovement(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (anim != null) anim.cancel();
                        downX = e.getX();
                        downY = e.getY();
                        pos0 = pos;
                        moved = false;
                        mode = 0;
                        RectF clock = toCard(LockFace.clockRect(sw, sh, insetTop()), cr, f);
                        clock.inset(-Ui.dpf(6), -Ui.dpf(4));
                        float hr = Ui.dpf(24);
                        if (clockActive && Math.hypot(downX - clock.right, downY - clock.bottom) < hr) {
                            mode = 4;
                            startA = Prefs.lockClockSize;
                        } else if (clockActive && Math.hypot(downX - clock.right, downY - clock.centerY()) < hr) {
                            mode = 2;
                            startA = Prefs.lockClockW;
                        } else if (clockActive && Math.hypot(downX - clock.centerX(), downY - clock.bottom) < hr) {
                            mode = 3;
                            startA = Prefs.lockClockH;
                        } else if (clock.contains(downX, downY)) {
                            mode = 1;
                            startA = Prefs.lockClockX;
                            startB = Prefs.lockClockY;
                        } else if (toCard(LockFace.widgetZone(sw, sh, insetTop()), cr, f).contains(downX, downY)) {
                            mode = 5;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!moved && Math.hypot(dx, dy) > Ui.dpf(8)) moved = true;
                        if (!moved) return true;
                        float rw = cr.width(), rh = cr.height();
                        switch (mode) {
                            case 1:
                                Prefs.lockClockX = Math.max(0.15f, Math.min(0.85f, startA + dx / rw));
                                Prefs.lockClockY = Math.max(0f, Math.min(0.45f, startB + dy / rh));
                                if (Math.abs(Prefs.lockClockX - 0.5f) < 0.025f) Prefs.lockClockX = 0.5f;
                                clockActive = true;
                                break;
                            case 2:
                                Prefs.lockClockW = Math.max(0.6f, Math.min(1.6f, startA + 2 * dx / rw));
                                break;
                            case 3:
                                Prefs.lockClockH = Math.max(0.6f, Math.min(2.4f, startA + 3f * dy / rh));
                                break;
                            case 4:
                                Prefs.lockClockSize = Math.max(0.6f, Math.min(1.5f, startA + (dx + dy) / (rw * 0.8f)));
                                break;
                            case 0:
                            case 5: {
                                if (sheet != null) break;
                                mode = 0;
                                float v = pos0 - dx / (cr.width() + Ui.dpf(16));
                                float max = walls.size() - 1;
                                if (v < 0) v *= 0.3f;
                                else if (v > max) v = max + (v - max) * 0.3f;
                                pos = v;
                                break;
                            }
                        }
                        invalidate();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        vt.computeCurrentVelocity(1000);
                        float vx = vt.getXVelocity();
                        vt.recycle();
                        vt = null;
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        if (moved && mode >= 1 && mode <= 4) {
                            save();
                            Feel.buzz(this, Feel.TICK);
                        } else if (!moved && up) {
                            if (mode >= 1 && mode <= 4) openSheet(0);
                            else if (mode == 5) openSheet(1);
                            else if (sheet != null) sheet.close(null);
                            else {
                                // нажали на соседнюю карточку — к ней
                                float d = (e.getX() - getWidth() / 2f) / (cr.width() + Ui.dpf(16));
                                if (Math.abs(d) > 0.5f) go(Math.round(pos + Math.signum(d)));
                            }
                        }
                        if (mode == 0 && moved) {
                            float step = cr.width() + Ui.dpf(16);
                            float target = Math.max(0, Math.min(walls.size() - 1, Math.round(pos - vx / step * 0.15f)));
                            if (Math.round(target) != Math.round(pos0)) Feel.buzz(this, Feel.TICK);
                            go((int) target);
                        }
                        mode = 0;
                        invalidate();
                        return true;
                    }
                }
                return true;
            }

            void go(int i) {
                if (anim != null) anim.cancel();
                anim = Anim.spring(pos, Math.max(0, Math.min(walls.size() - 1, i)), 0, 300, 0.86f, v -> {
                    pos = v;
                    invalidate();
                }, null);
            }
        }

        void open() {
            setAlpha(0);
            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            animate().alpha(1).setDuration(220).start();
            card.animate().scaleX(1).scaleY(1).setDuration(380).setInterpolator(Anim.EMPH).start();
            Feel.sound(SettingsActivity.this, "swoosh");
        }

        void back() {
            if (sheet != null) sheet.close(null);
            else close();
        }

        void invalidateAll() {
            card.invalidate();
        }

        void close() {
            if (closing) return;
            closing = true;
            card.animate().scaleX(0.92f).scaleY(0.92f).setDuration(240).setInterpolator(Anim.EMPH).start();
            animate().alpha(0).setDuration(240).withEndAction(() -> {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
                editor = null;
                if (done != null) done.run();
            }).start();
        }
    }

    // ── боковая панель ────────────────────────────────────────────────────

    private View edgeRowRef, lockMainRef, pinRow;

    /** Указать PIN-код Android (дважды, чтобы не ошибиться). */
    private void askPin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(22), Ui.dp(8), Ui.dp(22), 0);
        EditText a = new EditText(this), b = new EditText(this);
        for (EditText e : new EditText[]{a, b}) {
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            e.setTextColor(0xFFFFFFFF);
            e.setHintTextColor(SUB);
            box.addView(e);
        }
        a.setHint("PIN-код Android");
        b.setHint("Ещё раз");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("PIN-код")
                .setMessage("Тот же, что стоит в Android для экрана блокировки.")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String p = a.getText().toString();
                    if (p.length() < 4 || !p.equals(b.getText().toString())) {
                        toast("PIN-коды не совпали или короче 4 цифр");
                        return;
                    }
                    try {
                        Secret.set(this, p);
                        setValue(pinRow, "Задан");
                        toast("PIN-код сохранён");
                    } catch (Exception e) {
                        toast("Не удалось сохранить: " + e.getMessage());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private View edgePage() {
        View[] out = new View[1];
        LinearLayout col = page("Боковая панель", "Настройки", out);
        Group on = group(col, null, "Задержите палец у края экрана — там, где жест «назад», — и выедет панель. Свайп от края по-прежнему «назад». Работает поверх всех приложений.");
        LinearLayout tr = toggleRow(TEAL, Glyph::grid, "Боковая панель", null, Prefs.edge && Settings.canDrawOverlays(this), v -> {
            if (v && !Settings.canDrawOverlays(this)) {
                toast("Разрешите Vanks «Поверх других приложений», потом включите панель");
                open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                setToggle(edgeRowRef, false);
                return;
            }
            Prefs.edge = v;
            save();
            EdgeService.sync(this);
        });
        edgeRowRef = tr;
        add(on, tr);
        Group side = group(col, "Край", null);
        add(side, segments(new String[]{"Справа", "Слева"}, Prefs.edgeLeft ? 1 : 0, i -> {
            Prefs.edgeLeft = i == 1;
            save();
            EdgeService.sync(this);
        }));

        boolean ff = Windows.supported(this);
        Group w = group(col, "Плавающие окна", ff ? "Телефон умеет окна: приложение откроется окном посреди экрана. Удержание значка в панели — наоборот."
                : "Сейчас телефон окна не включил. " + Windows.HOW + ". Плавающие окна самой прошивки другим лаунчерам недоступны.");
        add(w, toggleRow(INDIGO, Glyph::phone, "Открывать в окне", null, Prefs.edgeWindow, v -> {
            Prefs.edgeWindow = v;
            save();
        }));

        Group list = group(col, "Приложения", "Не больше " + EdgeService.MAX + ". Пока ничего не выбрано, в панели — док и первые значки.");
        TextView count = Views.label(this, "", 13, Ui.regular, SUB);
        count.setPadding(Ui.dp(36), Ui.dp(4), Ui.dp(20), 0);
        Runnable recount = () -> count.setText("Выбрано " + Prefs.edgeApps.size() + " из " + EdgeService.MAX);
        recount.run();
        col.addView(count, col.indexOfChild(list.box));
        int px = Ui.dp(30);
        for (Apps.App a : Apps.visible()) {
            LinearLayout r = row(0, null, a.label, null, null, null);
            android.graphics.Bitmap ic = a.bmp != null ? a.bmp : Apps.render(this, a, px);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(ic);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px, px);
            ip.rightMargin = Ui.dp(14);
            r.addView(iv, 0, ip);
            r.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            View check = Views.drawn(this, (c, cw, ch) -> {
                boolean sel = Prefs.edgeApps.contains(a.key);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                float rr = Ui.dpf(11);
                if (sel) {
                    p.setColor(Ui.accent());
                    c.drawCircle(cw / 2, ch / 2, rr, p);
                    p.setColor(0xFFFFFFFF);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dpf(2));
                    p.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(cw / 2 - Ui.dpf(5), ch / 2, cw / 2 - Ui.dpf(1), ch / 2 + Ui.dpf(4), p);
                    c.drawLine(cw / 2 - Ui.dpf(1), ch / 2 + Ui.dpf(4), cw / 2 + Ui.dpf(6), ch / 2 - Ui.dpf(4), p);
                } else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dpf(1.5f));
                    p.setColor(0x66FFFFFF);
                    c.drawCircle(cw / 2, ch / 2, rr, p);
                }
            });
            r.addView(check, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
            r.setOnClickListener(v -> {
                if (Prefs.edgeApps.contains(a.key)) Prefs.edgeApps.remove(a.key);
                else if (Prefs.edgeApps.size() >= EdgeService.MAX) {
                    toast("В панели не больше " + EdgeService.MAX + " приложений");
                    return;
                } else Prefs.edgeApps.add(a.key);
                save();
                check.invalidate();
                recount.run();
            });
            add(list, r);
        }
        return out[0];
    }

    // ── звуки и отклик ────────────────────────────────────────────────────

    private View feelPage() {
        View[] out = new View[1];
        LinearLayout col = page("Звуки и отклик", "Настройки", out);
        Group g = group(col, null, "Мягкие тона Delta, как на компьютере: вход, волна, открытие приложений, панели. На беззвучном и вибрации звуков нет.");
        add(g, toggleRow(PINK, Glyph::speaker, "Звуки Vanks", null, Prefs.sounds, v -> {
            Prefs.sounds = v;
            save();
            if (v) Feel.sound(this, "pop");
        }));
        LinearLayout sl = new LinearLayout(this);
        sl.setGravity(Gravity.CENTER_VERTICAL);
        sl.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
        sl.addView(Views.label(this, "Громкость", 16, Ui.regular, 0xFFFFFFFF));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(Prefs.soundVolume * 100));
        sb.setProgressTintList(ColorStateList.valueOf(Ui.accent()));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {}
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                Prefs.soundVolume = s.getProgress() / 100f;
                save();
                Feel.sound(SettingsActivity.this, "tick");
            }
        });
        sl.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(44), 1));
        add(g, sl);
        Group h = group(col, null, "Мягкие щелчки при нажатии, листании, входе. Где телефон умеет — тонкая вибрация Android 11+.");
        add(h, toggleRow(ORANGE, Glyph::phone, "Вибрация", null, Prefs.haptics, v -> {
            Prefs.haptics = v;
            save();
            if (v) Feel.buzz(col, Feel.CONFIRM);
        }));
        Group t = group(col, "Послушать", null);
        String[][] demo = {{"unlock", "Вход"}, {"glass-wave", "Волна"}, {"app-open", "Открытие приложения"}, {"swoosh", "Панель"}, {"snap-in", "Боковая панель"}};
        for (String[] d : demo) add(t, row(0, null, d[1], null, null, () -> Feel.sound(this, d[0])));
        return out[0];
    }

    // ── главный экран ─────────────────────────────────────────────────────

    private View homePage() {
        View[] out = new View[1];
        LinearLayout col = page("Главный экран", "Настройки", out);
        Group cols = group(col, "Сетка значков", null);
        add(cols, segments(new String[]{"4 колонки", "5 колонок"}, Prefs.columns >= 5 ? 1 : 0, i -> {
            Prefs.columns = i == 1 ? 5 : 4;
            save();
        }));
        add(cols, toggleRow(INDIGO, Glyph::grid, "Подписи под значками", null, Prefs.labels, on -> {
            Prefs.labels = on;
            save();
        }));
        Group anim = group(col, "Анимации", "Открытие приложений, папок, меню, вход и пружины стола. Меньше — плавнее и дольше, больше — быстрее.");
        TextView speedVal = Views.label(this, speedName(Prefs.animSpeed), 15, Ui.regular, SUB);
        LinearLayout sp = slider("Скорость", 50, 200, Math.round(Prefs.animSpeed * 100), v -> {
            Prefs.animSpeed = Math.round(v / 25f) * 0.25f;
            speedVal.setText(speedName(Prefs.animSpeed));
        }, () -> save());
        sp.addView(speedVal);
        add(anim, sp);
        Group apps = group(col, "Значки", "Зажмите значок — меню-капля; потяните — правка стола: значки и виджеты двигаются, значок на значок — папка. Зажмите пустое место — виджеты и обои. Скрытые приложения открываются по отпечатку.");
        add(apps, row(GREY, Glyph::grid, "На главном экране", null, String.valueOf(Prefs.home.size()), null));
        add(apps, row(GREY, Glyph::phone, "В доке", null, String.valueOf(Prefs.dock.size()), null));
        add(apps, row(GREY, (c, x, y, s, cl) -> Glyph.lock(c, x, y, s, cl, false), "Скрыто", null, String.valueOf(Prefs.hidden.size()), null));
        Group reset = group(col, null, null);
        LinearLayout r = row(0, null, "Вернуть значки как при первом запуске", null, null, null);
        ((TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(0)).setTextColor(RED);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        r.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Вернуть значки?")
                .setMessage("Главный экран и док станут такими, как при первом запуске. Скрытые приложения останутся скрытыми.")
                .setPositiveButton("Вернуть", (d, w) -> {
                    Apps.pickDefaults(this);
                    // стол — заново из списка: часы и значки по порядку
                    Layout.items.clear();
                    Prefs.raw().edit().remove("desk").apply();
                    Layout.load(this);
                    Layout.sync();
                    Layout.save();
                    toast("Готово");
                })
                .setNegativeButton("Отмена", null)
                .show());
        add(reset, r);
        return out[0];
    }

    private static String speedName(float k) {
        if (k < 0.9f) return "×" + String.format(Locale.US, "%.2f", k).replaceAll("0$", "") + " · плавнее";
        if (k > 1.1f) return "×" + String.format(Locale.US, "%.2f", k).replaceAll("0$", "") + " · быстрее";
        return "Обычная";
    }

    // ── пункт управления ──────────────────────────────────────────────────

    private View controlPage() {
        View[] out = new View[1];
        LinearLayout col = page("Пункт управления", "Настройки", out);
        TextView how = Views.label(this, "Откройте: проведите вниз от правого верхнего угла. Порядок и размер плиток меняются прямо там — долгое нажатие на плитку, потом потяните уголок.",
                14, Ui.regular, SUB);
        how.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(4));
        col.addView(how);
        Group g = group(col, "Элементы", null);
        ArrayList<View> rows = new ArrayList<>();
        for (String t : TileGrid.TYPES) {
            LinearLayout r = toggleRow(tileColor(t), tileIcon(t), TileGrid.title(t), null, tilesHave(t), on -> setTile(t, on));
            rows.add(r);
            add(g, r);
        }
        Group reset = group(col, null, null);
        LinearLayout r = row(0, null, "Вернуть плитки по умолчанию", null, null, null);
        ((TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(0)).setTextColor(RED);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        r.setOnClickListener(v -> {
            Prefs.tiles = Prefs.DEFAULT_TILES;
            save();
            for (int i = 0; i < rows.size(); i++) setToggle(rows.get(i), tilesHave(TileGrid.TYPES[i]));
            toast("Готово");
        });
        add(reset, r);
        return out[0];
    }

    private static int tileColor(String t) {
        switch (t) {
            case "wifi": case "bt": return BLUE;
            case "airplane": case "alarm": case "timer": return ORANGE;
            case "dnd": return INDIGO;
            case "battery": return GREEN;
            case "media": return PINK;
            case "camera": case "calc": case "settings": return GREY;
        }
        return 0xFF48484A;
    }

    private static Icon tileIcon(String t) {
        switch (t) {
            case "wifi": return Glyph::wifi;
            case "bt": return Glyph::bluetooth;
            case "media": return (c, x, y, s, cl) -> Glyph.play(c, x, y, s * 0.8f, cl, false);
            case "torch": return (c, x, y, s, cl) -> Glyph.torch(c, x, y, s, cl, false);
            case "dnd": return (c, x, y, s, cl) -> Glyph.moon(c, x, y, s, cl, true);
            case "airplane": return Glyph::plane;
            case "rotate": return Glyph::rotate;
            case "bright": return Glyph::sun;
            case "volume": return Glyph::speaker;
            case "camera": return Glyph::camera;
            case "calc": return Glyph::calc;
            case "alarm": case "timer": return Glyph::alarm;
            case "battery": return (c, x, y, s, cl) -> Glyph.battery(c, x, y, s, cl, 0.8f);
        }
        return Glyph::gear;
    }

    private static boolean tilesHave(String type) {
        for (String part : Prefs.tiles.split(",")) if (part.startsWith(type + ":")) return true;
        return false;
    }

    private void setTile(String type, boolean on) {
        ArrayList<String> parts = new ArrayList<>(Arrays.asList(Prefs.tiles.split(",")));
        parts.removeIf(p -> p.isEmpty() || p.startsWith(type + ":"));
        if (on) {
            int[] s = TileGrid.sizes(type)[0];
            parts.add(type + ":" + s[0] + ":" + s[1]);
        }
        Prefs.tiles = String.join(",", parts);
        save();
    }

    // ── жесты ─────────────────────────────────────────────────────────────

    private View islandPage() {
        View[] out = new View[1];
        LinearLayout col = page("Динамический остров", "Настройки", out);
        boolean svc = VanksAccess.running(), notes = VanksNotes.allowed(this);
        Group st = group(col, null, "Капсула у камеры: громкость, такси и доставка, музыка, голосовые и кружки в Telegram, сканирование пальца. "
                + "Касание — раскрыть, ещё раз — открыть приложение. Свайп влево — убрать.");
        add(st, toggleRow(INDIGO, (c, x, y, s, col2) -> Glyph.play(c, x, y, s, col2, true), "Динамический остров", null, Prefs.island, on -> {
            Prefs.island = on;
            save();
            Island.refresh();
        }));
        Group need = group(col, "Что нужно", "Остров рисует служба «Vanks: жесты и вход», а такси, доставку и музыку он узнаёт из уведомлений. Если Android не даёт включить: «О приложении» → ⋮ → «Разрешить ограниченные настройки».");
        add(need, row(svc ? GREEN : ORANGE, Glyph::swipe, "Служба «Vanks: жесты и вход»", svc ? null : "Нажмите, чтобы включить",
                svc ? "Вкл." : "Выкл.", () -> {
                    if (VanksAccess.turnOn(this)) toast("Служба «Vanks: жесты и вход» включена");
                }));
        add(need, row(notes ? GREEN : ORANGE, Glyph::bell, "Доступ к уведомлениям", notes ? null : "«Vanks: динамический остров»",
                notes ? "Вкл." : "Выкл.", () -> {
                    if (VanksNotes.turnOn(this)) toast("Доступ к уведомлениям включён");
                }));
        Group what = group(col, "Показывать", null);
        add(what, toggleRow(PINK, Glyph::speaker, "Громкость", null, Prefs.islandVolume, on -> {
            Prefs.islandVolume = on;
            save();
        }));
        add(what, toggleRow(GREEN, Glyph::bell, "Такси и доставка", "Яндекс Go, Uber, Самокат, Ozon и другие", Prefs.islandRides, on -> {
            Prefs.islandRides = on;
            save();
        }));
        add(what, toggleRow(BLUE, (c, x, y, s, col2) -> Glyph.play(c, x, y, s, col2, true), "Музыка, голосовые и кружки", null, Prefs.islandMedia, on -> {
            Prefs.islandMedia = on;
            save();
        }));
        return out[0];
    }

    private View gesturePage() {
        View[] out = new View[1];
        LinearLayout col = page("Жесты", "Настройки", out);

        // жесты Android вместо трёх кнопок
        boolean can = Nav.canWrite(this);
        if (can) Nav.keep(this);
        boolean gest = Nav.gestures(this);
        Group nav = group(col, "Навигация Android", can
                ? "Разрешение есть: Vanks сам возвращает жесты, если система поставит кнопки."
                : "HyperOS и MIUI ставят кнопки, когда лаунчер по умолчанию не их. Вернуть жесты можно один раз с компьютера: "
                        + "включите на телефоне «Отладку по USB», подключите его и выполните команду ниже (она выдаёт Vanks разрешение), потом снова откройте этот экран.");
        add(nav, row(gest ? GREEN : ORANGE, Glyph::swipe, "Сейчас", null, gest ? "Жесты" : "Кнопки", null));
        add(nav, toggleRow(INDIGO, Glyph::swipe, "Жесты вместо кнопок", can ? null : "Нужна команда ADB", Prefs.keepGestures, on -> {
            Prefs.keepGestures = on;
            save();
            if (!Nav.canWrite(this)) {
                if (on) toast("Сначала выполните команду ADB ниже");
                return;
            }
            if (on) Nav.keep(this); else Nav.release(this);
            toast(on ? "Жесты включены" : "Как решит система");
        }));
        if (!can) {
            add(nav, row(GREY, Glyph::info, "Скопировать команду", Nav.GRANT, null, () -> copy(Nav.GRANT)));
            add(nav, row(GREY, Glyph::info, "Или сразу включить жесты", Nav.DIRECT, null, () -> copy(Nav.DIRECT)));
        }

        // свои жесты навигации
        boolean svc = VanksAccess.running();
        Group vg = group(col, "Жесты Vanks", "На HyperOS жесты Android обрабатывает её собственный лаунчер, поэтому с Vanks они не срабатывают, даже когда кнопки спрятаны. Здесь свои: свайп вверх от нижнего края — «Домой», "
                + "вверх и задержать — «Недавние», от левого или правого края внутрь — «Назад». Работают через службу специальных возможностей «Vanks: жесты и вход». "
                + "Если Android не даёт её включить: «О приложении» → ⋮ → «Разрешить ограниченные настройки».");
        add(vg, row(svc ? GREEN : ORANGE, Glyph::swipe, "Служба «Vanks: жесты и вход»", svc ? null : "Нажмите, чтобы включить",
                svc ? "Вкл." : "Выкл.", () -> {
                    if (VanksAccess.turnOn(this)) toast("Служба «Vanks: жесты и вход» включена");
                }));
        if (!svc) add(vg, row(GREY, Glyph::info, "О приложении", "Разрешить ограниченные настройки", null, () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)))));
        add(vg, toggleRow(INDIGO, Glyph::swipe, "Жесты Vanks", svc ? null : "Включение откроет службу в настройках Android", Prefs.navGestures, on -> {
            Prefs.navGestures = on;
            save();
            VanksAccess.sync();
            // служба выключена — без неё жестов нет: сразу туда, где её включают
            if (on && !VanksAccess.running() && VanksAccess.turnOn(this)) toast("Жесты Vanks включены");
        }));
        add(vg, toggleRow(BLUE, Glyph::swipe, "«Назад» с боковых краёв", null, Prefs.navBack, on -> {
            Prefs.navBack = on;
            save();
            VanksAccess.sync();
        }));
        add(vg, toggleRow(ORANGE, Glyph::swipe, "Окно за пальцем", "Свайп вверх: приложение уменьшается и улетает в свой значок", Prefs.liveClose, on -> {
            Prefs.liveClose = on;
            save();
        }));
        add(vg, toggleRow(INDIGO, Glyph::grid, "Свои «Недавние»", "Вверх и задержать: карточки приложений со снимками окна", Prefs.ownRecents, on -> {
            Prefs.ownRecents = on;
            save();
        }));
        add(vg, toggleRow(GREY, Glyph::swipe, "Полоска внизу", null, Prefs.navPill, on -> {
            Prefs.navPill = on;
            save();
            VanksAccess.sync();
        }));

        // свайпы
        Group sw = group(col, "Свайпы", "Чувствительность: правее — жест срабатывает от короткого движения, левее — нужен длинный и случайно не сработает.");
        add(sw, slider("Чувствительность", 60, 160, Math.round(Prefs.gestSens * 100), v -> Prefs.gestSens = v / 100f, this::save));
        int tz = Prefs.gestTop < 0.29f ? 0 : Prefs.gestTop > 0.4f ? 2 : 1;
        Group top = group(col, "Сверху вниз", "Откуда тянуть пункт управления и уведомления: верхняя часть экрана такой высоты.");
        add(top, segments(new String[]{"Узко", "Обычно", "Широко"}, tz, i -> {
            Prefs.gestTop = i == 0 ? 0.25f : i == 2 ? 0.45f : 0.33f;
            save();
        }));
        Group side = group(col, "Пункт управления", "С другой стороны сверху — уведомления.");
        add(side, segments(new String[]{"Справа", "Слева"}, Prefs.ctrlRight ? 0 : 1, i -> {
            Prefs.ctrlRight = i == 0;
            save();
        }));
        Group o = group(col, null, "Если выключить, свайп вниз посередине ничего не делает — поиск остаётся под кнопкой «Поиск» над доком.");
        add(o, toggleRow(BLUE, Glyph::search, "Свайп вниз — поиск", null, Prefs.swipeSearch, on -> {
            Prefs.swipeSearch = on;
            save();
        }));

        String c = Prefs.ctrlRight ? "правого" : "левого", n = Prefs.ctrlRight ? "левого" : "правого";
        Group g = group(col, "Что где", null);
        add(g, row(GREY, Glyph::sliders, "Пункт управления", "Вниз от " + c + " верхнего угла", null, null));
        add(g, row(RED, Glyph::bell, "Уведомления", "Вниз от " + n + " верхнего угла", null, null));
        add(g, row(BLUE, Glyph::search, "Поиск", "Вниз посередине экрана", null, null));
        add(g, row(INDIGO, Glyph::grid, "Рабочие столы", "Влево и вправо", null, null));
        add(g, row(TEAL, Glyph::grid, "Боковая панель", "Задержать палец у края экрана (включается отдельно)", null, null));
        add(g, row(GREY, Glyph::gear, "Меню стола", "Долгое нажатие на пустом месте", null, null));
        add(g, row(ORANGE, Glyph::swipe, "Закрыть панель", "Вверх или «назад»", null, null));
        Group h = group(col, null, null);
        add(h, row(ORANGE, Glyph::info, "Показать подсказку снова", null, null, () -> {
            Prefs.swipeHintSeen = false;
            save();
            toast("Подсказка появится на главном экране");
        }));
        return out[0];
    }

    // ── экран и яркость ───────────────────────────────────────────────────

    private View displayPage() {
        View[] out = new View[1];
        LinearLayout col = page("Экран и яркость", "Настройки", out);
        Group hz = group(col, "Частота обновления", "Для окон Vanks: стол, экран блокировки, «Настройки». Экран умеет: "
                + Hz.supported(getWindowManager().getDefaultDisplay()) + ". Выше — плавнее, ниже — дольше держит батарея.");
        int hi = 0;
        for (int i = 0; i < Hz.CHOICES.length; i++) if (Hz.CHOICES[i] == Prefs.hz) hi = i;
        add(hz, segments(Hz.NAMES, hi, i -> {
            Prefs.hz = Hz.CHOICES[i];
            save();
            Hz.apply(this);
        }));
        boolean can = Settings.System.canWrite(this);
        Group br = group(col, "Яркость", can ? null : "Чтобы менять яркость отсюда, разрешите Vanks «Изменение системных настроек».");
        if (can) {
            int cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            add(br, slider("Яркость", 1, 255, cur, v -> {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
            }, () -> {}));
            boolean auto = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            add(br, toggleRow(ORANGE, Glyph::sun, "Автояркость", null, auto, on -> Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)));
        } else {
            add(br, row(ORANGE, Glyph::sun, "Разрешить", null, null, () -> open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())))));
        }
        Group an = group(col, null, null);
        add(an, row(GREY, Glyph::gear, "Все настройки экрана Android", "Тёмная тема, шрифт, разрешение", null, () -> open(Settings.ACTION_DISPLAY_SETTINGS)));
        return out[0];
    }

    private void copy(String text) {
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", text));
        toast("Команда скопирована");
    }

    // ── отпечаток ─────────────────────────────────────────────────────────

    private View lockPage() {
        View[] out = new View[1];
        LinearLayout col = page("Отпечаток и вход", "Настройки", out);
        Group g = group(col, null, "Экран входа появляется после выключения экрана. Скрытые приложения в библиотеке тоже открываются по отпечатку.");
        add(g, toggleRow(RED, (c, x, y, s, cl) -> Glyph.fingerprint(c, x, y, s, cl, 1f), "Вход по отпечатку", null, Prefs.fingerprint, on -> {
            Prefs.fingerprint = on;
            save();
        }));

        Group m = group(col, "Основной экран блокировки",
                "Отдельный экран, не часть стола: открывается, когда гаснет экран, поверх всех приложений и поверх системного. "
                        + "Работает, даже если лаунчер другой. Палец читается без системного окна. "
                        + "Если Android заперт PIN-кодом, открыть его сам экран Vanks не может — это делает Android: "
                        + "лицом или пальцем под картинкой экрана Vanks (раздел «Лицо») или PIN-кодом, который набирает служба (раздел «Пароль»).");
        LinearLayout mr = toggleRow(INDIGO, (c, x, y, s, cl) -> Glyph.lock(c, x, y, s, cl, false), "Сделать основным", null,
                Prefs.lockMain && Settings.canDrawOverlays(this), v -> {
            if (v && !Settings.canDrawOverlays(this)) {
                toast("Разрешите Vanks «Поверх других приложений», потом включите снова");
                open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                setToggle(lockMainRef, false);
                return;
            }
            Prefs.lockMain = v;
            save();
            LockService.sync(this);
            if (v) toast("Готово: выключите экран — появится экран Vanks");
        });
        lockMainRef = mr;
        add(m, mr);
        add(m, row(GREEN, (c, x, y, s, cl) -> Glyph.lock(c, x, y, s, cl, false), "Показать сейчас", null, null, () -> {
            if (!Prefs.lockMain) {
                toast("Сначала включите «Сделать основным»");
                return;
            }
            LockActivity.show(this);
        }));

        float[] sensor = FingerSensor.locate(this);
        Group pos = group(col, "Где значок отпечатка", sensor != null
                ? "Телефон сообщил, где под экраном датчик, — значок встаёт прямо над ним."
                : "Телефон не сообщил, где датчик. Выключите «Над датчиком» и поставьте значок ползунком туда, куда прикладываете палец.");
        add(pos, toggleRow(TEAL, (c, x, y, s, cl) -> Glyph.fingerprint(c, x, y, s, cl, 1f), "Над датчиком (сам)",
                sensor != null ? "Датчик найден" : "Датчик не найден", Prefs.fpAuto, v -> {
            Prefs.fpAuto = v;
            save();
        }));
        int dh = getResources().getDisplayMetrics().heightPixels;
        int cur = Prefs.fpY > 0 ? Math.round(Prefs.fpY * 100) : Math.round(100f * (Ui.dpf(118) + Ui.dpf(24)) / Math.max(1, dh));
        add(pos, slider("Высота значка вручную", 5, 45, cur, v -> {
            Prefs.fpY = v / 100f;
        }, this::save));

        // пароль: для других людей и чтобы система не спрашивала второй раз
        boolean hasPin = Secret.has(this), svc = VanksAccess.running();
        Group pw = group(col, "Пароль", "Укажите тот же PIN-код, что стоит в Android. Тогда другой человек войдёт паролем прямо на экране Vanks (двойное касание экрана), "
                + "а после вашего пальца служба «Vanks: жесты и вход» сама наберёт этот PIN на системном экране — второй раз он не спросит. "
                + "PIN хранится на этом телефоне, зашифрованный ключом Android.");
        pinRow = row(INDIGO, (c, x, y, sz, cl) -> Glyph.lock(c, x, y, sz, cl, false), "PIN-код Android", null, hasPin ? "Задан" : "Нет", this::askPin);
        add(pw, pinRow);
        add(pw, row(RED, (c, x, y, sz, cl) -> Glyph.lock(c, x, y, sz, cl, true), "Удалить PIN-код", null, null, () -> {
            Secret.clear(this);
            setValue(pinRow, "Нет");
            toast("PIN-код удалён");
        }));
        add(pw, row(svc ? GREEN : ORANGE, Glyph::swipe, "Служба «Vanks: жесты и вход»", svc ? null : "Нужна, чтобы набирать PIN за вас",
                svc ? "Вкл." : "Выкл.", () -> {
                    if (VanksAccess.turnOn(this)) toast("Служба «Vanks: жесты и вход» включена");
                }));

        // лицо: Xiaomi отдаёт его только своему экрану блокировки
        Group fc = group(col, "Лицо", "HyperOS не даёт приложениям распознавание лица — только своему экрану блокировки. "
                + "Поэтому, когда Android заперт PIN-кодом, экран Vanks при включении открывает системный экран, но держит "
                + "поверх него свою картинку: лицо и палец проверяет Android, а видно экран Vanks. Не узнал за 8 секунд — "
                + "экран Vanks снова сверху, палец читается как обычно. Нужна служба «Vanks: жесты и вход».");
        add(fc, toggleRow(TEAL, Glyph::camera, "Лицо и палец через Android", svc ? "Под картинкой экрана Vanks" : "Включите службу ниже",
                Prefs.lockSystemBio, v -> {
            Prefs.lockSystemBio = v;
            save();
            if (v && !VanksAccess.running() && VanksAccess.turnOn(this)) toast("Служба «Vanks: жесты и вход» включена");
        }));

        Group st = group(col, "Состояние", null);
        add(st, row(GREY, Glyph::phone, "Датчик отпечатка", null, Bio.hasFingerprintSensor(this) ? "Есть" : "Нет", null));
        add(st, row(GREY, (c, x, y, s, cl) -> Glyph.lock(c, x, y, s, cl, false), "Отпечаток или PIN", null,
                Bio.available(this) ? "Настроены" : "Не настроены", null));
        add(st, row(GREY, Glyph::info, "Вход без системного окна", null, FingerSensor.silent(this) ? "Да" : "Нет", null));
        if (FingerSensor.lastCode >= 0)
            add(st, row(GREY, Glyph::info, "Последний ответ датчика", FingerSensor.lastMsg, "Код " + FingerSensor.lastCode, null));
        Group act = group(col, null, null);
        add(act, row(GREEN, (c, x, y, s, cl) -> Glyph.fingerprint(c, x, y, s, cl, 1f), "Проверить отпечаток", null, null, () -> {
            if (!Bio.available(this)) {
                toast("Сначала добавьте отпечаток или PIN в Android");
                return;
            }
            toast("Приложите палец к датчику");
            FingerSensor.auth(this, new FingerSensor.Callback() {
                @Override
                public void ok() { toast("Отпечаток принят"); }

                @Override
                public void miss(String msg) { toast(msg); }

                @Override
                public void fail(String msg) { if (msg != null) toast(msg); }
            });
        }));
        add(act, row(BLUE, Glyph::plus, "Добавить отпечаток в Android", null, null, () -> {
            if (Build.VERSION.SDK_INT >= 30) open(Settings.ACTION_BIOMETRIC_ENROLL);
            else open(Settings.ACTION_FINGERPRINT_ENROLL);
        }));
        return out[0];
    }

    // ── о лаунчере ────────────────────────────────────────────────────────

    private View aboutPage() {
        View[] out = new View[1];
        LinearLayout col = page("О лаунчере", "Настройки", out);
        Group g = group(col, null, "Проверочный лаунчер концепта Vanks OS Mobile.");
        String ver = "0.2";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        add(g, row(0, null, "Название", null, "Vanks Launcher", null));
        add(g, row(0, null, "Версия", null, ver, null));
        add(g, row(0, null, "Телефон", null, Build.MANUFACTURER + " " + Build.MODEL, null));
        add(g, row(0, null, "Android", null, Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", null));
        add(g, row(0, null, "Приложений", null, String.valueOf(Apps.all.size()), null));
        Group d = group(col, null, "Чтобы кнопка «домой» открывала Vanks, выберите его лаунчером по умолчанию.");
        add(d, row(GREY, Glyph::phone, "Лаунчер по умолчанию", null, null, () -> open(Settings.ACTION_HOME_SETTINGS)));
        add(d, row(GREY, Glyph::info, "О приложении в Android", null, null, () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)))));
        return out[0];
    }

    // ── обновление по воздуху ─────────────────────────────────────────────

    private View updatePage() {
        View[] out = new View[1];
        LinearLayout col = page("Обновление", "Настройки", out);
        String cur = Updater.current(this);
        Group g = group(col, null, "Новые версии выходят на GitHub (" + Updater.REPO + "). Vanks скачивает APK, сверяет контрольную сумму "
                + "и ставит его через установщик Android; Android проверяет, что он подписан тем же ключом. "
                + "«Игры Vanks» — часть того же приложения и обновляются вместе с лаунчером.");
        add(g, row(0, null, "Установлена", null, cur, null));
        LinearLayout[] check = new LinearLayout[1], inst = new LinearLayout[1];
        TextView notes = Views.label(this, "", 14, Ui.regular, SUB);
        notes.setPadding(Ui.dp(36), Ui.dp(4), Ui.dp(28), 0);
        Runnable show = () -> {
            Updater.Release r = Updater.known;
            inst[0].setVisibility(r != null ? View.VISIBLE : View.GONE);
            if (r != null) setValue(inst[0], r.version);
            notes.setText(r != null && !r.notes.isEmpty() ? "Что нового в " + r.version + ":\n" + r.notes : "");
            notes.setVisibility(notes.getText().length() > 0 ? View.VISIBLE : View.GONE);
        };
        check[0] = row(BLUE, Glyph::rotate, "Проверить обновления", null, "", () -> {
            setValue(check[0], "Проверяю…");
            Updater.check(this, (r, err) -> {
                setValue(check[0], err != null ? "Ошибка" : r != null ? "Есть " + r.version : "Последняя");
                if (err != null) toast(err);
                show.run();
            });
        });
        add(g, check[0]);
        inst[0] = row(GREEN, Glyph::plus, "Скачать и установить", null, "", () -> {
            Updater.Release r = Updater.known;
            if (r == null) return;
            setValue(inst[0], "0 %");
            Updater.install(this, r, f -> setValue(inst[0], Math.round(f * 100) + " %"), (rr, err) -> {
                setValue(inst[0], err != null ? r.version : "Ставлю…");
                if (err != null) toast(err);
            });
        });
        add(g, inst[0]);
        col.addView(notes);
        show.run();

        Group a = group(col, null, "Раз в 12 часов, когда открыт стол. Если вышла новая версия — придёт уведомление.");
        add(a, toggleRow(TEAL, Glyph::bell, "Проверять самому", null, Prefs.autoUpdate, on -> {
            Prefs.autoUpdate = on;
            save();
        }));
        add(a, row(GREY, Glyph::info, "Репозиторий на GitHub", null, null, () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.page())))));
        return out[0];
    }
}
