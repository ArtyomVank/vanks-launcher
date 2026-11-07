package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Что нарисовано на экране блокировки поверх обоев: часы (вид, цвет,
 * размер, ширина, высота и место — как на ПК) и маленькие стеклянные
 * виджеты столбиком справа. Одно и то же рисуют экран блокировки, его
 * редактор и превью в стопке обоев.
 */
final class LockFace {
    static final String[] WIDGETS = {"battery", "calendar", "alarm", "analog"};
    static final String[] WIDGET_NAMES = {"Батарея", "Дата", "Будильник", "Стрелки"};

    static String style() {
        return Prefs.lockClock.isEmpty() ? Look.clock() : Prefs.lockClock;
    }

    static float k() {
        return 1.2f * Prefs.lockClockSize;
    }

    /** Верх часов (без растяжения) на экране w×h. */
    static float top(int h, int insetTop) {
        return insetTop + Ui.dpf(48) + Prefs.lockClockY * h;
    }

    static boolean side() {
        return "side".equals(Prefs.lockWidgetsPos) && !widgets().isEmpty();
    }

    /** Полоса виджетов сбоку: её ширина с полями. */
    static float sideW() {
        return Ui.dpf(64 + 18 + 12);
    }

    static Context app;   // для замера цифр (ставит Ui.init)

    private static final java.util.LinkedHashMap<String, RectF> nats = new java.util.LinkedHashMap<String, RectF>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, RectF> e) {
            return size() > 8;
        }
    };

    static RectF natural() {
        return natural(k());
    }

    /**
     * Где на самом деле нарисованы часы масштаба kk при x = 0, top = 0:
     * рисуем их в четверть размера в пустую картинку и находим края. Меняется
     * раз в минуту или при смене вида и размера.
     */
    static RectF natural(float kk) {
        Context ctx = app;
        String key = style() + "|" + Prefs.posterKind + "|" + Prefs.longDigitW + "|" + Prefs.longStroke + "|" + Math.round(kk * 200) + "|" + (System.currentTimeMillis() / 60000);
        RectF got = nats.get(key);
        if (got != null) return got;
        float sc = 0.25f, ox = Ui.dpf(500), oy = Ui.dpf(20);
        int bw = Math.max(8, Math.round(Ui.dpf(1000) * sc)), bh = Math.max(8, Math.round(Ui.dpf(520) * sc));
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(sc, sc);
        if (ctx != null) ClockView.draw(c, ctx, style(), "white", ox, oy, kk, true);
        int[] px = new int[bw * bh];
        b.getPixels(px, 0, bw, 0, 0, bw, bh);
        b.recycle();
        int l = bw, t = bh, r = -1, btm = -1;
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++)
                if ((px[y * bw + x] >>> 24) > 60) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > btm) btm = y;
                }
        RectF n = new RectF();
        if (r < 0) n.set(-Ui.dpf(120) * kk, 0, Ui.dpf(120) * kk, Ui.dpf(ClockView.heightDp(style())) * kk);
        else n.set(l / sc - ox, t / sc - oy, (r + 1) / sc - ox, (btm + 1) / sc - oy);
        nats.put(key, n);
        return n;
    }

    /** Самая широкая рамка часов: не за край и не на виджеты сбоку. */
    private static float maxW(int w) {
        return side() ? w - sideW() - Ui.dpf(12) : w - Ui.dpf(16);
    }

    /**
     * Рамка часов (как у виджета): ширина и высота — от ползунков и ручек.
     * Цифры тянутся под неё в длину или в ширину (как высокие часы на ПК),
     * дата остаётся обычной.
     */
    private static float boxW(int w) {
        return Math.min(natural().width() * Prefs.lockClockW, maxW(w));
    }

    private static float boxH() {
        return natural().height() * Prefs.lockClockH;
    }

    /** Общий масштаб цифр в рамке (меньший из двух). */
    static float fit(int w) {
        RectF n = natural();
        return Math.min(boxW(w) / Math.max(1f, n.width()), boxH() / Math.max(1f, n.height()));
    }

    /** Насколько часы тянутся сверх общего масштаба: {по ширине, по высоте}, одно из них — 1. */
    static float[] stretch(int w) {
        RectF n = natural();
        float sx = boxW(w) / Math.max(1f, n.width()), sy = boxH() / Math.max(1f, n.height());
        float u = Math.min(sx, sy);
        return new float[]{sx / u, sy / u};
    }

    /** Середина часов (точка привязки). */
    static float centerX(int w) {
        float cx = w * Prefs.lockClockX, half = boxW(w) / 2;
        float right = side() ? w - sideW() : w - Ui.dpf(8);
        cx = Math.min(cx, right - half);
        cx = Math.max(cx, Ui.dpf(8) + half);
        return cx;
    }

    /** Рамка часов на экране — для ручек в редакторе, места виджетов и полёта на стол. */
    static RectF clockRect(int w, int h, int insetTop) {
        float cx = centerX(w), half = boxW(w) / 2;
        float t = top(h, insetTop) + natural().top;
        return new RectF(cx - half, t, cx + half, t + boxH());
    }

    static void drawClock(Canvas c, Context ctx, int w, int h, int insetTop) {
        RectF box = clockRect(w, h, insetTop);
        float[] st = stretch(w);
        drawStretched(c, ctx, Prefs.lockClockColor, box.centerX(), box.centerY(), k() * fit(w), st[0], st[1]);
    }

    /** Масштаб часов на столе («часы переезжают»): той же формы, что на экране блокировки, вписаны в рамку r. */
    private static float homeK(RectF r, float[] st) {
        RectF n1 = natural(1f);
        float kk = Math.min(r.width() / Math.max(1f, n1.width() * st[0]), (r.height() - Ui.dpf(8)) / Math.max(1f, n1.height() * st[1]));
        return Math.max(0.25f, Math.min(kk, 1.6f));
    }

    static RectF homeRect(RectF r) {
        float[] st = stretch(Ui.screenW());
        RectF n = natural(homeK(r, st));
        float ww = n.width() * st[0], hh = n.height() * st[1];
        float top = r.top + Ui.dpf(4);
        return new RectF(r.centerX() - ww / 2, top, r.centerX() + ww / 2, top + hh);
    }

    static void drawHome(Canvas c, Context ctx, String tint, RectF r) {
        float[] st = stretch(Ui.screenW());
        RectF d = homeRect(r);
        drawStretched(c, ctx, tint, d.centerX(), d.centerY(), homeK(r, st), st[0], st[1]);
    }

    private static final java.util.LinkedHashMap<String, android.graphics.Bitmap> cache = new java.util.LinkedHashMap<String, android.graphics.Bitmap>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) {
            return size() > 3;
        }
    };
    private static final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);

    /**
     * Часы масштаба kk, растянутые в ex × ey, серединой в (cx, cy). Растянутые
     * рисуем программно в картинку в настоящем размере: там буквы тянутся
     * по контуру и остаются чёткими (видеокарта тянет готовые буквы — мыло).
     * Картинка меняется раз в минуту.
     */
    static void drawStretched(Canvas c, Context ctx, String tint, float cx, float cy, float kk, float ex, float ey) {
        RectF n = natural(kk);
        if (Math.abs(ex - 1f) < 0.02f && Math.abs(ey - 1f) < 0.02f) {
            ClockView.draw(c, ctx, style(), tint, cx - n.centerX(), cy - n.centerY(), kk, true);
            return;
        }
        if ("long".equals(style())) {
            // «Длинные» тянут свои цифры сами: линия той же толщины, без картинки
            float extra = Ui.dpf(ClockView.LONG_H * kk) * (ey - 1f);
            float hh = n.height() + extra;
            ClockView.stretchX = ex;
            ClockView.stretchY = ey;
            try {
                ClockView.draw(c, ctx, style(), tint, cx - n.centerX() * ex, cy - hh / 2 - n.top, kk, true);
            } finally {
                ClockView.stretchX = 1f;
                ClockView.stretchY = 1f;
            }
            return;
        }
        String key = style() + "|" + Prefs.posterKind + "|" + Prefs.posterFront + "|" + Prefs.posterBack + "|" + tint + "|" + Ui.accent()
                + "|" + Math.round(kk * 400) + "|" + Math.round(ex * 400) + "|" + Math.round(ey * 400) + "|" + (System.currentTimeMillis() / 60000);
        android.graphics.Bitmap b = cache.get(key);
        float pad = Ui.dpf(28);
        float bw = Math.max(n.width() * ex, n.width()) + pad * 2, bh = n.height() * ey + pad * 2;
        if (b == null) {
            b = android.graphics.Bitmap.createBitmap(Math.max(1, Math.round(bw)), Math.max(1, Math.round(bh)), android.graphics.Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(b);
            sc.translate(bw / 2, bh / 2);
            sc.scale(ex, ey);
            ClockView.stretchX = ex;
            ClockView.stretchY = ey;
            try {
                ClockView.draw(sc, ctx, style(), tint, -n.centerX(), -n.centerY(), kk, true);
            } finally {
                ClockView.stretchX = 1f;
                ClockView.stretchY = 1f;
            }
            cache.put(key, b);
        }
        c.drawBitmap(b, cx - b.getWidth() / 2f, cy - b.getHeight() / 2f, bp);
    }

    static List<String> widgets() {
        ArrayList<String> out = new ArrayList<>();
        for (String s : Prefs.lockWidgets.split(",")) if (Arrays.asList(WIDGETS).contains(s) && !out.contains(s)) out.add(s);
        return out;
    }

    /** Плитка i: сбоку — столбиком справа от часов, иначе — рядом под часами. */
    static RectF widgetRect(int i, int w, int h, int insetTop, RectF out) {
        float s = Ui.dpf(64), gap = Ui.dpf(12);
        if (side()) {
            float x = w - Ui.dpf(18) - s, y = top(h, insetTop) + Ui.dpf(8) + i * (s + gap);
            out.set(x, y, x + s, y + s);
            return out;
        }
        int n = Math.max(1, widgets().size());
        float total = n * s + (n - 1) * gap;
        float x = w / 2f - total / 2 + i * (s + gap);
        float y = clockRect(w, h, insetTop).bottom + Ui.dpf(18);
        out.set(x, y, x + s, y + s);
        return out;
    }

    /** Вся зона виджетов (для рамки в редакторе), даже если их нет. */
    static RectF widgetZone(int w, int h, int insetTop) {
        float s = Ui.dpf(64), gap = Ui.dpf(12);
        if ("side".equals(Prefs.lockWidgetsPos)) {
            float x = w - Ui.dpf(18) - s, y = top(h, insetTop) + Ui.dpf(8);
            return new RectF(x - Ui.dpf(6), y - Ui.dpf(6), x + s + Ui.dpf(6), y + 4 * s + 3 * gap + Ui.dpf(6));
        }
        float total = 4 * s + 3 * gap;
        float y = clockRect(w, h, insetTop).bottom + Ui.dpf(18);
        return new RectF(w / 2f - total / 2 - Ui.dpf(6), y - Ui.dpf(6), w / 2f + total / 2 + Ui.dpf(6), y + s + Ui.dpf(6));
    }

    static void drawWidgets(Canvas c, Context ctx, int w, int h, int insetTop) {
        List<String> l = widgets();
        RectF r = new RectF();
        for (int i = 0; i < l.size(); i++) drawWidget(c, ctx, l.get(i), widgetRect(i, w, h, insetTop, r));
    }

    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Маленькая стеклянная плитка, как на экране блокировки ColorOS. */
    static void drawWidget(Canvas c, Context ctx, String kind, RectF r) {
        float rad = r.width() * 0.3f;
        p.reset();
        p.setAntiAlias(true);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dpf(1));
        p.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, 0x80FFFFFF, 0x14FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        float cx = r.centerX(), cy = r.centerY(), s = r.width();
        switch (kind) {
            case "battery": {
                int lv = VWidgets.battery;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(s * 0.07f);
                p.setColor(0x40FFFFFF);
                RectF a = new RectF(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f);
                c.drawArc(a, 135, 270, false, p);
                p.setColor(0xFFFFFFFF);
                c.drawArc(a, 135, 270 * Math.max(0, lv) / 100f, false, p);
                Paint t = Ui.text(Ui.bold, s / Ui.density * 0.24f, 0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText(lv < 0 ? "—" : String.valueOf(lv), cx, cy + s * 0.08f, t);
                if (VWidgets.charging) Glyph.bolt(c, cx, cy + s * 0.3f, s * 0.18f, 0xFFFFFFFF);
                break;
            }
            case "calendar": {
                Locale ru = new Locale("ru");
                String wd = new SimpleDateFormat("EE", ru).format(new Date()).toUpperCase(ru);
                Paint a = Ui.text(Ui.medium, s / Ui.density * 0.16f, 0xD9FFFFFF);
                a.setTextAlign(Paint.Align.CENTER);
                c.drawText(wd, cx, cy - s * 0.12f, a);
                Paint b = Ui.text(Ui.bold, s / Ui.density * 0.34f, 0xFFFFFFFF);
                b.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)), cx, cy + s * 0.24f, b);
                break;
            }
            case "alarm": {
                Glyph.alarm(c, cx, cy - s * 0.1f, s * 0.42f, 0xFFFFFFFF);
                String al = ClockView.nextAlarm(ctx);
                String tm = al == null ? "—" : al.replaceAll("^\\S+\\s", "");
                Paint t = Ui.text(Ui.medium, s / Ui.density * 0.17f, 0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText(tm, cx, cy + s * 0.3f, t);
                break;
            }
            default: {   // стрелки
                Calendar cal = Calendar.getInstance();
                float min = cal.get(Calendar.MINUTE), hr = cal.get(Calendar.HOUR) + min / 60f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(s * 0.06f);
                double ah = Math.PI * 2 * hr / 12, am = Math.PI * 2 * min / 60;
                c.drawLine(cx, cy, cx + (float) Math.sin(ah) * s * 0.17f, cy - (float) Math.cos(ah) * s * 0.17f, p);
                p.setStrokeWidth(s * 0.04f);
                c.drawLine(cx, cy, cx + (float) Math.sin(am) * s * 0.27f, cy - (float) Math.cos(am) * s * 0.27f, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.accentLight());
                c.drawCircle(cx, cy, s * 0.05f, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    // ── отпечаток ─────────────────────────────────────────────────────────

    static final String[] FP_IDS = {"classic", "glass", "bare", "ring", "photo"};
    static final String[] FP_NAMES = {"Обычный", "Стекло", "Без круга", "Кольцо", "Фото"};

    private static android.graphics.Bitmap fpPhoto;
    private static boolean fpTried;

    /** Своё фото для отпечатка лежит в files/fp.png. */
    static java.io.File fpFile(Context c) {
        return new java.io.File(c.getFilesDir(), "fp.png");
    }

    static void fpChanged() {
        fpPhoto = null;
        fpTried = false;
    }

    private static android.graphics.Bitmap photo() {
        if (!fpTried && app != null) {
            fpTried = true;
            java.io.File f = fpFile(app);
            if (f.exists()) fpPhoto = android.graphics.BitmapFactory.decodeFile(f.getPath());
        }
        return fpPhoto;
    }

    /** Отпечаток на экране блокировки в выбранном виде. acc — цвет (красный при ошибке). */
    static void drawFinger(Canvas c, float cx, float cy, int acc) {
        float r = Ui.dpf(36);
        String kind = Prefs.fpIcon;
        android.graphics.Bitmap ph = "photo".equals(kind) ? photo() : null;
        if ("photo".equals(kind) && ph == null) kind = "classic";
        p.reset();
        p.setAntiAlias(true);
        switch (kind) {
            case "photo": {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                c.drawBitmap(ph, null, new RectF(cx - r, cy - r, cx + r, cy + r), bp);
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(2));
                p.setColor(Ui.alpha(acc, 0.9f));
                c.drawCircle(cx, cy, r, p);
                break;
            }
            case "glass": {
                float g = r * 1.12f;
                p.setShader(new android.graphics.LinearGradient(0, cy - g, 0, cy + g, 0x4DFFFFFF, 0x14FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, g, p);
                p.setShader(new android.graphics.LinearGradient(0, cy - g, 0, cy + g, 0xCCFFFFFF, 0x26FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1.4f));
                c.drawCircle(cx, cy, g, p);
                p.setShader(null);
                Glyph.fingerprint(c, cx, cy, Ui.dpf(46), 0xFFFFFFFF, 1f);
                break;
            }
            case "bare":
                Glyph.fingerprint(c, cx, cy, Ui.dpf(56), acc, 1f);
                break;
            case "ring":
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(2.4f));
                p.setColor(acc);
                c.drawCircle(cx, cy, r, p);
                Glyph.fingerprint(c, cx, cy, Ui.dpf(34), 0xFFFFFFFF, 1f);
                break;
            default:
                p.setColor(0x33000000);
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1));
                p.setColor(0x55FFFFFF);
                c.drawCircle(cx, cy, r, p);
                Glyph.fingerprint(c, cx, cy, Ui.dpf(44), acc, 1f);
        }
        p.reset();
        p.setAntiAlias(true);
    }

    private LockFace() {}
}
