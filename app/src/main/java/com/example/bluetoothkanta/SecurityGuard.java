package com.example.bluetoothkanta;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import java.security.MessageDigest;
import java.util.Locale;

/** Release integrity checks. Client-side checks are not unbreakable DRM. */
final class SecurityGuard {
    private SecurityGuard() {}

    static boolean enforce(Activity activity) {
        if (BuildConfig.DEBUG) return true;
        if (Debug.isDebuggerConnected() ||
                (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            terminate(activity);
            return false;
        }
        String expected = BuildConfig.EXPECTED_SIGNATURE_SHA256;
        // A release without a pinned, valid certificate fingerprint must not run.
        if (expected == null || !expected.matches("(?i)[0-9a-f:]{64,95}") ||
                "PASTE_RELEASE_SHA256_HERE".equals(expected)) {
            terminate(activity);
            return false;
        }
        try {
            PackageManager manager = activity.getPackageManager();
            PackageInfo info;
            Signature[] certificates;
            if (Build.VERSION.SDK_INT >= 28) {
                info = manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                certificates = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                info = manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
                certificates = info.signatures;
            }
            if (certificates == null || certificates.length == 0) throw new SecurityException("Unsigned");
            byte[] expectedDigest = hexToBytes(expected.replace(":", ""));
            if (expectedDigest == null || expectedDigest.length != 32) throw new SecurityException("Bad fingerprint");
            for (Signature certificate : certificates) {
                byte[] actualDigest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray());
                if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
                    throw new SecurityException("Unexpected signing certificate");
                }
            }
            return true;
        } catch (Exception ignored) { /* Fail closed */ }
        terminate(activity);
        return false;
    }

    private static byte[] hexToBytes(String value) {
        if (!value.matches("(?i)[0-9a-f]{64}")) return null;
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static void terminate(Activity activity) {
        activity.finishAffinity();
        System.exit(0);
    }
}
