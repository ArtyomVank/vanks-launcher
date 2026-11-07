package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Динамический остров: чёрная капсула вокруг камеры. Показывает то, что идёт
 * сейчас: сканирование пальца, громкость, такси и доставку (постоянные
 * уведомления), музыку, голосовые и кружки в Telegram.
 *
 * Касание — раскрыть, ещё касание — открыть приложение (у музыки справа —
 * пауза). Свайп влево — убрать: такси и доставка не вернутся, пока не
 * закончатся, музыка — до следующего трека.
 *
 * Живёт в окне службы «Vanks: жесты и вход» поверх всего; без службы — только
 * на экране блокировки Vanks (сканирование).
 */
final class Island extends View {
    static final int SCAN = 0, VOLUME = 1, RIDE = 2, VOICE = 3, MEDIA = 4;   // по важности
    static final int SCANNING = 0, SCAN_OK = 1, SCAN_MISS = 2;

    static final class Item {
        String key;
        int kind, state;
        String title = "", text = "", badge;   // badge — коротко в свёрнутом («5 мин»)
        Drawable icon;
        Bitmap art;
        float progress = -1;   // 0…1, −1 — нет
        boolean playing;
        int tint = 0xFF34C759;
        PendingIntent open;
        MediaController media;
        long until;   // uptime, когда убрать само; 0 — пока не снимут

        Item(String key, int kind) {
            this.key = key;
            this.kind = kind;
        }
    }

    /** Окно острова: сколько места нужно (0 — остров скрыт). */
    interface Host {
        void size(int w, int h);
    }

    // ── что показывать ────────────────────────────────────────────────────

    private static final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    /** Смахнутые: ключ → подпись; пока подпись та же, не показываем. */
    private static final HashMap<String, String> dismissed = new HashMap<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    /** Остров поверх всего (служба) и встроенный в экран блокировки. */
    static Island top, local;

    static void put(Item it) {
        String d = dismissed.get(it.key);
        if (d != null) {
            if (d.equals(sig(it))) return;
            dismissed.remove(it.key);
        }
        items.put(it.key, it);
        if (it.until > 0) ui.postDelayed(Island::refresh, Math.max(0, it.until - SystemClock.uptimeMillis()) + 30);
        refresh();
    }

    static void remove(String key) {
        dismissed.remove(key);
        if (items.remove(key) != null) refresh();
    }

    static Item get(String key) {
        return items.get(key);
    }

    /** Такси и голосовые смахнули — до конца; музыку — до другого трека. */
    private static String sig(Item it) {
        return it.kind == MEDIA ? it.title : "";
    }

    private static Item pick() {
        long now = SystemClock.uptimeMillis();
        Item best = null;
        for (Iterator<Item> i = items.values().iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (it.until > 0 && it.until <= now) {
                i.remove();
                continue;
            }
            if (!Prefs.island && it.kind != SCAN) continue;
            if (best == null || it.kind < best.kind) best = it;
        }
        return best;
    }

    static void refresh() {
        Item it = pick();
        Island h = top != null && top.isAttachedToWindow() ? top : local;
        if (h != null) h.show(it);
        if (local != null && local != h) local.show(null);
    }

    // ── вид ───────────────────────────────────────────────────────────────

    Host host;
    private Item shown;
    private boolean expanded, swiping, leaving, hiding;
    private float pw, ph, fromW, fromH, toW, toH, shape = 1, content = 1, alpha, dx, shake;
    private ValueAnimator shapeAnim, alphaAnim, contentAnim, dxAnim;
    private float x0, y0;
    private long downAt;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint title = Ui.text(Ui.medium, 15, 0xFFFFFFFF);
    private final Paint body = Ui.text(Ui.regular, 13, 0xB3FFFFFF);
    private final Paint badge = Ui.text(Ui.medium, 13, 0xFFFFFFFF);
    private final RectF r = new RectF(), q = new RectF();
    private final Path clip = new Path();
    private final Matrix m = new Matrix();
    private final Runnable collapse = () -> setExpanded(false);

    Island(Context c) {
        super(c);
        badge.setTextAlign(Paint.Align.RIGHT);
        pw = toW = Ui.dpf(96);
        ph = toH = Ui.dpf(30);
    }

    private static float topGap() {
        return Ui.dpf(6);
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private float wideW() {
        return Math.min(screenW() - Ui.dpf(16), Ui.dpf(420));
    }

    private float targetW(Item it, boolean ex) {
        if (it == null) return Ui.dpf(96);
        if (ex) return wideW();
        switch (it.kind) {
            case SCAN: return Ui.dpf(it.state == SCAN_OK ? 176 : 156);
            case VOLUME: return Ui.dpf(212);
            case RIDE: return Ui.dpf(it.badge != null ? 204 : 172);
            default: return Ui.dpf(184);
        }
    }

    private float targetH(Item it, boolean ex) {
        if (it == null) return Ui.dpf(30);
        if (!ex) return Ui.dpf(34);
        return Ui.dpf(it.kind == MEDIA || it.kind == VOICE || it.progress >= 0 ? 100 : 84);
    }

    /** Окно под остров: шире на время свайпа, выше на время раскрытия. */
    private void fit() {
        if (host == null) return;
        if (shown == null && alpha <= 0.001f) {
            host.size(0, 0);
            return;
        }
        float w = swiping || leaving ? screenW() : Math.max(pw, Math.max(toW, fromW)) + Ui.dpf(12);
        float h = Math.max(ph, Math.max(toH, fromH)) + topGap() + Ui.dpf(12);
        host.size(Math.round(Math.min(screenW(), w)), Math.round(h));
    }

    private void show(Item it) {
        if (leaving) return;
        if (it == null) {
            if (shown == null) return;
            ui.removeCallbacks(collapse);
            expanded = false;
            morph(null, false);
            hiding = true;
            fade(0, () -> {
                hiding = false;
                shown = null;
                dx = 0;
                fit();
            });
            return;
        }
        if (hiding) {
            // убирали — а уже снова есть что показать
            hiding = false;
            fade(1, null);
        }
        boolean fresh = shown == null;
        boolean other = !fresh && !shown.key.equals(it.key);
        int was = shown == null ? -1 : shown.state;
        shown = it;
        if (fresh) {
            expanded = false;
            pw = Ui.dpf(96);
            ph = Ui.dpf(30);
            dx = 0;
            content = 0;
            fade(1, null);
        }
        if (other) expanded = false;
        if (it.kind == SCAN && was != it.state) {
            if (it.state == SCAN_OK) Feel.buzz(this, Feel.CONFIRM);
            if (it.state == SCAN_MISS) shakeIt();
        }
        if (fresh || other) {
            if (contentAnim != null) contentAnim.cancel();
            content = 0;
            contentAnim = Anim.run(0, 1, 260, Anim.STD, v -> {
                content = v;
                invalidate();
            }, null);
        }
        morph(it, expanded);
        invalidate();
    }

    /** Капсула тянется к новому размеру пружиной (чуть проскакивает, как живая). */
    private void morph(Item it, boolean ex) {
        float tw = targetW(it, ex), th = targetH(it, ex);
        if (Math.abs(tw - toW) < 0.5f && Math.abs(th - toH) < 0.5f && shapeAnim != null && shapeAnim.isRunning()) return;
        if (Math.abs(tw - pw) < 0.5f && Math.abs(th - ph) < 0.5f) {
            toW = tw;
            toH = th;
            fit();
            return;
        }
        fromW = pw;
        fromH = ph;
        toW = tw;
        toH = th;
        fit();
        if (shapeAnim != null) shapeAnim.cancel();
        shapeAnim = Anim.spring(0, 1, 0, 420, ex || it == null ? 0.78f : 0.7f, t -> {
            shape = t;
            pw = fromW + (toW - fromW) * t;
            ph = Math.max(Ui.dpf(20), fromH + (toH - fromH) * t);
            invalidate();
        }, () -> {
            fromW = toW;
            fromH = toH;
            fit();
        });
    }

    private void fade(float to, Runnable end) {
        if (alphaAnim != null) alphaAnim.cancel();
        alphaAnim = Anim.run(alpha, to, to > 0 ? 200 : 240, Anim.STD, v -> {
            alpha = v;
            invalidate();
        }, end);
    }

    private void shakeIt() {
        Feel.buzz(this, Feel.THUD);
        Anim.run(0, 1, 420, new android.view.animation.LinearInterpolator(), t -> {
            shake = (float) Math.sin(t * Math.PI * 6) * Ui.dpf(7) * (1 - t);
            invalidate();
        }, () -> shake = 0);
    }

    private void setExpanded(boolean ex) {
        if (shown == null || expanded == ex) return;
        expanded = ex;
        ui.removeCallbacks(collapse);
        if (ex) ui.postDelayed(collapse, 6000);
        if (contentAnim != null) contentAnim.cancel();
        content = 0;
        contentAnim = Anim.run(0, 1, 240, Anim.STD, v -> {
            content = v;
            invalidate();
        }, null);
        morph(shown, ex);
    }

    // ── рисование ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas c) {
        if (alpha <= 0.001f || shown == null && pw <= Ui.dpf(97)) return;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) return;
        float cx = getWidth() / 2f + dx + shake, t0 = topGap();
        r.set(cx - pw / 2, t0, cx + pw / 2, t0 + ph);
        float rad = Math.min(ph / 2, Ui.dpf(30));
        int a = Math.round(255 * Ui.clamp(alpha));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF000000);
        fill.setAlpha(a);
        c.drawRoundRect(r, rad, rad, fill);
        Item it = shown;
        if (it == null) return;
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        // содержимое проявляется, когда капсула почти встала
        float ca = Ui.clamp(content) * Ui.clamp(alpha) * Ui.clamp((ph - Ui.dpf(26)) / Ui.dpf(8));
        boolean wide = expanded && ph > Ui.dpf(60);
        if (wide) drawWide(c, it, ca);
        else drawCompact(c, it, ca);
        c.restore();
        if (it.playing && (it.kind == MEDIA || it.kind == VOICE) || it.kind == SCAN && it.state == SCANNING)
            postInvalidateOnAnimation();
    }

    private void drawCompact(Canvas c, Item it, float ca) {
        float h = ph, cy = r.top + h / 2, d = h - Ui.dpf(10);
        float ix = r.left + Ui.dpf(6) + d / 2, ex = r.right - Ui.dpf(14);
        int al = Math.round(255 * ca);
        switch (it.kind) {
            case SCAN: {
                int col = it.state == SCAN_OK ? 0xFF34C759 : it.state == SCAN_MISS ? 0xFFFF453A : Ui.accentLight();
                if (it.state == SCAN_OK) check(c, ix, cy, d * 0.62f, Ui.alpha(col, ca));
                else {
                    float sweep = it.state == SCANNING ? (SystemClock.uptimeMillis() % 1400) / 1400f : 1f;
                    Glyph.fingerprint(c, ix, cy, d * 0.8f, Ui.alpha(col, ca), sweep);
                }
                badge.setColor(Ui.alpha(it.state == SCAN_MISS ? 0xFFFF6B60 : 0xFFFFFFFF, ca));
                c.drawText(it.state == SCAN_OK ? "Готово" : it.state == SCAN_MISS ? "Не тот палец" : "Сканирую…", ex, cy + Ui.dpf(4.5f), badge);
                break;
            }
            case VOLUME: {
                Glyph.speaker(c, ix, cy, d * 0.62f, Ui.alpha(0xFFFFFFFF, ca));
                float bw = Ui.dpf(112), bh = Ui.dpf(5);
                q.set(ex - bw, cy - bh / 2, ex, cy + bh / 2);
                fill.setColor(Ui.alpha(0x40FFFFFF, ca));
                c.drawRoundRect(q, bh / 2, bh / 2, fill);
                q.right = q.left + bw * Ui.clamp(it.progress);
                fill.setColor(Ui.alpha(0xFFFFFFFF, ca));
                if (q.width() > 0) c.drawRoundRect(q, bh / 2, bh / 2, fill);
                break;
            }
            case RIDE: {
                icon(c, it, ix, cy, d, al);
                badge.setColor(Ui.alpha(it.tint, ca));
                String s = it.badge != null ? it.badge : cut(it.title, badge, Ui.dpf(96));
                c.drawText(s, ex, cy + Ui.dpf(4.5f), badge);
                break;
            }
            default: {
                icon(c, it, ix, cy, d, al);
                bars(c, ex, cy, it.playing, ca, it.kind == VOICE ? 0xFF5AC8FA : it.tint);
            }
        }
    }

    private void drawWide(Canvas c, Item it, float ca) {
        float pad = Ui.dpf(16), d = Ui.dpf(48);
        float top = r.top + pad, ix = r.left + pad + d / 2, iy = top + d / 2;
        int al = Math.round(255 * ca);
        boolean player = it.kind == MEDIA || it.kind == VOICE;
        float right = r.right - pad - (player ? Ui.dpf(92) : 0);
        if (it.kind == VOLUME) Glyph.speaker(c, ix, iy, d * 0.5f, Ui.alpha(0xFFFFFFFF, ca));
        else if (it.kind == SCAN) Glyph.fingerprint(c, ix, iy, d * 0.7f, Ui.alpha(Ui.accentLight(), ca), 1f);
        else icon(c, it, ix, iy, d, al);
        float tx = r.left + pad + d + Ui.dpf(12), tw = right - tx;
        title.setColor(Ui.alpha(0xFFFFFFFF, ca));
        body.setColor(Ui.alpha(0xB3FFFFFF, ca));
        boolean two = it.text != null && !it.text.isEmpty();
        c.drawText(cut(it.title, title, tw), tx, iy + (two ? -Ui.dpf(3) : Ui.dpf(5)), title);
        if (two) c.drawText(cut(it.text, body, tw), tx, iy + Ui.dpf(16), body);
        if (it.kind == RIDE && it.badge != null) {
            badge.setColor(Ui.alpha(it.tint, ca));
            c.drawText(it.badge, r.right - pad, r.top + pad + Ui.dpf(12), badge);
        }
        if (player) {
            float by = iy;
            Glyph.play(c, r.right - pad - Ui.dpf(62), by, Ui.dpf(22), Ui.alpha(0xFFFFFFFF, ca), it.playing);
            Glyph.skip(c, r.right - pad - Ui.dpf(16), by, Ui.dpf(18), Ui.alpha(0xFFFFFFFF, ca), true);
        }
        float pr = it.progress;
        if (pr < 0 && it.media != null) pr = mediaProgress(it.media);
        if (pr >= 0 && it.kind != VOLUME) {
            float bh = Ui.dpf(4), y = r.bottom - pad - bh / 2;
            q.set(r.left + pad, y - bh / 2, r.right - pad, y + bh / 2);
            fill.setColor(Ui.alpha(0x33FFFFFF, ca));
            c.drawRoundRect(q, bh / 2, bh / 2, fill);
            q.right = q.left + q.width() * Ui.clamp(pr);
            fill.setColor(Ui.alpha(it.kind == RIDE ? it.tint : 0xFFFFFFFF, ca));
            if (q.width() > 0) c.drawRoundRect(q, bh / 2, bh / 2, fill);
        }
    }

    private static float mediaProgress(MediaController mc) {
        try {
            PlaybackState s = mc.getPlaybackState();
            android.media.MediaMetadata md = mc.getMetadata();
            if (s == null || md == null) return -1;
            long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            if (dur <= 0) return -1;
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING)
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            return Ui.clamp(pos / (float) dur);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Значок: обложка или значок приложения в круге. */
    private void icon(Canvas c, Item it, float cx, float cy, float d, int al) {
        q.set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
        if (it.art != null && !it.art.isRecycled()) {
            BitmapShader bs = new BitmapShader(it.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = Math.max(d / it.art.getWidth(), d / it.art.getHeight());
            m.setScale(s, s);
            m.postTranslate(cx - it.art.getWidth() * s / 2, cy - it.art.getHeight() * s / 2);
            bs.setLocalMatrix(m);
            img.setShader(bs);
            img.setAlpha(al);
            float rr = d > Ui.dpf(30) ? Ui.dpf(12) : d / 2;
            c.drawRoundRect(q, rr, rr, img);
            img.setShader(null);
        } else if (it.icon != null) {
            it.icon.setBounds(Math.round(q.left), Math.round(q.top), Math.round(q.right), Math.round(q.bottom));
            it.icon.setAlpha(al);
            it.icon.draw(c);
        }
    }

    /** Эквалайзер: играет — столбики прыгают, пауза — стоят низко. */
    private void bars(Canvas c, float right, float cy, boolean playing, float ca, int col) {
        long now = SystemClock.uptimeMillis();
        float bw = Ui.dpf(3), gap = Ui.dpf(3), maxH = Ui.dpf(14);
        fill.setColor(Ui.alpha(col, ca));
        for (int i = 0; i < 4; i++) {
            float k = playing ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(now / (150f + i * 37f) + i * 1.7f)) : 0.22f;
            float h = maxH * k, x = right - (4 - i) * (bw + gap) + gap;
            q.set(x, cy - h / 2, x + bw, cy + h / 2);
            c.drawRoundRect(q, bw / 2, bw / 2, fill);
        }
    }

    private void check(Canvas c, float cx, float cy, float s, int col) {
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Ui.dpf(2.6f));
        fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(col);
        Path p = new Path();
        p.moveTo(cx - s * 0.5f, cy);
        p.lineTo(cx - s * 0.12f, cy + s * 0.38f);
        p.lineTo(cx + s * 0.52f, cy - s * 0.36f);
        c.drawPath(p, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    private static String cut(String s, Paint p, float w) {
        if (s == null) return "";
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, Math.max(0, w - p.measureText("…")), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    // ── касания ───────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Item it = shown;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (it == null || alpha < 0.5f || !r.contains(e.getX(), e.getY())) return false;
                x0 = e.getX();
                y0 = e.getY();
                downAt = SystemClock.uptimeMillis();
                swiping = false;
                if (dxAnim != null) dxAnim.cancel();
                ui.removeCallbacks(collapse);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float ddx = e.getX() - x0, ddy = e.getY() - y0;
                if (!swiping && ddx < -Ui.dpf(10) && Math.abs(ddx) > Math.abs(ddy)) {
                    swiping = true;
                    fit();
                }
                if (swiping) {
                    dx = Math.min(0, ddx);
                    invalidate();
                } else if (ddy > Ui.dpf(24) && !expanded) {
                    setExpanded(true);
                    y0 = e.getY() + Ui.dpf(1000);   // один раз за жест
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (it == null) return true;
                if (swiping) {
                    swiping = false;
                    float v = dx / Math.max(1, SystemClock.uptimeMillis() - downAt) * 1000f;
                    if (dx < -Ui.dpf(64) || v < -Ui.dpf(900)) dismiss(it);
                    else back();
                    return true;
                }
                boolean tap = e.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.hypot(e.getX() - x0, e.getY() - y0) < Ui.dpf(14);
                if (tap) tapped(it, e.getX());
                else if (expanded) ui.postDelayed(collapse, 6000);
                return true;
            }
        }
        return true;
    }

    private void back() {
        dxAnim = Anim.spring(dx, 0, 0, 500, 0.8f, v -> {
            dx = v;
            invalidate();
        }, this::fit);
    }

    /** Улетает влево и не возвращается, пока это не закончится. */
    private void dismiss(Item it) {
        Feel.buzz(this, Feel.TICK);
        leaving = true;
        fit();
        dxAnim = Anim.run(dx, -screenW() * 0.9f, 260, Anim.STD, v -> {
            dx = v;
            alpha = Ui.clamp(1 + v / (screenW() * 0.6f));
            invalidate();
        }, () -> {
            leaving = false;
            shown = null;
            alpha = 0;
            dx = 0;
            expanded = false;
            pw = toW = fromW = Ui.dpf(96);
            ph = toH = fromH = Ui.dpf(30);
            if (it.kind != VOLUME && it.kind != SCAN) dismissed.put(it.key, sig(it));
            items.remove(it.key);
            fit();
            refresh();
        });
    }

    private void tapped(Item it, float x) {
        if (!expanded) {
            Feel.buzz(this, Feel.TICK);
            setExpanded(true);
            return;
        }
        boolean player = it.kind == MEDIA || it.kind == VOICE;
        if (player && it.media != null && x > r.right - Ui.dpf(110)) {
            Feel.buzz(this, Feel.TICK);
            try {
                MediaController.TransportControls tc = it.media.getTransportControls();
                if (x > r.right - Ui.dpf(40)) tc.skipToNext();
                else if (it.playing) tc.pause();
                else tc.play();
            } catch (RuntimeException ignored) {
            }
            ui.postDelayed(collapse, 6000);
            return;
        }
        setExpanded(false);
        if (it.open != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    it.open.send(getContext(), 0, null, null, null, null, o.toBundle());
                } else it.open.send();
            } catch (Exception ignored) {
            }
        }
    }
}
