package os.vanks.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Рамки плавающих окон Vanks. Окна произвольной формы рисует сам Android,
 * Vanks (служба «Vanks: жесты и вход») кладёт поверх каждого окна:
 *   • рамку снаружи окна со скруглёнными углами — её внутренние углы
 *     закрывают квадратные углы окна (касания сквозь рамку проходят в окно);
 *   • пилюлю над окном: «свернуть», «на весь экран», «закрыть»; потянуть её —
 *     окно переезжает;
 *   • уголок справа снизу — потянуть, окно меняет размер.
 * Пока палец ведёт, рисуется контур нового места, по отпусканию окно встаёт туда.
 * На Vanks OS у окна свой заголовок Android (назад, свернуть, развернуть,
 * закрыть; потянуть — окно переезжает) — пилюлю там не рисуем, чтобы не было
 * двух рядов кнопок, а уголок размера — только если окна двигает сам
 * диспетчер задач (Tasks): тянуть край окна Android не даёт. На других
 * телефонах — жест по заголовку окна или его краю (dispatchGesture), а кнопки
 * ищут свои в заголовке окна Android (maximize_window, close_window, minimize_window).
 * Рамка только у самого окна: диалоги и меню внутри окна или поверх
 * приложения на весь экран рамки не получают.
 */
final class WinFrames {
    // RING — ширина рамки снаружи окна; R_OUT — скругление снаружи (не больше
    // RING × 3,4, иначе угол окна высунется за рамку); R_IN — скругление внутри
    private static final float RING_DP = 6, R_OUT_DP = 18, R_IN_DP = 12, BAR_W_DP = 132, BAR_H_DP = 30, GRIP_DP = 34;

    private final VanksAccess s;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<Integer, Frame> frames = new HashMap<>();
    private boolean dragging;
    // у окон есть заголовок Android с кнопками (Vanks OS — AOSP с оболочкой WM Shell)
    private final boolean caption = Updater.osVersion() != null;

    WinFrames(VanksAccess s) {
        this.s = s;
        this.wm = s.getSystemService(WindowManager.class);
    }

    /** Окно приложения на экране: место, приложение и порядок (0 — самое верхнее). */
    private static final class Win {
        final Rect b = new Rect();
        String pkg;
        int z, id;
    }

    /** Окна поменялись (служба зовёт на TYPE_WINDOWS_CHANGED и по таймеру). */
    void update() {
        if (dragging) return;
        HashSet<Integer> seen = new HashSet<>();
        if (Prefs.winFrames) {
            ArrayList<Win> apps = appWindows();
            if (Tasks.ok(s)) {
                for (Tasks.Task t : Tasks.freeform(s)) {
                    if (!t.visible || t.pkg.equals(s.getPackageName())) continue;
                    // окно задачи на экране: верхнее окно того же приложения в её границах
                    Win w = null;
                    for (Win a : apps) {
                        if (a.pkg.equals(t.pkg) && Rect.intersects(a.b, t.bounds)) { w = a; break; }
                    }
                    if (w == null) continue;
                    show(seen, t.id, t.bounds, t.pkg, t, w.z, apps);
                }
            } else {
                int sw = s.getResources().getDisplayMetrics().widthPixels;
                for (Win w : apps) {
                    if (w.pkg.equals(s.getPackageName())) continue;
                    if (w.b.width() >= sw * 0.98f || w.b.width() < Ui.dp(80) || w.b.height() < Ui.dp(80)) continue;
                    if (inside(w, apps, sw)) continue;
                    show(seen, w.id, w.b, w.pkg, null, w.z, apps);
                }
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

    private ArrayList<Win> appWindows() {
        ArrayList<Win> out = new ArrayList<>();
        List<AccessibilityWindowInfo> ws;
        try {
            ws = s.getWindows();
        } catch (RuntimeException e) {
            return out;
        }
        // getWindows — сверху вниз
        for (int i = 0; i < ws.size(); i++) {
            AccessibilityWindowInfo w = ws.get(i);
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode()) continue;
            AccessibilityNodeInfo root = w.getRoot();
            String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (pkg.isEmpty()) continue;
            Win a = new Win();
            w.getBoundsInScreen(a.b);
            a.pkg = pkg;
            a.z = i;
            a.id = w.getId();
            out.add(a);
        }
        return out;
    }

    /** Диалог или меню: внутри другого окна того же приложения или поверх него на весь экран. */
    private static boolean inside(Win w, List<Win> apps, int sw) {
        for (Win o : apps) {
            if (o == w || !o.pkg.equals(w.pkg)) continue;
            if (o.b.width() >= sw * 0.98f) return true;
            if (o.b.contains(w.b) && !o.b.equals(w.b)) return true;
        }
        return false;
    }

    private void show(HashSet<Integer> seen, int id, Rect b, String pkg, Tasks.Task task, int z, List<Win> apps) {
        seen.add(id);
        // что лежит выше этого окна: там рамку не рисуем, чтобы не наползала на соседа
        ArrayList<Rect> above = new ArrayList<>();
        for (Win a : apps) {
            if (a.z < z && !(a.pkg.equals(pkg) && b.contains(a.b))) above.add(new Rect(a.b));
        }
        Frame f = frames.get(id);
        if (f == null) {
            f = new Frame(id);
            frames.put(id, f);
        }
        f.place(b, pkg, task, above);
    }

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
        Tasks.Task task;
        final ArrayList<Rect> above = new ArrayList<>();
        final Ring ring;
        final Bar bar;
        final Grip grip;
        final WindowManager.LayoutParams ringLp = lp(false), barLp = lp(true), gripLp = lp(true);
        boolean barHidden, gripHidden, passing;
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

        void place(Rect b, String p, Tasks.Task t, List<Rect> over) {
            pkg = p;
            task = t;
            boolean same = b.equals(bounds) && over.equals(above) && ring.isAttachedToWindow();
            above.clear();
            above.addAll(over);
            if (same) return;
            bounds.set(b);
            int r = Ui.dp(RING_DP);
            ringLp.x = b.left - r;
            ringLp.y = b.top - r;
            ringLp.width = b.width() + 2 * r;
            ringLp.height = b.height() + 2 * r;
            int bw = Ui.dp(BAR_W_DP), bh = Ui.dp(BAR_H_DP);
            barLp.width = bw;
            barLp.height = bh;
            barLp.x = b.centerX() - bw / 2;
            // над окном; у самого верха экрана — внутри, по верхнему краю
            int up = b.top - r - bh - Ui.dp(6);
            barLp.y = up > Ui.dp(28) ? up : b.top + Ui.dp(6);
            int g = Ui.dp(GRIP_DP);
            gripLp.width = gripLp.height = g;
            gripLp.x = b.right + r - g + Ui.dp(8);
            gripLp.y = b.bottom + r - g + Ui.dp(8);
            // пилюля и уголок под чужим окном — прячем, иначе нажмутся сквозь него
            barHidden = caption || covered(barLp);
            gripHidden = caption && task == null || covered(gripLp);
            relayout();
        }

        private boolean covered(WindowManager.LayoutParams l) {
            Rect r = new Rect(l.x, l.y, l.x + l.width, l.y + l.height);
            for (Rect a : above) if (Rect.intersects(a, r)) return true;
            return false;
        }

        void relayout() {
            touchable(barLp, !barHidden && !passing);
            touchable(gripLp, !gripHidden && !passing);
            bar.setVisibility(barHidden ? View.INVISIBLE : View.VISIBLE);
            grip.setVisibility(gripHidden ? View.INVISIBLE : View.VISIBLE);
            try {
                wm.updateViewLayout(ring, ringLp);
                wm.updateViewLayout(bar, barLp);
                wm.updateViewLayout(grip, gripLp);
            } catch (RuntimeException ignored) {
            }
            ring.invalidate();
        }

        private void touchable(WindowManager.LayoutParams l, boolean on) {
            if (on) l.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else l.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        void remove() {
            for (View v : new View[]{ring, bar, grip}) {
                try { wm.removeViewImmediate(v); } catch (RuntimeException ignored) {}
            }
        }

        /** Рамка снаружи окна и контур нового места, пока палец ведёт. */
        final class Ring extends View {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                    ghost = new Paint(Paint.ANTI_ALIAS_FLAG), veil = new Paint(Paint.ANTI_ALIAS_FLAG);
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
                veil.setColor(Ui.alpha(Ui.accentLight(), 0.16f));
            }

            @Override
            protected void onDraw(Canvas c) {
                float t = Ui.dpf(RING_DP), ro = Ui.dpf(R_OUT_DP), ri = Ui.dpf(R_IN_DP);
                // растянута на весь экран (палец ведёт) — рамка на месте окна, иначе по своему размеру
                boolean wide = moving || sizing;
                float ox = wide ? 0 : ringLp.x, oy = wide ? 0 : ringLp.y;
                c.save();
                for (Rect a : above) c.clipOutRect(a.left - ox, a.top - oy, a.right - ox, a.bottom - oy);
                float x = bounds.left - t - ox, y = bounds.top - t - oy;
                float w = bounds.width() + 2 * t, h = bounds.height() + 2 * t;
                path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                path.addRoundRect(new RectF(x, y, x + w, y + h), ro, ro, Path.Direction.CW);
                path.addRoundRect(new RectF(x + t, y + t, x + w - t, y + h - t), ri, ri, Path.Direction.CW);
                c.drawPath(path, fill);
                c.drawRoundRect(new RectF(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f), ro, ro, rim);
                c.restore();
                if (wide) {
                    // контур нового места: окно встанет сюда, когда палец отпустит
                    RectF g = new RectF(x + dx, y + dy, x + w + dx + dw, y + h + dy + dh);
                    c.drawRoundRect(g, ro, ro, veil);
                    c.drawRoundRect(g, ro, ro, ghost);
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
                float w = getWidth(), h = getHeight(), o = Ui.dpf(8), ro = Ui.dpf(R_OUT_DP);
                // дуга по скруглённому углу рамки
                c.drawArc(new RectF(w - o - 2 * ro, h - o - 2 * ro, w - o, h - o), 15, 60, false, p);
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
            place(b, pkg, task, new ArrayList<>(above));
        }

        void finishMove() {
            int mx = dx, my = dy;
            cancelDrag();
            if (task != null) {
                Rect r = new Rect(bounds);
                r.offset(mx, my);
                set(r);
                return;
            }
            Rect cap = caption();
            // за заголовок окна Android; нет заголовка — за верхнюю полосу окна (её система и держит)
            float x0 = cap != null ? cap.centerX() : bounds.centerX();
            float y0 = cap != null ? cap.centerY() : bounds.top + Ui.dpf(14);
            drag(x0, y0, x0 + mx, y0 + my, true);
        }

        void finishSize() {
            int w = dw, h = dh;
            cancelDrag();
            if (task != null) {
                set(new Rect(bounds.left, bounds.top, bounds.right + w, bounds.bottom + h));
                return;
            }
            // за край окна снаружи: там система ловит изменение размера
            float x0 = bounds.right + Ui.dpf(5), y0 = bounds.bottom + Ui.dpf(5);
            drag(x0, y0, x0 + w, y0 + h, false);
        }

        /** Окно на новое место (Vanks OS): не выше строки состояния и не целиком за краем. */
        void set(Rect r) {
            android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
            int keep = Ui.dp(72), top = Ui.dp(30) + Ui.dp(BAR_H_DP) + Ui.dp(RING_DP);
            r.offset(0, Math.max(0, top - r.top));
            r.offset(Math.max(0, keep - r.right), 0);
            r.offset(Math.min(0, dm.widthPixels - keep - r.left), 0);
            r.offset(0, Math.min(0, dm.heightPixels - keep - r.top));
            Tasks.place(task, r);
            later();
        }

        /** Сверить рамки чуть позже: система ставит окно не мгновенно. */
        void later() {
            ui.postDelayed(WinFrames.this::update, 120);
            ui.postDelayed(WinFrames.this::update, 400);
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
            passing = on;
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
            // Vanks OS: окно уходит под стол одно, остальные остаются; вернуть — значок
            // на столе, в панели или карточка в «Недавних»
            if (task != null && Tasks.minimize(task)) { later(); return; }
            if (press("minimize_window", "minimize_button")) return;
            // свернуть одно окно Android стороннему не даёт — окна уходят, виден стол
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }

        void maximize() {
            if (task != null && Tasks.maximize(task)) { later(); return; }
            if (press("maximize_window", "maximize_button_view", "maximize_button")) return;
            Windows.fullscreen(s, pkg);
        }

        void close() {
            String p = pkg;
            if (task != null && Tasks.close(task)) { later(); return; }
            if (press("close_window", "close_button")) return;
            remove();
            frames.remove(id);
            Unload.closeWindow(s, p);
        }
    }
}
