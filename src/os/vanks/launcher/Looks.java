package os.vanks.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Готовые виды — как «Пресеты» в Delta на компьютере (wallpaper/Looks.qml):
 * обои на оба экрана и часы экрана блокировки одним нажатием. Прежние обои
 * и часы запоминаются один раз до первого вида подряд — «Вернуть как было»
 * ставит их обратно.
 */
final class Looks {
    static final class Look {
        final String id, title, note, wall, clock, tint, kind;

        Look(String id, String title, String note, String wall, String clock, String tint, String kind) {
            this.id = id;
            this.title = title;
            this.note = note;
            this.wall = wall;
            this.clock = clock;
            this.tint = tint;
            this.kind = kind;
        }
    }

    /** Те же виды, что в Delta: обои — живые обои телефона с тем же рисунком. */
    static final Look[] LIST = {
            new Look("sunset-glass", "Закат", "«Длинные» часы стеклом на «Акриле»", "acrylic-sunset", "long", "white", "mixed"),
            new Look("dots", "Точки", "Часы из точек на чёрном", "dots-black", "dots", "white", null),
            new Look("peak-glass", "Вершина", "Стеклянные часы над горой", "peak-live", "ios", "glass", null),
            new Look("silk-stack", "Шёлк", "Часы стопкой цветом акцента", "waves-silk", "stack", "accent", null),
    };

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("looks", 0);
    }

    static String current(Context c) {
        return sp(c).getString("current", "");
    }

    static boolean canUndo(Context c) {
        return sp(c).contains("wall");
    }

    static void apply(Context c, Look l) {
        if (Walls.find(l.wall) == null) return;
        SharedPreferences.Editor e = sp(c).edit();
        // что было — один раз, до первого вида подряд
        if (!canUndo(c)) {
            e.putString("wall", Prefs.wallpaper).putString("lockWall", Prefs.lockWallpaper)
                    .putString("clock", Prefs.lockClock).putString("tint", Prefs.lockClockColor)
                    .putString("kind", Prefs.posterKind);
        }
        e.putString("current", l.id).apply();
        Prefs.wallpaper = l.wall;
        Prefs.lockWallpaper = "";
        Prefs.lockClock = l.clock;
        Prefs.lockClockColor = l.tint;
        if (l.kind != null) Prefs.posterKind = l.kind;
        done(c);
    }

    static void undo(Context c) {
        SharedPreferences s = sp(c);
        if (!canUndo(c)) return;
        Prefs.wallpaper = s.getString("wall", Prefs.wallpaper);
        Prefs.lockWallpaper = s.getString("lockWall", "");
        Prefs.lockClock = s.getString("clock", "");
        Prefs.lockClockColor = s.getString("tint", "white");
        Prefs.posterKind = s.getString("kind", "glass");
        s.edit().clear().apply();
        done(c);
    }

    private static void done(Context c) {
        Prefs.save();
        Ui.loadWall(c);
        Ui.loadLockWall(c);
        SysWall.sync(c);
    }

    private Looks() {}
}
