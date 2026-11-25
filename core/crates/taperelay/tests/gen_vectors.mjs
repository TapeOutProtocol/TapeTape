import { writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SRC = process.env.TAPERELAY_JS_SRC
  ?? join(process.cwd(), 'node_modules/@taperelay/client/src');
const load = (f) => import(pathToFileURL(join(SRC, f)).href);
const core = await load('core.mjs');
const selfmsg = await load('selfmsg.mjs');
const session = await load('session.mjs');
const sealedbox = await load('sealedbox.mjs');
const sealedbody = await load('sealedbody.mjs');
const encrypted = await load('encrypted.mjs');
const remote = await load('remote.mjs');
const devkey = await load('devkey.mjs');
const wallet = await load('wallet.mjs');

const { hex, fromHex, utf8, digest, domainFor, signDigest, recover, addressOf } = core;
const pat = (seed, n) => Uint8Array.from({ length: n }, (_, i) => (seed + i * 13 + ((i * i) >> 3)) & 255);
const N = 0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141n;
const fakeHttp = (status, json) => {
  const calls = [];
  const http = async (url, opts) => { calls.push({ url, ...opts }); return { status, json: async () => json }; };
  return { http, calls };
};

const HOST = 'relay1.taperelay.com:4246';
const secpKeys = [
  '0000000000000000000000000000000000000000000000000000000000000001',
  '1111111111111111111111111111111111111111111111111111111111111111',
  'fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140',
  hex(pat(1, 32)),
  hex(pat(2, 32)),
];
const hosts = ['Relay1.TapeRelay.com:4246', 'relay1.taperelay.com', 'a.b', 'node-1.example.org:65535'];

const identities = [];
for (const [i, k] of secpKeys.entries()) {
  const host = hosts[i % hosts.length];
  const { account } = selfmsg.newSelfIdentity({ host, privateKey: fromHex(k) });
  const server = selfmsg.nodeOf(account);
  identities.push({ privateKey: k, host, address: addressOf(fromHex(k)), account, server, url: session.serverUrl(server) });
}

const addr40 = '0x' + '0123456789abcdef'.repeat(3).slice(0, 40);
const accountInputs = [
  `key:${addr40}@relay1.taperelay.com:4246`,
  `key:${addr40}@relay1.taperelay.com`,
  `key:${addr40}@relay1.taperelay.com:443`,
  `key:${addr40.toUpperCase().replace('0X', '0x')}@relay1.taperelay.com`,
  `key:${addr40}@localhost`,
  `key:${addr40}@localhost:4246`,
  `key:${addr40}@a.b`,
  `key:${addr40}@a.b:0`,
  `key:${addr40}@a.b:04246`,
  `key:${addr40}@a.b:65535`,
  `key:${addr40}@a.b:65536`,
  `key:${addr40}@a.b:99999`,
  `key:${addr40}@a.b:123456`,
  `key:${addr40}@-a.b`,
  `key:${addr40}@a-.b`,
  `key:${addr40}@a--b.c-d.e`,
  `key:${addr40}@A.b`,
  `key:${addr40}@a.b.`,
  `key:${addr40}@a..b`,
  `key:${addr40}@a.b\n`,
  `key:${addr40}@${'a'.repeat(63)}.${'b'.repeat(17)}`,
  `key:${addr40}@${'a'.repeat(63)}.${'b'.repeat(18)}`,
  `key:${addr40}@${'a'.repeat(64)}.b`,
  `key:${addr40.slice(0, 41)}@a.b`,
  `key:${addr40}0@a.b`,
  `KEY:${addr40}@a.b`,
  `${addr40}@a.b`,
  `key:${addr40}@a.b:4246:1`,
  `key:${addr40}@a_b.c`,
  `key:${addr40}@relay1.taperelay.com:4246 `,
  '',
];
const accounts = accountInputs.map((input) => {
  const isSelf = core.isSelfAccount(input);
  let node = null;
  try { node = selfmsg.nodeOf(input); } catch { node = null; }
  return { input, isSelf, key: core.selfKeyOf(input), host: core.selfHostOf(input), node, url: node ? session.serverUrl(node) : null };
});

const requests = [];
const requestCases = [
  { method: 'GET', path: '/v1/inbox/ACCOUNT', query: 'limit=100', body: new Uint8Array(0) },
  { method: 'POST', path: '/v1/outbox', query: '', body: utf8('{"notice":{"a":1},"signature":"0x00"}') },
  { method: 'PUT', path: '/v1/keys/ACCOUNT', query: '', body: pat(9, 300) },
  { method: 'GET', path: '/v1/inbox/ACCOUNT/events', query: '', body: new Uint8Array(0) },
  { method: 'GET', path: '/v1/inbox/ACCOUNT/senders', query: 'state=pending&after=x%2By&limit=50', body: new Uint8Array(0) },
];
for (const [i, c] of requestCases.entries()) {
  const id = identities[i % identities.length];
  const auth = selfmsg.selfAuth(fromHex(id.privateKey), id.host);
  const path = c.path.replace('ACCOUNT', auth.account);
  const timestamp = 1763450000 + i * 777;
  const nonce = hex(pat(40 + i, 16));
  const bodyHash = core.sha256Hex(c.body);
  const message = { account: auth.account, server: id.server, method: c.method, path, query: c.query, bodyHash: `0x${bodyHash}`, timestamp, nonce: `0x${nonce}` };
  const d = digest('Request', domainFor(auth.account), message);
  const headers = await session.signedHeaders(auth, { server: id.server, method: c.method, path, query: c.query, body: c.body, now: timestamp, nonce });
  requests.push({ privateKey: id.privateKey, host: id.host, server: id.server, method: c.method, path, query: c.query, body: hex(c.body), timestamp, nonce, bodyHash, digest: hex(d), headers: Object.entries(headers) });
}

const selfNoticeDigests = [];
for (const [i, id] of identities.entries()) {
  const to = identities[(i + 1) % identities.length].account;
  const msg = { from: id.account, to, body: `body-${i}-${'x'.repeat(i * 50)}`, createdAt: [0, 1, 1763450000, 2 ** 53 - 1, 4294967296][i] };
  const d = digest('SelfNotice', domainFor(id.account), msg);
  selfNoticeDigests.push({ privateKey: id.privateKey, host: id.host, ...msg, digest: hex(d), signature: signDigest(fromHex(id.privateKey), d) });
}

const recoverCases = [];
for (const v of selfNoticeDigests) {
  const sig = fromHex(v.signature.slice(2));
  const r = sig.subarray(0, 32), s = BigInt(`0x${hex(sig.subarray(32, 64))}`), vv = sig[64];
  const high = `0x${hex(r)}${(N - s).toString(16).padStart(64, '0')}${(vv === 27 ? 28 : 27).toString(16)}`;
  const flipped = `0x${hex(sig.subarray(0, 64))}${(vv === 27 ? 28 : 27).toString(16)}`;
  const raw01 = `0x${hex(sig.subarray(0, 64))}${(vv - 27).toString(16).padStart(2, '0')}`;
  const upper = v.signature.toUpperCase().replace('0X', '0x');
  const zeroS = `0x${hex(r)}${'0'.repeat(64)}${vv.toString(16)}`;
  for (const signature of [v.signature, high, flipped, raw01, upper, zeroS, v.signature.slice(0, -2), `0x${'00'.repeat(65)}`]) {
    recoverCases.push({ digest: v.digest, signature, signer: recover(fromHex(v.digest), signature) });
  }
}

const P = (1n << 255n) - 19n;
const le = (n) => { const b = new Uint8Array(32); for (let i = 0; i < 32; i++) { b[i] = Number(n & 255n); n >>= 8n; } return b; };
const keyCases = [
  sealedbox.publicKeyOf(pat(3, 32)),
  sealedbox.publicKeyOf(pat(4, 32)),
  new Uint8Array(32),
  le(1n),
  le(P - 1n),
  le(P),
  le(P + 1n),
  le((1n << 255n) - 1n),
  le(P - 1n + (1n << 255n)),
  fromHex('e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800'),
  fromHex('5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157'),
  fromHex('ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f'),
  fromHex('0900000000000000000000000000000000000000000000000000000000000000'),
  pat(5, 32),
].map((k) => ({ key: hex(k), result: sealedbox.checkPublicKey(k) }));

const sealBoxes = [];
for (const [i, msgLen] of [1, 32, 100, 1000].entries()) {
  const secret = pat(60 + i, 32);
  const pub = sealedbox.publicKeyOf(secret);
  const eph = pat(70 + i, 32);
  const message = pat(80 + i, msgLen);
  const sealed = sealedbox.sealBox(message, pub, { ephemeralSecret: eph });
  sealBoxes.push({ recipientSecret: hex(secret), recipientPublic: hex(pub), ephemeralSecret: hex(eph), message: hex(message), sealed: hex(sealed) });
}

const encSecrets = [pat(100, 32), pat(101, 32), pat(102, 32)];
const encPublics = encSecrets.map((s) => sealedbox.publicKeyOf(s));
const sealInlines = [];
for (const [i, plainLen] of [1, 17, 1000, 2870].entries()) {
  const fixed = { oneTimeKey: pat(110 + i, 32), nonce: pat(120 + i, 24), ephemeral: [pat(130 + i, 32), pat(140 + i, 32)] };
  const plain = pat(150 + i, plainLen);
  const body = sealedbody.sealInline(plain, encPublics[0], encPublics[1], { fixed });
  sealInlines.push({ plain: hex(plain), recipientSecret: hex(encSecrets[0]), senderSecret: hex(encSecrets[1]), oneTimeKey: hex(fixed.oneTimeKey), nonce: hex(fixed.nonce), ephemeral: fixed.ephemeral.map(hex), body,
    openedRecipient: hex(sealedbody.openInline(body, { publicKey: encPublics[0], secretKey: encSecrets[0] }) ?? []),
    openedSender: hex(sealedbody.openInline(body, { publicKey: encPublics[1], secretKey: encSecrets[1] }, { as: 'sender' }) ?? []),
    openedWrong: sealedbody.openInline(body, { publicKey: encPublics[2], secretKey: encSecrets[2] }) === null });
}
let tooLarge = null;
try { sealedbody.sealInline(pat(1, 2871), encPublics[0], encPublics[1]); } catch (e) { tooLarge = e.code; }

const inlineBody = sealInlines[1].body;
const inlineChecks = [inlineBody, inlineBody.slice(0, -1), inlineBody + 'A', inlineBody.replace(/.$/, (c) => (c === 'A' ? 'B' : 'A')), `${inlineBody}=`,
  core.base64url(Uint8Array.of(0xe1, 3, ...pat(1, 220))), core.base64url(Uint8Array.of(0xe0, 2, ...pat(1, 220))),
  core.base64url(Uint8Array.of(0xe1, 2, ...pat(1, 200))), core.base64url(Uint8Array.of(0xe1, 2, ...pat(1, 201))), '', 'A', 'AB', 'AB+/', sealInlines[3].body, sealInlines[3].body + 'AA']
  .map((body) => ({ body, ok: selfmsg.inlineBodyOk(body) }));

const base64Cases = ['', 'AA', 'AB', 'AQ', 'AQI', 'AQID', 'AQIDBA', 'AQIDBB', '-_-_', '+/+/', 'AQ==', 'A', 'AQIDB', 'a b']
  .map((s) => { let bytes = null; try { const b = sealedbody.fromBase64url(s); bytes = core.base64url(b) === s ? hex(b) : null; } catch { bytes = null; } return { s, bytes }; });

const sender = { privateKey: fromHex(secpKeys[3]), host: HOST };
const recipient = { privateKey: fromHex(secpKeys[4]), host: HOST };
const senderAuth = selfmsg.selfAuth(sender.privateKey, HOST);
const recipientAuth = selfmsg.selfAuth(recipient.privateKey, HOST);
const notices = [];
for (const [i, message] of [utf8('你好, TapeRelay 👋'), pat(7, 2870)].entries()) {
  const fixed = { oneTimeKey: pat(200 + i, 32), nonce: pat(210 + i, 24), ephemeral: [pat(220 + i, 32), pat(230 + i, 32)] };
  const createdAt = 1763456789 + i;
  const body = sealedbody.sealInline(message, encPublics[0], encPublics[1], { fixed });
  const notice = { from: senderAuth.account, to: recipientAuth.account, body, createdAt };
  const signed = await selfmsg.signSelfNotice(senderAuth, notice);
  const item = { id: signed.id, ...JSON.parse(signed.body) };
  const opened = selfmsg.openSelfNotice(item, { publicKey: encPublics[0], secretKey: encSecrets[0] }, { me: recipientAuth.account });
  if (!opened.ok || hex(opened.message) !== hex(message)) throw new Error('js could not open its own notice');
  notices.push({ senderKey: secpKeys[3], recipientKey: secpKeys[4], host: HOST, recipientEncSecret: hex(encSecrets[0]), senderEncSecret: hex(encSecrets[1]),
    to: recipientAuth.account, message: hex(message), createdAt, oneTimeKey: hex(fixed.oneTimeKey), nonce: hex(fixed.nonce), ephemeral: fixed.ephemeral.map(hex),
    id: signed.id, json: signed.body });
}

const base = { id: notices[0].id, ...JSON.parse(notices[0].json) };
const me = recipientAuth.account;
const other = selfmsg.selfAuth(fromHex(secpKeys[1]), HOST);
const resign = async (auth, n) => { const s = await selfmsg.signSelfNotice(auth, n); return { id: s.id, ...JSON.parse(s.body) }; };
const n0 = base.notice;
const sig = fromHex(base.signature.slice(2));
const highS = `0x${hex(sig.subarray(0, 32))}${(N - BigInt(`0x${hex(sig.subarray(32, 64))}`)).toString(16).padStart(64, '0')}${(sig[64] === 27 ? 28 : 27).toString(16)}`;
const variants = {
  ok: base,
  okNoId: { notice: n0, signature: base.signature },
  okNumericId: { id: 7, notice: n0, signature: base.signature },
  noSignature: { id: base.id, notice: n0 },
  noticeNotObject: { notice: 'x', signature: base.signature },
  notObject: [base],
  extraKey: { notice: { ...n0, extra: 1 }, signature: base.signature },
  missingKey: { notice: { from: n0.from, to: n0.to, body: n0.body, created: n0.createdAt }, signature: base.signature },
  badFrom: { notice: { ...n0, from: 'alice@example.com' }, signature: base.signature },
  fromNumber: { notice: { ...n0, from: 5 }, signature: base.signature },
  notMine: { notice: { ...n0, to: other.account }, signature: base.signature },
  bodyTampered: { notice: { ...n0, body: n0.body.slice(0, -1) + (n0.body.endsWith('A') ? 'Q' : 'A') }, signature: base.signature },
  bodyNotString: { notice: { ...n0, body: 12 }, signature: base.signature },
  createdAtString: { notice: { ...n0, createdAt: String(n0.createdAt) }, signature: base.signature },
  createdAtNegative: { notice: { ...n0, createdAt: -1 }, signature: base.signature },
  createdAtUnsafe: { notice: { ...n0, createdAt: 2 ** 53 }, signature: base.signature },
  createdAtChanged: { notice: { ...n0, createdAt: n0.createdAt + 1 }, signature: base.signature },
  wrongId: { id: '0'.repeat(64), notice: n0, signature: base.signature },
  upperId: { id: base.id.toUpperCase(), notice: n0, signature: base.signature },
  highS: { notice: n0, signature: highS },
  signedByOther: { notice: n0, signature: signDigest(fromHex(secpKeys[1]), digest('SelfNotice', domainFor(n0.from), n0)) },
  fromOther: await resign(other, { ...n0, from: other.account }),
};
const verifyCases = Object.entries(variants).map(([name, item]) => {
  item = JSON.parse(JSON.stringify(item));
  const v = selfmsg.verifySelfNotice(item, { me });
  const o = selfmsg.openSelfNotice(item, { publicKey: encPublics[0], secretKey: encSecrets[0] }, { me });
  return { name, item, me, verify: v.ok ? 'ok' : v.reason, open: o.ok ? 'ok' : o.reason, message: o.ok ? hex(o.message) : null };
});
const wrongKeys = selfmsg.openSelfNotice(base, { publicKey: encPublics[2], secretKey: encSecrets[2] }, { me });
verifyCases.push({ name: 'wrongKeys', item: base, me, verify: 'ok', open: wrongKeys.ok ? 'ok' : wrongKeys.reason, message: null, encSecret: hex(encSecrets[2]) });

const keyRecords = [];
for (const [i, id] of identities.entries()) {
  const auth = selfmsg.selfAuth(fromHex(id.privateKey), id.host);
  const pub = encPublics[i % encPublics.length];
  const createdAt = 1763400000 + i * 1000;
  const { http, calls } = fakeHttp(200, {});
  await selfmsg.publishSelfKey(http, auth, pub, { now: createdAt });
  const call = calls[0];
  const fetched = await selfmsg.fetchSelfKey(fakeHttp(200, JSON.parse(call.body)).http, auth.account);
  keyRecords.push({ privateKey: id.privateKey, host: id.host, account: auth.account, public: hex(pub), createdAt,
    digest: hex(digest('SelfEncryptionKey', domainFor(auth.account), { account: auth.account, key: `0x${hex(pub)}`, createdAt })),
    url: call.url, method: call.method, json: call.body, fetched: hex(fetched.key) });
}

const k0 = JSON.parse(keyRecords[0].json);
const a0 = keyRecords[0].account;
const signRecord = async (privateKey, record) => ({ record, signature: signDigest(privateKey, digest('SelfEncryptionKey', domainFor(record.account), record)) });
const smallOrder = fromHex('e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800');
const keyVariants = {
  ok: k0,
  extraKey: { record: { ...k0.record, extra: 1 }, signature: k0.signature },
  accountMismatch: { record: { ...k0.record, account: keyRecords[1].account }, signature: k0.signature },
  upperKey: { record: { ...k0.record, key: k0.record.key.toUpperCase().replace('0X', '0x') }, signature: k0.signature },
  shortKey: { record: { ...k0.record, key: k0.record.key.slice(0, -2) }, signature: k0.signature },
  createdAtFloat: { record: { ...k0.record, createdAt: 1.5 }, signature: k0.signature },
  createdAtChanged: { record: { ...k0.record, createdAt: k0.record.createdAt + 1 }, signature: k0.signature },
  noSignature: { record: k0.record },
  otherSigner: await signRecord(fromHex(secpKeys[1]), k0.record),
  smallOrderKey: await signRecord(fromHex(secpKeys[0]), { ...k0.record, key: `0x${hex(smallOrder)}` }),
  recordNull: { record: null, signature: k0.signature },
};
const keyVerifyCases = [];
for (const [name, json] of Object.entries(keyVariants)) {
  let result;
  try { result = hex((await selfmsg.fetchSelfKey(fakeHttp(200, json).http, a0)).key); } catch (e) { result = e.code; }
  keyVerifyCases.push({ name, account: a0, json, result });
}

const senderLists = [
  { allow: [me], deny: [], block: [], unblock: [] },
  { allow: [], deny: [other.account], block: [me], unblock: [] },
  { allow: [me, other.account], deny: [other.account], block: [me], unblock: [other.account] },
  { allow: [], deny: [], block: [], unblock: [me] },
  { allow: [], deny: [], block: [], unblock: [] },
];
const senders = [];
for (const l of senderLists) {
  const { http, calls } = fakeHttp(200, {});
  await selfmsg.updateSenders(http, recipientAuth, { ...l, now: 1 });
  senders.push({ ...l, json: calls[0].body, url: calls[0].url });
}

const EC = 1048536;
const CIRCUIT = '1.2.0.tape';
const seq = (seed) => { let c = 0; return (n) => pat(seed + c++, n); };
const big = (seed, n) => Uint8Array.from({ length: n }, (_, i) => (seed + i * 7 + Math.floor(i / 251)) & 255);
const fileSecrets = Array.from({ length: 18 }, (_, i) => pat(160 + i, 32));
const filePublics = fileSecrets.map((s) => sealedbox.publicKeyOf(s));
const keyPair = (i) => ({ secretKey: fileSecrets[i], publicKey: filePublics[i] });
const fakeStore = (objects) => {
  const m = new Map(objects.map((o) => [core.sha256Hex(o), o]));
  return { getObject: async (_name, sha) => { const b = m.get(sha); if (!b) throw Object.assign(new Error('not-found'), { code: 'not-found' }); return b; } };
};
const readWith = async (objects, envelope, i) => {
  try { return { bytes: hex((await encrypted.readEncryptedFile(fakeStore(objects), ['s.example:1'], core.sha256Hex(envelope), [keyPair(i)])).bytes) }; } catch (e) { return { code: e.code }; }
};

const circuitInputs = ['1.2.0.tape', '1.0.tape', '1.3.7.tape', '4246.2.0.tape', '1.1.0.tape', '1.4.0.tape', '0.2.0.tape', '01.2.0.tape', '1.02.0.tape', '1.2.00.tape',
  '1000000000000000000.2.1000000000.tape', '1000000000000000001.2.0.tape', '1.2.1000000001.tape', '99999999999999999999999999999.2.0.tape',
  '1.2.0', '1.2.0.TAPE', ' 1.2.0.tape', '1.2.0.tape ', '1..0.tape', '1.2.3.4.tape', '1.99999999999999999999.0.tape', 'key:0x0000000000000000000000000000000000000001@a.b', ''];
const circuits = circuitInputs.map((input) => ({ input, ok: core.isAccount(input) }));

const fileCases = [
  { name: 'one-byte', size: 1, to: [1] },
  { name: 'small', size: 5000, to: [1] },
  { name: 'self-only-4096', size: 4096, to: [] },
  { name: 'full-chunk', size: EC, to: [1, 2, 3] },
  { name: 'multi-chunk', size: 2 * EC + 1, to: [1, 2, 3, 4] },
  { name: 'two-full-chunks', size: 2 * EC, to: [1] },
  { name: 'sixteen-slots', size: 300, to: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15] },
  { name: 'dedup', size: 10, to: [0, 1, 1] },
];
const files = [];
for (const [k, c] of fileCases.entries()) {
  const plainSeed = 11 + k, randomSeed = 1000 + 97 * k;
  const plain = big(plainSeed, c.size);
  const plan = await encrypted.planEncryptedFile(plain, { from: CIRCUIT, selfKey: filePublics[0], to: c.to.map((i) => filePublics[i]), random: seq(randomSeed) });
  const chunks = [];
  for (let i = 0; i < plan.chunkHashes.length; i++) chunks.push(await plan.chunkBytes(i));
  const objects = [plan.indexObjects[0].bytes, ...chunks, plan.envelope.bytes];
  const openedBy = {};
  for (const i of [0, ...c.to, 17]) {
    const r = await readWith(objects, plan.envelope.bytes, i);
    openedBy[i] = r.bytes === hex(plain) ? 'ok' : r.code;
  }
  files.push({ ...c, plainSeed, randomSeed, fileKey: hex(plan.fileKey), chunkHashes: plan.chunkHashes, index: hex(plan.indexObjects[0].bytes), indexSha: plan.indexObjects[0].sha256,
    envelope: hex(plan.envelope.bytes), envelopeSha: plan.envelope.sha256, manifestJson: plan.manifestJson, chunks: c.size <= 10000 ? chunks.map(hex) : null, openedBy });
}
const fileErrors = [];
for (const [name, plainLen, from, own, to] of [
  ['too-many-recipients', 10, CIRCUIT, filePublics[0], filePublics.slice(1, 17)],
  ['bad-public-key', 10, CIRCUIT, filePublics[0], [new Uint8Array(32)]],
  ['bad-own-key', 10, CIRCUIT, fromHex('e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800'), []],
  ['self-account-from', 10, identities[0].account, filePublics[0], []],
  ['empty', 0, CIRCUIT, filePublics[0], []],
]) {
  let code = 'ok';
  try { await encrypted.planEncryptedFile(new Uint8Array(plainLen), { from, selfKey: own, to }); } catch (e) { code = e.code; }
  fileErrors.push({ name, size: plainLen, from, own: hex(own), to: to.map(hex), code });
}

const fk = pat(240, 32);
const padTo = (bytes, n) => { const out = new Uint8Array(n); out.set(bytes); return out; };
const craft = ({ size, plains, aads, indexPlain, manifest = {}, manifestJson, rawManifest = (b) => b, slotKey = 0, envelope = (e) => e }) => {
  const chunks = plains.map((pt, i) => encrypted.encryptObject(fk, aads ? aads(i) : encrypted.chunkAad(i, i === plains.length - 1), pt, pat(10 + i, 24)));
  const index = encrypted.encryptObject(fk, encrypted.indexAad(0, 0), indexPlain ?? concatBytes(chunks.map((c) => fromHex(core.sha256Hex(c)))), pat(30, 24));
  const m = { version: 1, type: 'encrypted', name: '', contentType: 'application/octet-stream', size, chunkSize: EC, index: core.sha256Hex(index), from: CIRCUIT, ...manifest };
  const json = manifestJson ? manifestJson(JSON.stringify(m)) : JSON.stringify(m);
  const slots = [sealedbox.sealBox(fk, filePublics[slotKey], { ephemeralSecret: pat(40, 32) }), pat(41, 80)];
  const env = envelope(Uint8Array.from(encrypted.buildEnvelope({ fileKey: fk, slots, manifestBytes: rawManifest(utf8(json)), nonce: pat(42, 24) })));
  return { envelope: env, index, chunks };
};
const concatBytes = (parts) => { const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0)); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; } return out; };
const five = big(3, 5);
const okLast = padTo(five, 4096);
const badPad = padTo(five, 4096); badPad[4095] = 1;
const flip = (at) => (e) => { e[at < 0 ? e.length + at : at] ^= 1; return e; };
const setByte = (at, v) => (e) => { e[at] = v; return e; };
const craftedCases = {
  ok: { size: 5, plains: [okLast] },
  okUnpadded: { size: 5, plains: [five] },
  badPadding: { size: 5, plains: [badPad] },
  lastTooShort: { size: 5, plains: [five.subarray(0, 3)] },
  middleTooShort: { size: EC + 5, plains: [big(4, 100), okLast] },
  wrongAad: { size: 5, plains: [okLast], aads: (i) => encrypted.chunkAad(i, false) },
  indexExtraEntry: { size: 5, plains: [okLast], indexPlain: concatBytes([fromHex(core.sha256Hex(encrypted.encryptObject(fk, encrypted.chunkAad(0, true), okLast, pat(10, 24)))), pat(5, 32)]) },
  fromSelfAccount: { size: 5, plains: [okLast], manifest: { from: identities[0].account } },
  fromMissing: { size: 5, plains: [okLast], manifest: { from: undefined } },
  publicType: { size: 5, plains: [okLast], manifest: { type: 'public' } },
  wrongChunkSize: { size: 5, plains: [okLast], manifest: { chunkSize: 1048576 } },
  sizeZero: { size: 0, plains: [okLast] },
  sizeString: { size: '5', plains: [okLast] },
  nameMissing: { size: 5, plains: [okLast], manifest: { name: undefined } },
  upperIndex: { size: 5, plains: [okLast], manifestJson: (j) => j.replace(/"index":"([0-9a-f]+)"/, (_, h) => `"index":"${h.toUpperCase()}"`) },
  wholeShaWrong: { size: 5, plains: [okLast], manifest: { sha256: '00'.repeat(32) } },
  wholeShaRight: { size: 5, plains: [okLast], manifest: { sha256: core.sha256Hex(five) } },
  wholeShaNull: { size: 5, plains: [okLast], manifestJson: (j) => j.replace('}', ',"sha256":null}') },
  unknownField: { size: 5, plains: [okLast], manifest: { extra: [1, 2] } },
  versionFloat: { size: 5, plains: [okLast], manifestJson: (j) => j.replace('"version":1', '"version":1.0') },
  sizeFloat: { size: 5, plains: [okLast], manifestJson: (j) => j.replace('"size":5', '"size":5.0') },
  versionTwo: { size: 5, plains: [okLast], manifest: { version: 2 } },
  notJson: { size: 5, plains: [okLast], manifestJson: () => 'nope' },
  notObject: { size: 5, plains: [okLast], manifestJson: () => '[1]' },
  badUtf8: { size: 5, plains: [okLast], rawManifest: (b) => Uint8Array.of(...b.subarray(0, -1), 0xff, 0x7d) },
  hugeManifest: { size: 5, plains: [okLast], manifest: { name: 'x'.repeat(16384) } },
  badMagic: { size: 5, plains: [okLast], envelope: flip(0) },
  badVersion: { size: 5, plains: [okLast], envelope: setByte(4, 2) },
  zeroSlots: { size: 5, plains: [okLast], envelope: setByte(5, 0) },
  seventeenSlots: { size: 5, plains: [okLast], envelope: setByte(5, 17) },
  threeSlotsShort: { size: 5, plains: [okLast], envelope: setByte(5, 3) },
  truncated: { size: 5, plains: [okLast], envelope: (e) => e.slice(0, 78 + 160 - 1) },
  tiny: { size: 5, plains: [okLast], envelope: (e) => e.slice(0, 5) },
  commitment: { size: 5, plains: [okLast], envelope: flip(6) },
  manifestTampered: { size: 5, plains: [okLast], envelope: flip(-1) },
  slotForOther: { size: 5, plains: [okLast], slotKey: 2 },
};
const crafted = [];
for (const [name, c] of Object.entries(craftedCases)) {
  const f = craft(c);
  const objects = [f.envelope, f.index, ...f.chunks];
  const r = await readWith(objects, f.envelope, 0);
  crafted.push({ name, envelope: hex(f.envelope), index: hex(f.index), chunks: f.chunks.map(hex), result: r.code ?? 'ok', plain: r.bytes ?? null });
}

const T = 1795000000;
const servers3 = ['relay1.taperelay.com:4246', 'relay2.taperelay.com:4246', 'relay3.taperelay.com:4246'];
const devPriv = pat(170, 32);
const dm = { account: CIRCUIT, sessionKey: addressOf(devPriv), servers: servers3, notBefore: T - 86400, expires: T - 86400 + 31622400, app: '', scopes: ['objects'], note: 'tapetape test' };
const dmSig = await wallet.keyWallet(fromHex(secpKeys[3])).signTypedData(core.typedData('DeveloperKey', domainFor(CIRCUIT), dm));
const devKey = `trk1_${core.base64url(utf8(JSON.stringify({ v: 1, key: hex(devPriv), delegation: dm, signature: dmSig, primary: servers3 })))}`;
const signer = remote.createRemoteSigner({ auth: session.sessionAuth(devkey.parseDeveloperKey(devKey).session), servers: servers3, now: () => T });
let batchJson = null;
const backendFetch = async (_url, opts) => {
  batchJson = opts.body;
  const reply = await signer.sign(signer.check(JSON.parse(opts.body)));
  return { ok: true, status: 200, json: async () => reply };
};
const rauth = remote.remoteAuth({ account: CIRCUIT, endpoint: 'https://backend.example/api/relay/sign', fetch: backendFetch });
const uploadBodies = [pat(180, 100), pat(181, 2000)];
const uploadReqs = uploadBodies.map((b, i) => ({ server: servers3[i], object: core.sha256Hex(b), timestamp: T + i, nonce: hex(pat(190 + i, 16)) }));
const uploadHeaders = await Promise.all(uploadReqs.map((r, i) => session.signedHeaders(rauth, { server: r.server, method: 'POST', path: '/v1/objects', query: '', body: uploadBodies[i], now: r.timestamp, nonce: r.nonce, upload: true })));
for (const [i, h] of uploadHeaders.entries()) {
  const m = { account: CIRCUIT, server: uploadReqs[i].server, method: 'POST', path: '/v1/objects', query: '', bodyHash: `0x${uploadReqs[i].object}`, timestamp: uploadReqs[i].timestamp, nonce: `0x${uploadReqs[i].nonce}` };
  if (core.signerOf('Request', CIRCUIT, m, h['TR-Signature']) !== addressOf(devPriv)) throw new Error('remote signature does not verify');
}
const remoteUpload = { account: CIRCUIT, requests: uploadReqs, batchJson, headers: uploadHeaders.map((h) => Object.entries(h)) };

const backendAuthBody = utf8(remoteUpload.batchJson);
const backendAuthHeaders = await session.signedHeaders(selfmsg.selfAuth(fromHex(secpKeys[4]), HOST), { server: 'tapetape-backend', method: 'POST', path: '/api/relay/sign', query: '', body: backendAuthBody, now: T, nonce: hex(pat(195, 16)) });
const backendAuth = { privateKey: secpKeys[4], host: HOST, body: remoteUpload.batchJson, timestamp: T, nonce: hex(pat(195, 16)), headers: Object.entries(backendAuthHeaders) };

const out = {
  library: '@taperelay/client 0.1.0-test.132',
  maxInlinePlaintext: 2870,
  tooLarge,
  identities,
  accounts,
  requests,
  selfNoticeDigests,
  recover: recoverCases,
  checkPublicKey: keyCases,
  sealBox: sealBoxes,
  sealInline: sealInlines,
  inlineBodyOk: inlineChecks,
  base64url: base64Cases,
  notices,
  verify: verifyCases,
  keyRecords,
  keyVerify: keyVerifyCases,
  senders,
  circuits,
  files,
  fileErrors,
  crafted,
  remoteUpload,
  backendAuth,
};
const file = join(dirname(fileURLToPath(import.meta.url)), 'vectors.json');
writeFileSync(file, `${JSON.stringify(out, null, 1)}\n`);
console.log(`wrote ${file}`);
