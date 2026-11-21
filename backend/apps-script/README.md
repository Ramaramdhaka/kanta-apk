# JG Kanta secure Firebase licensing on Spark (Google Apps Script backend)

The Android app must NOT connect directly to Firebase Realtime Database for license writes. The Google Apps Script web app runs as the owner and accesses private Firebase data using a narrowly scoped service account.

## Set up

1. Firebase RTDB rules must not permit public read or write. The example admin UID-only rules in FIREBASE_SPARK_AND_SIGNING_SETUP.md are appropriate for a license-only database, as long as the UID really belongs to your Firebase Authentication admin.
2. In Google Cloud Console, use the Firebase project's associated Google Cloud project. Create a **dedicated service account** with minimum access required to the Firebase Realtime Database. Generate its JSON credentials privately.
3. Open https://script.google.com/ and create a project. Paste the contents of Code.gs. Under Project Settings > Script Properties, add:
   - FIREBASE_DB_URL = https://apk-license-default-rtdb.asia-southeast1.firebasedatabase.app
   - SERVICE_ACCOUNT_EMAIL = the JSON's client_email
   - SERVICE_ACCOUNT_PRIVATE_KEY = the JSON's private_key including PEM delimiters
4. In Apps Script, **Deploy > New deployment > Web app**, Execute as **Me**, Who has access **Anyone**. Carefully review the permissions. Apps Script must be deployed by the account authorized to use its Script Properties.
5. Copy the deployed HTTPS URL ending in /exec. Put it in GitHub repository Settings > Secrets and variables > Actions > **Variables** as JG_LICENSE_API_URL (not as a secret; public app URLs are not authenticators). Do not put credentials in the URL.
6. The Android v7 Gradle build reads JG_LICENSE_API_URL at compilation. Without the URL, login must fail closed with "License backend is not configured."
7. Rebuild the v7 branch after configuring the GitHub variable, and test an ACTIVE DEVICE_LOCK key, ONE_TIME key, HOLD, expiry, invalid key, and two simultaneous devices.
8. Keep signing passwords, service account JSON, private key, and API credentials outside GitHub repositories and issues.

## Security boundaries

- Firebase ETag + conditional PUT make DEVICE_LOCK/ONE_TIME activation atomic. Unconditional PATCH from the Android APK is not secure.
- The Android ID is caller supplied and can be spoofed; 8-digit numeric keys can be guessed. The Apps Script CacheService limiter is only basic throttling. Use additional server-side anti-abuse monitoring and consider longer random license keys for a commercial release.
- Apps Script's UrlFetchApp is quota-limited. A production high-traffic service should have server infrastructure with more robust rate limits and service identity controls.
- Do not merge or distribute the APK until both secure backend and signed release are tested.
- Using Firebase Realtime Database REST with the service account OAuth2 token is supported by Firebase; REST conditional updates require GET with X-Firebase-ETag followed by if-match PUT.
