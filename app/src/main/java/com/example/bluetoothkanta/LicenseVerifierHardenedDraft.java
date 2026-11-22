package com.example.bluetoothkanta;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Direct HTTPS Firebase REST with required Firebase Auth and Play Integrity App Check tokens. */
public final class LicenseVerifier {
    private LicenseVerifier() {}

    public static final class Result {
        public final boolean valid;
        public final boolean serverRejected;
        public final boolean badKeyAttempt;
        public final String message;
        public final String type;
        public final String expiry;

        Result(boolean valid, boolean serverRejected, boolean badKeyAttempt,
               String message, String type, String expiry) {
            this.valid = valid;
            this.serverRejected = serverRejected;
            this.badKeyAttempt = badKeyAttempt;
            this.message = message;
            this.type = type;
            this.expiry = expiry;
        }
    }

    private static Result success(String type, String expiry) {
        return new Result(true, false, false, "License verified successfully.", type, expiry);
    }

    private static Result rejected(String message, boolean badKey) {
        return new Result(false, true, badKey, message, "", "");
    }

    private static Result blocked(String message) {
        return new Result(false, false, false, message, "", "");
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false).followSslRedirects(false)
            .build();

    public static Result verify(Context context, String key, boolean savedDeviceLockRecheck) {
        if (key == null || !key.matches("[0-9]{8}")) {
            return rejected("Enter an 8-digit numeric License Key", true);
        }
        if (!NetUtil.hasInternet(context)) {
            return blocked("Internet connection required to verify license");
        }
        String device = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (device == null || !device.matches("[0-9a-fA-F]{8,64}")) {
            return blocked("Unable to read Android Device ID.");
        }

        final FirebaseLicenseSession.Credentials credentials;
        final String path;
        try {
            // Must succeed BEFORE any database GET or PUT.
            credentials = FirebaseLicenseSession.credentials(context.getApplicationContext());
            path = FirebaseLicenseSession.hashedKey(key);
        } catch (Exception authFailure) {
            return blocked("Firebase authentication / App Check unavailable. Contact support.");
        }

        final String url = Obfuscated.baseUrl() + "licenses/" + path + ".json?auth=" +
                URLEncoder.encode(credentials.idToken, StandardCharsets.UTF_8);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Snapshot snapshot = load(url, credentials.appCheckToken);
                if (snapshot.value == null) return rejected("Invalid License Key!", true);

                JSONObject record = snapshot.value;
                String status = record.optString(Obfuscated.status(), "");
                String type = record.optString(Obfuscated.type(), "");
                String expiry = record.optString(Obfuscated.expiry(), "");
                String registered = record.optString(Obfuscated.deviceId(), "").trim();
                boolean claimed = record.optBoolean("claimed", true);
                String ownerUid = record.optString("uid", "");

                if (Obfuscated.hold().equals(status))
                    return rejected("Your Key is on HOLD (Locked).", false);
                if (Obfuscated.used().equals(status))
                    return rejected("This One-Time Key has already been used.", false);
                if (!Obfuscated.active().equals(status))
                    return rejected("License Key is not ACTIVE.", false);

                try {
                    LocalDate end = LocalDate.parse(expiry);
                    if (LocalDate.now(ZoneId.systemDefault()).isAfter(end))
                        return rejected("Your License Key has Expired.", false);
                } catch (DateTimeParseException error) {
                    return rejected("Invalid license expiry date.", false);
                }

                if (claimed) {
                    if (!credentials.uid.equals(ownerUid)) {
                        return rejected("This Key is already registered to another account.", false);
                    }
                    if (!Obfuscated.deviceLock().equals(type))
                        return rejected("One-Time Key has already been used.", false);
                    if (!device.equalsIgnoreCase(registered))
                        return rejected("This Key is registered on another mobile.", false);
                    return success(type, expiry); // one authenticated GET on repeat opens
                }
                if (savedDeviceLockRecheck)
                    return rejected("Saved license claim was removed.", false);
                if (!registered.isEmpty() || !ownerUid.isEmpty())
                    return rejected("License record is not available for first activation.", false);

                if (!Obfuscated.deviceLock().equals(type) &&
                        !Obfuscated.oneTime().equals(type))
                    return rejected("Unsupported license type.", false);

                JSONObject updated = new JSONObject(record.toString());
                updated.put("claimed", true);
                updated.put("uid", credentials.uid);
                updated.put(Obfuscated.deviceId(), device);
                // Keep status, expiry, plan, customer and all other metadata unchanged.
                int responseCode = claim(url, snapshot.etag, updated, credentials.appCheckToken);
                if (responseCode == 200) return success(type, expiry);
                if (responseCode == 412) continue; // simultaneous claims: re-read and deny losing UID
                if (responseCode == 401 || responseCode == 403)
                    return blocked("License activation denied by Firebase Rules.");
                return blocked("Firebase activation failed (HTTP " + responseCode + ").");
            }
            return blocked("License was claimed during login. Try again.");
        } catch (HttpFailure error) {
            if (error.status == 401 || error.status == 403)
                return blocked("Firebase authentication or database access denied.");
            return blocked("Firebase error (HTTP " + error.status + "). Try again.");
        } catch (Exception error) {
            return blocked("Could not verify license. Check internet and try again.");
        }
    }

    private static Snapshot load(String url, String appCheck) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Firebase-AppCheck", appCheck)
                .header("Cache-Control", "no-store")
                .header("X-Firebase-ETag", "true").get().build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (response.code() != 200) throw new HttpFailure(response.code());
            if (response.body() == null) throw new IOException("Empty response");
            String text = response.body().string();
            if (text.length() > 65536) throw new IOException("Response too large");
            String etag = response.header("ETag");
            if (etag == null || etag.isEmpty()) throw new IOException("Missing ETag");
            JSONObject value = text.trim().isEmpty() || text.trim().equals("null")
                    ? null : new JSONObject(text);
            return new Snapshot(value, etag);
        }
    }

    private static int claim(String url, String etag, JSONObject record, String appCheck) throws Exception {
        RequestBody body = RequestBody.create(record.toString(),
                MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url)
                .header("if-match", etag)
                .header("X-Firebase-AppCheck", appCheck)
                .header("Cache-Control", "no-store")
                .put(body).build();
        try (Response response = HTTP.newCall(request).execute()) {
            return response.code();
        }
    }

    private static final class Snapshot {
        final JSONObject value;
        final String etag;
        Snapshot(JSONObject value, String etag) { this.value = value; this.etag = etag; }
    }
    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("HTTP error"); this.status = status; }
    }
}
