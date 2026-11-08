package os.vanks.launcher;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Плитки вне стола: шторка Vanks OS открывается поверх любого приложения,
 * поэтому всё, что плитки читают и делают, — здесь, без окна лаунчера.
 * (Пункт управления на столе пока держит свою копию в Home.)
 */
final class Tiles {
    private static CameraManager cameras;
    private static String torchId;
    private static boolean torch;

    /** Следить за фонариком (включить могли и не мы). */
    static void watchTorch(Context c) {
        if (cameras != null) return;
        cameras = c.getSystemService(CameraManager.class);
        if (cameras == null) return;
        try {
            for (String id : cameras.getCameraIdList()) {
                Boolean f = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(f)) { torchId = id; break; }
            }
            cameras.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean on) {
                    if (id.equals(torchId)) torch = on;
                }
            }, null);
        } catch (Exception ignored) {
        }
    }

    /** Прочитать состояние: сеть, Bluetooth, режимы, громкость, яркость. */
    static void read(Context c, TileGrid t) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            t.wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }
        readMore(c, t);
        try {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter ba = bm == null ? null : bm.getAdapter();
            t.bt = ba != null && ba.isEnabled();
        } catch (Exception ignored) {
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) t.dnd = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        t.airplane = Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        t.rotate = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) != 0;
        t.bright = Ui.clamp(Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f);
        t.torch = torch;
        // заряд — из последнего (липкого) сообщения системы о батарее
        Intent b = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lvl = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            t.battery = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
            t.charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
        }
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            t.volume = max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0;
            t.playing = am.isMusicActive();
        }
        t.invalidate();
    }

    /** VPN, геолокация, экономия заряда — общее у пункта управления и стола. */
    static void readMore(Context c, TileGrid t) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            t.vpn = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception ignored) {
        }
        LocationManager lm = c.getSystemService(LocationManager.class);
        t.location = lm != null && Build.VERSION.SDK_INT >= 28 && lm.isLocationEnabled();
        PowerManager pm = c.getSystemService(PowerManager.class);
        t.saver = pm != null && pm.isPowerSaveMode();
    }

    /** Нажали плитку. true — открылось окно (шторку пора закрыть). */
    static boolean tap(Context c, String type, TileGrid t) {
        switch (type) {
            case "wifi":
                return start(c, new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS));
            case "bt":
                return start(c, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            case "airplane":
                return start(c, new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
            case "camera":
                return start(c, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            case "calc":
                return start(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
            case "alarm":
                return start(c, new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            case "timer":
                return start(c, new Intent(AlarmClock.ACTION_SHOW_TIMERS));
            case "battery":
                return start(c, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
            case "media":
                return start(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC));
            case "settings":
                return start(c, new Intent(c, SettingsActivity.class));
            case "torch":
                if (cameras == null || torchId == null) {
                    toast(c, "Фонарика нет");
                    return false;
                }
                try {
                    cameras.setTorchMode(torchId, !torch);
                    torch = !torch;
                    t.torch = torch;
                } catch (Exception e) {
                    toast(c, "Фонарик занят");
                }
                break;
            case "dnd": {
                boolean on = !t.dnd;
                // служба уведомлений Vanks меняет режим без отдельного разрешения
                VanksNotes n = VanksNotes.on;
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (n != null) n.requestInterruptionFilter(on ? NotificationListenerService.INTERRUPTION_FILTER_PRIORITY : NotificationListenerService.INTERRUPTION_FILTER_ALL);
                else if (nm != null && nm.isNotificationPolicyAccessGranted())
                    nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
                else return start(c, new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                t.dnd = on;
                break;
            }
            case "vpn": {
                // VPN включает плитка его приложения; приложений несколько или нет — настройки VPN
                QsTiles.Entry only = null;
                int n = 0;
                for (QsTiles.Entry e : QsTiles.all(c)) if (e.vpn) { only = e; n++; }
                if (n == 1) return clickApp(c, only.type, t);
                return start(c, new Intent("android.settings.VPN_SETTINGS"));
            }
            case "location":
                if (!Nav.canWrite(c)) return start(c, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                t.location = !t.location;
                // Android сам переводит эту настройку во включение геолокации
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE,
                        t.location ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF);
                break;
            case "saver":
                try {
                    // системный вызов (разрешение POWER_SAVER у Vanks OS)
                    PowerManager pm = c.getSystemService(PowerManager.class);
                    Object ok = pm.getClass().getMethod("setPowerSaveModeEnabled", boolean.class).invoke(pm, !t.saver);
                    if (Boolean.TRUE.equals(ok)) t.saver = !t.saver;
                    else return start(c, new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return start(c, new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
                }
                break;
            case "hotspot":
                return start(c, new Intent().setComponent(new android.content.ComponentName("com.android.settings",
                        "com.android.settings.Settings$TetherSettingsActivity")))
                        || start(c, new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            case "rotate":
                if (!Settings.System.canWrite(c))
                    return start(c, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + c.getPackageName())));
                t.rotate = !t.rotate;
                Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, t.rotate ? 1 : 0);
                break;
        }
        if (QsTiles.is(type)) return clickApp(c, type, t);
        t.invalidate();
        return false;
    }

    /**
     * Плитка приложения. VPN: окно не закрываем, а через секунду перечитываем,
     * включился ли он; остальные обычно что-то открывают — закрыть.
     */
    private static boolean clickApp(Context c, String type, TileGrid t) {
        boolean clicked = QsTiles.click(c, type);
        QsTiles.Entry e = QsTiles.get(type);
        if (!clicked) return true;
        if (e != null && e.vpn) {
            t.postDelayed(() -> readMore(c, t), 1500);
            t.postDelayed(() -> { readMore(c, t); t.invalidate(); }, 3500);
            return false;
        }
        return true;
    }

    /** Кнопки музыки: -1 назад, 0 пауза/играть, 1 вперёд. */
    static void media(Context c, int which, TileGrid t) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return;
        int code = which < 0 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : which > 0 ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        if (which == 0) {
            t.playing = !t.playing;
            t.invalidate();
        }
    }

    /** Ползунки: громкость музыки и яркость (яркость — если можно менять настройки системы). */
    static void slider(Context c, String type, float v) {
        if (type.equals("volume")) {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am == null) return;
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            } catch (SecurityException ignored) {
                // «Не беспокоить» не даёт менять громкость
            }
            return;
        }
        if (!Settings.System.canWrite(c)) return;
        try {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * 255)));
        } catch (Exception ignored) {
        }
    }

    static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            toast(c, "Не удалось открыть");
            return false;
        }
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    private Tiles() {}
}
