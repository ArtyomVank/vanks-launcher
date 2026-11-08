package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Сетка значков приложений: рисуется сама, значки появляются с пружинкой по очереди. */
final class AppGrid extends View {
    interface Listener {
        void tap(Apps.App a, AppGrid g, RectF r);
        void longPress(Apps.App a, AppGrid g, RectF r);
        /** Значки переставили пальцем (только если reorder). */
        default void reordered(List<Apps.App> order) {}
        /** Начали тащить после долгого нажатия — закрыть меню. */
        default void dragStarted() {}
    }

    List<Apps.App> apps = new ArrayList<>();
    int cols = 4;
    int minRows = 0;
    boolean labels = true;
    float rowGap;   // добавка к высоте ряда: на страницах ряды растягиваются на весь экран
    Listener listener;
    /** Можно переставлять значки: зажать и вести (в папке). */
    boolean reorder;

    private int dragIdx = -1;            // какой значок тащим
    private boolean dragging, armed;     // armed — зажали, ждём движения
    private float dragX, dragY, downX, downY;
    private final java.util.HashMap<Apps.App, float[]> pos = new java.util.HashMap<>();   // где значки сейчас (для плавной перестановки)

    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final GestureDetector gd;
    private int pressed = -1;
    private int pressIdx = -1;      // какой значок сейчас сжат (и пока отпускается)
    private float press, pressV;    // 0…1 и скорость: пружинка нажатия
    private long pressLast;
    private long enterAt = -100000;

    AppGrid(Context c) {
        super(c);
        label.setTypeface(Ui.medium);
        label.setTextSize(Ui.dpf(12));
        label.setColor(0xFFF4F6FF);
        label.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x88000000);
        label.setTextAlign(Paint.Align.CENTER);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                pressed = hit(e.getX(), e.getY());
                if (pressed >= 0) pressIdx = pressed;
                pressLast = SystemClock.uptimeMillis();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int i = hit(e.getX(), e.getY());
                if (i >= 0 && listener != null) listener.tap(apps.get(i), AppGrid.this, screenRect(i));
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                int i = hit(e.getX(), e.getY());
                pressed = -1;
                invalidate();
                if (listener == null) return;
                Feel.buzz(AppGrid.this, Feel.PRESS);
                Feel.sound(getContext(), "pop");
                if (reorder && i >= 0) {
                    armed = true;
                    dragIdx = i;
                    dragX = downX;
                    dragY = downY;
                }
                // мимо значков — a == null: например, открыть настройки
                listener.longPress(i >= 0 ? apps.get(i) : null, AppGrid.this, i >= 0 ? screenRect(i) : null);
            }
        });
    }

    /** Цвет подписей: на светлой подложке — тёмные, без тени. */
    void setLabelColor(int color) {
        label.setColor(color);
        if ((color & 0xFFFFFF) < 0x808080) label.clearShadowLayer();
        else label.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x88000000);
        invalidate();
    }

    void setApps(List<Apps.App> list) {
        apps = new ArrayList<>(list);
        requestLayout();
        invalidate();
    }

    void playEnter() {
        enterAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private float cellH() {
        return Apps.iconPx + (labels ? Ui.dpf(32) : Ui.dpf(14)) + rowGap;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int rows = Math.max(minRows, (apps.size() + cols - 1) / cols);
        setMeasuredDimension(w, Math.round(rows * cellH()) + getPaddingTop() + getPaddingBottom());
    }

    private void rectFor(int i, RectF out) {
        float cw = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) cols;
        int col = i % cols, row = i / cols;
        float cx = getPaddingLeft() + cw * (col + 0.5f);
        float top = getPaddingTop() + row * cellH() + Ui.dpf(4);
        float s = Apps.iconPx;
        out.set(cx - s / 2, top, cx + s / 2, top + s);
    }

    private RectF screenRect(int i) {
        RectF out = new RectF();
        rectFor(i, out);
        // с масштабом: ряд дока ужимается, когда значки не влезают
        return Ui.toWindow(this, out);
    }

    private int hit(float x, float y) {
        float cw = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) cols;
        int col = (int) ((x - getPaddingLeft()) / cw);
        int row = (int) ((y - getPaddingTop()) / cellH());
        if (col < 0 || col >= cols || row < 0) return -1;
        int i = row * cols + col;
        if (i >= apps.size()) return -1;
        rectFor(i, r);
        r.inset(-Ui.dpf(10), -Ui.dpf(8));
        r.bottom += labels ? Ui.dpf(18) : 0;
        return r.contains(x, y) ? i : -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean anim = false;
        // пружинка нажатия: быстро вниз, назад — с лёгким отскоком
        float dt = Math.min(0.032f, (now - pressLast) / 1000f);
        pressLast = now;
        float target = pressed >= 0 ? 1f : 0f;
        float kS = pressed >= 0 ? 900f : 420f, kD = pressed >= 0 ? 60f : 22f;
        pressV += ((target - press) * kS - pressV * kD) * dt;
        press += pressV * dt;
        if (Math.abs(target - press) < 0.002f && Math.abs(pressV) < 0.01f) {
            press = target;
            pressV = 0;
            if (target == 0) pressIdx = -1;
        } else anim = true;
        float cw = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) cols;
        for (int i = 0; i < apps.size(); i++) {
            Apps.App a = apps.get(i);
            rectFor(i, r);
            if (reorder) {
                // плавно к своему месту
                float[] p = pos.get(a);
                if (p == null) pos.put(a, p = new float[]{r.left, r.top});
                p[0] += (r.left - p[0]) * 0.28f;
                p[1] += (r.top - p[1]) * 0.28f;
                if (Math.abs(r.left - p[0]) > 0.5f || Math.abs(r.top - p[1]) > 0.5f) anim = true;
                else { p[0] = r.left; p[1] = r.top; }
                r.offsetTo(p[0], p[1]);
                if (dragging && i == dragIdx) continue;   // его рисуем поверх, под пальцем
            }
            float t = (now - enterAt - i * 24) / 460f;
            float k = 1f;
            float al = 1f;
            if (t < 1f) {
                anim = true;
                k = Anim.back(Ui.clamp(t));
                al = Ui.clamp(t * 2.5f);
            }
            float sc = 0.55f + 0.45f * k;
            if (i == pressIdx) sc *= 1 - 0.12f * press;
            c.save();
            c.scale(sc, sc, r.centerX(), r.centerY());
            bmp.setAlpha(Math.round(255 * al));
            if (a.bmp != null) c.drawBitmap(a.bmp, null, r, bmp);
            c.restore();
            if (labels) {
                label.setAlpha(Math.round(255 * al));
                CharSequence s = TextUtils.ellipsize(a.label, label, cw - Ui.dpf(8), TextUtils.TruncateAt.END);
                c.drawText(s, 0, s.length(), r.centerX(), r.bottom + Ui.dpf(17), label);
            }
        }
        if (dragging && dragIdx >= 0 && dragIdx < apps.size()) {
            Apps.App a = apps.get(dragIdx);
            float s = Apps.iconPx * 1.12f;
            r.set(dragX - s / 2, dragY - s / 2, dragX + s / 2, dragY + s / 2);
            bmp.setAlpha(255);
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setColor(0x40000000);
            c.drawCircle(r.centerX(), r.centerY() + Ui.dpf(6), s * 0.46f, sh);
            if (a.bmp != null) c.drawBitmap(a.bmp, null, r, bmp);
            if (a.bmp == null) anim = true;
        }
        if (anim) postInvalidateOnAnimation();
    }

    /** Ячейка под точкой (для перестановки): ближайшая из существующих. */
    private int slotAt(float x, float y) {
        float cw = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) cols;
        int col = Math.max(0, Math.min(cols - 1, (int) ((x - getPaddingLeft()) / cw)));
        int row = Math.max(0, (int) ((y - getPaddingTop()) / cellH()));
        return Math.max(0, Math.min(apps.size() - 1, row * cols + col));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            armed = false;
            dragging = false;
            dragIdx = -1;
        }
        if (reorder && armed && act == MotionEvent.ACTION_MOVE) {
            dragX = e.getX();
            dragY = e.getY();
            if (!dragging && Math.hypot(dragX - downX, dragY - downY) > Ui.dpf(10)) {
                dragging = true;
                pressed = -1;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.dragStarted();
            }
            if (dragging) {
                int to = slotAt(dragX, dragY);
                if (to != dragIdx) {
                    Apps.App a = apps.remove(dragIdx);
                    apps.add(to, a);
                    dragIdx = to;
                    Feel.buzz(this, Feel.TICK);
                }
                invalidate();
                return true;
            }
        }
        if (reorder && (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) && dragging) {
            // значок ложится на место из-под пальца
            Apps.App a = apps.get(dragIdx);
            float s = Apps.iconPx;
            pos.put(a, new float[]{dragX - s / 2, dragY - s / 2});
            dragging = false;
            armed = false;
            dragIdx = -1;
            Feel.sound(getContext(), "snap-in");
            if (listener != null) listener.reordered(new ArrayList<>(apps));
            invalidate();
            return true;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) armed = false;
        boolean h = gd.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (pressed != -1) {
                pressed = -1;
                invalidate();
            }
        }
        return h || super.onTouchEvent(e);
    }
}
