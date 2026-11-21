package com.example.bluetoothkanta;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    // Standard Bluetooth Classic Serial Port Profile UUID.
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int PERMISSION_REQUEST = 10;
    private static final int DISCOVERABLE_REQUEST = 11;

    private BluetoothAdapter adapter;
    private SharedPreferences preferences;
    private final Object connectionLock = new Object();
    private BluetoothSocket socket;
    private BluetoothServerSocket serverSocket;
    private volatile OutputStream output;
    private final AtomicInteger connectionVersion = new AtomicInteger(0);
    private final AtomicInteger sendVersion = new AtomicInteger(0);
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private final AtomicLong sentCount = new AtomicLong(0);
    private final AtomicReference<String> currentFrame = new AtomicReference<>();
    private final ArrayList<BluetoothDevice> paired = new ArrayList<>();

    private EditText deviceNameInput, weightInput, intervalInput;
    private Spinner pairedSpinner;
    private TextView nameStatus, status, preview, counter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        preferences = getSharedPreferences("kanta_settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 30, 28, 32);

        addText(root, "Bluetooth Kanta Simulator", 23);
        addText(root, "Asli JAGFOOD951 kante jaisa 12-byte Classic SPP data", 14);

        addText(root, "1. Apne phone ka Bluetooth Device Name", 17);
        deviceNameInput = editText(root, "Naam likho (jaise JAGFOOD951)", InputType.TYPE_CLASS_TEXT);
        deviceNameInput.setText(preferences.getString("desired_name", "KANTA20"));
        Button applyName = button(root, "APPLY BLUETOOTH DEVICE NAME");
        nameStatus = addText(root, "Current Bluetooth name: ...", 14);
        Button openSettings = button(root, "Open Bluetooth Settings (manual name change)");

        addText(root, "2. Receiver connect kare (phone kanta banega)", 17);
        addText(root, "Receiver ko apne phone se Bluetooth Classic SPP par connect karna hai to pehle Make Discoverable, phir Wait for Connection dabao.", 13);
        Button discoverable = button(root, "MAKE PHONE DISCOVERABLE (UP TO 5 MIN)");
        Button listen = button(root, "WAIT FOR INCOMING SPP CONNECTION");

        addText(root, "Ya: kisi paired SPP receiver ko is phone se connect karo", 15);
        pairedSpinner = new Spinner(this);
        root.addView(pairedSpinner);
        Button refresh = button(root, "Refresh Paired Devices");
        Button connect = button(root, "CONNECT TO SELECTED RECEIVER");
        Button disconnect = button(root, "DISCONNECT");

        addText(root, "3. Weight (KG)", 17);
        weightInput = editText(root, "20 ya 20.500", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weightInput.setText(preferences.getString("weight", "20"));
        preview = addText(root, "", 18);

        addText(root, "4. Sending interval (milliseconds)", 17);
        intervalInput = editText(root, "100", InputType.TYPE_CLASS_NUMBER);
        intervalInput.setText(preferences.getString("interval", "100"));
        addText(root, "Asli scale lagbhag 100 ms ke aas-paas ek frame bhej raha tha. Exact delivery timing guaranteed nahi hai.", 13);

        Button start = button(root, "START SENDING");
        Button stop = button(root, "STOP SENDING");
        status = addText(root, "Disconnected", 16);
        counter = addText(root, "Frames sent: 0", 15);
        addText(root, "Format: WT020.000KG + last mein ek SPACE byte (12 bytes total). App foreground mein khula rakho.", 13);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        weightInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateFrame(s.toString()); }
        });
        updateFrame(weightInput.getText().toString());

        applyName.setOnClickListener(v -> applyBluetoothName());
        openSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        discoverable.setOnClickListener(v -> requestDiscoverable());
        listen.setOnClickListener(v -> listenForReceiver());
        refresh.setOnClickListener(v -> loadPaired());
        connect.setOnClickListener(v -> connectSelected());
        disconnect.setOnClickListener(v -> disconnect());
        start.setOnClickListener(v -> startSending());
        stop.setOnClickListener(v -> stopSending());

        if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermissions()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, PERMISSION_REQUEST);
        } else {
            loadPaired();
            updateBluetoothNameStatus();
        }
    }

    private TextView addText(LinearLayout layout, String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setPadding(0, 9, 0, 9);
        layout.addView(view);
        return view;
    }

    private EditText editText(LinearLayout layout, String hint, int inputType) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setInputType(inputType);
        view.setHint(hint);
        layout.addView(view);
        return view;
    }

    private Button button(LinearLayout layout, String label) {
        Button result = new Button(this);
        result.setText(label);
        layout.addView(result);
        return result;
    }

    private boolean canConnect() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canAdvertise() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothPermissions() {
        return canConnect() && canAdvertise();
    }

    private boolean ready() {
        if (adapter == null) {
            showStatus("Phone mein Bluetooth Classic support nahi hai");
            return false;
        }
        if (!canConnect()) {
            showStatus("Bluetooth permission allow karo");
            return false;
        }
        if (!adapter.isEnabled()) {
            showStatus("Phone Settings mein Bluetooth ON karo");
            return false;
        }
        return true;
    }

    private void updateBluetoothNameStatus() {
        if (nameStatus == null) return;
        if (adapter == null || !canConnect()) {
            nameStatus.setText("Bluetooth unavailable / permission needed");
            return;
        }
        try {
            nameStatus.setText("Current phone Bluetooth name: " + adapter.getName());
        } catch (SecurityException e) {
            nameStatus.setText("Bluetooth name read permission denied");
        }
    }

    private void applyBluetoothName() {
        if (!ready()) return;
        String chosenName = deviceNameInput.getText().toString().trim();
        int byteLength = chosenName.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength == 0 || byteLength > 248) {
            showStatus("Naam 1 se 248 UTF-8 bytes ka rakho. Chhota naam (20 chars) behtar hai.");
            return;
        }
        try {
            boolean requested = adapter.setName(chosenName);
            if (requested) {
                preferences.edit().putString("desired_name", chosenName).apply();
                showStatus("Bluetooth name change request accepted: " + chosenName
                        + ". Doosre phone par Bluetooth refresh karke dekho.");
            } else {
                showStatus("Phone ne name change reject kiya. Open Bluetooth Settings se manually badlo.");
            }
            updateBluetoothNameStatus();
        } catch (Exception ex) {
            showStatus("Name change failed: " + ex.getMessage()
                    + ". Bluetooth Settings se manually try karo.");
        }
    }

    private void requestDiscoverable() {
        if (!ready()) return;
        if (!canAdvertise()) {
            showStatus("Nearby devices / Bluetooth advertise permission allow karo");
            return;
        }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try {
            startActivityForResult(intent, DISCOVERABLE_REQUEST);
        } catch (Exception ex) {
            showStatus("Discoverable request failed: " + ex.getMessage());
        }
    }

    private void updateFrame(String value) {
        try {
            double kg = Double.parseDouble(value.trim());
            if (!Double.isFinite(kg) || kg < 0 || kg > 999.999) {
                throw new IllegalArgumentException("Range: 0 to 999.999 kg");
            }
            // From captured real-scale frames: "WT091.940KG " = exactly 12 ASCII bytes.
            String packet = String.format(Locale.US, "WT%07.3fKG ", kg);
            if (packet.getBytes(StandardCharsets.US_ASCII).length != 12) {
                throw new IllegalArgumentException("Bad frame length");
            }
            currentFrame.set(packet);
            preview.setText("TX: [" + packet + "] (12 bytes; last char SPACE)");
            preferences.edit().putString("weight", value).apply();
        } catch (Exception ignored) {
            currentFrame.set(null);
            preview.setText("Valid weight type karo: 0 se 999.999 kg");
        }
    }

    private void loadPaired() {
        if (!ready()) return;
        paired.clear();
        ArrayList<String> labels = new ArrayList<>();
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                paired.add(device);
                labels.add((device.getName() == null ? "Unknown" : device.getName())
                        + " (" + device.getAddress() + ")");
            }
        } catch (Exception ex) {
            showStatus("Paired device list error: " + ex.getMessage());
        }
        if (labels.isEmpty()) labels.add("Pehle Settings mein receiver pair karo");
        pairedSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
    }

    private void closeQuietly(BluetoothSocket value) {
        try { if (value != null) value.close(); } catch (Exception ignored) { }
    }

    private void closeQuietly(BluetoothServerSocket value) {
        try { if (value != null) value.close(); } catch (Exception ignored) { }
    }

    private void stopSendingWithoutStatus() {
        sending.set(false);
        sendVersion.incrementAndGet();
    }

    private void closeConnections() {
        stopSendingWithoutStatus();
        connectionVersion.incrementAndGet();
        synchronized (connectionLock) {
            output = null;
            closeQuietly(socket);
            closeQuietly(serverSocket);
            socket = null;
            serverSocket = null;
        }
    }

    private void disconnect() {
        closeConnections();
        showStatus("Disconnected");
    }

    private void assignConnection(BluetoothSocket connected, int token, String label) throws Exception {
        synchronized (connectionLock) {
            if (connectionVersion.get() != token) {
                closeQuietly(connected);
                return;
            }
            socket = connected;
            output = connected.getOutputStream();
        }
        showStatus("Connected: " + label + ". Ab START SENDING dabao.");
    }

    private void listenForReceiver() {
        if (!ready()) return;
        closeConnections();
        final int token = connectionVersion.get();
        showStatus("Starting SPP listener...");
        new Thread(() -> {
            BluetoothServerSocket server = null;
            try {
                String serviceName = deviceNameInput.getText().toString().trim();
                if (serviceName.isEmpty()) serviceName = "Bluetooth Kanta";
                server = adapter.listenUsingRfcommWithServiceRecord(serviceName, SPP_UUID);
                synchronized (connectionLock) {
                    if (token != connectionVersion.get()) {
                        closeQuietly(server);
                        return;
                    }
                    serverSocket = server;
                }
                showStatus("Waiting for Bluetooth SPP receiver to connect... Pair from receiver.");
                BluetoothSocket accepted = server.accept();
                if (token == connectionVersion.get()) {
                    assignConnection(accepted, token, "incoming SPP receiver");
                } else {
                    closeQuietly(accepted);
                }
            } catch (Exception ex) {
                if (token == connectionVersion.get()) {
                    showStatus("SPP listen failed: " + ex.getMessage());
                }
            } finally {
                synchronized (connectionLock) {
                    if (serverSocket == server) serverSocket = null;
                }
                closeQuietly(server);
            }
        }, "KantaListenSPP").start();
    }

    private void connectSelected() {
        if (!ready()) return;
        int selected = pairedSpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= paired.size()) {
            showStatus("List se paired SPP receiver choose karo");
            return;
        }
        final BluetoothDevice device = paired.get(selected);
        closeConnections();
        final int token = connectionVersion.get();
        showStatus("Connecting to selected SPP receiver...");
        new Thread(() -> {
            BluetoothSocket candidate = null;
            try {
                candidate = device.createRfcommSocketToServiceRecord(SPP_UUID);
                candidate.connect();
                assignConnection(candidate, token, device.getName());
            } catch (Exception ex) {
                closeQuietly(candidate);
                if (connectionVersion.get() == token) {
                    showStatus("SPP connect failed: " + ex.getMessage()
                            + " (receiver ko SPP accept karna hoga)");
                }
            }
        }, "KantaConnectSPP").start();
    }

    private void startSending() {
        if (output == null) {
            showStatus("Pehle Bluetooth SPP connect hone do");
            return;
        }
        if (currentFrame.get() == null) {
            showStatus("Valid weight likho");
            return;
        }
        final long intervalMs;
        try {
            intervalMs = Long.parseLong(intervalInput.getText().toString().trim());
            if (intervalMs < 1 || intervalMs > 60000) throw new IllegalArgumentException();
        } catch (Exception ex) {
            showStatus("Interval 1 se 60000 milliseconds hona chahiye");
            return;
        }
        if (!sending.compareAndSet(false, true)) return;
        preferences.edit().putString("interval", String.valueOf(intervalMs)).apply();
        final int token = sendVersion.incrementAndGet();
        sentCount.set(0);
        showStatus("Sending scale-style 12-byte frames... Weight live change kar sakte ho.");
        new Thread(() -> {
            try {
                long nextNs = SystemClock.elapsedRealtimeNanos();
                while (sending.get() && token == sendVersion.get()) {
                    String frame = currentFrame.get();
                    OutputStream stream = output;
                    if (stream == null) throw new IllegalStateException("Bluetooth disconnected");
                    if (frame != null) {
                        stream.write(frame.getBytes(StandardCharsets.US_ASCII));
                        long total = sentCount.incrementAndGet();
                        if (total % 10 == 0) {
                            runOnUiThread(() -> counter.setText("Frames sent: " + total));
                        }
                    }
                    nextNs += intervalMs * 1_000_000L;
                    long leftNs = nextNs - SystemClock.elapsedRealtimeNanos();
                    if (leftNs > 0) {
                        SystemClock.sleep(Math.max(1, leftNs / 1_000_000L));
                    } else {
                        nextNs = SystemClock.elapsedRealtimeNanos();
                    }
                }
            } catch (Exception ex) {
                if (token == sendVersion.get()) {
                    showStatus("Data send failed: " + ex.getMessage());
                }
            } finally {
                if (token == sendVersion.get()) sending.set(false);
                long total = sentCount.get();
                runOnUiThread(() -> counter.setText("Frames sent: " + total));
            }
        }, "KantaSendFrames").start();
    }

    private void stopSending() {
        stopSendingWithoutStatus();
        showStatus("Sending stopped (Bluetooth still connected)");
    }

    private void showStatus(String message) {
        runOnUiThread(() -> {
            if (status != null) status.setText(message);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBluetoothNameStatus();
    }

    @Override
    protected void onDestroy() {
        closeConnections();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (canConnect()) {
                loadPaired();
                updateBluetoothNameStatus();
            } else {
                showStatus("Bluetooth permission denied. App settings se permission allow karo.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DISCOVERABLE_REQUEST) {
            if (resultCode == RESULT_CANCELED) {
                showStatus("Discoverable permission cancel kar di");
            } else {
                showStatus("Phone " + resultCode + " sec tak discoverable hai. WAIT FOR INCOMING dabao.");
            }
        }
    }
}
