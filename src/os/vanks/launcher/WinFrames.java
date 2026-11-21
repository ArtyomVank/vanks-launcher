package os.vanks.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Рамки плавающих окон Vanks. Окна произвольной формы рисует и двигает сам
 * Android, Vanks (служба «Vanks: жесты и вход») кладёт поверх каждого окна:
 *   • скруглённую рамку — она закрывает квадратные углы окна (сквозь неё
 *     касания проходят к приложению и к краю, за который система меняет размер);
 *   • пилюлю над окном: «свернуть», «на весь экран», «закрыть»; потянуть её —
 *     окно переезжает;
 *   • уголок справа снизу — потянуть, окно меняет размер.
 * Двигает и тянет окно сама система: по отпусканию пальца служба проводит
 * такой же жест по заголовку окна или по его краю (dispatchGesture), а пока
 * палец ведёт — рисуется контур нового места. Кнопки сначала ищут свои в
 * заголовке окна Android (maximize_window, close_window, minimize_window);
 * нет — «свернуть» уводит окна на стол, «закрыть» выгружает приложение.
 */
final class WinFrames {
    private static final float R_DP = 20, RING_DP = 3, BAR_W_DP = 132, BAR_H_DP = 30, GRIP_DP = 30;

    private final VanksAccess s;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<Integer, Frame> frames = new HashMap<>();
    private boolean dragging;

    WinFrames(VanksAccess s) {
        this.s = s;
        this.wm = s.getSystemService(WindowManager.class);
    }

    /** Окна поменялись (служба зовёт на TYPE_WINDOWS_CHANGED и по таймеру). */
    void update() {
        if (dragging) return;
        HashSet<Integer> seen = new HashSet<>();
        if (Prefs.winFrames) {
            int sw = s.getResources().getDisplayMetrics().widthPixels;
            List<AccessibilityWindowInfo> ws;
            try {
                ws = s.getWindows();
            } catch (RuntimeException e) {
                ws = java.util.Collections.emptyList();
            }
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode()) continue;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                // на весь экран и половинки экрана — без рамки
                if (b.width() >= sw * 0.98f || b.width() < Ui.dp(80) || b.height() < Ui.dp(80)) continue;
                AccessibilityNodeInfo root = w.getRoot();
                String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
                if (pkg.isEmpty() || pkg.equals(s.getPackageName())) continue;
                seen.add(w.getId());
                Frame f = frames.get(w.getId());
                if (f == null) {
                    f = new Frame(w.getId());
                    frames.put(w.getId(), f);
                }
                f.place(b, pkg);
            }
        }
        for (Integer id : new HashSet<>(frames.keySet())) {
            if (!seen.contains(id)) frames.remove(id).remove();
        }
        ui.removeCallbacks(poll);
        // пока окна есть — сверяемся чаще: система двигает их и без событий
        if (!frames.isEmpty()) ui.postDelayed(poll, 400);
    }

    private final Runnable poll = this::update;

    void clear() {
        for (Frame f : frames.values()) f.remove();
        frames.clear();
        ui.removeCallbacks(poll);
    }

    private WindowManager.LayoutParams lp(boolean touch) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | (touch ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.windowAnimations = 0;
        lp.setTitle("Vanks window frame");
        return lp;
    }

    // ── одно окно ─────────────────────────────────────────────────────────

    private final class Frame {
        final int id;
        final Rect bounds = new Rect();
        String pkg = "";
        final Ring ring;
        final Bar bar;
        final Grip grip;
        final WindowManager.LayoutParams ringLp = lp(false), barLp = lp(true), gripLp = lp(true);
        // куда ведёт палец: смещение окна (перенос) или новый правый нижний угол (размер)
        int dx, dy, dw, dh;
        boolean moving, sizing;

        Frame(int id) {
            this.id = id;
            ring = new Ring(s);
            bar = new Bar(s);
            grip = new Grip(s);
            try {
                wm.addView(ring, ringLp);
                wm.addView(bar, barLp);
                wm.addView(grip, gripLp);
            } catch (RuntimeException ignored) {
            }
        }

        void place(Rect b, String p) {
            pkg = p;
            if (b.equals(bounds) && ring.isAttachedToWindow()) return;
            bounds.set(b);
            int t = Ui.dp(RING_DP);
            // рамка шире окна на толщину — и снаружи, и на углах внутрь
            ringLp.x = b.left - t;
            ringLp.y = b.top - t;
            ringLp.width = b.width() + 2 * t;
            ringLp.height = b.height() + 2 * t;
            int bw = Ui.dp(BAR_W_DP), bh = Ui.dp(BAR_H_DP);
            barLp.width = bw;
            barLp.height = bh;
            barLp.x = b.centerX() - bw / 2;
            // над окном; у самого верха экрана — внутри, по верхнему краю
            barLp.y = b.top - bh - Ui.dp(6) > Ui.dp(28) ? b.top - bh - Ui.dp(6) : b.top + Ui.dp(6);
            int g = Ui.dp(GRIP_DP);
            gripLp.width = gripLp.height = g;
            gripLp.x = b.right - g + Ui.dp(10);
            gripLp.y = b.bottom - g + Ui.dp(10);
            relayout();
        }

        void relayout() {
            try {
                wm.updateViewLayout(ring, ringLp);
                wm.updateViewLayout(bar, barLp);
                wm.updateViewLayout(grip, gripLp);
            } catch (RuntimeException ignored) {
            }
            ring.invalidate();
        }

        void remove() {
            for (View v : new View[]{ring, bar, grip}) {
                try { wm.removeViewImmediate(v); } catch (RuntimeException ignored) {}
            }
        }

        /** Рамка: скруглённое кольцо поверх краёв окна и контур нового места, пока палец ведёт. */
        final class Ring extends View {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                    ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            Ring(Context c) {
                super(c);
                fill.setColor(0xFF16161A);
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(Ui.dpf(1));
                rim.setColor(0x40FFFFFF);
                ghost.setStyle(Paint.Style.STROKE);
                ghost.setStrokeWidth(Ui.dpf(2.5f));
                ghost.setColor(Ui.accentLight());
            }

            @Override
            protected void onDraw(Canvas c) {
                float t = Ui.dpf(RING_DP), r = Ui.dpf(R_DP);
                // растянута на весь экран (палец ведёт) — рамка на месте окна, иначе по своему размеру
                boolean wide = moving || sizing;
                float x = wide ? bounds.left - t : 0, y = wide ? bounds.top - t : 0;
                float w = wide ? bounds.width() + 2 * t : getWidth(), h = wide ? bounds.height() + 2 * t : getHeight();
                path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                path.addRoundRect(new RectF(x, y, x + w, y + h), r + t, r + t, Path.Direction.CW);
                path.addRoundRect(new RectF(x + t, y + t, x + w - t, y + h - t), r, r, Path.Direction.CW);
                c.drawPath(path, fill);
                c.drawRoundRect(new RectF(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f), r + t, r + t, rim);
                if (wide) {
                    // контур нового места
                    RectF g = new RectF(x + t + dx, y + t + dy, x + w - t + dx + dw, y + h - t + dy + dh);
                    c.drawRoundRect(g, r, r, ghost);
                }
            }
        }

        /** Пилюля над окном: свернуть, на весь экран, закрыть; потянуть — перенести окно. */
        final class Bar extends View {
            private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
            private float downX, downY;
            private boolean drag;

            Bar(Context c) {
                super(c);
                bg.setColor(0xE616161A);
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeCap(Paint.Cap.ROUND);
                line.setStrokeWidth(Ui.dpf(1.8f));
                line.setColor(0xFFFFFFFF);
            }

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, bg);
                float cell = w / 3, cy = h / 2, k = Ui.dpf(5.5f);
                // свернуть — черта, на весь экран — скруглённый квадрат, закрыть — крестик
                c.drawLine(cell * 0.5f - k, cy, cell * 0.5f + k, cy, line);
                c.drawRoundRect(new RectF(cell * 1.5f - k, cy - k, cell * 1.5f + k, cy + k), Ui.dpf(2.5f), Ui.dpf(2.5f), line);
                Glyph.close(c, cell * 2.5f, cy, Ui.dpf(15), 0xFFFF6B6B);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        drag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downX, my = e.getRawY() - downY;
                        if (!drag && Math.hypot(mx, my) > Ui.dp(8)) {
                            drag = true;
                            moving = true;
                            dragging = true;
                            stretch();
                        }
                        if (drag) {
                            dx = Math.round(mx);
                            dy = Math.round(my);
                            ring.invalidate();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (drag) finishMove();
                        else {
                            int which = (int) (e.getX() / (getWidth() / 3f));
                            Feel.buzz(this, Feel.TICK);
                            if (which == 0) minimize();
                            else if (which == 1) maximize();
                            else close();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (drag) cancelDrag();
                        return true;
                }
                return false;
            }
        }

        /** Уголок справа снизу: потянуть — новый размер. */
        final class Grip extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private float downX, downY;

            Grip(Context c) {
                super(c);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(Ui.dpf(3));
                p.setColor(0xCCFFFFFF);
            }

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), k = Ui.dpf(10), o = Ui.dpf(10);
                // дуга по скруглённому углу окна
                c.drawArc(new RectF(w - o - 2 * k - Ui.dpf(R_DP), h - o - 2 * k - Ui.dpf(R_DP), w - o, h - o), 15, 60, false, p);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        sizing = true;
                        dragging = true;
                        stretch();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int min = Ui.dp(160);
                        dw = Math.max(min - bounds.width(), Math.round(e.getRawX() - downX));
                        dh = Math.max(min - bounds.height(), Math.round(e.getRawY() - downY));
                        ring.invalidate();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        finishSize();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelDrag();
                        return true;
                }
                return false;
            }
        }

        /** Пока ведём палец — окно рамки на весь экран, чтобы контур был виден везде. */
        void stretch() {
            android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
            ringLp.x = 0;
            ringLp.y = 0;
            ringLp.width = dm.widthPixels;
            ringLp.height = dm.heightPixels + Ui.dp(200);
            relayout();
        }

        void cancelDrag() {
            moving = sizing = false;
            dragging = false;
            dx = dy = dw = dh = 0;
            Rect b = new Rect(bounds);
            bounds.setEmpty();
            place(b, pkg);
        }

        void finishMove() {
            int mx = dx, my = dy;
            cancelDrag();
            Rect cap = caption();
            // за заголовок окна Android; нет заголовка — за верхнюю полосу окна (её система и держит)
            float x0 = cap != null ? cap.centerX() : bounds.centerX();
            float y0 = cap != null ? cap.centerY() : bounds.top + Ui.dpf(14);
            drag(x0, y0, x0 + mx, y0 + my, true);
        }

        void finishSize() {
            int w = dw, h = dh;
            cancelDrag();
            // за край окна снаружи: там система ловит изменение размера
            float x0 = bounds.right + Ui.dpf(5), y0 = bounds.bottom + Ui.dpf(5);
            drag(x0, y0, x0 + w, y0 + h, false);
        }

        /** Провести жест системы: наши окна на это время пропускают касания. */
        void drag(float x0, float y0, float x1, float y1, boolean hold) {
            if (Math.hypot(x1 - x0, y1 - y0) < Ui.dp(4)) return;
            passThrough(true);
            Path p = new Path();
            p.moveTo(x0, y0);
            p.lineTo(x0, y0);
            Path q = new Path();
            q.moveTo(x0, y0);
            q.lineTo(x1, y1);
            GestureDescription.Builder g = new GestureDescription.Builder();
            // короткая задержка на месте — система берёт окно, потом ведём
            GestureDescription.StrokeDescription press = new GestureDescription.StrokeDescription(p, 0, hold ? 120 : 60, true);
            g.addStroke(press.continueStroke(q, hold ? 120 : 60, 320, false));
            boolean ok = s.dispatchGesture(g.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) { done(); }

                @Override
                public void onCancelled(GestureDescription d) { done(); }

                private void done() {
                    ui.postDelayed(() -> {
                        passThrough(false);
                        update();
                    }, 150);
                }
            }, ui);
            if (!ok) passThrough(false);
        }

        void passThrough(boolean on) {
            for (WindowManager.LayoutParams l : new WindowManager.LayoutParams[]{barLp, gripLp}) {
                if (on) l.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                else l.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            relayout();
        }

        // ── кнопки ────────────────────────────────────────────────────────

        /** Заголовок окна Android (если он есть) — за него окно и переносится. */
        Rect caption() {
            AccessibilityNodeInfo n = find("caption", "caption_handle", "decor_caption");
            if (n == null) return null;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r.height() > 0 && Math.abs(r.top - bounds.top) < Ui.dp(80) ? r : null;
        }

        /** Кнопка в заголовке окна Android по id (часть имени) — рядом с этим окном. */
        AccessibilityNodeInfo find(String... ids) {
            List<AccessibilityWindowInfo> ws;
            try {
                ws = s.getWindows();
            } catch (RuntimeException e) {
                return null;
            }
            for (AccessibilityWindowInfo w : ws) {
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                if (!Rect.intersects(wb, new Rect(bounds.left, bounds.top - Ui.dp(80), bounds.right, bounds.bottom))) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
                q.add(root);
                int seen = 0;
                while (!q.isEmpty() && seen++ < 400) {
                    AccessibilityNodeInfo n = q.poll();
                    String id = n.getViewIdResourceName();
                    if (id != null) {
                        String tail = id.substring(id.indexOf('/') + 1);
                        for (String want : ids) if (tail.equals(want)) return n;
                    }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo ch = n.getChild(i);
                        if (ch != null) q.add(ch);
                    }
                }
            }
            return null;
        }

        boolean press(String... ids) {
            AccessibilityNodeInfo n = find(ids);
            return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        void minimize() {
            if (press("minimize_window", "minimize_button")) return;
            // свернуть одно окно Android стороннему не даёт — окна уходят, виден стол;
            // вернуть — значок в панели или в «Недавних»
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }

        void maximize() {
            if (press("maximize_window", "maximize_button_view", "maximize_button")) return;
            Windows.fullscreen(s, pkg);
        }

        void close() {
            if (press("close_window", "close_button")) return;
            String p = pkg;
            remove();
            frames.remove(id);
            Unload.closeWindow(s, p);
        }
    }
}
