package os.vanks.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.net.VpnService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * Плитки приложений — те, что приложения дают для быстрых настроек Android
 * (TileService): VPN, «Поделиться рядом», запись экрана и т. п. В пункте
 * управления Vanks это плитка типа «qs=пакет/класс».
 *
 * Нажимает её сама система (StatusBarManager.clickTile, разрешение STATUS_BAR
 * у Vanks OS): так плитка VPN включает VPN, как в шторке Android. Состояние
 * плитки система наружу не отдаёт — для приложений VPN «включено» значит
 * «VPN сейчас работает».
 */
final class QsTiles {
    static final String PREFIX = "qs=";

    static final class Entry {
        String type, label, app;
        ComponentName cn;
        Drawable icon;
        boolean vpn;
    }

    private static LinkedHashMap<String, Entry> cache;

    static boolean is(String type) {
        return type.startsWith(PREFIX);
    }

    /** Все плитки приложений на телефоне (по названию). */
    static synchronized ArrayList<Entry> all(Context c) {
        if (cache == null) load(c);
        return new ArrayList<>(cache.values());
    }

    static synchronized Entry get(String type) {
        return cache == null ? null : cache.get(type);
    }

    /** Поставили или удалили приложение — перечитать. */
    static synchronized void reset() {
        cache = null;
    }

    private static void load(Context c) {
        cache = new LinkedHashMap<>();
        PackageManager pm = c.getPackageManager();
        HashSet<String> vpnApps = new HashSet<>();
        try {
            for (ResolveInfo ri : pm.queryIntentServices(new Intent(VpnService.SERVICE_INTERFACE), 0))
                vpnApps.add(ri.serviceInfo.packageName);
        } catch (RuntimeException ignored) {
        }
        ArrayList<Entry> found = new ArrayList<>();
        try {
            for (ResolveInfo ri : pm.queryIntentServices(new Intent("android.service.quicksettings.action.QS_TILE"), 0)) {
                ServiceInfo si = ri.serviceInfo;
                if (!si.exported || !"android.permission.BIND_QUICK_SETTINGS_TILE".equals(si.permission)) continue;
                Entry e = new Entry();
                e.cn = new ComponentName(si.packageName, si.name);
                e.type = PREFIX + e.cn.flattenToShortString();
                e.label = String.valueOf(si.loadLabel(pm));
                e.app = String.valueOf(si.applicationInfo.loadLabel(pm));
                if (e.label.isEmpty() || e.label.equals(si.name)) e.label = e.app;
                // свой экземпляр: значок перекрашивается под тему
                e.icon = si.loadIcon(pm).mutate();
                e.vpn = vpnApps.contains(si.packageName);
                found.add(e);
            }
        } catch (RuntimeException ignored) {
        }
        found.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        for (Entry e : found) cache.put(e.type, e);
    }

    /**
     * Нажать плитку. Система нажимает только плитки, что стоят у неё в
     * быстрых настройках, поэтому сначала добавляем (повторно — не страшно).
     * false — нажать не вышло: открываем само приложение.
     */
    static boolean click(Context c, String type) {
        Entry e = get(type);
        if (e == null) return false;
        if (Updater.osVersion() != null
                && c.checkSelfPermission("android.permission.STATUS_BAR") == PackageManager.PERMISSION_GRANTED) {
            try {
                Object sb = c.getSystemService("statusbar");
                sb.getClass().getMethod("addTile", ComponentName.class).invoke(sb, e.cn);
                sb.getClass().getMethod("clickTile", ComponentName.class).invoke(sb, e.cn);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        Intent i = c.getPackageManager().getLaunchIntentForPackage(e.cn.getPackageName());
        if (i != null) Tiles.start(c, i);
        return false;
    }
}
