// PBKDF2WithHmacSHA256 密码哈希 — 与 Java PasswordUtil 完全一致
const crypto = require('crypto');

const ITERATIONS = 100000;
const KEY_LENGTH = 256;
const SALT_BYTES = 16;

function generateSalt() {
  return crypto.randomBytes(SALT_BYTES).toString('base64');
}

function hash(password, salt) {
  const saltBytes = Buffer.from(salt, 'base64');
  const derivedKey = crypto.pbkdf2Sync(
    password,
    saltBytes,
    ITERATIONS,
    KEY_LENGTH / 8,
    'sha256'
  );
  return derivedKey.toString('base64');
}

function verify(password, salt, storedHash) {
  return hash(password, salt) === storedHash;
}

module.exports = { generateSalt, hash, verify };
