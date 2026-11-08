package os.vanks.launcher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Обновление по воздуху: новая версия — это релиз в репозитории на GitHub с
 * APK внутри. Vanks смотрит последний релиз, сравнивает версию со своей,
 * качает APK, сверяет SHA-256 (GitHub пишет его у файла) и ставит через
 * установщик Android. Android сам проверяет, что APK подписан тем же ключом,
 * что и стоящий Vanks, — чужой файл обновлением не встанет. «Игры Vanks» —
 * часть того же APK и обновляются вместе с лаунчером.
 */
final class Updater {
    static final String REPO = "ArtyomVank/vanks-launcher";
    private static final long EVERY = 12 * 3600_000L;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** Найденная новая версия (или null — не искали / нет новее). */
    static Release known;

    static final class Release {
        String version, notes, url, sha256;
        long size;
    }

    interface Found {
        /** r — новая версия или null; error — что не так (или null). */
        void done(Release r, String error);
    }

    interface Progress {
        void at(float f);
    }

    private Updater() {}

    /** Версия Vanks OS (rom/a04 пишет ro.vanks.version), или null — обычный Android. */
    static String osVersion() {
        try {
            String v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.vanks.version");
            return v == null || v.isEmpty() ? null : v;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String current(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Сравнить «0.21» и «0.3»: по числам, а не по буквам. */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("vanks_update", 0);
    }

    static String page() {
        return "https://github.com/" + REPO;
    }

    /** Спросить GitHub о последнем релизе. Ответ — в главном потоке. */
    static void check(Context c, Found cb) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Release r = null;
            String err = null;
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "Vanks-Launcher");
                int code = h.getResponseCode();
                if (code == 404) {
                    h.disconnect();
                } else if (code != 200) {
                    err = "GitHub ответил " + code;
                    h.disconnect();
                } else {
                    JSONObject j;
                    try (InputStream in = h.getInputStream()) {
                        j = new JSONObject(read(in));
                    }
                    h.disconnect();
                    String tag = j.optString("tag_name", "").replaceFirst("^[vV]", "");
                    JSONArray as = j.optJSONArray("assets");
                    JSONObject apk = null;
                    if (as != null) for (int i = 0; i < as.length(); i++) {
                        JSONObject a = as.getJSONObject(i);
                        if (a.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            apk = a;
                            break;
                        }
                    }
                    sp(app).edit().putLong("last", System.currentTimeMillis()).apply();
                    if (apk != null && !tag.isEmpty() && compare(tag, current(app)) > 0) {
                        r = new Release();
                        r.version = tag;
                        r.notes = j.optString("body", "").trim();
                        r.url = apk.optString("browser_download_url");
                        r.size = apk.optLong("size", -1);
                        String d = apk.optString("digest", "");
                        if (d.startsWith("sha256:")) r.sha256 = d.substring(7).toLowerCase(Locale.ROOT);
                    }
                }
            } catch (Exception e) {
                err = "Нет связи с GitHub";
            }
            Release res = r;
            String er = err;
            ui.post(() -> {
                if (er == null) known = res;
                cb.done(res, er);
            });
        }, "vanks-update").start();
    }

    /** Со стола: не чаще раза в 12 часов; нашлась новая — уведомление (один раз на версию). */
    static void maybeCheck(Context c) {
        if (!Prefs.autoUpdate) return;
        if (System.currentTimeMillis() - sp(c).getLong("last", 0) < EVERY) return;
        sp(c).edit().putLong("last", System.currentTimeMillis()).apply();
        Context app = c.getApplicationContext();
        check(app, (r, err) -> {
            if (r == null || r.version.equals(sp(app).getString("told", ""))) return;
            sp(app).edit().putString("told", r.version).apply();
            tell(app, r);
        });
    }

    private static void tell(Context c, Release r) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("update", "Обновления Vanks", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(c, 3,
                new Intent(c, SettingsActivity.class).putExtra("page", "update").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "update") : new Notification.Builder(c);
        try {
            nm.notify(11, b.setSmallIcon(R.drawable.ic_fg)
                    .setContentTitle("Доступна Vanks " + r.version)
                    .setContentText("Лаунчер и «Игры Vanks» — нажмите, чтобы обновить")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (RuntimeException ignored) {
            // уведомления запрещены — версия видна в «Настройки Vanks» → «Обновление»
        }
    }

    /** Скачать и поставить. Без разрешения «Установка приложений» — сначала туда. */
    static void install(Activity a, Release r, Progress p, Found done) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException ignored) {
            }
            done.done(r, "Разрешите Vanks устанавливать приложения и нажмите ещё раз");
            return;
        }
        Context app = a.getApplicationContext();
        new Thread(() -> {
            String err = null;
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(app.getPackageName());
                if (r.size > 0) sp.setSize(r.size);
                // после первого обновления отсюда следующие ставятся без лишнего вопроса
                if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                id = pi.createSession(sp);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection h = (HttpURLConnection) new URL(r.url).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    h.setRequestProperty("User-Agent", "Vanks-Launcher");
                    long total = r.size > 0 ? r.size : h.getContentLengthLong(), got = 0;
                    try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("vanks.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        long lastShown = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            md.update(buf, 0, n);
                            got += n;
                            if (total > 0 && got - lastShown > 256 * 1024) {
                                lastShown = got;
                                float f = got / (float) total;
                                ui.post(() -> p.at(f));
                            }
                        }
                        s.fsync(out);
                    }
                    h.disconnect();
                    if (r.sha256 != null && !r.sha256.equals(hex(md.digest()))) {
                        s.abandon();
                        err = "Файл повреждён (не та контрольная сумма) — попробуйте ещё раз";
                    } else {
                        Intent back = new Intent(app, Receiver.class);
                        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                        s.commit(PendingIntent.getBroadcast(app, id, back, fl).getIntentSender());
                    }
                }
            } catch (Exception e) {
                if (id >= 0) try { pi.abandonSession(id); } catch (Exception ignored) {}
                err = "Не удалось скачать обновление";
            }
            String er = err;
            ui.post(() -> done.done(r, er));
        }, "vanks-install").start();
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    /** Ответ установщика: попросил подтверждения — показать его; ошибка — сказать. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent ask = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (ask != null) {
                    try {
                        c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                    }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Обновление не встало" + (m != null ? ": " + m : ""), Toast.LENGTH_LONG).show();
            }
        }
    }
}
