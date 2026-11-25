import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const SRC = process.env.TAPERELAY_JS_SRC
  ?? join(process.cwd(), 'node_modules/@taperelay/client/src');
const load = (f) => import(pathToFileURL(join(SRC, f)).href);
const core = await load('core.mjs');
const sealedbox = await load('sealedbox.mjs');
const encrypted = await load('encrypted.mjs');
const remote = await load('remote.mjs');

const line = readFileSync(0, 'utf8').split('\n').find((l) => l.startsWith('FILE-FOR-JS '));
const v = JSON.parse(line.slice('FILE-FOR-JS '.length));
const { fromHex, sha256Hex, utf8 } = core;
const objects = new Map(Object.entries(v.objects).map(([sha, h]) => [sha, fromHex(h)]));
const client = { getObject: async (_name, sha) => { const b = objects.get(sha); if (!b || sha256Hex(b) !== sha) throw Object.assign(new Error('not-found'), { code: 'not-found' }); return b; } };

const results = [];
for (const [i, h] of v.secrets.entries()) {
  const secretKey = fromHex(h);
  const keys = { secretKey, publicKey: sealedbox.publicKeyOf(secretKey) };
  let got = 'no-slot';
  for (const env of v.envelopes) {
    try {
      const r = await encrypted.readEncryptedFile(client, ['s.example:1'], env, [keys], { expectFrom: v.circuit });
      got = r.bytes.length === v.size && sha256Hex(r.bytes) === v.plainSha256 && r.fromMatches ? 'ok' : 'mismatch';
      break;
    } catch (e) {
      if (e.code !== 'no-slot') { got = e.code; break; }
    }
  }
  results.push(`${i}:${got}`);
}
console.log('readEncryptedFile per recipient', results.join(' '));
console.log('all recipients opened', results.every((r) => r.endsWith(':ok')));

const devPriv = Uint8Array.from({ length: 32 }, (_, i) => (77 + i) & 255);
const signer = remote.createRemoteSigner({ auth: { account: v.circuit, delegationHeader: null, sign: async (d) => core.signDigest(devPriv, d) }, servers: v.servers, now: () => v.now });
const checked = signer.check(JSON.parse(v.batch));
const { signatures } = await signer.sign(checked);
const verified = checked.every((c, i) => core.signerOf('Request', v.circuit, c.message, signatures[i]) === core.addressOf(devPriv));
console.log('createRemoteSigner.check accepts Rust batch', checked.length, checked.map((c) => c.kind).join(','), 'signatures verify', verified);

const h = Object.fromEntries(v.auth);
const account = h['TR-Account'];
const message = { account, server: 'tapetape-backend', method: 'POST', path: '/api/relay/sign', query: '', bodyHash: `0x${sha256Hex(utf8(v.batch))}`, timestamp: Number(h['TR-Timestamp']), nonce: `0x${h['TR-Nonce']}` };
console.log('backend auth signer matches TR-Account', core.isSelfAccount(account) && core.signerOf('Request', account, message, h['TR-Signature']) === core.selfKeyOf(account));
