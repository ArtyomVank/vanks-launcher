package os.vanks.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.HashMap;

/**
 * Отклик: звуки Delta (res/raw/snd_*.ogg — те же мягкие тона, что на
 * компьютере) и вибрация. Звук молчит, если телефон на беззвучном или
 * вибрации. Вибрация — из «примитивов» Android 11+ (мягкие щелчки, тук,
 * нарастание), где телефон их умеет, иначе — обычный отклик кнопок.
 *     Feel.sound(ctx, "unlock");  Feel.buzz(view, Feel.CONFIRM);
 */
final class Feel {
    static final int TICK = 0, CLICK = 1, PRESS = 2, CONFIRM = 3, THUD = 4, RISE = 5;

    private static SoundPool pool;
    private static final HashMap<String, Integer> ids = new HashMap<>();
    private static final HashMap<String, Long> last = new HashMap<>();
    private static AudioManager am;
    private static Vibrator vib;
    private static Boolean primitives;

    static void init(Context c) {
        if (pool != null) return;
        Context app = c.getApplicationContext();
        am = app.getSystemService(AudioManager.class);
        vib = app.getSystemService(Vibrator.class);
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        for (String n : new String[]{"app_open", "drop", "glass_wave", "pop", "snap_in", "swoosh", "tick",
                "toggle_off", "toggle_on", "unlock", "workspace", "launcher", "island"}) {
            int res = app.getResources().getIdentifier("snd_" + n, "raw", app.getPackageName());
            if (res != 0) ids.put(n, pool.load(app, res, 1));
        }
    }

    /** Звук Delta по имени («app-open» или «app_open»). Частые повторы не трещат. */
    static void sound(Context c, String name) {
        if (!Prefs.sounds || Prefs.soundVolume <= 0) return;
        init(c);
        if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        String n = name.replace('-', '_');
        Integer id = ids.get(n);
        if (id == null) return;
        long now = SystemClock.uptimeMillis();
        Long l = last.get(n);
        if (l != null && now - l < 90) return;
        last.put(n, now);
        float v = Prefs.soundVolume;
        pool.play(id, v, v, 1, 0, 1f);
    }

    /** Вибрация-отклик. view — для запасного варианта без примитивов. */
    static void buzz(View v, int kind) {
        if (!Prefs.haptics || v == null) return;
        init(v.getContext());
        if (Build.VERSION.SDK_INT >= 30 && vib != null && hasPrimitives()) {
            VibrationEffect.Composition c = VibrationEffect.startComposition();
            switch (kind) {
                case TICK: c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f); break;
                case CLICK: c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f); break;
                case PRESS:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.4f);
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 40);
                    break;
                case CONFIRM:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f);
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 70);
                    break;
                case THUD: c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f); break;
                case RISE:
                    // нарастание и мягкий толчок в конце — разблокировка
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.5f);
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 30);
                    break;
            }
            try {
                vib.vibrate(c.compose());
                return;
            } catch (RuntimeException ignored) {
                primitives = false;
            }
        }
        int f;
        switch (kind) {
            case TICK: f = Build.VERSION.SDK_INT >= 27 ? HapticFeedbackConstants.TEXT_HANDLE_MOVE : HapticFeedbackConstants.CLOCK_TICK; break;
            case PRESS: f = HapticFeedbackConstants.LONG_PRESS; break;
            case CONFIRM:
            case RISE: f = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS; break;
            case THUD: f = HapticFeedbackConstants.LONG_PRESS; break;
            default: f = HapticFeedbackConstants.VIRTUAL_KEY;
        }
        v.performHapticFeedback(f);
    }

    private static boolean hasPrimitives() {
        if (primitives == null) {
            primitives = Build.VERSION.SDK_INT >= 30 && vib.hasVibrator() && vib.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE);
        }
        return primitives;
    }

    private Feel() {}
}
