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

/** Stores the router password encrypted with a key held by Android Keystore. */
final class SecureCredentials {
    private static final String PREFS = "router_credentials";
    private static final String KEY_ALIAS = "router_devices_credentials_v1";
    private static final String PASSWORD_IV = "password_iv";
    private static final String PASSWORD_DATA = "password_data";

    static void save(Context context, String host, String username, String password) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString("host", host)
                .putString("username", username)
                .putString(PASSWORD_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PASSWORD_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    static Saved load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String host = prefs.getString("host", "192.168.1.1");
        String username = prefs.getString("username", "admin");
        String ivText = prefs.getString(PASSWORD_IV, null);
        String dataText = prefs.getString(PASSWORD_DATA, null);
        if (ivText == null || dataText == null) return new Saved(host, username, null);
        try {
            byte[] iv = Base64.decode(ivText, Base64.DEFAULT);
            byte[] data = Base64.decode(dataText, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            String password = new String(cipher.doFinal(data), StandardCharsets.UTF_8);
            return new Saved(host, username, password);
        } catch (Exception e) {
            // Reinstallation, keystore reset or corrupted data: forget the unreadable secret.
            forget(context);
            return new Saved(host, username, null);
        }
    }

    static void forget(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
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
        Saved(String host, String username, String password) {
            this.host = host;
            this.username = username;
            this.password = password;
        }
    }

    private SecureCredentials() {}
}
