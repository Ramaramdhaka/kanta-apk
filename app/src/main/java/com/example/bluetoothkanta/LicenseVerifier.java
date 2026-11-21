package com.example.bluetoothkanta;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Secure boundary: Android never uses Firebase Database read/write credentials.
 * The configured Google Apps Script backend performs authoritative license checks.
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

    private static Result failed(String message, boolean rejected, boolean badAttempt) {
        return new Result(false, rejected, badAttempt, message, "", "");
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    public static Result verify(Context context, String key, boolean savedDeviceLockRecheck) {
        if (key == null || !key.matches("[0-9]{8}"))
            return failed("Enter an 8-digit numeric License Key", false, false);
        if (!NetUtil.hasInternet(context))
            return failed("Internet connection required to verify license", false, false);

        final String endpoint = BuildConfig.LICENSE_API_URL;
        if (endpoint == null || !endpoint.startsWith("https://script.google.com/macros/s/")
                || !endpoint.endsWith("/exec")) {
            return failed("License backend is not configured. Contact JG support.", false, false);
        }
        final String deviceId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId == null || !deviceId.matches("[a-fA-F0-9]{8,64}"))
            return failed("Unable to read Android Device ID.", false, false);

        try {
            JSONObject request = new JSONObject();
            request.put("key", key);
            request.put("deviceId", deviceId);
            URL current = new URL(endpoint);
            boolean sendPost = true;

            for (int redirects = 0; redirects <= 4; redirects++) {
                String host = current.getHost().toLowerCase(Locale.US);
                if (!"https".equalsIgnoreCase(current.getProtocol()) ||
                        !(host.equals("script.google.com") || host.equals("script.googleusercontent.com"))) {
                    return failed("Blocked unsafe license API redirect.", false, false);
                }

                Request.Builder builder = new Request.Builder().url(current.toString())
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-store");
                if (sendPost) {
                    RequestBody body = RequestBody.create(request.toString(),
                            MediaType.parse("application/json; charset=utf-8"));
                    builder.post(body);
                } else {
                    builder.get();
                }

                try (Response response = HTTP.newCall(builder.build()).execute()) {
                    final int code = response.code();
                    if (code == 301 || code == 302 || code == 303) {
                        String location = response.header("Location");
                        if (location == null || location.isEmpty())
                            return failed("License API redirect missing Location.", false, false);
                        current = new URL(current, location);
                        sendPost = false;
                        continue;
                    }
                    if (code == 307 || code == 308) {
                        String location = response.header("Location");
                        if (location == null || location.isEmpty())
                            return failed("License API redirect missing Location.", false, false);
                        current = new URL(current, location);
                        continue;
                    }
                    if (code < 200 || code >= 300 || response.body() == null) {
                        return failed("License server unavailable (HTTP " + code + ").", false, false);
                    }
                    String body = response.body().string();
                    if (body.length() > 65536)
                        return failed("License server response too large.", false, false);
                    JSONObject answer = new JSONObject(body);
                    String message = answer.optString("message", "License verification failed.");
                    boolean valid = answer.optBoolean("valid", false);
                    if (!valid) {
                        return failed(message, !answer.optBoolean("retryable", false),
                                answer.optBoolean("badAttempt", false));
                    }
                    String type = answer.optString("type", "");
                    String expiry = answer.optString("expiry", "");
                    if (!"DEVICE_LOCK".equals(type) && !"ONE_TIME".equals(type))
                        return failed("Invalid license type from server.", false, false);
                    if (savedDeviceLockRecheck && !"DEVICE_LOCK".equals(type))
                        return failed("Saved license is not DEVICE_LOCK.", true, false);
                    if (!expiry.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                        return failed("Invalid license expiry from server.", false, false);
                    return new Result(true, false, false, message, type, expiry);
                }
            }
            return failed("Too many license API redirects.", false, false);
        } catch (Exception ignored) {
            return failed("Could not verify license. Check internet and try again.", false, false);
        }
    }
}
