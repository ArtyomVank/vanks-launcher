package os.vanks.launcher;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Обои Vanks — и в систему. Под окнами Vanks бывают места, где Android
 * показывает свои обои: системный экран блокировки под нашим, анимации
 * запуска и возврата, «Недавние». Чтобы там не мелькали чужие обои, ставим
 * готовый кадр обоев Vanks и системе (главный и экран блокировки).
 */
final class SysWall {
    static void sync(Context c) {
        if (!Prefs.syncSystemWall) return;
        Context app = c.getApplicationContext();
        String home = Prefs.wallpaper, lock = Prefs.lockWallpaper.isEmpty() ? Prefs.wallpaper : Prefs.lockWallpaper;
        if (home.equals(Prefs.sysWallHome) && lock.equals(Prefs.sysWallLock)) return;
        new Thread(() -> {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(app);
                if (!wm.isSetWallpaperAllowed()) return;
                set(app, wm, Walls.find(home), WallpaperManager.FLAG_SYSTEM);
                set(app, wm, Walls.find(lock), WallpaperManager.FLAG_LOCK);
                Prefs.sysWallHome = home;
                Prefs.sysWallLock = lock;
                Prefs.save();
            } catch (Exception ignored) {
                // прошивка не дала — останутся её обои
            }
        }, "sys-wall").start();
    }

    private static void set(Context c, WallpaperManager wm, Walls.W w, int which) throws java.io.IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        Bitmap b = Walls.decode(c, w.still, o);
        if (b != null) wm.setBitmap(b, null, true, which);
    }

    private SysWall() {}
}
