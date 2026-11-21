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
        if (expected == null || "PASTE_RELEASE_SHA256_HERE".equals(expected)) return true;
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
            for (Signature certificate : certificates) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray());
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 0xff));
                if (MessageDigest.isEqual(hex.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        expected.toLowerCase(Locale.US).replace(":", "").getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        } catch (Exception ignored) { /* Fail closed */ }
        terminate(activity);
        return false;
    }

    private static void terminate(Activity activity) {
        activity.finishAffinity();
        System.exit(0);
    }
}
