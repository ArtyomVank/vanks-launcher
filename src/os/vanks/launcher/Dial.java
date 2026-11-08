package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.IntConsumer;

/**
 * Шкала-колёсико профи-режима камеры: значения в ряд, нужное — под жёлтой
 * чертой посередине. Ведут пальцем вбок, отпускают — шкала встаёт на
 * ближайшее значение; касание значения — сразу к нему.
 */
final class Dial extends View {
    private String[] items = {};
    private int index;
    private float offset;           // сдвиг шкалы: index * step, пока не тянут
    private float downX, lastX;
    private boolean dragging;
    private IntConsumer pick;
    private android.animation.ValueAnimator settling;
    private final float step = Ui.dpf(58);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

    Dial(Context c) {
        super(c);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Ui.medium);
        text.setTextSize(Ui.dpf(13));
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(String[] values, int at, IntConsumer onPick) {
        items = values;
        index = Math.max(0, Math.min(at, values.length - 1));
        offset = index * step;
        pick = onPick;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settling != null) settling.cancel();
                downX = lastX = e.getX();
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - downX) > Ui.dpf(6)) dragging = true;
                if (dragging) {
                    offset = Math.max(0, Math.min((items.length - 1) * step, offset - (e.getX() - lastX)));
                    lastX = e.getX();
                    choose(Math.round(offset / step));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    // касание: к значению под пальцем
                    choose(Math.round((offset + e.getX() - getWidth() / 2f) / step));
                }
                settle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                settle();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void choose(int i) {
        i = Math.max(0, Math.min(i, items.length - 1));
        if (i == index) return;
        index = i;
        Feel.buzz(this, Feel.TICK);
        if (pick != null) pick.accept(i);
    }

    private void settle() {
        if (settling != null) settling.cancel();
        settling = Anim.run(offset, index * step, 180, Anim.EMPH, v -> {
            offset = v;
            invalidate();
        }, null);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), mid = w / 2f;
        for (int i = 0; i < items.length; i++) {
            float x = mid + i * step - offset;
            if (x < -step || x > w + step) continue;
            float near = Math.max(0, 1 - Math.abs(x - mid) / (w / 2f));
            boolean on = i == index;
            // черта значения и мелкие черты между значениями
            line.setStrokeWidth(Ui.dpf(1.4f));
            line.setColor(Ui.alpha(0xFFFFFFFF, 0.25f + 0.5f * near));
            c.drawLine(x, h - Ui.dpf(16), x, h - Ui.dpf(6), line);
            if (i < items.length - 1) {
                line.setColor(Ui.alpha(0xFFFFFFFF, 0.18f + 0.2f * near));
                for (int k = 1; k < 4; k++) {
                    float sx = x + step * k / 4f;
                    c.drawLine(sx, h - Ui.dpf(12), sx, h - Ui.dpf(6), line);
                }
            }
            text.setColor(on ? 0xFFFFD54A : Ui.alpha(0xFFFFFFFF, 0.35f + 0.6f * near));
            text.setTextSize(Ui.dpf(on ? 15 : 13));
            c.drawText(items[i], x, h - Ui.dpf(24), text);
        }
        // указатель
        line.setStrokeWidth(Ui.dpf(2.4f));
        line.setColor(0xFFFFD54A);
        c.drawLine(mid, h - Ui.dpf(20), mid, h - Ui.dpf(2), line);
    }
}
