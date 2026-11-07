package os.vanks.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

/**
 * Обои под всем: фото или обои Delta, которые считает видеокарта.
 *
 * Супер обои: на экране входа — вид издалека, сцена живёт; при входе камера
 * летит к ним (1,7 с) и кадр замирает. Живые: на экране входа видна часть
 * рисунка (lockPose), при входе он доигрывает и замирает. Пока ничего не
 * движется, шейдер не считается: готовый кадр лежит в слое видеокарты. В
 * движении кадр считается в половину разрешения.
 *
 * Когда открыт пункт управления или поиск, поверх проступает размытая копия
 * (frost) — весь экран становится матовым стеклом.
 */
final class WallView extends View {
    static final float LOCK_POSE = 0.3f, INTRO_SECONDS = 6f, FLIGHT_MS = 1700f;

    float frost;   // 0…1: насколько всё матовое
    float dim;     // 0…1: затемнение
    private float zoom = 1f;   // у фото: на экране входа чуть ближе
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();

    private Walls.W spec;
    private RuntimeShader shader;
    private RenderNode node;
    private final Paint sp = new Paint();
    private float time, intro = 1f, introTarget = 1f, flight = 1f;
    private long flightStart = -1;
    private float flightFrom;
    private boolean locked, paused, dirty = true, fullRes;
    private long last, lastRecord;

    private final boolean lockLayer;   // свои обои экрана блокировки

    WallView(Context c) {
        this(c, false);
    }

    WallView(Context c, boolean lockLayer) {
        super(c);
        this.lockLayer = lockLayer;
    }

    private Walls.W wanted() {
        return lockLayer ? Walls.find(Prefs.lockWallpaper) : Walls.current();
    }

    private Bitmap still() {
        return lockLayer && Ui.lockWall != null ? Ui.lockWall : Ui.wall;
    }

    private Bitmap blurred() {
        return lockLayer && Ui.lockBlur != null ? Ui.lockBlur : Ui.wallBlur;
    }

    /** Обои сменились (или первый запуск): собрать шейдер. */
    void reload() {
        if (lockLayer) Ui.loadLockWall(getContext());
        spec = wanted();
        shader = null;
        if (Walls.animated(spec)) {
            try {
                shader = build(getContext(), spec);
                if (node == null) {
                    node = new RenderNode("wall");
                    node.setUseCompositingLayer(true, null);
                }
            } catch (Throwable e) {
                // не собрался на этом телефоне — будет готовый кадр
                shader = null;
            }
        }
        dirty = true;
        invalidate();
    }

    /** Обои сменили в «Настройках». */
    boolean stale() {
        return spec != wanted();
    }

    static RuntimeShader build(Context c, Walls.W w) {
        String src = Walls.source(c, w.frag);
        if (src == null) return null;
        RuntimeShader s = new RuntimeShader(src);
        if (w.colors != null) {
            String[] n = {"colorA", "colorB", "colorC", "colorD"};
            for (int i = 0; i < 4; i++) {
                int col = w.colors[i];
                s.setFloatUniform(n[i], Color.red(col) / 255f, Color.green(col) / 255f, Color.blue(col) / 255f, 1f);
            }
            s.setFloatUniform("material", w.material);
        }
        if (w.frag.equals("moon")) texture(c, s, "moonMap", R.drawable.wall_moon_map, Shader.TileMode.REPEAT);
        if (w.frag.equals("summit")) {
            texture(c, s, "photo", R.drawable.wall_summit_photo, Shader.TileMode.CLAMP);
            texture(c, s, "mask", R.drawable.wall_summit_mask, Shader.TileMode.CLAMP);
        }
        if (w.frag.equals("peak")) {
            texture(c, s, "photo", R.drawable.wall_peak_photo, Shader.TileMode.CLAMP);
            texture(c, s, "mask", R.drawable.wall_peak_mask, Shader.TileMode.CLAMP);
        }
        return s;
    }

    private static void texture(Context c, RuntimeShader s, String name, int res, Shader.TileMode tx) {
        Bitmap b = Walls.texture(c, res);
        BitmapShader bs = new BitmapShader(b, tx, Shader.TileMode.CLAMP);
        bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        s.setInputShader(name, bs);
        s.setFloatUniform(name + "Size", b.getWidth(), b.getHeight());
    }

    /** Задать кадр для превью: живые — доиграли, супер — вблизи. */
    static void uniforms(RuntimeShader s, Walls.W w, float width, float height, float intro, float zoom, float time) {
        s.setFloatUniform("res", width, height);
        s.setFloatUniform("zoom", zoom);
        s.setFloatUniform("time", time);
        if (w.kind == Walls.FLOW) s.setFloatUniform("intro", intro);
    }

    // ── состояния ─────────────────────────────────────────────────────────

    /** Экран погас: вид издалека / поза экрана входа. */
    void lockNow() {
        locked = true;
        flightStart = -1;
        flight = 0;
        zoom = 1.06f;
        // фото на экране входа — размытые, как на компьютере (кроме «глубины»:
        // там гора должна быть чёткой); супер и живые — как есть
        frost = spec != null && spec.kind == Walls.PHOTO && !(Prefs.depth && spec.fg != 0) ? 1f : 0f;
        if (spec != null && spec.kind == Walls.FLOW) {
            intro = Math.min(intro, LOCK_POSE);
            introTarget = LOCK_POSE;
        }
        dirty = true;
        invalidate();
    }

    /** Стоят в позе экрана входа (ждут входа, чтобы доиграть появление). */
    boolean lockPose() {
        return locked;
    }

    /** Вход: камера летит, живые обои доигрывают. */
    void unlock() {
        locked = false;
        flightFrom = flight;
        flightStart = SystemClock.uptimeMillis();
        introTarget = 1;
        Anim.run(zoom, 1f, 850, Anim.PC, z -> {
            zoom = z;
            if (shader == null) invalidate();
        }, null);
        if (frost > 0) Anim.run(frost, 0f, 850, Anim.STD, f -> {
            frost = f;
            invalidate();
        }, null);
        last = SystemClock.uptimeMillis();
        invalidate();
    }

    /** С начала: при выборе обоев и при первом запуске. */
    void playIntro() {
        intro = 0;
        introTarget = locked ? LOCK_POSE : 1;
        if (!locked) flight = 1;
        last = SystemClock.uptimeMillis();
        dirty = true;
        invalidate();
    }

    void setPaused(boolean p) {
        paused = p;
        last = SystemClock.uptimeMillis();
        if (!p) {
            dirty = true;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        dirty = true;
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        // окно снова видно (экран включился) — кадр заново, а не из старой памяти
        if (v == VISIBLE) {
            dirty = true;
            invalidate();
        }
    }

    boolean lockedPhotoZoom() {
        return shader == null && zoom > 1.001f;
    }

    float photoZoom() {
        return zoom;
    }

    // ── рисование ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        boolean drawn = false;
        if (shader != null && node != null && c.isHardwareAccelerated()) {
            try {
                drawShader(c, w, h);
                drawn = true;
            } catch (RuntimeException e) {
                // шейдер не пошёл на этом телефоне — дальше готовый кадр
                shader = null;
            }
        }
        if (!drawn) {
            paint.setAlpha(255);
            Bitmap b = still();
            if (b == null || b.isRecycled()) {
                // картинка ещё не загружена (запуск телефона) — загрузить сейчас
                Ui.loadWall(getContext());
                if (lockLayer) Ui.loadLockWall(getContext());
                b = still();
            }
            if (b != null) c.drawBitmap(b, Ui.wallMatrix(b, w, h, zoom, m), paint);
        }
        if (frost > 0.001f) {
            paint.setAlpha(Math.round(255 * Ui.clamp(frost)));
            Bitmap b = blurred();
            if (b != null && !b.isRecycled()) c.drawBitmap(b, Ui.wallMatrix(b, w, h, 1f, m), paint);
        }
        float d = Ui.clamp(dim + frost * 0.38f);
        if (d > 0.001f) c.drawColor(Ui.alpha(0xFF070A18, d));
    }

    private void drawShader(Canvas c, int w, int h) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        boolean moving = false;
        // полёт камеры
        if (flightStart >= 0) {
            float t = Math.min(1f, (now - flightStart) / FLIGHT_MS);
            float e = 1 - (1 - t) * (1 - t) * (1 - t);
            flight = flightFrom + (1 - flightFrom) * e;
            if (t >= 1) flightStart = -1;
            moving = true;
        }
        // живые обои: доиграть до цели
        if (spec.kind == Walls.FLOW && intro != introTarget) {
            float step = dt / INTRO_SECONDS * (introTarget < intro ? 8 : 1);
            intro = introTarget > intro ? Math.min(introTarget, intro + step) : Math.max(introTarget, intro - step);
            moving = true;
        }
        // время сцены: супер обои живут на экране входа и в полёте; на столе — по настройке
        boolean ticking = !paused && ((spec.kind == Walls.SHADER && (locked || flightStart >= 0)) || (Prefs.wallAnimate && !locked));
        if (ticking) time = (time + dt) % 3600f;
        boolean animating = moving || ticking;
        // кадр обоев живёт в своём RenderNode; когда экран гаснет или памяти
        // мало, Android выбрасывает его вместе с видеопамятью — а сам вид
        // не знает, что надо перерисовать. Без этой проверки — чёрный экран
        boolean lost = !node.hasDisplayList();
        if (lost || animating || dirty || (fullRes != !animating)) {
            // в движении — полразрешения и не чаще 30 кадров, кроме полёта
            if (lost || !animating || moving || now - lastRecord >= 30) {
                record(w, h, !animating);
                lastRecord = now;
            }
        }
        float s = fullRes ? 1f : 0.5f;
        c.save();
        c.scale(w / (float) Math.max(1, Math.round(w * s)), h / (float) Math.max(1, Math.round(h * s)));
        c.drawRenderNode(node);
        c.restore();
        if (animating && !paused) postInvalidateOnAnimation();
    }

    private void record(int w, int h, boolean full) {
        fullRes = full;
        float s = full ? 1f : 0.5f;
        int lw = Math.max(1, Math.round(w * s)), lh = Math.max(1, Math.round(h * s));
        uniforms(shader, spec, lw, lh, intro, flight, time);
        sp.setShader(shader);
        node.setPosition(0, 0, lw, lh);
        RecordingCanvas rc = node.beginRecording(lw, lh);
        rc.drawRect(0, 0, lw, lh, sp);
        node.endRecording();
        dirty = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        dirty = true;
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 33;
    }
}
