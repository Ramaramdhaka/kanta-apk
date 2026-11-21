package com.example.bluetoothkanta;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Client-side Firebase REST prototype. Requires a protected server endpoint for secure production use. */
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

    private static Result ok(String type, String expiry) {
        return new Result(true, false, false, "License verified successfully.", type, expiry);
    }
    private static Result invalid(String message) {
        return new Result(false, true, true, message, "", "");
    }
    private static Result error(String message) {
        return new Result(false, false, false, message, "", "");
    }

    public static Result verify(Context context, String key, boolean savedDeviceLockRecheck) {
        if (key == null || !key.matches("[0-9]{8}")) return invalid("Invalid License Key!");
        if (!NetUtil.hasInternet(context)) return error("Internet connection required to verify license");
        String device = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (device == null || device.isEmpty()) return error("Unable to obtain Android Device ID.");
        try {
            String endpoint = Obfuscated.baseUrl() + Obfuscated.endpoint() + key + ".json";
            JSONObject data = getLicense(endpoint);
            if (data == null) return invalid("Invalid License Key!");
            String status = data.optString(Obfuscated.status(), "");
            String type = data.optString(Obfuscated.type(), "");
            String boundDevice = data.optString(Obfuscated.deviceId(), "");
            String expiry = data.optString(Obfuscated.expiry(), "");
            if (Obfuscated.hold().equals(status)) return invalid("Your Key is on HOLD (Locked).");
            if (Obfuscated.used().equals(status)) return invalid("This One-Time Key has already been used.");
            if (!Obfuscated.active().equals(status)) return invalid("License Key is not ACTIVE.");
            if (expiry.isEmpty()) return invalid("License expiry date is missing.");
            final LocalDate expires;
            try { expires = LocalDate.parse(expiry); }
            catch (DateTimeParseException e) { return invalid("Invalid license expiry date."); }
            if (LocalDate.now(ZoneId.systemDefault()).isAfter(expires))
                return invalid("Your License Key has Expired.");
            if (Obfuscated.oneTime().equals(type)) {
                if (savedDeviceLockRecheck) return invalid("Saved license is not DEVICE_LOCK.");
                JSONObject patch = new JSONObject();
                patch.put(Obfuscated.status(), Obfuscated.used());
                patch.put(Obfuscated.deviceId(), device);
                JSONObject updated = patchLicense(endpoint, patch);
                if (!Obfuscated.used().equals(updated.optString(Obfuscated.status(), "")) ||
                        !device.equals(updated.optString(Obfuscated.deviceId(), ""))) {
                    return error("Unable to confirm one-time key usage.");
                }
                return ok(Obfuscated.oneTime(), expiry);
            }
            if (Obfuscated.deviceLock().equals(type)) {
                if (boundDevice.isEmpty()) {
                    if (savedDeviceLockRecheck) return invalid("License device binding is missing.");
                    JSONObject patch = new JSONObject();
                    patch.put(Obfuscated.deviceId(), device);
                    JSONObject updated = patchLicense(endpoint, patch);
                    if (!device.equals(updated.optString(Obfuscated.deviceId(), ""))) {
                        return error("Could not register this device.");
                    }
                    JSONObject after = getLicense(endpoint);
                    if (after == null || !device.equals(after.optString(Obfuscated.deviceId(), ""))) {
                        return invalid("This Key is already registered on another mobile.");
                    }
                    if (!Obfuscated.active().equals(after.optString(Obfuscated.status(), ""))) {
                        return invalid("License is no longer ACTIVE.");
                    }
                } else if (!device.equals(boundDevice)) {
                    return invalid("This Key is already registered on another mobile.");
                }
                return ok(Obfuscated.deviceLock(), expiry);
            }
            return invalid("Unsupported license type.");
        } catch (HttpFailure e) {
            if (e.code == 401 || e.code == 403) {
                return error("Firebase access denied. Check database Security Rules.");
            }
            return error("License server error (HTTP " + e.code + "). Try again.");
        } catch (Exception e) {
            return error("Could not verify license. Check internet and try again.");
        }
    }

    private static JSONObject getLicense(String url) throws Exception {
        String body = request("GET", url, null);
        if (body == null || body.trim().isEmpty() || "null".equals(body.trim())) return null;
        return new JSONObject(body);
    }

    private static JSONObject patchLicense(String url, JSONObject value) throws Exception {
        return new JSONObject(request("PATCH", url, value.toString()));
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false).build();

    private static String request(String method, String url, String body) throws Exception {
        URL current = new URL(url);
        final String expectedHost = new URL(Obfuscated.baseUrl()).getHost();
        for (int redirect = 0; redirect <= 3; redirect++) {
            if (!"https".equalsIgnoreCase(current.getProtocol()) ||
                    !expectedHost.equalsIgnoreCase(current.getHost())) {
                throw new IOException("Untrusted redirect blocked");
            }
            Request.Builder builder = new Request.Builder().url(current.toString())
                    .header("Accept", "application/json").header("Cache-Control", "no-store");
            if ("GET".equals(method)) builder.get();
            else builder.patch(RequestBody.create(body, MediaType.parse("application/json; charset=utf-8")));
            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                int code = response.code();
                if (code == 301 || code == 302 || code == 307 || code == 308) {
                    String location = response.header("Location");
                    if (location == null) throw new IOException("Redirect has no Location");
                    current = new URL(current, location);
                    continue;
                }
                if (code < 200 || code >= 300) throw new HttpFailure(code);
                if (response.body() == null) throw new IOException("No response body");
                try (InputStream in = response.body().byteStream()) {
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int total = 0, count;
                    while ((count = in.read(buf)) != -1) {
                        total += count;
                        if (total > 65536) throw new IOException("Response is too large");
                        sink.write(buf, 0, count);
                    }
                    return sink.toString("UTF-8");
                }
            }
        }
        throw new IOException("Too many redirects");
    }

    private static final class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code) { super("HTTP error"); this.code = code; }
    }
}
