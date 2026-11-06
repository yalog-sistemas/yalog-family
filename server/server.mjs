import { createServer as createHttp } from 'node:http';
import { createServer as createHttps } from 'node:https';
import { readFileSync } from 'node:fs';
import { cleanLogin, db, digest, hashPassword, sweep, token, transaction,
  validPassword, validToken, verifyPassword } from './accounts.mjs';

sweep();
setInterval(sweep, 60 * 60 * 1000).unref();
const files = new Map([
  ['/controle', ['text/html; charset=utf-8', readFileSync(new URL('./controle.html', import.meta.url))]],
  ['/controle.js', ['text/javascript; charset=utf-8', readFileSync(new URL('./controle.js', import.meta.url))]],
]);
const attempts = new Map();
let cryptoRequests = 0;
const cookieKey = req => {
  const value = /(?:^|;\s*)__Host-yalog_parent=([A-Za-z0-9_-]{43})(?:;|$)/.exec(req.headers.cookie || '')?.[1];
  return value ? digest(value) : null;
};
const validSession = req => db.prepare('SELECT family_id FROM sessions WHERE digest=? AND expires>?')
  .get(cookieKey(req), Date.now());
function sessionCookie(res, value = '', seconds = 0) {
  res.setHeader('Set-Cookie', '__Host-yalog_parent=' + value + '; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=' + seconds);
}
function rateLimited(path, login) {
  const now = Date.now();
  for (const [key, value] of attempts) if (value.expires <= now) attempts.delete(key);
  const key = path + ':' + (login || '');
  if (attempts.size >= 2000 && !attempts.has(key)) return true;
  const entry = attempts.get(key) ?? { count: 0, expires: now + 60000 };
  entry.count++;
  attempts.set(key, entry);
  return entry.count > 8 || cryptoRequests >= 3;
}
async function accountAction(req, res, path) {
  const data = await body(req);
  const login = cleanLogin(data.login);
  if (rateLimited(path, login)) return reply(res, 429, {error: 'Aguarde um minuto e tente novamente.'});
  if (!login || !validPassword(data.password)) return reply(res, 400, {error: 'Confira o usuário e a senha de 12 a 128 caracteres.'});
  cryptoRequests++;
  try {
    if (path === '/api/login') {
      const account = db.prepare('SELECT id,secret FROM families WHERE login=?').get(login);
      if (!await verifyPassword(data.password, account?.secret)) return reply(res, 401, {error: 'Usuário ou senha inválidos.'});
      const key = token();
      const result = db.prepare(`INSERT INTO sessions SELECT ?,id,? FROM families WHERE id=? AND secret=?`)
        .run(digest(key), Date.now() + 28800000, account.id, account.secret);
      if (!result.changes) return reply(res, 401, {error: 'Credenciais alteradas. Entre novamente.'});
      sessionCookie(res, key, 28800);
      return reply(res, 200, {ok: true});
    }
    if (!validToken(data.code)) return reply(res, 400, {error: 'Código inválido.'});
    const code = digest(data.code);
    const registration = path === '/api/register';
    const allowed = registration
      ? db.prepare('SELECT 1 FROM enrollment WHERE digest=? AND expires>?').get(code, Date.now())
      : db.prepare('SELECT 1 FROM families WHERE login=? AND recovery=?').get(login, code);
    if (!allowed) return reply(res, 400, {error: 'Código inválido ou já utilizado.'});
    const secret = await hashPassword(data.password);
    const recovery = token();
    const changed = transaction(() => {
      if (registration) {
        if (db.prepare('SELECT 1 FROM families WHERE login=?').get(login)) return false;
        const invite = db.prepare('DELETE FROM enrollment WHERE digest=? AND expires>?').run(code, Date.now());
        if (!invite.changes) return false;
        db.prepare('INSERT INTO families VALUES (?,?,?,?)').run(token(16), login, secret, digest(recovery));
        return true;
      }
      const row = db.prepare('UPDATE families SET secret=?, recovery=? WHERE login=? AND recovery=? RETURNING id')
        .get(secret, digest(recovery), login, code);
      if (!row) return false;
      db.prepare('DELETE FROM sessions WHERE family_id=?').run(row.id);
      return true;
    });
    return changed ? reply(res, 200, {recovery}) : reply(res, 400, {error: 'Usuário ou código indisponível.'});
  } finally {
    cryptoRequests--;
  }
}
function reply(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(payload));
}
async function body(req) {
  let size = 0;
  const parts = [];
  for await (const part of req) {
    size += part.length;
    if (size > 65536) throw Error('Tamanho inválido');
    parts.push(part);
  }
  const data = JSON.parse(Buffer.concat(parts));
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw Error('Formato inválido');
  return data;
}
function device(req) {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '');
  return match && db.prepare('SELECT id, rules FROM devices WHERE digest=?').get(digest(match[1]));
}
function validLocation(point) {
  return point && Number.isFinite(point.lat) && Math.abs(point.lat) <= 90
    && Number.isFinite(point.lon) && Math.abs(point.lon) <= 180
    && Number.isFinite(point.accuracy) && point.accuracy >= 0
    && Number.isSafeInteger(point.at) && point.at > 0 && point.at <= Date.now() + 600000;
}
function validUsage(usage) {
  return usage && /^\d{4}-\d{2}-\d{2}$/.test(usage.day)
    && Array.isArray(usage.apps) && usage.apps.length <= 150 && usage.apps.every(app =>
      app && typeof app.label === 'string' && app.label.length > 0 && app.label.length <= 160
      && Number.isSafeInteger(app.seconds) && app.seconds >= 0 && app.seconds <= 172800);
}
async function route(req, res) {
  const path = req.url?.split('?')[0];
  const post = req.method === 'POST';
  if (req.method === 'GET' && path === '/healthz') {
    db.prepare('SELECT 1 FROM families LIMIT 1').get();
    return reply(res, 200, {ok: true});
  }
  if (req.method === 'GET' && path === '/') {
    res.writeHead(302, { Location: '/controle' }).end();
    return;
  }
  if (req.method === 'GET' && files.has(path)) {
    const [type, data] = files.get(path);
    res.writeHead(200, { 'Content-Type': type }).end(data);
    return;
  }
  if (post && !/^application\/json(?:;|$)/i.test(req.headers['content-type'] || '')) {
    return reply(res, 415, { error: 'Envie JSON.' });
  }
  if (post && ['/api/login', '/api/register', '/api/recover'].includes(path)) {
    return accountAction(req, res, path);
  }
  if (post && path === '/api/pair') {
    const { code } = await body(req);
    if (typeof code !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(code)) {
      return reply(res, 400, { error: 'Convite inválido.' });
    }
    db.exec('BEGIN IMMEDIATE');
    try {
      const invite = db.prepare('DELETE FROM invites WHERE digest=? AND expires>? RETURNING label,family_id').get(digest(code), Date.now());
      if (!invite) {
        db.exec('ROLLBACK');
        return reply(res, 404, { error: 'Convite expirado ou já utilizado.' });
      }
      const key = token();
      db.prepare('INSERT INTO devices (id,digest,label,paired,family_id) VALUES (?,?,?,?,?)')
        .run(token(16), digest(key), invite.label, Date.now(), invite.family_id);
      db.exec('COMMIT');
      return reply(res, 200, { token: key, label: invite.label });
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  }
  if (post && ['/api/report', '/api/disconnect'].includes(path)) {
    const data = await body(req);
    const linked = device(req);
    if (!linked) return reply(res, 401, { error: 'Aparelho desvinculado.' });
    if (path === '/api/disconnect') {
      db.prepare('DELETE FROM devices WHERE id=?').run(linked.id);
      return reply(res, 200, { ok: true });
    }
    if ((data.location != null && !validLocation(data.location))
        || (data.usage != null && !validUsage(data.usage))) {
      return reply(res, 400, { error: 'Resumo inválido.' });
    }
    db.prepare('UPDATE devices SET location=?, usage=?, updated=? WHERE id=?').run(
      data.location == null ? null : JSON.stringify(data.location),
      data.usage == null ? null : JSON.stringify(data.usage),
      Date.now(), linked.id);
    return reply(res, 200, { ok: true, rules: JSON.parse(linked.rules) ?? [] });
  }
  const session = validSession(req);
  if (!session) return reply(res, 401, { error: 'Entre como responsável.' });
  if (post && path === '/api/logout') {
    db.prepare('DELETE FROM sessions WHERE digest=?').run(cookieKey(req));
    sessionCookie(res);
    return reply(res, 200, {ok: true});
  }
  if (post && path === '/api/delete-family') {
    const { password } = await body(req);
    if (!validSession(req)) return reply(res, 401, {error: 'Entre novamente.'});
    if (rateLimited(path, session.family_id)) return reply(res, 429, {error: 'Aguarde um minuto.'});
    cryptoRequests++;
    try {
      const account = db.prepare('SELECT secret FROM families WHERE id=?').get(session.family_id);
      if (!await verifyPassword(password, account?.secret)) return reply(res, 403, {error: 'Senha inválida.'});
      if (!validSession(req)) return reply(res, 401, {error: 'Entre novamente.'});
      const removed = db.prepare('DELETE FROM families WHERE id=? AND secret=?').run(session.family_id, account.secret);
      if (!removed.changes) return reply(res, 401, {error: 'Entre novamente.'});
      sessionCookie(res);
      return reply(res, 200, {ok: true});
    } finally {
      cryptoRequests--;
    }
  }
  if (post && path === '/api/invite') {
    const { label } = await body(req);
    if (!validSession(req)) return reply(res, 401, {error: 'Entre novamente.'});
    if (typeof label !== 'string' || !label.trim() || label.trim().length > 80) {
      return reply(res, 400, { error: 'Informe um nome de até 80 caracteres.' });
    }
    const code = token(16);
    const expires = Date.now() + 600000;
    db.prepare('DELETE FROM invites WHERE expires<=?').run(Date.now());
    db.prepare('INSERT INTO invites VALUES (?,?,?,?)').run(digest(code), expires, label.trim(), session.family_id);
    return reply(res, 201, { code, expires });
  }
  if (post && path === '/api/rules') {
    const { id, rules } = await body(req);
    if (!validSession(req)) return reply(res, 401, {error: 'Entre novamente.'});
    const validTime = value => typeof value === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value);
    if (typeof id !== 'string' || !Array.isArray(rules) || rules.length > 20
        || !rules.every(rule => rule && typeof rule.app === 'string'
          && /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(rule.app)
          && rule.app.length <= 160 && Number.isInteger(rule.minutes)
          && rule.minutes >= 0 && rule.minutes <= 1440
          && ((rule.from === '' && rule.to === '') || (validTime(rule.from) && validTime(rule.to) && rule.from !== rule.to)))
        || new Set(rules.map(rule => rule.app)).size !== rules.length) {
      return reply(res, 400, { error: 'Confira os aplicativos, os horários e os limites.' });
    }
    const result = db.prepare('UPDATE devices SET rules=? WHERE id=? AND family_id=?').run(JSON.stringify(rules), id, session.family_id);
    return result.changes ? reply(res, 200, {ok: true}) : reply(res, 404, {error: 'Aparelho desvinculado.'});
  }
  if (post && path === '/api/revoke') {
    const { id } = await body(req);
    if (!validSession(req)) return reply(res, 401, {error: 'Entre novamente.'});
    if (typeof id !== 'string') return reply(res, 400, { error: 'Identificador inválido.' });
    db.prepare('DELETE FROM devices WHERE id=? AND family_id=?').run(id, session.family_id);
    return reply(res, 200, { ok: true });
  }
  if (req.method === 'GET' && path === '/api/devices') {
    const devices = db.prepare('SELECT id,label,paired,location,usage,updated,rules FROM devices WHERE family_id=? ORDER BY paired DESC').all(session.family_id);
    return reply(res, 200, { devices: devices.map(row => ({
      ...row, location: JSON.parse(row.location), usage: JSON.parse(row.usage), rules: JSON.parse(row.rules) ?? []
    })) });
  }
  reply(res, 404, { error: 'Página indisponível.' });
}

function handle(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  route(req, res).catch(error => {
    if (res.headersSent || res.destroyed) return;
    if (error instanceof SyntaxError || error.message === 'Formato inválido' || error.message === 'Tamanho inválido') {
      return reply(res, 400, { error: 'Corpo inválido.' });
    }
    console.error(error.code || error.name);
    reply(res, 500, { error: 'Falha no servidor.' });
  });
}
const { YALOG_TLS_CERT, YALOG_TLS_KEY, YALOG_BIND, YALOG_MANAGED_HTTPS } = process.env;
if (!!YALOG_TLS_CERT !== !!YALOG_TLS_KEY) throw Error('Configure certificado e chave juntos.');
const managedHttps = YALOG_MANAGED_HTTPS === '1';
if (YALOG_BIND && !YALOG_TLS_CERT && !managedHttps) throw Error('A exposição à rede exige TLS.');
if (managedHttps && !process.env.YALOG_DB) throw Error('Configure YALOG_DB em volume persistente.');
const server = YALOG_TLS_CERT
  ? createHttps({cert: readFileSync(YALOG_TLS_CERT), key: readFileSync(YALOG_TLS_KEY)}, handle)
  : createHttp(handle);
server.listen(Number(process.env.PORT || 8080), YALOG_BIND || '127.0.0.1', () => {
  console.log(YALOG_TLS_CERT
    ? 'Painel: https://' + (YALOG_BIND || '127.0.0.1') + ':' + server.address().port + '/controle'
    : managedHttps ? 'Servidor pronto atrás do proxy HTTPS da hospedagem.'
      : 'Servidor local pronto. Use um proxy HTTPS para acesso pelo celular.');
});
