# JG Kanta v9 — Background sending, floating controls, screen awake

## What changed

- JG Bluetooth Classic RFCOMM server/client, connection sockets and 100 ms weight sending now live in a **connectedDevice Foreground Service** instead of the Activity. Switching apps should not call `BluetoothController.shutdown()`.
- When JG is licensed and its main screen is open, `FLAG_KEEP_SCREEN_ON` keeps the display awake.
- When another app opens, JG can show a draggable `TYPE_APPLICATION_OVERLAY` panel with WEIGHT manual edit, 5/10/15/20/25/30/35/40/45/50 KG, WAIT/START/STOP, APP and collapse button. The overlay requests `FLAG_KEEP_SCREEN_ON` as well.
- The service posts an ongoing notification with a deliberate STOP SERVICE action. The service is NOT auto-started after boot or without a valid license.
- Previously saved DEVICE_LOCK licenses still revalidate online on opening; background service checks again every 15 minutes while running and stops if verification fails.
- Settings PIN, WhatsApp Buy Now and Firebase rules/data remain unchanged.

## How to use (Android phone)

1. Install **signed release APK** from the passing GitHub Actions run on branch `jg-v7-firebase-setup-20261121` (version 9).
2. Login with valid 8-digit license. **Allow Nearby devices** permission when Android asks.
3. Open `FLOATING JG CONTROLS` and allow Android `Display over other apps / Appear on top`. Return to JG.
4. Connect the Bluetooth SPP receiver and select `WAIT FOR CONNECTION` then `START`.
5. Open WhatsApp or another app. The floating JG panel appears when JG's main screen is not visible; drag its header to move it, `+`/`−` to fold, or `APP` to open JG. Weight can be typed or chosen by quick buttons.
6. Return to the app as usual. App re-checks the DEVICE_LOCK license without intentionally closing Bluetooth sockets.

## Limitations and troubleshooting

- **Keep-screen-awake is best-effort.** Android/OEM may hide overlays, dim during certain full-screen/system screens, or turn screen off if the user explicitly presses Power. A foreground service is not a way to override power controls.
- Some manufacturers require battery settings such as **Allow background activity** or unrestricted battery optimization for the service to run reliably. Android can still kill it, and user force-stop terminates it. Reopen JG to restart if needed.
- Screen staying on consumes battery. Long-lived screen-on usage may generate heat; do not leave unattended during charging.
- On Android 13+, notification permission improves visibility of the service notification; Bluetooth foreground service can still operate if notifications are disallowed, subject to Android policy.
- `ONE_TIME` keys are not persisted between launches. `DEVICE_LOCK` keys remain encrypted. When license is revoked, the service must stop.
- This code passed a GitHub compile only when the corresponding build reports success. Actual Bluetooth/overlay testing must be performed on a phone.

This feature requires Android `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` and Bluetooth runtime permissions.
