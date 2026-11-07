package os.vanks.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Жесты Android вместо трёх кнопок. HyperOS и MIUI сами ставят кнопки, когда
 * лаунчер по умолчанию не их, — жесты возвращает их же настройка
 * force_fsg_nav_bar. Писать её можно только с разрешением
 * WRITE_SECURE_SETTINGS, которое выдаётся один раз с компьютера (ADB):
 * обычному приложению Android его не даёт.
 */
final class Nav {
    static final String GRANT = "adb shell pm grant os.vanks.launcher android.permission.WRITE_SECURE_SETTINGS";
    static final String DIRECT = "adb shell settings put global force_fsg_nav_bar 1";

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Сейчас жесты (а не кнопки). */
    static boolean gestures(Context c) {
        ContentResolver r = c.getContentResolver();
        if (Settings.Global.getInt(r, "force_fsg_nav_bar", 0) == 1) return true;
        return Settings.Secure.getInt(r, "navigation_mode", 0) == 2;
    }

    /** Включить жесты, если можно. true — получилось (или уже были). */
    static boolean keep(Context c) {
        if (!Prefs.keepGestures || !canWrite(c)) return false;
        try {
            ContentResolver r = c.getContentResolver();
            if (Settings.Global.getInt(r, "force_fsg_nav_bar", 0) != 1) Settings.Global.putInt(r, "force_fsg_nav_bar", 1);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Вернуть как решит система (кнопки на HyperOS). */
    static void release(Context c) {
        if (!canWrite(c)) return;
        try {
            Settings.Global.putInt(c.getContentResolver(), "force_fsg_nav_bar", 0);
        } catch (RuntimeException ignored) {
        }
    }

    private Nav() {}
}
