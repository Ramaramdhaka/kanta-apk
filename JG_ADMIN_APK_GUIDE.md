# JG Admin – separate native Android license manager

A private, compact Android app for the owner. **No admin.html needed** for day-to-day license management. It installs alongside JG Kanta (different package name `com.jgkanta.admin`).

## Login and safety
- Login with your **existing Firebase Authentication admin email and password**. Admin UID is pinned to `QFhyikjUbYZ3eL3I1QQh0CHYfJR2`. The server's Realtime Database Rules remain the actual authorization boundary.
- A hardcoded `admin/123` login has **not** been added because it would be extractable from the APK, even if nobody is intentionally given the file.
- No Firebase admin service account, database secret, or email password is embedded.
- Uses Firebase email/password HTTPS REST API. Reuses existing signed APK keystore and `JG_LICENSE_HASH_SALT` at GitHub build time.

## Features
- Native dark-themed dashboard with total, active, hold/used key counts.
- Secure randomized 8-digit license creation: 30 days ₹300, 1 year ₹1200, Lifetime ₹2000.
- Device Lock or One Time license, customer name, expiry, claim status.
- Search customers, original 8-digit key or SHA-256 node hash.
- Copy generated key, Hold / Activate, Reset claim and device binding, Delete license.
- Stores plaintext keys **only on the owner phone** in Android EncryptedSharedPreferences. Firebase has `/licenses/<SHA256(key + salt)>` only. If the app is uninstalled, app data reset or phone changed, the original key numbers can't be recovered from their hashes; **keep a separate backup of issued keys**. Keys generated earlier via admin.html display as hashes until re-entered.

## Setup
1. Firebase Realtime Database Rules must grant full read/write to the known admin account. Use the reviewed final `database.rules.json`; its admin access is limited to UID+email. Current Firebase App Check should stay **Monitoring (Enforce OFF)**; this standalone admin APK does not yet have its own App Check attestation provider. If enforced, this admin APK's database calls will be rejected.
2. GitHub Actions builds `:adminapp:assembleRelease` with the existing `JG_LICENSE_HASH_SALT` and release signing keystore secrets. Artifact `JG-Admin-signed-APK`.
3. Install JG Admin separately; enter the same registered Firebase admin email/password. The app will verify the UID.
4. Generate a fresh 8-digit DEVICE_LOCK test key, copy it and test in the customer JG Kanta APK. Verify 1st activation, expiry and owner binding before issuing customer keys.

## Tradeoffs
- This is an owner-only phone app; do not distribute to customers.
- The hash salt must match customer APK and is embedded in the admin APK. It is **not a true server secret**.
- The 'Lifetime' option currently expires on 2099-12-31 because the Android license client requires an ISO date; it is not an infinite-duration license.
- The admin app uses REST instead of heavy Firebase SDK. App Check enforcement will need a separate Android registration and integration later.
- Passing a GitHub compile/signing job does not establish phone UI/network success; test on a real device.
