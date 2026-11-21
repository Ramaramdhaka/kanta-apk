# Bluetooth Kanta Simulator

Android app that sends simulated weighing-scale data over Bluetooth Classic SPP (RFCOMM) to a paired, compatible receiver.

## Build APK
1. Open the `BluetoothKanta` folder in Android Studio (with Android SDK installed).
2. Let Gradle sync and install the requested SDK.
3. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Find `app/build/outputs/apk/debug/app-debug.apk`.

## Use
1. Pair an SPP-compatible Bluetooth device in Android settings.
2. Open app, grant Bluetooth permission, refresh paired devices, choose receiver, connect.
3. Enter weight and interval (default 1 ms), press START.
4. Press STOP to stop sending.

Data example: `ST,GS,+00010.50kg\r\n`. This is an illustrative format, not a universal scale protocol. Only paired Bluetooth Classic SPP receivers are supported. iPhones and most BLE-only devices will not accept this stream. The requested 1ms interval is best effort and cannot guarantee exact timing or delivery. Background sending is not supported; keep the app open.
