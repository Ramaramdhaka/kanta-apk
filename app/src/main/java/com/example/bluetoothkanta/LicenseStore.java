package com.example.bluetoothkanta;

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

/** DEVICE_LOCK keys only: AES-GCM encrypted SharedPreferences backed by AndroidKeyStore. */
final class LicenseStore {
    private static final String KEY_ALIAS = "jg_license_storage_v1";
    private final SharedPreferences prefs;
    LicenseStore(Context context) {
        prefs = context.getSharedPreferences("jg_device_license", Context.MODE_PRIVATE);
    }

    String getDeviceLockKey() {
        String value = prefs.getString("locked_key", null);
        if (value == null) return null;
        try {
            byte[] encoded = Base64.decode(value, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            if (encoded.length < 29) throw new IllegalArgumentException("Bad encrypted key");
            System.arraycopy(encoded, 0, iv, 0, iv.length);
            Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            byte[] plaintext = decrypt.doFinal(encoded, 12, encoded.length - 12);
            String result = new String(plaintext, StandardCharsets.UTF_8);
            if (!result.matches("[0-9]{8}")) throw new IllegalArgumentException("Bad license key");
            return result;
        } catch (Exception ex) {
            clear();
            return null;
        }
    }

    void saveDeviceLockKey(String key) {
        try {
            Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = encrypt.getIV();
            byte[] encrypted = encrypt.doFinal(key.getBytes(StandardCharsets.UTF_8));
            byte[] data = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, data, 0, iv.length);
            System.arraycopy(encrypted, 0, data, iv.length, encrypted.length);
            prefs.edit().putString("locked_key", Base64.encodeToString(data, Base64.NO_WRAP)).apply();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot securely save the license", ex);
        }
    }

    void clear() { prefs.edit().remove("locked_key").apply(); }

    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry previous = store.getEntry(KEY_ALIAS, null);
        if (previous instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) previous).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
