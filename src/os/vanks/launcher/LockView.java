package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Экран входа: часы за горой (эффект глубины, если у обоев он есть), внизу — отпечаток пальца.
 * Касание или свайп вверх вызывает системное окно отпечатка.
 *
 * Уход — как разблокировка Delta на компьютере (lock/LockSurface.qml, ~850 мс,
 * кривая .65,0,.3,1): часы улетают вверх и сжимаются, отпечаток уезжает вниз,
 * затемнение тает; по экрану в это время идёт стеклянная волна.
 */
final class LockView extends View {
    interface Listener { void unlockRequested(); }

    Listener listener;
    /** Двойное касание экрана блокировки. */
    Runnable onDoubleTap;
    private long lastTapAt;
    private float lastTapX, lastTapY, downX;
    String msg = "Коснитесь, чтобы войти по отпечатку";
    boolean error;
    /** Есть датчик отпечатка: без него значка нет, подпись — внизу экрана. */
    boolean sensor = true;
    int insetTop, insetBottom;
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hint = Ui.text(Ui.medium, 14, 0xD9FFFFFF);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final LightingColorFilter dim = new LightingColorFilter(0xFFD1D1D1, 0);
    private float downY;
    private long exitAt = -1;
    private Runnable exitDone;
    private static final android.view.animation.PathInterpolator CURVE = new android.view.animation.PathInterpolator(0.65f, 0f, 0.3f, 1f);

    LockView(Context c) {
        super(c);
        hint.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        img.setColorFilter(dim);
    }

    /** Куда летят часы при входе (рамка часов на столе); null — улетают вверх, как на ПК. */
    android.graphics.RectF flyTo;

    /** Разблокировка: проиграть уход, потом done. */
    void playExit(Runnable done) {
        exitAt = SystemClock.uptimeMillis();
        exitDone = done;
        invalidate();
    }

    /** Снова экран входа (после блокировки). */
    void reset() {
        exitAt = -1;
        exitDone = null;
        held = false;
        downAt = upAt = -1;
        invalidate();
    }

    private static float inOutQuad(float x) {
        x = Ui.clamp(x);
        return x < 0.5f ? 2 * x * x : 1 - (-2 * x + 2) * (-2 * x + 2) / 2;
    }

    private final int[] at = new int[2];

    /** Центр отпечатка: над самим датчиком (если нашли), иначе — место из «Настроек». */
    float[] finger() {
        getLocationOnScreen(at);
        float[] f = FingerSensor.center(getContext(), getWidth() + at[0], getHeight() + at[1], insetBottom);
        f[0] -= at[0];
        f[1] -= at[1];
        return f;
    }

    float fingerY() {
        return finger()[1];
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        float t = exitAt < 0 ? 0 : (now - exitAt) / Prefs.k();   // скорость анимаций из настроек
        if (exitAt >= 0 && t >= 870) {
            Runnable d = exitDone;
            exitAt = -1;
            exitDone = null;
            if (d != null) d.run();
            return;
        }
        // обои рисует WallView под нами (супер обои живут и здесь), сверху —
        // лёгкое затемнение, чтобы читались часы
        c.drawColor(Ui.alpha(0xFF000000, 0.18f * (1 - inOutQuad(t / 850f))));
        // часы: вверх на 160 dp и в 0,35 размера; гаснут с 200-й мс.
        // Если часы переезжают на стол — летят в его часы и гаснут в самом конце
        float ce = CURVE.getInterpolation(Ui.clamp(t / 850f));
        float ca = 1 - inOutQuad((t - 200) / 600f);
        android.graphics.RectF cr = LockFace.clockRect(w, h, insetTop);
        boolean fly = flyTo != null && exitAt >= 0;
        float clockA = fly ? 1 - inOutQuad((t - 640) / 220f) : ca;
        if (clockA > 0.01f) {
            // слой прозрачности — только вокруг часов: слой на весь экран
            // в каждом кадре слабому видеочипу не по силам
            int sc = c.save();
            if (fly) {
                float k = flyTo.height() / Math.max(1f, cr.height());
                float s = 1 + (k - 1) * ce;
                c.translate((flyTo.centerX() - cr.centerX()) * ce, (flyTo.top - cr.top) * ce);
                c.scale(s, s, cr.centerX(), cr.top);
            } else {
                c.translate(0, -Ui.dpf(160) * ce);
                float k = 1 - 0.65f * ce;
                c.scale(k, k, w / 2f, cr.centerY());
            }
            c.saveLayerAlpha(cr.left - Ui.dpf(48), cr.top - Ui.dpf(48), cr.right + Ui.dpf(48), cr.bottom + Ui.dpf(96), Math.round(255 * clockA));
            LockFace.drawClock(c, getContext(), w, h, insetTop);
            c.restoreToCount(sc);
        }
        if (ca > 0.01f) {
            int sc = c.save();
            c.translate(Ui.dpf(60) * ce, 0);
            android.graphics.RectF wz = LockFace.widgetZone(w, h, insetTop);
            c.saveLayerAlpha(wz.left - Ui.dpf(32), wz.top - Ui.dpf(32), wz.right + Ui.dpf(32), wz.bottom + Ui.dpf(32), Math.round(255 * ca));
            LockFace.drawWidgets(c, getContext(), w, h, insetTop);
            c.restoreToCount(sc);
        }
        // у фото «Вершина» гора стоит перед цифрами
        drawDepth(c, w, h, ca);

        // отпечаток: вниз на 120 dp, 0,9 размера, гаснет за 450 мс
        float fe = CURVE.getInterpolation(Ui.clamp(t / 700f));
        float fa = 1 - inOutQuad(t / 450f);
        if (fa <= 0.01f) {
            postInvalidateOnAnimation();
            return;
        }
        float[] fp = finger();
        float cx = fp[0], cy = fp[1];
        int fs = c.saveLayerAlpha(0, Math.min(cy, h - insetBottom) - Ui.dpf(170), w, h, Math.round(255 * fa));
        c.translate(0, Ui.dpf(120) * fe);
        c.scale(1 - 0.1f * fe, 1 - 0.1f * fe, cx, cy);
        int acc = error ? 0xFFFF6B6B : Ui.accentLight();
        if (!sensor) {
            hint.setColor(error ? 0xFFFFA0A0 : 0xD9FFFFFF);
            c.drawText(msg, w / 2f, h - insetBottom - Ui.dpf(44), hint);
            c.restoreToCount(fs);
            // без пульса отпечатка кадр нужен только для часов
            if (exitAt >= 0) postInvalidateOnAnimation();
            else if (getVisibility() == VISIBLE && getAlpha() > 0) postInvalidateDelayed(1000);
            return;
        }
        fingerEffect(c, cx, cy, now, acc, Prefs.fpEffect);
        drawPress(c, cx, cy, now, acc);
        // палец на датчике — значок чуть вжимается
        float pk = held ? Ui.clamp((now - downAt) / 160f) : downAt < 0 ? 0 : 1 - Ui.clamp((now - upAt) / 220f);
        c.save();
        c.scale(1 - 0.08f * pk, 1 - 0.08f * pk, cx, cy);
        LockFace.drawFinger(c, cx, cy, acc);
        c.restore();
        hint.setColor(error ? 0xFFFFA0A0 : 0xD9FFFFFF);
        // датчик низко — подпись над значком, а не под ним
        float ty = cy + Ui.dpf(66) > h - insetBottom - Ui.dpf(8) ? cy - Ui.dpf(58) : cy + Ui.dpf(66);
        c.drawText(msg, cx, ty, hint);
        c.restoreToCount(fs);
        if (exitAt >= 0) postInvalidateOnAnimation();
        else if (getVisibility() == VISIBLE && getAlpha() > 0) postInvalidateDelayed(16);
    }

    // ── палец на датчике ──────────────────────────────────────────────────

    private long downAt = -1, upAt = -1;
    private boolean held;

    /** Палец лёг на датчик: вспышка и волна от значка. */
    void fingerDown() {
        if (held) return;
        held = true;
        downAt = SystemClock.uptimeMillis();
        invalidate();
    }

    void fingerUp() {
        if (!held) return;
        held = false;
        upAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawPress(Canvas c, float cx, float cy, long now, int acc) {
        if (downAt < 0) return;
        float since = now - downAt;
        // волна расходится от датчика (один раз на касание)
        if (since < 650) {
            float k = since / 650f;
            press.setStyle(Paint.Style.STROKE);
            press.setStrokeWidth(Ui.dpf(2.5f) * (1 - k));
            press.setColor(Ui.alpha(acc, 0.8f * (1 - k)));
            c.drawCircle(cx, cy, Ui.dpf(38) + Ui.dpf(70) * inOutQuad(k), press);
        }
        // свечение под пальцем, пока он лежит
        float g = held ? Ui.clamp(since / 180f) : 1 - Ui.clamp((now - upAt) / 260f);
        if (g > 0.01f) {
            press.setStyle(Paint.Style.FILL);
            float r = Ui.dpf(58);
            press.setShader(new android.graphics.RadialGradient(cx, cy, r,
                    new int[]{Ui.alpha(acc, 0.55f * g), Ui.alpha(acc, 0.18f * g), Ui.alpha(acc, 0)},
                    new float[]{0, 0.6f, 1}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, press);
            press.setShader(null);
        }
    }

    /** Передний план фото (гора) поверх часов — «глубина». */
    private void drawDepth(Canvas c, int w, int h, float alpha) {
        if (!Prefs.depth) return;
        boolean own = Ui.ownLockWall();
        Walls.W spec = own ? Walls.find(Prefs.lockWallpaper) : Walls.current();
        android.graphics.Bitmap fg = own ? Ui.lockFg : Ui.wallFg, base = own ? Ui.lockWall : Ui.wall;
        if (fg == null || base == null || spec.kind != Walls.PHOTO) return;
        Ui.wallMatrix(base, w, h, 1.06f, m);
        img.setAlpha(Math.round(255 * Ui.clamp(alpha)));
        c.drawBitmap(fg, m, img);
    }

    /** Отпечаток: пульс (кольцо расходится), волна (три кольца), свечение или ничего. */
    static void fingerEffect(Canvas c, float cx, float cy, long now, int acc, String kind) {
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case "ripple":
                for (int i = 0; i < 3; i++) {
                    float k = ((now + i * 700) % 2100) / 2100f;
                    ring.setStrokeWidth(Ui.dpf(1.6f));
                    ring.setColor(Ui.alpha(acc, 0.5f * (1 - k)));
                    c.drawCircle(cx, cy, Ui.dpf(36) + Ui.dpf(44) * k, ring);
                }
                break;
            case "glow": {
                float k = 0.5f + 0.5f * (float) Math.sin(now / 1800.0 * Math.PI * 2);
                Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
                float r = Ui.dpf(70);
                g.setShader(new android.graphics.RadialGradient(cx, cy, r,
                        new int[]{Ui.alpha(acc, 0.25f + 0.3f * k), Ui.alpha(acc, 0)}, null, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, g);
                break;
            }
            case "none":
                break;
            default: {   // pulse
                float k = (now % 1800) / 1800f;
                ring.setStrokeWidth(Ui.dpf(2));
                ring.setColor(Ui.alpha(acc, 0.55f * (1 - k)));
                c.drawCircle(cx, cy, Ui.dpf(34) + Ui.dpf(26) * k, ring);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (exitAt >= 0) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downY = e.getY();
                downX = e.getX();
                // коснулись значка отпечатка — он откликается
                float[] f = finger();
                if (sensor && Math.hypot(e.getX() - f[0], e.getY() - f[1]) < Ui.dpf(48)) fingerDown();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                setTranslationY(Math.min(0, (e.getY() - downY) * 0.35f));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                fingerUp();
                animate().translationY(0).setDuration(240).setInterpolator(Anim.EMPH).start();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    long now = SystemClock.uptimeMillis();
                    boolean tap = Math.hypot(e.getX() - downX, e.getY() - downY) < Ui.dpf(16);
                    if (tap && onDoubleTap != null && now - lastTapAt < 320 && Math.hypot(e.getX() - lastTapX, e.getY() - lastTapY) < Ui.dpf(60)) {
                        lastTapAt = 0;
                        onDoubleTap.run();
                    } else {
                        if (tap) {
                            lastTapAt = now;
                            lastTapX = e.getX();
                            lastTapY = e.getY();
                        }
                        if (listener != null) listener.unlockRequested();
                    }
                }
                return true;
        }
        return true;
    }
}
