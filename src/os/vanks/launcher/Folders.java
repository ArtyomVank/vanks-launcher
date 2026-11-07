package os.vanks.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Папки на столе, три вида:
 *   grid  — «Сетка»: маленькие значки в квадрате, как в iOS;
 *   big   — «Крупная», как в ColorOS: первые значки крупные и нажимаются
 *           прямо на столе, в последней ячейке — сетка остальных;
 *   stack — «Стопка»: значки веером один за другим.
 * Размер меняется в правке стола, как у виджета. Внутри — название (его
 * можно править), выбор вида и все значки.
 */
final class Folders {
    private static final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final RectF tmp = new RectF();

    // ── на столе ──────────────────────────────────────────────────────────

    static void draw(Canvas c, Layout.Item it, RectF body, View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        // стол сдвинут при листании — стекло берём с того места, где он встанет
        if (v.getParent() instanceof Desk.PageView) loc[0] -= Math.round(((View) v.getParent()).getTranslationX());
        View root = v.getRootView();
        float rad = Math.min(Look.radius(it.w == 1 && it.h == 1 ? 14 : 22), body.height() / 2);
        if (!"stack".equals(it.style)) Look.panel(c, body, rad, loc[0] + body.left, loc[1] + body.top, root.getWidth(), root.getHeight());
        List<Apps.App> apps = Apps.resolve(it.apps);
        switch (style(it)) {
            case "big": drawBig(c, it, body, apps); break;
            case "stack": drawStack(c, body, apps); break;
            default: drawGrid(c, body, apps, gridCols(it), gridRows(it));
        }
    }

    /** «Крупная» в одну клетку не помещается — там «Сетка». */
    static String style(Layout.Item it) {
        if ("big".equals(it.style) && it.w * it.h < 2) return "grid";
        return it.style == null ? "grid" : it.style;
    }

    private static int gridCols(Layout.Item it) { return it.w == 1 ? 3 : Math.min(6, it.w * 2); }

    private static int gridRows(Layout.Item it) { return it.h == 1 ? 3 : Math.min(6, it.h * 2); }

    private static void drawGrid(Canvas c, RectF body, List<Apps.App> apps, int cols, int rows) {
        float pad = Math.min(body.width(), body.height()) * 0.1f;
        float cw = (body.width() - 2 * pad) / cols, ch = (body.height() - 2 * pad) / rows;
        float s = Math.min(cw, ch) * 0.8f;
        for (int i = 0; i < Math.min(apps.size(), cols * rows); i++) {
            float cx = body.left + pad + cw * (i % cols + 0.5f), cy = body.top + pad + ch * (i / cols + 0.5f);
            Bitmap b = apps.get(i).bmp;
            tmp.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            if (b != null) c.drawBitmap(b, null, tmp, bmp);
        }
    }

    private static int bigSlots(Layout.Item it) {
        return Math.max(2, it.w * it.h);
    }

    /** Ячейка «Крупной»: i-я по порядку (последняя — сетка остальных, если не влезли). */
    private static RectF slot(Layout.Item it, RectF body, int i, RectF out) {
        int cols = Math.max(1, it.w), rows = Math.max(1, it.h);
        if (cols * rows < 2) { cols = 2; rows = 1; }
        float pad = Ui.dpf(8);
        float cw = (body.width() - 2 * pad) / cols, ch = (body.height() - 2 * pad) / rows;
        float cx = body.left + pad + cw * (i % cols + 0.5f), cy = body.top + pad + ch * (i / cols + 0.5f);
        float s = Math.min(cw, ch) * 0.78f;
        out.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        return out;
    }

    private static void drawBig(Canvas c, Layout.Item it, RectF body, List<Apps.App> apps) {
        int n = bigSlots(it);
        boolean more = apps.size() > n;
        int big = more ? n - 1 : Math.min(n, apps.size());
        for (int i = 0; i < big; i++) {
            Bitmap b = apps.get(i).bmp;
            if (b != null) c.drawBitmap(b, null, slot(it, body, i, tmp), bmp);
        }
        if (more) {
            RectF r = new RectF(slot(it, body, n - 1, tmp));
            drawGrid(c, r, apps.subList(big, apps.size()), 2, 2);
        }
    }

    private static void drawStack(Canvas c, RectF body, List<Apps.App> apps) {
        float s = Math.min(body.width(), body.height()) * 0.74f;
        int n = Math.min(3, apps.size());
        // сзади — дальние, повёрнутые в стороны
        for (int i = n - 1; i >= 0; i--) {
            Bitmap b = apps.get(i).bmp;
            if (b == null) continue;
            float k = 1 - 0.1f * i;
            float rot = i == 0 ? 0 : (i == 1 ? -11 : 11);
            float dx = i == 0 ? 0 : (i == 1 ? -s * 0.16f : s * 0.16f);
            c.save();
            c.translate(body.centerX() + dx, body.centerY() - i * s * 0.03f);
            c.rotate(rot);
            tmp.set(-s * k / 2, -s * k / 2, s * k / 2, s * k / 2);
            bmp.setAlpha(i == 0 ? 255 : 220);
            c.drawBitmap(b, null, tmp, bmp);
            c.restore();
        }
        bmp.setAlpha(255);
    }

    /** Нажатие в «Крупной» — по какому значку (null — открыть папку). */
    static String hit(Layout.Item it, RectF body, float x, float y) {
        if (!"big".equals(style(it))) return null;
        List<String> keys = it.apps;
        int n = bigSlots(it);
        int big = keys.size() > n ? n - 1 : Math.min(n, keys.size());
        for (int i = 0; i < big; i++) {
            RectF r = slot(it, body, i, new RectF());
            r.inset(-Ui.dpf(6), -Ui.dpf(6));
            if (r.contains(x, y)) return keys.get(i);
        }
        return null;
    }

    static RectF slotRect(Layout.Item it, RectF body, String key) {
        int i = Math.max(0, it.apps.indexOf(key));
        return slot(it, body, i, new RectF());
    }

    /** Название новой папки — по тому, что общего у приложений. */
    static String guessName(List<String> keys) {
        int cat = -2;
        for (String k : keys) {
            Apps.App a = Apps.byKey.get(k);
            int c = a == null ? -1 : a.category;
            if (cat == -2) cat = c;
            else if (cat != c) { cat = -1; break; }
        }
        switch (cat) {
            case ApplicationInfo.CATEGORY_GAME: return "Игры";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO: return "Медиа";
            case ApplicationInfo.CATEGORY_IMAGE: return "Фото";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Общение";
            case ApplicationInfo.CATEGORY_NEWS: return "Новости";
            case ApplicationInfo.CATEGORY_MAPS: return "Карты";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Работа";
        }
        return "Папка";
    }

    // ── открытая папка ────────────────────────────────────────────────────

    /**
     * Папка раскрывается из своего места на столе в карточку посреди экрана
     * (пружиной); вокруг — матовые обои. Нажатие мимо или «назад» — закрыть.
     */
    static final class Panel extends FrameLayout {
        final Layout.Item item;
        private final Home home;
        private final LinearLayout card;
        private final AppGrid grid;
        private final EditText title;
        private final RectF from;
        private float k;
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean closing;

        Panel(Home h, Layout.Item item, RectF from) {
            super(h);
            this.home = h;
            this.item = item;
            this.from = from;
            setWillNotDraw(false);
            setClickable(true);
            setOnClickListener(v -> close());

            card = new LinearLayout(h) {
                @Override
                protected void onDraw(Canvas c) {
                    RectF r = new RectF(0, 0, getWidth(), getHeight());
                    float rad = Look.radius(26);
                    cp.setColor(Ui.alpha(Look.surface(), Look.is("nothing") || Look.is("gnome") || Look.is("material") ? 0.95f : 0.6f));
                    c.drawRoundRect(r, rad, rad, cp);
                    if (Look.is("acrylic")) Look.contour(c, r, rad);
                }
            };
            card.setWillNotDraw(false);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(14), Ui.dp(16), Ui.dp(14), Ui.dp(14));
            card.setClickable(true);

            title = new EditText(h);
            title.setText(item.name);
            title.setHint("Название");
            title.setTextColor(Look.fg());
            title.setHintTextColor(Look.fg2());
            title.setTextSize(24);
            title.setTypeface(Ui.bold);
            title.setSingleLine(true);
            title.setGravity(Gravity.CENTER);
            title.setBackground(null);
            title.setImeOptions(EditorInfo.IME_ACTION_DONE);
            title.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    item.name = s.toString().trim().isEmpty() ? "Папка" : s.toString().trim();
                }
            });
            title.setOnEditorActionListener((v, a, e) -> {
                hideKeyboard();
                return true;
            });
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));

            // вид папки
            LinearLayout styles = new LinearLayout(h);
            styles.setGravity(Gravity.CENTER);
            styles.setPadding(0, Ui.dp(4), 0, Ui.dp(10));
            TextView[] chips = new TextView[Layout.FOLDER_STYLES.length];
            for (int i = 0; i < chips.length; i++) {
                final int idx = i;
                TextView t = Views.label(h, Layout.FOLDER_NAMES[i], 13, Ui.medium, Look.fg());
                t.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
                t.setOnClickListener(v -> {
                    item.style = Layout.FOLDER_STYLES[idx];
                    for (int j = 0; j < chips.length; j++) chips[j].setBackground(chip(j == idx));
                    Feel.buzz(v, Feel.TICK);
                    Layout.save();
                    home.deskChanged();
                });
                chips[i] = t;
                t.setBackground(chip(Layout.FOLDER_STYLES[i].equals(item.style)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = lp.rightMargin = Ui.dp(3);
                styles.addView(t, lp);
            }
            card.addView(styles);
            TextView hint = Views.label(h, "Зажмите значок и ведите — чтобы переставить", 12, Ui.regular, Look.fg2());
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, 0, 0, Ui.dp(6));
            card.addView(hint, new LinearLayout.LayoutParams(-1, -2));

            grid = new AppGrid(h);
            grid.cols = 4;
            grid.labels = true;
            grid.setLabelColor(Look.light() ? 0xFF16171D : 0xFFF4F6FF);
            grid.setApps(Apps.resolve(item.apps));
            grid.listener = new AppGrid.Listener() {
                @Override
                public void tap(Apps.App a, AppGrid g, RectF r) {
                    if (a == null) return;
                    close();
                    home.launch(a, r);
                }

                @Override
                public void longPress(Apps.App a, AppGrid g, RectF r) {
                    if (a != null) home.folderAppMenu(Panel.this, a, r);
                }

                @Override
                public void dragStarted() {
                    home.closeMenus();
                }

                @Override
                public void reordered(java.util.List<Apps.App> order) {
                    // новый порядок значков в папке
                    item.apps.clear();
                    for (Apps.App x : order) item.apps.add(x.key);
                    Layout.save();
                    home.deskChanged();
                }
            };
            grid.reorder = true;
            android.widget.ScrollView sv = new android.widget.ScrollView(h);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(grid);
            card.addView(sv, new LinearLayout.LayoutParams(-1, -2));

            LayoutParams lp = new LayoutParams(-1, -2, Gravity.CENTER);
            lp.leftMargin = lp.rightMargin = Ui.dp(22);
            addView(card, lp);
        }

        private static android.graphics.drawable.Drawable chip(boolean on) {
            return Views.round(on ? Ui.alpha(Ui.accent(), 0.9f) : (Look.light() ? 0x14000000 : 0x1FFFFFFF), 16);
        }

        void open() {
            card.post(() -> {
                // из места папки на столе — в карточку
                float cx = from.centerX() - (card.getLeft() + card.getWidth() / 2f);
                float cy = from.centerY() - (card.getTop() + card.getHeight() / 2f);
                float s0 = Math.max(0.15f, from.width() / Math.max(1f, card.getWidth()));
                Anim.spring(0, 1, 0, 300, 0.8f, t -> {
                    k = t;
                    card.setTranslationX(cx * (1 - t));
                    card.setTranslationY(cy * (1 - t));
                    float s = s0 + (1 - s0) * t;
                    card.setScaleX(s);
                    card.setScaleY(s);
                    card.setAlpha(Ui.clamp(t * 2.2f));
                    invalidate();
                }, null);
                grid.playEnter();
            });
            Feel.sound(getContext(), "swoosh");
        }

        void close() {
            if (closing) return;
            closing = true;
            hideKeyboard();
            Layout.save();
            home.deskChanged();
            float cx = from.centerX() - (card.getLeft() + card.getWidth() / 2f);
            float cy = from.centerY() - (card.getTop() + card.getHeight() / 2f);
            float s0 = Math.max(0.15f, from.width() / Math.max(1f, card.getWidth()));
            Anim.run(k, 0, 280, Anim.EMPH, t -> {
                k = t;
                card.setTranslationX(cx * (1 - t));
                card.setTranslationY(cy * (1 - t));
                float s = s0 + (1 - s0) * t;
                card.setScaleX(s);
                card.setScaleY(s);
                card.setAlpha(Ui.clamp(t * 1.6f));
                invalidate();
            }, () -> {
                if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
                home.folderClosed(this);
            });
        }

        private void hideKeyboard() {
            InputMethodManager im = getContext().getSystemService(InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(getWindowToken(), 0);
            title.clearFocus();
        }

        @Override
        protected void onDraw(Canvas c) {
            Look.backdrop(c, getWidth(), getHeight(), k, m, bp);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            super.onTouchEvent(e);
            return true;
        }

        static Context ctx(View v) {
            return v.getContext();
        }
    }

    private Folders() {}
}
