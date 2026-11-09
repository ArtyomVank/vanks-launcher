package os.vanks.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Общее оформление: размеры, цвета, шрифты, обои и «стекло» из размытых обоев. */
final class Ui {
    // акценты Delta
    static final int[] ACCENTS = {0xFF7C5CFF, 0xFF2BC4F0, 0xFFE0567F, 0xFF3FB37A, 0xFFF09A2B, 0xFFE5484D, 0xFF4C7DFF, 0xFFC9A227};
    static final String[] ACCENT_NAMES = {"Фиолет", "Лазурь", "Роза", "Зелень", "Апельсин", "Красный", "Синий", "Золото"};

    static float density = 1f;
    static Typeface light, regular, medium, bold;
    static Bitmap wall, wallBlur, wallFg;
    static Bitmap lockWall, lockBlur, lockFg;   // свои обои экрана блокировки
    private static String wallId, lockId;
    private static BitmapShader blurShader;
    private static final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix tmp = new Matrix();

    static void init(Context c) {
        base(c);
        loadWall(c);
    }

    /** Без обоев: для окон, которым они не нужны (камера в своём процессе). */
    static void base(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        LockFace.app = c.getApplicationContext();
        light = Typeface.create(Typeface.SANS_SERIF, 300, false);
        regular = Typeface.create(Typeface.SANS_SERIF, 400, false);
        medium = Typeface.create(Typeface.SANS_SERIF, 500, false);
        bold = Typeface.create(Typeface.SANS_SERIF, 650, false);
        rimPaint.setStrokeWidth(dp(1));
    }

    /** Загрузить обои из настроек, если сменились. true — сменились. */
    static boolean loadWall(Context c) {
        Walls.W w = Walls.current();
        if (w.id.equals(wallId) && wall != null) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        float k = fitScale(c, w.still);
        wall = decode(c, w.still, k);
        wallBlur = Walls.decode(c, w.blur, o);
        wallFg = w.fg != 0 ? decode(c, w.fg, k) : null;
        blurShader = new BitmapShader(wallBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        glassPaint.setShader(blurShader);
        glassPaint.setFilterBitmap(true);
        wallId = w.id;
        Look.seedFrom(wallBlur);
        return true;
    }

    /** Обои экрана блокировки, если там свои. true — сменились. */
    static boolean loadLockWall(Context c) {
        if (Prefs.lockWallpaper.isEmpty()) {
            lockWall = lockBlur = lockFg = null;
            lockId = null;
            return false;
        }
        Walls.W w = Walls.find(Prefs.lockWallpaper);
        if (w.id.equals(lockId) && lockWall != null) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        float k = fitScale(c, w.still);
        lockWall = decode(c, w.still, k);
        lockBlur = Walls.decode(c, w.blur, o);
        lockFg = w.fg != 0 ? decode(c, w.fg, k) : null;
        lockId = w.id;
        return true;
    }

    /**
     * Во сколько уменьшить картинку обоев, чтобы она лишь накрывала экран (с
     * запасом на приближение при разблокировке). Обои 1080×2364 на экране
     * 720×1600 — вдвое больше памяти и работы видеочипу без всякой пользы.
     */
    static float fitScale(Context c, int res) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        b.inScaled = false;
        Walls.decode(c, res, b);
        if (b.outWidth <= 0 || b.outHeight <= 0) return 1;
        int sw, sh;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect r = c.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            sw = r.width();
            sh = r.height();
        } else {
            sw = c.getResources().getDisplayMetrics().widthPixels;
            sh = c.getResources().getDisplayMetrics().heightPixels;
        }
        float s = Math.max(Math.min(sw, sh) / (float) b.outWidth, Math.max(sw, sh) / (float) b.outHeight) * 1.1f;
        return Math.min(1f, s);
    }

    /** Картинка из ресурсов, уменьшенная в k раз прямо при чтении. */
    static Bitmap decode(Context c, int res, float k) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        if (k >= 0.99f) {
            o.inScaled = false;
        } else {
            o.inScaled = true;
            o.inDensity = 1000;
            o.inTargetDensity = Math.round(1000 * k);
        }
        return Walls.decode(c, res, o);
    }

    /** Свои обои на экране блокировки (не те же, что на столе). */
    static boolean ownLockWall() {
        return !Prefs.lockWallpaper.isEmpty() && !Prefs.lockWallpaper.equals(Prefs.wallpaper);
    }

    // ── картинки обоев для выбора: читаются в фоне ───────────────────────

    private static final java.util.concurrent.ExecutorService picLoader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vanks-walls");
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });
    private static final android.util.LruCache<String, Bitmap> pics = new android.util.LruCache<String, Bitmap>(28 << 20) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getAllocationByteCount();
        }
    };
    private static final java.util.HashSet<String> picsLoading = new java.util.HashSet<>();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Картинка обоев для выбора (sample — во сколько раз меньше, alpha — с
     * прозрачностью, как передний план «Вершины»). Готова — вернёт сразу; нет —
     * null, а картинка читается в фоне и потом перерисует view. Раньше каждую
     * читали прямо при листании — выбор обоев дёргался.
     */
    static Bitmap wallPic(Context c, int res, int sample, boolean alpha, android.view.View into) {
        String key = res + "/" + sample;
        Bitmap b = pics.get(key);
        if (b != null || res == 0) return b;
        if (picsLoading.add(key)) {
            Context app = c.getApplicationContext();
            picLoader.execute(() -> {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                o.inSampleSize = sample;
                // без прозрачности — 2 байта на точку: вдвое меньше памяти
                if (!alpha) o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap r;
                try {
                    r = Walls.decode(app, res, o);
                } catch (OutOfMemoryError e) {
                    r = null;
                }
                Bitmap done = r;
                main.post(() -> {
                    picsLoading.remove(key);
                    if (done != null) pics.put(key, done);
                    if (into != null) into.invalidate();
                });
            });
        }
        return null;
    }

    /** Маленькая картинка обоев (сетка, боковые карточки); null — ещё читается. */
    static Bitmap wallThumb(Context c, Walls.W w, android.view.View into) {
        return wallPic(c, w.still, 4, false, into);
    }

    /** Крупная картинка обоев; пока читается — маленькая (или null). */
    static Bitmap wallBig(Context c, Walls.W w, android.view.View into) {
        Bitmap b = wallPic(c, w.still, 2, false, into);
        return b != null ? b : wallThumb(c, w, into);
    }

    /** Выбор обоев закрыт — картинки больше не нужны. */
    static void dropPics() {
        pics.evictAll();
    }

    /** Карточка-заглушка, пока картинка читается. */
    static void picHolder(Canvas c, RectF r, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1C1C22);
        c.drawRoundRect(r, radius, radius, p);
    }

    /** Картинка со скруглёнными углами «по cover» — без clipPath (он дорог видеочипу). */
    static void drawCover(Canvas c, Bitmap b, RectF r, float radius, Paint p, float zoom) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float k = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight()) * zoom;
        Matrix m = new Matrix();
        m.setScale(k, k);
        m.postTranslate(r.centerX() - b.getWidth() * k / 2, r.centerY() - b.getHeight() * k / 2);
        s.setLocalMatrix(m);
        p.setShader(s);
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
    }

    // ── слабый телефон ────────────────────────────────────────────────────

    private static int weak = -1;

    /**
     * Слабый телефон (до 4,5 ГБ памяти — Galaxy A04 и подобные): обои в
     * движении считаются в меньшем разрешении, тяжёлые эффекты по умолчанию выключены.
     */
    static boolean weak(Context c) {
        if (weak < 0) {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            weak = am.isLowRamDevice() || mi.totalMem < (4500L << 20) ? 1 : 0;
        }
        return weak == 1;
    }

    static int accent() { return Prefs.accent | 0xFF000000; }

    static String accentName() {
        for (int i = 0; i < ACCENTS.length; i++) if (ACCENTS[i] == accent()) return ACCENT_NAMES[i];
        return "Свой";
    }
    /** Яркость цвета 0…1. */
    static float luma(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    static int accentLight() { return mix(accent(), Color.WHITE, 0.3f); }
    static int accentDark() { return mix(accent(), Color.BLACK, 0.55f); }

    static int dp(float v) { return Math.round(v * density); }
    static float dpf(float v) { return v * density; }

    /**
     * Прямоугольник внутри вида — в координаты окна, со всеми масштабами и
     * сдвигами вида и его родителей (ужатый ряд дока, стол в глубине).
     * getLocationInWindow сдвигает только начало, размер оставался прежним —
     * окно приложения вырастало и возвращалось мимо значка.
     */
    static android.graphics.RectF toWindow(android.view.View v, android.graphics.RectF local) {
        android.graphics.RectF out = new android.graphics.RectF(local);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            v.transformMatrixToGlobal(m);
            m.mapRect(out);
            return out;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        float sx = v.getScaleX(), sy = v.getScaleY();
        for (android.view.ViewParent p = v.getParent(); p instanceof android.view.View; p = p.getParent()) {
            sx *= ((android.view.View) p).getScaleX();
            sy *= ((android.view.View) p).getScaleY();
        }
        out.set(loc[0] + local.left * sx, loc[1] + local.top * sy, loc[0] + local.right * sx, loc[1] + local.bottom * sy);
        return out;
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int alpha(int c, float a) {
        return (Math.round(Color.alpha(c) * Math.max(0, Math.min(1, a))) << 24) | (c & 0xFFFFFF);
    }

    static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Обои «обложкой» на экран w×h; zoom > 1 — чуть ближе, от центра. */
    static Matrix wallMatrix(Bitmap b, int w, int h, float zoom, Matrix out) {
        return wallMatrix(b.getWidth(), b.getHeight(), w, h, zoom, out);
    }

    static Matrix wallMatrix(int iw, int ih, int w, int h, float zoom, Matrix out) {
        float s = Math.max(w / (float) iw, h / (float) ih) * zoom;
        out.reset();
        out.setScale(s, s);
        out.postTranslate((w - iw * s) / 2f, (h - ih * s) / 2f);
        return out;
    }

    /**
     * Стеклянная плитка: под ней размытые обои ровно с того места экрана, где она
     * стоит (sx, sy — её левый верхний угол на экране), оттенок и светлая кромка.
     */
    static void glass(Canvas c, RectF r, float radius, float sx, float sy, int screenW, int screenH, int tint) {
        wallMatrix(wallBlur, screenW, screenH, 1f, tmp);
        tmp.postTranslate(-sx, -sy);
        blurShader.setLocalMatrix(tmp);
        c.drawRoundRect(r, radius, radius, glassPaint);
        Paint p = rimPaint;
        p.setStyle(Paint.Style.FILL);
        p.setColor(tint);
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(r, radius, radius, p);
    }

    static int screenW() {
        return android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    static Paint text(Typeface tf, float sizeDp, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(dpf(sizeDp));
        p.setColor(color);
        return p;
    }

    private Ui() {}
}
