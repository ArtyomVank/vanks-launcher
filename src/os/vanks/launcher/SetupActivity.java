package os.vanks.launcher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Мастер первого запуска Vanks OS — как начальная настройка Delta на ПК, но
 * в сплошном стиле, без стекла: язык, интернет, аккаунт Google, оформление,
 * обои, защита, жесты и звуки, «Готово». Всё можно пропустить и поменять потом
 * в «Настройках Vanks». Открывается со стола, пока мастер не пройден.
 */
public final class SetupActivity extends Activity {
    static boolean running;

    private static final String[] LANGS = {"ru-RU", "en-US", "uk-UA", "kk-KZ"};
    private static final String[] LANG_NAMES = {"Русский", "English", "Українська", "Қазақша"};
    private static final int PAGES = 8;

    private int page;
    private int bg, card, fg, fg2, acc;
    private FrameLayout stack, root;
    private LinearLayout rootCol;
    private boolean leaving;
    private TextView next, back;
    private View dots;
    private int insetTop, insetBottom;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        running = true;
        Prefs.load(this);
        Ui.init(this);
        if (b != null) page = b.getInt("page", 0);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        build();
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("page", page);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // вернулись из Wi‑Fi, аккаунта или PIN — показать, что получилось
        if (page == 1 || page == 2 || page == 5) show(page, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (page > 0 && !leaving) show(page - 1, -1);
    }

    // ── каркас: точки шагов сверху, страница, «Назад» и «Далее» снизу ─────

    /** Цвета от темы — сплошные: фон, карточки, текст, акцент. */
    private void colors() {
        bg = Look.surface();
        card = Look.light() ? Ui.mix(bg, 0xFF000000, 0.05f) : Ui.mix(bg, 0xFFFFFFFF, 0.08f);
        fg = Look.fg();
        fg2 = Look.fg2();
        acc = Ui.accent();
    }

    /** Собрать окно заново (после смены темы — новыми цветами). */
    private void build() {
        colors();
        // окно прозрачное (как у экрана блокировки), фон рисуем сами:
        // на «Начать» он тает, и под ним уже стол
        root = new FrameLayout(this);
        root.setBackground(new android.graphics.drawable.ColorDrawable(bg));
        rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        root.addView(rootCol, new FrameLayout.LayoutParams(-1, -1));

        dots = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                float r = Ui.dpf(3.5f), gap = Ui.dpf(14), w = gap * (PAGES - 1) + Ui.dpf(16);
                float x = (getWidth() - w) / 2f, y = getHeight() / 2f;
                for (int i = 0; i < PAGES; i++) {
                    p.setColor(i == page ? acc : Ui.alpha(fg, i < page ? 0.45f : 0.18f));
                    if (i == page) {
                        c.drawRoundRect(x - r, y - r, x + Ui.dpf(16) + r, y + r, r, r, p);
                        x += Ui.dpf(16) + gap;
                    } else {
                        c.drawCircle(x, y, r, p);
                        x += gap;
                    }
                }
            }
        };
        rootCol.addView(dots, new LinearLayout.LayoutParams(-1, Ui.dp(36)));
        stack = new FrameLayout(this);
        rootCol.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), Ui.dp(14));
        back = button("Назад", false);
        back.setOnClickListener(v -> show(page - 1, -1));
        bar.addView(back);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        next = button("Далее", true);
        next.setOnClickListener(v -> {
            if (page == PAGES - 1) done();
            else show(page + 1, 1);
        });
        bar.addView(next);
        rootCol.addView(bar);

        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetTop = s.top;
                insetBottom = s.bottom;
            } else {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
            }
            rootCol.setPadding(0, insetTop, 0, insetBottom);
            return in;
        });
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController wc = getWindow().getInsetsController();
            if (wc != null) wc.setSystemBarsAppearance(Look.light()
                            ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        show(page, 0);
    }

    /** Показать страницу p; dir — откуда въезжает (1 справа, -1 слева, 0 без движения). */
    private void show(int p, int dir) {
        if (p < 0 || p >= PAGES) return;
        page = p;
        View old = stack.getChildCount() > 0 ? stack.getChildAt(stack.getChildCount() - 1) : null;
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(24));
        sv.addView(col);
        switch (p) {
            case 0: hello(col); break;
            case 1: network(col); break;
            case 2: account(col); break;
            case 3: look(col); break;
            case 4: walls(col); break;
            case 5: security(col); break;
            case 6: motion(col); break;
            default: ready(col);
        }
        stack.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        if (old != null) {
            if (dir == 0) stack.removeView(old);
            else {
                float w = stack.getWidth();
                sv.setTranslationX(dir * w * 0.3f);
                sv.setAlpha(0);
                sv.animate().translationX(0).alpha(1).setDuration(260).setInterpolator(Anim.STD).start();
                old.animate().translationX(-dir * w * 0.3f).alpha(0).setDuration(200)
                        .withEndAction(() -> stack.removeView(old)).start();
            }
        }
        back.setVisibility(p == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(p == PAGES - 1 ? "Начать" : "Далее");
        dots.invalidate();
    }

    // ── страницы ──────────────────────────────────────────────────────────

    private void hello(LinearLayout col) {
        TextView logo = Views.label(this, "Vanks OS", 44, Ui.light, fg);
        logo.setPadding(0, Ui.dp(36), 0, 0);
        col.addView(logo);
        col.addView(sub("Добро пожаловать. Несколько шагов — и телефон готов. Всё можно поменять потом в «Настройках Vanks»."));
        col.addView(header("Язык"));
        String cur = Locale.getDefault().getLanguage();
        for (int i = 0; i < LANGS.length; i++) {
            String tag = LANGS[i];
            boolean on = Locale.forLanguageTag(tag).getLanguage().equals(cur);
            col.addView(choice(LANG_NAMES[i], null, on, () -> {
                setLocale(tag);
                show(0, 0);
            }));
        }
        col.addView(note("Язык системы и приложений. Vanks пока на русском."));
    }

    private void network(LinearLayout col) {
        col.addView(title("Интернет"));
        col.addView(sub("Нужен для Google Play, обновлений Vanks и погоды."));
        String state = netState();
        col.addView(status(state != null ? "Подключено: " + state : "Нет подключения", state != null));
        col.addView(action("Выбрать сеть Wi‑Fi", () -> open(new Intent(Build.VERSION.SDK_INT >= 29
                ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS))));
        col.addView(action("Мобильная сеть и SIM", () -> open(new Intent(Settings.ACTION_WIRELESS_SETTINGS))));
        col.addView(note("Можно пропустить и подключиться позже."));
    }

    private void account(LinearLayout col) {
        col.addView(title("Аккаунт Google"));
        col.addView(sub("Google Play, почта, контакты и резервные копии. Вход — в окне Google: данные вводите там сами."));
        col.addView(action("Войти в аккаунт Google", () -> open(new Intent(Settings.ACTION_ADD_ACCOUNT)
                .putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"}))));
        col.addView(action("Все аккаунты", () -> open(new Intent(Settings.ACTION_SYNC_SETTINGS))));
        col.addView(note("Без аккаунта телефон работает, но Google Play не откроется."));
    }

    private void look(LinearLayout col) {
        col.addView(title("Оформление"));
        col.addView(sub("Цвет, тема и стиль — сразу видно на этом экране."));

        col.addView(header("Цвет"));
        LinearLayout tones = new LinearLayout(this);
        for (Look.Tone t : Look.TONES) {
            // «Цвет обоев» — кружок главного цвета обоев
            int sw = t.wall ? (t.light ? Ui.mix(Look.wallSeed, 0xFFFFFFFF, 0.7f) : Ui.mix(Look.wallSeed, 0xFF000000, 0.6f)) : t.surface;
            tones.addView(swatch(sw, t.name, t.id.equals(Prefs.tone), () -> {
                Prefs.tone = t.id;
                if (t.accent != 0) Prefs.accent = t.accent;
                apply();
            }));
        }
        col.addView(hscroll(tones));

        col.addView(header("Акцент"));
        LinearLayout accents = new LinearLayout(this);
        for (int i = 0; i < Ui.ACCENTS.length; i++) {
            int a = Ui.ACCENTS[i];
            accents.addView(swatch(a, Ui.ACCENT_NAMES[i], a == Ui.accent(), () -> {
                Prefs.accent = a;
                apply();
            }));
        }
        col.addView(hscroll(accents));

        col.addView(header("Тема"));
        for (int i = 0; i < Look.MAT_IDS.length; i++) {
            String id = Look.MAT_IDS[i];
            col.addView(choice(Look.MAT_NAMES[i], Look.MAT_NOTES[i], Look.mat().equals(id), () -> {
                Prefs.material = id;
                apply();
            }));
        }
        col.addView(header("Стиль"));
        for (int i = 0; i < Look.UI_IDS.length; i++) {
            String id = Look.UI_IDS[i];
            col.addView(choice(Look.UI_NAMES[i], Look.UI_NOTES[i], id.equals(Prefs.ui), () -> {
                Prefs.ui = id;
                apply();
            }));
        }
    }

    private void walls(LinearLayout col) {
        col.addView(title("Обои"));
        col.addView(sub("На главный экран и экран блокировки. Больше — в «Настройках Vanks»."));
        List<Walls.W> list = new ArrayList<>();
        for (Walls.W w : Walls.PHOTOS) list.add(w);
        for (Walls.W w : Walls.all()) if (w.kind != Walls.PHOTO && list.size() < 12) list.add(w);
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            Walls.W w = list.get(i);
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.topMargin = Ui.dp(12);
                col.addView(row, rp);
            }
            row.addView(wallCell(w), new LinearLayout.LayoutParams(0, Ui.dp(190), 1));
        }
        if (row != null) for (int k = list.size() % 3; k > 0 && k < 3; k++)
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void security(LinearLayout col) {
        col.addView(title("Защита"));
        col.addView(sub("PIN-код защищает телефон; лицо и отпечаток открывают его быстрее. Экран блокировки — Vanks."));
        KeyguardManager km = getSystemService(KeyguardManager.class);
        boolean secure = km != null && km.isDeviceSecure();
        col.addView(status(secure ? "Экран защищён" : "Защиты нет: открыть может любой", secure));
        col.addView(action(secure ? "Сменить PIN-код или пароль" : "Задать PIN-код или пароль",
                () -> open(new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))));
        if (Build.VERSION.SDK_INT >= 30) {
            col.addView(action("Добавить лицо", () -> open(new Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK))));
        }
        if (Bio.hasFingerprintSensor(this)) {
            col.addView(action("Добавить отпечаток", () -> open(new Intent(Build.VERSION.SDK_INT >= 28
                    ? Settings.ACTION_FINGERPRINT_ENROLL : Settings.ACTION_SECURITY_SETTINGS))));
        }
        col.addView(note("Лицо и отпечаток добавляются после PIN-кода."));
    }

    private void motion(LinearLayout col) {
        col.addView(title("Жесты и звуки"));
        col.addView(sub("Как телефон отвечает на касания."));
        col.addView(toggle("Жесты Vanks", "Свайп снизу — домой, от края — назад", Prefs.navGestures, on -> {
            Prefs.navGestures = on;
            if (on) VanksAccess.enable(this);
        }));
        col.addView(toggle("Звуки Vanks", "Короткие звуки интерфейса", Prefs.sounds, on -> Prefs.sounds = on));
        col.addView(toggle("Отклик вибрацией", "Лёгкий щелчок при нажатиях", Prefs.haptics, on -> Prefs.haptics = on));
        col.addView(header("Скорость анимаций"));
        float[] speeds = {0.75f, 1f, 1.5f};
        String[] names = {"Плавно", "Обычно", "Быстро"};
        LinearLayout seg = new LinearLayout(this);
        for (int i = 0; i < speeds.length; i++) {
            float sp = speeds[i];
            seg.addView(chip(names[i], Math.abs(Prefs.animSpeed - sp) < 0.01f, () -> {
                Prefs.animSpeed = sp;
                Prefs.save();
                show(6, 0);
            }));
        }
        col.addView(seg);
        col.addView(header("Всегда на экране"));
        LinearLayout aod = new LinearLayout(this);
        for (int i = 0; i < AodActivity.MODES.length; i++) {
            String m = AodActivity.MODES[i];
            aod.addView(chip(AodActivity.MODE_NAMES[i], m.equals(Prefs.aodMode), () -> {
                Prefs.aodMode = m;
                Prefs.save();
                LockService.sync(this);
                show(6, 0);
            }));
        }
        col.addView(aod);
    }

    private void ready(LinearLayout col) {
        TextView t = title("Всё готово");
        t.setPadding(0, Ui.dp(36), 0, 0);
        col.addView(t);
        col.addView(sub("Свайп сверху слева — уведомления, справа — пункт управления. Снизу — домой, от края — назад."));
        col.addView(sub("Обои, часы экрана блокировки, значки и плитки — в «Настройках Vanks»."));
    }

    /**
     * Мастер пройден: отметить и у Android (настройка устройства завершена) и
     * уйти, как при входе с экрана блокировки — страница уплывает, фон тает
     * со стеклянной волной, под ним стол (он и открыл мастер).
     */
    private void done() {
        if (leaving) return;
        leaving = true;
        Prefs.setupDone = true;
        Prefs.save();
        if (Nav.canWrite(this)) {
            try {
                Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
                Settings.Secure.putInt(getContentResolver(), "user_setup_complete", 1);
            } catch (RuntimeException ignored) {
            }
        }
        LockService.sync(this);
        Feel.sound(this, "unlock");
        Feel.buzz(root, Feel.RISE);
        root.postDelayed(() -> Feel.sound(this, "glass-wave"), 140);
        long k = (long) (700 * Prefs.k());
        rootCol.animate().alpha(0).scaleX(1.06f).scaleY(1.06f).setDuration(k / 2).setInterpolator(Anim.STD).start();
        if (Prefs.unlockWave) GlassWave.play(root, root.getWidth() / 2f, root.getHeight() / 2f, 1f, 1700);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(255, 0);
        a.setStartDelay(160);
        a.setDuration(k);
        a.setInterpolator(Anim.STD);
        a.addUpdateListener(v -> root.getBackground().setAlpha((int) v.getAnimatedValue()));
        a.start();
        root.postDelayed(() -> {
            finish();
            overridePendingTransition(0, 0);
        }, (long) (900 * Prefs.k()));
    }

    // ── действия ──────────────────────────────────────────────────────────

    /** Поменять оформление и перекрасить мастер его цветами. */
    private void apply() {
        Prefs.save();
        Feel.buzz(stack, Feel.CLICK);
        build();
    }

    /** Язык системы: системный вызов (разрешение CHANGE_CONFIGURATION у Vanks OS), иначе — настройки языка. */
    private void setLocale(String tag) {
        try {
            Class<?> lp = Class.forName("com.android.internal.app.LocalePicker");
            lp.getMethod("updateLocales", LocaleList.class).invoke(null, new LocaleList(Locale.forLanguageTag(tag)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            open(new Intent(Settings.ACTION_LOCALE_SETTINGS));
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, "Не удалось открыть", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String netState() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "мобильная сеть";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_USB) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "по проводу";
            return "есть";
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── детали ────────────────────────────────────────────────────────────

    private TextView title(String s) {
        TextView t = Views.label(this, s, 30, Ui.bold, fg);
        t.setPadding(0, Ui.dp(20), 0, 0);
        return t;
    }

    private TextView sub(String s) {
        TextView t = Views.label(this, s, 15, Ui.regular, fg2);
        t.setPadding(0, Ui.dp(8), 0, Ui.dp(4));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView note(String s) {
        TextView t = Views.label(this, s, 13, Ui.regular, fg2);
        t.setPadding(Ui.dp(4), Ui.dp(12), 0, 0);
        return t;
    }

    private TextView header(String s) {
        TextView t = Views.label(this, s, 13, Ui.medium, fg2);
        t.setPadding(Ui.dp(4), Ui.dp(22), 0, Ui.dp(8));
        return t;
    }

    private TextView button(String s, boolean primary) {
        TextView t = Views.label(this, s, 16, Ui.medium, primary ? onAccent() : fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(26), Ui.dp(12), Ui.dp(26), Ui.dp(12));
        t.setBackground(Views.round(primary ? acc : card, 24));
        return t;
    }

    /** Текст на акценте: на светлом — тёмный. */
    private int onAccent() {
        float l = (android.graphics.Color.red(acc) + android.graphics.Color.green(acc) + android.graphics.Color.blue(acc)) / 765f;
        return l > 0.6f ? 0xFF1A1D2A : 0xFFFFFFFF;
    }

    private View cardBox(View inner, boolean selected) {
        GradientDrawable d = Views.round(card, 18);
        if (selected) d.setStroke(Ui.dp(2), acc);
        inner.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(8);
        inner.setLayoutParams(lp);
        return inner;
    }

    /** Карточка выбора: название, пояснение, кружок отметки справа. */
    private View choice(String name, String note, boolean on, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Views.label(this, name, 16, Ui.medium, fg));
        if (note != null) tx.addView(Views.label(this, note, 13, Ui.regular, fg2));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        View mark = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(2));
            p.setColor(on ? acc : Ui.alpha(fg, 0.35f));
            c.drawCircle(w / 2, h / 2, Ui.dpf(10), p);
            if (on) {
                p.setColor(onAccent());
                c.drawCircle(w / 2, h / 2, Ui.dpf(4), p);
            }
        });
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
        row.setOnClickListener(v -> {
            Feel.buzz(v, Feel.CLICK);
            click.run();
        });
        return cardBox(row, on);
    }

    /** Строка состояния: зелёная точка — хорошо, оранжевая — нужно сделать. */
    private View status(String s, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        View dot = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(ok ? 0xFF30D158 : 0xFFFF9F0A);
            c.drawCircle(w / 2, h / 2, Ui.dpf(5), p);
        });
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        TextView t = Views.label(this, s, 15, Ui.medium, fg);
        t.setPadding(Ui.dp(10), 0, 0, 0);
        row.addView(t);
        return cardBox(row, false);
    }

    /** Кнопка-строка: открыть что-то системное. */
    private View action(String s, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(14), Ui.dp(16));
        row.addView(Views.label(this, s, 16, Ui.medium, acc), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Views.drawn(this, (c, w, h) -> Glyph.chevronRight(c, w / 2, h / 2, Ui.dpf(18), fg2)),
                new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        row.setOnClickListener(v -> click.run());
        return cardBox(row, false);
    }

    interface Flip { void set(boolean on); }

    /** Переключатель: название, пояснение, тумблер справа. */
    private View toggle(String name, String note, boolean on0, Flip flip) {
        boolean[] on = {on0};
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Views.label(this, name, 16, Ui.medium, fg));
        tx.addView(Views.label(this, note, 13, Ui.regular, fg2));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        View sw = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(on[0] ? acc : Ui.alpha(fg, 0.2f));
            c.drawRoundRect(0, h / 2 - Ui.dpf(14), w, h / 2 + Ui.dpf(14), Ui.dpf(14), Ui.dpf(14), p);
            p.setColor(on[0] ? onAccent() : 0xFFFFFFFF);
            c.drawCircle(on[0] ? w - Ui.dpf(14) : Ui.dpf(14), h / 2, Ui.dpf(11), p);
        });
        row.addView(sw, new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(32)));
        row.setOnClickListener(v -> {
            on[0] = !on[0];
            flip.set(on[0]);
            Prefs.save();
            Feel.buzz(v, Feel.CLICK);
            sw.invalidate();
        });
        return cardBox(row, false);
    }

    /** Кнопка в ряду (сегмент). */
    private View chip(String s, boolean on, Runnable click) {
        TextView t = Views.label(this, s, 14, Ui.medium, on ? onAccent() : fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(8), Ui.dp(12), Ui.dp(8), Ui.dp(12));
        t.setBackground(Views.round(on ? acc : card, 16));
        t.setOnClickListener(v -> {
            Feel.buzz(v, Feel.CLICK);
            click.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.rightMargin = Ui.dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** Кружок цвета с подписью. */
    private View swatch(int color, String name, boolean on, Runnable click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        View dot = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color | 0xFF000000);
            c.drawCircle(w / 2, h / 2, Ui.dpf(20), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(on ? 3 : 1));
            p.setColor(on ? acc : Ui.alpha(fg, 0.25f));
            c.drawCircle(w / 2, h / 2, Ui.dpf(on ? 24 : 20), p);
        });
        box.addView(dot, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
        TextView t = Views.label(this, name, 12, on ? Ui.medium : Ui.regular, on ? fg : fg2);
        t.setGravity(Gravity.CENTER);
        t.setMaxWidth(Ui.dp(72));
        box.addView(t);
        box.setOnClickListener(v -> click.run());
        return box;
    }

    private View hscroll(View inner) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.addView(inner);
        return h;
    }

    /** Картинка обоев: нажали — на оба экрана. */
    private View wallCell(Walls.W w) {
        boolean on = w.id.equals(Prefs.wallpaper);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inSampleSize = 8;
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), w.still, o);
        iv.setImageBitmap(bmp);
        iv.setClipToOutline(true);
        GradientDrawable d = Views.round(card, 16);
        if (on) d.setStroke(Ui.dp(3), acc);
        iv.setBackground(d);
        if (on) iv.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        f.setOnClickListener(v -> {
            Feel.buzz(v, Feel.CLICK);
            Prefs.wallpaper = w.id;
            Prefs.lockWallpaper = "";
            Prefs.save();
            Ui.loadWall(this);
            Ui.loadLockWall(this);
            SysWall.sync(this);
            show(4, 0);
        });
        return f;
    }
}
