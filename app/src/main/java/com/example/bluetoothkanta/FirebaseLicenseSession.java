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
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
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
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context.getApplicationContext(), options);
            }
            FirebaseAppCheck.getInstance()
                    .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
            initialized = true;
            if (FirebaseAuth.getInstance().getCurrentUser() == null) {
                // Called at startup. The verifier waits for this Task before any DB request.
                signInTask = FirebaseAuth.getInstance().signInAnonymously();
            }
        } catch (RuntimeException exception) {
            initialized = false;
        }
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
        begin(context);
        if (!initialized || !configured()) {
            throw new IOException("Firebase app configuration missing.");
        }
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Task<AuthResult> pending;
            synchronized (FirebaseLicenseSession.class) {
                if (signInTask == null || signInTask.isCanceled() || signInTask.isComplete()) {
                    signInTask = auth.signInAnonymously();
                }
                pending = signInTask;
            }
            AuthResult result = Tasks.await(pending, 20, TimeUnit.SECONDS);
            user = result == null ? null : result.getUser();
        }
        if (user == null) throw new IOException("Anonymous sign-in did not return a user.");
        GetTokenResult id = Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS);
        AppCheckToken appCheck = Tasks.await(
                FirebaseAppCheck.getInstance().getAppCheckToken(false), 20, TimeUnit.SECONDS);
        if (id == null || id.getToken() == null || id.getToken().isEmpty() ||
                appCheck == null || appCheck.getToken() == null || appCheck.getToken().isEmpty()) {
            throw new IOException("Firebase Auth or App Check token missing.");
        }
        return new Credentials(user.getUid(), id.getToken(), appCheck.getToken());
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
