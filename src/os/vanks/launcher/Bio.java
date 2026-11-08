package os.vanks.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

import java.util.function.Consumer;

/**
 * Отпечаток пальца через системный BiometricPrompt: палец читает сама система,
 * лаунчер узнаёт только «да» или «нет». С Android 11 можно войти и по PIN-коду
 * телефона, если палец не читается.
 */
final class Bio {
    private static CancellationSignal current;

    static boolean available(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            return bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS;
        }
        if (Build.VERSION.SDK_INT == 29) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        }
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
    }

    static boolean hasFingerprintSensor(Context c) {
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
    }

    /** Лицо (или другая биометрия без датчика отпечатка) записано и работает. */
    static boolean face(Context c) {
        if (Build.VERSION.SDK_INT < 30 || hasFingerprintSensor(c)) return false;
        BiometricManager bm = c.getSystemService(BiometricManager.class);
        return bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** ok — вошли; fail — отмена или ошибка (сообщение, может быть null). */
    static void auth(Activity a, String title, String subtitle, Runnable ok, Consumer<String> fail) {
        if (current != null) current.cancel();
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle(title);
        if (subtitle != null) b.setSubtitle(subtitle);
        // лицо узнано — сразу вход, без кнопки «Подтвердить»
        if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setNegativeButton("Отмена", a.getMainExecutor(), (d, w) -> fail.accept(null));
        }
        CancellationSignal cs = new CancellationSignal();
        current = cs;
        try {
            b.build().authenticate(cs, a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    if (current == cs) current = null;
                    ok.run();
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    if (current == cs) current = null;
                    fail.accept(code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ? null : String.valueOf(msg));
                }
            });
        } catch (RuntimeException e) {
            current = null;
            fail.accept("Отпечаток недоступен: " + e.getMessage());
        }
    }

    static void cancel() {
        if (current != null) {
            current.cancel();
            current = null;
        }
    }

    private Bio() {}
}
