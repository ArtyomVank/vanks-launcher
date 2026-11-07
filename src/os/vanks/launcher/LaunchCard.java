package os.vanks.launcher;

import android.animation.TimeAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * Запуск приложения, как в ColorOS 17: окно рождается из самого значка —
 * сначала это цветной круг значка, он растёт, углы из круглых становятся
 * углами экрана, цвет значка перетекает в фон приложения, а значок остаётся
 * посередине и тает. Центр и размер едут на разных пружинах, поэтому путь —
 * дуга, а не прямая; высота чуть отстаёт от ширины. При возврате — всё
 * обратно, точно в значок, без проскока меньше него.
 */
final class LaunchCard extends View {
    /** Пружина по физике (жёсткость k, затухание z), шаг — время кадра. */
    static final class Spring {
        float v, vel, t, k = 300, z = 1;

        Spring(float v) {
            this.v = this.t = v;
        }

        void to(float target, float k, float z) {
            t = target;
            this.k = k;
            this.z = z;
        }

        void snap(float x) {
            v = t = x;
            vel = 0;
        }

        void step(float dt) {
            float w = (float) Math.sqrt(k), h = dt / 6f;
            for (int i = 0; i < 6; i++) {
                float a = -k * (v - t) - 2 * z * w * vel;
                vel += a * h;
                v += vel * h;
            }
        }

        boolean still(float eps) {
            return Math.abs(v - t) < eps && Math.abs(vel) < eps * 8;
        }
    }

    private final RectF from, back;
    private final Bitmap icon;
    private final int iconColor, appColor;
    private final Spring x, y, w, h;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF(), ir = new RectF();
    private final Path clip = new Path();
    private TimeAnimator clock;
    private Runnable when, landed;
    private boolean closing;

    LaunchCard(Context c, RectF from, Bitmap icon) {
        super(c);
        this.from = new RectF(from);
        this.back = new RectF(from);
        this.icon = icon;
        this.iconColor = iconColor(icon);
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        this.appColor = night ? 0xFF141416 : 0xFFF6F6F8;
        x = new Spring(from.centerX());
        y = new Spring(from.centerY());
        w = new Spring(from.width());
        h = new Spring(from.height());
    }

    /** Цвет значка: средний по картинке. */
    static int iconColor(Bitmap b) {
        if (b == null) return Look.surface();
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            return one.getPixel(0, 0) | 0xFF000000;
        } catch (Exception e) {
            return Look.surface();
        }
    }

    /** Вырасти из значка на весь экран; when — когда окно почти заняло экран. */
    void open(Runnable when) {
        this.when = when;
        closing = false;
        float W = Math.max(1, getRootW()), H = Math.max(1, getRootH());
        // центр — бодрее, размер — мягче, высота отстаёт: дуга и «вытягивание»
        x.to(W / 2, 240, 0.9f);
        y.to(H / 2, 170, 0.86f);
        w.to(W, 210, 0.88f);
        h.to(H, 165, 0.84f);
        run();
    }

    /** Куда садиться при возврате (стол к тому времени уже не в глубине). */
    void landAt(RectF r) {
        back.set(r);
    }

    /** Сжаться обратно в значок; done — когда сел на место (вид уже убран). */
    void close(Runnable done) {
        landed = done;
        when = null;
        closing = true;
        x.to(back.centerX(), 190, 0.82f);
        y.to(back.centerY(), 170, 0.8f);
        w.to(back.width(), 230, 1f);
        h.to(back.height(), 230, 1f);
        run();
    }

    /** Убрать сразу (возврат жестом — окно в значок уносит служба). */
    void drop() {
        if (clock != null) clock.end();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    private float getRootW() {
        View p = (View) getParent();
        return p != null ? p.getWidth() : getWidth();
    }

    private float getRootH() {
        View p = (View) getParent();
        return p != null ? p.getHeight() : getHeight();
    }

    private void run() {
        if (clock != null) return;
        clock = new TimeAnimator();
        clock.setTimeListener((a, total, dt) -> {
            float s = Math.min(0.05f, dt / 1000f) / Prefs.k();
            x.step(s);
            y.step(s);
            w.step(s);
            h.step(s);
            if (closing) {
                // не меньше значка: никакого проскока и мигания
                w.v = Math.max(w.v, back.width() * 0.92f);
                h.v = Math.max(h.v, back.height() * 0.92f);
                if (Math.abs(w.v - back.width()) < 1.5f && Math.hypot(x.v - back.centerX(), y.v - back.centerY()) < 2) {
                    a.end();
                    clock = null;
                    if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
                    if (landed != null) landed.run();
                    return;
                }
            } else {
                if (when != null && progress() > 0.93f) {
                    Runnable go = when;
                    when = null;
                    go.run();
                }
                if (x.still(0.5f) && y.still(0.5f) && w.still(0.5f) && h.still(0.5f)) {
                    a.end();
                    clock = null;
                }
            }
            invalidate();
        });
        clock.start();
    }

    /** 0 — значок, 1 — весь экран. */
    float progress() {
        float W = Math.max(1, getRootW()), H = Math.max(1, getRootH());
        float s = Math.min(w.v / W, h.v / H) * W;
        return Ui.clamp((s - from.width()) / Math.max(1, W - from.width()));
    }

    private static float sm(float a, float b, float x) {
        float t = Ui.clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = progress();
        r.set(x.v - w.v / 2, y.v - h.v / 2, x.v + w.v / 2, y.v + h.v / 2);
        float rad = Math.min(Math.min(r.width(), r.height()) / 2, from.width() / 2 + (Ui.dpf(30) - from.width() / 2) * sm(0, 0.35f, t));
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        // цвет значка перетекает в фон приложения
        p.setColor(Ui.mix(iconColor, appColor, sm(0.06f, 0.42f, t)));
        c.drawRect(r, p);
        if (icon != null) {
            float s = from.width() * (1 + 0.55f * sm(0, 0.6f, t));
            ir.set(x.v - s / 2, y.v - s / 2, x.v + s / 2, y.v + s / 2);
            ip.setAlpha(Math.round(255 * (1 - sm(0.18f, 0.55f, t))));
            if (ip.getAlpha() > 0) c.drawBitmap(icon, null, ir, ip);
        }
        c.restore();
    }
}
