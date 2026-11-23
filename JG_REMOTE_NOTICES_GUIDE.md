# JG Kanta v9.3 – Banner and update notice rollout

## What changed
- Wrong 8-digit key or denied license lookup now shows a **customer-friendly** message with **Buy Now** guidance, not internal Firebase errors. Firebase still fails closed; no security or licensing bypass.
- Main APK shows an optional banner with **× close** on customer login and licensed main screen. It never resizes or changes the existing weight buttons, Bluetooth packet, 2 receivers, floating controls, interval, license security, PIN or Buy Now behavior.
- An optional **UPDATE** dialog opens an HTTPS download link, with **LATER** dismissal. The dialog appears only if the published `versionCode` is greater than the APK's current `versionCode`. This is an **on-launch / next-screen notice**, not an operating-system push notification, automatic forced install or FCM background delivery.
- Banner dismissal stays remembered on that phone for the current campaign ID. Every new Admin Save generates a new campaign ID, so intentionally republishing shows it again. Update dismissals are remembered per versionCode.
- On network, invalid URL or Firebase failure, optional notices fail silently and do not block license or Bluetooth.

## Where the owner controls it
JG Admin v1.2 (separate admin APK) adds **CUSTOMER BANNER & APP UPDATES** controls:
- Show banner checkbox, message, optional HTTPS info link.
- Enable update checkbox, new APK `versionCode` (greater than installed customer APK), title, message, and valid HTTPS APK/download-page link.
- LOAD SAVED NOTICES and SAVE & PUBLISH CUSTOMER NOTICES.

The app saves under Firebase Realtime Database:
`/appPublic/jgKanta/banner` and `/appPublic/jgKanta/update`.

## Firebase Rules – REQUIRED before notices work
Both rules files in this security branch now contain:

```json
"appPublic": {
  ".read": true,
  ".write": "auth != null && auth.uid == 'QFhyikjUbYZ3eL3I1QQh0CHYfJR2' && auth.token.email == 'jogaleghakanod@gmail.com'",
  "$key": {
    ".validate": "$key == 'jgKanta' && newData.hasChildren(['banner','update'])"
  }
}
```

The actual files also validate the types and lengths of individual banner/update fields. The stored **public text and download URL** are intentionally readable without a customer license; **never put keys, passwords, customer data or salts in this public path**.

**GitHub edits do not change live Firebase Rules.** After reviewing and backing up live rules, merge only the matching `appPublic` rule into the **currently active** Firebase Realtime Database Rules, keeping the entire existing `keys` and `licenses` rules untouched. Do not blindly replace live rules with a different migration/final rules file, because it could break old customers. Then Publish.

Do **not** press Firebase App Check Enforce. The current admin Android app uses Firebase Auth REST but does not submit an Android App Check token; enforcing RTDB App Check would block administrative notice saves.

## Version selection for update
Customer APK: **v9.3**, Android `versionCode=13`. Admin APK: **v1.2**, `versionCode=3`. When you build a future customer APK, set the Admin update `versionCode` to its **Android build number**, not a decimal versionName like `9.4`, and enter the downloadable HTTPS URL.

## Test plan before customer rollout
1. Install/update the admin signed APK and sign in. Existing generated keys remain locally encrypted.
2. Enable a banner with a short test sentence; save after Rules are published. Open customer APK; check banner at top, tap ×, reopen to verify it stays dismissed for that campaign.
3. Put a safe HTTPS test link and future `versionCode` in admin, turn on update notice, save. Test UPDATE link and LATER dismissal (no forced install).
4. Check a wrong 8-digit key shows the friendly Buy Now text. Check a valid key still verifies; ensure expired, bound and HOLD keys remain rejected.
5. Test both SPP receivers, Bluetooth frame bytes, 5–50 weight shortcuts, Settings PIN 18360100, background and floating operation. These source files were not touched.
