# JG Kanta — Spark plan security and GitHub signing setup

## Firebase Realtime Database (Spark)

Your old rules permit anonymous `keys/$key` reads and anonymous child PATCH writes. They are **not safe** for a commercial license server.

In Firebase Console > Realtime Database > Rules, use the following **only if this database contains just the license keys and the chosen UID is your actual Firebase Authentication admin account**:

```json
{
  "rules": {
    "keys": {
      ".read": "auth != null && auth.uid == 'QFhyikjUbYZ3eL3I1QQh0CHYfJR2'",
      ".write": "auth != null && auth.uid == 'QFhyikjUbYZ3eL3I1QQh0CHYfJR2'"
    }
  }
}
```

These rules must be published manually in Firebase Console: committing this file does not change your live database. Review all other database paths if the database contains non-license data. Firebase Authentication UID is **not** a private password.

**Current blocker:** The existing Android v7 LicenseVerifier uses unauthenticated direct GET/PATCH on the Firebase REST database. This is only a prototype and **will be blocked** by secure Rules. Do not loosen Rules to make the app work.

For the Spark plan, use a separate Google Apps Script web app as the backend. Keep its service-account credentials in **Apps Script Script Properties** (never in this public repository or APK), and implement server-side checks plus atomic state changes for ONE_TIME / DEVICE_LOCK. Do not grant the public web app arbitrary database write endpoints. Before production, add effective rate limiting, abuse monitoring and server-signed short-lived responses. Deploy the backend and configure the Android endpoint before merging PR #1.

## Android signed release APK using GitHub Actions — no local keystore required

The default main branch contains a manual workflow:
`.github/workflows/initialize-signing.yml`.

1. Open repository > Settings > Secrets and variables > Actions.
2. Create **two separate repository secrets**:
   - `JG_RELEASE_STORE_PASSWORD`: strong generated password of at least 16 characters.
   - `JG_KEYSTORE_WRAP_SECRET`: independent, strong randomly generated password of at least 24 characters, preferably 32+.
3. **Save both secrets in an offline or independent password manager / secure backup.** Do not put them in an issue, chat, README, or repository file.
4. Actions > **Initialize JG Release Signing Key** > Run workflow from main. Run **once only**.
5. Successful workflow commits **only** `signing/jg-release.p12.gpg` (AES256-encrypted PKCS12 signing key) and `signing/jg-release.sha256.txt` (public SHA-256 certificate fingerprint). No plain private key is committed. The workflow refuses to replace an existing signing identity.
6. Keep a second **offline encrypted** backup of the keystore and the exact secrets. If both key recovery and secrets are lost, future APKs will not install as an update to earlier APKs signed with that identity.
7. After approved v7 changes are merged, `build.yml` automatically decrypts the stable keystore using Actions secrets for main-branch runs, checks its fingerprint, signs the R8 release APK, and uploads the `JG-Kanta-signed-release-APK` artifact.
8. Do not release or install the signed APK commercially until Firebase backend authorization is secure and testing succeeds.

**Important:** The initialization workflow cannot create GitHub Secrets itself. The GitHub integration can maintain code but the user must enter secret values privately via the GitHub UI. Existing debug and new release APKs have different signing certificates; uninstall of old debug APK may be required.

## Build status

The v7 branch previously compiled debug and minified release successfully. The GitHub Actions logs are the source of truth for subsequent builds. This README does not claim license backend deployment or signing execution has occurred.
