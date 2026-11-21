package os.vanks.launcher;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Delta Link: телефон и компьютер с Delta (delta-link на ПК) в одной сети.
 *
 * Компьютер ищем широковещательным «DELTA-LINK?» на UDP 47821, привязка —
 * шестизначный код с экрана компьютера, дальше свой ключ (prefs «link»).
 * Цикл связи живёт, пока работает доступ к уведомлениям (VanksNotes) или
 * открыт стол: «привет» с зарядом раз в минуту и долгий запрос /events —
 * компьютер отвечает сразу, как только есть что прислать: буфер обмена,
 * файл (скачивает DownloadManager в «Загрузки»), «Найти телефон».
 * Уведомления уходят на компьютер из VanksNotes, буфер телефона — когда
 * открыт стол (фоновым приложениям Android буфер читать не даёт), файлы —
 * через «Поделиться → Delta Link» (LinkShareActivity).
 */
final class Link {
    static final int UDP_PORT = 47821;
    private static final String CH_RING = "link-ring";

    static final class Pc {
        final String id, name, host;
        final int port;
        Pc(String id, String name, String host, int port) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Context app;
    private static Thread loop;
    private static volatile boolean online;
    private static volatile String lastClip;
    private static final HashSet<String> iconSent = new HashSet<>();
    private static final HashMap<String, Integer> noteHash = new HashMap<>();
    private static Ringtone ringing;
    private static final Runnable STOP_RING = Link::stopRing;   // одна и та же, чтобы снять таймер

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("link", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return sp(c).getBoolean("on", false); }
    static boolean paired(Context c) { return !sp(c).getString("token", "").isEmpty(); }
    static String pcName(Context c) { return sp(c).getString("pcName", "Компьютер"); }
    static boolean notes(Context c) { return sp(c).getBoolean("notes", true); }
    static boolean clip(Context c) { return sp(c).getBoolean("clip", true); }
    static boolean online() { return online; }

    static String deviceId(Context c) {
        SharedPreferences p = sp(c);
        String id = p.getString("dev", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("dev", id).apply();
        }
        return id;
    }

    static String phoneName() {
        String m = Build.MODEL == null ? "" : Build.MODEL;
        String b = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!b.isEmpty() && !m.toLowerCase().startsWith(b.toLowerCase())) m = Character.toUpperCase(b.charAt(0)) + b.substring(1) + " " + m;
        return m.isEmpty() ? "Телефон" : m;
    }

    // ── поиск компьютеров ─────────────────────────────────────────────────

    /** Компьютеры с Delta Link в этой сети; ответ — в главном потоке. */
    static void discover(Context c, Consumer<List<Pc>> done) {
        Context ctx = c.getApplicationContext();
        new Thread(() -> {
            List<Pc> found = discoverNow(ctx, 1500);
            ui.post(() -> done.accept(found));
        }, "link-find").start();
    }

    private static List<Pc> discoverNow(Context c, int waitMs) {
        ArrayList<Pc> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] q = "DELTA-LINK?".getBytes(StandardCharsets.US_ASCII);
            ArrayList<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            InetAddress sub = subnetBroadcast(c);
            if (sub != null) targets.add(sub);
            long end = System.currentTimeMillis() + waitMs;
            long nextSend = 0;
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (InetAddress t : targets) {
                        try { s.send(new DatagramPacket(q, q.length, t, UDP_PORT)); } catch (IOException ignored) {}
                    }
                    nextSend = System.currentTimeMillis() + 500;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    if (!o.has("delta-link")) continue;
                    String id = o.optString("id");
                    if (!seen.add(id)) continue;
                    out.add(new Pc(id, o.optString("name", "Компьютер"), p.getAddress().getHostAddress(), o.optInt("port", 47820)));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static InetAddress subnetBroadcast(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address)) continue;
                byte[] a = la.getAddress().getAddress();
                int ip = ((a[0] & 0xFF) << 24) | ((a[1] & 0xFF) << 16) | ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
                int mask = la.getPrefixLength() == 0 ? 0 : -1 << (32 - la.getPrefixLength());
                int b = ip | ~mask;
                return InetAddress.getByAddress(new byte[]{(byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b});
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Компьютер по адресу, если поиск его не видит (другая подсеть, фильтр широковещания). */
    static void probe(Context c, String address, Consumer<Pc> done) {
        new Thread(() -> {
            Pc pc = null;
            String host = address.trim();
            int port = 47820;
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                try { port = Integer.parseInt(host.substring(colon + 1)); } catch (NumberFormatException ignored) {}
                host = host.substring(0, colon);
            }
            try {
                JSONObject o = call(host, port, "GET", "/info", null, null, null, 4000);
                if (o.has("delta-link")) pc = new Pc(o.optString("id"), o.optString("name", host), host, port);
            } catch (Exception ignored) {
            }
            Pc res = pc;
            ui.post(() -> done.accept(res));
        }, "link-probe").start();
    }

    // ── привязка ──────────────────────────────────────────────────────────

    /** Привязать по коду. В done — null, если получилось, иначе что сказать. */
    static void pair(Context c, Pc pc, String code, Consumer<String> done) {
        Context ctx = c.getApplicationContext();
        new Thread(() -> {
            String err;
            try {
                JSONObject body = new JSONObject().put("code", code.replaceAll("\\D", ""))
                        .put("id", deviceId(ctx)).put("name", phoneName()).put("model", Build.MODEL);
                JSONObject r = call(pc.host, pc.port, "POST", "/pair", body, null, null, 8000);
                if (r.has("token")) {
                    sp(ctx).edit().putString("token", r.getString("token"))
                            .putString("pcId", pc.id).putString("pcName", pc.name)
                            .putString("host", pc.host).putInt("port", pc.port)
                            .putInt("after", -1).putBoolean("on", true).apply();
                    err = null;
                    start(ctx);
                } else {
                    String e = r.optString("error");
                    err = "code".equals(e) ? "Неверный код" : "tries".equals(e) ? "Слишком много попыток — покажите новый код"
                            : "no-code".equals(e) ? "На компьютере нет кода — нажмите «Показать код» в Delta Link" : "Компьютер отказал";
                }
            } catch (Exception e) {
                err = "Компьютер не отвечает";
            }
            String res = err;
            ui.post(() -> done.accept(res));
        }, "link-pair").start();
    }

    static void unpair(Context c) {
        sp(c).edit().remove("token").remove("pcId").remove("pcName").remove("host").remove("port").remove("after").apply();
        online = false;
        stop();
    }

    // ── цикл связи ────────────────────────────────────────────────────────

    /** Запустить, если включено и привязано. Повторный вызов ничего не делает. */
    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (!enabled(app) || !paired(app)) return;
        if (loop != null && loop.isAlive()) return;
        loop = new Thread(Link::run, "delta-link");
        loop.setDaemon(true);
        loop.start();
    }

    static synchronized void stop() {
        if (loop != null) loop.interrupt();
        loop = null;
    }

    private static void run() {
        Thread me = Thread.currentThread();
        long lastHello = 0;
        int fails = 0;
        while (loop == me && enabled(app) && paired(app)) {
            try {
                if (System.currentTimeMillis() - lastHello > 60_000) {
                    hello();
                    lastHello = System.currentTimeMillis();
                }
                int after = sp(app).getInt("after", -1);
                JSONObject r = req("GET", "/events?after=" + after, null, null, null, 35_000);
                online = true;
                fails = 0;
                JSONArray evs = r.optJSONArray("events");
                if (evs != null) for (int i = 0; i < evs.length(); i++) event(evs.getJSONObject(i));
                if (paired(app)) sp(app).edit().putInt("after", r.optInt("next", after)).apply();
            } catch (AuthError e) {
                // компьютер забыл телефон (отвязали там)
                unpairedByPc();
                return;
            } catch (Exception e) {
                if (loop != me) return;
                online = false;
                fails++;
                // адрес сменился (DHCP) — найти тот же компьютер заново
                if (fails % 3 == 0) rediscover();
                try {
                    Thread.sleep(Math.min(30_000, 1000L << Math.min(fails, 5)));
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        online = false;
    }

    private static void rediscover() {
        String id = sp(app).getString("pcId", "");
        for (Pc pc : discoverNow(app, 1500)) {
            if (pc.id.equals(id)) {
                sp(app).edit().putString("host", pc.host).putInt("port", pc.port).putString("pcName", pc.name).apply();
                return;
            }
        }
    }

    private static void unpairedByPc() {
        sp(app).edit().remove("token").remove("after").apply();
        online = false;
        ui.post(() -> android.widget.Toast.makeText(app, "Delta Link: компьютер отвязал телефон", android.widget.Toast.LENGTH_LONG).show());
    }

    private static void hello() throws Exception {
        Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        boolean charging = false;
        if (b != null) {
            int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = l < 0 || s <= 0 ? -1 : Math.round(l * 100f / s);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        }
        req("POST", "/hello", new JSONObject().put("battery", level).put("charging", charging).put("name", phoneName()), null, null, 8000);
    }

    private static void event(JSONObject e) {
        switch (e.optString("type")) {
            case "clip": {
                String t = e.optString("text");
                if (t.isEmpty() || !clip(app)) break;
                lastClip = t;
                ui.post(() -> {
                    try {
                        ClipboardManager cm = app.getSystemService(ClipboardManager.class);
                        cm.setPrimaryClip(ClipData.newPlainText("Delta Link", t));
                    } catch (RuntimeException ignored) {
                    }
                });
                break;
            }
            case "file":
                download(e.optString("fid"), e.optString("name", "file"));
                break;
            case "ring":
                ui.post(Link::ring);
                break;
            case "unpaired":
                unpairedByPc();
                break;
        }
    }

    private static void download(String fid, String name) {
        SharedPreferences p = sp(app);
        try {
            Uri u = Uri.parse("http://" + p.getString("host", "") + ":" + p.getInt("port", 47820) + "/file/" + Uri.encode(fid));
            DownloadManager.Request r = new DownloadManager.Request(u)
                    .addRequestHeader("Authorization", "Bearer " + p.getString("token", ""))
                    .addRequestHeader("X-Link-Device", deviceId(app))
                    .setTitle(name)
                    .setDescription("С компьютера «" + pcName(app) + "»")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name.replace('/', '_'));
            app.getSystemService(DownloadManager.class).enqueue(r);
        } catch (RuntimeException ignored) {
        }
    }

    // ── «Найти телефон» ──────────────────────────────────────────────────

    /** Звонит по будильнику (слышно и без звука), пока не нажмут уведомление; 45 секунд максимум. */
    private static void ring() {
        stopRing();
        try {
            Uri tone = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            ringing = RingtoneManager.getRingtone(app, tone);
            if (ringing != null) {
                ringing.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ringing.setLooping(true);
                ringing.play();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            Vibrator v = app.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 400}, 0));
        } catch (RuntimeException ignored) {
        }
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_RING) == null) {
            NotificationChannel ch = new NotificationChannel(CH_RING, "Delta Link: найти телефон", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        PendingIntent stop = PendingIntent.getBroadcast(app, 0, new Intent(app, Stop.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(app, CH_RING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Телефон ищут")
                .setContentText("С компьютера «" + pcName(app) + "». Нажмите, чтобы остановить")
                .setContentIntent(stop)
                .setDeleteIntent(stop)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build();
        try { nm.notify(47820, n); } catch (RuntimeException ignored) {}
        ui.postDelayed(STOP_RING, 45_000);
    }

    static void stopRing() {
        ui.removeCallbacks(STOP_RING);
        if (ringing != null) {
            try { ringing.stop(); } catch (RuntimeException ignored) {}
            ringing = null;
        }
        if (app == null) return;
        try {
            Vibrator v = app.getSystemService(Vibrator.class);
            if (v != null) v.cancel();
            app.getSystemService(NotificationManager.class).cancel(47820);
        } catch (RuntimeException ignored) {
        }
    }

    /** Нажали уведомление «Телефон ищут». */
    public static final class Stop extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (app == null) app = c.getApplicationContext();
            stopRing();
        }
    }

    // ── уведомления телефона на компьютер ────────────────────────────────

    static void posted(Context c, StatusBarNotification sbn) {
        if (app == null) app = c.getApplicationContext();
        if (!online || !notes(app)) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null || sbn.getPackageName().equals(app.getPackageName())) return;
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (!call && ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)) return;
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return;
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        String t = title == null ? "" : title.toString(), x = text == null ? "" : text.toString();
        // одно и то же уведомление приложения обновляют по многу раз
        int h = (t + "\n" + x).hashCode();
        if (noteHash.size() > 500) noteHash.clear();
        Integer old = noteHash.put(sbn.getKey(), h);
        if (old != null && old == h) return;
        String pkg = sbn.getPackageName();
        String appName = pkg;
        try {
            appName = app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        String icon = iconSent.contains(pkg) ? null : icon(pkg);
        String name = appName;
        new Thread(() -> {
            try {
                JSONObject b = new JSONObject().put("key", sbn.getKey()).put("pkg", pkg).put("app", name)
                        .put("title", t).put("text", x.length() > 1000 ? x.substring(0, 1000) : x).put("call", call);
                if (icon != null) b.put("icon", icon);
                req("POST", "/notify", b, null, null, 8000);
                if (icon != null) synchronized (iconSent) { iconSent.add(pkg); }
            } catch (Exception ignored) {
            }
        }, "link-note").start();
    }

    static void removed(StatusBarNotification sbn) {
        if (noteHash.remove(sbn.getKey()) == null || !online) return;
        new Thread(() -> {
            try { req("POST", "/notify-removed", new JSONObject().put("key", sbn.getKey()), null, null, 8000); } catch (Exception ignored) {}
        }, "link-note").start();
    }

    private static String icon(String pkg) {
        try {
            Drawable d = app.getPackageManager().getApplicationIcon(pkg);
            int s = 96;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, s, s);
            d.draw(cv);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ── буфер обмена и файлы с телефона ───────────────────────────────────

    /** Стол на экране: буфер можно прочитать — отправить, если поменялся. */
    static void clipFromForeground(Context c) {
        if (app == null) app = c.getApplicationContext();
        if (!online || !clip(app)) return;
        String t;
        try {
            ClipboardManager cm = c.getSystemService(ClipboardManager.class);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return;
            // пароли, помеченные приложениями как секрет, не отправляем
            if (Build.VERSION.SDK_INT >= 24 && d.getDescription().getExtras() != null
                    && d.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE")) return;
            CharSequence s = d.getItemAt(0).coerceToText(c);
            t = s == null ? "" : s.toString();
        } catch (RuntimeException e) {
            return;
        }
        if (t.isEmpty() || t.equals(lastClip)) return;
        sendText(c, t, null);
    }

    static void sendText(Context c, String text, Consumer<Boolean> done) {
        if (app == null) app = c.getApplicationContext();
        lastClip = text;
        new Thread(() -> {
            boolean ok;
            try {
                req("POST", "/clip", new JSONObject().put("text", text), null, null, 8000);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            boolean r = ok;
            if (done != null) ui.post(() -> done.accept(r));
        }, "link-clip").start();
    }

    /** Файл на компьютер (в «Загрузки»). Вызывать не из главного потока. */
    static void upload(Context c, Uri uri, String name, long size) throws Exception {
        if (app == null) app = c.getApplicationContext();
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("нет файла");
            req("POST", "/upload", null, in, new String[]{name, String.valueOf(size)}, 600_000);
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private static final class AuthError extends Exception {}

    private static JSONObject req(String method, String path, JSONObject body, InputStream stream, String[] file, int timeout) throws Exception {
        SharedPreferences p = sp(app);
        String host = p.getString("host", "");
        if (host.isEmpty()) throw new IOException("не привязан");
        return call(host, p.getInt("port", 47820), method, path, body, stream, file, timeout);
    }

    private static JSONObject call(String host, int port, String method, String path, JSONObject body,
                                   InputStream stream, String[] file, int timeout) throws Exception {
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        try {
            h.setConnectTimeout(5000);
            h.setReadTimeout(timeout);
            h.setRequestMethod(method);
            h.setUseCaches(false);
            if (app != null) {
                SharedPreferences p = sp(app);
                if (!p.getString("token", "").isEmpty()) {
                    h.setRequestProperty("Authorization", "Bearer " + p.getString("token", ""));
                    h.setRequestProperty("X-Link-Device", deviceId(app));
                }
            }
            if (stream != null) {
                long size = Long.parseLong(file[1]);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/octet-stream");
                h.setRequestProperty("X-Name", URLEncoder.encode(file[0], "UTF-8").replace("+", "%20"));
                h.setFixedLengthStreamingMode(size);
                try (OutputStream o = h.getOutputStream()) {
                    byte[] buf = new byte[1 << 16];
                    long left = size;
                    int n;
                    while (left > 0 && (n = stream.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        o.write(buf, 0, n);
                        left -= n;
                    }
                    if (left > 0) throw new IOException("файл короче, чем обещал");
                }
            } else if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = h.getOutputStream()) { o.write(b); }
            }
            int code = h.getResponseCode();
            if (code == 401) throw new AuthError();
            InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream i = in) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            String s = out.toString("UTF-8");
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } finally {
            h.disconnect();
        }
    }
}
