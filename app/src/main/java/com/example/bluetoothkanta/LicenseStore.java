package com.example.bluetoothkanta;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;

/** AES-256-GCM encrypted values, AES-256-SIV encrypted preference keys. */
@SuppressWarnings("deprecation")
final class LicenseStore {
    private static final String LICENSE = "saved_device_lock_key";
    private static final String FAIL_COUNT = "failed_attempts";
    private static final String LOCK_UNTIL = "lock_until";
    private static final String LOCK_ELAPSED = "lock_elapsed";
    private static final long BLOCK_MS = 10L * 60L * 1000L;
    private final SharedPreferences encrypted;

    LicenseStore(Context context) throws GeneralSecurityException, IOException {
        MasterKey master = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        encrypted = EncryptedSharedPreferences.create(context, "jg_license_secure_v2", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    String getDeviceLockKey() {
        String key = encrypted.getString(LICENSE, null);
        return key != null && key.matches("[0-9]{8}") ? key : null;
    }

    void saveDeviceLockKey(String key) {
        if (key == null || !key.matches("[0-9]{8}")) throw new IllegalArgumentException("Invalid key");
        if (!encrypted.edit().putString(LICENSE, key).commit()) throw new IllegalStateException("License not saved");
    }

    void clear() { encrypted.edit().remove(LICENSE).commit(); }

    void resetFailures() {
        encrypted.edit().remove(FAIL_COUNT).remove(LOCK_UNTIL).remove(LOCK_ELAPSED).commit();
    }

    void failAttempt() {
        int count = encrypted.getInt(FAIL_COUNT, 0) + 1;
        SharedPreferences.Editor editor = encrypted.edit().putInt(FAIL_COUNT, count);
        if (count >= 5) {
            editor.putLong(LOCK_UNTIL, System.currentTimeMillis() + BLOCK_MS);
            editor.putLong(LOCK_ELAPSED, android.os.SystemClock.elapsedRealtime() + BLOCK_MS);
        }
        editor.commit();
    }

    long lockRemainingMs() {
        long wall = encrypted.getLong(LOCK_UNTIL, 0L);
        long elapsed = encrypted.getLong(LOCK_ELAPSED, 0L);
        if (wall == 0) return 0;
        long wallRemaining = wall - System.currentTimeMillis();
        long elapsedRemaining = elapsed - android.os.SystemClock.elapsedRealtime();
        long remaining = Math.max(wallRemaining, elapsedRemaining > BLOCK_MS ? 0 : elapsedRemaining);
        if (remaining <= 0) { resetFailures(); return 0; }
        return remaining;
    }
}
