import { createHash, randomBytes, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { DatabaseSync } from 'node:sqlite';

const derive = promisify(scrypt);
export const digest = value => createHash('sha256').update(value).digest('hex');
export const token = (bytes = 32) => randomBytes(bytes).toString('base64url');
export const db = new DatabaseSync(process.env.YALOG_DB || 'family-v2.sqlite');
db.exec('PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON; PRAGMA busy_timeout=5000;');
const columns = db.prepare('PRAGMA table_info(devices)').all();
if (columns.length && !columns.some(column => column.name === 'family_id')) {
  throw Error('Banco do piloto anterior. Use um banco novo e vincule os aparelhos novamente.');
}
db.exec(`
  CREATE TABLE IF NOT EXISTS families (
    id TEXT PRIMARY KEY, login TEXT UNIQUE NOT NULL, secret TEXT NOT NULL, recovery TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS enrollment (digest TEXT PRIMARY KEY, expires INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS sessions (
    digest TEXT PRIMARY KEY, family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    expires INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS invites (
    digest TEXT PRIMARY KEY, expires INTEGER NOT NULL, label TEXT NOT NULL,
    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE
  );
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY, digest TEXT UNIQUE NOT NULL, label TEXT NOT NULL,
    paired INTEGER NOT NULL, location TEXT, usage TEXT, updated INTEGER, rules TEXT,
    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE
  );
  CREATE INDEX IF NOT EXISTS devices_family ON devices(family_id);
  CREATE INDEX IF NOT EXISTS sessions_family ON sessions(family_id);
  CREATE INDEX IF NOT EXISTS invites_family ON invites(family_id);
`);
export const cleanLogin = value => typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9._-]{2,39}$/.test(value.trim())
  ? value.trim().toLowerCase() : null;
export const validPassword = value => typeof value === 'string' && value.length >= 12 && value.length <= 128;
export const validToken = value => typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value);
export async function hashPassword(password, salt = token(16)) {
  const key = await derive(password, salt, 64, { N: 32768, maxmem: 64 * 1024 * 1024 });
  return salt + ':' + key.toString('hex');
}
export async function verifyPassword(password, stored) {
  if (!validPassword(password)) return false;
  const salt = stored?.split(':')[0] ?? 'dummy-salt-for-missing-account';
  const candidate = await hashPassword(password, salt);
  return !!stored && timingSafeEqual(Buffer.from(candidate), Buffer.from(stored));
}
export function transaction(action) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = action();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}
export function sweep() {
  const now = Date.now();
  for (const table of ['sessions', 'invites', 'enrollment']) {
    db.prepare('DELETE FROM ' + table + ' WHERE expires<=?').run(now);
  }
  db.prepare('UPDATE devices SET location=NULL,usage=NULL,updated=NULL WHERE updated<=?')
    .run(now - 7 * 86400000);
}
