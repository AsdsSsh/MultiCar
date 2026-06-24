// JWT HMAC-SHA256 签名 — 与 Java JwtUtil 完全一致
const crypto = require('crypto');
const config = require('../config');

function base64UrlEncode(data) {
  return Buffer.from(data)
    .toString('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

function sign(data) {
  const hmac = crypto.createHmac('sha256', config.jwt.secret);
  hmac.update(data);
  return base64UrlEncode(hmac.digest());
}

function generate(username, role) {
  const now = Date.now();
  const header = { alg: 'HS256', typ: 'JWT' };
  const payload = {
    sub: username,
    role: role,
    iat: now,
    exp: now + config.jwt.expiresMs,
  };

  const headerB64 = base64UrlEncode(JSON.stringify(header));
  const payloadB64 = base64UrlEncode(JSON.stringify(payload));
  const signature = sign(headerB64 + '.' + payloadB64);

  return headerB64 + '.' + payloadB64 + '.' + signature;
}

function verify(token) {
  if (!token) return null;
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;

    const expectedSig = sign(parts[0] + '.' + parts[1]);
    if (parts[2] !== expectedSig) return null;

    const payloadJson = Buffer.from(parts[1], 'base64').toString('utf8');
    const payload = JSON.parse(payloadJson);

    if (Date.now() > payload.exp) return null;

    return payload;
  } catch (e) {
    return null;
  }
}

module.exports = { generate, verify };
