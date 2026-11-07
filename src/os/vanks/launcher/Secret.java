package os.vanks.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * PIN-код Android, который человек сам указал в «Отпечаток и вход»: им
 * открывается экран Vanks для других людей, и его служба набирает на
 * системном экране после пальца. Хранится зашифрованным ключом из
 * хранилища ключей Android (ключ не покидает телефон и не копируется в
 * резервные копии).
 */
final class Secret {
    private static final String KEY = "vanks_pin", FILE = "vanks_secret";

    static boolean has(Context c) {
        return c.getSharedPreferences(FILE, 0).contains("pin");
    }

    static void set(Context c, String pin) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = ci.doFinal(pin.getBytes(StandardCharsets.UTF_8));
        c.getSharedPreferences(FILE, 0).edit()
                .putString("pin", Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(ci.getIV(), Base64.NO_WRAP))
                .apply();
    }

    static String get(Context c) {
        SharedPreferences sp = c.getSharedPreferences(FILE, 0);
        String ct = sp.getString("pin", null), iv = sp.getString("iv", null);
        if (ct == null || iv == null) return null;
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(ci.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Проверить введённый пароль, не держа сохранённый в полях. */
    static boolean check(Context c, String typed) {
        String s = get(c);
        return s != null && java.security.MessageDigest.isEqual(s.getBytes(StandardCharsets.UTF_8), typed.getBytes(StandardCharsets.UTF_8));
    }

    static void clear(Context c) {
        c.getSharedPreferences(FILE, 0).edit().clear().apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY)) return (SecretKey) ks.getKey(KEY, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }

    private Secret() {}
}
