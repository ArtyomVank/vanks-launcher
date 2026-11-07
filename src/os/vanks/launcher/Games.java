package os.vanks.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Игры Windows. Сами их Android не запускает — это делает эмулятор
 * (Winlator, GameHub и другие: Wine переводит Windows в Android, Box64 —
 * команды x86 в ARM). В эмуляторе у игры есть «Добавить на главный экран»:
 * ярлык приходит главному экрану, то есть Vanks (PinActivity), и Vanks
 * собирает такие ярлыки в одну библиотеку — «Игры Vanks».
 */
final class Games {
    /** Известные эмуляторы: пакет и имя. Сборки Winlator узнаются и по «winlator» в пакете. */
    private static final String[][] KNOWN = {
            {"com.winlator", "Winlator"},
            {"com.winlator.cmod", "Winlator CMod"},
            {"app.gamenative", "GameNative"},
            {"com.xiaoji.egggame", "GameHub"},
            {"com.OxGames.Pluvia", "Pluvia"},
            {"com.eltechs.ed", "ExaGear"},
    };

    static final String WINLATOR_PAGE = "https://github.com/brunodev85/winlator/releases";

    private Games() {}

    static boolean isEmulator(String pkg) {
        if (pkg == null) return false;
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return true;
        return pkg.toLowerCase(Locale.ROOT).contains("winlator");
    }

    /** Имя эмулятора для подписи под игрой. */
    static String emulatorName(String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return k[1];
        for (Apps.App a : Apps.all) if (a.cn.getPackageName().equals(pkg)) return a.label;
        return pkg;
    }

    /** Установленные эмуляторы (по одному значку на пакет). */
    static List<Apps.App> emulators(Context c) {
        if (Apps.all.isEmpty()) Apps.load(c);
        ArrayList<Apps.App> out = new ArrayList<>();
        ArrayList<String> seen = new ArrayList<>();
        for (Apps.App a : Apps.all) {
            String p = a.cn.getPackageName();
            if (isEmulator(p) && !seen.contains(p)) {
                seen.add(p);
                out.add(a);
            }
        }
        return out;
    }

    /** Ярлыки видны, только если Vanks — главный экран (так решил Android). */
    static boolean host(Context c) {
        LauncherApps la = c.getSystemService(LauncherApps.class);
        try {
            return la != null && la.hasShortcutHostPermission();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Все закреплённые ярлыки: games — из эмуляторов, other — остальные. Свежие игры — первыми. */
    static void pinned(Context c, List<ShortcutInfo> games, List<ShortcutInfo> other) {
        if (!host(c)) return;
        LauncherApps la = c.getSystemService(LauncherApps.class);
        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
        q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
        List<ShortcutInfo> all;
        try {
            all = la.getShortcuts(q, Process.myUserHandle());
        } catch (RuntimeException e) {
            return;
        }
        if (all == null) return;
        for (ShortcutInfo si : all) (isEmulator(si.getPackage()) ? games : other).add(si);
        SharedPreferences sp = prefs(c);
        games.sort((a, b) -> Long.compare(sp.getLong(key(b), 0), sp.getLong(key(a), 0)));
    }

    static String key(ShortcutInfo si) {
        return si.getPackage() + "/" + si.getId();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vanks_games", 0);
    }

    static void played(Context c, ShortcutInfo si) {
        prefs(c).edit().putLong(key(si), System.currentTimeMillis()).apply();
    }

    static long lastPlayed(Context c, ShortcutInfo si) {
        return prefs(c).getLong(key(si), 0);
    }

    /** Убрать из библиотеки: открепить ярлык (в эмуляторе игра остаётся). */
    static void unpin(Context c, ShortcutInfo si) {
        LauncherApps la = c.getSystemService(LauncherApps.class);
        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
        q.setPackage(si.getPackage());
        q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
        ArrayList<String> keep = new ArrayList<>();
        try {
            for (ShortcutInfo s : la.getShortcuts(q, Process.myUserHandle()))
                if (!s.getId().equals(si.getId())) keep.add(s.getId());
            la.pinShortcuts(si.getPackage(), keep, Process.myUserHandle());
        } catch (RuntimeException ignored) {
        }
        prefs(c).edit().remove(key(si)).apply();
    }
}
