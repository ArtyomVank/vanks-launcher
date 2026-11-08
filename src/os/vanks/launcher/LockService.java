package os.vanks.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

/**
 * Держит свой экран блокировки: экран погас — открыть LockActivity, чтобы
 * при включении первым было видно его. Работает и без стола Vanks: это
 * отдельная служба с тихим уведомлением (иначе Android её выгрузит).
 *
 * Открыть окно из фона Android даёт только с разрешением «Поверх других
 * приложений» — без него служба не запускается.
 */
public final class LockService extends Service {
    private static final int NOTE = 8;
    private BroadcastReceiver off;

    /**
     * Можно ли открывать окна из фона: «Поверх других приложений» или, в
     * Vanks OS, системное разрешение START_ACTIVITIES_FROM_BACKGROUND.
     */
    static boolean canOpen(Context c) {
        return Settings.canDrawOverlays(c)
                || c.checkSelfPermission("android.permission.START_ACTIVITIES_FROM_BACKGROUND") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Включить или выключить по настройке. */
    static void sync(Context c) {
        Intent i = new Intent(c, LockService.class);
        boolean on = (Prefs.lockMain || AodActivity.enabled()) && canOpen(c);
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } else {
                c.stopService(i);
                LockActivity.locked = false;
            }
        } catch (RuntimeException ignored) {
            // из фона службу запустить не дали — запустится из «Настроек» или со стола
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.load(this);
        foreground();
        off = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Prefs.load(c);
                // погас при горящем AOD — его дело (уйти или включить экран)
                if (AodActivity.screenOff(c)) return;
                if (Prefs.lockMain) LockActivity.show(c);
                // AOD поверх экрана блокировки: открыть чуть позже, чтобы встал сверху
                if (AodActivity.enabled() && AodActivity.worth(c))
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> AodActivity.show(c, false), Prefs.lockMain ? 250 : 0);
            }
        };
        registerReceiver(off, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel("lock", "Экран блокировки", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).putExtra("page", "lock").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "lock") : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(Prefs.lockMain ? "Экран блокировки Vanks" : "Всегда на экране")
                .setContentText("Включится, когда погаснет экран")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTE, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        Prefs.load(this);
        if (!(Prefs.lockMain || AodActivity.enabled()) || !canOpen(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(off);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    /** После перезагрузки — снова держать экран блокировки. */
    public static final class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            Prefs.load(c);
            sync(c);
            EdgeService.sync(c);
            if (Updater.osVersion() != null) {
                // Vanks OS: системного замка нет — свой экран сразу после включения,
                // и жесты (системной панели навигации тоже нет)
                if (Prefs.navGestures) VanksAccess.enable(c);
                // уведомления — в шторке Vanks (системной в Vanks OS нет)
                VanksNotes.enable(c);
                if (Prefs.lockMain && canOpen(c)) LockActivity.show(c);
            }
            // на обычном телефоне после включения он уже открыт системным
            // замком — свой экран покажется при следующем выключении экрана
        }
    }
}
