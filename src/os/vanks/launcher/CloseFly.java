package os.vanks.launcher;

import android.animation.TimeAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Закрытие приложения жестом, как в ColorOS 17: снимок окна идёт за пальцем
 * (уменьшается, чем выше палец, углы скругляются), а отпустили — улетает в
 * свой значок на столе: снимок перетекает в цвет значка, значок проступает.
 * Тянули мало — окно возвращается на место. Рисуется в окне службы жестов
 * поверх всего; под ним уже стол (служба нажала «Домой» в начале жеста).
 */
final class CloseFly extends View {
    interface Done {
        void run(boolean home);
    }

    private final Bitmap shot;
    private final Bitmap icon;
    private final int iconColor;
    private final LaunchCard.Spring x, y, w, h;
    private RectF target;
    private boolean flying, backing, card;   // card — летим в карточку «Недавних», снимок остаётся
    private float backdrop = 1;
    private TimeAnimator clock;
    private Done done;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF(), ir = new RectF();
    private final Path clip = new Path();
    private final Matrix m = new Matrix();
    private float W, H, x0, y0;

    CloseFly(Context c, Bitmap shot, Bitmap icon) {
        super(c);
        this.shot = shot;
        this.icon = icon;
        this.iconColor = LaunchCard.iconColor(icon);
        W = c.getResources().getDisplayMetrics().widthPixels;
        H = c.getResources().getDisplayMetrics().heightPixels;
        x = new LaunchCard.Spring(W / 2);
        y = new LaunchCard.Spring(H / 2);
        w = new LaunchCard.Spring(W);
        h = new LaunchCard.Spring(H);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        if (nw > 0 && nh > 0 && !flying) {
            W = nw;
            H = nh;
        }
    }

    /** Начало жеста: где был палец. */
    void start(float rawX, float rawY) {
        x0 = rawX;
        y0 = rawY;
        run();
    }

    /** Палец ведёт окно: чем выше — тем оно меньше; низ окна — у пальца. */
    void follow(float rawX, float rawY) {
        if (flying) return;
        float dy = Math.max(0, y0 - rawY);
        float s = Math.max(0.32f, Math.min(1f, 1 - dy / (H * 1.05f)));
        float ww = W * s, hh = H * s;
        x.to(W / 2 + (rawX - x0) * 0.85f, 900, 1);
        y.to(rawY + (H - y0) * s - hh / 2, 900, 1);
        w.to(ww, 900, 1);
        h.to(hh, 900, 1);
    }

    /** Отпустили: домой — в значок (или вниз и прочь, если значка нет), иначе — обратно. */
    void release(boolean home, float vx, float vy, RectF icon, Done done) {
        this.done = done;
        flying = true;
        if (!home) {
            backing = true;
            x.to(W / 2, 320, 0.86f);
            y.to(H / 2, 320, 0.86f);
            w.to(W, 320, 0.86f);
            h.to(H, 320, 0.86f);
            return;
        }
        x.vel += vx * 0.6f;
        y.vel += vy * 0.6f;
        if (icon == null) {
            // значка на столе нет — окно уходит маленьким к низу экрана и тает
            float s = Ui.dpf(56);
            icon = new RectF(W / 2 - s / 2, H * 0.78f - s / 2, W / 2 + s / 2, H * 0.78f + s / 2);
        }
        target = new RectF(icon);
        x.to(target.centerX(), 190, 0.82f);
        y.to(target.centerY(), 170, 0.8f);
        w.to(target.width(), 230, 1f);
        h.to(target.height(), 230, 1f);
        if (vy < 0) {
            w.vel = Math.min(0, vy / H) * 0.8f * W;
            h.vel = Math.min(0, vy / H) * 0.8f * H;
        }
    }

    /** В «Недавние»: окно уменьшается в первую карточку и остаётся снимком. */
    void toCard(RectF r, float vx, float vy, Done done) {
        card = true;
        release(true, vx, vy, r, done);
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
            // пока стол не встал сверху — под окном его обои; потом они тают
            if (Home.onTop() || flying) backdrop = Math.max(0, backdrop - s * 6);
            if (flying && target != null) {
                w.v = Math.max(w.v, target.width() * 0.92f);
                h.v = Math.max(h.v, target.height() * 0.92f);
                if (Math.abs(w.v - target.width()) < 1.5f && Math.hypot(x.v - target.centerX(), y.v - target.centerY()) < 2) finish(true);
            } else if (backing && x.still(0.5f) && y.still(0.5f) && w.still(0.5f) && h.still(0.5f)) finish(false);
            invalidate();
        });
        clock.start();
    }

    private void finish(boolean home) {
        if (clock != null) clock.end();
        clock = null;
        if (done != null) done.run(home);
        done = null;
    }

    /** Убрать без анимации (служба выключается и т. п.). */
    void stop() {
        if (clock != null) clock.end();
        clock = null;
    }

    private static float sm(float a, float b, float x) {
        float t = Ui.clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (backdrop > 0.01f) {
            Bitmap wall = Ui.wall;
            if (wall != null && !wall.isRecycled()) {
                p.setAlpha(Math.round(255 * backdrop));
                c.drawBitmap(wall, Ui.wallMatrix(wall, getWidth(), getHeight(), 1f, m), p);
                c.drawColor(Ui.alpha(0xFF000000, 0.3f * backdrop));
            } else c.drawColor(Ui.alpha(0xFF0B0D16, backdrop));
        }
        // 1 — весь экран, 0 — значок
        float iw = target != null ? target.width() : Ui.dpf(56);
        float t = Ui.clamp((Math.min(w.v / W, h.v / H) * W - iw) / Math.max(1, W - iw));
        r.set(x.v - w.v / 2, y.v - h.v / 2, x.v + w.v / 2, y.v + h.v / 2);
        float rad = card ? Ui.dpf(26) : Math.min(Math.min(r.width(), r.height()) / 2, iw / 2 + (Ui.dpf(30) - iw / 2) * sm(0, 0.35f, t));
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        p.setAlpha(255);
        p.setColor(iconColor);
        c.drawRect(r, p);
        // снимок окна перетекает в цвет значка
        int sa = card ? 255 : Math.round(255 * sm(0.1f, 0.45f, t));
        if (sa > 0 && shot != null) {
            p.setAlpha(sa);
            c.drawBitmap(shot, null, r, p);
        }
        if (icon != null && !card) {
            float s = iw * (1 + 0.55f * sm(0, 0.6f, t));
            ir.set(x.v - s / 2, y.v - s / 2, x.v + s / 2, y.v + s / 2);
            ip.setAlpha(Math.round(255 * (1 - sm(0.18f, 0.55f, t))));
            if (ip.getAlpha() > 0) c.drawBitmap(icon, null, ir, ip);
        }
        c.restore();
    }
}
