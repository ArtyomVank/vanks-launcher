package os.vanks.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Датчик отпечатка: где он под экраном и тихое чтение пальца.
 *
 * Место берётся из того, что телефон сам знает о датчике: из настроек Android
 * (config_udfps_sensor_props — x, y, радиус в точках матрицы) или из свойств
 * Xiaomi (fod.location / fod.size). Точки матрицы пересчитываются под текущее
 * разрешение. Не нашлось — значок там, куда его поставили в «Настройках».
 *
 * Читаем палец старым FingerprintManager: он не показывает системное окно,
 * лаунчер просто слушает датчик. Где его нет — обычное окно BiometricPrompt.
 */
@SuppressWarnings("deprecation")
final class FingerSensor {
    /** Центр и радиус датчика в точках экрана; null — не нашли. */
    private static float[] found;
    private static boolean looked;
    private static CancellationSignal current;
    /** Последняя ошибка датчика (код FingerprintManager и текст) — для повтора и «Состояния». */
    static int lastCode = -1;
    static String lastMsg;

    interface Callback {
        void ok();
        /** Палец не подошёл — можно приложить ещё. */
        void miss(String msg);
        /** Слушать больше нельзя (много попыток, ошибка); msg может быть null — отменили. */
        void fail(String msg);
        /** Палец лёг на датчик / убрали. */
        default void down() {}
        default void up() {}
    }

    /** Где датчик: {x, y, r} в точках экрана (портрет), null — не знаем. */
    static float[] locate(Context c) {
        if (looked) return found;
        looked = true;
        int[] raw = fromResources();
        if (raw == null) raw = fromXiaomi();
        if (raw == null) return null;
        // точки матрицы → нынешнее разрешение
        float scale = 1f;
        Display d = c.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) {
            int native_ = 0;
            for (Display.Mode m : d.getSupportedModes())
                native_ = Math.max(native_, Math.min(m.getPhysicalWidth(), m.getPhysicalHeight()));
            Point now = new Point();
            d.getRealSize(now);
            int cur = Math.min(now.x, now.y);
            if (native_ > 0 && cur > 0) scale = cur / (float) native_;
        }
        found = new float[]{raw[0] * scale, raw[1] * scale, Math.max(1, raw[2]) * scale};
        return found;
    }

    /** Сброс (сменили разрешение или настройку места). */
    static void forget() {
        looked = false;
        found = null;
    }

    private static int[] fromResources() {
        try {
            Resources r = Resources.getSystem();
            int id = r.getIdentifier("config_udfps_sensor_props", "array", "android");
            if (id == 0) return null;
            int[] a = r.getIntArray(id);
            if (a.length >= 3 && a[0] > 0 && a[1] > 0) return new int[]{a[0], a[1], a[2]};
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Xiaomi: «x,y» — левый верхний угол круга, «w,h» — его размер. */
    private static int[] fromXiaomi() {
        String loc = prop("persist.vendor.sys.fp.fod.location.X_Y");
        if (loc.isEmpty()) loc = prop("persist.sys.fp.fod.location.X_Y");
        if (loc.isEmpty()) return null;
        String size = prop("persist.vendor.sys.fp.fod.size.width_height");
        if (size.isEmpty()) size = prop("persist.sys.fp.fod.size.width_height");
        try {
            String[] p = loc.split(",");
            int x = Integer.parseInt(p[0].trim()), y = Integer.parseInt(p[1].trim());
            int w = 170, h = 170;
            if (!size.isEmpty()) {
                String[] s = size.split(",");
                w = Integer.parseInt(s[0].trim());
                h = Integer.parseInt(s[1].trim());
            }
            return new int[]{x + w / 2, y + h / 2, Math.min(w, h) / 2};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        } catch (Throwable ignored) {
        }
        try {
            Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Центр значка отпечатка в окне шириной w и высотой h (окно на весь экран):
     * сам датчик, а если не нашли — место из «Настроек» (доля высоты снизу).
     * {x, y, автоматически ли (1/0)}.
     */
    static float[] center(Context c, int w, int h, int insetBottom) {
        float[] s = Prefs.fpAuto ? locate(c) : null;
        if (s != null && s[1] > h * 0.4f && s[1] < h) return new float[]{Math.min(w, s[0]), s[1], 1};
        float y = Prefs.fpY > 0 ? h * (1 - Prefs.fpY) : h - insetBottom - Ui.dpf(118);
        return new float[]{w / 2f, y, 0};
    }

    /** Можно ли слушать палец без системного окна. */
    static boolean silent(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.USE_FINGERPRINT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        try {
            FingerprintManager fm = c.getSystemService(FingerprintManager.class);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Слушать палец. Без системного окна, если можно; иначе — BiometricPrompt. */
    static void auth(Activity a, Callback cb) {
        cancel();
        if (!silent(a)) {
            Bio.auth(a, "Вход в Vanks", Bio.hasFingerprintSensor(a) ? "Приложите палец к датчику" : "Посмотрите на экран", cb::ok, cb::fail);
            return;
        }
        FingerprintManager fm = a.getSystemService(FingerprintManager.class);
        CancellationSignal cs = new CancellationSignal();
        current = cs;
        try {
            fm.authenticate(null, cs, 0, new FingerprintManager.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult r) {
                    if (current == cs) current = null;
                    cb.ok();
                }

                @Override
                public void onAuthenticationFailed() {
                    cb.up();
                    cb.miss("Не тот палец — попробуйте ещё");
                }

                // Скрытые в SDK, но Android их вызывает: палец лёг на датчик
                // под экраном и поднят, датчик снял отпечаток.
                @SuppressWarnings("unused")
                public void onUdfpsPointerDown(int sensorId) {
                    cb.down();
                }

                @SuppressWarnings("unused")
                public void onUdfpsPointerUp(int sensorId) {
                    cb.up();
                }

                @SuppressWarnings("unused")
                public void onAuthenticationAcquired(int info) {
                    cb.down();
                }

                @Override
                public void onAuthenticationHelp(int code, CharSequence msg) {
                    if (msg != null) cb.miss(String.valueOf(msg));
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    if (current == cs) current = null;
                    lastCode = code;
                    lastMsg = msg != null ? msg.toString() : null;
                    cb.fail(code == FingerprintManager.FINGERPRINT_ERROR_CANCELED
                            || code == FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED ? null : String.valueOf(msg));
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException e) {
            current = null;
            // тихо не вышло — обычное окно
            Bio.auth(a, "Вход в Vanks", Bio.hasFingerprintSensor(a) ? "Приложите палец к датчику" : "Посмотрите на экран", cb::ok, cb::fail);
        }
    }

    static void cancel() {
        if (current != null) {
            current.cancel();
            current = null;
        }
        Bio.cancel();
    }

    private FingerSensor() {}
}
