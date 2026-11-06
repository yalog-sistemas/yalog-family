import { db, digest, sweep, token } from './accounts.mjs';
sweep();
const code = token();
db.prepare('INSERT INTO enrollment VALUES (?,?)').run(digest(code), Date.now() + 60 * 60 * 1000);
console.log('Convite de cadastro do responsável, válido por uma hora: ' + code);
