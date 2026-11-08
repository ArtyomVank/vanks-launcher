package os.vanks.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * «Всегда на экране» (AOD): погас экран — служба LockService открывает это
 * окно, и на чёрном видны часы, дата, заряд и значки уведомлений.
 *
 * Настоящий AOD держит экран в особом режиме прошивки, приложению он не
 * доступен. Поэтому экран остаётся включённым, но тратит мало: самая низкая
 * яркость и частота экрана, кадр перерисовывается раз в минуту, без
 * анимаций. Каждую минуту картинка сдвигается на несколько точек, чтобы не
 * выгорал экран. В кармане и на почти пустой батарее AOD не горит.
 *
 * «10 секунд» — показать и погасить экран (это делает служба «Vanks: жесты
 * и вход»), касание — ещё 10 секунд. «Всегда» — пока экран не включат.
 * Двойное касание или свайп вверх — открыть экран блокировки.
 */
public final class AodActivity extends Activity {
    static final String[] MODES = {"off", "10s", "always"};
    static final String[] MODE_NAMES = {"Выкл.", "10 секунд", "Всегда"};
    static final String[] STYLES = {"big", "dim"};
    static final String[] STYLE_NAMES = {"Крупные цифры", "Приглушённый экран"};
    private static final long SHOW_MS = 10_000, POCKET_MS = 2_500;

    /** Окно AOD сейчас открыто. */
    static boolean active;
    /** Экран гасим мы сами — показывать AOD снова не нужно. */
    private static boolean selfOff;
    private static AodActivity current;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Face face;
    private BroadcastReceiver tick, battery;
    private SensorManager sensors;
    private SensorEventListener pocket;
    private boolean preview, closing;

    static boolean enabled() {
        return !"off".equals(Prefs.aodMode);
    }

    /**
     * Стоит ли сейчас гореть: не во время звонка (экран гасит датчик у уха),
     * не в экономии энергии и не на последних 10 % — если не на зарядке.
     */
    static boolean worth(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null && (am.getMode() == AudioManager.MODE_IN_CALL || am.getMode() == AudioManager.MODE_IN_COMMUNICATION)) return false;
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (charging) return true;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && pm.isPowerSaveMode()) return false;
        return level(b) > 10;
    }

    private static int level(Intent b) {
        if (b == null) return 100;
        int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return l < 0 ? 100 : Math.round(100f * l / Math.max(1, s));
    }

    /** Открыть AOD (preview — из настроек: без таймера и без гашения экрана). */
    static void show(Context c, boolean preview) {
        Intent i = new Intent(c, AodActivity.class).putExtra("preview", preview)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {
            // из фона без «поверх других окон» Android не пустит
        }
    }

    /**
     * Экран погас (служба спрашивает, прежде чем открыть экран блокировки).
     * true — это касается AOD, больше ничего делать не нужно: погасили мы
     * сами — окно уходит; нажали кнопку питания, пока горел AOD, — значит,
     * хотят включить: экран включается на экране блокировки.
     */
    static boolean screenOff(Context c) {
        AodActivity a = current;
        if (selfOff) {
            selfOff = false;
            if (a != null) a.close(false);
            return true;
        }
        if (a == null) return false;
        if (a.preview) {
            a.close(false);
            return false;
        }
        // окно поднимается снова — Android включает экран (setTurnScreenOn), и оно уходит
        c.startActivity(new Intent(c, AodActivity.class).putExtra("wake", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        return true;
    }

    /** Экран открыли (палец, PIN) — AOD больше не нужен. */
    static void dismiss() {
        if (current != null) current.close(false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Ui.init(this);
        Ui.loadLockWall(this);
        current = this;
        active = true;
        preview = getIntent().getBooleanExtra("preview", false);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // самая низкая яркость, но не 0: 0 у Android — «выключить подсветку»
        lp.screenBrightness = 0.01f;
        lp.buttonBrightness = 0f;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        slowest();

        face = new Face(this);
        setContentView(face);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.systemBars());
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            face.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        face.setOnApplyWindowInsetsListener((v, in) -> {
            face.insetTop = Build.VERSION.SDK_INT >= 30
                    ? in.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top
                    : in.getSystemWindowInsetTop();
            return in;
        });

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (preview) close(true);
                else restart();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                close(true);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (vy < -Ui.dpf(600) && Math.abs(vy) > Math.abs(vx)) close(true);
                return true;
            }
        });
        face.setOnTouchListener((v, e) -> gd.onTouchEvent(e));

        // раз в минуту: время, значки, сдвиг от выгорания (TIME_TICK приходит, только пока экран горит)
        tick = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                face.refresh();
            }
        };
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        battery = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int l = level(i);
                boolean ch = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                if (l != face.level || ch != face.charging) {
                    face.level = l;
                    face.charging = ch;
                    face.invalidate();
                }
            }
        };
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        watchPocket();
        face.refresh();
        restart();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.getBooleanExtra("wake", false)) {
            close(false);
            return;
        }
        preview = i.getBooleanExtra("preview", false);
        restart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getIntent().getBooleanExtra("wake", false)) close(false);
    }

    /** Самая низкая частота экрана в том же разрешении — кадр всё равно раз в минуту. */
    @SuppressWarnings("deprecation")
    private void slowest() {
        Display d = getWindowManager().getDefaultDisplay();
        if (d == null) return;
        Display.Mode cur = d.getMode(), low = null;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (low == null || m.getRefreshRate() < low.getRefreshRate()) low = m;
        }
        if (low == null) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = low.getModeId();
        lp.preferredRefreshRate = low.getRefreshRate();
        getWindow().setAttributes(lp);
    }

    /** В кармане или экраном вниз (датчик приближения закрыт) — погасить экран. */
    private void watchPocket() {
        sensors = getSystemService(SensorManager.class);
        Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (s == null) return;
        float far = s.getMaximumRange();
        pocket = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                boolean near = e.values.length > 0 && e.values[0] < Math.min(far, 3f);
                ui.removeCallbacks(pocketOff);
                if (near && !preview) ui.postDelayed(pocketOff, POCKET_MS);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        sensors.registerListener(pocket, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private final Runnable pocketOff = this::sleep;
    private final Runnable timeUp = this::sleep;

    /** «10 секунд» — отсчёт заново (касание по AOD). */
    private void restart() {
        ui.removeCallbacks(timeUp);
        selfOff = false;
        if (face.dark) {
            // экран ждал тайм-аута системы — снова светится
            face.dark = false;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            face.invalidate();
        }
        if (!preview && "10s".equals(Prefs.aodMode)) ui.postDelayed(timeUp, SHOW_MS);
    }

    /**
     * Погасить экран. Сразу — через службу «Vanks: жесты и вход» (как кнопка
     * питания). Без неё — чёрный кадр, и экран гаснет по тайм-ауту Android.
     */
    private void sleep() {
        if (closing) return;
        selfOff = true;
        VanksAccess a = VanksAccess.on;
        if (a != null && a.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        face.dark = true;
        face.invalidate();
    }

    /** Уйти: под окном — экран блокировки (свой или системный). */
    private void close(boolean fade) {
        if (closing) return;
        closing = true;
        ui.removeCallbacksAndMessages(null);
        Runnable done = () -> {
            finish();
            overridePendingTransition(0, 0);
        };
        if (fade) face.animate().alpha(0).setDuration(180).withEndAction(done).start();
        else done.run();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        for (BroadcastReceiver r : new BroadcastReceiver[]{tick, battery}) {
            try {
                if (r != null) unregisterReceiver(r);
            } catch (RuntimeException ignored) {
            }
        }
        if (sensors != null && pocket != null) sensors.unregisterListener(pocket);
        if (current == this) {
            current = null;
            active = false;
            // окна нет — следующее выключение экрана обычное (с экраном блокировки)
            selfOff = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // назад на AOD ничего не делает — как и на экране блокировки
    }

    /** Что нарисовано: вид «Крупные цифры» или «Приглушённый экран». */
    static final class Face extends View {
        int insetTop, level = 100;
        boolean charging, dark;
        private final ArrayList<Drawable> icons = new ArrayList<>();
        private float ox, oy;
        private int shift;
        private Bitmap wall;
        private String wallKey = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();

        Face(Context c) {
            super(c);
            setBackgroundColor(0xFF000000);
        }

        /** Раз в минуту: сдвиг (9 мест по кругу, до 6 dp) и значки уведомлений. */
        void refresh() {
            shift = (shift + 1) % 9;
            float step = Ui.dpf(3);
            ox = (shift % 3 - 1) * step * 2;
            oy = (shift / 3 - 1) * step * 2;
            icons.clear();
            VanksNotes n = VanksNotes.on;
            if (n != null) {
                HashSet<String> seen = new HashSet<>();
                try {
                    for (StatusBarNotification s : n.getActiveNotifications()) {
                        if (icons.size() >= 6) break;
                        if (s.isOngoing() || !s.isClearable() || s.getPackageName().equals(getContext().getPackageName())) continue;
                        if (!seen.add(s.getPackageName()) || s.getNotification().getSmallIcon() == null) continue;
                        Drawable d = s.getNotification().getSmallIcon().loadDrawable(getContext());   // значок чужого пакета Icon находит сам
                        if (d != null) icons.add(d.mutate());
                    }
                } catch (RuntimeException ignored) {
                    // служба уведомлений отключилась — без значков
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (dark) return;
            int w = getWidth(), h = getHeight();
            if ("dim".equals(Prefs.aodStyle)) dim(c, w, h);
            else big(c, w, h);
        }

        /** Крупные цифры: часы над минутами во весь рост, в цвете акцента; ниже дата, заряд, значки. */
        private void big(Canvas c, int w, int h) {
            c.save();
            c.translate(ox, oy);
            String[] hm = ClockView.timeText(getContext()).split(":");
            float x = Ui.dpf(34), size = Math.min(w * 0.42f, Ui.dpf(150));
            Typeface tf = Typeface.create(Ui.bold, 800, false);
            p.setTypeface(tf);
            p.setTextSize(size);
            p.setTextAlign(Paint.Align.LEFT);
            p.setLetterSpacing(-0.04f);
            Paint.FontMetrics fm = p.getFontMetrics();
            float cap = -fm.ascent * 0.72f;   // высота цифры
            float top = insetTop + h * 0.16f, gap = size * 0.08f;
            float y1 = top + cap, y2 = y1 + gap + cap;
            int acc = Ui.accent(), warm = UpdateArt.warm();
            // часы — белые, тающие в акцент; минуты — акцент, тающий в тёплый
            p.setShader(new LinearGradient(0, top, 0, y1, 0xFFFFFFFF, Ui.mix(Ui.accentLight(), 0xFFFFFFFF, 0.35f), Shader.TileMode.CLAMP));
            c.drawText(hm[0], x, y1, p);
            p.setShader(new LinearGradient(0, y1 + gap, w * 0.6f, y2, Ui.accentLight(), Ui.mix(acc, warm, 0.45f), Shader.TileMode.CLAMP));
            c.drawText(hm.length > 1 ? hm[1] : "", x, y2, p);
            p.setShader(null);
            p.setLetterSpacing(0);

            // тонкая черта акцента и дата
            float y = y2 + Ui.dpf(30);
            p.setColor(Ui.alpha(acc, 0.9f));
            c.drawRoundRect(x + Ui.dpf(4), y, x + Ui.dpf(34), y + Ui.dpf(3), Ui.dpf(2), Ui.dpf(2), p);
            y += Ui.dpf(30);
            Paint t = Ui.text(Ui.medium, 18, 0xE6FFFFFF);
            c.drawText(ClockView.dateText(), x + Ui.dpf(4), y, t);
            y += Ui.dpf(28);
            battery(c, x + Ui.dpf(4), y, Paint.Align.LEFT);
            String al = ClockView.nextAlarm(getContext());
            if (al != null) {
                y += Ui.dpf(26);
                c.drawText("Будильник · " + al, x + Ui.dpf(4), y, Ui.text(Ui.regular, 14, 0x99FFFFFF));
            }
            icons(c, x + Ui.dpf(4), y + Ui.dpf(22), false);
            c.restore();
        }

        /** Приглушённый экран: обои экрана блокировки в полутьме, те же часы и виджеты, значки внизу. */
        private void dim(Canvas c, int w, int h) {
            Bitmap src = Ui.ownLockWall() && Ui.lockWall != null ? Ui.lockWall : Ui.wall;
            String key = w + "x" + h + "/" + Prefs.lockWallpaper + "/" + Prefs.wallpaper + "/" + (src == null ? 0 : src.getGenerationId());
            if (src != null && (wall == null || !key.equals(wallKey))) {
                // темнее и бледнее — один раз в картинку, дальше только копируется
                if (wall != null) wall.recycle();
                wall = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
                Canvas wc = new Canvas(wall);
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0.75f);
                ColorMatrix dark = new ColorMatrix();
                dark.setScale(0.3f, 0.3f, 0.3f, 1f);
                cm.postConcat(dark);
                Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
                bp.setColorFilter(new ColorMatrixColorFilter(cm));
                wc.drawBitmap(src, Ui.wallMatrix(src, w, h, 1f, m), bp);
                // сверху и снизу ещё темнее — там текст
                bp.setColorFilter(null);
                bp.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x99000000, 0x00000000, 0x00000000, 0xB3000000},
                        new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                wc.drawRect(0, 0, w, h, bp);
                wallKey = key;
            }
            if (wall != null) c.drawBitmap(wall, 0, 0, null);
            c.save();
            c.translate(ox, oy);
            int sc = c.saveLayerAlpha(0, 0, w, h, 215);
            LockFace.drawClock(c, getContext(), w, h, insetTop);
            LockFace.drawWidgets(c, getContext(), w, h, insetTop);
            c.restoreToCount(sc);
            float bottom = h - Ui.dpf(64);
            icons(c, w / 2f, bottom - Ui.dpf(40), true);
            battery(c, w / 2f, bottom, Paint.Align.CENTER);
            c.restore();
        }

        /** Заряд: маленькая батарейка и проценты; на зарядке — «Заряжается». */
        private void battery(Canvas c, float x, float base, Paint.Align al) {
            Paint t = Ui.text(Ui.medium, 14, 0xB3FFFFFF);
            String s = charging ? "Заряжается · " + level + " %" : level + " %";
            float bw = Ui.dpf(22), bh = Ui.dpf(11), gap = Ui.dpf(8);
            float tw = t.measureText(s), full = bw + gap + tw;
            float left = al == Paint.Align.CENTER ? x - full / 2 : x;
            RectF r = new RectF(left, base - bh - Ui.dpf(1), left + bw, base - Ui.dpf(1));
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1.2f));
            p.setColor(0x99FFFFFF);
            c.drawRoundRect(r, Ui.dpf(3), Ui.dpf(3), p);
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r.right + Ui.dpf(1), r.centerY() - Ui.dpf(2), r.right + Ui.dpf(2.6f), r.centerY() + Ui.dpf(2), Ui.dpf(1), Ui.dpf(1), p);
            int col = charging ? 0xFF4ADE80 : level <= 20 ? 0xFFFF5A5A : 0xCCFFFFFF;
            p.setColor(col);
            float in = Ui.dpf(2.2f);
            c.drawRoundRect(r.left + in, r.top + in, r.left + in + (r.width() - in * 2) * Ui.clamp(level / 100f), r.bottom - in, Ui.dpf(1.5f), Ui.dpf(1.5f), p);
            t.setTextAlign(Paint.Align.LEFT);
            c.drawText(s, left + bw + gap, base, t);
        }

        /** Значки приложений с уведомлениями — серые, в ряд. */
        private void icons(Canvas c, float x, float top, boolean center) {
            if (icons.isEmpty()) return;
            int s = Ui.dp(18), gap = Ui.dp(14);
            float full = icons.size() * s + (icons.size() - 1) * gap;
            float left = center ? x - full / 2 : x;
            for (int i = 0; i < icons.size(); i++) {
                Drawable d = icons.get(i);
                int l = Math.round(left + i * (s + gap)), t = Math.round(top);
                d.setBounds(l, t, l + s, t + s);
                d.setTint(0xB3FFFFFF);
                d.draw(c);
            }
        }
    }
}
