package com.example.bluetoothkanta;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Fast direct HTTPS Firebase Realtime Database REST verifier.
 * CLIENT SECURITY LIMITATION: unauthenticated read access to /keys/$key must be enabled
 * in rules for this approach. Eight-digit keys are guessable; anti-brute-force in the
 * app cannot stop external scripts. Use a trusted server for strong protection.
 */
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
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
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
        // All strings are XOR-obfuscated in Obfuscated.java and decrypted only when needed.
        final String url = Obfuscated.baseUrl() + Obfuscated.endpoint() + key + ".json";
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Snapshot snapshot = load(url);
                if (snapshot.value == null) {
                    return rejected("Invalid License Key!", true);
                }
                JSONObject record = snapshot.value;
                String status = record.optString(Obfuscated.status(), "");
                String type = record.optString(Obfuscated.type(), "");
                String expiry = record.optString(Obfuscated.expiry(), "");
                String registered = record.optString(Obfuscated.deviceId(), "").trim();

                if (Obfuscated.hold().equals(status)) {
                    return rejected("Your Key is on HOLD (Locked).", false);
                }
                if (Obfuscated.used().equals(status)) {
                    return rejected("This One-Time Key has already been used.", false);
                }
                if (!Obfuscated.active().equals(status)) {
                    return rejected("License Key is not ACTIVE.", false);
                }

                try {
                    LocalDate end = LocalDate.parse(expiry);
                    if (LocalDate.now(ZoneId.systemDefault()).isAfter(end)) {
                        return rejected("Your License Key has Expired.", false);
                    }
                } catch (DateTimeParseException invalidDate) {
                    return rejected("Invalid license expiry date.", false);
                }

                if (Obfuscated.deviceLock().equals(type)) {
                    if (!registered.isEmpty()) {
                        if (!registered.equalsIgnoreCase(device)) {
                            return rejected("This Key is already registered on another mobile.", false);
                        }
                        return success(type, expiry); // one fast GET per relaunch
                    }
                    if (savedDeviceLockRecheck) {
                        return rejected("Saved license device binding was removed.", false);
                    }
                    JSONObject updated = new JSONObject(record.toString());
                    updated.put(Obfuscated.deviceId(), device);
                    int responseCode = commit(url, snapshot.etag, updated);
                    if (responseCode == 200) return success(type, expiry);
                    if (responseCode == 412) continue; // simultaneous device claim; retry GET
                    if (responseCode == 401 || responseCode == 403) {
                        return blocked("Firebase activation denied by database Rules.");
                    }
                    return blocked("Firebase activation failed (HTTP " + responseCode + ").");
                }

                if (Obfuscated.oneTime().equals(type)) {
                    if (savedDeviceLockRecheck) {
                        return rejected("Saved license is not a DEVICE_LOCK key.", false);
                    }
                    JSONObject updated = new JSONObject(record.toString());
                    updated.put(Obfuscated.status(), Obfuscated.used());
                    updated.put(Obfuscated.deviceId(), device);
                    int responseCode = commit(url, snapshot.etag, updated);
                    if (responseCode == 200) return success(type, expiry);
                    if (responseCode == 412) continue;
                    if (responseCode == 401 || responseCode == 403) {
                        return blocked("Firebase activation denied by database Rules.");
                    }
                    return blocked("Firebase activation failed (HTTP " + responseCode + ").");
                }
                return rejected("Unsupported license type.", false);
            }
            return blocked("License was changed during login. Try again.");
        } catch (HttpFailure error) {
            if (error.status == 401 || error.status == 403) {
                return blocked("Firebase access denied. Check Realtime Database Rules.");
            }
            return blocked("Firebase error (HTTP " + error.status + "). Try again.");
        } catch (Exception error) {
            return blocked("Could not verify license. Check internet and try again.");
        }
    }

    private static Snapshot load(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url).header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .header("X-Firebase-ETag", "true").get().build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (response.code() != 200) throw new HttpFailure(response.code());
            if (response.body() == null) throw new IOException("Empty Firebase body");
            String text = response.body().string();
            if (text.length() > 65536) throw new IOException("Firebase body too large");
            String etag = response.header("ETag");
            if (etag == null || etag.isEmpty()) throw new IOException("Missing Firebase ETag");
            JSONObject body = text.trim().equals("null") || text.trim().isEmpty()
                    ? null : new JSONObject(text);
            return new Snapshot(body, etag);
        }
    }

    private static int commit(String url, String etag, JSONObject updated) throws Exception {
        RequestBody body = RequestBody.create(updated.toString(),
                MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url).header("If-Match", etag)
                .header("Cache-Control", "no-store")
                .put(body).build();
        try (Response response = HTTP.newCall(request).execute()) {
            return response.code();
        }
    }

    private static final class Snapshot {
        final JSONObject value;
        final String etag;
        Snapshot(JSONObject value, String etag) {
            this.value = value;
            this.etag = etag;
        }
    }

    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) {
            super("HTTP error");
            this.status = status;
        }
    }
}
