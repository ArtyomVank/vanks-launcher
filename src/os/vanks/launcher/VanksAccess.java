package os.vanks.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * «Vanks: жесты и вход» — служба специальных возможностей.
 *
 * Жесты: HyperOS и MIUI с чужим лаунчером жесты Android не слушают (или
 * ставят кнопки). Здесь свои: тонкая полоса у нижнего края — свайп вверх
 * «Домой», вверх с задержкой — «Недавние»; полосы у боковых краёв — свайп
 * внутрь «Назад». Действия выполняет сама система (performGlobalAction).
 *
 * Вход: палец или пароль приняты на экране Vanks, а Android заперт PIN-кодом
 * — служба сама набирает этот PIN на системном экране (PIN хранится
 * зашифрованным ключом Android, см. Secret). Одна попытка на вход: если
 * клавиатура не нашлась целиком, ничего не нажимается.
 */
public final class VanksAccess extends AccessibilityService {
    static VanksAccess on;

    private WindowManager wm;
    private Strip bottom, left, right;
    private final Handler ui = new Handler(Looper.getMainLooper());

    static boolean running() {
        return on != null;
    }

    /**
     * Включить службу: с разрешением WRITE_SECURE_SETTINGS (выдаётся один раз
     * через ADB, см. Nav) — сразу, сами; без него — открыть её страницу в
     * настройках Android, где остаётся один переключатель. true — включили сами.
     */
    static boolean turnOn(android.app.Activity a) {
        if (running()) return true;
        String me = new android.content.ComponentName(a, VanksAccess.class).flattenToString();
        if (Nav.canWrite(a)) {
            try {
                android.content.ContentResolver cr = a.getContentResolver();
                String list = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (list == null || list.isEmpty()) list = me;
                else if (!list.contains(me)) list = list + ":" + me;
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
                android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        // список служб с подсвеченной нашей (страницу одной службы Android
        // открывает только системным приложениям)
        android.os.Bundle args = new android.os.Bundle();
        args.putString(":settings:fragment_args_key", me);
        android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", me).putExtra(":settings:show_fragment_args", args);
        try {
            a.startActivity(i);
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Настройки жестов или острова сменились. */
    static void sync() {
        if (on != null) {
            on.place();
            on.placeIsland();
        }
    }

    @Override
    protected void onServiceConnected() {
        on = this;
        Prefs.load(this);
        Ui.init(this);
        wm = getSystemService(WindowManager.class);
        place();
        placeIsland();
        listenSound();
    }

    @Override
    public boolean onUnbind(android.content.Intent i) {
        removeAll();
        dropIsland();
        stopSound();
        if (on == this) on = null;
        return super.onUnbind(i);
    }

    @Override
    public void onDestroy() {
        if (fly != null) {
            fly.stop();
            try { wm.removeView(fly); } catch (Exception ignored) {}
            fly = null;
        }
        removeAll();
        dropIsland();
        stopSound();
        if (on == this) on = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    // ── жесты ─────────────────────────────────────────────────────────────

    private void removeAll() {
        for (Strip s : new Strip[]{bottom, left, right}) {
            if (s == null) continue;
            try { wm.removeView(s); } catch (Exception ignored) {}
        }
        bottom = left = right = null;
    }

    private void place() {
        removeAll();
        if (!Prefs.navGestures || wm == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bottom = new Strip(this, Strip.BOTTOM);
        add(bottom, -1, Ui.dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0);
        if (Prefs.navBack) {
            // у края боковой панели Vanks — её ручка, полосу «назад» там не ставим
            int top = Math.round(dm.heightPixels * 0.18f), h = Math.round(dm.heightPixels * 0.68f);
            left = new Strip(this, Strip.LEFT);
            right = new Strip(this, Strip.RIGHT);
            addSide(left, Gravity.LEFT, top, h, Prefs.edge && Prefs.edgeLeft, dm);
            addSide(right, Gravity.RIGHT, top, h, Prefs.edge && !Prefs.edgeLeft, dm);
        }
    }

    private void addSide(Strip s, int g, int top, int h, boolean edgeHere, DisplayMetrics dm) {
        if (edgeHere) {
            // ручка панели: 0,3 высоты, 170 dp — полоса ниже неё
            int handleEnd = Math.round(dm.heightPixels * 0.3f) + Ui.dp(178);
            h = top + h - handleEnd;
            top = handleEnd;
            if (h < Ui.dp(80)) return;
        }
        add(s, Ui.dp(16), h, g | Gravity.TOP, top);
    }

    private void add(Strip s, int w, int h, int gravity, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.y = y;
        // Android 11+ сам отодвигает окно от системных полос — тогда полоса не у края и пальцу не попасть
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        lp.setTitle("Vanks gestures");
        if (android.os.Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try {
            wm.addView(s, lp);
            s.lp = lp;
        } catch (Exception ignored) {
        }
    }

    /** Окно-полоса на время жеста становится шире: в нём рисуется стрелка «назад». */
    private void grow(Strip s, boolean big) {
        if (s.lp == null || s.kind == Strip.BOTTOM) return;
        s.lp.width = big ? Ui.dp(96) : Ui.dp(16);
        try { wm.updateViewLayout(s, s.lp); } catch (Exception ignored) {}
    }

    // ── закрытие приложения жестом: окно за пальцем и в свой значок ───────

    private Bitmap shot;
    private String shotPkg;
    private CloseFly fly;
    private int shotGen;

    /** Окно летит в значок прямо сейчас (стол не рисует свою карточку). */
    static boolean flying() {
        return on != null && on.fly != null;
    }

    private static final java.util.Set<String> HOMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.miui.home", "com.mi.android.globallauncher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
            "com.android.systemui", "android"));

    /** Палец лёг на нижний край: заранее снять окно приложения (снимок приходит не сразу). */
    private void prepareShot() {
        dropShot();
        if (android.os.Build.VERSION.SDK_INT < 30 || !Prefs.liveClose) return;
        String pkg = front;
        if (pkg.isEmpty() || pkg.equals(getPackageName()) || HOMES.contains(pkg) || pkg.contains("inputmethod")) return;
        shotPkg = pkg;
        int gen = ++shotGen;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap b = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    hb.close();
                    if (gen != shotGen || b == null) {
                        if (b != null) b.recycle();
                        return;
                    }
                    shot = b;
                    // и в «Недавние»: окно таким, каким его оставили
                    Recents.shot(VanksAccess.this, pkg, b, false);
                }

                @Override
                public void onFailure(int code) {
                    // окно защищено от снимков или просили слишком часто — обычное «Домой»
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void dropShot() {
        shotGen++;
        if (shot != null && fly == null) shot.recycle();
        shot = null;
    }

    private void startFly(float rawX, float rawY) {
        if (wm == null) return;
        if (Ui.wall == null) Ui.loadWall(this);
        Bitmap icon = shotPkg != null && shotPkg.equals(Home.lastPkg) ? Home.lastIcon : appIcon(shotPkg);
        CloseFly f = new CloseFly(this, shot, icon);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("Vanks close");
        try {
            wm.addView(f, lp);
        } catch (Exception e) {
            return;
        }
        fly = f;
        f.start(rawX, rawY);
        Feel.buzz(f, Feel.TICK);
        // стол — сразу под окно, пока палец ещё ведёт
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** Отпустили: в значок, в «Недавние» или назад в приложение. */
    private void endFly(boolean recents, boolean home, float vx, float vy) {
        CloseFly f = fly;
        Bitmap b = shot;
        shot = null;
        String pkg = shotPkg;
        Runnable remove = () -> {
            try { wm.removeView(f); } catch (Exception ignored) {}
            f.stop();
            if (fly == f) fly = null;
            if (b != null) b.recycle();
        };
        if (recents && Prefs.ownRecents) {
            // свои «Недавние»: окно уменьшается прямо в первую карточку
            RecentsActivity.open(this, true);
            Feel.buzz(f, Feel.CLICK);
            f.toCard(RecentsActivity.firstCard(f.getWidth(), f.getHeight()), vx, vy, h -> {
                RecentsActivity.landed();
                ui.postDelayed(remove, 120);
            });
            return;
        }
        if (recents) {
            performGlobalAction(GLOBAL_ACTION_RECENTS);
            f.animate().alpha(0).setDuration(160).withEndAction(remove).start();
            return;
        }
        if (home) {
            Feel.buzz(f, Feel.CLICK);
            RectF to = pkg != null && pkg.equals(Home.lastPkg) ? Home.lastRect : null;
            f.release(true, vx, vy, to, h -> remove.run());
            return;
        }
        // мало потянули — приложение обратно
        f.release(false, 0, 0, null, h -> ui.postDelayed(remove, 120));
        try {
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    private Bitmap appIcon(String pkg) {
        try {
            android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(pkg);
            int s = Ui.dp(56);
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, s, s);
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private final class Strip extends View {
        static final int BOTTOM = 0, LEFT = 1, RIGHT = 2;
        final int kind;
        WindowManager.LayoutParams lp;
        private float x0, y0, pull, lastY, vx, vy, lastRawX, lastRawY;
        private long lastT;
        private long lastMove, downAt;
        private boolean active;
        // «Недавние»: палец задержали выше 110 dp — запомнили сразу (щелчок),
        // а не по последнему движению: при отрыве Android почти всегда шлёт
        // ещё одно короткое движение, и жест через раз становился «Домой»
        private boolean held;
        private float holdY;
        private final Runnable holdCheck = this::holdNow;

        private void holdNow() {
            if (active && kind == BOTTOM && !held && pull > Ui.dpf(110)) {
                held = true;
                Feel.buzz(this, Feel.TICK);
            }
        }

        /** Палец сдвинулся: заново ждать задержки; ушёл далеко — «Недавние» отменить. */
        private void trackHold(float rawY) {
            if (Math.abs(rawY - holdY) <= Ui.dpf(held ? 36 : 10)) return;
            holdY = rawY;
            held = false;
            removeCallbacks(holdCheck);
            postDelayed(holdCheck, 200);
        }
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Strip(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (kind == BOTTOM) {
                if (!Prefs.navPill && !active) return;
                // полоска-подсказка; при свайпе тянется вверх и становится ярче
                float w = Ui.dpf(active ? 140 : 118), h = Ui.dpf(4.5f);
                float cy = getHeight() - Ui.dpf(8) - Math.min(Ui.dpf(10), pull * 0.06f);
                r.set(getWidth() / 2f - w / 2, cy - h / 2, getWidth() / 2f + w / 2, cy + h / 2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(active ? 0xF2FFFFFF : 0xB3FFFFFF);
                p.setShadowLayer(Ui.dpf(3), 0, 0, 0x66000000);
                c.drawRoundRect(r, h / 2, h / 2, p);
                return;
            }
            if (!active || pull <= 0) return;
            // «назад»: капля со стрелкой выезжает от края
            float k = Ui.clamp(pull / Ui.dpf(70));
            boolean ready = pull > Ui.dpf(56);
            float cy = y0, rad = Ui.dpf(22);
            float cx = kind == LEFT ? Ui.dpf(-8) + Ui.dpf(44) * k : getWidth() + Ui.dpf(8) - Ui.dpf(44) * k;
            p.setStyle(Paint.Style.FILL);
            p.setShadowLayer(Ui.dpf(6), 0, Ui.dpf(1), 0x55000000);
            p.setColor(ready ? Ui.accentLight() : 0xE6202228);
            c.drawCircle(cx, cy, rad, p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(2.6f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFFFFFFFF);
            float a = Ui.dpf(6), dir = kind == LEFT ? 1 : -1;
            Path ar = new Path();
            ar.moveTo(cx + a * 0.5f * dir, cy - a);
            ar.lineTo(cx - a * 0.5f * dir, cy);
            ar.lineTo(cx + a * 0.5f * dir, cy + a);
            c.drawPath(ar, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getY();
                    lastY = e.getRawY();
                    downAt = lastMove = SystemClock.uptimeMillis();
                    pull = 0;
                    active = true;
                    held = false;
                    holdY = e.getRawY();
                    removeCallbacks(holdCheck);
                    if (kind == BOTTOM) {
                        y0 = e.getRawY();
                        vx = vy = 0;
                        lastT = downAt;
                        lastRawX = e.getRawX();
                        lastRawY = e.getRawY();
                        prepareShot();
                    }
                    grow(this, true);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float d;
                    if (kind == BOTTOM) d = y0 - e.getRawY();
                    else d = kind == LEFT ? e.getRawX() - x0 : x0 - e.getRawX();
                    if (Math.abs(e.getRawY() - lastY) > Ui.dpf(2)) {
                        lastMove = SystemClock.uptimeMillis();
                        lastY = e.getRawY();
                    }
                    boolean was = pull > Ui.dpf(56);
                    pull = Math.max(0, d);
                    if (kind == BOTTOM) trackHold(e.getRawY());
                    if (kind == BOTTOM) {
                        long t = SystemClock.uptimeMillis();
                        if (t > lastT) {
                            float k = Math.min(1f, (t - lastT) / 40f);
                            vx += ((e.getRawX() - lastRawX) / ((t - lastT) / 1000f) - vx) * k;
                            vy += ((e.getRawY() - lastRawY) / ((t - lastT) / 1000f) - vy) * k;
                        }
                        lastT = t;
                        lastRawX = e.getRawX();
                        lastRawY = e.getRawY();
                        // окно приложения пошло за пальцем — под ним уже стол
                        if (fly == null && shot != null && pull > Ui.dpf(28)) startFly(x0, y0);
                        if (fly != null) fly.follow(e.getRawX(), e.getRawY());
                    }
                    if (kind != BOTTOM && !was && pull > Ui.dpf(56)) Feel.buzz(this, Feel.TICK);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    long now = SystemClock.uptimeMillis();
                    // HyperOS сам перехватывает свайп у края (свои жесты, которые с чужим
                    // лаунчером ничего не делают) — нам приходит CANCEL посреди движения.
                    // Палец уже шёл вверх или внутрь — значит, жест был: выполняем сами.
                    if (!up && pull > Ui.dpf(kind == BOTTOM ? 10 : 14) && now - downAt < 1500) up = true;
                    if (kind == BOTTOM && fly != null) {
                        boolean held = this.held && pull > Ui.dpf(90);
                        // CANCEL — систему потянуло на свой жест: значит, домой
                        boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                        endFly(held, cancel || pull > Ui.dpf(60) || vy < -Ui.dpf(500), vx, vy);
                        dropShot();
                    } else if (up && kind == BOTTOM && pull > Ui.dpf(e.getActionMasked() == MotionEvent.ACTION_UP ? 36 : 10)) {
                        // задержали палец выше 110 dp — «Недавние», иначе «Домой»
                        boolean held = this.held && pull > Ui.dpf(90);
                        Feel.buzz(this, Feel.CLICK);
                        if (held && Prefs.ownRecents) RecentsActivity.open(VanksAccess.this, false);
                        else performGlobalAction(held ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_HOME);
                        dropShot();
                    } else if (up && kind != BOTTOM && pull > Ui.dpf(e.getActionMasked() == MotionEvent.ACTION_UP ? 56 : 14)) {
                        Feel.buzz(this, Feel.CLICK);
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                    active = false;
                    held = false;
                    removeCallbacks(holdCheck);
                    pull = 0;
                    grow(this, false);
                    invalidate();
                    return true;
                }
            }
            return true;
        }
    }

    // ── PIN-код на системном экране ───────────────────────────────────────

    private static String pin;
    private static long armedUntil;
    private int swipes;
    private boolean typing;

    /**
     * Набрать PIN на системном экране, когда тот появится (после
     * requestDismissKeyguard). true — служба работает и попробует.
     */
    static boolean typePin(String p) {
        VanksAccess s = on;
        if (s == null || p == null || p.isEmpty()) return false;
        pin = p;
        armedUntil = SystemClock.uptimeMillis() + 5000;
        s.swipes = 0;
        s.typing = false;
        s.ui.postDelayed(s::tryType, 250);
        s.ui.postDelayed(s::tryType, 700);
        return true;
    }

    static void disarm() {
        pin = null;
        armedUntil = 0;
    }

    // ── занавес над системным экраном блокировки ──────────────────────────

    private View cover;

    /**
     * Показать поверх всего (и системного экрана блокировки) снимок экрана
     * Vanks: пока служба набирает PIN или система узнаёт лицо, человек видит
     * свой экран, а не системный. Касания проходят сквозь. Сам уйдёт через ms.
     */
    static boolean cover(Bitmap shot, long ms) {
        VanksAccess s = on;
        if (s == null || s.wm == null || shot == null) return false;
        s.ui.post(() -> s.showCover(shot, ms));
        return true;
    }

    /** Убрать занавес: быстро растаять (или сразу, now). */
    static void uncover(boolean now) {
        VanksAccess s = on;
        if (s != null) s.ui.post(() -> s.hideCover(now));
    }

    private void showCover(Bitmap shot, long ms) {
        hideCover(true);
        View v = new View(this) {
            final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            final android.graphics.Rect dst = new android.graphics.Rect();

            @Override
            protected void onDraw(Canvas c) {
                dst.set(0, 0, getWidth(), getHeight());
                c.drawBitmap(shot, null, dst, p);
            }
        };
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.windowAnimations = 0;
        lp.setTitle("Vanks cover");
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            return;
        }
        cover = v;
        ui.postDelayed(dropCover, ms);
    }

    private final Runnable dropCover = () -> hideCover(false);

    private void hideCover(boolean now) {
        ui.removeCallbacks(dropCover);
        View v = cover;
        if (v == null) return;
        cover = null;
        Runnable gone = () -> {
            try { wm.removeView(v); } catch (Exception ignored) {}
        };
        if (now) gone.run();
        else v.animate().alpha(0).setDuration(180).withEndAction(gone).start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            // клавиатура и системная шторка «передним» приложением не считаются
            if (!p.equals("com.android.systemui") && !p.contains("inputmethod") && !p.contains(".ime")) {
                front = p;
                voiceCheck();
                // «Недавние»: вперёд вышло приложение — первым в список, и снять окно, когда оно встанет
                if (!p.equals(getPackageName()) && !HOMES.contains(p)) {
                    Recents.touch(this, p);
                    ui.removeCallbacks(snapFront);
                    ui.postDelayed(snapFront, 900);
                }
            }
        }
        if (pin != null && !typing) ui.post(this::tryType);
    }

    // ── динамический остров ───────────────────────────────────────────────

    private Island island;
    private WindowManager.LayoutParams islandLp;
    private String front = "";

    /** Снять окно переднего приложения для «Недавних» (тихо, без вспышки). */
    private final Runnable snapFront = () -> {
        String p = front;
        if (android.os.Build.VERSION.SDK_INT < 30 || fly != null || p.isEmpty() || p.equals(getPackageName()) || HOMES.contains(p)) return;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap b = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    hb.close();
                    if (b == null) return;
                    if (!p.equals(front)) {
                        b.recycle();
                        return;
                    }
                    Recents.shot(VanksAccess.this, p, b, true);
                }

                @Override
                public void onFailure(int code) {
                    // окно защищено от снимков — в «Недавних» будет значок
                }
            });
        } catch (RuntimeException ignored) {
        }
    };

    private void placeIsland() {
        if (wm == null) return;
        if (island != null) return;
        island = new Island(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("Vanks island");
        island.host = (w, h) -> {
            boolean off = w <= 0 || h <= 0;
            int f = off ? lp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            int nw = off ? 1 : w, nh = off ? 1 : h;
            if (nw == lp.width && nh == lp.height && f == lp.flags) return;
            lp.width = nw;
            lp.height = nh;
            lp.flags = f;
            try { wm.updateViewLayout(island, lp); } catch (Exception ignored) {}
        };
        try {
            wm.addView(island, lp);
            islandLp = lp;
            Island.top = island;
        } catch (Exception e) {
            island = null;
            return;
        }
        Island.refresh();
    }

    private void dropIsland() {
        if (island == null) return;
        if (Island.top == island) Island.top = null;
        try { wm.removeView(island); } catch (Exception ignored) {}
        island = null;
        islandLp = null;
    }

    // громкость, режим звонка и голосовые в Telegram (если своей медиасессии у него нет)
    private android.content.BroadcastReceiver sound;
    private android.media.AudioManager.AudioPlaybackCallback players;
    private boolean audioOn;

    private void listenSound() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        sound = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                if (!Prefs.island || !Prefs.islandVolume) return;
                if (android.media.AudioManager.RINGER_MODE_CHANGED_ACTION.equals(i.getAction())) {
                    int m = am.getRingerMode();
                    volume(m == android.media.AudioManager.RINGER_MODE_SILENT ? "Без звука"
                            : m == android.media.AudioManager.RINGER_MODE_VIBRATE ? "Вибрация" : "Звук включён", -1);
                    return;
                }
                int st = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                int v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                int was = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                if (v < 0 || v == was) return;
                String name;
                switch (st) {
                    case android.media.AudioManager.STREAM_MUSIC: name = "Мультимедиа"; break;
                    case android.media.AudioManager.STREAM_RING: name = "Звонок"; break;
                    case android.media.AudioManager.STREAM_VOICE_CALL: name = "Разговор"; break;
                    case android.media.AudioManager.STREAM_ALARM: name = "Будильник"; break;
                    default: return;
                }
                int max = Math.max(1, am.getStreamMaxVolume(st));
                volume(name, v / (float) max);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(sound, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(sound, f);
        } catch (RuntimeException e) {
            sound = null;
        }
        players = new android.media.AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<android.media.AudioPlaybackConfiguration> list) {
                audioOn = false;
                for (android.media.AudioPlaybackConfiguration a : list) {
                    if (!active(a)) continue;
                    int u = a.getAudioAttributes().getUsage();
                    if (u == android.media.AudioAttributes.USAGE_MEDIA || u == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                            || u == android.media.AudioAttributes.USAGE_UNKNOWN) audioOn = true;
                }
                voiceCheck();
            }
        };
        try { am.registerAudioPlaybackCallback(players, ui); } catch (RuntimeException ignored) { players = null; }
    }

    /** Плеер играет (метод скрыт в SDK; не вышло спросить — считаем, что играет). */
    private static boolean active(android.media.AudioPlaybackConfiguration a) {
        try {
            Object r = a.getClass().getMethod("isActive").invoke(a);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable e) {
            return true;
        }
    }

    private void stopSound() {
        if (sound != null) try { unregisterReceiver(sound); } catch (RuntimeException ignored) {}
        sound = null;
        if (players != null) try { getSystemService(android.media.AudioManager.class).unregisterAudioPlaybackCallback(players); } catch (RuntimeException ignored) {}
        players = null;
    }

    private void volume(String name, float level) {
        Island.Item it = new Island.Item("vol", Island.VOLUME);
        it.title = name;
        it.text = level >= 0 ? Math.round(level * 100) + " %" : "";
        it.progress = level >= 0 ? level : 0;
        it.until = SystemClock.uptimeMillis() + 1800;
        Island.put(it);
    }

    /** Открыт Telegram и что-то играет, а медиасессии нет — голосовое или кружок. */
    private void voiceCheck() {
        boolean tg = VanksNotes.TELEGRAM.contains(front);
        if (Prefs.island && Prefs.islandMedia && tg && audioOn && !VanksNotes.telegramSession()) {
            if (Island.get("v:tg") != null) return;
            Island.Item it = new Island.Item("v:tg", Island.VOICE);
            it.title = "Голосовое или кружок";
            it.text = "Telegram";
            it.playing = true;
            try { it.icon = getPackageManager().getApplicationIcon(front); } catch (Exception ignored) {}
            Island.put(it);
        } else {
            Island.remove("v:tg");   // и смахнутое: следующее сообщение покажется снова
        }
    }

    private void tryType() {
        if (pin == null || typing) return;
        if (SystemClock.uptimeMillis() > armedUntil) {
            disarm();
            return;
        }
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km != null && !km.isDeviceLocked()) {
            disarm();
            return;
        }
        AccessibilityNodeInfo[] keys = findKeypad();
        if (keys != null) {
            typing = true;
            String p = pin;
            disarm();   // одна попытка
            press(keys, p, 0);
            return;
        }
        AccessibilityNodeInfo field = findPasswordField();
        if (field != null) {
            typing = true;
            String p = pin;
            disarm();
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (android.os.Build.VERSION.SDK_INT >= 30)
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            typing = false;
            return;
        }
        // экран блокировки системы без клавиатуры («проведите вверх») — смахнуть один раз
        if (swipes < 1 && SystemClock.uptimeMillis() > armedUntil - 4300) {
            swipes++;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            Path path = new Path();
            path.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.82f);
            path.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.3f);
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 220)).build(), null, null);
            ui.postDelayed(this::tryType, 450);
        }
    }

    private void press(AccessibilityNodeInfo[] keys, String p, int i) {
        if (i >= p.length()) {
            // PIN набран; где нужно — «ОК»
            ui.postDelayed(() -> {
                android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
                if (km != null && km.isDeviceLocked()) {
                    AccessibilityNodeInfo ok = findEnter();
                    if (ok != null) ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                typing = false;
            }, 180);
            return;
        }
        int d = p.charAt(i) - '0';
        if (d >= 0 && d <= 9 && keys[d] != null) keys[d].performAction(AccessibilityNodeInfo.ACTION_CLICK);
        ui.postDelayed(() -> press(keys, p, i + 1), 70);
    }

    private List<AccessibilityNodeInfo> roots() {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                // свои окна (экран Vanks) не трогаем
                if (r != null && !getPackageName().contentEquals(String.valueOf(r.getPackageName()))) out.add(r);
            }
        } catch (RuntimeException ignored) {
        }
        AccessibilityNodeInfo a = getRootInActiveWindow();
        if (a != null && !getPackageName().contentEquals(String.valueOf(a.getPackageName()))) out.add(a);
        return out;
    }

    /** Цифры 0–9 PIN-клавиатуры системы — только если нашлись все десять. */
    private AccessibilityNodeInfo[] findKeypad() {
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo[] keys = new AccessibilityNodeInfo[10];
            collect(root, keys, 0);
            boolean all = true;
            for (AccessibilityNodeInfo k : keys) if (k == null) { all = false; break; }
            if (all) return keys;
        }
        return null;
    }

    private void collect(AccessibilityNodeInfo n, AccessibilityNodeInfo[] keys, int depth) {
        if (n == null || depth > 30) return;
        String t = label(n);
        if (t.length() == 1 && Character.isDigit(t.charAt(0))) {
            int d = t.charAt(0) - '0';
            AccessibilityNodeInfo c = n;
            for (int up = 0; c != null && !c.isClickable() && up < 3; up++) c = c.getParent();
            if (c != null && c.isClickable() && keys[d] == null) keys[d] = c;
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), keys, depth + 1);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    private AccessibilityNodeInfo findPasswordField() {
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo f = findField(root, 0);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findField(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isPassword() && n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findField(n.getChild(i), depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findEnter() {
        String[] names = {"ok", "ок", "enter", "ввод", "готово", "done", "подтвердить"};
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo f = findNamed(root, names, 0);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findNamed(AccessibilityNodeInfo n, String[] names, int depth) {
        if (n == null || depth > 30) return null;
        String t = label(n).toLowerCase(java.util.Locale.ROOT);
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(java.util.Locale.ROOT);
        if (n.isClickable()) {
            for (String s : names) if (t.equals(s)) return n;
            if (id.endsWith("key_enter") || id.endsWith("key_ok")) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findNamed(n.getChild(i), names, depth + 1);
            if (f != null) return f;
        }
        return null;
    }
}
