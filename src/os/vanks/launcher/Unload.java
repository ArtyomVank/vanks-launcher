package os.vanks.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Выгрузка приложений из памяти («Недавние»). В Vanks OS лаунчер системный и
 * останавливает их сам; на обычном телефоне Android такого не разрешает — там
 * крестик нажимает «Остановить» в сведениях о приложении через службу
 * специальных возможностей, а смахивание убирает только фоновые процессы.
 */
final class Unload {
    private static final String FORCE = "android.permission.FORCE_STOP_PACKAGES";

    private static boolean canForce(Context c) {
        return c.checkSelfPermission(FORCE) == PackageManager.PERMISSION_GRANTED;
    }

    /** forceStopPackage скрыт из SDK, но системному приложению доступен. */
    private static boolean force(ActivityManager am, String pkg) {
        try {
            ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, pkg);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void background(ActivityManager am, String pkg) {
        try {
            am.killBackgroundProcesses(pkg);
        } catch (RuntimeException ignored) {
        }
    }

    /** Смахнули одну карточку: остаёмся в «Недавних». */
    static void one(Context c, String pkg) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (!canForce(c) || !force(am, pkg)) background(am, pkg);
    }

    /** Крестик: выгрузить всё. true — служба работает поверх и сама вернётся на стол. */
    static boolean all(Context c, List<String> pkgs) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ArrayList<String> rest = new ArrayList<>(pkgs);
        rest.remove(c.getPackageName());
        if (canForce(c)) {
            new Thread(() -> {
                for (String p : rest) if (!force(am, p)) background(am, p);
            }, "unload").start();
            return false;
        }
        for (String p : rest) background(am, p);
        return VanksAccess.forceStop(rest);
    }

    private Unload() {}
}
