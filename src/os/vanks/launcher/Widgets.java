package os.vanks.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Виджеты на столе: свои (VWidgets) и виджеты приложений Android
 * (AppWidgetHost). Чтобы поставить чужой виджет, Android спрашивает
 * разрешение (один раз для лаунчера), а некоторые виджеты сначала
 * открывают свою настройку.
 */
final class Widgets {
    static final int REQ_BIND = 41, REQ_CONFIG = 42;
    private static final int HOST_ID = 0x7a4e;

    static float cellW = 1, cellH = 1;   // клетка стола в пикселях (ставит Desk)

    private static AppWidgetHost host;
    private static AppWidgetManager awm;
    private static final HashMap<Integer, AppWidgetHostView> views = new HashMap<>();

    // ожидающий виджет: пока Android спрашивает разрешение или идёт настройка
    private static int pendingId = -1;
    private static AppWidgetProviderInfo pendingInfo;

    static void init(Context c) {
        if (host != null) return;
        Context app = c.getApplicationContext();
        awm = AppWidgetManager.getInstance(app);
        host = new AppWidgetHost(app, HOST_ID);
    }

    static void start() {
        try { host.startListening(); } catch (Exception ignored) {}
    }

    static void stop() {
        try { host.stopListening(); } catch (Exception ignored) {}
    }

    /** Вид виджета приложения (один на виджет — переносится между пересборками стола). */
    static View view(Context c, Layout.Item it) {
        if (it.widgetId < 0 || host == null) return null;
        AppWidgetHostView v = views.get(it.widgetId);
        if (v == null) {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(it.widgetId);
            if (info == null) return null;
            v = host.createView(c.getApplicationContext(), it.widgetId, info);
            v.setPadding(0, 0, 0, 0);
            views.put(it.widgetId, v);
        }
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    /** Пределы размера в клетках: {minW, minH, maxW, maxH}. */
    static int[] limits(Context c, Layout.Item it) {
        int cols = Layout.cols(), rows = Layout.rows;
        if (Layout.FOLDER.equals(it.type)) return new int[]{1, 1, Math.min(4, cols), Math.min(4, rows)};
        if (!"app".equals(it.kind)) {
            VWidgets.Type t = VWidgets.type(it.kind);
            return new int[]{t.minW, t.minH, Math.min(t.maxW, cols), Math.min(t.maxH, rows)};
        }
        AppWidgetProviderInfo info = awm == null ? null : awm.getAppWidgetInfo(it.widgetId);
        if (info == null) return new int[]{it.w, it.h, it.w, it.h};
        int minW = it.w, minH = it.h, maxW = it.w, maxH = it.h;
        if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) {
            minW = cells(info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth, cellW);
            maxW = cols;
            if (Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0) maxW = Math.max(minW, Math.min(cols, cells(info.maxResizeWidth, cellW)));
        }
        if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) {
            minH = cells(info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight, cellH);
            maxH = rows;
            if (Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) maxH = Math.max(minH, Math.min(rows, cells(info.maxResizeHeight, cellH)));
        }
        return new int[]{Math.min(minW, it.w), Math.min(minH, it.h), Math.max(maxW, it.w), Math.max(maxH, it.h)};
    }

    private static int cells(int px, float cell) {
        return Math.max(1, (int) Math.ceil((px - cell * 0.15f) / Math.max(1f, cell)));
    }

    /** Сказать виджету его новый размер — он может перестроиться. */
    static void sizeChanged(Context c, Layout.Item it, int wPx, int hPx) {
        if (!"app".equals(it.kind) || awm == null) return;
        AppWidgetHostView v = views.get(it.widgetId);
        float d = Ui.density;
        int w = Math.round(wPx / d), h = Math.round(hPx / d);
        try {
            if (Build.VERSION.SDK_INT >= 31 && v != null) {
                ArrayList<SizeF> sizes = new ArrayList<>();
                sizes.add(new SizeF(w, h));
                v.updateAppWidgetSize(new Bundle(), sizes);
            } else {
                Bundle o = new Bundle();
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w);
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w);
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h);
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h);
                awm.updateAppWidgetOptions(it.widgetId, o);
            }
        } catch (Exception ignored) {
        }
    }

    static void delete(Layout.Item it) {
        if (it.widgetId < 0 || host == null) return;
        views.remove(it.widgetId);
        try { host.deleteAppWidgetId(it.widgetId); } catch (Exception ignored) {}
    }

    /** Убрать с хоста виджеты, которых на столе больше нет (остались от сбоев). */
    static void prune() {
        if (host == null) return;
        try {
            int[] ids = host.getAppWidgetIds();
            for (int id : ids) {
                boolean used = false;
                for (Layout.Item it : Layout.items) if (it.widgetId == id) { used = true; break; }
                if (!used && id != pendingId) host.deleteAppWidgetId(id);
            }
        } catch (Exception ignored) {
        }
    }

    // ── добавление ────────────────────────────────────────────────────────

    /** Поставить виджет приложения: разрешение → настройка → на стол. */
    static void add(Home h, AppWidgetProviderInfo info) {
        init(h);
        int id = host.allocateAppWidgetId();
        pendingId = id;
        pendingInfo = info;
        boolean ok;
        try {
            ok = awm.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null);
        } catch (Exception e) {
            ok = false;
        }
        if (ok) configure(h);
        else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            try {
                h.startActivityForResult(i, REQ_BIND);
            } catch (Exception e) {
                cancel();
                h.toast("Android не дал поставить виджет");
            }
        }
    }

    private static void configure(Home h) {
        if (pendingInfo != null && pendingInfo.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(h, pendingId, 0, REQ_CONFIG, null);
                return;
            } catch (Exception ignored) {
                // настройка не открылась — ставим как есть
            }
        }
        place(h);
    }

    private static void place(Home h) {
        AppWidgetProviderInfo info = pendingInfo;
        Layout.Item it = Layout.widget("app", 2, 2);
        it.widgetId = pendingId;
        it.w = defaultW(info);
        it.h = defaultH(info);
        pendingId = -1;
        pendingInfo = null;
        h.addWidget(it);
    }

    static int defaultW(AppWidgetProviderInfo info) {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0) return Math.min(Layout.cols(), info.targetCellWidth);
        return Math.min(Layout.cols(), cells(info.minWidth, cellW));
    }

    static int defaultH(AppWidgetProviderInfo info) {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0) return Math.min(Layout.rows, info.targetCellHeight);
        return Math.min(Layout.rows, cells(info.minHeight, cellH));
    }

    private static void cancel() {
        if (pendingId >= 0 && host != null) {
            try { host.deleteAppWidgetId(pendingId); } catch (Exception ignored) {}
        }
        pendingId = -1;
        pendingInfo = null;
    }

    /** Ответ Android на «разрешить?» и настройки виджета. true — это был наш запрос. */
    static boolean onResult(Home h, int req, int res) {
        if (req != REQ_BIND && req != REQ_CONFIG) return false;
        if (pendingId < 0) return true;
        if (res != Activity.RESULT_OK) {
            cancel();
            return true;
        }
        if (req == REQ_BIND) configure(h);
        else place(h);
        return true;
    }

    // ── выбор виджета ─────────────────────────────────────────────────────

    /**
     * Лист «Виджеты» снизу: сначала свои (Vanks) с живым видом, ниже —
     * виджеты приложений по приложениям, с картинкой-превью и размером.
     */
    static final class Picker extends FrameLayout {
        private final Home home;
        private final LinearLayout sheet;
        private float k;
        private boolean closing;

        Picker(Home h) {
            super(h);
            home = h;
            init(h);
            setClickable(true);
            setOnClickListener(v -> close());
            setBackgroundColor(0);

            sheet = new LinearLayout(h) {
                @Override
                protected void onDraw(Canvas c) {
                    RectF r = new RectF(0, 0, getWidth(), getHeight() + Ui.dpf(40));
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Ui.alpha(Look.surface(), 0.97f));
                    float rad = Look.radius(28);
                    c.drawRoundRect(r, rad, rad, p);
                    p.setColor(Ui.alpha(Look.fg(), 0.25f));
                    c.drawRoundRect(getWidth() / 2f - Ui.dpf(18), Ui.dpf(8), getWidth() / 2f + Ui.dpf(18), Ui.dpf(12), Ui.dpf(2), Ui.dpf(2), p);
                }
            };
            sheet.setWillNotDraw(false);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setClickable(true);
            sheet.setPadding(0, Ui.dp(22), 0, Ui.dp(10));

            TextView title = Views.label(h, "Виджеты", 22, Ui.bold, Look.fg());
            title.setPadding(Ui.dp(22), 0, Ui.dp(22), Ui.dp(10));
            sheet.addView(title);

            LinearLayout list = new LinearLayout(h);
            list.setOrientation(LinearLayout.VERTICAL);
            ScrollView sv = new ScrollView(h);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(list);
            sheet.addView(sv, new LinearLayout.LayoutParams(-1, -2));

            // свои
            VWidgets.sample(h);
            list.addView(section(h, "Vanks"));
            LinearLayout own = new LinearLayout(h);
            own.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(6));
            for (VWidgets.Type t : VWidgets.TYPES) {
                own.addView(ownTile(h, t));
            }
            HorizontalScrollView hs = new HorizontalScrollView(h);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(own);
            list.addView(hs);

            // приложений: список и картинки грузятся в фоне (раньше лист ждал их
            // все по 5–10 секунд), лист открывается сразу
            TextView wait = Views.label(h, "Загружаю виджеты приложений…", 14, Ui.regular, Look.fg2());
            wait.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(10));
            list.addView(wait);
            Runnable fill = () -> {
                list.removeView(wait);
                if (cached.isEmpty()) {
                    TextView none = Views.label(h, "Виджетов приложений нет", 14, Ui.regular, Look.fg2());
                    none.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(10));
                    list.addView(none);
                }
                for (AppGroup g : cached) {
                    list.addView(section(h, g.label));
                    LinearLayout row = new LinearLayout(h);
                    row.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(6));
                    for (int i = 0; i < g.infos.size(); i++) row.addView(appTile(h, g.infos.get(i), g.names.get(i), g.previews.get(i)));
                    HorizontalScrollView rs = new HorizontalScrollView(h);
                    rs.setHorizontalScrollBarEnabled(false);
                    rs.addView(row);
                    list.addView(rs);
                }
            };
            if (cachedAt > 0 && android.os.SystemClock.elapsedRealtime() - cachedAt < 10 * 60_000) post(fill);
            else {
                Context app = h.getApplicationContext();
                new Thread(() -> {
                    List<AppGroup> got = load(app);
                    post(() -> {
                        cached = got;
                        cachedAt = android.os.SystemClock.elapsedRealtime();
                        fill.run();
                    });
                }, "widgets-load").start();
            }

            LayoutParams lp = new LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.topMargin = Ui.dp(90);
            addView(sheet, lp);
        }

        private static TextView section(Context c, String s) {
            TextView t = Views.label(c, s, 14, Ui.medium, Look.fg2());
            t.setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(8));
            return t;
        }

        private View ownTile(Context c, VWidgets.Type t) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            Layout.Item demo = Layout.widget(t.kind, t.w, t.h);
            demo.style = "";
            View preview = new View(c) {
                @Override
                protected void onDraw(Canvas cv) {
                    RectF r = new RectF(0, 0, getWidth(), getHeight());
                    Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                    bg.setColor(Look.light() ? 0x14000000 : 0x26FFFFFF);
                    cv.drawRoundRect(r, Ui.dpf(18), Ui.dpf(18), bg);
                    // своему виджету нужен тёмный фон под белые цифры часов
                    if (t.kind.equals("clock")) {
                        bg.setColor(0xFF1C1D26);
                        cv.drawRoundRect(r, Ui.dpf(18), Ui.dpf(18), bg);
                    }
                    VWidgets.draw(cv, demo, r, this);
                }
            };
            float pw = Ui.dpf(t.w >= 4 ? 210 : 120), ph = Ui.dpf(t.w >= 4 ? 105 : 120);
            col.addView(preview, new LinearLayout.LayoutParams(Math.round(pw), Math.round(ph)));
            col.addView(caption(c, t.name, t.w + " × " + t.h));
            col.setOnClickListener(v -> {
                Feel.buzz(v, Feel.CONFIRM);
                Layout.Item it = Layout.widget(t.kind, Math.min(t.w, Layout.cols()), t.h);
                close();
                home.addWidget(it);
            });
            return col;
        }

        /** Виджеты одного приложения, уже с названиями и картинками. */
        static final class AppGroup {
            String label;
            final ArrayList<AppWidgetProviderInfo> infos = new ArrayList<>();
            final ArrayList<String> names = new ArrayList<>();
            final ArrayList<android.graphics.Bitmap> previews = new ArrayList<>();
        }

        static List<AppGroup> cached = new ArrayList<>();
        static long cachedAt;

        /** Загрузить список заранее, в фоне — чтобы лист открывался сразу и в первый раз. */
        static void preload(Context c) {
            Context app = c.getApplicationContext();
            android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
            new Thread(() -> {
                List<AppGroup> got = load(app);
                ui.post(() -> {
                    cached = got;
                    cachedAt = android.os.SystemClock.elapsedRealtime();
                });
            }, "widgets-preload").start();
        }

        /** Сбросить запомненный список (поставили или удалили приложение). */
        static void forget() {
            cachedAt = 0;
        }

        /** В фоне: виджеты всех приложений, названия и маленькие картинки. */
        private static List<AppGroup> load(Context c) {
            PackageManager pm = c.getPackageManager();
            ArrayList<AppGroup> out = new ArrayList<>();
            // не больше 300 × 240 точек: десятки картинок не забивают память
            int pw = Math.min(Ui.dp(150), 300), ph = Math.min(Ui.dp(120), 240);
            for (java.util.Map.Entry<String, List<AppWidgetProviderInfo>> e : providers(c).entrySet()) {
                AppGroup g = new AppGroup();
                g.label = e.getKey();
                for (AppWidgetProviderInfo info : e.getValue()) {
                    Drawable d = null;
                    try { d = info.loadPreviewImage(c, 0); } catch (Exception ignored) {}
                    if (d == null) try { d = info.loadIcon(c, 0); } catch (Exception ignored) {}
                    android.graphics.Bitmap b = null;
                    if (d != null) {
                        // уменьшенная копия: большие картинки не тормозят прокрутку
                        int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
                        float sc = Math.min(1f, Math.min(pw / (float) iw, ph / (float) ih));
                        int bw = Math.max(1, Math.round(iw * sc)), bh = Math.max(1, Math.round(ih * sc));
                        if (d.getIntrinsicWidth() <= 0) { bw = pw / 2; bh = pw / 2; }
                        try {
                            b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                            d.setBounds(0, 0, bw, bh);
                            d.draw(new Canvas(b));
                        } catch (Exception ex) {
                            b = null;
                        }
                    }
                    String name;
                    try { name = info.loadLabel(pm); } catch (Exception ex) { name = "Виджет"; }
                    g.infos.add(info);
                    g.names.add(name);
                    g.previews.add(b);
                }
                out.add(g);
            }
            return out;
        }

        private View appTile(Context c, AppWidgetProviderInfo info, String name, android.graphics.Bitmap preview) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackground(Views.round(Look.light() ? 0x14000000 : 0x26FFFFFF, 18));
            iv.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
            if (preview != null) iv.setImageBitmap(preview);
            col.addView(iv, new LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(120)));
            col.addView(caption(c, name, defaultW(info) + " × " + defaultH(info)));
            col.setOnClickListener(v -> {
                Feel.buzz(v, Feel.CONFIRM);
                close();
                add(home, info);
            });
            return col;
        }

        private static View caption(Context c, String name, String size) {
            LinearLayout l = new LinearLayout(c);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setGravity(Gravity.CENTER_HORIZONTAL);
            l.setPadding(0, Ui.dp(6), 0, Ui.dp(4));
            TextView a = Views.label(c, name, 13, Ui.medium, Look.fg());
            a.setMaxWidth(Ui.dp(150));
            a.setSingleLine(true);
            a.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.addView(a);
            l.addView(Views.label(c, size, 12, Ui.regular, Look.fg2()));
            return l;
        }

        private static LinkedHashMap<String, List<AppWidgetProviderInfo>> providers(Context c) {
            PackageManager pm = c.getPackageManager();
            ArrayList<AppWidgetProviderInfo> all = new ArrayList<>();
            try { all.addAll(awm.getInstalledProviders()); } catch (Exception ignored) {}
            HashMap<String, String> labels = new HashMap<>();
            for (AppWidgetProviderInfo i : all) {
                String pkg = i.provider.getPackageName();
                if (labels.containsKey(pkg)) continue;
                try {
                    labels.put(pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
                } catch (Exception e) {
                    labels.put(pkg, pkg);
                }
            }
            Collator col = Collator.getInstance(new Locale("ru"));
            all.sort((a, b) -> col.compare(labels.get(a.provider.getPackageName()), labels.get(b.provider.getPackageName())));
            LinkedHashMap<String, List<AppWidgetProviderInfo>> out = new LinkedHashMap<>();
            for (AppWidgetProviderInfo i : all) {
                String l = labels.get(i.provider.getPackageName());
                List<AppWidgetProviderInfo> g = out.get(l);
                if (g == null) out.put(l, g = new ArrayList<>());
                g.add(i);
            }
            return out;
        }

        void open() {
            sheet.post(() -> {
                float h0 = sheet.getHeight();
                Anim.spring(0, 1, 0, 260, 0.86f, t -> {
                    k = t;
                    sheet.setTranslationY(h0 * (1 - t));
                    setBackgroundColor(Ui.alpha(0xFF000000, 0.35f * Ui.clamp(t)));
                }, null);
            });
            Feel.sound(getContext(), "swoosh");
        }

        void close() {
            if (closing) return;
            closing = true;
            float h0 = sheet.getHeight();
            Anim.run(k, 0, 260, Anim.EMPH, t -> {
                sheet.setTranslationY(h0 * (1 - t));
                setBackgroundColor(Ui.alpha(0xFF000000, 0.35f * Ui.clamp(t)));
            }, () -> {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
                home.pickerClosed(this);
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            super.onTouchEvent(e);
            return true;
        }
    }

    // ── виды виджета ──────────────────────────────────────────────────────

    /** Вид: вариант оформления и размер. */
    static final class Variant {
        final String name, style;
        final int w, h;

        Variant(String name, String style, int w, int h) {
            this.name = name;
            this.style = style;
            this.w = w;
            this.h = h;
        }
    }

    static List<Variant> variants(Context c, Layout.Item it) {
        ArrayList<Variant> out = new ArrayList<>();
        int cols = Layout.cols(), rows = Layout.rows;
        switch (it.kind == null ? "" : it.kind) {
            case "clock":
                out.add(new Variant("Обычные", "", it.w, it.h));
                for (int i = 0; i < ClockView.STYLES.length; i++) out.add(new Variant(ClockView.STYLE_NAMES[i], ClockView.STYLES[i], it.w, it.h));
                break;
            case "calendar":
                out.add(new Variant("День", "", 2, 2));
                out.add(new Variant("Месяц", "", Math.min(4, cols), Math.min(3, rows)));
                out.add(new Variant("Месяц крупно", "", Math.min(4, cols), Math.min(4, rows)));
                break;
            case "battery":
                out.add(new Variant("Кольцо", "", 2, 2));
                out.add(new Variant("Полоса", "", 2, 1));
                out.add(new Variant("Широкая полоса", "", Math.min(4, cols), 1));
                break;
            case "music":
                out.add(new Variant("Короткий", "", 2, 2));
                out.add(new Variant("Строка", "", Math.min(4, cols), 1));
                out.add(new Variant("Длинный", "", Math.min(4, cols), 2));
                break;
            case "timer":
                out.add(new Variant("Маленький", "", 2, 2));
                out.add(new Variant("Средний", "", Math.min(3, cols), 3));
                break;
            case "net":
                out.add(new Variant("Числа", "", 2, 2));
                out.add(new Variant("Строка", "", Math.min(4, cols), 1));
                out.add(new Variant("График", "", Math.min(4, cols), 2));
                break;
            case "memory":
            case "storage":
                out.add(new Variant("Кольцо", "", 2, 2));
                out.add(new Variant("Полоса", "", Math.min(4, cols), 1));
                break;
            case "analog":
                out.add(new Variant("Маленькие", "", 2, 2));
                out.add(new Variant("Средние", "", 3, 3));
                out.add(new Variant("Большие", "", Math.min(4, cols), Math.min(4, rows)));
                break;
            default: {
                int[] lim = limits(c, it);
                int[][] sizes = {{2, 1}, {2, 2}, {3, 2}, {4, 1}, {4, 2}, {4, 3}, {4, 4}};
                for (int[] sz : sizes)
                    if (sz[0] >= lim[0] && sz[0] <= lim[2] && sz[1] >= lim[1] && sz[1] <= lim[3])
                        out.add(new Variant(sz[0] + " × " + sz[1], "", sz[0], sz[1]));
                if (out.isEmpty()) out.add(new Variant(it.w + " × " + it.h, "", it.w, it.h));
            }
        }
        return out;
    }

    /**
     * Лист снизу со всеми видами виджета: у часов — все часы Delta, у
     * календаря — день и месяц, у батареи — кольцо и полоса, у виджетов
     * приложений — размеры. Нажатие — виджет сразу становится таким.
     */
    static final class Variants extends FrameLayout {
        private final Home home;
        private final Layout.Item item;
        private final LinearLayout sheet;
        private float k;
        private boolean closing;

        Variants(Home h, Layout.Item item) {
            super(h);
            home = h;
            this.item = item;
            setClickable(true);
            setOnClickListener(v -> close());
            sheet = new LinearLayout(h) {
                @Override
                protected void onDraw(Canvas c) {
                    RectF r = new RectF(0, 0, getWidth(), getHeight() + Ui.dpf(40));
                    float rad = Look.radius(28);
                    View root = getRootView();
                    int[] l = new int[2];
                    getLocationInWindow(l);
                    Look.panel(c, r, rad, l[0], l[1], root.getWidth(), root.getHeight());
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Ui.alpha(Look.fg(), 0.25f));
                    c.drawRoundRect(getWidth() / 2f - Ui.dpf(18), Ui.dpf(8), getWidth() / 2f + Ui.dpf(18), Ui.dpf(12), Ui.dpf(2), Ui.dpf(2), p);
                }
            };
            sheet.setWillNotDraw(false);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setClickable(true);
            sheet.setPadding(0, Ui.dp(22), 0, Ui.dp(18));
            String title = "app".equals(item.kind) ? "Размер виджета" : "Виды: " + VWidgets.type(item.kind).name.toLowerCase(new Locale("ru"));
            TextView t = Views.label(h, title, 18, Ui.bold, Look.fg());
            t.setPadding(Ui.dp(22), 0, Ui.dp(22), Ui.dp(12));
            sheet.addView(t);
            LinearLayout row = new LinearLayout(h);
            row.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            for (Variant v : variants(h, item)) row.addView(tile(h, v));
            HorizontalScrollView hs = new HorizontalScrollView(h);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(row);
            sheet.addView(hs);
            addView(sheet, new LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        private boolean current(Variant v) {
            String st = "clock".equals(item.kind) && Prefs.clockToHome ? Prefs.lockClock : (item.style == null ? "" : item.style);
            return v.w == item.w && v.h == item.h && v.style.equals(st);
        }

        private View tile(Context c, Variant v) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            float aspect = Math.max(0.5f, Math.min(2.2f, v.w * cellW / Math.max(1f, v.h * cellH)));
            int ph = Ui.dp(96), pw = Math.round(ph * aspect);
            Layout.Item demo = Layout.widget(item.kind, v.w, v.h);
            demo.style = v.style;
            View preview = new View(c) {
                @Override
                protected void onDraw(Canvas cv) {
                    RectF r = new RectF(Ui.dpf(2), Ui.dpf(2), getWidth() - Ui.dpf(2), getHeight() - Ui.dpf(2));
                    Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                    bg.setColor(0xFF20222C);
                    cv.drawRoundRect(r, Ui.dpf(16), Ui.dpf(16), bg);
                    if ("clock".equals(item.kind)) {
                        String st = v.style.isEmpty() ? Look.clock() : v.style;
                        float kk = Math.min((r.height() - Ui.dpf(10)) / Ui.dpf(ClockView.heightDp(st)), r.width() / Ui.dpf(300));
                        cv.save();
                        cv.clipRect(r);
                        ClockView.draw(cv, getContext(), st, Prefs.lockClockColor, r.centerX(), r.top + Ui.dpf(4), kk, true);
                        cv.restore();
                    } else if ("app".equals(item.kind)) {
                        Paint tp = Ui.text(Ui.bold, 18, 0xCCFFFFFF);
                        tp.setTextAlign(Paint.Align.CENTER);
                        cv.drawText(v.w + " × " + v.h, r.centerX(), r.centerY() + Ui.dpf(6), tp);
                    } else VWidgets.draw(cv, demo, r, this);
                    if (current(v)) {
                        bg.setStyle(Paint.Style.STROKE);
                        bg.setStrokeWidth(Ui.dpf(2.5f));
                        bg.setColor(Ui.accent());
                        cv.drawRoundRect(r, Ui.dpf(16), Ui.dpf(16), bg);
                    }
                }
            };
            col.addView(preview, new LinearLayout.LayoutParams(pw, ph));
            TextView a = Views.label(c, v.name, 13, Ui.medium, Look.fg());
            a.setPadding(0, Ui.dp(6), 0, 0);
            col.addView(a);
            col.addView(Views.label(c, v.w + " × " + v.h, 12, Ui.regular, Look.fg2()));
            col.setOnClickListener(x -> {
                Feel.buzz(x, Feel.CONFIRM);
                home.applyVariant(item, v);
                close();
            });
            return col;
        }

        void open() {
            sheet.post(() -> {
                float h0 = sheet.getHeight();
                Anim.spring(0, 1, 0, 260, 0.86f, t -> {
                    k = t;
                    sheet.setTranslationY(h0 * (1 - t));
                }, null);
            });
            Feel.sound(getContext(), "swoosh");
        }

        void close() {
            if (closing) return;
            closing = true;
            float h0 = sheet.getHeight();
            Anim.run(k, 0, 240, Anim.EMPH, t -> sheet.setTranslationY(h0 * (1 - t)), () -> {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
                home.variantsClosed(this);
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            super.onTouchEvent(e);
            return true;
        }
    }

    private Widgets() {}
}
