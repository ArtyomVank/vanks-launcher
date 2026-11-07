package os.vanks.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * «Игры Vanks» — библиотека Windows-игр на обоях Vanks: сверху последняя
 * игра («Продолжить»), ниже все игры карточками, эмуляторы и ярлыки других
 * приложений. Игру запускает её эмулятор (Winlator, GameHub…), а сюда она
 * попадает его кнопкой «Добавить на главный экран».
 */
public final class GamesActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout col;
    private WallView wall;
    private int top, bottom;
    private LauncherApps la;

    private final LauncherApps.Callback changes = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reloadApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reloadApps(); }

        @Override
        public void onShortcutsChanged(String p, List<ShortcutInfo> s, UserHandle u) {
            ui.removeCallbacks(rebuild);
            ui.postDelayed(rebuild, 120);
        }
    };

    private final Runnable rebuild = this::build;

    private void reloadApps() {
        ui.post(() -> {
            Apps.load(this);
            build();
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Hz.apply(this);
        Ui.init(this);
        Feel.init(this);
        la = getSystemService(LauncherApps.class);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        wall = new WallView(this);
        wall.dim = 0.42f;
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top;
                bottom = s.bottom;
            } else {
                top = in.getSystemWindowInsetTop();
                bottom = in.getSystemWindowInsetBottom();
            }
            col.setPadding(Ui.dp(18), top + Ui.dp(18), Ui.dp(18), bottom + Ui.dp(28));
            return in;
        });
        setContentView(root);
        wall.reload();
        wall.playIntro();
        Apps.load(this);
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            la.registerCallback(changes, ui);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            la.unregisterCallback(changes);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        wall.setPaused(false);
        if (Ui.loadWall(this) || wall.stale()) wall.reload();
        build();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wall.setPaused(true);
    }

    // ── экран ─────────────────────────────────────────────────────────────

    private void build() {
        col.removeAllViews();
        col.addView(text("Игры", 34, Ui.bold, 0xFFFFFFFF), lp(-1, -2, 0, 8, 0, 0));
        col.addView(text("Windows-игры через эмулятор — в одном месте", 15, Ui.regular, 0xB3FFFFFF), lp(-1, -2, 0, 2, 0, 18));

        List<Apps.App> emus = Games.emulators(this);
        ArrayList<ShortcutInfo> games = new ArrayList<>(), other = new ArrayList<>();
        Games.pinned(this, games, other);
        boolean host = Games.host(this);

        if (!host) {
            col.addView(note("Сделайте Vanks главным экраном",
                    "Ярлыки игр Android отдаёт только главному экрану. Выберите Vanks как главный экран — и игры из эмулятора появятся здесь.",
                    "Выбрать главный экран", () -> open(new Intent(Settings.ACTION_HOME_SETTINGS))), lp(-1, -2, 0, 0, 0, 14));
        }
        if (emus.isEmpty()) {
            col.addView(note("Нужен эмулятор Windows",
                    "Windows-игры на телефоне запускает эмулятор: Wine переводит Windows в Android, Box64 — команды компьютера в команды телефона. "
                            + "Бесплатный и открытый — Winlator: скачайте его со страницы проекта, установите, добавьте игру (папку с .exe) "
                            + "и нажмите у неё «Добавить на главный экран». Подойдут и GameHub, GameNative.",
                    "Winlator на GitHub", () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(Games.WINLATOR_PAGE)))), lp(-1, -2, 0, 0, 0, 14));
        }

        if (!games.isEmpty()) {
            ShortcutInfo last = games.get(0);
            if (Games.lastPlayed(this, last) > 0) {
                col.addView(header("Продолжить"));
                col.addView(hero(last), lp(-1, Ui.dp(132), 0, 0, 0, 18));
            }
            col.addView(header("Все игры · " + games.size()));
            col.addView(grid(games), lp(-1, -2, 0, 0, 0, 18));
        } else if (host && !emus.isEmpty()) {
            col.addView(note("Пока нет игр",
                    "Откройте эмулятор, добавьте игру и нажмите у неё «Добавить на главный экран» (в Winlator — зажмите ярлык игры). "
                            + "Игра появится здесь, а запускаться будет одним касанием.",
                    "Открыть " + emus.get(0).label, () -> launchApp(emus.get(0))), lp(-1, -2, 0, 0, 0, 14));
        }

        if (!emus.isEmpty()) {
            col.addView(header("Эмуляторы"));
            col.addView(emulatorRow(emus), lp(-1, -2, 0, 0, 0, 18));
        }
        if (!other.isEmpty()) {
            col.addView(header("Ярлыки"));
            col.addView(grid(other), lp(-1, -2, 0, 0, 0, 18));
        }
        col.addView(text("Зажмите игру — убрать из библиотеки. Сама игра в эмуляторе остаётся.", 12, Ui.regular, 0x80FFFFFF),
                lp(-1, -2, 4, 4, 4, 0));
    }

    private TextView header(String s) {
        TextView t = text(s, 13, Ui.medium, 0x99FFFFFF);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setLayoutParams(lp(-1, -2, 4, 0, 0, 8));
        return t;
    }

    /** Последняя игра: широкая карточка с кнопкой «Играть». */
    private View hero(ShortcutInfo si) {
        Glass g = new Glass(this, 26, true);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));
        ImageView ic = new ImageView(this);
        ic.setImageBitmap(icon(si, Ui.dp(96)));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(96), Ui.dp(96)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(label(si), 19, Ui.bold, 0xFFFFFFFF);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(name);
        tx.addView(text(Games.emulatorName(si.getPackage()), 13, Ui.regular, 0x99FFFFFF));
        TextView play = text("▶  Играть", 15, Ui.medium, 0xFF101010);
        play.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xFFFFFFFF);
        pill.setCornerRadius(Ui.dpf(18));
        play.setBackground(pill);
        play.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, Ui.dp(36));
        pl.topMargin = Ui.dp(10);
        tx.addView(play, pl);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = Ui.dp(16);
        row.addView(tx, tl);
        g.addView(row, new FrameLayout.LayoutParams(-1, -1));
        press(g, () -> start(si, g), () -> menu(si, g));
        return g;
    }

    /** Карточки по две в ряд. */
    private View grid(List<ShortcutInfo> list) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, lp(-1, -2, 0, 0, 0, 12));
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, Ui.dp(178), 1);
            if (i % 2 == 0) cl.rightMargin = Ui.dp(6); else cl.leftMargin = Ui.dp(6);
            row.addView(card(list.get(i)), cl);
        }
        if (list.size() % 2 == 1 && row != null) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, 1, 1);
            cl.leftMargin = Ui.dp(6);
            row.addView(new View(this), cl);
        }
        return box;
    }

    private View card(ShortcutInfo si) {
        Glass g = new Glass(this, 24, true);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(Ui.dp(12), Ui.dp(18), Ui.dp(12), Ui.dp(12));
        ImageView ic = new ImageView(this);
        ic.setImageBitmap(icon(si, Ui.dp(76)));
        if (!si.isEnabled()) ic.setAlpha(0.4f);
        v.addView(ic, new LinearLayout.LayoutParams(Ui.dp(76), Ui.dp(76)));
        TextView name = text(label(si), 15, Ui.medium, 0xFFFFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(name, lp(-1, -2, 0, 10, 0, 0));
        TextView sub = text(Games.emulatorName(si.getPackage()), 12, Ui.regular, 0x8CFFFFFF);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(sub, lp(-1, -2, 0, 2, 0, 0));
        g.addView(v, new FrameLayout.LayoutParams(-1, -1));
        press(g, () -> start(si, g), () -> menu(si, g));
        return g;
    }

    private View emulatorRow(List<Apps.App> emus) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Apps.App a : emus) {
            Glass g = new Glass(this, 22, true);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(16), Ui.dp(8));
            ImageView ic = new ImageView(this);
            ic.setImageBitmap(rounded(a.icon, Ui.dp(30)));
            chip.addView(ic, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));
            TextView t = text(a.label, 14, Ui.medium, 0xFFFFFFFF);
            t.setSingleLine(true);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.leftMargin = Ui.dp(10);
            chip.addView(t, tl);
            g.addView(chip, new FrameLayout.LayoutParams(-2, -2));
            press(g, () -> launchApp(a), null);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.rightMargin = Ui.dp(10);
            row.addView(g, gl);
        }
        hs.addView(row);
        return hs;
    }

    /** Карточка-подсказка с кнопкой. */
    private View note(String title, String body, String button, Runnable run) {
        Glass g = new Glass(this, 24, true);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
        v.addView(text(title, 17, Ui.bold, 0xFFFFFFFF));
        v.addView(text(body, 14, Ui.regular, 0xBFFFFFFF), lp(-1, -2, 0, 6, 0, 0));
        TextView b = text(button, 15, Ui.medium, 0xFF101010);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xFFFFFFFF);
        pill.setCornerRadius(Ui.dpf(19));
        b.setBackground(pill);
        b.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, Ui.dp(38));
        bl.topMargin = Ui.dp(12);
        v.addView(b, bl);
        press(b, run, null);
        g.addView(v, new FrameLayout.LayoutParams(-1, -2));
        return g;
    }

    // ── действия ──────────────────────────────────────────────────────────

    private void start(ShortcutInfo si, View from) {
        if (!si.isEnabled()) {
            CharSequence m = si.getDisabledMessage();
            Toast.makeText(this, m != null ? m : "Игра недоступна — откройте эмулятор", Toast.LENGTH_SHORT).show();
            return;
        }
        Feel.sound(this, "app-open");
        Feel.buzz(from, Feel.CLICK);
        int[] l = new int[2];
        from.getLocationOnScreen(l);
        android.graphics.Rect r = new android.graphics.Rect(l[0], l[1], l[0] + from.getWidth(), l[1] + from.getHeight());
        Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        try {
            la.startShortcut(si.getPackage(), si.getId(), r, opts, si.getUserHandle());
            Games.played(this, si);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Не удалось запустить — откройте эмулятор", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchApp(Apps.App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        open(i);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Нечем открыть", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(ShortcutInfo si, View from) {
        Feel.buzz(from, Feel.PRESS);
        int[] l = new int[2];
        from.getLocationOnScreen(l);
        RectF r = new RectF(l[0], l[1], l[0] + from.getWidth(), l[1] + from.getHeight());
        DropMenu m = new DropMenu(this, label(si), r.centerX(), r.centerY(), r);
        m.add("Играть", (c, x, y, s, cl) -> Glyph.play(c, x, y, s, cl, false), () -> start(si, from));
        for (Apps.App a : Games.emulators(this))
            if (a.cn.getPackageName().equals(si.getPackage())) {
                m.add("Открыть " + a.label, Glyph::grid, () -> launchApp(a));
                break;
            }
        m.add("Убрать из библиотеки", Glyph::close, () -> {
            Games.unpin(this, si);
            build();
        });
        m.show(root);
    }

    // ── мелочи ────────────────────────────────────────────────────────────

    private static String label(ShortcutInfo si) {
        CharSequence l = si.getLongLabel();
        if (l == null || l.length() == 0 || l.length() > 40) l = si.getShortLabel();
        return l == null ? "" : l.toString();
    }

    private Bitmap icon(ShortcutInfo si, int px) {
        Drawable d = null;
        try {
            d = la.getShortcutIconDrawable(si, getResources().getDisplayMetrics().densityDpi);
        } catch (RuntimeException ignored) {
        }
        if (d == null) d = getPackageManager().getDefaultActivityIcon();
        return rounded(d, px);
    }

    /** Значок в скруглённом квадрате (у игр из .exe значки любые — так они в одном стиле). */
    private static Bitmap rounded(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        float rad = px * 0.26f;
        clip.addRoundRect(new RectF(0, 0, px, px), rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        if (d != null) {
            d.setBounds(0, 0, px, px);
            d.draw(c);
        }
        return b;
    }

    private TextView text(String s, float sp, android.graphics.Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(Ui.dp(l), Ui.dp(t), Ui.dp(r), Ui.dp(b));
        return p;
    }

    /** Нажатие с пружинкой; долгое — меню. */
    private static void press(View v, Runnable tap, Runnable hold) {
        v.setOnClickListener(x -> tap.run());
        if (hold != null) v.setOnLongClickListener(x -> {
            hold.run();
            return true;
        });
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    x.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
                    break;
            }
            return false;
        });
    }
}
