package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;

/**
 * Плитки пункта управления. Сетка в 4 колонки, плитки встают плотно, по порядку.
 * Ползунки яркости и громкости тянутся пальцем. Долгое нажатие — режим правки:
 * плитки покачиваются, их можно перетаскивать, менять размер (потянуть или нажать
 * уголок справа внизу) и убирать (крестик слева вверху).
 */
final class TileGrid extends View {
    interface Host {
        void editChanged(boolean edit);
        void addTile();
        void tileTap(String type);   // "" — нажали мимо плиток
        void mediaKey(int which);   // -1 назад, 0 пауза/играть, 1 вперёд
        void slider(String type, float value, boolean done);
    }

    static final class T {
        String type;
        int w, h;
        final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    }

    static final String[] TYPES = {"wifi", "bt", "media", "torch", "dnd", "airplane", "rotate", "bright", "volume",
            "camera", "calc", "alarm", "timer", "battery", "settings"};

    static String title(String t) {
        switch (t) {
            case "wifi": return "Wi‑Fi";
            case "bt": return "Bluetooth";
            case "media": return "Музыка";
            case "torch": return "Фонарик";
            case "dnd": return "Не беспокоить";
            case "airplane": return "Авиарежим";
            case "rotate": return "Автоповорот";
            case "bright": return "Яркость";
            case "volume": return "Громкость";
            case "camera": return "Камера";
            case "calc": return "Калькулятор";
            case "alarm": return "Будильник";
            case "timer": return "Таймер";
            case "battery": return "Батарея";
            case "settings": return "Настройки";
        }
        return t;
    }

    static int[][] sizes(String t) {
        switch (t) {
            case "wifi": case "bt": return new int[][]{{2, 1}, {1, 1}};
            case "media": return new int[][]{{2, 2}, {4, 2}, {2, 1}, {4, 1}};
            case "bright": case "volume": return new int[][]{{1, 2}, {1, 3}, {2, 1}, {4, 1}};
            case "battery": return new int[][]{{2, 1}, {1, 1}, {2, 2}};
        }
        return new int[][]{{1, 1}, {2, 1}};
    }

    final ArrayList<T> list = new ArrayList<>();
    boolean edit;
    Host host;

    // состояние для плиток (заполняет главный экран)
    boolean wifi, bt, torch, dnd, airplane, rotate, playing;
    String wifiName, btName;
    float bright = 0.5f, volume = 0.5f;
    int battery = -1;
    boolean charging;

    private float cell, gap, pad, addH;
    private int rows;
    private ValueAnimator anim;

    // касания
    private static final int NONE = 0, PENDING = 1, DRAG = 2, RESIZE = 3, REMOVE = 4, ADD = 5, SLIDE = 6;
    private int mode = NONE;
    private T touchT;
    private float downX, downY, grabX, grabY, slideStart;
    private boolean moved;
    private final RectF dragRect = new RectF();
    private T lastSwap;
    private final int slop;
    private final GestureDetector gd;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = Ui.text(Ui.light, 30, 0xFFFFFFFF);
    private final Paint mid = Ui.text(Ui.medium, 14, 0xFFFFFFFF);
    private final Paint small = Ui.text(Ui.regular, 12, 0xB3FFFFFF);
    private final Paint tiny = Ui.text(Ui.medium, 10, 0xCCFFFFFF);
    private final RectF r = new RectF();
    private int fg = 0xFFFFFFFF;

    TileGrid(Context c) {
        super(c);
        gap = Ui.dpf(12);
        pad = Ui.dpf(18);
        addH = Ui.dpf(52);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        load(Prefs.tiles);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (mode == SLIDE && moved) return true;
                T t = at(e.getX(), e.getY());
                if (host == null) return true;
                if (t == null) {
                    // мимо плиток — закрыть пункт управления
                    host.tileTap("");
                    return true;
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (t.type.equals("media") && mediaButton(t, e.getX(), e.getY()) != Integer.MIN_VALUE) {
                    host.mediaKey(mediaButton(t, e.getX(), e.getY()));
                } else if (isSlider(t.type)) {
                    // нажатие на ползунок — поставить значение в эту точку
                    float v = sliderValue(t, e.getX(), e.getY());
                    setSlider(t.type, v);
                    host.slider(t.type, v, true);
                } else {
                    host.tileTap(t.type);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mode == SLIDE && moved) return;
                T t = at(e.getX(), e.getY());
                if (t == null) return;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                setEdit(true);
                // сразу можно тащить
                touchT = t;
                mode = PENDING;
                downX = e.getX();
                downY = e.getY();
                grabX = e.getX() - t.to.left;
                grabY = e.getY() - t.to.top;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        });
    }

    // ── данные ────────────────────────────────────────────────────────────

    void load(String s) {
        list.clear();
        for (String part : s.split(",")) {
            String[] f = part.split(":");
            if (f.length != 3 || !known(f[0])) continue;
            T t = new T();
            t.type = f[0];
            try {
                t.w = Integer.parseInt(f[1]);
                t.h = Integer.parseInt(f[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!allowed(t.type, t.w, t.h)) { t.w = sizes(t.type)[0][0]; t.h = sizes(t.type)[0][1]; }
            list.add(t);
        }
        for (T t : list) t.cur.setEmpty();
        relayout(false);
    }

    private void save() {
        StringBuilder b = new StringBuilder();
        for (T t : list) {
            if (b.length() > 0) b.append(',');
            b.append(t.type).append(':').append(t.w).append(':').append(t.h);
        }
        Prefs.tiles = b.toString();
        Prefs.save();
    }

    private static boolean known(String t) {
        for (String k : TYPES) if (k.equals(t)) return true;
        return false;
    }

    private static boolean allowed(String t, int w, int h) {
        for (int[] s : sizes(t)) if (s[0] == w && s[1] == h) return true;
        return false;
    }

    boolean has(String type) {
        for (T t : list) if (t.type.equals(type)) return true;
        return false;
    }

    void add(String type) {
        T t = new T();
        t.type = type;
        t.w = sizes(type)[0][0];
        t.h = sizes(type)[0][1];
        list.add(t);
        relayout(true);
        save();
    }

    void setEdit(boolean e) {
        if (edit == e) return;
        edit = e;
        mode = NONE;
        touchT = null;
        relayout(true);
        if (host != null) host.editChanged(e);
        invalidate();
    }

    private static boolean isSlider(String type) {
        return type.equals("bright") || type.equals("volume");
    }

    private void setSlider(String type, float v) {
        if (type.equals("bright")) bright = v; else volume = v;
        invalidate();
    }

    // ── раскладка ─────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int ws, int hs) {
        int width = MeasureSpec.getSize(ws);
        cell = (width - 2 * pad - 3 * gap) / 4f;
        place();
        for (T t : list) if (anim == null || !anim.isRunning()) t.cur.set(t.to);
        setMeasuredDimension(width, Math.round(contentHeight()));
    }

    private float contentHeight() {
        float h = 2 * pad + Math.max(1, rows) * cell + (Math.max(1, rows) - 1) * gap;
        if (edit) h += gap + addH;
        return h;
    }

    /** Плотная укладка по порядку: каждая плитка — в первое место, где помещается. */
    private void place() {
        boolean[][] occ = new boolean[96][4];
        rows = 0;
        for (T t : list) {
            outer:
            for (int row = 0; row < 90; row++) {
                for (int col = 0; col + t.w <= 4; col++) {
                    boolean ok = true;
                    for (int y = row; y < row + t.h && ok; y++)
                        for (int x = col; x < col + t.w; x++) if (occ[y][x]) { ok = false; break; }
                    if (!ok) continue;
                    for (int y = row; y < row + t.h; y++) for (int x = col; x < col + t.w; x++) occ[y][x] = true;
                    float l = pad + col * (cell + gap), tp = pad + row * (cell + gap);
                    t.to.set(l, tp, l + t.w * cell + (t.w - 1) * gap, tp + t.h * cell + (t.h - 1) * gap);
                    rows = Math.max(rows, row + t.h);
                    break outer;
                }
            }
        }
    }

    private void relayout(boolean animate) {
        if (cell <= 0) { requestLayout(); return; }
        float oldH = contentHeight();
        place();
        if (anim != null) anim.cancel();
        if (animate) {
            for (T t : list) t.from.set(t.cur.isEmpty() ? t.to : t.cur);
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(380);
            anim.setInterpolator(new OvershootInterpolator(1.1f));
            anim.addUpdateListener(v -> {
                float k = (Float) v.getAnimatedValue();
                for (T t : list) {
                    t.cur.set(t.from.left + (t.to.left - t.from.left) * k, t.from.top + (t.to.top - t.from.top) * k,
                            t.from.right + (t.to.right - t.from.right) * k, t.from.bottom + (t.to.bottom - t.from.bottom) * k);
                }
                invalidate();
            });
            anim.start();
        } else {
            for (T t : list) t.cur.set(t.to);
        }
        if (Math.abs(contentHeight() - oldH) > 0.5f || edit) requestLayout();
        invalidate();
    }

    private T at(float x, float y) {
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).cur.contains(x, y)) return list.get(i);
        return null;
    }

    private RectF addRect() {
        float t = 2 * pad + Math.max(1, rows) * cell + (Math.max(1, rows) - 1) * gap + gap - pad;
        r.set(pad, t, getWidth() - pad, t + addH);
        return r;
    }

    // ── рисование ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        fg = Look.fg();
        mid.setColor(fg);
        big.setColor(fg);
        small.setColor(Look.fg2());
        tiny.setColor(Look.fg2());
        big.setTypeface(Look.digits(Ui.light));
        for (int i = 0; i < list.size(); i++) {
            T t = list.get(i);
            if (t == touchT && mode == DRAG) continue;
            c.save();
            if (edit) c.rotate((float) Math.sin(now / 95.0 + i * 1.9) * 0.9f, t.cur.centerX(), t.cur.centerY());
            drawTile(c, t, t.cur);
            c.restore();
        }
        if (touchT != null && mode == DRAG) {
            c.save();
            c.scale(1.06f, 1.06f, dragRect.centerX(), dragRect.centerY());
            fill.setColor(0x40000000);
            c.drawRoundRect(dragRect.left + Ui.dpf(4), dragRect.top + Ui.dpf(10), dragRect.right - Ui.dpf(4), dragRect.bottom + Ui.dpf(12), Ui.dpf(24), Ui.dpf(24), fill);
            drawTile(c, touchT, dragRect);
            c.restore();
        }
        if (edit) {
            RectF a = addRect();
            card.setStyle(Paint.Style.FILL);
            card.setColor(Ui.alpha(fg, mode == ADD ? 0.2f : 0.12f));
            c.drawRoundRect(a, a.height() / 2, a.height() / 2, card);
            String s = "Добавить элемент";
            float tw = mid.measureText(s);
            float x0 = a.centerX() - (tw + Ui.dpf(28)) / 2;
            Glyph.plus(c, x0 + Ui.dpf(10), a.centerY(), Ui.dpf(20), fg);
            c.drawText(s, x0 + Ui.dpf(28), a.centerY() + Ui.dpf(5), mid);
            postInvalidateOnAnimation();
        }
    }

    private boolean active(String type) {
        switch (type) {
            case "wifi": return wifi;
            case "bt": return bt;
            case "torch": return torch;
            case "dnd": return dnd;
            case "airplane": return airplane;
            case "rotate": return rotate;
        }
        return false;
    }

    /** Цвет включённой кнопки — как в пункте управления: у каждой свой. */
    private static int onColor(String type) {
        if (Look.acrylicUi() || Look.is("nothing")) return Look.is("nothing") ? (Look.light() ? 0xFF111111 : 0xFFFFFFFF) : Ui.accent();
        switch (type) {
            case "wifi": case "bt": return 0xFF3E8BFF;
            case "airplane": return 0xFFFF9F2E;
            case "dnd": return 0xFF7B6CFF;
            case "torch": case "rotate": return 0xFFFFFFFF;
        }
        return Ui.accent();
    }

    private void drawTile(Canvas c, T t, RectF rr) {
        boolean acr = Look.acrylicUi();
        // у «Акрила» ползунки — капсулы, у Delta — скруглённые плитки
        float rad = isSlider(t.type) && acr ? Math.min(rr.width(), rr.height()) / 2
                : Math.min(Look.radius(15), Math.min(rr.width(), rr.height()) * 0.32f);
        boolean on = active(t.type);
        // кнопки 1×1: у «Акрила» — круглые, у Delta — квадратные с подписью
        boolean small1 = t.w == 1 && t.h == 1 && !isSlider(t.type) && !t.type.equals("battery");
        boolean round = small1 && acr;
        Look.tile(c, rr, rad, on && small1, onColor(t.type), round);
        c.save();
        c.clipRect(rr);
        switch (t.type) {
            case "media": media(c, t, rr); break;
            case "bright": case "volume": slider(c, t, rr, rad); break;
            case "battery": battery(c, t, rr); break;
            default: button(c, t, rr, on, small1, round);
        }
        c.restore();

        if (edit) {
            float cx = rr.left + Ui.dpf(10), cy = rr.top + Ui.dpf(10);
            fill.setColor(0xF0262A3A);
            c.drawCircle(cx, cy, Ui.dpf(11), fill);
            Glyph.close(c, cx, cy, Ui.dpf(16), 0xFFFFFFFF);
            if (sizes(t.type).length > 1) {
                float hx = rr.right - Ui.dpf(12), hy = rr.bottom - Ui.dpf(12);
                fill.setColor(Ui.accent());
                c.drawCircle(hx, hy, Ui.dpf(12), fill);
                Glyph.resize(c, hx, hy, Ui.dpf(16), 0xFFFFFFFF);
            }
        }
    }

    private void glyph(Canvas c, String type, float cx, float cy, float s, int col, boolean on) {
        switch (type) {
            case "wifi": Glyph.wifi(c, cx, cy, s, col); break;
            case "bt": Glyph.bluetooth(c, cx, cy, s, col); break;
            case "torch": Glyph.torch(c, cx, cy, s, col, on); break;
            case "dnd": Glyph.moon(c, cx, cy, s, col, on); break;
            case "airplane": Glyph.plane(c, cx, cy, s, col); break;
            case "rotate": Glyph.rotate(c, cx, cy, s, col); break;
            case "camera": Glyph.camera(c, cx, cy, s, col); break;
            case "calc": Glyph.calc(c, cx, cy, s, col); break;
            case "alarm": case "timer": Glyph.alarm(c, cx, cy, s, col); break;
            case "settings": Glyph.gear(c, cx, cy, s, col); break;
            case "bright": Glyph.sun(c, cx, cy, s, col); break;
            case "volume": Glyph.speaker(c, cx, cy, s, col); break;
        }
    }

    private String sub(String type, boolean on) {
        switch (type) {
            case "wifi": return on ? (wifiName != null ? wifiName : "Подключено") : "Выкл.";
            case "bt": return on ? (btName != null ? btName : "Вкл.") : "Выкл.";
            case "torch": case "dnd": case "airplane": return on ? "Вкл." : "Выкл.";
            case "rotate": return on ? "Вкл." : "Заблокирован";
            case "timer": return "Запустить";
            case "alarm": {
                String a = ClockView.nextAlarm(getContext());
                return a == null ? "Нет" : a;
            }
        }
        return "Открыть";
    }

    /** Цвет значка на включённой кнопке: на светлой заливке — тёмный. */
    private int onGlyph(String type) {
        int bg = onColor(type);
        float l = (android.graphics.Color.red(bg) + android.graphics.Color.green(bg) + android.graphics.Color.blue(bg)) / 765f;
        return l > 0.7f ? 0xFF1A1D2A : 0xFFFFFFFF;
    }

    /**
     * Кнопка. 1×1: у «Акрила» — круг со значком, у Delta — значок и подпись
     * внизу. 2×1 и больше — кружок со значком и подпись рядом.
     */
    private void button(Canvas c, T t, RectF rr, boolean on, boolean small1, boolean round) {
        if (round) {
            glyph(c, t.type, rr.centerX(), rr.centerY(), rr.width() * 0.42f, on ? onGlyph(t.type) : fg, on);
            return;
        }
        if (small1) {
            glyph(c, t.type, rr.centerX(), rr.top + rr.height() * 0.4f, rr.width() * 0.36f, on ? onGlyph(t.type) : fg, on);
            tiny.setTextAlign(Paint.Align.CENTER);
            tiny.setColor(on ? Ui.alpha(onGlyph(t.type), 0.85f) : Look.fg2());
            CharSequence ti = TextUtils.ellipsize(title(t.type), textPaint(tiny), rr.width() - Ui.dpf(8), TextUtils.TruncateAt.END);
            c.drawText(ti, 0, ti.length(), rr.centerX(), rr.bottom - Ui.dpf(10), tiny);
            tiny.setTextAlign(Paint.Align.LEFT);
            tiny.setColor(Look.fg2());
            return;
        }
        float rad = Math.min(rr.height(), Ui.dpf(80)) * 0.3f;
        float cx = rr.left + Ui.dpf(12) + rad, cy = rr.top + Math.min(rr.height(), Ui.dpf(80)) / 2f;
        if (t.h > 1) cy = rr.top + Ui.dpf(12) + rad;
        fill.setColor(on ? onColor(t.type) : Ui.alpha(fg, 0.16f));
        c.drawCircle(cx, cy, rad, fill);
        glyph(c, t.type, cx, cy, rad * 1.25f, on ? onGlyph(t.type) : fg, on);
        float x = t.h > 1 ? rr.left + Ui.dpf(14) : cx + rad + Ui.dpf(10);
        float y = t.h > 1 ? rr.bottom - Ui.dpf(34) : cy - Ui.dpf(2);
        float avail = rr.right - x - Ui.dpf(8);
        CharSequence ti = TextUtils.ellipsize(title(t.type), textPaint(mid), avail, TextUtils.TruncateAt.END);
        c.drawText(ti, 0, ti.length(), x, y, mid);
        CharSequence su = TextUtils.ellipsize(sub(t.type, on), textPaint(small), avail, TextUtils.TruncateAt.END);
        c.drawText(su, 0, su.length(), x, y + Ui.dpf(17), small);
    }

    private final android.text.TextPaint tp = new android.text.TextPaint();

    private android.text.TextPaint textPaint(Paint p) {
        tp.set(p);
        return tp;
    }

    /** Ползунок: заполняется снизу (вертикальный) или слева (горизонтальный). */
    private void slider(Canvas c, T t, RectF rr, float rad) {
        float v = t.type.equals("bright") ? bright : volume;
        boolean vert = t.h > t.w;
        boolean acr = Look.acrylicUi();
        int lvl = acr ? Ui.accent() : (Look.light() ? 0xFF2A2C36 : 0xF2FFFFFF);
        if (acr) fill.setShader(new android.graphics.LinearGradient(rr.left, rr.bottom, rr.right, rr.top,
                Ui.accentDark(), Ui.accentLight(), android.graphics.Shader.TileMode.CLAMP));
        else fill.setColor(lvl);
        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(rr, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        if (vert) c.drawRect(rr.left, rr.bottom - rr.height() * v, rr.right, rr.bottom, fill);
        else c.drawRect(rr.left, rr.top, rr.left + rr.width() * v, rr.bottom, fill);
        fill.setShader(null);
        c.restore();
        // значок темнеет там, где под ним уже белая заливка
        float gx, gy;
        if (vert) { gx = rr.centerX(); gy = rr.bottom - Ui.dpf(26); }
        else { gx = rr.left + Ui.dpf(26); gy = rr.centerY(); }
        boolean covered = vert ? v * rr.height() > Ui.dpf(40) : v * rr.width() > Ui.dpf(40);
        int onLvl = acr || Look.light() ? 0xFFFFFFFF : 0xFF3A3F55;
        glyph(c, t.type, gx, gy, Ui.dpf(24), covered ? onLvl : fg, false);
        if (!vert && rr.width() > Ui.dpf(150)) {
            mid.setColor(v * rr.width() > Ui.dpf(150) ? onLvl : fg);
            mid.setTypeface(Look.digits(Ui.medium));
            c.drawText(title(t.type) + " · " + Math.round(v * 100) + "%", rr.left + Ui.dpf(50), rr.centerY() + Ui.dpf(5), mid);
            mid.setTypeface(Ui.medium);
            mid.setColor(fg);
        }
    }

    private float sliderValue(T t, float x, float y) {
        if (t.h > t.w) return Ui.clamp((t.cur.bottom - y) / t.cur.height());
        return Ui.clamp((x - t.cur.left) / t.cur.width());
    }

    /** Кнопки плеера: где лежат и какая под пальцем (MIN_VALUE — ни одна). */
    private float[] mediaButtons(T t, RectF rr) {
        float y = t.h == 1 ? rr.centerY() : rr.bottom - Ui.dpf(34);
        float cx = t.h == 1 ? rr.right - Ui.dpf(70) : rr.centerX();
        float step = Math.min(Ui.dpf(48), rr.width() / 4);
        return new float[]{cx - step, cx, cx + step, y};
    }

    private int mediaButton(T t, float x, float y) {
        float[] b = mediaButtons(t, t.cur);
        for (int i = 0; i < 3; i++) if (Math.hypot(x - b[i], y - b[3]) < Ui.dpf(22)) return i - 1;
        return Integer.MIN_VALUE;
    }

    private void media(Canvas c, T t, RectF rr) {
        float x = rr.left + Ui.dpf(14);
        if (t.h > 1) {
            // «обложка»: цветной квадрат с нотой
            float s = Ui.dpf(44);
            fill.setColor(Ui.accent());
            c.drawRoundRect(x, rr.top + Ui.dpf(14), x + s, rr.top + Ui.dpf(14) + s, Ui.dpf(10), Ui.dpf(10), fill);
            Glyph.speaker(c, x + s / 2, rr.top + Ui.dpf(14) + s / 2, s * 0.6f, 0xFFFFFFFF);
            c.drawText(playing ? "Играет" : "Не исполняется", x, rr.top + Ui.dpf(80), mid);
            c.drawText("Музыка", x, rr.top + Ui.dpf(98), small);
        } else {
            c.drawText(playing ? "Играет" : "Не исполняется", x, rr.centerY() + Ui.dpf(5), mid);
        }
        float[] b = mediaButtons(t, rr);
        float s = Ui.dpf(t.h > 1 ? 26 : 22);
        Glyph.skip(c, b[0], b[3], s, fg, false);
        Glyph.play(c, b[1], b[3], s * 1.15f, fg, playing);
        Glyph.skip(c, b[2], b[3], s, fg, true);
    }

    private void battery(Canvas c, T t, RectF rr) {
        int lvl = Math.max(0, battery);
        int col = charging ? 0xFF4ADE80 : (lvl <= 15 ? 0xFFFF5C5C : fg);
        String pct = battery < 0 ? "—" : lvl + "%";
        if (t.w == 1) {
            Glyph.battery(c, rr.centerX(), rr.centerY() - Ui.dpf(8), rr.width() * 0.5f, col, lvl / 100f);
            tiny.setTextAlign(Paint.Align.CENTER);
            c.drawText(pct, rr.centerX(), rr.bottom - Ui.dpf(14), tiny);
            tiny.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float x = rr.left + Ui.dpf(14);
        if (t.h == 1) {
            Glyph.battery(c, x + Ui.dpf(20), rr.centerY(), Ui.dpf(40), col, lvl / 100f);
            big.setTextSize(Math.min(rr.height() * 0.4f, Ui.dpf(30)));
            c.drawText(pct, x + Ui.dpf(48), rr.centerY() + Ui.dpf(4), big);
            c.drawText(charging ? "Заряжается" : "Батарея", x + Ui.dpf(48), rr.centerY() + Ui.dpf(22), small);
        } else {
            Glyph.battery(c, x + Ui.dpf(26), rr.top + Ui.dpf(32), Ui.dpf(52), col, lvl / 100f);
            big.setTextSize(Ui.dpf(40));
            c.drawText(pct, x, rr.bottom - Ui.dpf(40), big);
            c.drawText(charging ? "Заряжается" : "Батарея", x, rr.bottom - Ui.dpf(18), small);
        }
    }

    // ── касания ───────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (!edit && (mode == NONE || mode == SLIDE)) {
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = x;
                    downY = y;
                    moved = false;
                    T t = at(x, y);
                    if (t != null && isSlider(t.type)) {
                        mode = SLIDE;
                        touchT = t;
                        slideStart = t.type.equals("bright") ? bright : volume;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (mode == SLIDE && touchT != null) {
                        if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) moved = true;
                        if (moved) {
                            boolean vert = touchT.h > touchT.w;
                            float d = vert ? (downY - y) / touchT.cur.height() : (x - downX) / touchT.cur.width();
                            float v = Ui.clamp(slideStart + d);
                            setSlider(touchT.type, v);
                            host.slider(touchT.type, v, false);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode == SLIDE && touchT != null && moved && host != null)
                        host.slider(touchT.type, touchT.type.equals("bright") ? bright : volume, true);
                    if (mode == SLIDE) { mode = NONE; touchT = null; }
                    return true;
            }
            return true;
        }
        if (!edit) gd.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                moved = false;
                mode = NONE;
                touchT = null;
                for (int i = list.size() - 1; i >= 0 && mode == NONE; i--) {
                    T t = list.get(i);
                    if (dist(x, y, t.cur.left + Ui.dpf(10), t.cur.top + Ui.dpf(10)) < Ui.dpf(20)) { mode = REMOVE; touchT = t; }
                    else if (sizes(t.type).length > 1 && dist(x, y, t.cur.right - Ui.dpf(12), t.cur.bottom - Ui.dpf(12)) < Ui.dpf(24)) { mode = RESIZE; touchT = t; }
                    else if (t.cur.contains(x, y)) {
                        mode = PENDING;
                        touchT = t;
                        grabX = x - t.to.left;
                        grabY = y - t.to.top;
                    }
                }
                if (mode == NONE && addRect().contains(x, y)) mode = ADD;
                if (mode != NONE) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) moved = true;
                if (mode == PENDING && moved) {
                    mode = DRAG;
                    lastSwap = null;
                }
                if (mode == DRAG && touchT != null) {
                    dragRect.set(x - grabX, y - grabY, x - grabX + touchT.to.width(), y - grabY + touchT.to.height());
                    T over = null;
                    for (T t : list) if (t != touchT && t.to.contains(x, y)) over = t;
                    if (over != null && over != lastSwap) {
                        int to = list.indexOf(over);
                        list.remove(touchT);
                        list.add(to, touchT);
                        lastSwap = over;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        relayout(true);
                    } else if (over == null) {
                        lastSwap = null;
                    }
                    invalidate();
                } else if (mode == RESIZE && touchT != null && moved) {
                    int dw = Math.round((x - touchT.to.left + gap / 2) / (cell + gap));
                    int dh = Math.round((y - touchT.to.top + gap / 2) / (cell + gap));
                    int[] best = null;
                    int bestD = Integer.MAX_VALUE;
                    for (int[] s : sizes(touchT.type)) {
                        int d = Math.abs(s[0] - dw) * 2 + Math.abs(s[1] - dh) * 2 + (s[0] == touchT.w && s[1] == touchT.h ? 0 : 1);
                        if (d < bestD) { bestD = d; best = s; }
                    }
                    if (best != null && (best[0] != touchT.w || best[1] != touchT.h)) {
                        touchT.w = best[0];
                        touchT.h = best[1];
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        relayout(true);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (up && mode == REMOVE && !moved && touchT != null) {
                    list.remove(touchT);
                    relayout(true);
                    save();
                } else if (up && mode == RESIZE && touchT != null) {
                    if (!moved) {
                        // нажатие на уголок — следующий размер по кругу
                        int[][] ss = sizes(touchT.type);
                        int k = 0;
                        for (int i = 0; i < ss.length; i++) if (ss[i][0] == touchT.w && ss[i][1] == touchT.h) k = i;
                        touchT.w = ss[(k + 1) % ss.length][0];
                        touchT.h = ss[(k + 1) % ss.length][1];
                        relayout(true);
                    }
                    save();
                } else if (mode == DRAG && touchT != null) {
                    touchT.cur.set(dragRect);
                    relayout(true);
                    save();
                } else if (up && mode == ADD && !moved && host != null) {
                    host.addTile();
                } else if (up && mode == NONE && !moved) {
                    // нажатие мимо плиток — выйти из правки
                    setEdit(false);
                }
                mode = NONE;
                touchT = null;
                invalidate();
                return true;
            }
        }
        return true;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
