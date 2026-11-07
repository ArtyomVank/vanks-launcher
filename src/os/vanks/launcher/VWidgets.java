package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Свои виджеты Vanks, как WidgetTypes в Delta: часы (вид — из часов Delta),
 * стрелочные часы, календарь (день или месяц — по размеру), батарея и
 * перенесённые с компьютера музыка, таймер, скорость сети, память и хранилище.
 * Рисуются прямо на столе; раз в минуту главный экран их перерисовывает.
 */
final class VWidgets {
    static final class Type {
        final String kind, name;
        final int w, h, minW, minH, maxW, maxH;

        Type(String kind, String name, int w, int h, int minW, int minH, int maxW, int maxH) {
            this.kind = kind;
            this.name = name;
            this.w = w;
            this.h = h;
            this.minW = minW;
            this.minH = minH;
            this.maxW = maxW;
            this.maxH = maxH;
        }
    }

    static final Type[] TYPES = {
            new Type("clock", "Часы", 4, 2, 2, 1, 6, 3),
            new Type("analog", "Стрелочные часы", 2, 2, 2, 2, 4, 4),
            new Type("calendar", "Календарь", 2, 2, 2, 2, 4, 4),
            new Type("battery", "Батарея", 2, 2, 2, 1, 4, 2),
            new Type("music", "Музыка", 4, 2, 2, 1, 4, 2),
            new Type("timer", "Таймер", 2, 2, 2, 2, 4, 3),
            new Type("net", "Скорость сети", 2, 2, 2, 1, 4, 2),
            new Type("memory", "Память", 2, 2, 2, 1, 4, 2),
            new Type("storage", "Хранилище", 2, 2, 2, 1, 4, 2),
    };

    static int battery = -1;
    static boolean charging;

    static Type type(String kind) {
        for (Type t : TYPES) if (t.kind.equals(kind)) return t;
        return TYPES[0];
    }

    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF tmp = new RectF();

    static void draw(Canvas c, Layout.Item it, RectF r, View v) {
        switch (it.kind == null ? "" : it.kind) {
            case "clock": clock(c, it, r, v); break;
            case "analog": panel(c, r, v); analog(c, r); break;
            case "calendar": panel(c, r, v); calendar(c, r, it.w >= 3 && it.h >= 3); break;
            case "battery": panel(c, r, v); battery(c, r, it.h <= 1); break;
            case "music": panel(c, r, v); music(c, it, r); break;
            case "timer": panel(c, r, v); timer(c, r); break;
            case "net": panel(c, r, v); net(c, it, r); break;
            case "memory": panel(c, r, v); meter(c, r, it.h <= 1, memTotal <= 0 ? -1 : 1f - memFree / (float) memTotal, "Память", gb(memTotal - memFree) + " из " + gb(memTotal)); break;
            case "storage": panel(c, r, v); meter(c, r, it.h <= 1, stTotal <= 0 ? -1 : 1f - stFree / (float) stTotal, "Хранилище", gb(stTotal - stFree) + " из " + gb(stTotal)); break;
        }
    }

    /** Есть ли на столе виджеты, которые меняются каждую секунду. */
    static boolean live() {
        for (Layout.Item it : Layout.items)
            if (it.kind != null && (it.kind.equals("net") || it.kind.equals("memory") || it.kind.equals("storage")
                    || it.kind.equals("music") || (it.kind.equals("timer") && timerEnd > 0))) return true;
        return false;
    }

    // ── замеры (раз в секунду, пока виден стол) ───────────────────────────

    static boolean playing;
    static long memFree, memTotal = -1, stFree, stTotal = -1;
    static float rx, tx;                          // байт в секунду
    static final float[] rxHist = new float[32];
    private static long lastRx = -1, lastTx = -1, lastAt;
    private static int ticks;

    static void sample(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        long r0 = android.net.TrafficStats.getTotalRxBytes(), t0 = android.net.TrafficStats.getTotalTxBytes();
        if (lastRx >= 0 && r0 >= 0 && now > lastAt) {
            float dt = (now - lastAt) / 1000f;
            rx = Math.max(0, (r0 - lastRx) / dt);
            tx = Math.max(0, (t0 - lastTx) / dt);
            System.arraycopy(rxHist, 1, rxHist, 0, rxHist.length - 1);
            rxHist[rxHist.length - 1] = rx;
        }
        lastRx = r0;
        lastTx = t0;
        lastAt = now;
        if (ticks++ % 5 == 0) {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                memFree = mi.availMem;
                memTotal = mi.totalMem;
            }
            try {
                android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                stFree = fs.getAvailableBytes();
                stTotal = fs.getTotalBytes();
            } catch (Exception ignored) {}
            android.media.AudioManager au = c.getSystemService(android.media.AudioManager.class);
            if (au != null) playing = au.isMusicActive();
        }
    }

    private static String gb(long b) {
        if (b < 0) return "—";
        float g = b / 1e9f;
        return (g >= 100 ? String.valueOf(Math.round(g)) : String.format(new Locale("ru"), "%.1f", g)) + " ГБ";
    }

    static String speed(float bps) {
        Locale ru = new Locale("ru");
        if (bps < 1000) return Math.round(bps) + " Б/с";
        if (bps < 1e6f) return Math.round(bps / 1e3f) + " КБ/с";
        return String.format(ru, "%.1f МБ/с", bps / 1e6f);
    }

    // ── нажатия ───────────────────────────────────────────────────────────

    /**
     * Нажатие по своему виджету. Возвращает, что делать главному экрану:
     * "prev", "play", "next" — кнопки музыки; "done" — виджет сам всё сделал;
     * null — открыть приложение (часы, календарь, музыку, батарею…).
     */
    static String tap(Context c, Layout.Item it, RectF r, float x, float y) {
        switch (it.kind == null ? "" : it.kind) {
            case "music": {
                float[] b = musicButtons(it, r);
                for (int i = 0; i < 3; i++)
                    if (Math.hypot(x - b[i], y - b[3]) < Ui.dpf(26)) return i == 0 ? "prev" : i == 1 ? "play" : "next";
                return null;
            }
            case "timer": {
                float[] b = timerButtons(r);
                if (Math.hypot(x - b[0], y - b[3]) < Ui.dpf(24)) {
                    // слева: пока не идёт — минус минута, иначе — сброс
                    if (timerEnd == 0 && timerLeft < 0) timerSet = Math.max(60_000, timerSet - 60_000);
                    else timerReset();
                } else if (Math.hypot(x - b[2], y - b[3]) < Ui.dpf(24)) {
                    if (timerEnd > 0) timerAt(c, timerEnd + 60_000);
                    else if (timerLeft >= 0) timerLeft += 60_000;
                    else timerSet = Math.min(180 * 60_000, timerSet + 60_000);
                } else timerToggle(c);
                return "done";
            }
            default:
                return null;
        }
    }

    // ── музыка ────────────────────────────────────────────────────────────

    /** x назад, x играть, x вперёд, y. */
    private static float[] musicButtons(Layout.Item it, RectF r) {
        boolean wide = r.width() > r.height() * 1.6f;
        if (wide && it.h > 1) return new float[]{r.right - Ui.dpf(132), r.right - Ui.dpf(82), r.right - Ui.dpf(32), r.bottom - Ui.dpf(34)};
        if (wide) return new float[]{r.right - Ui.dpf(124), r.right - Ui.dpf(78), r.right - Ui.dpf(32), r.centerY()};
        float cx = r.centerX(), s = Math.min(Ui.dpf(46), r.width() / 3.2f);
        return new float[]{cx - s, cx, cx + s, r.bottom - Ui.dpf(30)};
    }

    private static void music(Canvas c, Layout.Item it, RectF r) {
        int fg = Look.fg();
        float pad = Ui.dpf(14);
        boolean wide = r.width() > r.height() * 1.6f;
        float s = Math.min(Ui.dpf(it.h > 1 ? 56 : 40), r.height() - pad * 2);
        float cy = it.h > 1 ? r.top + pad + s / 2 : r.centerY();
        RectF cover = new RectF(r.left + pad, cy - s / 2, r.left + pad + s, cy + s / 2);
        if (!wide) cover.offsetTo(r.left + pad, r.top + pad);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new android.graphics.LinearGradient(cover.left, cover.top, cover.right, cover.bottom, Ui.accentLight(), Ui.accentDark(), android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(cover, s * 0.24f, s * 0.24f, p);
        p.setShader(null);
        Glyph.speaker(c, cover.centerX(), cover.centerY(), s * 0.55f, 0xFFFFFFFF);
        Paint t1 = Ui.text(Ui.bold, 15, fg), t2 = Ui.text(Ui.regular, 13, Look.fg2());
        float tx = cover.right + Ui.dpf(12);
        if (wide) {
            float ty = it.h > 1 ? cover.centerY() : r.centerY();
            c.drawText(playing ? "Играет" : "На паузе", tx, ty - Ui.dpf(2), t1);
            c.drawText("Музыка", tx, ty + Ui.dpf(16), t2);
        } else {
            c.drawText(playing ? "Играет" : "Пауза", tx, cover.centerY() + Ui.dpf(5), t1);
        }
        float[] b = musicButtons(it, r);
        float bs = Ui.dpf(22);
        Glyph.skip(c, b[0], b[3], bs, fg, false);
        p.setColor(Ui.alpha(fg, 0.14f));
        c.drawCircle(b[1], b[3], Ui.dpf(21), p);
        Glyph.play(c, b[1], b[3], bs * 1.1f, fg, playing);
        Glyph.skip(c, b[2], b[3], bs, fg, true);
    }

    // ── таймер ────────────────────────────────────────────────────────────

    static long timerSet = 5 * 60_000, timerEnd, timerLeft = -1;   // timerEnd — когда зазвонит (по часам), timerLeft — осталось на паузе
    private static final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable ring;
    static Runnable onTimer;   // главный экран: перерисовать

    private static void timerToggle(Context c) {
        if (timerEnd > 0) {
            timerLeft = Math.max(0, timerEnd - System.currentTimeMillis());
            timerEnd = 0;
            if (ring != null) h.removeCallbacks(ring);
        } else timerAt(c, System.currentTimeMillis() + (timerLeft >= 0 ? timerLeft : timerSet));
    }

    private static void timerAt(Context c, long end) {
        timerEnd = end;
        timerLeft = -1;
        Context app = c.getApplicationContext();
        if (ring != null) h.removeCallbacks(ring);
        ring = () -> {
            timerEnd = 0;
            timerLeft = -1;
            Feel.sound(app, "unlock");
            android.os.Vibrator vb = app.getSystemService(android.os.Vibrator.class);
            if (vb != null) vb.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 300, 160, 300, 160, 500}, -1));
            android.widget.Toast.makeText(app, "Таймер: время вышло", android.widget.Toast.LENGTH_LONG).show();
            if (onTimer != null) onTimer.run();
        };
        h.postDelayed(ring, Math.max(0, end - System.currentTimeMillis()));
    }

    private static void timerReset() {
        timerEnd = 0;
        timerLeft = -1;
        if (ring != null) h.removeCallbacks(ring);
    }

    /** x слева, x середина, x справа, y. */
    private static float[] timerButtons(RectF r) {
        float y = r.bottom - Ui.dpf(26), s = Math.min(Ui.dpf(48), r.width() / 3.4f);
        return new float[]{r.centerX() - s, r.centerX(), r.centerX() + s, y};
    }

    private static void timer(Canvas c, RectF r) {
        int fg = Look.fg();
        long left = timerEnd > 0 ? Math.max(0, timerEnd - System.currentTimeMillis()) : timerLeft >= 0 ? timerLeft : timerSet;
        long total = Math.max(timerSet, left);
        float frac = timerEnd == 0 && timerLeft < 0 ? 1f : left / (float) total;
        float rad = Math.min(r.width() * 0.3f, (r.height() - Ui.dpf(56)) * 0.5f);
        float cx = r.centerX(), cy = r.top + Ui.dpf(10) + rad + Ui.dpf(4);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(Ui.dpf(4), rad * 0.12f));
        p.setColor(Ui.alpha(fg, 0.14f));
        c.drawCircle(cx, cy, rad, p);
        p.setColor(Ui.accent());
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(tmp, -90, 360 * frac, false, p);
        p.setStyle(Paint.Style.FILL);
        long sec = (left + 999) / 1000;
        String txt = sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60) : String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        Paint t = Ui.text(Look.digits(Typeface()), rad / Ui.density * (sec >= 3600 ? 0.34f : 0.44f), fg);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(txt, cx, cy - (t.ascent() + t.descent()) / 2, t);
        float[] b = timerButtons(r);
        boolean idle = timerEnd == 0 && timerLeft < 0;
        p.setColor(Ui.alpha(fg, 0.12f));
        for (int i = 0; i < 3; i++) c.drawCircle(b[i], b[3], Ui.dpf(i == 1 ? 18 : 15), p);
        // слева: «−» или сброс, справа: «+»
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dpf(2));
        p.setColor(fg);
        if (idle) c.drawLine(b[0] - Ui.dpf(6), b[3], b[0] + Ui.dpf(6), b[3], p);
        else {
            tmp.set(b[0] - Ui.dpf(6), b[3] - Ui.dpf(6), b[0] + Ui.dpf(6), b[3] + Ui.dpf(6));
            c.drawArc(tmp, -60, 290, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        Glyph.plus(c, b[2], b[3], Ui.dpf(16), fg);
        Glyph.play(c, b[1], b[3], Ui.dpf(20), fg, timerEnd > 0);
    }

    // ── сеть ──────────────────────────────────────────────────────────────

    private static void net(Canvas c, Layout.Item it, RectF r) {
        int fg = Look.fg();
        float pad = Ui.dpf(14);
        Paint big = Ui.text(Look.digits(Typeface()), it.h <= 1 ? 17 : 19, fg);
        Paint sub = Ui.text(Ui.medium, 12, Look.fg2());
        boolean graph = it.w >= 3 && it.h >= 2;
        if (graph) {
            // график приёма за последние полминуты
            float max = 1;
            for (float v : rxHist) max = Math.max(max, v);
            RectF g = new RectF(r.left + pad, r.top + pad + Ui.dpf(44), r.right - pad, r.bottom - pad);
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < rxHist.length; i++) {
                float x = g.left + g.width() * i / (rxHist.length - 1);
                float y = g.bottom - g.height() * (rxHist[i] / max);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            android.graphics.Path fill = new android.graphics.Path(path);
            fill.lineTo(g.right, g.bottom);
            fill.lineTo(g.left, g.bottom);
            fill.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, g.top, 0, g.bottom, Ui.alpha(Ui.accent(), 0.45f), Ui.alpha(Ui.accent(), 0f), android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(2));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Ui.accentLight());
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            c.drawText("↓ " + speed(rx), r.left + pad, r.top + pad + Ui.dpf(16), big);
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText("↑ " + speed(tx), r.right - pad, r.top + pad + Ui.dpf(16), sub);
            sub.setTextAlign(Paint.Align.LEFT);
            c.drawText("Скорость сети", r.left + pad, r.top + pad + Ui.dpf(34), sub);
            return;
        }
        if (it.h <= 1) {
            c.drawText("↓ " + speed(rx), r.left + pad, r.centerY() + Ui.dpf(6), big);
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText("↑ " + speed(tx), r.right - pad, r.centerY() + Ui.dpf(5), sub);
            return;
        }
        Glyph.wifi(c, r.left + pad + Ui.dpf(10), r.top + pad + Ui.dpf(10), Ui.dpf(20), Ui.accentLight());
        c.drawText("↓ " + speed(rx), r.left + pad, r.bottom - pad - Ui.dpf(26), big);
        c.drawText("↑ " + speed(tx), r.left + pad, r.bottom - pad - Ui.dpf(4), sub);
    }

    // ── память и хранилище ────────────────────────────────────────────────

    /** Кольцо (или полоса, если виджет низкий) с процентом и подписью. */
    private static void meter(Canvas c, RectF r, boolean wide, float frac, String title, String sub) {
        int fg = Look.fg();
        float pad = Ui.dpf(14);
        Paint st = Ui.text(Ui.medium, 12, Look.fg2());
        String pct = frac < 0 ? "—" : Math.round(frac * 100) + "%";
        int col = frac > 0.9f ? 0xFFE5484D : Ui.accent();
        if (wide) {
            Paint t = Ui.text(Typeface(), 20, fg);
            c.drawText(pct, r.left + pad, r.top + pad + Ui.dpf(16), t);
            st.setTextAlign(Paint.Align.RIGHT);
            c.drawText(title + " · " + sub, r.right - pad, r.top + pad + Ui.dpf(14), st);
            float lv = Math.max(0, frac);
            RectF b = new RectF(r.left + pad, r.bottom - pad - Ui.dpf(8), r.right - pad, r.bottom - pad);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.alpha(fg, 0.14f));
            c.drawRoundRect(b, b.height() / 2, b.height() / 2, p);
            p.setColor(col);
            tmp.set(b.left, b.top, b.left + Math.max(b.height(), b.width() * lv), b.bottom);
            c.drawRoundRect(tmp, b.height() / 2, b.height() / 2, p);
            return;
        }
        float rad = Math.min(r.width() * 0.3f, (r.height() - Ui.dpf(48)) * 0.5f);
        float cx = r.centerX(), cy = r.top + pad + rad;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(rad * 0.2f);
        p.setColor(Ui.alpha(fg, 0.14f));
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(tmp, 135, 270, false, p);
        p.setColor(col);
        c.drawArc(tmp, 135, 270 * Math.max(0, frac), false, p);
        p.setStyle(Paint.Style.FILL);
        Paint t = Ui.text(Typeface(), rad / Ui.density * 0.5f, fg);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(pct, cx, cy - (t.ascent() + t.descent()) / 2, t);
        st.setTextAlign(Paint.Align.CENTER);
        Paint tt = Ui.text(Ui.bold, 13, fg);
        tt.setTextAlign(Paint.Align.CENTER);
        c.drawText(title, cx, r.bottom - pad - Ui.dpf(16), tt);
        c.drawText(sub, cx, r.bottom - pad, st);
    }

    private static void panel(Canvas c, RectF r, View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        View root = v.getRootView();
        Look.panel(c, r, Math.min(Look.radius(22), r.height() / 2), loc[0] + r.left, loc[1] + r.top, root.getWidth(), root.getHeight());
    }

    // ── часы ──────────────────────────────────────────────────────────────

    static boolean hideClock;   // пока часы экрана блокировки летят сюда

    /** Стиль часов на столе: с «часами из экрана блокировки» — те же, что там. */
    static String clockStyle(Layout.Item it) {
        if (Prefs.clockToHome) return LockFace.style();
        return it.style == null || it.style.isEmpty() ? Look.clock() : it.style;
    }

    /** Масштаб часов в рамке виджета. */
    static float clockK(Layout.Item it, RectF r) {
        float hDp = ClockView.heightDp(clockStyle(it)) + 10;
        float k = Math.min(r.height() / Ui.dpf(hDp), r.width() / Ui.dpf(300));
        return Math.max(0.3f, Math.min(k, 1.4f));
    }

    /** Где на столе стоят цифры (для полёта с экрана блокировки). */
    static RectF clockBox(Layout.Item it, RectF r) {
        if (Prefs.clockToHome) return LockFace.homeRect(r);
        float k = clockK(it, r);
        float hh = Ui.dpf(ClockView.heightDp(clockStyle(it))) * k;
        float ww = Math.min(r.width(), Ui.dpf(300) * k * 0.85f);
        float top = r.top + Ui.dpf(4);
        return new RectF(r.centerX() - ww / 2, top, r.centerX() + ww / 2, top + hh);
    }

    private static void clock(Canvas c, Layout.Item it, RectF r, View v) {
        if (hideClock) return;
        float k = clockK(it, r);
        c.save();
        c.clipRect(r.left - Ui.dpf(20), r.top - Ui.dpf(10), r.right + Ui.dpf(20), r.bottom + Ui.dpf(10));
        String tint = Prefs.lockClockColor;
        if (Prefs.clockToHome) LockFace.drawHome(c, v.getContext(), tint, r);
        else ClockView.draw(c, v.getContext(), clockStyle(it), tint, r.left + Ui.dpf(10), r.top + Ui.dpf(4), k, false);
        c.restore();
    }

    private static void analog(Canvas c, RectF r) {
        float cx = r.centerX(), cy = r.centerY(), rad = Math.min(r.width(), r.height()) * 0.38f;
        int fg = Look.fg();
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            float d = i % 3 == 0 ? rad * 0.06f : rad * 0.035f;
            p.setColor(Ui.alpha(fg, i % 3 == 0 ? 0.9f : 0.45f));
            c.drawCircle(cx + (float) Math.sin(a) * rad * 0.9f, cy - (float) Math.cos(a) * rad * 0.9f, d, p);
        }
        Calendar cal = Calendar.getInstance();
        float min = cal.get(Calendar.MINUTE), hr = cal.get(Calendar.HOUR) + min / 60f;
        hand(c, cx, cy, hr / 12f, rad * 0.5f, rad * 0.075f, fg);
        hand(c, cx, cy, min / 60f, rad * 0.78f, rad * 0.05f, fg);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.accent());
        c.drawCircle(cx, cy, rad * 0.08f, p);
    }

    private static void hand(Canvas c, float cx, float cy, float turn, float len, float w, int color) {
        double a = Math.PI * 2 * turn;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawLine(cx, cy, cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
    }

    // ── календарь ─────────────────────────────────────────────────────────

    private static void calendar(Canvas c, RectF r, boolean month) {
        Locale ru = new Locale("ru");
        Calendar cal = Calendar.getInstance();
        int fg = Look.fg();
        float pad = Ui.dpf(14);
        Paint t = Ui.text(Ui.bold, 13, Ui.accentLight());
        String wd = new SimpleDateFormat("EEEE", ru).format(new Date());
        wd = wd.isEmpty() ? wd : Character.toUpperCase(wd.charAt(0)) + wd.substring(1);
        if (!month) {
            c.drawText(wd, r.left + pad, r.top + pad + Ui.dpf(12), t);
            Paint d = Ui.text(Typeface(), Math.min(r.height(), r.width()) / Ui.density * 0.42f, fg);
            c.drawText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)), r.left + pad - Ui.dpf(2), r.bottom - pad - Ui.dpf(18), d);
            Paint m = Ui.text(Ui.medium, 13, Look.fg2());
            c.drawText(new SimpleDateFormat("LLLL", ru).format(new Date()), r.left + pad, r.bottom - pad, m);
            return;
        }
        String title = new SimpleDateFormat("LLLL", ru).format(new Date());
        title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
        c.drawText(title, r.left + pad, r.top + pad + Ui.dpf(12), t);
        int today = cal.get(Calendar.DAY_OF_MONTH);
        Calendar first = (Calendar) cal.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int shift = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // понедельник — первый
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (shift + days + 6) / 7;
        float top = r.top + pad + Ui.dpf(24);
        float cw = (r.width() - 2 * pad) / 7f, ch = (r.bottom - pad - top) / (rows + 1);
        float ts = Math.min(cw, ch) / Ui.density * 0.46f;
        Paint head = Ui.text(Ui.medium, ts * 0.85f, Look.fg2());
        head.setTextAlign(Paint.Align.CENTER);
        String[] wds = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (int i = 0; i < 7; i++) c.drawText(wds[i], r.left + pad + cw * (i + 0.5f), top + ch * 0.62f, head);
        Paint num = Ui.text(Ui.medium, ts, fg);
        num.setTextAlign(Paint.Align.CENTER);
        for (int d = 1; d <= days; d++) {
            int cell = shift + d - 1;
            float x = r.left + pad + cw * (cell % 7 + 0.5f), y = top + ch * (cell / 7 + 1.5f);
            if (d == today) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.accent());
                c.drawCircle(x, y, Math.min(cw, ch) * 0.42f, p);
                num.setColor(0xFFFFFFFF);
            } else num.setColor(cell % 7 >= 5 ? Look.fg2() : fg);
            c.drawText(String.valueOf(d), x, y - (num.ascent() + num.descent()) / 2, num);
        }
    }

    private static android.graphics.Typeface Typeface() {
        return android.graphics.Typeface.create(Ui.bold, 700, false);
    }

    // ── батарея ───────────────────────────────────────────────────────────

    private static void battery(Canvas c, RectF r, boolean wide) {
        int fg = Look.fg();
        float lv = battery < 0 ? 0 : battery / 100f;
        float pad = Ui.dpf(14);
        if (wide) {
            // полоской: проценты слева, полоса во всю ширину
            Paint t = Ui.text(Typeface(), 26, fg);
            c.drawText(battery < 0 ? "—" : battery + "%", r.left + pad, r.centerY() + Ui.dpf(4), t);
            bar(c, new RectF(r.left + pad, r.bottom - pad - Ui.dpf(8), r.right - pad, r.bottom - pad), lv);
            if (charging) Glyph.bolt(c, r.right - pad - Ui.dpf(10), r.top + pad + Ui.dpf(10), Ui.dpf(16), Ui.accentLight());
            return;
        }
        // кольцом
        float cx = r.centerX(), cy = r.centerY(), rad = Math.min(r.width(), r.height()) * 0.34f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(rad * 0.2f);
        p.setColor(Ui.alpha(fg, 0.14f));
        c.drawCircle(cx, cy, rad, p);
        p.setColor(lv < 0.2f && !charging ? 0xFFE5484D : Ui.accent());
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(tmp, -90, 360 * lv, false, p);
        Paint t = Ui.text(Typeface(), rad / Ui.density * 0.62f, fg);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(battery < 0 ? "—" : String.valueOf(battery), cx, cy - (t.ascent() + t.descent()) / 2, t);
        if (charging) Glyph.bolt(c, cx, cy + rad * 0.62f, rad * 0.4f, Ui.accentLight());
    }

    private static void bar(Canvas c, RectF r, float lv) {
        float rad = r.height() / 2;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.alpha(Look.fg(), 0.14f));
        c.drawRoundRect(r, rad, rad, p);
        p.setColor(lv < 0.2f && !charging ? 0xFFE5484D : Ui.accent());
        tmp.set(r.left, r.top, r.left + Math.max(r.height(), r.width() * lv), r.bottom);
        c.drawRoundRect(tmp, rad, rad, p);
    }

    private VWidgets() {}
}
