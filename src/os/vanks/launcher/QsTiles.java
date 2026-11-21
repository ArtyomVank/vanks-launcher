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
    /** Плитки, уже добавленные в быстрые настройки системы за этот запуск. */
    private static final HashSet<String> added = new HashSet<>();

    static boolean click(Context c, String type) {
        Entry e = get(type);
        if (e == null) return false;
        if (Updater.osVersion() != null
                && c.checkSelfPermission("android.permission.STATUS_BAR") == PackageManager.PERMISSION_GRANTED) {
            try {
                Object sb = c.getSystemService("statusbar");
                java.lang.reflect.Method click = sb.getClass().getMethod("clickTile", ComponentName.class);
                if (added.add(e.type)) {
                    // плитки ещё нет в быстрых настройках: система добавляет её не сразу,
                    // и нажатие до этого уходит в пустоту — жмём, когда она появится
                    sb.getClass().getMethod("addTile", ComponentName.class).invoke(sb, e.cn);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try { click.invoke(sb, e.cn); } catch (ReflectiveOperationException | RuntimeException ignored) {}
                    }, 450);
                } else click.invoke(sb, e.cn);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (e.vpn) return vpnFallback(c, e);
        openApp(c, e);
        return false;
    }

    private static void openApp(Context c, Entry e) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(e.cn.getPackageName());
        if (i != null) Tiles.start(c, i);
    }

    // ── VPN на обычном телефоне ──────────────────────────────────────────
    //
    // Нажать плитку чужого приложения может только система. Без неё VPN
    // переключаем так, чтобы приложение осталось в фоне:
    //   1) ярлык приложения «Подключить» / «Отключить» / «Переключить»
    //      (v2rayNG, Hiddify, NekoBox и др.; Vanks — лаунчер по умолчанию);
    //   2) его плитка в шторке Android — нажимает служба «Vanks: жесты и вход»
    //      и сразу закрывает шторку;
    //   3) иначе — само приложение (не страница VPN в настройках).

    /** true — переключаем в фоне (пункт управления не закрывать), false — открыли приложение. */
    private static boolean vpnFallback(Context c, Entry e) {
        boolean on = vpnOn(c);
        if (vpnShortcut(c, e, on)) return true;
        VanksAccess a = VanksAccess.on;
        if (a != null) {
            a.clickQsTile(e.label, e.app, () -> openApp(c, e));
            return true;
        }
        openApp(c, e);
        return false;
    }

    static boolean vpnOn(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static final java.util.regex.Pattern OFF = java.util.regex.Pattern.compile(
            "disconnect|stop|turn.?off|\\boff\\b|отключ|выключ|останов|разъедин");
    private static final java.util.regex.Pattern ON = java.util.regex.Pattern.compile(
            "connect|start|turn.?on|\\bon\\b|подключ|включ|запуст|соедин");
    private static final java.util.regex.Pattern TOGGLE = java.util.regex.Pattern.compile("toggle|switch|переключ|вкл.?/.?выкл");

    /** Ярлык приложения, что делает нужное: при включённом VPN — отключить, иначе — подключить. */
    private static boolean vpnShortcut(Context c, Entry e, boolean on) {
        try {
            android.content.pm.LauncherApps la = c.getSystemService(android.content.pm.LauncherApps.class);
            if (la == null || !la.hasShortcutHostPermission()) return false;
            android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
            q.setPackage(e.cn.getPackageName());
            q.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            java.util.List<android.content.pm.ShortcutInfo> list = la.getShortcuts(q, android.os.Process.myUserHandle());
            if (list == null) return false;
            android.content.pm.ShortcutInfo best = null;
            int bestScore = 0;
            for (android.content.pm.ShortcutInfo si : list) {
                if (!si.isEnabled()) continue;
                String t = (si.getId() + " " + si.getShortLabel() + " " + si.getLongLabel()).toLowerCase(java.util.Locale.ROOT);
                boolean off = OFF.matcher(t).find(), toggle = TOGGLE.matcher(t).find();
                boolean onWord = !off && ON.matcher(t).find();
                int score = toggle ? 2 : on ? (off ? 3 : 0) : (onWord ? 3 : 0);
                if (score > bestScore) { best = si; bestScore = score; }
            }
            if (best == null) return false;
            la.startShortcut(best.getPackage(), best.getId(), null, null, android.os.Process.myUserHandle());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ── какой VPN включает общая плитка «VPN» ─────────────────────────────

    static java.util.List<Entry> vpns(Context c) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : all(c)) if (e.vpn) out.add(e);
        return out;
    }

    static String vpnChoice(Context c) {
        return c.getSharedPreferences("tiles", Context.MODE_PRIVATE).getString("vpn", "");
    }

    static void setVpnChoice(Context c, String type) {
        c.getSharedPreferences("tiles", Context.MODE_PRIVATE).edit().putString("vpn", type).apply();
    }

    /** Приложение для плитки «VPN»: одно — оно, выбрано — выбранное, иначе null (спросить). */
    static Entry vpnFor(Context c) {
        java.util.List<Entry> l = vpns(c);
        if (l.size() == 1) return l.get(0);
        String want = vpnChoice(c);
        for (Entry e : l) if (e.type.equals(want)) return e;
        return null;
    }

    /** Спросить, какой VPN включать (один раз; поменять — «Пункт управления» в настройках). */
    static void askVpn(Context c, java.util.function.Consumer<Entry> picked) {
        java.util.List<Entry> l = vpns(c);
        if (l.isEmpty()) return;
        if (!(c instanceof android.app.Activity)) {
            setVpnChoice(c, l.get(0).type);
            picked.accept(l.get(0));
            return;
        }
        String[] names = new String[l.size()];
        for (int i = 0; i < names.length; i++) {
            Entry e = l.get(i);
            names[i] = e.label.equals(e.app) ? e.app : e.app + " · " + e.label;
        }
        new android.app.AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Какой VPN включать?")
                .setItems(names, (d, w) -> {
                    setVpnChoice(c, l.get(w).type);
                    picked.accept(l.get(w));
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
