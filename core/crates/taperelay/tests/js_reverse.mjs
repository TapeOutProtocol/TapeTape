import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const SRC = process.env.TAPERELAY_JS_SRC
  ?? join(process.cwd(), 'node_modules/@taperelay/client/src');
const load = (f) => import(pathToFileURL(join(SRC, f)).href);
const core = await load('core.mjs');
const selfmsg = await load('selfmsg.mjs');
const sealedbox = await load('sealedbox.mjs');

const line = readFileSync(0, 'utf8').split('\n').find((l) => l.startsWith('JSON-FOR-JS '));
const v = JSON.parse(line.slice('JSON-FOR-JS '.length));
const { fromHex, hex } = core;
const secret = fromHex(v.recipientEncSecret);
const keys = { secretKey: secret, publicKey: sealedbox.publicKeyOf(secret) };
const item = { id: v.item.id, ...JSON.parse(v.item.json) };
const opened = selfmsg.openSelfNotice(item, keys, { me: v.me });
console.log('openSelfNotice', opened.ok, opened.reason ?? '', opened.ok && hex(opened.message) === v.message, opened.text);
const senderSecret = fromHex(v.senderEncSecret);
const sent = selfmsg.openSelfNotice(item, { secretKey: senderSecret, publicKey: sealedbox.publicKeyOf(senderSecret) }, { me: item.notice.from, as: 'sender' });
console.log('openSelfNotice as sender', sent.ok, sent.ok && hex(sent.message) === v.message);
const http = async () => ({ status: 200, json: async () => JSON.parse(v.keyJson) });
const fetched = await selfmsg.fetchSelfKey(http, v.keyAccount);
console.log('fetchSelfKey', hex(fetched.key) === v.senderEncPublic, fetched.createdAt);
const h = Object.fromEntries(v.request.headers);
const r = v.request;
const body = new Uint8Array(0);
const message = { account: h['TR-Account'], server: r.server, method: r.method, path: r.path, query: r.query, bodyHash: `0x${core.sha256Hex(body)}`, timestamp: Number(h['TR-Timestamp']), nonce: `0x${h['TR-Nonce']}` };
const signer = core.signerOf('Request', h['TR-Account'], message, h['TR-Signature']);
console.log('Request signer matches TR-Account', signer === core.selfKeyOf(h['TR-Account']));
