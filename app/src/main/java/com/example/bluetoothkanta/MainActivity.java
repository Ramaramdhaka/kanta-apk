package com.example.bluetoothkanta;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** JG Kanta • Main screen + PIN settings + online, fail-closed license gate. */
public class MainActivity extends Activity {
    private static final int RC_BLUETOOTH = 31;
    private static final int RC_DISCOVERABLE = 32;
    private static final int BG = Color.rgb(8, 15, 28);
    private static final int PANEL = Color.rgb(18, 29, 47);
    private static final int FIELD = Color.rgb(27, 41, 62);
    private static final int TEAL = Color.rgb(31, 218, 195);
    private static final int WHITE = Color.rgb(243, 249, 255);
    private static final int MUTED = Color.rgb(148, 168, 192);
    private static final int RED = Color.rgb(255, 112, 124);
    private static final int GREEN = Color.rgb(70, 230, 167);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<BluetoothDevice> paired = new ArrayList<>();
    private SharedPreferences prefs;
    private LicenseStore licenseStore;
    private BluetoothAdapter bluetooth;
    private BluetoothController transport;
    private boolean working = false;
    private boolean licensed = false;
    private boolean wasBackgrounded = false;
    private boolean settingsShowing = false;
    private boolean destroyed = false;
    private String activeKey;
    private String licenseType = "";
    private String expiry = "";

    private TextView mainStatus, sentCount, frameLabel, settingsLog, realNameLabel, loginError;
    private EditText weightEdit, intervalEdit, deviceNameEdit;
    private Spinner receiver1, receiver2;
    private Button loginButton;
    private ProgressBar loginSpinner;
    private int savedInterval = 100;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!SecurityGuard.enforce(this)) return;
        prefs = getSharedPreferences("jg_ui", MODE_PRIVATE);
        try {
            licenseStore = new LicenseStore(this);
        } catch (Exception storageFailure) {
            LinearLayout problem = base(Gravity.CENTER);
            title(problem, "JG", 42, Gravity.CENTER);
            label(problem, "Secure license storage unavailable. Please reinstall or contact support.",
                    16, RED, Gravity.CENTER);
            setContentView(problem);
            return;
        }
        BluetoothManager mgr = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetooth = mgr == null ? null : mgr.getAdapter();
        savedInterval = prefs.getInt("interval_ms", 100);
        String saved = licenseStore.getDeviceLockKey();
        if (saved == null) showLogin(null);
        else checkLicense(saved, true);
    }

    @Override protected void onStart() {
        super.onStart();
        // Returning from Home, Recents, or Bluetooth Settings counts as reopening.
        // Recheck online. A ONE_TIME key lives only in memory within this process.
        if (wasBackgrounded && licensed && activeKey != null && Obfuscated.deviceLock().equals(licenseType)) {
            wasBackgrounded = false;
            stopTransport();
            checkLicense(activeKey, true);
        } else if (wasBackgrounded) {
            // ONE_TIME remains valid during this same live app session only.
            wasBackgrounded = false;
        }
    }

    @Override protected void onStop() {
        if (licensed) wasBackgrounded = true;
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopTransport();
        network.shutdownNow();
        super.onDestroy();
    }

    private void stopTransport() {
        if (transport != null) { transport.shutdown(); transport = null; }
    }

    private void checkLicense(String key, boolean silent) {
        if (working) return;
        working = true;
        licensed = false;  // Important: never grant access until fresh verification succeeds.
        if (silent) {
            showSplash("Verifying your JG license...");
        } else {
            // Keep login UI visible so the requested loading spinner remains visible.
            if (loginButton != null) loginButton.setEnabled(false);
            if (loginSpinner != null) loginSpinner.setVisibility(View.VISIBLE);
            if (loginError != null) { loginError.setText("Checking license..."); loginError.setTextColor(MUTED); }
        }
        network.execute(() -> {
            LicenseVerifier.Result result = LicenseVerifier.verify(getApplicationContext(), key, silent);
            main.post(() -> {
                if (destroyed) return;
                working = false;
                if (result.valid) {
                    licenseStore.resetFailures();
                    activeKey = key;
                    licenseType = result.type;
                    expiry = result.expiry;
                    if (Obfuscated.deviceLock().equals(result.type)) {
                        try { licenseStore.saveDeviceLockKey(key); }
                        catch (Exception e) {
                            activeKey = null;
                            showLogin("Cannot securely save license: " + e.getMessage());
                            return;
                        }
                    } else {
                        // ONE_TIME never written to prefs. It expires when process restarts.
                        licenseStore.clear();
                    }
                    licensed = true;
                    showMain();
                    if (!silent && result.message != null && !result.message.isEmpty()) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                    }
                } else {
                    if (!silent && result.badKeyAttempt) licenseStore.failAttempt();
                    stopTransport();
                    activeKey = null;
                    licensed = false;
                    if (result.serverRejected) {
                        licenseStore.clear(); // HOLD, expiry, revoked -> force login.
                        showLogin(loginErrorText(result.message));
                    } else if (silent) {
                        // Offline, network error, invalid response token: fail closed, retry only.
                        showBlocked(result.message, key);
                    } else {
                        showLogin(loginErrorText(result.message));
                    }
                }
            });
        });
    }

    private String loginErrorText(String message) {
        return licenseStore.lockRemainingMs() > 0
                ? "Too many wrong attempts! Try again after 10 minutes." : message;
    }

    private void showSplash(String label) {
        LinearLayout root = base(Gravity.CENTER);
        ImageView logo = logo(104);
        root.addView(logo);
        space(root, 20);
        title(root, "JG", 42, Gravity.CENTER);
        space(root, 16);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TEAL));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(dp(36), dp(36));
        bar.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(spinner, bar);
        space(root, 15);
        label(root, label, 15, MUTED, Gravity.CENTER);
        setContentView(root);
    }

    private void showBlocked(String error, String key) {
        LinearLayout root = base(Gravity.CENTER);
        root.addView(logo(92));
        space(root, 18);
        title(root, "JG", 40, Gravity.CENTER);
        space(root, 24);
        label(root, error, 16, RED, Gravity.CENTER);
        space(root, 25);
        action(root, "RETRY LICENSE CHECK", TEAL, BG, () -> checkLicense(key, true));
        space(root, 12);
        ghost(root, "USE A DIFFERENT KEY", () -> {
            licenseStore.clear();
            activeKey = null;
            showLogin(null);
        });
        setContentView(root);
    }

    private void showLogin(String error) {
        settingsShowing = false;
        LinearLayout content = base(Gravity.CENTER);
        content.addView(logo(105));
        space(content, 16);
        title(content, "JG", 43, Gravity.CENTER);
        space(content, 8);
        label(content, "SECURE LICENSE LOGIN", 12, MUTED, Gravity.CENTER);
        space(content, 30);
        LinearLayout card = panel();
        content.addView(card);
        label(card, "8-DIGIT LICENSE KEY", 12, MUTED, Gravity.START);
        space(card, 8);
        EditText keyEdit = input("12345678", InputType.TYPE_CLASS_NUMBER, "");
        keyEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        card.addView(keyEdit);
        space(card, 18);
        loginButton = action(card, "VERIFY & LOGIN", TEAL, BG, null);
        loginSpinner = new ProgressBar(this);
        loginSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TEAL));
        loginSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(30), dp(30));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(12);
        card.addView(loginSpinner, pp);
        loginError = label(card, error == null ? "Internet is required every time the app opens." : error,
                13, error == null ? MUTED : RED, Gravity.CENTER);
        loginError.setPadding(0, dp(10), 0, 0);
        space(content, 14);
        action(content, "KEY • BUY NOW", FIELD, WHITE, this::openLicensePurchase);
        space(content, 12);
        LinearLayout plans = panel();
        content.addView(plans);
        label(plans, "LICENSE KEY PRICES", 12, MUTED, Gravity.START);
        space(plans, 8);
        label(plans, "30 Days  —  ₹300", 16, WHITE, Gravity.START);
        space(plans, 7);
        label(plans, "1 Year      —  ₹1200", 16, WHITE, Gravity.START);
        space(plans, 7);
        label(plans, "Life Time  —  ₹2000", 16, WHITE, Gravity.START);
        long remainingLock = licenseStore.lockRemainingMs();
        if (remainingLock > 0) {
            loginButton.setEnabled(false);
            loginError.setText("Too many wrong attempts! Try again after 10 minutes.");
            loginError.setTextColor(RED);
            scheduleLockoutRefresh(loginButton, loginError);
        }
        loginButton.setOnClickListener(v -> {
            if (licenseStore.lockRemainingMs() > 0) {
                loginButton.setEnabled(false);
                loginError.setText("Too many wrong attempts! Try again after 10 minutes.");
                loginError.setTextColor(RED);
                scheduleLockoutRefresh(loginButton, loginError);
                return;
            }
            String typed = keyEdit.getText().toString().trim();
            if (!typed.matches("[0-9]{8}")) {
                loginError.setText("Enter an 8-digit numeric License Key");
                loginError.setTextColor(RED);
                return;
            }
            loginButton.setEnabled(false);
            loginSpinner.setVisibility(View.VISIBLE);
            checkLicense(typed, false);
        });
        setContentView(scroll(content));
        if (error != null && !error.isEmpty()) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private void openLicensePurchase() {
        String message = "Hello, I want to buy a JG Kanta License Key. " +
                "Plans: 30 Days - Rs 300, 1 Year - Rs 1200, Life Time - Rs 2000. " +
                "Please share payment details.";
        Uri link = Uri.parse("https://wa.me/918094251586?text=" + Uri.encode(message));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, link));
        } catch (ActivityNotFoundException e) {
            toast("Unable to open WhatsApp link. Please check your browser.");
        }
    }

    private void scheduleLockoutRefresh(Button button, TextView text) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed || loginButton != button || loginError != text) return;
                if (licenseStore.lockRemainingMs() <= 0) {
                    button.setEnabled(true);
                    text.setText("You can try again.");
                    text.setTextColor(MUTED);
                } else {
                    button.setEnabled(false);
                    text.setText("Too many wrong attempts! Try again after 10 minutes.");
                    main.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    private void showMain() {
        settingsShowing = false;
        if (transport == null) transport = new BluetoothController(bluetooth, (status, connected, sent, log) -> {
            if (mainStatus != null && !settingsShowing) {
                mainStatus.setText(status);
                mainStatus.setTextColor(connected > 0 ? GREEN : MUTED);
            }
            if (sentCount != null && !settingsShowing) sentCount.setText("Frames sent: " + sent);
            if (settingsLog != null && settingsShowing) settingsLog.setText(log);
        });
        transport.setIntervalMs(savedInterval);
        transport.setServiceName(prefs.getString("service_name", "JG"));
        LinearLayout content = base(Gravity.TOP);
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        content.addView(header, headerP);
        ImageView smallIcon = logo(44);
        header.addView(smallIcon);
        TextView name = new TextView(this);
        name.setText("JG");
        name.setTextSize(30);
        name.setTextColor(WHITE);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams nameP = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameP.leftMargin = dp(12);
        header.addView(name, nameP);
        Button settings = smallButton("⚙");
        header.addView(settings);
        settings.setOnClickListener(v -> openPinPrompt());

        space(content, 30);
        LinearLayout panel = panel();
        content.addView(panel);
        label(panel, "WEIGHT (KG)", 12, MUTED, Gravity.START);
        space(panel, 8);
        weightEdit = input("20", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                prefs.getString("weight", "20"));
        weightEdit.setTextSize(34);
        weightEdit.setGravity(Gravity.CENTER);
        panel.addView(weightEdit);
        space(panel, 12);
        addWeightShortcuts(panel);
        space(panel, 15);
        frameLabel = label(panel, "WT020.000KG ", 15, TEAL, Gravity.CENTER);
        weightEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateWeight(s.toString()); }
        });
        updateWeight(weightEdit.getText().toString());

        space(content, 26);
        mainStatus = label(content, "No receivers connected", 16, MUTED, Gravity.CENTER);
        sentCount = label(content, "Frames sent: 0", 12, MUTED, Gravity.CENTER);
        space(content, 22);
        action(content, "WAIT FOR CONNECTION", FIELD, WHITE, () -> {
            if (ensureBluetooth()) {
                transport.waitForIncoming();
                if (transport.getActiveCount() < 2) makeDiscoverable();
            }
        });
        space(content, 12);
        action(content, "▶   START", TEAL, BG, () -> {
            updateWeight(weightEdit.getText().toString());
            if (!ensureBluetooth()) return;
            if (transport.getActiveCount() == 0 && !transport.isListening()) {
                transport.waitForIncoming();
                toast("Waiting for an SPP receiver. Start will send when connected.");
            }
            transport.startSending();
        });
        space(content, 12);
        action(content, "■   STOP", PANEL, WHITE, () -> {
            if (transport != null) transport.stopSending();
        });
        space(content, 20);
        label(content, "Classic Bluetooth SPP   •   Up to 2 receivers", 12, MUTED, Gravity.CENTER);
        setContentView(scroll(content));
        ensureBluetooth();
    }

    private void addWeightShortcuts(LinearLayout parent) {
        final String[] shortcuts = {"5", "10", "15", "20", "25", "30", "35", "40", "45", "50"};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = horizontal();
            parent.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            for (int column = 0; column < 5; column++) {
                final String weight = shortcuts[row * 5 + column];
                Button choice = new Button(this);
                choice.setText(weight);
                choice.setAllCaps(false);
                choice.setTextSize(14);
                choice.setTextColor(WHITE);
                choice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                choice.setMinWidth(0);
                choice.setMinimumWidth(0);
                choice.setPadding(0, 0, 0, 0);
                choice.setBackground(shape(FIELD, Color.rgb(53, 71, 96)));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
                params.setMargins(dp(2), 0, dp(2), 0);
                line.addView(choice, params);
                choice.setOnClickListener(v -> {
                    weightEdit.setText(weight);
                    weightEdit.setSelection(weightEdit.getText().length());
                });
            }
            if (row == 0) space(parent, 5);
        }
    }

    private void updateWeight(String value) {
        try {
            double kg = Double.parseDouble(value.trim());
            if (!Double.isFinite(kg) || kg < 0 || kg > 999.999) throw new Exception("Range");
            String frame = String.format(Locale.US, "WT%07.3fKG ", kg);
            if (frame.getBytes(StandardCharsets.US_ASCII).length != 12) throw new Exception("Length");
            if (transport != null) transport.setPacket(frame);
            if (frameLabel != null) { frameLabel.setText("TX: " + frame + "  •  12 bytes"); frameLabel.setTextColor(TEAL); }
            prefs.edit().putString("weight", value).apply();
        } catch (Exception e) {
            if (transport != null) transport.setPacket(null);
            if (frameLabel != null) { frameLabel.setText("Enter 0 to 999.999 KG"); frameLabel.setTextColor(RED); }
        }
    }

    private void openPinPrompt() {
        if (!licensed) return;
        EditText pin = new EditText(this);
        pin.setHint("4-digit PIN");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        pin.setPadding(dp(20), dp(18), dp(20), dp(18));
        new AlertDialog.Builder(this)
                .setTitle("Settings locked")
                .setMessage("Enter settings password")
                .setView(pin)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("UNLOCK", (dialog, which) -> {
                    if ("1234".equals(pin.getText().toString())) showSettings();
                    else toast("Wrong settings password");
                }).show();
    }

    private void showSettings() {
        if (!licensed) return;
        settingsShowing = true;
        LinearLayout content = base(Gravity.TOP);
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(bar);
        Button back = smallButton("‹");
        bar.addView(back);
        back.setOnClickListener(v -> returnMain());
        TextView heading = new TextView(this);
        heading.setText("SETTINGS");
        heading.setTextSize(22);
        heading.setTextColor(WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        h.leftMargin = dp(12);
        bar.addView(heading, h);
        space(content, 20);

        LinearLayout license = panel();
        content.addView(license);
        section(license, "License");
        label(license, "Type: " + licenseType + "    Expiry: " + (expiry.isEmpty() ? "Not specified" : expiry), 13, MUTED, Gravity.START);
        space(content, 16);

        LinearLayout radio = panel();
        content.addView(radio);
        section(radio, "Bluetooth device name");
        deviceNameEdit = input("JG", InputType.TYPE_CLASS_TEXT,
                prefs.getString("service_name", "JG"));
        radio.addView(deviceNameEdit);
        space(radio, 10);
        action(radio, "APPLY / VERIFY NAME", TEAL, BG, () -> renameBluetooth());
        space(radio, 10);
        ghost(radio, "OPEN PHONE BLUETOOTH SETTINGS", () ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        realNameLabel = label(radio, "", 12, MUTED, Gravity.START);
        refreshDeviceName();
        space(content, 16);

        LinearLayout receivers = panel();
        content.addView(receivers);
        section(receivers, "Receivers (up to 2)");
        label(receivers, "Paired Bluetooth Classic SPP receivers", 12, MUTED, Gravity.START);
        space(receivers, 6);
        receiver1 = new Spinner(this);
        receiver2 = new Spinner(this);
        receivers.addView(receiver1);
        space(receivers, 9);
        action(receivers, "CONNECT RECEIVER 1", FIELD, WHITE, () -> connectSpinner(receiver1));
        space(receivers, 11);
        receivers.addView(receiver2);
        space(receivers, 9);
        action(receivers, "CONNECT RECEIVER 2", FIELD, WHITE, () -> connectSpinner(receiver2));
        space(receivers, 11);
        ghost(receivers, "REFRESH PAIRED DEVICES", () -> populatePaired());
        space(receivers, 10);
        ghost(receivers, "MAKE PHONE DISCOVERABLE", () -> makeDiscoverable());
        space(receivers, 10);
        ghost(receivers, "DISCONNECT ALL", () -> {
            if (transport != null) transport.disconnectAll();
        });
        populatePaired();
        space(content, 16);

        LinearLayout transmit = panel();
        content.addView(transmit);
        section(transmit, "Transmission");
        label(transmit, "Interval (milliseconds), 1 to 60000", 12, MUTED, Gravity.START);
        space(transmit, 8);
        intervalEdit = input("100", InputType.TYPE_CLASS_NUMBER, Integer.toString(savedInterval));
        transmit.addView(intervalEdit);
        space(transmit, 10);
        action(transmit, "SAVE INTERVAL", TEAL, BG, () -> saveInterval());
        label(transmit, "Actual JAGFOOD951 stream was roughly 100 ms. 1 ms is not guaranteed.", 12, MUTED, Gravity.START);
        space(content, 16);

        LinearLayout logs = panel();
        content.addView(logs);
        section(logs, "Connection & error log");
        ghost(logs, "COPY LOG", () -> {
            if (transport == null) return;
            ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("JG logs", transport.getLog()));
            toast("Log copied");
        });
        settingsLog = label(logs, transport == null ? "" : transport.getLog(), 12, MUTED, Gravity.START);
        settingsLog.setTextIsSelectable(true);
        space(content, 18);
        ghost(content, "LOG OUT / REMOVE SAVED KEY", () -> {
            licenseStore.clear();
            activeKey = null;
            licensed = false;
            stopTransport();
            showLogin(null);
        });
        space(content, 25);
        setContentView(scroll(content));
    }

    private void saveInterval() {
        try {
            int n = Integer.parseInt(intervalEdit.getText().toString().trim());
            if (n < 1 || n > 60000) throw new NumberFormatException();
            savedInterval = n;
            prefs.edit().putInt("interval_ms", n).apply();
            if (transport != null) transport.setIntervalMs(n);
            toast("Interval saved: " + n + "ms");
        } catch (NumberFormatException ex) { toast("Interval must be 1 to 60000 ms"); }
    }

    private void renameBluetooth() {
        String requested = deviceNameEdit.getText().toString().trim();
        if (requested.isEmpty() || requested.getBytes(StandardCharsets.UTF_8).length > 248) {
            toast("Enter a valid Bluetooth name (max 248 bytes)");
            return;
        }
        prefs.edit().putString("service_name", requested).apply();
        if (transport != null) transport.setServiceName(requested);
        if (!ensureBluetooth()) return;
        try {
            boolean accepted = bluetooth.setName(requested);
            toast(accepted ? "Rename requested; verify actual name" : "Phone blocked rename. Use Bluetooth Settings.");
        } catch (Exception e) { toast("Rename blocked. Use Bluetooth Settings."); }
        main.postDelayed(() -> {
            refreshDeviceName();
            if (realNameLabel != null) {
                try {
                    if (!requested.equals(bluetooth.getName())) {
                        toast("Actual Bluetooth name unchanged. Rename in phone Settings.");
                    }
                } catch (Exception ignored) { }
            }
        }, 1500);
    }

    private void refreshDeviceName() {
        if (realNameLabel == null) return;
        if (bluetooth == null || !allowedConnect()) {
            realNameLabel.setText("Allow Nearby devices to check the actual phone name");
            return;
        }
        try {
            String actual = bluetooth.getName();
            realNameLabel.setText("Actual phone name: " + (actual == null ? "Unknown" : actual)
                    + "\nThe service name can be different from the phone name.");
        } catch (Exception e) { realNameLabel.setText("Cannot read current Bluetooth name"); }
    }

    private void populatePaired() {
        if (receiver1 == null || receiver2 == null) return;
        if (!ensureBluetooth()) return;
        paired.clear();
        List<String> names = new ArrayList<>();
        try {
            Set<BluetoothDevice> devices = bluetooth.getBondedDevices();
            for (BluetoothDevice device : devices) {
                paired.add(device);
                String name = device.getName();
                names.add((name == null ? "Unknown" : name) + "  •  " + device.getAddress());
            }
        } catch (Exception e) { toast("Could not read paired devices"); }
        if (names.isEmpty()) names.add("No paired receiver found");
        ArrayAdapter<String> adapter1 = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        receiver1.setAdapter(adapter1);
        ArrayAdapter<String> adapter2 = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        receiver2.setAdapter(adapter2);
        if (names.size() > 1) receiver2.setSelection(1);
    }

    private void connectSpinner(Spinner dropdown) {
        if (!ensureBluetooth() || transport == null) return;
        int index = dropdown.getSelectedItemPosition();
        if (index < 0 || index >= paired.size()) { toast("Pair a Bluetooth SPP receiver first"); return; }
        transport.connect(paired.get(index));
        toast("Connecting. Check log if receiver closes the link.");
    }

    private boolean allowedConnect() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean allowedAdvertise() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureBluetooth() {
        if (bluetooth == null) { toast("Bluetooth not supported on this phone"); return false; }
        if (!allowedConnect() || !allowedAdvertise()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE}, RC_BLUETOOTH);
            }
            return false;
        }
        try {
            if (!bluetooth.isEnabled()) { toast("Switch on Bluetooth first"); return false; }
        } catch (SecurityException e) { toast("Nearby devices permission required"); return false; }
        return true;
    }

    private void makeDiscoverable() {
        if (!ensureBluetooth()) return;
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try { startActivityForResult(intent, RC_DISCOVERABLE); }
        catch (Exception ex) { toast("Discoverable request failed"); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] requested, int[] results) {
        super.onRequestPermissionsResult(code, requested, results);
        if (code == RC_BLUETOOTH) {
            toast(allowedConnect() && allowedAdvertise() ? "Bluetooth permission granted" : "Allow Nearby devices permission");
            if (settingsShowing) { refreshDeviceName(); populatePaired(); }
        }
    }

    @Override public void onBackPressed() {
        if (settingsShowing && licensed) returnMain();
        else super.onBackPressed();
    }

    private void returnMain() {
        if (!licensed) { showLogin(null); return; }
        // Preserve both live SPP sockets when returning from Settings.
        settingsShowing = false;
        settingsLog = null;
        showMain();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(19));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout base(int gravity) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(25), dp(22), dp(25));
        root.setGravity(gravity);
        return root;
    }
    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(19), dp(20), dp(19), dp(20));
        p.setBackground(shape(PANEL, Color.rgb(36, 51, 72)));
        return p;
    }
    private ImageView logo(int px) {
        ImageView view = new ImageView(this);
        view.setImageResource(R.drawable.jg_logo);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(px), dp(px));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        view.setLayoutParams(p);
        return view;
    }
    private ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(content);
        return scroll;
    }
    private void space(LinearLayout parent, int dp) {
        View view = new View(this);
        parent.addView(view, new LinearLayout.LayoutParams(1, dp(dp)));
    }
    private TextView title(LinearLayout parent, String str, int size, int gravity) {
        TextView t = label(parent, str, size, WHITE, gravity);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private void section(LinearLayout parent, String text) {
        TextView t = title(parent, text, 18, Gravity.START);
        t.setPadding(0, 0, 0, dp(12));
    }
    private TextView label(LinearLayout parent, String s, int size, int color, int gravity) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(gravity);
        parent.addView(t);
        return t;
    }
    private EditText input(String hint, int inputType, String value) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setHint(hint);
        edit.setHintTextColor(MUTED);
        edit.setTextColor(WHITE);
        edit.setTextSize(18);
        edit.setBackground(shape(FIELD, Color.rgb(53, 71, 96)));
        edit.setPadding(dp(16), dp(12), dp(16), dp(12));
        edit.setText(value);
        edit.setSelectAllOnFocus(true);
        return edit;
    }
    private Button action(LinearLayout parent, String title, int bg, int fg, Runnable fn) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(fg);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(shape(bg, bg == PANEL ? FIELD : 0));
        button.setMinHeight(dp(54));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        parent.addView(button, p);
        if (fn != null) button.setOnClickListener(v -> fn.run());
        return button;
    }
    private Button smallButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(25);
        b.setTextColor(WHITE);
        b.setBackground(shape(PANEL, 0));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return b;
    }
    private void ghost(LinearLayout parent, String label, Runnable action) {
        action(parent, label, FIELD, WHITE, action);
    }
}
