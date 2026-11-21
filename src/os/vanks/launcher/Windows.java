package os.vanks.launcher;

import android.app.ActivityOptions;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.Toast;

/**
 * Плавающие окна: приложение открывается в окне посреди экрана. Так умеет
 * сам Android, если на телефоне включены окна произвольной формы (у части
 * прошивок — всегда, у остальных — в «Для разработчиков»). Свои плавающие
 * окна прошивок (ColorOS, HyperOS, One UI) сторонним лаунчерам недоступны.
 */
final class Windows {
    static final String HOW = "Включите в «Для разработчиков»: «Окна произвольной формы» "
            + "и «Изменение размера в многооконном режиме», затем перезагрузите телефон";

    /**
     * Vanks OS (и телефон, где выдано WRITE_SECURE_SETTINGS): включить окна
     * самим — Android подхватывает настройку без перезагрузки.
     */
    static void enable(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver r = c.getContentResolver();
        try {
            if (Settings.Global.getInt(r, "enable_freeform_support", 0) == 0)
                Settings.Global.putInt(r, "enable_freeform_support", 1);
            if (Settings.Global.getInt(r, "force_resizable_activities", 0) == 0)
                Settings.Global.putInt(r, "force_resizable_activities", 1);
        } catch (RuntimeException ignored) {
        }
    }

    static boolean supported(Context c) {
        enable(c);
        if (c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true;
        ContentResolver r = c.getContentResolver();
        return Settings.Global.getInt(r, "enable_freeform_support", 0) != 0
                || Settings.Global.getInt(r, "force_resizable_activities", 0) != 0;
    }

    // ключ режима окна в параметрах запуска (ActivityOptions.KEY_LAUNCH_WINDOWING_MODE):
    // сам метод setLaunchWindowingMode скрыт от приложений, а параметры система
    // читает из Bundle — так окно открывается и на новых Android
    private static final String KEY_MODE = "android.activity.windowingMode";
    private static final int FREEFORM = 5, FULLSCREEN = 1;

    private static Bundle mode(ActivityOptions o, int mode) {
        try {
            ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, mode);
        } catch (Throwable ignored) {
        }
        Bundle b = o.toBundle();
        b.putInt(KEY_MODE, mode);
        return b;
    }

    /**
     * Открыть приложение в окне; не умеет телефон — как обычно, с подсказкой.
     * Без FLAG_ACTIVITY_MULTIPLE_TASK: свёрнутое окно этого приложения
     * возвращается на место, а не открывается второе.
     */
    static void open(Context c, Apps.App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // окно просим всегда: часть прошивок (HyperOS) умеет окна, не говоря об этом
        // в настройках; не умеет — Android сам откроет приложение как обычно
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = Math.round(dm.widthPixels * 0.86f), h = Math.round(dm.heightPixels * 0.58f);
        int x = (dm.widthPixels - w) / 2, y = Math.round(dm.heightPixels * 0.17f);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(new Rect(x, y, x + w, y + h));
        Bundle opts = mode(o, FREEFORM);
        if (!supported(c)) Toast.makeText(c, "Если окно не открылось: " + HOW, Toast.LENGTH_LONG).show();
        try {
            c.startActivity(i, opts);
        } catch (Exception e) {
            Toast.makeText(c, "Не удалось открыть", Toast.LENGTH_SHORT).show();
        }
    }

    /** Окно — на весь экран (кнопка на рамке, если у окна Android своей нет). */
    static void fullscreen(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i, mode(ActivityOptions.makeBasic(), FULLSCREEN));
        } catch (Exception ignored) {
        }
    }

    private Windows() {}
}
