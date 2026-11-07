package os.vanks.launcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Vanks: остров» — доступ к уведомлениям для динамического острова.
 *
 * Такси и доставка: постоянные уведомления этих приложений (и любых, что
 * просят «живое» уведомление Android 16 или помечены как поездка). Музыка,
 * голосовые и кружки: активные медиасессии (Telegram тоже заводит её, когда
 * играет сообщение).
 */
public final class VanksNotes extends NotificationListenerService {
    static VanksNotes on;

    /** Такси, доставка еды и посылок. */
    private static final HashSet<String> RIDES = new HashSet<>(java.util.Arrays.asList(
            "ru.yandex.taxi", "ru.yandex.yandexmaps", "com.yandex.lavka", "ru.foodfox.client", "ru.yandex.market",
            "com.ubercab", "com.ubercab.eats", "ee.mtakso.client", "com.citymobil", "com.didiglobal.passenger",
            "ru.sbcs.store", "ru.instamart", "ru.ozon.app.android", "com.wildberries.ru", "ru.dc.client",
            "com.deliveryclub", "ru.vkusvill", "com.maxim.taxi", "com.taxsee.taxsee", "sinet.startup.inDriver",
            "com.grabtaxi.passenger", "com.lyft.android", "com.dd.doordash", "com.glovo", "com.wolt.android",
            "ru.samokat.app", "ru.cdek.app", "ru.russianpost.android", "com.boxberry", "ru.megamarket.marketplace"));
    static final HashSet<String> TELEGRAM = new HashSet<>(java.util.Arrays.asList(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
            "org.thunderdog.challegram", "tw.nekomimi.nekogram", "com.exteragram.messenger",
            "uz.unnarsx.cherrygram", "org.telegram.plus", "it.octogram.android", "com.radolyn.ayugram"));
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})\\s*(мин|min)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private final HashMap<String, MediaController> sessions = new HashMap<>();
    private final HashMap<String, MediaController.Callback> callbacks = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::sessions;

    /** Доступ к уведомлениям: сами (WRITE_SECURE_SETTINGS) или страница в настройках. */
    static boolean turnOn(android.app.Activity a) {
        if (allowed(a)) return true;
        ComponentName me = new ComponentName(a, VanksNotes.class);
        if (Nav.canWrite(a)) {
            try {
                android.content.ContentResolver cr = a.getContentResolver();
                String list = android.provider.Settings.Secure.getString(cr, "enabled_notification_listeners");
                String s = me.flattenToString();
                list = list == null || list.isEmpty() ? s : list.contains(s) ? list : list + ":" + s;
                android.provider.Settings.Secure.putString(cr, "enabled_notification_listeners", list);
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        android.content.Intent i = android.os.Build.VERSION.SDK_INT >= 30
                ? new android.content.Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                        .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", me.flattenToString())
                : new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        try {
            a.startActivity(i);
        } catch (RuntimeException e) {
            try { a.startActivity(new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (RuntimeException ignored) {}
        }
        return false;
    }

    static boolean allowed(Context c) {
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, VanksNotes.class).flattenToString());
    }

    @Override
    public void onListenerConnected() {
        on = this;
        Prefs.load(this);
        Ui.init(this);
        try {
            for (StatusBarNotification n : getActiveNotifications()) posted(n);
        } catch (RuntimeException ignored) {
        }
        msm = getSystemService(MediaSessionManager.class);
        try {
            ComponentName me = new ComponentName(this, VanksNotes.class);
            msm.addOnActiveSessionsChangedListener(changed, me, ui);
            sessions(msm.getActiveSessions(me));
        } catch (RuntimeException ignored) {
            msm = null;
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(changed); } catch (RuntimeException ignored) {}
        }
        sessions(null);
        if (on == this) on = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification n) {
        posted(n);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification n) {
        Island.remove("n:" + n.getKey());
    }

    // ── такси и доставка ──────────────────────────────────────────────────

    private void posted(StatusBarNotification sbn) {
        if (!Prefs.islandRides) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || n.extras == null) return;
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return;   // музыка — через сессии
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        boolean live = n.extras.getBoolean("android.requestPromotedOngoing")
                || Notification.CATEGORY_NAVIGATION.equals(n.category)
                || "transport".equals(n.category)
                || (ongoing && RIDES.contains(pkg));
        String key = "n:" + sbn.getKey();
        if (!live) {
            Island.remove(key);
            return;
        }
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (x == null) x = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (t == null && x == null) return;
        Island.Item it = new Island.Item(key, Island.RIDE);
        it.title = t != null ? t.toString() : label(pkg);
        it.text = x != null ? x.toString() : "";
        it.badge = shortTime(it.title + " " + it.text);
        int max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        if (max > 0) it.progress = n.extras.getInt(Notification.EXTRA_PROGRESS, 0) / (float) max;
        it.icon = large(n);
        if (it.icon == null) it.icon = appIcon(pkg);
        if (n.color != 0 && n.color != Notification.COLOR_DEFAULT) it.tint = 0xFF000000 | n.color;
        if (Ui.luma(it.tint) < 0.25f) it.tint = 0xFF34C759;
        it.open = n.contentIntent;
        Island.put(it);
    }

    /** «через 5 мин», «будет в 14:20» → «5 мин» / «14:20». */
    private static String shortTime(String s) {
        Matcher m = MINUTES.matcher(s);
        if (m.find()) return m.group(1) + " мин";
        m = CLOCK.matcher(s);
        if (m.find()) return m.group();
        return null;
    }

    private Drawable large(Notification n) {
        try {
            Icon ic = n.getLargeIcon();
            return ic != null ? ic.loadDrawable(this) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ── музыка, голосовые и кружки ────────────────────────────────────────

    private void sessions(List<MediaController> list) {
        HashMap<String, MediaController> now = new HashMap<>();
        if (list != null) for (MediaController mc : list) now.putIfAbsent(mc.getPackageName(), mc);
        // ушедшие
        for (String pkg : new HashSet<>(sessions.keySet())) {
            MediaController old = sessions.get(pkg);
            MediaController cur = now.get(pkg);
            if (cur != null && cur.getSessionToken().equals(old.getSessionToken())) continue;
            try { old.unregisterCallback(callbacks.get(pkg)); } catch (RuntimeException ignored) {}
            sessions.remove(pkg);
            callbacks.remove(pkg);
            Island.remove("m:" + pkg);
        }
        // новые
        for (MediaController mc : now.values()) {
            String pkg = mc.getPackageName();
            if (sessions.containsKey(pkg)) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState s) {
                    media(mc);
                }

                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    media(mc);
                }

                @Override
                public void onSessionDestroyed() {
                    Island.remove("m:" + pkg);
                }
            };
            sessions.put(pkg, mc);
            callbacks.put(pkg, cb);
            try { mc.registerCallback(cb, ui); } catch (RuntimeException ignored) {}
            media(mc);
        }
    }

    private void media(MediaController mc) {
        String pkg = mc.getPackageName(), key = "m:" + pkg;
        PlaybackState st = mc.getPlaybackState();
        int s = st != null ? st.getState() : PlaybackState.STATE_NONE;
        boolean playing = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
        Island.Item was = Island.get(key);
        if (!Prefs.islandMedia || !playing && was == null) {
            if (!Prefs.islandMedia) Island.remove(key);
            return;
        }
        if (s == PlaybackState.STATE_STOPPED || s == PlaybackState.STATE_NONE || s == PlaybackState.STATE_ERROR) {
            Island.remove(key);
            return;
        }
        boolean tg = TELEGRAM.contains(pkg);
        Island.Item it = new Island.Item(key, tg ? Island.VOICE : Island.MEDIA);
        MediaMetadata md = mc.getMetadata();
        String title = null, artist = null;
        Bitmap art = null;
        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        it.title = title != null && !title.isEmpty() ? title : tg ? "Голосовое сообщение" : label(pkg);
        it.text = artist != null ? artist : label(pkg);
        it.art = art;
        it.icon = appIcon(pkg);
        it.playing = playing;
        it.media = mc;
        it.open = mc.getSessionActivity();
        it.tint = tg ? 0xFF5AC8FA : 0xFFFFFFFF;
        // на паузе — ещё 5 секунд, потом уходит
        if (!playing) it.until = SystemClock.uptimeMillis() + 5000;
        Island.put(it);
    }

    /** Играет ли у Telegram своя сессия (тогда угадывать по звуку не нужно). */
    static boolean telegramSession() {
        VanksNotes n = on;
        if (n == null) return false;
        for (String pkg : n.sessions.keySet()) if (TELEGRAM.contains(pkg) && Island.get("m:" + pkg) != null) return true;
        return false;
    }
}
