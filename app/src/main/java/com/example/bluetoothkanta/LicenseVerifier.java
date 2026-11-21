package com.example.bluetoothkanta;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Verifies every opening and resume online. No offline bypass. */
public final class LicenseVerifier {
    // WARNING: A secret embedded in an APK is extractable. Move signing to a server
    // with asymmetric response signatures for production anti-tamper protection.
    private static final String API_URL = "https://script.google.com/macros/s/AKfycbz31qN4KS3702BtwfttCHCIK_m6OgnWe24NQiOXoYolNUlhNgaXW5PdNpJ5-dIY6oDE/exec";
    private static final String SHARED_SECRET = "JG_SECRET_KEY_2026_99";

    public static final class Result {
        public final boolean valid;
        public final boolean serverRejected;
        public final String message;
        public final String type;
        public final String expiry;
        Result(boolean valid, boolean serverRejected, String message, String type, String expiry) {
            this.valid = valid;
            this.serverRejected = serverRejected;
            this.message = message;
            this.type = type;
            this.expiry = expiry;
        }
    }

    private LicenseVerifier() { }

    public static Result verify(Context context, String key) {
        if (key == null || !key.matches("[0-9]{8}")) {
            return new Result(false, false, "Enter an 8-digit numeric License Key", "", "");
        }
        if (!NetUtil.hasInternet(context)) {
            return new Result(false, false, "Internet connection required to verify license", "", "");
        }
        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId == null || deviceId.isEmpty()) {
            return new Result(false, false, "Unable to read Device ID", "", "");
        }
        long ts = System.currentTimeMillis() / 1000L;
        String signature = sha256(key + "|" + deviceId + "|" + ts + "|" + SHARED_SECRET);
        String expectedToken = sha256("VALID|" + key + "|" + deviceId + "|" + ts + "|" + SHARED_SECRET);
        HttpURLConnection connection = null;
        try {
            String requestUrl = API_URL + "?action=verify&key=" + enc(key)
                    + "&device=" + enc(deviceId) + "&ts=" + ts + "&sig=" + signature;
            URL current = new URL(requestUrl);
            // Google Apps Script's ContentService responds with HTTP 302 to googleusercontent.com.
            // Explicitly follow up to 6 HTTPS redirects, never forwarding data to other domains.
            for (int i = 0; i < 7; i++) {
                if (!"https".equalsIgnoreCase(current.getProtocol()) || !allowedHost(current.getHost())) {
                    return new Result(false, false, "Blocked unsafe API redirect", "", "");
                }
                connection = (HttpURLConnection) current.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.trim().isEmpty()) {
                        return new Result(false, false, "API redirect missing Location", "", "");
                    }
                    current = new URL(current, location);
                    continue;
                }
                InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
                String text = readText(stream);
                connection.disconnect();
                connection = null;
                JSONObject reply;
                try { reply = new JSONObject(text); }
                catch (Exception invalidJson) {
                    return new Result(false, false, "API returned an invalid response (HTTP " + code + ")", "", "");
                }
                String message = reply.optString("message", "License verification failed");
                boolean valid = reply.optBoolean("valid", false);
                if (!valid) {
                    // Show exact server message; do not overwrite it with a generic error.
                    return new Result(false, true, message, "", "");
                }
                String type = reply.optString("type", "");
                String expiry = reply.optString("expiry", "");
                String token = reply.optString("token", "");
                boolean authenticated = MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        expectedToken.getBytes(StandardCharsets.UTF_8));
                if (!authenticated) {
                    return new Result(false, false, "Invalid server verification token", "", "");
                }
                if (!"DEVICE_LOCK".equals(type) && !"ONE_TIME".equals(type)) {
                    return new Result(false, false, "Unsupported license type", "", "");
                }
                return new Result(true, false, message, type, expiry);
            }
            return new Result(false, false, "Too many API redirects", "", "");
        } catch (Exception e) {
            // Never print key/signature/secret/request URL in logs or error messages.
            return new Result(false, false, "Could not verify license: " + e.getClass().getSimpleName()
                    + ". Check internet and try again.", "", "");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean allowedHost(String host) {
        String h = host.toLowerCase(Locale.US);
        return h.equals("script.google.com") || h.equals("script.googleusercontent.com");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String readText(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = stream.read(b)) >= 0) {
                if (n > 0) result.write(b, 0, n);
                if (result.size() > 256 * 1024) throw new IllegalArgumentException("Response too large");
            }
            return result.toString("UTF-8");
        }
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            char[] chars = "0123456789abcdef".toCharArray();
            char[] hex = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = chars[(bytes[i] >>> 4) & 0xF];
                hex[i * 2 + 1] = chars[bytes[i] & 0xF];
            }
            return new String(hex);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
