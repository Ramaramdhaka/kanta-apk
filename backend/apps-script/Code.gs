/**
 * JG Kanta license backend for Google Apps Script (Spark plan compatible).
 *
 * Deployment: Deploy > New deployment > Web app > Execute as Me > Anyone.
 * Script Properties (NEVER commit their values):
 *   FIREBASE_DB_URL = https://apk-license-default-rtdb.asia-southeast1.firebasedatabase.app
 *   SERVICE_ACCOUNT_EMAIL = <Google Cloud service account client_email>
 *   SERVICE_ACCOUNT_PRIVATE_KEY = <PEM private_key of that account>
 *
 * Service account must be narrowly authorized for Firebase Realtime Database access.
 * No Android client receives admin credentials or database write permission.
 *
 * This is NOT strong hardware attestation. 8-digit numeric keys have limited entropy;
 * Android ID is client-provided and spoofable. Add external anti-abuse protection for
 * production and prefer longer licenses.
 */
const RATE_WINDOW_SECONDS = 600;
const MAX_ATTEMPTS_PER_DEVICE = 5;
const MAX_ATTEMPTS_PER_KEY = 15;
const JWT_SCOPE = 'https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/userinfo.email';
const OAUTH_TOKEN_URL = 'https://oauth2.googleapis.com/token';

function doGet(e) {
  return json_({valid:false,message:'POST required.'});
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents || e.postData.contents.length > 1500) {
      return json_({valid:false,message:'Invalid request.'});
    }
    const request = JSON.parse(e.postData.contents);
    const key = String(request.key || '').trim();
    const deviceId = String(request.deviceId || '').trim();
    if (!/^[0-9]{8}$/.test(key) || !/^[a-f0-9]{8,64}$/i.test(deviceId)) {
      return json_({valid:false,message:'Invalid License Key!',badAttempt:true});
    }
    const limiter = rateLimit_(key, deviceId);
    if (limiter) return json_({valid:false,message:limiter,badAttempt:true});
    const result = verifyKey_(key, deviceId);
    return json_(result);
  } catch (err) {
    // Never log private keys, credentials, full request URLs, or device IDs.
    console.error('License service error: ' + (err && err.name || 'ServerError'));
    return json_({valid:false,message:'License server temporarily unavailable. Try again later.'});
  }
}

function verifyKey_(key, deviceId) {
  const url = databaseUrl_() + '/keys/' + key + '.json';
  // Retry ETag CAS rather than letting concurrent devices redeem the same key.
  for (let attempt = 0; attempt < 4; attempt++) {
    const token = accessToken_();
    const fetched = UrlFetchApp.fetch(url, {
      method:'get',
      headers: {'Authorization':'Bearer ' + token,'X-Firebase-ETag':'true'},
      muteHttpExceptions:true
    });
    const code = fetched.getResponseCode();
    if (code !== 200) return {valid:false,message:'Database verification temporarily unavailable.'};
    const raw = fetched.getContentText();
    if (!raw || raw === 'null') return {valid:false,message:'Invalid License Key!',badAttempt:true};
    let record;
    try { record = JSON.parse(raw); } catch (_) { return {valid:false,message:'Invalid license record.'}; }
    if (!record || typeof record !== 'object') {
      return {valid:false,message:'Invalid License Key!',badAttempt:true};
    }

    if (record.status === 'HOLD') return denied_('Your Key is on HOLD (Locked).');
    if (record.status === 'USED') return denied_('This One-Time Key has already been used.');
    if (record.status !== 'ACTIVE') return denied_('License Key is not ACTIVE.');
    if (typeof record.expiry !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(record.expiry)) {
      return denied_('Invalid license expiry date.');
    }
    const today = Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
    if (today > record.expiry) return denied_('Your License Key has Expired.');

    let updated = null;
    if (record.type === 'ONE_TIME') {
      updated = Object.assign({}, record, {status:'USED',deviceId:deviceId});
    } else if (record.type === 'DEVICE_LOCK') {
      const registered = String(record.deviceId || '').trim();
      if (registered && registered !== deviceId) {
        return denied_('This Key is already registered on another mobile.');
      }
      if (!registered) updated = Object.assign({}, record, {deviceId:deviceId});
    } else {
      return denied_('Unsupported license type.');
    }
    if (updated !== null) {
      const headers = fetched.getAllHeaders();
      const etag = headers.ETag || headers.Etag || headers.etag;
      if (!etag) return {valid:false,message:'License transaction ETag missing.'};
      const put = UrlFetchApp.fetch(url, {
        method:'put',
        contentType:'application/json',
        payload:JSON.stringify(updated),
        headers:{'Authorization':'Bearer ' + token,'if-match':etag},
        muteHttpExceptions:true
      });
      if (put.getResponseCode() === 412) continue;
      if (put.getResponseCode() !== 200) {
        return {valid:false,message:'Unable to activate license right now.'};
      }
    }
    return {valid:true,message:'License verified successfully.',type:record.type,expiry:record.expiry};
  }
  return {valid:false,message:'License is busy. Please retry.'};
}

function denied_(message) { return {valid:false,message:message,badAttempt:true}; }
function json_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}

function rateLimit_(key, deviceId) {
  // Basic device/key throttling. Not IP-grade abuse protection; Apps Script has no
  // trustworthy anonymous client IP and callers can spoof deviceId.
  const cache = CacheService.getScriptCache();
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(4000)) return 'License service is busy. Please try again.';
  try {
    const deviceSlot = 'dev_' + sha256_(deviceId).slice(0,48);
    const keySlot = 'key_' + sha256_(key).slice(0,48);
    const deviceCount = Number(cache.get(deviceSlot) || 0);
    const keyCount = Number(cache.get(keySlot) || 0);
    if (deviceCount >= MAX_ATTEMPTS_PER_DEVICE || keyCount >= MAX_ATTEMPTS_PER_KEY) {
      return 'Too many wrong attempts! Try again after 10 minutes.';
    }
    // Count requests, not only failures: unauthenticated clients may otherwise
    // brute-force keys. Reset automatically when the cache entry expires.
    cache.put(deviceSlot, String(deviceCount + 1), RATE_WINDOW_SECONDS);
    cache.put(keySlot, String(keyCount + 1), RATE_WINDOW_SECONDS);
    return null;
  } finally { lock.releaseLock(); }
}

function sha256_(text) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, text)
    .map(function(byte) { return ('0' + ((byte & 255).toString(16))).slice(-2); }).join('');
}

function databaseUrl_() {
  const value = PropertiesService.getScriptProperties().getProperty('FIREBASE_DB_URL') || '';
  if (!/^https:\/\/[a-z0-9-]+\.[a-z0-9-]+\.firebasedatabase\.app\/?$/i.test(value)) {
    throw new Error('Invalid FIREBASE_DB_URL');
  }
  return value.replace(/\/$/, '');
}

function accessToken_() {
  const cache = CacheService.getScriptCache();
  const cached = cache.get('firebase_oauth_v1');
  if (cached) return cached;
  const props = PropertiesService.getScriptProperties();
  const email = props.getProperty('SERVICE_ACCOUNT_EMAIL');
  const privateKey = props.getProperty('SERVICE_ACCOUNT_PRIVATE_KEY');
  if (!email || !privateKey) throw new Error('Service account properties missing');
  const now = Math.floor(Date.now() / 1000);
  const header = url64_(JSON.stringify({alg:'RS256',typ:'JWT'}));
  const claims = url64_(JSON.stringify({
    iss:email,scope:JWT_SCOPE,aud:OAUTH_TOKEN_URL,
    iat:now,exp:now + 3300
  }));
  const unsigned = header + '.' + claims;
  const signedBytes = Utilities.computeRsaSha256Signature(unsigned, privateKey.replace(/\\n/g,'\n'));
  const signed = unsigned + '.' + url64_(signedBytes);
  const response = UrlFetchApp.fetch(OAUTH_TOKEN_URL, {
    method:'post',
    payload:{grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:signed},
    muteHttpExceptions:true
  });
  if (response.getResponseCode() !== 200) throw new Error('OAuth token request denied');
  const result = JSON.parse(response.getContentText());
  if (!result.access_token) throw new Error('OAuth access token missing');
  cache.put('firebase_oauth_v1', result.access_token, Math.min(3000,Math.max(60,(result.expires_in || 3600)-120)));
  return result.access_token;
}

function url64_(input) {
  const bytes = typeof input === 'string' ? Utilities.newBlob(input).getBytes() : input;
  return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/,'');
}
