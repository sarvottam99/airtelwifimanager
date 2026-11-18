package com.sarvo.routerdevices;

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
 * Persists router credentials. Android Keystore AES-GCM is preferred. If the device's
 * Keystore implementation refuses the operation, the app falls back to MODE_PRIVATE
 * app storage so auto-login still works. The fallback is sandboxed by Android but is
 * NOT hardware/Keystore encrypted; the UI explicitly informs the user when it is used.
 */
final class SecureCredentials {
    private static final String PREFS = "router_credentials";
    private static final String KEY_ALIAS = "router_devices_credentials_v1";
    private static final String PASSWORD_IV = "password_iv";
    private static final String PASSWORD_DATA = "password_data";
    private static final String PASSWORD_FALLBACK = "password_app_private_fallback";
    private static final String AUTO_LOGIN = "auto_login";

    /**
     * Saves the password immediately. Returns true when Keystore encryption succeeded,
     * false when the app-private fallback had to be used. Throws only if neither path works.
     */
    static boolean save(Context context, String host, String username, String password) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            boolean committed = prefs.edit()
                    .putString("host", host)
                    .putString("username", username)
                    .putString(PASSWORD_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(PASSWORD_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .remove(PASSWORD_FALLBACK)
                    .putBoolean(AUTO_LOGIN, true)
                    .commit();
            if (!committed) {
                throw new IllegalStateException("Android could not write the encrypted credentials to app storage.");
            }
            return true;
        } catch (Exception keystoreError) {
            // Keep login functional on devices with a broken/unsupported Keystore AES provider.
            // MODE_PRIVATE confines this preference to this app's sandbox, but it is plaintext.
            boolean committed = prefs.edit()
                    .putString("host", host)
                    .putString("username", username)
                    .putString(PASSWORD_FALLBACK, password)
                    .remove(PASSWORD_IV)
                    .remove(PASSWORD_DATA)
                    .putBoolean(AUTO_LOGIN, true)
                    .commit();
            if (!committed) {
                throw new IllegalStateException(
                        "Both Android Keystore storage and app-private fallback storage failed.", keystoreError);
            }
            return false;
        }
    }

    static Saved load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String host = prefs.getString("host", "192.168.1.1");
        String username = prefs.getString("username", "admin");
        boolean autoLogin = prefs.getBoolean(AUTO_LOGIN, false);

        // A fallback password is used only when Keystore could not encrypt it at save time.
        if (prefs.contains(PASSWORD_FALLBACK)) {
            return new Saved(host, username, prefs.getString(PASSWORD_FALLBACK, ""), autoLogin, false);
        }

        String ivText = prefs.getString(PASSWORD_IV, null);
        String dataText = prefs.getString(PASSWORD_DATA, null);
        if (ivText == null || dataText == null) {
            return new Saved(host, username, null, false, true);
        }
        try {
            byte[] iv = Base64.decode(ivText, Base64.DEFAULT);
            byte[] data = Base64.decode(dataText, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            String password = new String(cipher.doFinal(data), StandardCharsets.UTF_8);
            return new Saved(host, username, password, autoLogin, true);
        } catch (Exception e) {
            // Do not erase host/username; let the user re-enter the password.
            prefs.edit().remove(PASSWORD_IV).remove(PASSWORD_DATA).putBoolean(AUTO_LOGIN, false).commit();
            return new Saved(host, username, null, false, true);
        }
    }

    static void setAutoLogin(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(AUTO_LOGIN, enabled).commit();
    }

    static void forget(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Credential removal from preferences is still complete.
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    static final class Saved {
        final String host;
        final String username;
        final String password;
        final boolean autoLogin;
        final boolean keystoreEncrypted;

        Saved(String host, String username, String password, boolean autoLogin, boolean keystoreEncrypted) {
            this.host = host;
            this.username = username;
            this.password = password;
            this.autoLogin = autoLogin;
            this.keystoreEncrypted = keystoreEncrypted;
        }
    }

    private SecureCredentials() {}
}
