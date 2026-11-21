# Bluetooth Kanta - GitHub Actions APK Builder

This is an **Android Bluetooth Classic (SPP)** weight-data sender.
It simulates a weighing scale but does not need a real weighing scale.

**Sent line example:** `ST,GS,+00010.50kg\r\n`

- Default weight: 10.50 kg (editable)
- Default interval: 1 millisecond (editable, best effort only)
- Bluetooth SPP receiver: connect to an already paired device
- START and STOP buttons, live packet count

## Build an APK on GitHub without Android Studio

1. Download `BluetoothKanta_Fixed_GitHub.zip`, then **extract** it on your computer.
2. Open your GitHub repository `kanta-apk` > **Code** > **Add file** > **Upload files**.
3. Drag all **contents** of the extracted folder onto GitHub. This includes `.github`, `app`, `README.md`, `build.gradle`, `settings.gradle`.
4. Make sure the paths are **exactly** `.github/workflows/build.yml`, `app/src/main/AndroidManifest.xml`, and `app/src/main/java/com/example/bluetoothkanta/MainActivity.java`.
5. Click **Commit changes** to the `main` branch. Wait for the GitHub Actions `Build Bluetooth Kanta APK` workflow to finish.
6. In **Actions**, open the newest run. On success, download **Artifacts > BluetoothKanta-APK**; unzip to find `app-debug.apk`.

**IMPORTANT:** Do not upload the ZIP file without extracting it, and do not put the project files in an extra `BluetoothKanta_Fixed_GitHub/` folder inside the repo. If there is an old `.github/workflows/build.yml`, uploading this file should replace it. Do not run a different old workflow.

## How to use

1. Pair your Android phone with a *compatible Bluetooth Classic SPP receiver* in Android Settings.
2. Open the app, allow nearby devices / Bluetooth permission.
3. Tap **Paired Devices Refresh**, pick the receiver, then **Connect Bluetooth**.
4. Enter the desired weight and interval, then tap **START SENDING**. Tap **STOP** to stop.

This project **sends** data; it does not require a real weighing scale. But to actually transmit and receive bytes, it **does** require a compatible connected Bluetooth receiver. A second ordinary Android phone is not necessarily an SPP receiver unless its app supports SPP server mode. iPhones and BLE-only devices generally cannot receive this SPP stream.

Exact 1 ms packet delivery is not guaranteed: Android scheduling and Bluetooth packet batching can introduce delays. Keep the app open while sending; background transmission is not implemented.

## Build details

- GitHub-hosted `ubuntu-latest`
- Android Gradle plugin 8.7.3
- Gradle 8.9 (installed in the GitHub workflow; **no `gradlew` file needed**)
- Java 17
- Android API 35 compile SDK, Android API 26 minimum

If a build fails, open Actions > failed run > `Build Android debug APK` > the failed step, and copy the error lines.
