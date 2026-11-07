package os.vanks.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Простые значки линиями: рисуются в квадрате со стороной s с центром (cx, cy). */
final class Glyph {
    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();
    private static final RectF r = new RectF();

    private static Paint stroke(int color, float s) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(Math.max(1.5f, s * 0.085f));
        p.setColor(color);
        return p;
    }

    static void fingerprint(Canvas c, float cx, float cy, float s, int color, float sweep) {
        Paint q = stroke(color, s);
        q.setStrokeWidth(Math.max(1.5f, s * 0.06f));
        for (int i = 0; i < 5; i++) {
            float rr = s * (0.1f + i * 0.095f);
            r.set(cx - rr, cy - rr * 1.15f, cx + rr, cy + rr * 1.15f);
            float start = 200 + i * 8, len = 140 - i * 6;
            if (i == 0) { start = 180; len = 200; }
            c.drawArc(r, start, len * sweep, false, q);
            if (i > 0 && i < 4) c.drawArc(r, 20 - i * 5, (60 + i * 10) * sweep, false, q);
        }
    }

    static void wifi(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        for (int i = 1; i <= 3; i++) {
            float rr = s * 0.16f * i;
            r.set(cx - rr, cy + s * 0.28f - rr, cx + rr, cy + s * 0.28f + rr);
            c.drawArc(r, 225, 90, false, q);
        }
        q.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy + s * 0.26f, s * 0.06f, q);
    }

    static void speaker(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        path.reset();
        path.moveTo(cx - s * 0.36f, cy - s * 0.12f);
        path.lineTo(cx - s * 0.18f, cy - s * 0.12f);
        path.lineTo(cx + s * 0.02f, cy - s * 0.3f);
        path.lineTo(cx + s * 0.02f, cy + s * 0.3f);
        path.lineTo(cx - s * 0.18f, cy + s * 0.12f);
        path.lineTo(cx - s * 0.36f, cy + s * 0.12f);
        path.close();
        c.drawPath(path, q);
        r.set(cx - s * 0.12f, cy - s * 0.2f, cx + s * 0.28f, cy + s * 0.2f);
        c.drawArc(r, -50, 100, false, q);
        r.set(cx - s * 0.2f, cy - s * 0.34f, cx + s * 0.42f, cy + s * 0.34f);
        c.drawArc(r, -50, 100, false, q);
    }

    static void torch(Canvas c, float cx, float cy, float s, int color, boolean on) {
        Paint q = stroke(color, s);
        path.reset();
        path.moveTo(cx - s * 0.2f, cy - s * 0.34f);
        path.lineTo(cx + s * 0.2f, cy - s * 0.34f);
        path.lineTo(cx + s * 0.2f, cy - s * 0.2f);
        path.lineTo(cx + s * 0.1f, cy - s * 0.04f);
        path.lineTo(cx + s * 0.1f, cy + s * 0.36f);
        path.lineTo(cx - s * 0.1f, cy + s * 0.36f);
        path.lineTo(cx - s * 0.1f, cy - s * 0.04f);
        path.lineTo(cx - s * 0.2f, cy - s * 0.2f);
        path.close();
        if (on) {
            q.setStyle(Paint.Style.FILL_AND_STROKE);
        }
        c.drawPath(path, q);
        if (!on) c.drawLine(cx - s * 0.2f, cy - s * 0.2f, cx + s * 0.2f, cy - s * 0.2f, q);
    }

    static void gear(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawCircle(cx, cy, s * 0.13f, q);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            float x1 = cx + (float) Math.cos(a) * s * 0.26f, y1 = cy + (float) Math.sin(a) * s * 0.26f;
            float x2 = cx + (float) Math.cos(a) * s * 0.38f, y2 = cy + (float) Math.sin(a) * s * 0.38f;
            c.drawLine(x1, y1, x2, y2, q);
        }
        c.drawCircle(cx, cy, s * 0.27f, q);
    }

    static void lock(Canvas c, float cx, float cy, float s, int color, boolean open) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.26f, cy - s * 0.04f, cx + s * 0.26f, cy + s * 0.36f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, q);
        r.set(cx - s * 0.16f, cy - s * 0.36f, cx + s * 0.16f, cy + s * 0.04f);
        if (open) {
            r.offset(s * 0.2f, -s * 0.04f);
            c.drawArc(r, 180, 180, false, q);
        } else {
            c.drawArc(r, 180, 180, false, q);
            c.drawLine(r.left, r.centerY(), r.left, cy - s * 0.04f, q);
            c.drawLine(r.right, r.centerY(), r.right, cy - s * 0.04f, q);
        }
    }

    static void search(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawCircle(cx - s * 0.06f, cy - s * 0.06f, s * 0.24f, q);
        c.drawLine(cx + s * 0.12f, cy + s * 0.12f, cx + s * 0.32f, cy + s * 0.32f, q);
    }

    static void plus(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, q);
        c.drawLine(cx, cy - s * 0.3f, cx, cy + s * 0.3f, q);
    }

    static void close(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx - s * 0.22f, cy - s * 0.22f, cx + s * 0.22f, cy + s * 0.22f, q);
        c.drawLine(cx + s * 0.22f, cy - s * 0.22f, cx - s * 0.22f, cy + s * 0.22f, q);
    }

    /** Уголок «потяни, чтобы изменить размер». */
    static void resize(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx - s * 0.26f, cy + s * 0.26f, cx + s * 0.26f, cy - s * 0.26f, q);
        c.drawLine(cx + s * 0.26f, cy - s * 0.26f, cx + s * 0.26f, cy, q);
        c.drawLine(cx + s * 0.26f, cy - s * 0.26f, cx, cy - s * 0.26f, q);
        c.drawLine(cx - s * 0.26f, cy + s * 0.26f, cx - s * 0.26f, cy, q);
        c.drawLine(cx - s * 0.26f, cy + s * 0.26f, cx, cy + s * 0.26f, q);
    }

    /** Молния: заряжается. */
    static void bolt(Canvas c, float cx, float cy, float s, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        path.reset();
        path.moveTo(cx + s * 0.08f, cy - s * 0.5f);
        path.lineTo(cx - s * 0.3f, cy + s * 0.08f);
        path.lineTo(cx - s * 0.02f, cy + s * 0.08f);
        path.lineTo(cx - s * 0.08f, cy + s * 0.5f);
        path.lineTo(cx + s * 0.3f, cy - s * 0.08f);
        path.lineTo(cx + s * 0.02f, cy - s * 0.08f);
        path.close();
        c.drawPath(path, p);
    }

    static void chevronUp(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx - s * 0.3f, cy + s * 0.12f, cx, cy - s * 0.14f, q);
        c.drawLine(cx, cy - s * 0.14f, cx + s * 0.3f, cy + s * 0.12f, q);
    }

    static void bell(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        path.reset();
        path.moveTo(cx - s * 0.28f, cy + s * 0.16f);
        path.cubicTo(cx - s * 0.22f, cy + s * 0.05f, cx - s * 0.24f, cy - s * 0.3f, cx, cy - s * 0.32f);
        path.cubicTo(cx + s * 0.24f, cy - s * 0.3f, cx + s * 0.22f, cy + s * 0.05f, cx + s * 0.28f, cy + s * 0.16f);
        path.close();
        c.drawPath(path, q);
        c.drawLine(cx - s * 0.06f, cy + s * 0.28f, cx + s * 0.06f, cy + s * 0.28f, q);
    }

    static void moon(Canvas c, float cx, float cy, float s, int color, boolean fill) {
        Paint q = stroke(color, s);
        if (fill) q.setStyle(Paint.Style.FILL_AND_STROKE);
        path.reset();
        r.set(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f);
        path.arcTo(r, 60, 260, false);
        r.set(cx - s * 0.12f, cy - s * 0.36f, cx + s * 0.34f, cy + s * 0.1f);
        path.arcTo(r, 270, -170, false);
        path.close();
        c.drawPath(path, q);
    }

    static void palette(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawCircle(cx, cy, s * 0.34f, q);
        q.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - s * 0.13f, cy - s * 0.1f, s * 0.05f, q);
        c.drawCircle(cx + s * 0.04f, cy - s * 0.17f, s * 0.05f, q);
        c.drawCircle(cx + s * 0.17f, cy - s * 0.02f, s * 0.05f, q);
        c.drawCircle(cx + s * 0.02f, cy + s * 0.16f, s * 0.07f, q);
    }

    static void image(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.34f, cy - s * 0.28f, cx + s * 0.34f, cy + s * 0.28f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, q);
        path.reset();
        path.moveTo(cx - s * 0.3f, cy + s * 0.2f);
        path.lineTo(cx - s * 0.08f, cy - s * 0.04f);
        path.lineTo(cx + s * 0.06f, cy + s * 0.1f);
        path.lineTo(cx + s * 0.14f, cy + s * 0.02f);
        path.lineTo(cx + s * 0.3f, cy + s * 0.2f);
        c.drawPath(path, q);
        c.drawCircle(cx + s * 0.14f, cy - s * 0.12f, s * 0.04f, q);
    }

    static void grid(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        float a = s * 0.13f, d = s * 0.17f;
        for (int i = -1; i <= 1; i += 2)
            for (int j = -1; j <= 1; j += 2) {
                r.set(cx + i * d - a, cy + j * d - a, cx + i * d + a, cy + j * d + a);
                c.drawRoundRect(r, s * 0.05f, s * 0.05f, q);
            }
    }

    static void sliders(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * s * 0.2f;
            c.drawLine(cx - s * 0.32f, y, cx + s * 0.32f, y, q);
            float k = cx + (i == 0 ? s * 0.12f : -s * 0.12f * i);
            Paint f = new Paint(q);
            f.setStyle(Paint.Style.FILL);
            c.drawCircle(k, y, s * 0.07f, f);
        }
    }

    static void swipe(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx, cy + s * 0.32f, cx, cy - s * 0.3f, q);
        c.drawLine(cx - s * 0.16f, cy - s * 0.14f, cx, cy - s * 0.3f, q);
        c.drawLine(cx + s * 0.16f, cy - s * 0.14f, cx, cy - s * 0.3f, q);
        c.drawLine(cx - s * 0.32f, cy + s * 0.08f, cx - s * 0.16f, cy + s * 0.08f, q);
        c.drawLine(cx + s * 0.16f, cy + s * 0.08f, cx + s * 0.32f, cy + s * 0.08f, q);
    }

    static void shield(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        path.reset();
        path.moveTo(cx, cy - s * 0.34f);
        path.lineTo(cx + s * 0.28f, cy - s * 0.22f);
        path.cubicTo(cx + s * 0.28f, cy + s * 0.1f, cx + s * 0.14f, cy + s * 0.26f, cx, cy + s * 0.36f);
        path.cubicTo(cx - s * 0.14f, cy + s * 0.26f, cx - s * 0.28f, cy + s * 0.1f, cx - s * 0.28f, cy - s * 0.22f);
        path.close();
        c.drawPath(path, q);
    }

    static void info(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawCircle(cx, cy, s * 0.34f, q);
        c.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.17f, q);
        q.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy - s * 0.15f, s * 0.045f, q);
    }

    static void sun(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawCircle(cx, cy, s * 0.15f, q);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            c.drawLine(cx + (float) Math.cos(a) * s * 0.26f, cy + (float) Math.sin(a) * s * 0.26f,
                    cx + (float) Math.cos(a) * s * 0.36f, cy + (float) Math.sin(a) * s * 0.36f, q);
        }
    }

    static void signal(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        for (int i = 0; i < 4; i++) {
            float x = cx - s * 0.27f + i * s * 0.18f;
            c.drawLine(x, cy + s * 0.3f, x, cy + s * 0.3f - s * (0.14f + i * 0.14f), q);
        }
    }

    static void bluetooth(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        path.reset();
        path.moveTo(cx - s * 0.2f, cy - s * 0.17f);
        path.lineTo(cx + s * 0.18f, cy + s * 0.17f);
        path.lineTo(cx, cy + s * 0.34f);
        path.lineTo(cx, cy - s * 0.34f);
        path.lineTo(cx + s * 0.18f, cy - s * 0.17f);
        path.lineTo(cx - s * 0.2f, cy + s * 0.17f);
        c.drawPath(path, q);
    }

    static void plane(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        path.reset();
        path.moveTo(cx, cy - s * 0.36f);
        path.lineTo(cx + s * 0.05f, cy - s * 0.08f);
        path.lineTo(cx + s * 0.34f, cy + s * 0.08f);
        path.lineTo(cx + s * 0.05f, cy + s * 0.04f);
        path.lineTo(cx + s * 0.04f, cy + s * 0.24f);
        path.lineTo(cx + s * 0.14f, cy + s * 0.34f);
        path.lineTo(cx - s * 0.14f, cy + s * 0.34f);
        path.lineTo(cx - s * 0.04f, cy + s * 0.24f);
        path.lineTo(cx - s * 0.05f, cy + s * 0.04f);
        path.lineTo(cx - s * 0.34f, cy + s * 0.08f);
        path.lineTo(cx - s * 0.05f, cy - s * 0.08f);
        path.close();
        c.drawPath(path, q);
    }

    static void rotate(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.28f, cy - s * 0.28f, cx + s * 0.28f, cy + s * 0.28f);
        c.drawArc(r, -60, 300, false, q);
        float ex = cx + s * 0.14f, ey = cy - s * 0.24f;
        c.drawLine(ex, ey, ex + s * 0.14f, ey - s * 0.06f, q);
        c.drawLine(ex, ey, ex + s * 0.06f, ey + s * 0.14f, q);
        q.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, s * 0.05f, q);
    }

    static void camera(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.34f, cy - s * 0.2f, cx + s * 0.34f, cy + s * 0.28f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, q);
        c.drawLine(cx - s * 0.12f, cy - s * 0.2f, cx - s * 0.06f, cy - s * 0.3f, q);
        c.drawLine(cx - s * 0.06f, cy - s * 0.3f, cx + s * 0.06f, cy - s * 0.3f, q);
        c.drawLine(cx + s * 0.06f, cy - s * 0.3f, cx + s * 0.12f, cy - s * 0.2f, q);
        c.drawCircle(cx, cy + s * 0.04f, s * 0.12f, q);
    }

    static void calc(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.26f, cy - s * 0.34f, cx + s * 0.26f, cy + s * 0.34f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, q);
        c.drawLine(cx - s * 0.14f, cy - s * 0.18f, cx + s * 0.14f, cy - s * 0.18f, q);
        q.setStyle(Paint.Style.FILL);
        for (int i = -1; i <= 1; i++)
            for (int j = 0; j <= 1; j++) c.drawCircle(cx + i * s * 0.12f, cy + s * 0.04f + j * s * 0.14f, s * 0.035f, q);
    }

    static void alarm(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawCircle(cx, cy + s * 0.04f, s * 0.28f, q);
        c.drawLine(cx, cy + s * 0.04f, cx, cy - s * 0.12f, q);
        c.drawLine(cx, cy + s * 0.04f, cx + s * 0.12f, cy + s * 0.1f, q);
        c.drawLine(cx - s * 0.32f, cy - s * 0.22f, cx - s * 0.2f, cy - s * 0.32f, q);
        c.drawLine(cx + s * 0.32f, cy - s * 0.22f, cx + s * 0.2f, cy - s * 0.32f, q);
    }

    static void play(Canvas c, float cx, float cy, float s, int color, boolean playing) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        if (playing) {
            r.set(cx - s * 0.22f, cy - s * 0.28f, cx - s * 0.06f, cy + s * 0.28f);
            c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
            r.set(cx + s * 0.06f, cy - s * 0.28f, cx + s * 0.22f, cy + s * 0.28f);
            c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
        } else {
            path.reset();
            path.moveTo(cx - s * 0.18f, cy - s * 0.3f);
            path.lineTo(cx + s * 0.3f, cy);
            path.lineTo(cx - s * 0.18f, cy + s * 0.3f);
            path.close();
            c.drawPath(path, p);
        }
    }

    static void skip(Canvas c, float cx, float cy, float s, int color, boolean next) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        float d = next ? 1 : -1;
        for (int k = 0; k < 2; k++) {
            float ox = cx + d * (k == 0 ? -s * 0.14f : s * 0.1f);
            path.reset();
            path.moveTo(ox - d * s * 0.16f, cy - s * 0.2f);
            path.lineTo(ox + d * s * 0.12f, cy);
            path.lineTo(ox - d * s * 0.16f, cy + s * 0.2f);
            path.close();
            c.drawPath(path, p);
        }
    }

    static void battery(Canvas c, float cx, float cy, float s, int color, float level) {
        Paint q = stroke(color, s);
        q.setStrokeWidth(Math.max(1.5f, s * 0.06f));
        r.set(cx - s * 0.34f, cy - s * 0.17f, cx + s * 0.28f, cy + s * 0.17f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, q);
        c.drawLine(cx + s * 0.34f, cy - s * 0.06f, cx + s * 0.34f, cy + s * 0.06f, q);
        q.setStyle(Paint.Style.FILL);
        r.set(cx - s * 0.28f, cy - s * 0.11f, cx - s * 0.28f + s * 0.5f * Ui.clamp(level), cy + s * 0.11f);
        c.drawRoundRect(r, s * 0.03f, s * 0.03f, q);
    }

    static void chevronRight(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx - s * 0.1f, cy - s * 0.22f, cx + s * 0.12f, cy, q);
        c.drawLine(cx + s * 0.12f, cy, cx - s * 0.1f, cy + s * 0.22f, q);
    }

    static void chevronLeft(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        c.drawLine(cx + s * 0.1f, cy - s * 0.22f, cx - s * 0.12f, cy, q);
        c.drawLine(cx - s * 0.12f, cy, cx + s * 0.1f, cy + s * 0.22f, q);
    }

    static void phone(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.2f, cy - s * 0.36f, cx + s * 0.2f, cy + s * 0.36f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, q);
        c.drawLine(cx - s * 0.05f, cy + s * 0.26f, cx + s * 0.05f, cy + s * 0.26f, q);
    }

    static void storage(Canvas c, float cx, float cy, float s, int color) {
        Paint q = stroke(color, s);
        r.set(cx - s * 0.32f, cy - s * 0.22f, cx + s * 0.32f, cy + s * 0.22f);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, q);
        c.drawLine(cx - s * 0.32f, cy + s * 0.02f, cx + s * 0.32f, cy + s * 0.02f, q);
        q.setStyle(Paint.Style.FILL);
        c.drawCircle(cx + s * 0.2f, cy + s * 0.12f, s * 0.035f, q);
    }

    private Glyph() {}
}
