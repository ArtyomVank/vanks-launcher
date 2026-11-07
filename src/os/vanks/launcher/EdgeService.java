package os.vanks.launcher;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Боковая панель поверх всех приложений: у края экрана — тонкая «ручка» там,
 * где жест «назад». Свайп от края остаётся жестом «назад» (его забирает
 * система), а если палец задержать у края — выезжает панель с десятью
 * приложениями, которые человек выбрал в «Настройках Vanks».
 *
 * Нужно разрешение «Поверх других приложений»; чтобы Android не выгружал
 * панель, она живёт в службе с постоянным тихим уведомлением.
 */
public final class EdgeService extends Service {
    static final int MAX = 10;
    private static final int NOTE = 7;

    private WindowManager wm;
    private Handle handle;
    private Panel panel;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Включить или выключить по настройке (вызывают «Настройки» и главный экран). */
    static void sync(Context c) {
        Intent i = new Intent(c, EdgeService.class);
        boolean on = Prefs.edge && Settings.canDrawOverlays(c);
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } else {
                c.stopService(i);
            }
        } catch (RuntimeException ignored) {
            // из фона Android запускать службу не дал — запустится с главного экрана
        }
    }

    /** Что в панели: выбранное, а пока ничего не выбрано — док и первые значки. */
    static List<Apps.App> apps() {
        ArrayList<String> keys = new ArrayList<>(Prefs.edgeApps);
        if (keys.isEmpty()) {
            keys.addAll(Prefs.dock);
            for (String k : Prefs.home) if (keys.size() < MAX && !keys.contains(k)) keys.add(k);
        }
        List<Apps.App> out = Apps.resolve(keys);
        return out.size() > MAX ? out.subList(0, MAX) : out;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.load(this);
        Ui.init(this);
        Feel.init(this);
        if (Apps.all.isEmpty()) {
            Apps.load(this);
            Apps.renderIcons(this, Math.round(Ui.dpf(52) * Prefs.iconScale));
        }
        wm = getSystemService(WindowManager.class);
        foreground();
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel("edge", "Боковая панель", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).putExtra("page", "edge").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "edge") : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_edge)
                .setContentTitle("Боковая панель Vanks")
                .setContentText("Задержите палец у края экрана")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTE, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        Prefs.load(this);
        if (!Prefs.edge || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        placeHandle();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        closePanel(false);
        if (handle != null) {
            try { wm.removeView(handle); } catch (Exception ignored) {}
            handle = null;
        }
        super.onDestroy();
    }

    // ── ручка у края ──────────────────────────────────────────────────────

    private void placeHandle() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Ui.dp(14), Ui.dp(170),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = (Prefs.edgeLeft ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
        lp.y = Math.round(dm.heightPixels * 0.3f);
        lp.setTitle("Vanks edge");
        try {
            if (handle == null) {
                handle = new Handle(this);
                wm.addView(handle, lp);
            } else {
                wm.updateViewLayout(handle, lp);
                handle.invalidate();
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    private final class Handle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int slop;
        private float x0, y0;
        private boolean fired;
        private final Runnable longPress = () -> {
            fired = true;
            Feel.buzz(this, Feel.PRESS);
            openPanel();
        };

        Handle(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        protected void onDraw(Canvas c) {
            // едва заметная полоска: где держать палец
            float w = Ui.dpf(3.5f), h = Ui.dpf(54);
            float x = Prefs.edgeLeft ? Ui.dpf(3) : getWidth() - Ui.dpf(3) - w;
            float y = (getHeight() - h) / 2f;
            p.setColor(0x59FFFFFF);
            p.setShadowLayer(Ui.dpf(2), 0, 0, 0x40000000);
            c.drawRoundRect(x, y, x + w, y + h, w / 2, w / 2, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    fired = false;
                    ui.postDelayed(longPress, 380);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // палец пошёл — это не удержание (свайп «назад» забрала система)
                    if (!fired && Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) ui.removeCallbacks(longPress);
                    return true;
                default:
                    ui.removeCallbacks(longPress);
                    return true;
            }
        }
    }

    // ── панель ────────────────────────────────────────────────────────────

    private void openPanel() {
        if (panel != null) return;
        Prefs.load(this);
        panel = new Panel(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        lp.dimAmount = 0.28f;
        if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(Ui.dp(22));
        }
        lp.setTitle("Vanks edge panel");
        try {
            wm.addView(panel, lp);
            Feel.sound(this, "snap-in");
            panel.show();
        } catch (Exception e) {
            panel = null;
        }
    }

    private void closePanel(boolean animate) {
        Panel p = panel;
        if (p == null) return;
        panel = null;
        Runnable remove = () -> {
            try { wm.removeView(p); } catch (Exception ignored) {}
        };
        if (animate) p.hide(remove); else remove.run();
    }

    private void launch(Apps.App a, RectF r, boolean window) {
        View from = panel;   // окно растёт из значка в панели (она ещё видна, пока уезжает)
        closePanel(true);
        Feel.sound(this, "app-open");
        if (window) {
            Windows.open(this, a);
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            android.os.Bundle o = r == null ? null : ActivityOptions.makeScaleUpAnimation(from != null ? from : handle,
                    Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height())).toBundle();
            startActivity(i, o);
        } catch (Exception e) {
            try { startActivity(i); } catch (Exception ignored) {}
        }
    }

    /** Окно панели: затемнённый экран, у края — карточка с приложениями. */
    private final class Panel extends FrameLayout {
        private final LinearLayout card;
        private final AppGrid grid;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Panel(Context c) {
            super(c);
            setFocusableInTouchMode(true);
            setOnClickListener(v -> closePanel(true));
            card = new LinearLayout(c) {
                @Override
                protected void onDraw(Canvas cv) {
                    r.set(0, 0, getWidth(), getHeight());
                    float rad = Math.min(Look.radius(22), getWidth() / 2f);
                    int s = Look.surface();
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Ui.alpha(s, Look.is("nothing") || Look.is("gnome") || Look.is("material") ? 0.94f : 0.72f));
                    cv.drawRoundRect(r, rad, rad, p);
                    if (Look.is("acrylic")) Look.contour(cv, r, rad);
                    else {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(Ui.dpf(1));
                        p.setColor(Look.light() ? 0x26000000 : 0x33FFFFFF);
                        cv.drawRoundRect(r, rad, rad, p);
                    }
                }
            };
            card.setWillNotDraw(false);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), Ui.dp(10));
            card.setClickable(true);

            // шапка: «в окне» и настройки
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Ui.dp(10), 0, Ui.dp(4), Ui.dp(4));
            head.addView(Views.label(c, "Панель", 15, Ui.medium, Look.fg()), new LinearLayout.LayoutParams(0, -2, 1));
            View win = Views.drawn(c, (cv, w, h) -> {
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (Prefs.edgeWindow) {
                    q.setColor(Ui.accent());
                    cv.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, q);
                }
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(Ui.dpf(1.6f));
                q.setColor(Prefs.edgeWindow ? 0xFFFFFFFF : Look.fg());
                float s = Ui.dpf(7);
                cv.drawRoundRect(w / 2 - s, h / 2 - s * 0.8f, w / 2 + s, h / 2 + s * 0.8f, Ui.dpf(2.5f), Ui.dpf(2.5f), q);
                cv.drawLine(w / 2 - s, h / 2 - s * 0.35f, w / 2 + s, h / 2 - s * 0.35f, q);
            });
            win.setOnClickListener(v -> {
                Prefs.edgeWindow = !Prefs.edgeWindow;
                Prefs.save();
                Feel.buzz(v, Feel.TICK);
                Feel.sound(getContext(), Prefs.edgeWindow ? "toggle-on" : "toggle-off");
                v.invalidate();
            });
            head.addView(win, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
            View gear = Views.drawn(c, (cv, w, h) -> Glyph.gear(cv, w / 2, h / 2, Ui.dpf(18), Look.fg2()));
            gear.setOnClickListener(v -> {
                closePanel(true);
                try {
                    startActivity(new Intent(EdgeService.this, SettingsActivity.class).putExtra("page", "edge")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (Exception ignored) {}
            });
            head.addView(gear, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
            card.addView(head);

            grid = new AppGrid(c);
            grid.cols = 2;
            grid.labels = true;
            grid.setLabelColor(Look.light() ? 0xFF16171D : 0xFFF4F6FF);
            grid.setApps(apps());
            grid.listener = new AppGrid.Listener() {
                @Override
                public void tap(Apps.App a, AppGrid g, RectF rr) {
                    if (a != null) launch(a, rr, Prefs.edgeWindow);
                }

                @Override
                public void longPress(Apps.App a, AppGrid g, RectF rr) {
                    // удержание — наоборот: в окне, если обычно во весь экран, и наоборот
                    if (a != null) launch(a, rr, !Prefs.edgeWindow);
                }
            };
            card.addView(grid, new LinearLayout.LayoutParams(-1, -2));

            int cw = Math.round(2 * (Apps.iconPx + Ui.dpf(34)) + Ui.dpf(12));
            LayoutParams lp = new LayoutParams(cw, -2, (Prefs.edgeLeft ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
            lp.leftMargin = lp.rightMargin = Ui.dp(10);
            addView(card, lp);
        }

        void show() {
            float from = (Prefs.edgeLeft ? -1 : 1) * Ui.dpf(260);
            card.setTranslationX(from);
            card.setAlpha(0);
            Anim.spring(from, 0, 0, 320, 0.82f, card::setTranslationX, null);
            card.animate().alpha(1).setDuration(180).start();
            grid.playEnter();
            requestFocus();
        }

        void hide(Runnable done) {
            float to = (Prefs.edgeLeft ? -1 : 1) * Ui.dpf(260);
            card.animate().translationX(to).alpha(0).setDuration(220).setInterpolator(Anim.EMPH).withEndAction(done).start();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) closePanel(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
