package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Меню-капля, как в Delta (DesktopMenu / BlobMenu): из места нажатия
 * вырастает капля цвета акцента, летит к месту меню, растягиваясь в
 * полёте, и распускается в карточку с перелётом (outBack), меняя цвет на
 * цвет карточки; пункты проявляются в самом конце. Открытая карточка
 * чуть дрожит, как желе. Закрывается тем же путём, но быстрее.
 */
final class DropMenu extends FrameLayout {
    interface Icon {
        void draw(Canvas c, float cx, float cy, float s, int color);
    }

    private static final class Entry {
        String label;
        Icon icon;
        Drawable image;
        boolean danger, shortcut;
        Runnable run;
    }

    private static final float P1 = 0.22f, P2 = 0.5f;

    private final String title;
    private final float srcX, srcY;
    private final RectF anchor;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final LinearLayout card;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF(), dest = new RectF();
    private float t, wobble = 1f;
    private float landX, landY, R;
    private ValueAnimator anim;
    private boolean closing;
    private Runnable onClosed;
    private final int[] loc = new int[2];

    /** rawX/rawY — откуда растёт капля; anchor (на экране) — что не закрывать карточкой. */
    DropMenu(Context c, String title, float rawX, float rawY, RectF anchor) {
        super(c);
        this.title = title;
        this.srcX = rawX;
        this.srcY = rawY;
        this.anchor = anchor == null ? new RectF(rawX, rawY, rawX, rawY) : anchor;
        setWillNotDraw(false);
        setClipChildren(false);
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        card.setAlpha(0);
        card.setClickable(true);
        addView(card, new LayoutParams(-2, -2));
    }

    DropMenu add(String label, Icon icon, Runnable run) {
        Entry e = new Entry();
        e.label = label;
        e.icon = icon;
        e.run = run;
        entries.add(e);
        return this;
    }

    DropMenu danger(String label, Icon icon, Runnable run) {
        add(label, icon, run);
        entries.get(entries.size() - 1).danger = true;
        return this;
    }

    /** Быстрое действие приложения (ярлык) — сверху, со своим значком. */
    DropMenu shortcut(String label, Drawable image, Runnable run) {
        Entry e = new Entry();
        e.label = label;
        e.image = image;
        e.shortcut = true;
        e.run = run;
        entries.add(e);
        return this;
    }

    void onClosed(Runnable r) {
        onClosed = r;
    }

    // ── сборка ────────────────────────────────────────────────────────────

    private void buildRows() {
        Context c = getContext();
        int fg = Look.fg();
        if (title != null) {
            TextView h = Views.label(c, title, 13, Ui.medium, Look.fg2());
            h.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(6));
            h.setSingleLine(true);
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.setMaxWidth(Ui.dp(240));
            card.addView(h);
        }
        boolean hadShortcut = false;
        for (Entry e : entries) {
            if (hadShortcut && !e.shortcut) {
                View sep = new View(c);
                sep.setBackgroundColor(Ui.alpha(fg, 0.12f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(0.7f)));
                lp.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
                card.addView(sep, lp);
                hadShortcut = false;
            }
            if (e.shortcut) hadShortcut = true;
            card.addView(row(c, e, fg), new LinearLayout.LayoutParams(-1, Ui.dp(44)));
        }
    }

    private View row(Context c, Entry e, int fg) {
        int color = e.danger ? 0xFFE5484D : fg;
        LinearLayout r = new LinearLayout(c) {
            boolean down;

            @Override
            protected void onDraw(Canvas cv) {
                if (!down) return;
                Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
                hp.setColor(Ui.alpha(fg, 0.1f));
                cv.drawRoundRect(0, 0, getWidth(), getHeight(), Look.radius(10), Look.radius(10), hp);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) { down = true; invalidate(); }
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { down = false; invalidate(); }
                return super.onTouchEvent(ev);
            }
        };
        r.setWillNotDraw(false);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(12), 0, Ui.dp(18), 0);
        if (e.image != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(e.image);
            r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        } else {
            View g = new View(c) {
                @Override
                protected void onDraw(Canvas cv) {
                    if (e.icon != null) e.icon.draw(cv, getWidth() / 2f, getHeight() / 2f, getWidth(), color);
                }
            };
            r.addView(g, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        }
        TextView t = Views.label(c, e.label, 15, Ui.medium, color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(Ui.dp(230));
        t.setPadding(Ui.dp(12), 0, 0, 0);
        r.addView(t);
        r.setOnClickListener(v -> {
            Feel.buzz(v, Feel.CLICK);
            Feel.sound(getContext(), "tick");
            dismiss();
            if (e.run != null) e.run.run();
        });
        return r;
    }

    void show(ViewGroup root) {
        buildRows();
        root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        Feel.sound(getContext(), "pop");
        post(this::start);
    }

    private void start() {
        getLocationOnScreen(loc);
        int w = getWidth(), h = getHeight();
        card.measure(MeasureSpec.makeMeasureSpec(Math.min(w - Ui.dp(32), Ui.dp(300)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        int cw = Math.max(card.getMeasuredWidth(), Ui.dp(200)), ch = card.getMeasuredHeight();
        float sx = srcX - loc[0], sy = srcY - loc[1];
        float ax = anchor.left - loc[0], ay = anchor.top - loc[1], ab = anchor.bottom - loc[1];
        float gap = Ui.dpf(10), m = Ui.dpf(16);
        // под тем, что зажали; не влезает — над ним; не влезает и там — у пальца
        float top = ab + gap;
        if (top + ch > h - m) top = ay - gap - ch;
        if (top < m) top = Math.max(m, Math.min(h - m - ch, sy - ch / 2f));
        float left = Math.max(m, Math.min(w - m - cw, (ax + anchor.width() / 2f) - cw / 2f));
        dest.set(left, top, left + cw, top + ch);
        card.layout(0, 0, cw, ch);
        card.setTranslationX(left);
        card.setTranslationY(top);
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        lp.width = cw;
        card.setLayoutParams(lp);

        R = Ui.clamp((Math.min(cw, ch) * 0.2f - Ui.dpf(10)) / Ui.dpf(6)) * Ui.dpf(6) + Ui.dpf(10);
        // капля садится на ближайшую к пальцу точку края карточки
        landX = Math.max(dest.left + R, Math.min(dest.right - R, sx));
        landY = Math.max(dest.top + R, Math.min(dest.bottom - R, sy));
        float dist = (float) Math.hypot(landX - sx, landY - sy) / Ui.density;
        long ms = (long) Math.min(1250, 720 + dist * 0.6f);
        anim = Anim.run(0, 1, ms, null, v -> {
            t = v;
            card.setAlpha(Ui.clamp((t - 0.86f) / 0.14f));
            invalidate();
        }, this::jelly);
    }

    /** Открылась — дрогнуть, как желе. */
    private void jelly() {
        if (closing) return;
        anim = Anim.spring(1.045f, 1f, 0, 280, 0.35f, v -> {
            wobble = v;
            card.setScaleX(v);
            card.setScaleY(2 - v);
            invalidate();
        }, null);
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        if (anim != null) anim.cancel();
        wobble = 1f;
        card.setScaleX(1);
        card.setScaleY(1);
        float from = t;
        long ms = (long) (Math.min(1250, 720 + Math.hypot(landX - (srcX - loc[0]), landY - (srcY - loc[1])) / Ui.density * 0.6f) * 0.72f * from);
        anim = Anim.run(from, 0, Math.max(120, ms), null, v -> {
            t = v;
            card.setAlpha(Ui.clamp((t - 0.86f) / 0.14f));
            invalidate();
        }, () -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (onClosed != null) onClosed.run();
        });
    }

    boolean closing() {
        return closing;
    }

    // ── капля ─────────────────────────────────────────────────────────────

    private static float outCubic(float x) { float y = 1 - x; return 1 - y * y * y; }

    private static float inOutCubic(float x) { return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2; }

    private static float outBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (dest.isEmpty()) return;
        float sx = srcX - loc[0], sy = srcY - loc[1];
        int cardColor = Ui.alpha(Look.surface(), Look.is("acrylic") ? 0.82f : 0.96f);
        int accent = Ui.accent();
        p.setStyle(Paint.Style.FILL);
        if (t < P1) {
            // 1. капля набухает под пальцем
            float u = t / P1;
            float r = R * (0.35f + 0.65f * outCubic(u));
            p.setColor(accent);
            c.drawCircle(sx, sy, r, p);
            return;
        }
        if (t < P2) {
            // 2. летит к месту меню и тянется по ходу
            float u = (t - P1) / (P2 - P1);
            float e = inOutCubic(u);
            float x = sx + (landX - sx) * e, y = sy + (landY - sy) * e;
            float stretch = (float) Math.sin(Math.PI * u);
            float ang = (float) Math.toDegrees(Math.atan2(landY - sy, landX - sx));
            float len = R * (1 + 0.9f * stretch), wid = R * (1 - 0.22f * stretch);
            p.setColor(Ui.mix(accent, cardColor | 0xFF000000, u * 0.25f));
            c.save();
            c.translate(x, y);
            c.rotate(ang);
            c.drawRoundRect(-len, -wid, len, wid, wid, wid, p);
            c.restore();
            // хвостик у пальца тает
            if (u < 0.5f) c.drawCircle(sx, sy, R * 0.45f * (1 - u * 2), p);
            return;
        }
        // 3. распускается в карточку с перелётом
        float u = (t - P2) / (1 - P2);
        float g = outBack(u);
        float over = Ui.dpf(16);
        float l = lerp(landX - R, dest.left, g), r = lerp(landX + R, dest.right, g);
        float tp = lerp(landY - R, dest.top, g), b = lerp(landY + R, dest.bottom, g);
        l = Math.max(dest.left - over, l);
        r = Math.min(dest.right + over, r);
        tp = Math.max(dest.top - over, tp);
        b = Math.min(dest.bottom + over, b);
        body.set(l, tp, r, b);
        if (wobble != 1f) {
            float cx = dest.centerX(), cy = dest.centerY();
            body.set(cx + (l - cx) * wobble, cy + (tp - cy) * (2 - wobble), cx + (r - cx) * wobble, cy + (b - cy) * (2 - wobble));
        }
        float rad = lerp(R, Look.radius(18), Ui.clamp(u * 1.4f));
        rad = Math.min(rad, Math.min(body.width(), body.height()) / 2);
        float mix = Ui.clamp(u * 1.6f);
        p.setColor(Ui.mix(accent, cardColor | 0xFF000000, mix));
        p.setAlpha(Math.round(255 * (1 - mix + mix * (Color.alpha(cardColor) / 255f))));
        // мягкая тень, когда карточка уже почти собралась
        p.setShadowLayer(Ui.dpf(18) * mix, 0, Ui.dpf(6) * mix, Ui.alpha(0xFF000000, 0.35f * mix));
        c.drawRoundRect(body, rad, rad, p);
        p.clearShadowLayer();
        if (mix > 0.5f) {
            // материал темы (стекло из обоев, контур Акрила)
            int sc = c.saveLayerAlpha(body.left - over, body.top - over, body.right + over, body.bottom + over, Math.round(255 * (mix - 0.5f) * 2));
            Look.panel(c, body, rad, loc[0] + body.left, loc[1] + body.top, getRootView().getWidth(), getRootView().getHeight());
            c.restoreToCount(sc);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class Color {
        static int alpha(int c) { return c >>> 24; }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dismiss();
        return true;
    }
}
