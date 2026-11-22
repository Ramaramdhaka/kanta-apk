# JG Kanta – Anonymous Firebase Auth + App Check migration (PREVIEW ONLY)

**Do not merge to main, publish new database rules, or distribute the new APK until every item below is complete.** The previous app uses plaintext /keys/12345678, while this preview reads /licenses/<sha256hex> and REQUIRES both an anonymous Firebase Authentication user and an App Check token. The live database has not been altered by these commits. No existing Bluetooth/UI/EncryptedSharedPreferences/ProGuard settings were changed.


## Admin panel — uploaded admin.html updated

The original admin panel provided by the project owner has now been adapted in this branch and saved as **admin.html**. It retains the original visual layout and login and adds one salt input plus the explicit **Copy eligible old keys** button. Its existing Firebase Web API key is reused as *public client configuration*; no password or private key is stored in the HTML.

- Sign in with the actual Firebase Authentication admin account (verified against the known admin UID).
- Enter the **same** salt configured as GitHub Secret \`JG_LICENSE_HASH_SALT\`. The browser keeps the salt in the input during the current session only, without localStorage/cookies.
- **Generate** now creates cryptographically random 8-digit numbers, stores only \`licenses/<hash>\`, initializes \`claimed:false\`, \`uid:""\`, \`deviceId:""\` and preserves status/type/expiry/customer. Collision writes use Firebase \`If-Match: null_etag\`.
- New plaintext numbers are shown in the creation dialog, and remain in memory for Copy until the page closes. Existing hashes cannot be converted back to an 8-digit number. Search for an exact 8-digit number with the salt to match its hash.
- The panel can list both hashed \`licenses\` and old \`keys\`. **Copy eligible old keys** copies unclaimed \`ACTIVE\` or \`HOLD\` keys to the new hashed path *without deleting or changing* the originals. Already-bound, \`USED\` and malformed entries are skipped and need a customer reissue plan.
- Existing Hold/Activate, Reset Device and Delete actions work on the matching collection; these actions still require the admin account. Input values are inserted with \`textContent\` instead of HTML interpolation to avoid customer-name XSS.
- Auth ID tokens refresh automatically. After RTDB App Check enforcement, the web panel requires a registered Firebase **Web app**, its \`WEB_APP_ID\`, its \`RECAPTCHA_V3_SITE_KEY\`, and the hosting domain allowed by reCAPTCHA. Those are **web App Check** settings; the Android phone uses Play Integrity.
- Host the panel from **HTTPS or localhost** for Web Crypto SHA-256 and App Check. Opening \`file://\` directly is not supported for hashed key generation.

### Safe staged rollout: old APK can keep working during migration

1. Back up the entire Realtime Database first (Firebase Console export; preserve it offline).
2. Confirm the actual admin Firebase Authentication **email**, ensure it is verified, and replace the \`REPLACE_WITH_ADMIN_EMAIL@example.com\` placeholder in both **database.rules.migration.json** and **database.rules.json**.
3. Enable Anonymous Authentication in Firebase Console. Register Android and web App Check providers, but **do not enforce** Realtime Database App Check yet.
4. Publish **database.rules.migration.json** first, after carefully checking its content: this *temporarily retains the old public /keys direct-access rules* while creating the new authenticated /licenses branch. Old APKs continue to work, but the known public-access risk remains during the transition.
5. Open the updated HTTPS **admin.html**, sign in, enter the same salt as Android, and click **Copy eligible old keys**. The action never deletes old records and reports copied, existing and failed counts.
6. Verify each migrated license under \`/licenses/<sha256>\` and test an Android build with GitHub Firebase configuration. Already-bound legacy users cannot be silently migrated to anonymous UID ownership; handle them with explicit customer reissue/rebind.
7. Configure web App Check (WEB_APP_ID + RECAPTCHA_V3_SITE_KEY) for the admin page. Once both Android and web clients send valid App Check tokens, test App Check in monitoring mode, then enforce RTDB App Check.
8. **Only after old customers have upgraded and migrated:** publish final **database.rules.json**, which restricts legacy \`/keys\` to the verified admin alone and removes the old anonymous grant. Keep an offline backup; do not delete /keys automatically.

Publishing neither rules file from GitHub changes the live Firebase database. An administrator must explicitly review and Publish in Firebase Console.

## Required Firebase Console configuration (not automated through GitHub)

1. In Firebase Console > Authentication > Sign-in method, **enable Anonymous**. Existing email/password administrator remains separate.
2. In Firebase Console > Project settings, register the existing Android application ID **com.example.bluetoothkanta**, and obtain its Firebase **Web API key**, Firebase Android **App ID** (format 1:NNNN:android:...), and **Project ID**. Do not substitute the Realtime Database URL for the App ID. These are app configuration identifiers, not privileged service-account secrets.
3. In App Check > Apps, register that exact Android application using the **SHA-256 release signing certificate** in \`signing/jg-release.sha256.txt\`. Set provider to **Play Integrity**. For a GitHub-sideloaded APK, check the Play Integrity configuration for distribution **outside Google Play**. Test real installs before enforcing App Check on Realtime Database. Devices without Google Play services or with unsupported integrity results may fail closed.
4. In Firebase Realtime Database > Rules, **do NOT publish** \`database.rules.json\` yet. First replace \`REPLACE_WITH_ADMIN_EMAIL@example.com\` with the exact **verified Firebase Authentication admin email**. The database Rules expression uses the authenticated Firebase **email token**, not the email string supplied by the client.
5. Migrate all license records to \`/licenses/<hash>\` and to the new schema below, using an authenticated administrator. Existing bound devices **cannot** automatically migrate their old \`deviceId\` to a Firebase Anonymous \`uid\`: each affected key requires a planned reissue or admin-owned binding process. Stop the old client / coordinate migration before publishing the new rules.
6. Once migrated, test administrative creation, anonymous unclaimed read, atomic claim, same-user reopen, second-user denied read, immutable expiry/plan, HOLD, ONE_TIME, expiry, offline rejection, invalid key, App Check invalid token, and real device installations. **Only then** publish the reviewed rules and enforce Realtime Database App Check.

## GitHub Actions build config

Go to repository Settings > Secrets and variables > Actions.

Add non-secret **Variables**:
- \`JG_FIREBASE_API_KEY\`: Firebase Web API key (typically starts with AIza...)
- \`JG_FIREBASE_APP_ID\`: Firebase Android App ID (starts with 1:)
- \`JG_FIREBASE_PROJECT_ID\`: the Firebase project ID (for this repository likely \`apk-license\`; confirm in console)

Add a **Secret**:
- \`JG_LICENSE_HASH_SALT\`: a random 24–128 character string from letters, digits, underscores or hyphens. **Back it up securely.** If you change it, all existing license hashes change and user keys stop working. This is **not a real server-side secret**: the value is compiled into the APK and can be extracted. It must match the admin panel hashing input exactly. For real secret-key protection, use a trusted server and HMAC.

Until all config values exist, license login intentionally fails closed and the workflow will **not upload a signed release APK** for this branch.

## Exact matching admin.html hashing function

No \`admin.html\` was found in the GitHub project, so integrate this into your existing admin panel. Use secure HTTPS (Web Crypto requires a secure context):

\`\`\`js
async function hashLicenseKey(eightDigitKey, SECRET_SALT) {
  if (!/^[0-9]{8}$/.test(eightDigitKey)) {
    throw new Error("License key must contain exactly 8 digits");
  }
  if (!/^[A-Za-z0-9_-]{24,128}$/.test(SECRET_SALT)) {
    throw new Error("Invalid license salt configuration");
  }
  const bytes = new TextEncoder().encode(eightDigitKey + SECRET_SALT);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest),
    byte => byte.toString(16).padStart(2, "0")).join("");
}

const hash = await hashLicenseKey(key, SECRET_SALT);
await firebase.database().ref("licenses/" + hash).set({
  status: "ACTIVE",
  type: "DEVICE_LOCK", // or ONE_TIME
  expiry: "2027-12-31",
  customer: "Test Customer",
  plan: "30 Days",
  claimed: false,
  uid: "",
  deviceId: ""
});
\`\`\`

IMPORTANT: Never put a Firebase Admin SDK private key, service-account JSON, account password or signing keystore into \`admin.html\`. Any salt hardcoded into browser JavaScript is visible to that browser user. You can instead enter it into the admin panel at runtime after admin login, but it is still not server secret.

## Updated database schema

\`\`\`json
{
  "licenses": {
    "<64 lowercase hex SHA256 of key + salt>": {
      "customer": "Test Customer",
      "status": "ACTIVE",
      "type": "DEVICE_LOCK",
      "plan": "30 Days",
      "expiry": "2027-12-31",
      "claimed": false,
      "uid": "",
      "deviceId": ""
    }
  }
}
\`\`\`

Once claimed, only \`claimed=true\`, \`uid=<authenticated user UID>\`, \`deviceId=<ANDROID_ID>\` change. The rules check expiry/type/plan/status/customer are unchanged. The app performs ETag conditional PUT; concurrent first claims cannot both win. ONE_TIME uses \`claimed=true\` to enforce one use, without modifying \`status\`.

Rules only permit anonymous read of an **unclaimed** record or the owner's own record. An anonymous identity can be lost after clear-data, uninstall, or auth-account deletion; an admin must handle recovery. Anonymous Auth is not proof of a human and users can create many accounts. App Check helps reduce forged API requests but does not eliminate key guessing or reverse-engineering. A deterministic SHA-256 hash with a salt embedded in the APK is not cryptographic access control.

## Release certificate / VCS protections

\`SecurityGuard\` now fails closed for unconfigured signature pins on release, and checks all observed signing certificates against the pinned SHA-256 certificate fingerprint. The existing GitHub signing workflow derives that pin from the encrypted persistent signing keystore.

AGP 8.7 does **not** accept \`vcsInfo {}\` at the root of \`android {}\`; use \`android { buildTypes { release { vcsInfo { include = false } } } }\` instead. The normal \`dependenciesInfo\` DSL still lives under \`android {}\`. The workflow builds the release and checks the ZIP entries to ensure \`META-INF/version-control-info.textproto\` is absent.

**Status: code staged for review; no live Firebase rules, Firebase Auth setting, App Check setting, or historical key entries have been changed by this repository commit.**
