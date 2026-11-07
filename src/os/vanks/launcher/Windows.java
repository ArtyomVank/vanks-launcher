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

    static boolean supported(Context c) {
        if (c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true;
        ContentResolver r = c.getContentResolver();
        return Settings.Global.getInt(r, "enable_freeform_support", 0) != 0
                || Settings.Global.getInt(r, "force_resizable_activities", 0) != 0;
    }

    /** Открыть приложение в окне; не умеет телефон — как обычно, с подсказкой. */
    static void open(Context c, Apps.App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Bundle opts = null;
        if (supported(c)) {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int w = Math.round(dm.widthPixels * 0.84f), h = Math.round(dm.heightPixels * 0.56f);
            int x = (dm.widthPixels - w) / 2, y = Math.round(dm.heightPixels * 0.16f);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchBounds(new Rect(x, y, x + w, y + h));
            try {
                // режим «окно» (5 — WINDOWING_MODE_FREEFORM); на новых Android скрыт — тогда хватает границ
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, 5);
            } catch (Throwable ignored) {
            }
            opts = o.toBundle();
        } else {
            Toast.makeText(c, "Плавающие окна выключены. " + HOW, Toast.LENGTH_LONG).show();
            i.removeFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        try {
            c.startActivity(i, opts);
        } catch (Exception e) {
            Toast.makeText(c, "Не удалось открыть", Toast.LENGTH_SHORT).show();
        }
    }

    private Windows() {}
}
