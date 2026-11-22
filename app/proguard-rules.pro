# Preserve AndroidX Security/Tink reflective types.
-keep class androidx.security.crypto.** { *; }
-keep class org.json.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class com.example.bluetoothkanta.LicenseVerifier$Result { *; }
-keep class com.example.bluetoothkanta.Obfuscated { *; }
