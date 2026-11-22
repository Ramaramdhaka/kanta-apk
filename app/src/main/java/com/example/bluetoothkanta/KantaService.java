package com.example.bluetoothkanta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the Bluetooth RFCOMM sockets independently of MainActivity's lifecycle.
 * An authorized foreground session keeps sending while users switch applications.
 */
public final class KantaService extends Service {
    static final String ACTION_RUN = "com.example.bluetoothkanta.RUN";
    static final String ACTION_STOP = "com.example.bluetoothkanta.STOP_SERVICE";
    private static final String CHANNEL = "jg_bluetooth_foreground";
    private static final int NOTIFICATION_ID = 2118;
    private static final int BG = Color.rgb(9, 17, 30);
    private static final int PANEL = Color.rgb(19, 31, 49);
    private static final int FIELD = Color.rgb(30, 47, 69);
    private static final int TEAL = Color.rgb(30, 218, 190);
    private static final int WHITE = Color.rgb(242, 250, 255);
    private static final int MUTED = Color.rgb(151, 172, 194);
    private static volatile KantaService live;

    interface UiListener { void onState(String status, int connected, long sent, String log); }
    final class LocalBinder extends Binder { KantaService service() { return KantaService.this; } }

    static KantaService active() { return live; }

    private final LocalBinder binder = new LocalBinder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService checker = Executors.newSingleThreadExecutor();
    private BluetoothController transport;
    private SharedPreferences prefs;
    private UiListener listener;
    private String latestStatus = "No receivers connected";
    private String latestLog = "";
    private long latestFrames;
    private int latestConnected;
    private boolean authorized;
    private boolean foregroundStarted;
    private boolean activityVisible = true;
    private String sessionKey;
    private String sessionType;
    private WindowManager manager;
    private WindowManager.LayoutParams bubbleParams;
    private View bubble;
    private LinearLayout expandedArea;
    private TextView bubbleStatus;
    private EditText bubbleWeight;
    private boolean bubbleCollapsed;
    private boolean updatingWeight;
    private final Runnable periodicRecheck = new Runnable() {
        @Override public void run() {
            if (!authorized || sessionKey == null || !Obfuscated.deviceLock().equals(sessionType)) return;
            final String key = sessionKey;
            checker.execute(() -> {
                LicenseVerifier.Result result = LicenseVerifier.verify(getApplicationContext(), key, true);
                ui.post(() -> {
                    if (!authorized || !key.equals(sessionKey)) return;
                    if (!result.valid) {
                        // HOLD, expiry, mismatched device and offline all fail closed.
                        try {
                            new LicenseStore(KantaService.this).clear();
                        } catch (Exception ignored) { }
                        Toast.makeText(KantaService.this, result.message, Toast.LENGTH_LONG).show();
                        endSession();
                    } else {
                        ui.postDelayed(periodicRecheck, 15L * 60L * 1000L);
                    }
                });
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        live = this;
        prefs = getSharedPreferences("jg_ui", MODE_PRIVATE);
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        transport = new BluetoothController(adapter, (status, connected, sent, log) -> {
            latestStatus = status;
            latestConnected = connected;
            latestFrames = sent;
            latestLog = log;
            if (listener != null) listener.onState(status, connected, sent, log);
            if (bubbleStatus != null) {
                bubbleStatus.setText(status + " • Frames " + sent);
                bubbleStatus.setTextColor(connected > 0 ? TEAL : MUTED);
            }
        });
        transport.setIntervalMs(prefs.getInt("interval_ms", 100));
        transport.setServiceName(prefs.getString("service_name", "JG"));
        updateWeight(prefs.getString("weight", "20"));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "JG Bluetooth weighing service", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            endSession();
            return START_NOT_STICKY;
        }
        if (!foregroundStarted) {
            try {
                Notification notification = notification();
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                foregroundStarted = true;
            } catch (Exception ex) {
                // Never continue a detached Bluetooth service without a valid foreground notification.
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;  // Don't auto-restart without a verified license.
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    BluetoothController controller() { return transport; }

    void attach(UiListener observer) {
        listener = observer;
        if (listener != null) listener.onState(latestStatus, latestConnected, latestFrames, latestLog);
    }

    void detach(UiListener observer) {
        if (listener == observer) listener = null;
    }

    void setActivityVisible(boolean visible) {
        activityVisible = visible;
        if (visible) hideOverlay();
        else showOverlay();
    }

    void authorize(String key, String type) {
        if (key == null || !key.matches("[0-9]{8}")) return;
        authorized = true;
        sessionKey = key;
        sessionType = type;
        ui.removeCallbacks(periodicRecheck);
        if (Obfuscated.deviceLock().equals(type)) {
            ui.postDelayed(periodicRecheck, 15L * 60L * 1000L);
        }
        if (!activityVisible) showOverlay();
    }

    private boolean maySend() {
        if (!authorized || transport == null) {
            Toast.makeText(this, "Verify your JG license first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    void waitForReceiver() {
        if (maySend()) transport.waitForIncoming();
    }

    void startSending() {
        if (!maySend()) return;
        if (transport.getActiveCount() == 0 && !transport.isListening()) {
            transport.waitForIncoming();
        }
        transport.startSending();
    }

    void stopSending() {
        if (transport != null) transport.stopSending();
    }

    boolean updateWeight(String text) {
        if (transport == null) return false;
        try {
            double kg = Double.parseDouble(text.trim());
            if (!Double.isFinite(kg) || kg < 0 || kg > 999.999) throw new IllegalArgumentException();
            String frame = String.format(Locale.US, "WT%07.3fKG ", kg);
            if (frame.getBytes(StandardCharsets.US_ASCII).length != 12) throw new IllegalArgumentException();
            transport.setPacket(frame);
            prefs.edit().putString("weight", text).apply();
            return true;
        } catch (Exception invalidWeight) {
            transport.setPacket(null);
            return false;
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, KantaService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("JG Kanta • Bluetooth active")
                .setContentText("Switch apps freely • Tap to return to JG")
                .setSmallIcon(R.drawable.jg_logo)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "STOP SERVICE", stopPending)
                .build();
    }

    private void endSession() {
        authorized = false;
        sessionKey = null;
        sessionType = null;
        ui.removeCallbacks(periodicRecheck);
        hideOverlay();
        if (transport != null) transport.shutdown();
        transport = null;
        listener = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        ui.removeCallbacks(periodicRecheck);
        hideOverlay();
        if (transport != null) transport.shutdown();
        transport = null;
        checker.shutdownNow();
        if (live == this) live = null;
        super.onDestroy();
    }

    private void showOverlay() {
        if (activityVisible || !authorized || bubble != null || !Settings.canDrawOverlays(this)) return;
        try {
            manager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (manager == null) return;
            buildOverlay();
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            bubbleParams = new WindowManager.LayoutParams(dp(314), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
            bubbleParams.gravity = Gravity.TOP | Gravity.END;
            bubbleParams.x = prefs.getInt("popup_x", dp(12));
            bubbleParams.y = prefs.getInt("popup_y", dp(120));
            manager.addView(bubble, bubbleParams);
        } catch (Exception ignored) {
            bubble = null;
            bubbleWeight = null;
            bubbleStatus = null;
            expandedArea = null;
        }
    }

    private void hideOverlay() {
        if (bubble != null && manager != null) {
            try { manager.removeViewImmediate(bubble); } catch (Exception ignored) { }
        }
        bubble = null;
        bubbleWeight = null;
        bubbleStatus = null;
        expandedArea = null;
        bubbleParams = null;
    }

    private void buildOverlay() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.setBackground(round(PANEL));
        root.setElevation(dp(12));
        bubble = root;
        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header);
        TextView title = text("⠿  JG", 19, TEAL);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1f));
        Button openApp = button("APP", FIELD, WHITE);
        header.addView(openApp, new LinearLayout.LayoutParams(dp(60), dp(38)));
        openApp.setOnClickListener(v -> {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        });
        Button fold = button("−", FIELD, WHITE);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(40), dp(38));
        fp.leftMargin = dp(5);
        header.addView(fold, fp);
        fold.setOnClickListener(v -> {
            bubbleCollapsed = !bubbleCollapsed;
            expandedArea.setVisibility(bubbleCollapsed ? View.GONE : View.VISIBLE);
            fold.setText(bubbleCollapsed ? "+" : "−");
            if (bubbleParams != null && manager != null) {
                bubbleParams.width = dp(bubbleCollapsed ? 156 : 314);
                manager.updateViewLayout(bubble, bubbleParams);
            }
        });
        title.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (bubbleParams == null || manager == null) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    // END gravity means increasing x moves panel toward the left.
                    bubbleParams.x = Math.max(0, startX + Math.round(downX - event.getRawX()));
                    bubbleParams.y = Math.max(0, startY + Math.round(event.getRawY() - downY));
                    try { manager.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) { }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    prefs.edit().putInt("popup_x", bubbleParams.x).putInt("popup_y", bubbleParams.y).apply();
                    return true;
                }
                return true;
            }
        });
        expandedArea = new LinearLayout(this);
        expandedArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(expandedArea);
        TextView intro = text("WEIGHT (KG)", 11, MUTED);
        expandedArea.addView(intro);
        bubbleWeight = new EditText(this);
        bubbleWeight.setTextSize(23);
        bubbleWeight.setTextColor(WHITE);
        bubbleWeight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        bubbleWeight.setSingleLine(true);
        bubbleWeight.setGravity(Gravity.CENTER);
        bubbleWeight.setBackground(round(FIELD));
        expandedArea.addView(bubbleWeight, new LinearLayout.LayoutParams(-1, dp(47)));
        bubbleWeight.setText(prefs.getString("weight", "20"));
        bubbleWeight.setOnClickListener(v -> focusWeight());
        bubbleWeight.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focusWeight();
        });
        bubbleWeight.setOnEditorActionListener((v, actionId, event) -> {
            blurWeight(); return false;
        });
        bubbleWeight.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {}
            @Override public void afterTextChanged(Editable e) {
                if (!updatingWeight) updateWeight(e.toString());
            }
        });
        LinearLayout weightRows = new LinearLayout(this);
        weightRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rows = new LinearLayout.LayoutParams(-1, -2);
        rows.topMargin = dp(6);
        expandedArea.addView(weightRows, rows);
        final int[] choices = {5,10,15,20,25,30,35,40,45,50};
        for (int y = 0; y < 2; y++) {
            LinearLayout line = new LinearLayout(this);
            weightRows.addView(line);
            for (int x = 0; x < 5; x++) {
                String value = String.valueOf(choices[y * 5 + x]);
                Button quick = button(value, FIELD, WHITE);
                quick.setTextSize(13);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
                p.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(quick, p);
                quick.setOnClickListener(v -> {
                    blurWeight();
                    if (bubbleWeight == null) return;
                    updatingWeight = true;
                    bubbleWeight.setText(value);
                    updatingWeight = false;
                    updateWeight(value);
                });
            }
        }
        bubbleStatus = text(latestStatus, 12, MUTED);
        bubbleStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(38));
        expandedArea.addView(bubbleStatus, statusParams);
        final LinearLayout actions = new LinearLayout(this);
        expandedArea.addView(actions);
        String[] captions = {"WAIT", "START", "STOP"};
        for (int i = 0; i < captions.length; i++) {
            final int index = i;
            Button action = button(captions[i], i == 1 ? TEAL : FIELD, i == 1 ? BG : WHITE);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(45), 1f);
            bp.setMargins(dp(2), 0, dp(2), 0);
            actions.addView(action, bp);
            action.setOnClickListener(v -> {
                blurWeight();
                if (index == 0) waitForReceiver();
                else if (index == 1) startSending();
                else stopSending();
            });
        }
        if (bubbleCollapsed) expandedArea.setVisibility(View.GONE);
        bubbleStatus.setText(latestStatus + " • Frames " + latestFrames);
    }

    private void focusWeight() {
        if (bubble == null || bubbleParams == null || manager == null || bubbleWeight == null) return;
        bubbleParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            manager.updateViewLayout(bubble, bubbleParams);
            bubbleWeight.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(bubbleWeight, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) { }
    }
    private void blurWeight() {
        if (bubbleParams == null || manager == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && bubbleWeight != null) imm.hideSoftInputFromWindow(bubbleWeight.getWindowToken(), 0);
        if (bubbleWeight != null) bubbleWeight.clearFocus();
        bubbleParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { manager.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) { }
    }
    private int dp(float x) { return Math.round(getResources().getDisplayMetrics().density * x); }
    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(13)); return d;
    }
    private TextView text(String label, int size, int color) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private Button button(String label, int color, int textColor) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(13);
        b.setTextColor(textColor); b.setPadding(0,0,0,0);
        b.setBackground(round(color)); return b;
    }
}
