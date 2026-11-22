# JG Kanta – fresh hashed-license reset (no old-key migration)

**Owner agreed that old license keys can stop working.** Do not delete Firebase records or revoke existing clients until the new signed APK, Firebase Rules, administrator dashboard and new license claiming have been tested on a real phone.

## Fix admin HTTP 401 and permanently remember salt (2026-11-22)

**Why 401 happens:** The admin can sign in and see legacy `/keys`, but the live Realtime Database Rules do not yet grant this admin write access under `/licenses`. No JavaScript change can override live database rules.

**Fix:** If intentionally retiring all old licenses, open Firebase Console > Realtime Database > Rules, replace the existing rules with the final reviewed `database.rules.json` in this same branch, and press Publish. Admin access is bound to the existing UID and email. This immediately revokes old APK reads/claims; preserve the exported backup first. Do not press App Check Enforce; it remains Monitoring until web attestation is configured.

**Domain and deployment:** The admin site shown by the owner is `https://jglegha.online/admin.html`. The GitHub source file and hosted website are distinct. After updating code in the security branch, deploy/copy the newest `admin.html` to the actual hosting serving `jglegha.online`. A browser hard reload alone cannot deploy GitHub changes. Do not deploy it publicly with a plaintext salt or credentials.

**Encrypted salt vault:** After the updated page is deployed, log in using the Firebase admin email/password, enter your existing `JG_LICENSE_HASH_SALT` value once, click **Remember Salt on this browser**. The value is saved only as PBKDF2/SHA-256 (310,000 rounds) derived AES-GCM ciphertext in that browser's localStorage. The login password is NOT stored and is cleared from the form. The next login on the same browser/password automatically decrypts and restores the salt. If the admin password changes, or storage is cleared, restore from your password manager and click Remember again. The browser is a trust boundary; malicious browser extensions or device compromise can access the salt after login. No browser-local storage is a substitute for a secure off-device backup.

## Confirmed setup
- Firebase Authentication Anonymous and Email/Password providers: enabled in Console
- Android Firebase App Check Play Integrity: registered for the release certificate
- Realtime Database App Check: **Monitoring** (Enforce OFF)
- Original database backup: user confirmed complete
- Hash salt: owner confirmed adding `JG_LICENSE_HASH_SALT` to repository GitHub Actions Secrets
- Admin identity: Firebase Auth UID `QFhyikjUbYZ3eL3I1QQh0CHYfJR2` and email `jogaleghakanod@gmail.com`

## Fresh cutover sequence

1. **No legacy migration needed.** The security branch's admin.html generates fresh 8-digit keys stored under hashed `/licenses/<sha256(key + salt)>`.
2. Because old license keys can be retired, a **direct switch to final `database.rules.json`** is simpler than temporary migration rules. Publishing the final rules **immediately denies old /keys client access**. Only do this when intentionally ready to issue new keys to anyone still relying on old licenses. The verified admin UID+email remain authorized; App Check enforcement stays OFF.
3. Open the actual HTTPS-hosted **admin.html** in a browser; GitHub's source-file `blob` page alone is not a hosted app. Log in using your own verified admin identity, and enter the same salt as GitHub's secret. **Never publish the salt in HTML source**, issue tracker, or chat.
4. Generate **one fresh test key** for DEVICE_LOCK. Check that `/licenses` now contains a new 64-character hashed node. Keep the plaintext 8-digit number separately; hash cannot be reversed.
5. Install the **security branch's signed release APK** on one test phone only. Verify Play Integrity token, anonymous sign-in, first claim, reopening with same account, expiry, another phone rejection, and Bluetooth/floating behavior. Do not install debug APK when testing release signature pinning.
6. Once new login and admin controls work, confirm final Rules continue to block anonymous access to legacy /keys and allow only authorized first claims under /licenses.
7. Once satisfied, delete old `/keys` subtree **manually in Firebase Console** after checking backup; that is irreversible for users still using previous keys. You may leave /keys present and admin-only instead of deleting.
8. App Check Realtime Database enforcement remains OFF until **both** Android app and admin web app have been verified to supply valid App Check tokens. If you choose no admin Web App Check, keep enforcement OFF. Anonymous auth + per-node Firebase Rules still apply.

### Known limitations
- **Clean cutover revokes old key holders.** Any customer relying on old keys will need a newly issued license.
- Key+salt embedded in APK is not a true server secret. SHA-256 conceals plaintext key values at rest but does not eliminate brute-force of 8-digit keys by a determined attacker.
- Admin panel is not an App Check-protected web client unless its Fraud Defense/reCAPTCHA Enterprise provider is configured.
- Android Play Integrity registration alone does not guarantee every GitHub-sideloaded device receives valid tokens; run real phone tests first.

Nothing in this file publishes rules or deletes existing Firebase records.
