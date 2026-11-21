package com.example.bluetoothkanta;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream output;
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private Spinner devices;
    private EditText weight, interval;
    private TextView status, counter;
    private final ArrayList<BluetoothDevice> paired = new ArrayList<>();
    private long sent = 0;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28, 38, 28, 20);
        TextView title = new TextView(this); title.setText("Bluetooth Kanta Simulator"); title.setTextSize(23); root.addView(title);
        devices = new Spinner(this); root.addView(devices);
        Button refresh = new Button(this); refresh.setText("Paired Devices Refresh"); root.addView(refresh);
        Button connect = new Button(this); connect.setText("Connect Bluetooth"); root.addView(connect);
        weight = new EditText(this); weight.setSingleLine(true); weight.setInputType(8194); weight.setText("10.50"); weight.setHint("Weight in kg"); root.addView(weight);
        interval = new EditText(this); interval.setSingleLine(true); interval.setInputType(2); interval.setText("1"); interval.setHint("Interval milliseconds"); root.addView(interval);
        Button start = new Button(this); start.setText("START SENDING"); root.addView(start);
        Button stop = new Button(this); stop.setText("STOP"); root.addView(stop);
        status = new TextView(this); status.setText("Not connected"); status.setTextSize(16); root.addView(status);
        counter = new TextView(this); counter.setText("Packets sent: 0"); root.addView(counter);
        TextView note = new TextView(this); note.setText("Example: ST,GS,+00010.50kg\\r\\n  |  Bluetooth Classic SPP only. Actual 1 ms delivery is not guaranteed."); root.addView(note);
        setContentView(root);
        refresh.setOnClickListener(v -> loadPaired());
        connect.setOnClickListener(v -> connectSelected());
        start.setOnClickListener(v -> startSending());
        stop.setOnClickListener(v -> { sending.set(false); setStatus("Stopped"); });
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 10);
        } else loadPaired();
    }
    private void setStatus(String s) { runOnUiThread(() -> status.setText(s)); }
    private boolean hasPermission() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private void loadPaired() {
        if (!hasPermission()) { setStatus("Allow Bluetooth permission first"); return; }
        paired.clear(); ArrayList<String> names = new ArrayList<>();
        if (adapter != null && adapter.isEnabled()) {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice d : bonded) { paired.add(d); names.add(d.getName() + " (" + d.getAddress() + ")"); }
        }
        if (names.isEmpty()) names.add("Pair a Bluetooth device in Android Settings first");
        devices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }
    private void connectSelected() {
        if (!hasPermission() || paired.isEmpty()) { setStatus("Pair/select a device first"); return; }
        BluetoothDevice target = paired.get(devices.getSelectedItemPosition());
        sending.set(false); setStatus("Connecting...");
        new Thread(() -> {
            try {
                closeSocket();
                BluetoothSocket s = target.createRfcommSocketToServiceRecord(SPP);
                s.connect(); socket = s; output = s.getOutputStream(); setStatus("Connected to " + target.getName());
            } catch (Exception e) { setStatus("Connection failed: " + e.getMessage()); closeSocket(); }
        }).start();
    }
    private void startSending() {
        if (output == null) { setStatus("Connect Bluetooth first"); return; }
        if (!sending.compareAndSet(false, true)) return;
        final String value;
        final long period;
        try {
            double kg = Double.parseDouble(weight.getText().toString());
            if (!Double.isFinite(kg) || Math.abs(kg) > 99999.99) throw new IllegalArgumentException();
            value = String.format(java.util.Locale.US, "ST,GS,%+09.2fkg\r\n", kg);
            period = Math.max(1, Long.parseLong(interval.getText().toString()));
        } catch (Exception e) { sending.set(false); setStatus("Enter valid weight and interval"); return; }
        setStatus("Sending: " + value.trim());
        new Thread(() -> {
            try {
                byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
                long next = SystemClock.elapsedRealtimeNanos();
                while (sending.get()) {
                    OutputStream stream = output;
                    if (stream == null) break;
                    stream.write(bytes);
                    sent++;
                    if (sent % 100 == 0) runOnUiThread(() -> counter.setText("Packets sent: " + sent));
                    next += period * 1_000_000L;
                    long delay = next - SystemClock.elapsedRealtimeNanos();
                    if (delay > 0) SystemClock.sleep(Math.max(1, delay / 1_000_000L));
                    else next = SystemClock.elapsedRealtimeNanos();
                }
            } catch (Exception e) { setStatus("Send failed: " + e.getMessage()); }
            finally { sending.set(false); runOnUiThread(() -> counter.setText("Packets sent: " + sent)); }
        }).start();
    }
    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null; output = null;
    }
    @Override protected void onDestroy() { sending.set(false); closeSocket(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) loadPaired();
    }
}
