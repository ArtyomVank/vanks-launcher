package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Ввод пароля на экране блокировки Vanks: точки, круглые клавиши 1–9, 0,
 * «стереть» и «ОК». Верный PIN принимается сразу, без «ОК». Пять ошибок
 * подряд — пауза 30 секунд.
 */
final class PinPad extends View {
    interface Listener {
        /** Набрали верный PIN. */
        void accepted(String pin);
        void cancelled();
    }

    Listener listener;
    int insetBottom;
    private final StringBuilder typed = new StringBuilder();
    private final Paint key = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digit = Ui.text(Ui.light, 30, 0xFFFFFFFF);
    private final Paint small = Ui.text(Ui.medium, 15, 0xFFFFFFFF);
    private final Paint title = Ui.text(Ui.medium, 18, 0xFFFFFFFF);
    private final RectF r = new RectF();
    private int pressed = -1, wrong;
    private long shakeAt = -1, blockedUntil;
    private String note = "Введите пароль";
    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "ОК"};

    PinPad(Context c) {
        super(c);
        digit.setTextAlign(Paint.Align.CENTER);
        small.setTextAlign(Paint.Align.CENTER);
        title.setTextAlign(Paint.Align.CENTER);
    }

    void open() {
        typed.setLength(0);
        note = "Введите пароль";
        setVisibility(VISIBLE);
        setAlpha(0);
        animate().alpha(1).setDuration(220).start();
    }

    void close() {
        animate().alpha(0).setDuration(180).withEndAction(() -> setVisibility(GONE)).start();
    }

    private float keySize() {
        return Ui.dpf(74);
    }

    private float gap() {
        return Ui.dpf(22);
    }

    /** Рамка клавиши i (0…11). */
    private RectF keyRect(int i, RectF out) {
        float s = keySize(), g = gap();
        float gridW = s * 3 + g * 2, gridH = s * 4 + g * 3;
        float x0 = getWidth() / 2f - gridW / 2, y0 = getHeight() - insetBottom - Ui.dpf(70) - gridH;
        int col = i % 3, row = i / 3;
        out.set(x0 + col * (s + g), y0 + row * (s + g), x0 + col * (s + g) + s, y0 + row * (s + g) + s);
        return out;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        c.drawColor(0x99000000);
        keyRect(0, r);
        float top = r.top;
        float shake = 0;
        if (shakeAt >= 0 && now - shakeAt < 400) {
            float t = (now - shakeAt) / 400f;
            shake = (float) Math.sin(t * Math.PI * 6) * Ui.dpf(12) * (1 - t);
            postInvalidateOnAnimation();
        }
        // заголовок и точки
        boolean blocked = now < blockedUntil;
        String head = blocked ? "Подождите " + ((blockedUntil - now) / 1000 + 1) + " с" : note;
        c.drawText(head, getWidth() / 2f + shake, top - Ui.dpf(86), title);
        int n = Math.max(4, typed.length());
        float step = Ui.dpf(22), x0 = getWidth() / 2f - (n - 1) * step / 2f + shake, cy = top - Ui.dpf(48);
        for (int i = 0; i < n; i++) {
            key.setStyle(i < typed.length() ? Paint.Style.FILL : Paint.Style.STROKE);
            key.setStrokeWidth(Ui.dpf(1.5f));
            key.setColor(0xE6FFFFFF);
            c.drawCircle(x0 + i * step, cy, Ui.dpf(6), key);
        }
        // клавиши
        for (int i = 0; i < KEYS.length; i++) {
            keyRect(i, r);
            boolean num = i != 9 && i != 11;
            if (num) {
                key.setStyle(Paint.Style.FILL);
                key.setColor(pressed == i ? 0x66FFFFFF : 0x29FFFFFF);
                c.drawOval(r, key);
                c.drawText(KEYS[i], r.centerX(), r.centerY() + Ui.dpf(11), digit);
            } else {
                small.setColor(pressed == i ? 0xFFFFFFFF : 0xCCFFFFFF);
                c.drawText(KEYS[i], r.centerX(), r.centerY() + Ui.dpf(6), small);
            }
        }
        small.setColor(0xCCFFFFFF);
        c.drawText("Отмена", getWidth() / 2f, getHeight() - insetBottom - Ui.dpf(26), small);
        if (blocked) postInvalidateDelayed(500);
    }

    private int hit(float x, float y) {
        for (int i = 0; i < KEYS.length; i++) {
            keyRect(i, r);
            r.inset(-gap() / 2, -gap() / 2);
            if (r.contains(x, y)) return i;
        }
        if (y > getHeight() - insetBottom - Ui.dpf(52)) return 99;   // «Отмена»
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int k = pressed;
                pressed = -1;
                invalidate();
                if (k == 99) {
                    if (listener != null) listener.cancelled();
                    return true;
                }
                if (k < 0 || SystemClock.uptimeMillis() < blockedUntil) return true;
                Feel.buzz(this, Feel.TICK);
                if (k == 9) {
                    if (typed.length() > 0) typed.setLength(typed.length() - 1);
                } else if (k == 11) {
                    submit(true);
                } else if (typed.length() < 16) {
                    typed.append(KEYS[k]);
                    submit(false);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return true;
    }

    /** Верный — сразу; неверный — только по «ОК». */
    private void submit(boolean ok) {
        String p = typed.toString();
        if (p.length() >= 4 && Secret.check(getContext(), p)) {
            wrong = 0;
            if (listener != null) listener.accepted(p);
            return;
        }
        if (!ok) return;
        typed.setLength(0);
        wrong++;
        shakeAt = SystemClock.uptimeMillis();
        Feel.buzz(this, Feel.THUD);
        note = "Неверный пароль";
        if (wrong >= 5) {
            wrong = 0;
            blockedUntil = SystemClock.uptimeMillis() + 30000;
        }
        invalidate();
    }
}
