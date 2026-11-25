// 本机测试：用随手生成的假开发者密钥（电路 1.2.0、签名不上链核对）走一遍 GET、POST、防重放、限额。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDeveloperKey } from '@taperelay/client/src/devkey.mjs';
import { keyWallet } from '@taperelay/client/src/wallet.mjs';
import { addressOf, digest, domainFor, newPrivateKey, randomHex, sha256Hex, signDigest, signerOf, utf8 } from '@taperelay/client/src/core.mjs';
import { createServer, openDb, OBJECT_MAX, SIGN_PATH, SIGN_SERVER } from './server.mjs';

const now = Math.floor(Date.now() / 1000);
const RELAY = 'relay1.taperelay.com:4246';
const { key: devKey, sessionKey, account: circuit } = await createDeveloperKey({
  wallet: keyWallet(newPrivateKey()), account: '1.2.0', servers: [RELAY], now: now - 60, days: 30, note: 'test',
});

const userKey = newPrivateKey();
const user = `key:${addressOf(userKey)}@relay1.taperelay.com`;

function uploadRequest(n) {
  return { message: { account: circuit, server: RELAY, method: 'POST', path: '/v1/objects', query: '', bodyHash: `0x${sha256Hex(utf8(`obj${n}`))}`, timestamp: now, nonce: `0x${randomHex(16)}` } };
}
function signedHeaders(body, { priv = userKey, account = user, ts = now, nonce = randomHex(16) } = {}) {
  const m = { account, server: SIGN_SERVER, method: 'POST', path: SIGN_PATH, query: '', bodyHash: `0x${sha256Hex(utf8(body))}`, timestamp: ts, nonce: `0x${nonce}` };
  return { 'content-type': 'application/json', 'TR-Account': account, 'TR-Timestamp': String(ts), 'TR-Nonce': nonce, 'TR-Signature': signDigest(priv, digest('Request', domainFor(account), m)) };
}

async function start(opts) {
  const srv = createServer({ db: openDb(':memory:'), clock: () => now, ...opts });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  return { srv, url: `http://127.0.0.1:${srv.address().port}${SIGN_PATH}` };
}

test('没配密钥时回 503', async () => {
  const { srv, url } = await start({ developerKey: undefined });
  const r = await fetch(url);
  assert.equal(r.status, 503);
  assert.equal((await r.json()).error, 'not-configured');
  srv.close();
});

test('GET 给出电路和节点，不泄露密钥', async () => {
  const { srv, url } = await start({ developerKey: devKey });
  const r = await fetch(url);
  const text = await r.text();
  assert.equal(r.status, 200);
  assert.deepEqual(JSON.parse(text), { account: circuit, servers: [RELAY] });
  assert.ok(!text.includes('trk1_'));
  srv.close();
});

test('POST 签名正确、防重放、身份不对就拒', async () => {
  const { srv, url } = await start({ developerKey: devKey });
  const body = JSON.stringify({ requests: [uploadRequest(1), uploadRequest(2)] });
  const h = signedHeaders(body);
  const r = await fetch(url, { method: 'POST', headers: h, body });
  assert.equal(r.status, 200);
  const reply = await r.json();
  assert.equal(reply.signatures.length, 2);
  assert.ok(reply.delegation);
  assert.deepEqual(reply.servers, [RELAY]);
  const input = JSON.parse(body);
  input.requests.forEach((q, i) => assert.equal(signerOf('Request', circuit, q.message, reply.signatures[i]), sessionKey));

  const again = await fetch(url, { method: 'POST', headers: h, body });
  assert.equal(again.status, 401);
  assert.equal((await again.json()).error, 'replayed');

  const other = newPrivateKey();
  const forged = await fetch(url, { method: 'POST', headers: signedHeaders(body, { priv: other }), body });
  assert.equal(forged.status, 401);

  const stale = await fetch(url, { method: 'POST', headers: signedHeaders(body, { ts: now - 600 }), body });
  assert.equal(stale.status, 401);

  const tampered = await fetch(url, { method: 'POST', headers: signedHeaders(body), body: body.replace('obj', 'xyz') });
  assert.equal(tampered.status, 401);
  srv.close();
});

test('不签别的节点、不签上传和认领以外的请求', async () => {
  const { srv, url } = await start({ developerKey: devKey });
  const bad = uploadRequest(3);
  bad.message.server = 'evil.example:4246';
  const body = JSON.stringify({ requests: [bad] });
  const r = await fetch(url, { method: 'POST', headers: signedHeaders(body), body });
  assert.equal(r.status, 400);
  srv.close();
});

test('每天限额用完回 429', async () => {
  const { srv, url } = await start({ developerKey: devKey, limit: 3 * OBJECT_MAX });
  const post = async (n) => {
    const body = JSON.stringify({ requests: Array.from({ length: n }, (_, i) => uploadRequest(i)) });
    return (await fetch(url, { method: 'POST', headers: signedHeaders(body), body })).status;
  };
  assert.equal(await post(2), 200);
  assert.equal(await post(2), 429);
  assert.equal(await post(1), 200);
  assert.equal(await post(1), 429);
  srv.close();
});

test('别的路径 404，请求体过大 413', async () => {
  const { srv, url } = await start({ developerKey: devKey });
  assert.equal((await fetch(url.replace(SIGN_PATH, '/'))).status, 404);
  const big = 'x'.repeat((1 << 20) + 10);
  const r = await fetch(url, { method: 'POST', headers: signedHeaders(big), body: big }).catch(() => ({ status: 413 }));
  assert.equal(r.status, 413);
  srv.close();
});

test('RPC 中转：只放行白名单方法、批量上限、限流、不泄露上游地址', async () => {
  const calls = [];
  const fakeFetch = async (url, init) => { calls.push(url); return new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: '0x1' }), { status: 200 }); };
  const { srv, url } = await start({ developerKey: devKey, rpcUpstreams: { 196: 'https://secret.example/key123' }, fetchImpl: fakeFetch });
  const base = url.replace(SIGN_PATH, '');
  const post = (path, body, ip = '1.1.1.1') => fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': ip }, body: JSON.stringify(body) });
  let r = await post('/rpc/196', { jsonrpc: '2.0', id: 1, method: 'eth_blockNumber', params: [] });
  assert.equal(r.status, 200);
  const text = await r.text();
  assert.ok(!text.includes('secret'));
  assert.equal(calls[0], 'https://secret.example/key123');
  r = await post('/rpc/196', { jsonrpc: '2.0', id: 1, method: 'debug_traceTransaction', params: [] });
  assert.equal(r.status, 403);
  r = await post('/rpc/196', Array.from({ length: 11 }, (_, i) => ({ jsonrpc: '2.0', id: i, method: 'eth_chainId', params: [] })));
  assert.equal(r.status, 400);
  r = await post('/rpc/56', { jsonrpc: '2.0', id: 1, method: 'eth_chainId', params: [] });
  assert.equal(r.status, 200);
  assert.equal(calls[1], 'https://bsc-dataseed.bnbchain.org');
  r = await post('/rpc/999', { jsonrpc: '2.0', id: 1, method: 'eth_chainId', params: [] });
  assert.equal(r.status, 404);
  srv.close();
});

test('RPC 中转：每个 IP 每分钟限量', async () => {
  const fakeFetch = async () => new Response('{"jsonrpc":"2.0","id":1,"result":"0x1"}', { status: 200 });
  const { rpcLimiter } = await import('./server.mjs');
  const { srv, url } = await start({ developerKey: devKey, fetchImpl: fakeFetch, rpcAllow: rpcLimiter({ perIp: 3, total: 100 }) });
  const base = url.replace(SIGN_PATH, '');
  const post = (ip) => fetch(base + '/rpc/196', { method: 'POST', headers: { 'x-forwarded-for': ip }, body: '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' }).then((r) => r.status);
  assert.deepEqual([await post('a'), await post('a'), await post('a'), await post('a'), await post('b')], [200, 200, 200, 429, 200]);
  srv.close();
});

test('RPC 中转允许浏览器跨域（预检 204、回复带 allow-origin）', async () => {
  const fakeFetch = async () => new Response('{"jsonrpc":"2.0","id":1,"result":"0x1"}', { status: 200 });
  const { srv, url } = await start({ developerKey: devKey, fetchImpl: fakeFetch });
  const base = url.replace(SIGN_PATH, '');
  const pre = await fetch(base + '/rpc/196', { method: 'OPTIONS', headers: { origin: 'https://1-2-265.tapekit.org', 'access-control-request-method': 'POST' } });
  assert.equal(pre.status, 204);
  assert.equal(pre.headers.get('access-control-allow-origin'), '*');
  const r = await fetch(base + '/rpc/196', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' });
  assert.equal(r.headers.get('access-control-allow-origin'), '*');
  const sign = await fetch(url);
  assert.equal(sign.headers.get('access-control-allow-origin'), null, '签名接口不开放跨域');
  srv.close();
});

test('RPC 中转：网关用的只读方法放行，getLogs 必须限定区块范围', async () => {
  const fakeFetch = async () => new Response('{"jsonrpc":"2.0","id":1,"result":[]}', { status: 200 });
  const { srv, url } = await start({ developerKey: devKey, fetchImpl: fakeFetch });
  const base = url.replace(SIGN_PATH, '');
  const post = async (body) => (await fetch(base + '/rpc/196', { method: 'POST', body: JSON.stringify(body) })).status;
  assert.equal(await post({ jsonrpc: '2.0', id: 1, method: 'eth_getStorageAt', params: ['0x0000000000000000000000000000000000000001', '0x0', 'latest'] }), 200);
  assert.equal(await post({ jsonrpc: '2.0', id: 1, method: 'eth_getLogs', params: [{ fromBlock: '0x100', toBlock: '0x200' }] }), 200);
  assert.equal(await post({ jsonrpc: '2.0', id: 1, method: 'eth_getLogs', params: [{ fromBlock: '0x0', toBlock: '0x100000' }] }), 400);
  assert.equal(await post({ jsonrpc: '2.0', id: 1, method: 'eth_getLogs', params: [{ address: '0x1' }] }), 400);
  srv.close();
});
