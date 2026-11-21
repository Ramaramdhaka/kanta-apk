package com.example.bluetoothkanta;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Two simultaneous Classic SPP RFCOMM links; independent sender per link. */
final class BluetoothController {
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final int MAX_LINKS = 2;

    interface Observer { void onChange(String status, int connected, long sent, String log); }
    private static final class Link {
        final BluetoothSocket socket;
        final String label;
        final String address;
        final boolean outgoing;
        final OutputStream output;
        final AtomicBoolean closed = new AtomicBoolean(false);
        Link(BluetoothSocket socket, String label, String address, boolean outgoing) throws IOException {
            this.socket = socket;
            this.label = label;
            this.address = address;
            this.outgoing = outgoing;
            this.output = socket.getOutputStream();
        }
    }

    private final Object lock = new Object();
    private final ArrayList<Link> links = new ArrayList<>();
    private final Set<String> pending = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final Observer observer;
    private final AtomicReference<String> packet = new AtomicReference<>("WT020.000KG ");
    private final AtomicBoolean transmitting = new AtomicBoolean(false);
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicInteger sendGeneration = new AtomicInteger(0);
    private final AtomicInteger connectionEpoch = new AtomicInteger(0);
    private final StringBuilder log = new StringBuilder();
    private volatile boolean listening = false;
    private volatile boolean shutdown = false;
    private volatile boolean autoReconnect = true;
    private volatile int intervalMs = 100;
    private volatile BluetoothServerSocket server;
    private String serviceName = "JG";

    BluetoothController(BluetoothAdapter adapter, Observer observer) {
        this.adapter = adapter;
        this.observer = observer;
        writeLog("JG SPP ready, 2 receiver slots. UUID=" + SPP_UUID);
    }

    void setPacket(String value) { packet.set(value); }
    void setIntervalMs(int value) { intervalMs = value; writeLog("TX interval " + intervalMs + " ms"); }
    void setAutoReconnect(boolean enabled) { autoReconnect = enabled; }
    void setServiceName(String name) { if (name != null && !name.trim().isEmpty()) serviceName = name.trim(); }
    int getActiveCount() { synchronized (lock) { return links.size(); } }
    boolean isSending() { return transmitting.get(); }
    boolean isListening() { return listening; }
    String getLog() { synchronized (lock) { return log.toString(); } }

    private void writeLog(String message) {
        synchronized (lock) {
            log.append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()))
                    .append(" | ").append(message).append('\n');
            if (log.length() > 22000) log.delete(0, log.length() - 16000);
        }
        notifyObserver();
    }
    private void notifyObserver() {
        if (shutdown) return;
        final int count = getActiveCount();
        final long sent = frameCount.get();
        final String status = count > 0 ? ("Connected " + count + "/2")
                : (listening ? "Waiting for receiver..." : "No receivers connected");
        final String text = getLog();
        ui.post(() -> { if (!shutdown) observer.onChange(status, count, sent, text); });
    }

    void waitForIncoming() {
        if (shutdown) return;
        if (adapter == null || !adapter.isEnabled()) { writeLog("Turn on Bluetooth first"); return; }
        synchronized (lock) {
            if (listening) { writeLog("SPP listener already running"); return; }
            listening = true;
        }
        writeLog("WAIT: SPP listener starting (up to 2 receivers)");
        new Thread(() -> {
            BluetoothServerSocket opened = null;
            try {
                opened = adapter.listenUsingRfcommWithServiceRecord(serviceName, SPP_UUID);
                synchronized (lock) {
                    if (shutdown || !listening) { close(opened); return; }
                    server = opened;
                }
                while (!shutdown && listening) {
                    BluetoothSocket socket = opened.accept();
                    if (shutdown || !listening) { close(socket); break; }
                    if (!register(socket, "Incoming " + safeName(socket.getRemoteDevice()), false)) {
                        writeLog("Receiver rejected: 2 slots already used or device already connected");
                    }
                }
            } catch (Exception e) {
                if (!shutdown && listening) writeLog("SPP WAIT failed: " + e.getMessage());
            } finally {
                synchronized (lock) { if (server == opened) server = null; listening = false; }
                close(opened);
                notifyObserver();
            }
        }, "JG-SPP-Listen").start();
        notifyObserver();
    }

    void connect(BluetoothDevice device) {
        connectInternal(device, 0, connectionEpoch.get());
    }

    private void connectInternal(BluetoothDevice device, int attempt, int requestEpoch) {
        if (device == null || shutdown || requestEpoch != connectionEpoch.get()) return;
        if (adapter == null || !adapter.isEnabled()) { writeLog("Bluetooth OFF"); return; }
        final String address;
        try { address = device.getAddress(); }
        catch (SecurityException e) { writeLog("Nearby devices permission required"); return; }
        synchronized (lock) {
            if (links.size() + pending.size() >= MAX_LINKS) { writeLog("Maximum 2 connections; disconnect a receiver first"); return; }
            for (Link l : links) if (l.address.equals(address)) { writeLog("Already connected: " + address); return; }
            if (!pending.add(address)) { writeLog("Already connecting: " + address); return; }
        }
        writeLog("Connecting slot " + (getActiveCount() + 1) + " → " + safeName(device)
                + (attempt > 0 ? " retry=" + attempt : ""));
        new Thread(() -> {
            BluetoothSocket socket = null;
            boolean success = false;
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                if (requestEpoch == connectionEpoch.get() && !shutdown) {
                    success = register(socket, "Outgoing " + safeName(device), true);
                }
            } catch (Exception e) {
                writeLog("SPP connect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                synchronized (lock) { pending.remove(address); }
                if (!success) {
                    close(socket);
                    // Retrying cannot make a device without an SPP server accept a connection.
                    if (autoReconnect && !shutdown && attempt < 2
                            && requestEpoch == connectionEpoch.get()) {
                        ui.postDelayed(() -> connectInternal(device, attempt + 1, requestEpoch), 3500);
                    }
                }
                notifyObserver();
            }
        }, "JG-Connect-" + address).start();
    }

    private boolean register(BluetoothSocket socket, String label, boolean outgoing) {
        final Link link;
        try {
            String address = socket.getRemoteDevice().getAddress();
            synchronized (lock) {
                if (shutdown || links.size() >= MAX_LINKS) { close(socket); return false; }
                for (Link l : links) {
                    if (l.address.equals(address)) { close(socket); return false; }
                }
                link = new Link(socket, label, address, outgoing);
                links.add(link);
            }
        } catch (Exception e) { writeLog("Cannot open SPP streams: " + e.getMessage()); close(socket); return false; }
        writeLog("CONNECTED: " + label + " (" + getActiveCount() + "/2)");
        watch(link);
        if (transmitting.get()) launchSender(link, sendGeneration.get());
        return true;
    }

    private void watch(Link link) {
        new Thread(() -> {
            byte[] b = new byte[128];
            try {
                InputStream in = link.socket.getInputStream();
                while (!shutdown && !link.closed.get()) {
                    int n = in.read(b);
                    if (n < 0) { removeLink(link, "Remote closed SPP (EOF)"); return; }
                    if (n > 0) writeLog("RX from " + link.label + ": " + n + " bytes");
                }
            } catch (Exception e) {
                removeLink(link, "SPP read closed: " + e.getMessage());
            }
        }, "JG-Watch-" + link.address).start();
    }

    void startSending() {
        if (packet.get() == null) { writeLog("Enter a valid weight before START"); return; }
        if (!transmitting.compareAndSet(false, true)) { writeLog("Already sending"); return; }
        frameCount.set(0);
        int token = sendGeneration.incrementAndGet();
        List<Link> all;
        synchronized (lock) { all = new ArrayList<>(links); }
        for (Link l : all) launchSender(l, token);
        writeLog("START: " + intervalMs + " ms, 12 bytes per frame. Receivers=" + all.size());
        if (all.isEmpty()) {
            writeLog("No receiver yet: WAIT for incoming SPP or connect both in Settings");
        }
    }

    private void launchSender(Link link, int token) {
        new Thread(() -> {
            while (!shutdown && !link.closed.get() && transmitting.get() && sendGeneration.get() == token) {
                String data = packet.get();
                try {
                    if (data != null) {
                        link.output.write(data.getBytes(StandardCharsets.US_ASCII));
                        long count = frameCount.incrementAndGet();
                        if (count == 1 || count % 20 == 0) notifyObserver();
                    }
                    SystemClock.sleep(intervalMs);
                } catch (Exception e) {
                    removeLink(link, "TX failed: " + e.getMessage());
                    return;
                }
            }
        }, "JG-TX-" + link.address).start();
    }

    void stopSending() {
        transmitting.set(false);
        sendGeneration.incrementAndGet();
        writeLog("STOP: transmission paused; connections stay open");
    }

    private void removeLink(Link link, String reason) {
        if (!link.closed.compareAndSet(false, true)) return;
        synchronized (lock) { links.remove(link); }
        close(link.socket);
        writeLog("DISCONNECTED " + link.label + ": " + reason);
        // Do not reconnect without user consent after a manual disconnect.
        // Manual CONNECT can reconnect a closed remote link, and WAIT remains active.
    }

    void disconnectAll() {
        connectionEpoch.incrementAndGet();  // Cancel in-flight and delayed outbound connects.
        transmitting.set(false);
        sendGeneration.incrementAndGet();
        listening = false;
        close(server);
        server = null;
        List<Link> old;
        synchronized (lock) { old = new ArrayList<>(links); links.clear(); pending.clear(); }
        for (Link l : old) { l.closed.set(true); close(l.socket); }
        writeLog("Disconnected all receivers / stopped WAIT");
    }

    void shutdown() { disconnectAll(); shutdown = true; }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return (name == null || name.isEmpty()) ? device.getAddress() : name;
        } catch (SecurityException e) { return "Unknown device"; }
    }
    private static void close(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }
    private static void close(BluetoothServerSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }
}
