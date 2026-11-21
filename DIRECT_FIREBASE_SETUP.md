# JG Kanta v8 — Fast Direct Firebase (Spark)

**Google Apps Script is removed from the Android login.** The APK directly contacts the Firebase Realtime Database via HTTPS and uses GET with `X-Firebase-ETag: true` and conditional PUT with `If-Match` for first-use activation. For an already-locked DEVICE_LOCK key, verification is a single GET.

## Important: Firebase Rules must be published separately

Open Firebase Console > Realtime Database > Rules and paste **firebase-rules-direct.json** from this repository. Click **Publish**. This file alone does **not** update live Firebase.

The rules preserve full admin access for Firebase Authentication UID `QFhyikjUbYZ3eL3I1QQh0CHYfJR2`. They permit public read of individual 8-digit license records and narrowly constrained conditional updates for claims. They do NOT allow public creation of new keys, changing customer/type/expiry during a claim, changing a bound DEVICE_LOCK key, or changing a USED/HOLD key back to ACTIVE.

**Security warning:** These client-only rules are weaker than server-side verification. Attackers who know or guess an 8-digit key can read license details and may redeem/consume unclaimed keys; scripts can bypass on-device local lockout. XOR and R8 do not prevent this. If you require strong commercial DRM, you need an authenticated trusted server with rate limits and stronger keys. Do not store sensitive customer details in records exposed to anonymous reads.

## Expected records under /keys

```json
{
  "12345678": {
    "customer":"Test Customer",
    "status":"ACTIVE",
    "type":"DEVICE_LOCK",
    "deviceId":"",
    "expiry":"2027-12-31"
  }
}
```

For ONE_TIME use `"type":"ONE_TIME"`. Both activation modes use Firebase ETag CAS PUT; two simultaneous clients cannot successfully claim the same record without the second client seeing the changed state on retry.

## GitHub signed release

The encrypted signing key is already stored in the repository and signing secrets are configured. GitHub Actions builds R8-obfuscated signed APK using the stable signing identity. **Do not run the Initialize signing workflow again.** The debug and signed release builds run on this branch.

## Test before release

Confirm: invalid key, ACTIVE DEVICE_LOCK first claim, same-device restart, second-device denial, ONE_TIME first use and used denial, HOLD, expired key, offline fail-closed, five failed attempts/10-minute lockout, both Bluetooth receiver connections. Real Firebase rules and network behavior must be tested on devices.

## Speed

The HTTP client uses connection pooling and one request for a previously claimed DEVICE_LOCK key. First activation has a GET + conditional PUT, required for atomic claiming. Actual latency depends on network and Firebase region.

## No Apps Script

The old `backend/apps-script` source was removed from the active branch. No Google Apps Script URL or secret is needed.
