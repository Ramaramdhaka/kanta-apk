package com.example.bluetoothkanta;

import android.content.Context;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import android.util.Log;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight Firebase Auth + App Check bootstrap. No Realtime Database SDK is needed:
 * LicenseVerifier keeps its existing OkHttp REST transport with ID/App Check tokens.
 *
 * The salt is a release build input, not a genuinely server-side secret. A determined
 * adversary can extract it from the APK; prefer HMAC on a trusted server for real secrecy.
 */
final class FirebaseLicenseSession {
    private static boolean initialized;
    private static Task<AuthResult> signInTask;
    private static String initializationFailure = "";

    private FirebaseLicenseSession() { }

    static synchronized void begin(Context context) {
        if (initialized || !configured()) return;
        try {
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setDatabaseUrl(Obfuscated.baseUrl())
                    .build();
            FirebaseApp app;
            try {
                app = FirebaseApp.getInstance();
            } catch (IllegalStateException noDefaultApp) {
                app = FirebaseApp.initializeApp(context.getApplicationContext(), options);
            }
            if (!BuildConfig.FIREBASE_APP_ID.equals(app.getOptions().getApplicationId())) {
                throw new IllegalStateException("Default Firebase app config mismatch");
            }

            try {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        PlayIntegrityAppCheckProviderFactory.getInstance());
            } catch (RuntimeException checkError) {
                // During App Check Monitoring, an attestation outage must not block Auth.
                Log.w("JG-AppCheck", "Provider unavailable during monitoring", checkError);
            }

            FirebaseAuth auth = FirebaseAuth.getInstance(app);
            initialized = true;
            initializationFailure = "";
            if (auth.getCurrentUser() == null) {
                signInTask = auth.signInAnonymously();
            }
        } catch (RuntimeException error) {
            initialized = false;
            initializationFailure = shortError(error);
            Log.e("JG-Firebase", "Firebase initialization failed", error);
        }
    }

    private static String shortError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof FirebaseAuthException) {
                return ((FirebaseAuthException) cause).getErrorCode();
            }
            if (cause instanceof TimeoutException) return "TIMEOUT";
            if (cause instanceof java.net.UnknownHostException) return "NO_INTERNET";
        }
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
    }

    private static boolean configured() {
        return BuildConfig.FIREBASE_API_KEY != null &&
                BuildConfig.FIREBASE_API_KEY.length() >= 20 &&
                BuildConfig.FIREBASE_APP_ID != null &&
                BuildConfig.FIREBASE_APP_ID.matches("1:[0-9]+:android:[A-Za-z0-9]+") &&
                BuildConfig.FIREBASE_PROJECT_ID != null &&
                BuildConfig.FIREBASE_PROJECT_ID.matches("[a-z0-9-]+");
    }

    static final class Credentials {
        final String uid;
        final String idToken;
        final String appCheckToken;
        Credentials(String uid, String idToken, String appCheckToken) {
            this.uid = uid;
            this.idToken = idToken;
            this.appCheckToken = appCheckToken;
        }
    }

    static Credentials credentials(Context context) throws Exception {
        if (!configured()) {
            throw new IOException("Firebase Android app config missing.");
        }
        begin(context);
        if (!initialized) {
            throw new IOException("Firebase initialization failed [" + initializationFailure + "]");
        }

        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            final Task<AuthResult> pending;
            synchronized (FirebaseLicenseSession.class) {
                if (signInTask == null || signInTask.isCanceled() || signInTask.isComplete()) {
                    signInTask = auth.signInAnonymously();
                }
                pending = signInTask;
            }
            try {
                AuthResult result = Tasks.await(pending, 20, TimeUnit.SECONDS);
                user = result == null ? null : result.getUser();
            } catch (Exception error) {
                Log.w("JG-Firebase", "Anonymous sign-in failed", error);
                throw new IOException("Anonymous sign-in failed [" + shortError(error) + "]", error);
            }
        }
        if (user == null) throw new IOException("Firebase anonymous account missing.");

        final String idToken;
        try {
            GetTokenResult id = Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS);
            idToken = id == null ? null : id.getToken();
        } catch (Exception error) {
            Log.w("JG-Firebase", "Auth ID token unavailable", error);
            throw new IOException("Firebase ID token failed [" + shortError(error) + "]", error);
        }
        if (idToken == null || idToken.isEmpty()) {
            throw new IOException("Firebase ID token missing.");
        }

        String appCheckToken = "";
        try {
            AppCheckToken attestation = Tasks.await(
                    FirebaseAppCheck.getInstance().getAppCheckToken(false), 8, TimeUnit.SECONDS);
            if (attestation != null && attestation.getToken() != null) {
                appCheckToken = attestation.getToken();
            }
        } catch (Exception error) {
            // This exception is allowed ONLY while RTDB App Check remains in Monitoring.
            // With enforcement enabled Firebase rejects unverified requests server-side.
            Log.w("JG-AppCheck", "Attestation unavailable, Auth-only request during Monitoring", error);
        }
        return new Credentials(user.getUid(), idToken, appCheckToken);
    }

    static String hashedKey(String key) throws Exception {
        String salt = BuildConfig.LICENSE_HASH_SALT;
        if (key == null || !key.matches("[0-9]{8}") ||
                salt == null || !salt.matches("[A-Za-z0-9_-]{24,128}")) {
            throw new IOException("License hash configuration missing.");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((key + salt).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }
}
