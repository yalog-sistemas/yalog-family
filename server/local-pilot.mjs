import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { isIPv4 } from 'node:net';
import { join, resolve } from 'node:path';

const address = process.argv[2];
if (!isIPv4(address) || !/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(address)) {
  throw Error('Informe o IPv4 privado do notebook na rede Wi-Fi.');
}
const directory = resolve('piloto-local');
mkdirSync(directory, { recursive: true, mode: 0o700 });
const ca = join(directory, 'yalog-piloto-ca.crt');
const caKey = join(directory, 'yalog-piloto-ca.key');
const endpointCert = join(directory, address + '.crt');
const endpointKey = join(directory, address + '.key');

function openssl(args) {
  execFileSync(process.env.OPENSSL_BIN || 'openssl', args, { stdio: ['ignore', 'ignore', 'inherit'] });
}
if (existsSync(ca) !== existsSync(caKey)) {
  throw Error('Certificado e chave da CA precisam estar juntos na pasta piloto-local.');
}
if (!existsSync(ca)) {
  openssl(['req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '90',
    '-subj', '/CN=YALog Family Homologacao Local', '-keyout', caKey, '-out', ca,
    '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
    '-addext', 'keyUsage=critical,keyCertSign,cRLSign']);
}
const config = join(directory, 'endpoint.cnf');
const csr = join(directory, 'endpoint.csr');
writeFileSync(config, 'subjectAltName=IP:' + address + '\nextendedKeyUsage=serverAuth\n');
openssl(['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=' + address,
  '-keyout', endpointKey, '-out', csr]);
openssl(['x509', '-req', '-in', csr, '-CA', ca, '-CAkey', caKey, '-CAcreateserial',
  '-out', endpointCert, '-days', '30', '-extfile', config]);
process.env.YALOG_TLS_CERT = endpointCert;
process.env.YALOG_TLS_KEY = endpointKey;
process.env.YALOG_BIND = address;
console.log('Para os dispositivos de teste, importe somente: ' + ca);
await import('./server.mjs');
