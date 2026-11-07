package os.vanks.launcher;

import android.app.AlarmManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Часы и дата вверху главного экрана. Вид — от темы, как в Delta:
 * ios (дата и крупное время), tall (высокие узкие цифры), minimal (строкой),
 * stack (часы над минутами), dots (цифры из точек, Nothing).
 */
final class ClockView extends View {
    ClockView(Context c) {
        super(c);
    }

    static String timeText(Context c) {
        return new SimpleDateFormat(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
    }

    static String dateText() {
        String s = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date());
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String nextAlarm(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo a = am == null ? null : am.getNextAlarmClock();
        if (a == null) return null;
        return new SimpleDateFormat(DateFormat.is24HourFormat(c) ? "EE HH:mm" : "EE h:mm a", new Locale("ru")).format(new Date(a.getTriggerTime()));
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(Look.clock().equals("minimal") ? 96 : Look.clock().equals("stack") ? 196 : 158));
    }

    @Override
    protected void onDraw(Canvas c) {
        float bottom = draw(c, getContext(), Look.clock(), Ui.dpf(26), Ui.dpf(14), 1f, false);
        String al = nextAlarm(getContext());
        if (al != null) {
            Paint s = shadow(Ui.text(Ui.regular, 13, 0xB3FFFFFF));
            c.drawText("Будильник · " + al, Ui.dpf(26), bottom + Ui.dpf(20), s);
        }
    }

    private static Paint shadow(Paint p) {
        p.setShadowLayer(Ui.dpf(6), 0, Ui.dpf(1), 0x55000000);
        return p;
    }

    /** Часы Delta (BigClock): id → название. Пустой выбор — часы темы. */
    static final String[] STYLES = {"long", "ios", "thin", "mono", "neon", "stack", "serif", "wide", "outline", "minimal", "tall", "dots"};
    static final String[] STYLE_NAMES = {"Длинные", "Классика", "Тонкие", "Моно", "Неон", "Стопка", "С засечками", "Широкие", "Контур", "Строкой", "Высокие", "Точки"};
    static final String[] TINTS = {"white", "accent", "gradient", "glass"};
    static final String[] TINT_NAMES = {"Белые", "Акцент", "Градиент", "Стекло"};

    static float draw(Canvas c, Context ctx, String style, float x, float top, float k, boolean center) {
        return draw(c, ctx, style, "white", x, top, k, center);
    }

    /**
     * Нарисовать часы с датой. x — левый край (или середина, если center),
     * top — верх; k — масштаб (экран входа крупнее); tint — белые, акцент,
     * градиент (белый → акцент) или стекло. Возвращает нижний край.
     */
    static float draw(Canvas c, Context ctx, String style, String tint, float x, float top, float k, boolean center) {
        if (style.equals("poster")) style = "long";   // «Афишу» заменили «Длинные»
        String t = timeText(ctx);
        Paint date = shadow(Ui.text(Ui.medium, 16 * Math.min(k, 1.15f), 0xE6FFFFFF));
        Paint.Align al = center ? Paint.Align.CENTER : Paint.Align.LEFT;
        date.setTextAlign(al);
        switch (style) {
            case "minimal": {
                Paint tp = digits(Ui.text(Look.digits(Ui.regular), 46 * k, 0xFFFFFFFF), tint);
                tp.setTextAlign(al);
                float base = top + Ui.dpf(46 * k);
                text(c, t, x, base, tp, tint);
                dateLine(c, dateText(), x, base + Ui.dpf(26 * k), date);
                return base + Ui.dpf(26 * k);
            }
            case "stack": {
                String[] hm = t.split(":");
                Paint tp = digits(Ui.text(Typeface.create(Ui.bold, 800, false), 84 * k, 0xFFFFFFFF), tint);
                tp.setTextAlign(al);
                float base = top + Ui.dpf(74 * k);
                text(c, hm[0], x, base, tp, tint);
                if (tint.equals("white")) tp.setColor(Ui.accentLight());
                text(c, hm.length > 1 ? hm[1] : "", x, base + Ui.dpf(78 * k), tp, tint);
                dateLine(c, dateText(), x, base + Ui.dpf(108 * k), date);
                return base + Ui.dpf(108 * k);
            }
            case "long": {
                // «Длинные»: свои высокие цифры — ровная линия с круглыми концами,
                // овалы с прямыми боками, двоеточие из двух точек. Растяжение
                // (stretchX, stretchY) меняет размер цифр, а не толщину линии.
                // Виды: пустые внутри, часы стеклянные, всё стекло, обычные.
                String[] hm = t.split(":");
                String hh = hm[0], mm = hm.length > 1 ? hm[1] : "";
                float dw = Ui.dpf(LONG_W * k) * stretchX * Prefs.longDigitW, dh = Ui.dpf(LONG_H * k) * stretchY;
                float sw = Ui.dpf(LONG_STROKE * k) * Prefs.longStroke * Math.min(1.25f, Math.max(0.8f, (float) Math.sqrt(stretchX * Prefs.longDigitW)));
                sw = Math.min(sw, dw * 0.45f);
                float gap = Ui.dpf(11 * k) * stretchX * (0.6f + 0.4f * Prefs.longDigitW), colon = Ui.dpf(30 * k) * Math.max(0.8f, stretchX);
                float wh = hh.length() * dw + Math.max(0, hh.length() - 1) * gap;
                float wm = mm.length() * dw + Math.max(0, mm.length() - 1) * gap;
                float total = wh + colon + wm;
                float x0 = center ? x - total / 2 : x;
                float dtop = top + Ui.dpf(4 * k);
                int hc = Prefs.posterFront == 0 ? Ui.accentLight() : Prefs.posterFront;
                int mc = Prefs.posterBack == 0 ? Ui.accentLight() : Prefs.posterBack;
                String kind = Prefs.posterKind;
                int hMode = kind.equals("hollow") ? 0 : kind.equals("solid") ? 2 : 1;
                int mMode = kind.equals("hollow") ? 0 : kind.equals("glass") ? 1 : 2;
                float cx = x0;
                for (int i = 0; i < hh.length(); i++) {
                    longShape(c, longDigit(hh.charAt(i), dw, dh, sw), cx, dtop, dh, hc, hMode, k);
                    cx += dw + gap;
                }
                // двоеточие — две точки цвета часов
                float dx = x0 + wh + colon / 2, dr = sw * 0.62f;
                android.graphics.Path dots = new android.graphics.Path();
                dots.addCircle(dx, dtop + dh * 0.36f, dr, android.graphics.Path.Direction.CW);
                dots.addCircle(dx, dtop + dh * 0.68f, dr, android.graphics.Path.Direction.CW);
                longShape(c, dots, 0, 0, dh, hc, hMode, k);
                cx = x0 + wh + colon;
                for (int i = 0; i < mm.length(); i++) {
                    longShape(c, longDigit(mm.charAt(i), dw, dh, sw), cx, dtop, dh, mc, mMode, k);
                    cx += dw + gap;
                }
                Paint d1 = shadow(Ui.text(Ui.medium, 18 * Math.min(k, 1.2f), 0xF2FFFFFF));
                d1.setTextAlign(al);
                Locale ru = new Locale("ru");
                String day = new SimpleDateFormat("EEEE, d MMMM", ru).format(new Date());
                day = day.isEmpty() ? day : Character.toUpperCase(day.charAt(0)) + day.substring(1);
                float base = dtop + dh + Ui.dpf(30 * Math.min(k, 1.2f));
                c.drawText(day, center ? x : x0 + Ui.dpf(2), base, d1);
                return base;
            }
            case "dots": {
                float dot = Ui.dpf(5.2f * k), step = dot * 1.9f;
                float w = dotsWidth(t, step);
                float x0 = center ? x - w / 2 : x;
                drawDots(c, t, x0, top + Ui.dpf(6 * k), dot, step, tint.equals("white") || tint.equals("glass") ? 0xFFFFFFFF : Ui.accentLight());
                float base = top + Ui.dpf(6 * k) + step * 7 + Ui.dpf(26 * k);
                Paint d = shadow(Ui.text(Typeface.MONOSPACE, 14 * Math.min(k, 1.15f), 0xE6FFFFFF));
                d.setTextAlign(al);
                d.setLetterSpacing(0.12f);
                dateLine(c, dateText().toUpperCase(new Locale("ru")), x, base, d);
                return base;
            }
            case "tall": {
                Paint d = shadow(Ui.text(Ui.medium, 15 * Math.min(k, 1.15f), 0xE6FFFFFF));
                d.setTextAlign(al);
                dateLine(c, dateText(), x, top + Ui.dpf(18 * k), d);
                Paint tp = digits(Ui.text(Typeface.create(Ui.light, 300, false), 104 * k, 0xFFFFFFFF), tint);
                tp.setTextAlign(al);
                tp.setTextScaleX(0.78f);
                tp.setLetterSpacing(-0.02f);
                float base = top + Ui.dpf(112 * k);
                text(c, t, center ? x : x - Ui.dpf(4), base, tp, tint);
                return base;
            }
            case "ios":
            case "thin":
            case "mono":
            case "neon":
            case "serif":
            case "wide":
            case "outline": {
                // как BigClock в Delta: размер, насыщенность и разрядка у каждого свои
                float f = style.equals("mono") ? 0.84f : style.equals("serif") ? 0.92f : style.equals("wide") ? 0.86f : 1f;
                Typeface tf;
                switch (style) {
                    case "thin": tf = Typeface.create(Ui.light, 200, false); break;
                    case "mono": tf = Typeface.create(Typeface.MONOSPACE, 500, false); break;
                    case "neon": tf = Typeface.create(Ui.regular, 600, false); break;
                    case "wide": tf = Typeface.create(Ui.light, 300, false); break;
                    case "serif": tf = Typeface.create(Typeface.SERIF, Typeface.NORMAL); break;
                    case "outline": tf = Typeface.create(Ui.bold, 700, false); break;
                    default: tf = Typeface.create(Ui.regular, 600, false);
                }
                dateLine(c, dateText(), x, top + Ui.dpf(18 * k), date);
                Paint tp = digits(Ui.text(tf, 82 * f * k, 0xFFFFFFFF), tint);
                tp.setTextAlign(al);
                tp.setLetterSpacing(style.equals("wide") ? 0.075f : style.equals("mono") ? -0.04f : style.equals("thin") ? -0.02f : -0.035f);
                if (style.equals("outline")) {
                    tp.setStyle(Paint.Style.STROKE);
                    tp.setStrokeWidth(Ui.dpf(1.6f * k));
                }
                if (style.equals("neon")) {
                    int glow = tint.equals("white") ? Ui.accent() : tp.getColor();
                    tp.setShadowLayer(Ui.dpf(14 * k), 0, 0, glow);
                }
                float base = top + Ui.dpf(100 * k) - Ui.dpf(82 * (1 - f) * k * 0.7f);
                text(c, t, center ? x : x - Ui.dpf(3), base, tp, tint);
                if (style.equals("neon")) {
                    // второй слой — ярче в середине, как у неоновой трубки
                    tp.setShadowLayer(Ui.dpf(4 * k), 0, 0, 0xFFFFFFFF);
                    text(c, t, center ? x : x - Ui.dpf(3), base, tp, tint);
                }
                return base;
            }
            default:
                return draw(c, ctx, Look.clock(), tint, x, top, k, center);
        }
    }

    // ── цифры «Длинных» ───────────────────────────────────────────────────

    /** Размер цифры «Длинных» в dp при k = 1: ширина, высота, толщина линии. */
    static final float LONG_W = 50, LONG_H = 172, LONG_STROKE = 13.5f;

    private static final java.util.LinkedHashMap<String, android.graphics.Path> longCache = new java.util.LinkedHashMap<String, android.graphics.Path>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Path> e) {
            return size() > 40;
        }
    };

    /**
     * Контур цифры w × h (от 0, 0): средняя линия из прямых и полукругов
     * радиусом в половину ширины, обведённая линией sw с круглыми концами и
     * слитая в один контур (без внутренних линий на стыках).
     */
    static android.graphics.Path longDigit(char ch, float w, float h, float sw) {
        String key = ch + "|" + Math.round(w * 4) + "|" + Math.round(h * 4) + "|" + Math.round(sw * 4);
        android.graphics.Path got = longCache.get(key);
        if (got != null) return got;
        float L = sw / 2, T = sw / 2, R = w - sw / 2, B = h - sw / 2;
        float iw = R - L, ih = B - T, r = iw / 2, cxm = L + iw / 2;
        android.graphics.Path p = new android.graphics.Path();
        android.graphics.Path.Direction cw = android.graphics.Path.Direction.CW;
        switch (ch) {
            case '0':
                p.addRoundRect(L, T, R, B, r, r, cw);
                break;
            case '1':
                p.moveTo(L + iw * 0.68f, B);
                p.lineTo(L + iw * 0.68f, T);
                p.lineTo(L, T + ih * 0.15f);
                break;
            case '2':
                p.moveTo(L, T + r);
                p.arcTo(L, T, R, T + 2 * r, 180, 180, false);
                p.lineTo(R, T + r + ih * 0.07f);
                p.lineTo(L, B);
                p.lineTo(R, B);
                break;
            case '3': {
                float m = T + ih * 0.46f;
                p.moveTo(L, T + r);
                p.arcTo(L, T, R, T + 2 * r, 180, 180, false);
                p.lineTo(R, m - r);
                p.arcTo(L, m - 2 * r, R, m, 0, 90, false);
                p.lineTo(L + iw * 0.28f, m);
                p.moveTo(cxm, m);
                p.arcTo(L, m, R, m + 2 * r, -90, 90, false);
                p.lineTo(R, B - r);
                p.arcTo(L, B - 2 * r, R, B, 0, 180, false);
                break;
            }
            case '4': {
                float y = T + ih * 0.68f;
                p.moveTo(L + iw * 0.78f, B);
                p.lineTo(L + iw * 0.78f, T);
                p.lineTo(L, y);
                p.lineTo(R, y);
                break;
            }
            case '5': {
                float m = T + ih * 0.42f;
                p.moveTo(R, T);
                p.lineTo(L, T);
                p.lineTo(L, m + r);
                p.arcTo(L, m, R, m + 2 * r, 180, 180, false);
                p.lineTo(R, B - r);
                p.arcTo(L, B - 2 * r, R, B, 0, 180, false);
                break;
            }
            case '6': {
                float m = T + ih * 0.42f;
                p.moveTo(R, T + r);
                p.arcTo(L, T, R, T + 2 * r, 0, -180, false);
                p.lineTo(L, B - r);
                p.addRoundRect(L, m, R, B, r, r, cw);
                break;
            }
            case '7':
                p.moveTo(L, T);
                p.lineTo(R, T);
                p.lineTo(L + iw * 0.22f, B);
                break;
            case '8': {
                float m = T + ih * 0.45f, inset = iw * 0.05f;
                p.addRoundRect(L + inset, T, R - inset, m, r - inset, r - inset, cw);
                p.addRoundRect(L, m, R, B, r, r, cw);
                break;
            }
            default: {   // '9'
                float m = T + ih * 0.58f;
                p.addRoundRect(L, T, R, m, r, r, cw);
                p.moveTo(R, T + r);
                p.lineTo(R, B - r);
                p.arcTo(L, B - 2 * r, R, B, 0, 180, false);
            }
        }
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(sw);
        st.setStrokeCap(Paint.Cap.ROUND);
        st.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path outline = new android.graphics.Path();
        st.getFillPath(p, outline);
        // слить перекрытия в один контур, чтобы обводка шла только снаружи
        android.graphics.Path one = new android.graphics.Path();
        if (!one.op(outline, android.graphics.Path.Op.UNION)) one = outline;
        longCache.put(key, one);
        return one;
    }

    /**
     * Цифра или точки «Длинных» в (x, y). mode: 0 — пустые внутри (только
     * контур), 1 — стекло (полупрозрачная заливка, блик и светлая кромка),
     * 2 — обычные (сплошной цвет).
     */
    private static void longShape(Canvas c, android.graphics.Path shape, float x, float y, float h, int col, int mode, float k) {
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.translate(x, y);
        switch (mode) {
            case 0:
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeJoin(Paint.Join.ROUND);
                q.setStrokeWidth(Ui.dpf(2f * k));
                q.setShadowLayer(Ui.dpf(5 * k), 0, Ui.dpf(1), 0x55000000);
                q.setColor(col);
                c.drawPath(shape, q);
                break;
            case 1: {
                // тень, чтобы стекло отделялось от обоев
                q.setShadowLayer(Ui.dpf(12 * k), 0, Ui.dpf(3 * k), 0x38000000);
                q.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                        Ui.alpha(Ui.mix(col, 0xFFFFFFFF, 0.25f), 0.56f), Ui.alpha(col, 0.40f), android.graphics.Shader.TileMode.CLAMP));
                c.drawPath(shape, q);
                q.clearShadowLayer();
                q.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.3f, 0x26FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                c.drawPath(shape, q);
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(Ui.dpf(1.1f * k));
                q.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                        0xD9FFFFFF, Ui.alpha(Ui.mix(col, 0xFFFFFFFF, 0.5f), 0.45f), android.graphics.Shader.TileMode.CLAMP));
                c.drawPath(shape, q);
                break;
            }
            default:
                q.setShadowLayer(Ui.dpf(10 * k), 0, Ui.dpf(2 * k), 0x40000000);
                q.setColor(col);
                c.drawPath(shape, q);
        }
        c.restore();
    }

    /**
     * Растяжение часов (как у виджета на ПК): цифры тянутся в длину или
     * ширину, а дата — нет. Ставит LockFace, пока рисует растянутые часы.
     * «Длинные» тянут свои цифры сами, остальные — растяжением холста.
     */
    static float stretchX = 1f, stretchY = 1f;

    /** Строка даты: обратное растяжение вокруг её точки, чтобы буквы были обычными. */
    private static void dateLine(Canvas c, String s, float x, float y, Paint p) {
        if (stretchX == 1f && stretchY == 1f) {
            c.drawText(s, x, y, p);
            return;
        }
        c.save();
        c.scale(1f / stretchX, 1f / stretchY, x, y);
        c.drawText(s, x, y, p);
        c.restore();
    }

    /** Цвет цифр. Градиент кладётся в text(), когда известна ширина. */
    private static Paint digits(Paint p, String tint) {
        p.setShadowLayer(Ui.dpf(6), 0, Ui.dpf(1), 0x55000000);
        switch (tint) {
            case "accent": p.setColor(Ui.accentLight()); break;
            case "glass": p.setColor(0xB3FFFFFF); break;
        }
        return p;
    }

    private static void text(Canvas c, String s, float x, float base, Paint p, String tint) {
        if (tint.equals("gradient")) {
            float w = p.measureText(s);
            float x0 = p.getTextAlign() == Paint.Align.CENTER ? x - w / 2 : x;
            p.setShader(new android.graphics.LinearGradient(x0, base - p.getTextSize(), x0 + w, base,
                    new int[]{0xFFFFFFFF, Ui.accentLight(), Ui.mix(Ui.accent(), 0xFFE0567F, 0.5f)}, null, android.graphics.Shader.TileMode.CLAMP));
        }
        if (tint.equals("glass")) {
            // стекло: полупрозрачная заливка и светлый край
            c.drawText(s, x, base, p);
            Paint rim = new Paint(p);
            rim.setShader(null);
            rim.clearShadowLayer();
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Ui.dpf(1));
            rim.setColor(0xCCFFFFFF);
            c.drawText(s, x, base, rim);
            return;
        }
        c.drawText(s, x, base, p);
        p.setShader(null);
    }

    /** Высота часов в dp при k = 1 — для места на экране входа и виджета. */
    static float heightDp(String style) {
        if (style.equals("poster")) style = "long";
        switch (style) {
            case "minimal": return 78;
            case "stack": return 186;
            case "dots": return 104;
            case "long": return LONG_H + 40;
            case "tall": return 120;
            case "": return heightDp(Look.clock());
        }
        return 108;
    }

    // ── цифры из точек (Nothing) ──────────────────────────────────────────

    private static final String[] DOTS = {
            "01110100011001110101110011000101110", // 0
            "00100011000010000100001000010001110", // 1
            "01110100010000100010001000100011111", // 2
            "11111000100010000010000011000101110", // 3
            "00010001100101010010111110001000010", // 4
            "11111100001111000001000011000101110", // 5
            "00110010001000011110100011000101110", // 6
            "11111000010001000100010000100001000", // 7
            "01110100011000101110100011000101110", // 8
            "01110100011000101111000010001001100", // 9
    };

    private static float dotsWidth(String t, float step) {
        float w = 0;
        for (char ch : t.toCharArray()) w += ch == ':' ? step * 2 : step * 6;
        return w - step;
    }

    private static void drawDots(Canvas c, String t, float x, float y, float dot, float step, int color) {
        Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
        on.setColor(color);
        on.setShadowLayer(Ui.dpf(4), 0, 0, 0x66000000);
        Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
        off.setColor(0x26FFFFFF);
        for (char ch : t.toCharArray()) {
            if (ch == ':') {
                c.drawCircle(x + dot / 2, y + step * 2 + dot / 2, dot / 2, on);
                c.drawCircle(x + dot / 2, y + step * 4 + dot / 2, dot / 2, on);
                x += step * 2;
                continue;
            }
            if (ch < '0' || ch > '9') continue;
            String g = DOTS[ch - '0'];
            for (int r = 0; r < 7; r++)
                for (int col = 0; col < 5; col++)
                    c.drawCircle(x + col * step + dot / 2, y + r * step + dot / 2, dot / 2, g.charAt(r * 5 + col) == '1' ? on : off);
            x += step * 6;
        }
    }
}
