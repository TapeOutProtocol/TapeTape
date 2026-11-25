// TapeTape 大附件签名后台。只有一个接口：/api/relay/sign。
//   GET  → { account, servers }：电路名和存文件用的节点（App 用它核对下载来源）。
//   POST → App 用自己的身份签名请求，后台核对身份、防重放、按天限额，再用电路的开发者密钥签上传/认领请求。
// 开发者密钥只从环境变量 TAPERELAY_KEY 读，不写日志、不回给调用方。
import http from 'node:http';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { isSelfAccount, selfKeyOf, sha256Hex, signerOf } from '@taperelay/client/src/core.mjs';
import { developerKeyAuth, serversOfKey } from '@taperelay/client/src/devkey.mjs';
import { createRemoteSigner } from '@taperelay/client/src/remote.mjs';

export const SIGN_SERVER = 'tapetape-backend';
export const SIGN_PATH = '/api/relay/sign';
export const DEFAULT_DAILY_QUOTA_BYTES = 1 << 30;
export const OBJECT_MAX = 1048576;
const MAX_SKEW = 120;
const NONCE_TTL = 300;
const MAX_BODY = 1 << 20;
const KEEP_QUOTA_DAYS = 7;
const KEY_ERRORS = ['no-developer-key', 'bad-developer-key', 'developer-key-expired'];

// ---- RPC 中转：/rpc/<链编号> ----
// App 先走这里；真正的节点地址（可能带密钥）只在服务器环境变量 RPC_<链编号> 里，不回给调用方。
// 没配时转给公共节点。只放行 App 用到的只读方法和广播交易；每个 IP 每分钟限量，总量也限。
export const RPC_DEFAULTS = { 196: 'https://rpc.xlayer.tech', 56: 'https://bsc-dataseed.bnbchain.org' };
export const RPC_METHODS = new Set([
  'eth_call', 'eth_estimateGas', 'eth_gasPrice', 'eth_getBalance', 'eth_getTransactionCount',
  'eth_getTransactionReceipt', 'eth_getTransactionByHash', 'eth_sendRawTransaction', 'eth_blockNumber', 'eth_chainId',
  // 链上网站网关（tapekit.org）要用的只读方法
  'eth_getStorageAt', 'eth_getBlockByNumber', 'eth_getCode', 'eth_getLogs',
]);
const RPC_MAX_LOG_RANGE = 10000n;
const RPC_MAX_BODY = 512 << 10;
const RPC_MAX_BATCH = 10;
const RPC_PER_IP_PER_MIN = 600;
const RPC_TOTAL_PER_MIN = 6000;
const RPC_TIMEOUT_MS = 25000;

export function rpcLimiter({ perIp = RPC_PER_IP_PER_MIN, total = RPC_TOTAL_PER_MIN } = {}) {
  let minute = 0;
  let all = 0;
  let byIp = new Map();
  return (ip, n, nowMs = Date.now()) => {
    const m = Math.floor(nowMs / 60000);
    if (m !== minute) { minute = m; all = 0; byIp = new Map(); }
    const mine = (byIp.get(ip) ?? 0) + n;
    if (mine > perIp || all + n > total) return false;
    byIp.set(ip, mine);
    all += n;
    return true;
  };
}

/** 检查一个 JSON-RPC 请求（或批量）：返回调用个数；不合格抛 RelayError */
export function checkRpcBody(body) {
  let parsed;
  try { parsed = JSON.parse(new TextDecoder().decode(body)); } catch { throw new RelayError(400, 'bad-request', 'Body is not JSON'); }
  const list = Array.isArray(parsed) ? parsed : [parsed];
  if (list.length < 1 || list.length > RPC_MAX_BATCH) throw new RelayError(400, 'bad-request', `1 to ${RPC_MAX_BATCH} calls per request`);
  for (const r of list) {
    if (!r || typeof r !== 'object' || !RPC_METHODS.has(r.method)) throw new RelayError(403, 'method-not-allowed', `Method not allowed: ${String(r?.method).slice(0, 40)}`);
    if (r.method === 'eth_getLogs') {
      // 只认写明区块范围、跨度不超过 1 万块的查询（不带范围的查询会扫全链，太贵）
      const f = Array.isArray(r.params) ? r.params[0] : null;
      const num = (v) => (typeof v === 'string' && /^0x[0-9a-fA-F]{1,16}$/.test(v) ? BigInt(v) : null);
      const from = num(f?.fromBlock), to = num(f?.toBlock);
      if (!f || f.blockHash === undefined && (from === null || to === null || to < from || to - from > RPC_MAX_LOG_RANGE)) {
        throw new RelayError(400, 'bad-request', 'eth_getLogs needs fromBlock/toBlock within 10000 blocks');
      }
    }
  }
  return list.length;
}

export class RelayError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const codeOf = (e) => (typeof e === 'object' && e !== null && 'code' in e ? String(e.code) : '');

export function openDb(path) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS relay_quota (account TEXT NOT NULL, day TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (account, day));
    CREATE TABLE IF NOT EXISTS nonces (key TEXT PRIMARY KEY, expires INTEGER NOT NULL);
  `);
  return db;
}

export function circuitOf(developerKey) {
  try {
    const auth = developerKeyAuth(developerKey);
    return { auth, account: auth.account, servers: serversOfKey(developerKey) };
  } catch (e) {
    throw new RelayError(503, 'not-configured', `Developer key unusable: ${codeOf(e)}`);
  }
}

export function verifyCaller(headers, body, db, now) {
  const account = headers['tr-account'] ?? '';
  const timestamp = Number(headers['tr-timestamp']);
  const nonce = headers['tr-nonce'] ?? '';
  if (!isSelfAccount(account)) throw new RelayError(401, 'bad-auth', 'TR-Account is not a TapeRelay user identity');
  if (!Number.isSafeInteger(timestamp) || Math.abs(timestamp - now) > MAX_SKEW) {
    throw new RelayError(401, 'bad-auth', `TR-Timestamp is more than ${MAX_SKEW}s off`);
  }
  if (!/^[0-9a-f]{1,64}$/.test(nonce)) throw new RelayError(401, 'bad-auth', 'TR-Nonce is not hex');
  const message = {
    account,
    server: SIGN_SERVER,
    method: 'POST',
    path: SIGN_PATH,
    query: '',
    bodyHash: `0x${sha256Hex(body)}`,
    timestamp,
    nonce: `0x${nonce}`,
  };
  let signer = null;
  try {
    signer = signerOf('Request', account, message, headers['tr-signature'] ?? '');
  } catch {
    signer = null;
  }
  if (signer !== selfKeyOf(account)) throw new RelayError(401, 'bad-auth', 'TR-Signature does not match TR-Account');
  const used = db
    .prepare('INSERT INTO nonces (key, expires) VALUES (?, ?) ON CONFLICT(key) DO NOTHING')
    .run(`${account}:${nonce}`, now + NONCE_TTL);
  if (used.changes === 0) throw new RelayError(401, 'replayed', 'TR-Nonce was already used');
  return account;
}

export function charge(db, account, bytes, limit, now) {
  const day = new Date(now * 1000).toISOString().slice(0, 10);
  const over = new RelayError(429, 'quota-exceeded', `Daily attachment quota of ${limit} bytes used up`);
  if (bytes > limit) throw over;
  const r = db
    .prepare(
      `INSERT INTO relay_quota (account, day, bytes) VALUES (?, ?, ?)
       ON CONFLICT(account, day) DO UPDATE SET bytes = bytes + excluded.bytes WHERE bytes + excluded.bytes <= ?`
    )
    .run(account, day, bytes, limit);
  if (r.changes === 0) throw over;
}

export async function signBatch({ developerKey, input, account, db, limit, now }) {
  const { auth, servers } = circuitOf(developerKey);
  const signer = createRemoteSigner({ auth, servers, now: () => now });
  let checked;
  try {
    checked = signer.check(input);
  } catch (e) {
    throw new RelayError(400, codeOf(e) || 'remote-refused', e.message);
  }
  const objects = checked.reduce((n, c) => n + c.objects.length, 0);
  charge(db, account, objects * OBJECT_MAX, limit, now);
  try {
    return { ...(await signer.sign(checked)), servers };
  } catch (e) {
    if (KEY_ERRORS.includes(codeOf(e))) throw new RelayError(503, 'not-configured', `Developer key unusable: ${codeOf(e)}`);
    throw e;
  }
}

function cleanup(db, now) {
  db.prepare('DELETE FROM nonces WHERE expires < ?').run(now);
  const cutoff = new Date((now - KEEP_QUOTA_DAYS * 86400) * 1000).toISOString().slice(0, 10);
  db.prepare('DELETE FROM relay_quota WHERE day < ?').run(cutoff);
}

function readBody(req, max = MAX_BODY) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > max) {
        reject(new RelayError(413, 'too-large', 'Body too large'));
        req.destroy();
      } else chunks.push(c);
    });
    req.on('end', () => resolve(new Uint8Array(Buffer.concat(chunks))));
    req.on('error', reject);
  });
}

export function createServer({ developerKey, limit = DEFAULT_DAILY_QUOTA_BYTES, db, clock = () => Math.floor(Date.now() / 1000), rpcUpstreams = {}, fetchImpl = globalThis.fetch, rpcAllow = rpcLimiter() }) {
  const send = (res, status, obj) => {
    const text = JSON.stringify(obj);
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', 'content-length': Buffer.byteLength(text) });
    res.end(text);
  };
  const fail = (res, e) => {
    if (e instanceof RelayError) {
      console.warn(`[relay/sign] ${e.code}: ${e.message}`);
      return send(res, e.status, { error: e.code, message: e.message });
    }
    console.error('[relay/sign] failed:', e?.message ?? e);
    return send(res, 500, { error: 'internal', message: 'Signing failed' });
  };
  let lastCleanup = 0;
  const rpc = async (req, res, chain) => {
    // 链上网站网关（*.tapekit.org 的浏览器端）也直接来读：允许跨域，只读接口不带凭据
    res.setHeader('access-control-allow-origin', '*');
    res.setHeader('access-control-allow-methods', 'POST, OPTIONS');
    res.setHeader('access-control-allow-headers', 'content-type');
    res.setHeader('access-control-max-age', '86400');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    const upstream = rpcUpstreams[chain] || RPC_DEFAULTS[chain];
    if (!upstream) return send(res, 404, { error: 'unknown-chain' });
    if (req.method !== 'POST') return send(res, 405, { error: 'method-not-allowed' });
    const body = await readBody(req, RPC_MAX_BODY);
    const n = checkRpcBody(body);
    const ip = String(req.headers['x-forwarded-for'] ?? req.socket.remoteAddress ?? '').split(',')[0].trim();
    if (!rpcAllow(ip, n)) throw new RelayError(429, 'rate-limited', 'Too many requests, slow down');
    let up;
    try {
      up = await fetchImpl(upstream, { method: 'POST', headers: { 'content-type': 'application/json' }, body, signal: AbortSignal.timeout(RPC_TIMEOUT_MS) });
    } catch {
      throw new RelayError(502, 'upstream-unreachable', 'RPC node unreachable');
    }
    const text = await up.text();
    if (!up.ok) {
      console.warn(`[rpc/${chain}] upstream HTTP ${up.status}`);
      throw new RelayError(502, 'upstream-error', `RPC node HTTP ${up.status}`);
    }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store', 'content-length': Buffer.byteLength(text) });
    res.end(text);
  };
  return http.createServer(async (req, res) => {
    const path = (req.url ?? '').split('?')[0];
    const m = /^\/rpc\/(\d{1,10})$/.exec(path);
    if (m) {
      try { return await rpc(req, res, m[1]); } catch (e) { return fail(res, e); }
    }
    if (path !== SIGN_PATH) return send(res, 404, { error: 'not-found' });
    try {
      const now = clock();
      if (now - lastCleanup > 600) {
        lastCleanup = now;
        cleanup(db, now);
      }
      if (req.method === 'GET') {
        const { account, servers } = circuitOf(developerKey);
        return send(res, 200, { account, servers });
      }
      if (req.method !== 'POST') return send(res, 405, { error: 'method-not-allowed' });
      const body = await readBody(req);
      const account = verifyCaller(req.headers, body, db, now);
      let input;
      try {
        input = JSON.parse(new TextDecoder().decode(body));
      } catch {
        throw new RelayError(400, 'bad-request', 'Body is not JSON');
      }
      const reply = await signBatch({ developerKey, input, account, db, limit, now });
      console.log(`[relay/sign] signed ${reply.signatures.length} requests for ${account}`);
      return send(res, 200, reply);
    } catch (e) {
      return fail(res, e);
    }
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT) || 3010;
  const host = process.env.HOST || '127.0.0.1';
  const db = openDb(process.env.DB_PATH || '/var/lib/tapetape-files/quota.db');
  const developerKey = process.env.TAPERELAY_KEY;
  const limit = Number(process.env.RELAY_DAILY_QUOTA_BYTES) || DEFAULT_DAILY_QUOTA_BYTES;
  try {
    const { account, servers } = circuitOf(developerKey);
    console.log(`[relay/sign] circuit ${account}, servers ${servers.join(', ')}`);
  } catch (e) {
    console.warn(`[relay/sign] ${e.message} (GET/POST will answer 503 until TAPERELAY_KEY is set)`);
  }
  const rpcUpstreams = {};
  for (const [k, v] of Object.entries(process.env)) {
    const m = /^RPC_(\d+)$/.exec(k);
    if (m && /^https:\/\//.test(v)) rpcUpstreams[m[1]] = v;
  }
  console.log(`[rpc] own nodes for chains: ${Object.keys(rpcUpstreams).join(', ') || 'none (public RPC)'}`);
  createServer({ developerKey, limit, db, rpcUpstreams }).listen(port, host, () => console.log(`[relay/sign] listening on ${host}:${port}`));
}
