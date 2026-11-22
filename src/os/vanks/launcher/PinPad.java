package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Ввод пароля на экране блокировки Vanks — как в ColorOS: обои под панелью
 * размыты (это делает LockActivity), сверху замок, «Введите пароль» и точки,
 * ниже крупные тонкие цифры без кружков, под цифрами буквы; нажатая клавиша
 * вспыхивает кругом и гаснет. Внизу слева «Отмена», а когда цифры набраны —
 * «Удалить»; справа «ОК», если PIN проверяет система.
 *
 * Сохранённый PIN (Secret) проверяется здесь и принимается сразу, без «ОК».
 * Пять ошибок подряд — пауза 30 секунд. Без сохранённого PIN (system) панель
 * отдаёт любой код от 4 цифр по «ОК»: его проверит системный экран.
 */
final class PinPad extends View {
    interface Listener {
        /** Набрали верный PIN (или любой от 4 цифр, если проверяет система). */
        void accepted(String pin);
        void cancelled();
    }

    Listener listener;
    int insetBottom;
    private final StringBuilder typed = new StringBuilder();
    private final Paint key = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digit = Ui.text(Ui.light, 36, 0xFFFFFFFF);
    private final Paint letters = Ui.text(Ui.medium, 9, 0x99FFFFFF);
    private final Paint side = Ui.text(Ui.regular, 16, 0xFFFFFFFF);
    private final Paint title = Ui.text(Ui.regular, 18, 0xFFFFFFFF);
    private final RectF r = new RectF();
    private int pressed = -1, wrong;
    /** Вспышка клавиши: какая и когда отпущена. */
    private int flash = -1;
    private long flashAt, shakeAt = -1, blockedUntil, openedAt;
    private String note = "Введите пароль";
    private boolean system;
    /** Длина сохранённого PIN (читается при открытии, не на каждом кадре). */
    private int pinLen;
    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", ""};
    private static final String[] ABC = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};
    private static final int LEFT = 9, RIGHT = 11, FLASH_MS = 260;

    PinPad(Context c) {
        super(c);
        digit.setTextAlign(Paint.Align.CENTER);
        letters.setTextAlign(Paint.Align.CENTER);
        letters.setLetterSpacing(0.18f);
        side.setTextAlign(Paint.Align.CENTER);
        title.setTextAlign(Paint.Align.CENTER);
    }

    /** note — подпись сверху (null — «Введите пароль»); system — PIN проверит система. */
    void open(String note, boolean system) {
        typed.setLength(0);
        this.system = system;
        String saved = system ? null : Secret.get(getContext());
        pinLen = saved != null ? saved.length() : 4;
        this.note = note != null ? note : "Введите пароль";
        if (note != null) shakeAt = SystemClock.uptimeMillis();
        openedAt = SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        setAlpha(0);
        setScaleX(1.06f);
        setScaleY(1.06f);
        animate().alpha(1).scaleX(1).scaleY(1).setDuration(260).setInterpolator(Anim.EMPH).start();
    }

    void close() {
        animate().alpha(0).scaleX(1.04f).scaleY(1.04f).setDuration(180)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    private float keySize() {
        return Ui.dpf(78);
    }

    private float gapX() {
        return Ui.dpf(26);
    }

    private float gapY() {
        return Ui.dpf(12);
    }

    /** Рамка клавиши i (0…11). */
    private RectF keyRect(int i, RectF out) {
        float s = keySize(), gx = gapX(), gy = gapY();
        float gridW = s * 3 + gx * 2, gridH = s * 4 + gy * 3;
        float x0 = getWidth() / 2f - gridW / 2, y0 = getHeight() - insetBottom - Ui.dpf(56) - gridH;
        int col = i % 3, row = i / 3;
        out.set(x0 + col * (s + gx), y0 + row * (s + gy), x0 + col * (s + gx) + s, y0 + row * (s + gy) + s);
        return out;
    }

    private String leftLabel() {
        return typed.length() > 0 ? "Удалить" : "Отмена";
    }

    private String rightLabel() {
        return system && typed.length() >= 4 ? "ОК" : "";
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        // под панелью размытые обои; затемнение — чтобы белые цифры читались на любых
        c.drawColor(0x8C000000);
        keyRect(0, r);
        float top = r.top;
        float shake = 0;
        if (shakeAt >= 0 && now - shakeAt < 420) {
            float t = (now - shakeAt) / 420f;
            shake = (float) Math.sin(t * Math.PI * 6) * Ui.dpf(10) * (1 - t);
            postInvalidateOnAnimation();
        }
        float cx = getWidth() / 2f;

        // замок
        float ly = top - Ui.dpf(150);
        key.setStyle(Paint.Style.STROKE);
        key.setStrokeWidth(Ui.dpf(1.8f));
        key.setColor(0xE6FFFFFF);
        r.set(cx - Ui.dpf(7), ly - Ui.dpf(10), cx + Ui.dpf(7), ly + Ui.dpf(4));
        c.drawArc(r, 180, 180, false, key);
        key.setStyle(Paint.Style.FILL);
        r.set(cx - Ui.dpf(10), ly - Ui.dpf(3), cx + Ui.dpf(10), ly + Ui.dpf(13));
        c.drawRoundRect(r, Ui.dpf(3.5f), Ui.dpf(3.5f), key);

        // подпись и точки
        boolean blocked = now < blockedUntil;
        String head = blocked ? "Повторите через " + ((blockedUntil - now) / 1000 + 1) + " с" : note;
        c.drawText(head, cx + shake, top - Ui.dpf(98), title);
        int n = Math.max(Math.max(4, pinLen), typed.length());
        float step = Ui.dpf(20), x0 = cx - (n - 1) * step / 2f + shake, cy = top - Ui.dpf(62);
        for (int i = 0; i < n; i++) {
            boolean on = i < typed.length();
            key.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
            key.setStrokeWidth(Ui.dpf(1.2f));
            key.setColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
            c.drawCircle(x0 + i * step, cy, Ui.dpf(on ? 5.5f : 5f), key);
        }

        // клавиши: без кружков, круг только вспыхивает под пальцем
        for (int i = 0; i < KEYS.length; i++) {
            keyRect(i, r);
            float a = 0;
            if (pressed == i) a = 1;
            else if (flash == i && now - flashAt < FLASH_MS) {
                a = 1 - (now - flashAt) / (float) FLASH_MS;
                postInvalidateOnAnimation();
            }
            if (a > 0 && !KEYS[i].isEmpty()) {
                key.setStyle(Paint.Style.FILL);
                key.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * a));
                c.drawCircle(r.centerX(), r.centerY(), keySize() / 2f * (0.9f + 0.1f * a), key);
            }
            if (!KEYS[i].isEmpty()) {
                boolean hasAbc = !ABC[i].isEmpty();
                float dy = hasAbc ? -Ui.dpf(2) : Ui.dpf(12);
                c.drawText(KEYS[i], r.centerX(), r.centerY() + dy, digit);
                if (hasAbc) c.drawText(ABC[i], r.centerX(), r.centerY() + Ui.dpf(17), letters);
            } else {
                String s = i == LEFT ? leftLabel() : rightLabel();
                side.setAlpha(pressed == i ? 140 : 255);
                c.drawText(s, r.centerX(), r.centerY() + Ui.dpf(6), side);
            }
        }
        if (blocked) postInvalidateDelayed(500);
    }

    private int hit(float x, float y) {
        for (int i = 0; i < KEYS.length; i++) {
            keyRect(i, r);
            r.inset(-gapX() / 2, -gapY() / 2);
            if (r.contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // палец, который открыл панель долгим нажатием, ещё на экране — не клавиша
                if (SystemClock.uptimeMillis() - openedAt < 200) return true;
                pressed = hit(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int k = pressed;
                pressed = -1;
                if (k >= 0) {
                    flash = k;
                    flashAt = SystemClock.uptimeMillis();
                }
                invalidate();
                if (k == LEFT) {
                    if (typed.length() > 0) {
                        typed.setLength(typed.length() - 1);
                        Feel.buzz(this, Feel.TICK);
                    } else if (listener != null) listener.cancelled();
                    return true;
                }
                if (k < 0 || SystemClock.uptimeMillis() < blockedUntil) return true;
                if (k == RIGHT) {
                    if (!rightLabel().isEmpty()) submit(true);
                    return true;
                }
                Feel.buzz(this, Feel.TICK);
                if (typed.length() < 16) {
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

    /** Сохранённый — верный сразу, неверный по длине; без сохранённого — по «ОК». */
    private void submit(boolean ok) {
        String p = typed.toString();
        if (system) {
            if (ok && p.length() >= 4 && listener != null) listener.accepted(p);
            return;
        }
        if (p.length() >= 4 && Secret.check(getContext(), p)) {
            wrong = 0;
            if (listener != null) listener.accepted(p);
            return;
        }
        // длина как у сохранённого, а не подошёл — ошибка сразу (как в ColorOS)
        if (!ok && p.length() < Math.max(4, pinLen)) return;
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
