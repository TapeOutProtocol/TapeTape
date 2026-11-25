// 广场浏览器的内核宿主：App 里一个看不见的网页，跑 Tapekit 内核（原样打包，不改一行），
// 把可见浏览器发来的每个 tape 请求变成「读链 → 多家节点核对 → SHA-256 校验 → 响应」。
//
// 和 Service Worker 网关（TapeKit/sw-gateway/sw.js）是同一套规则：同样的开通判断、屏蔽名单、保留路径、
// 同样的响应头和内容安全策略（serve.js 原样引入），所以 App 里看到的和 https://<名字>.tapekit.org 一致。
// 区别只有一处：内核跑在 App 私有的来源里（https://kernel.tapetape.invalid），网站的脚本碰不到内核、
// 缓存和设置——网关那边内核和网站同源，所以 sw.js 里要处处防网站改缓存，这里天然隔离。
//
// 原生层调用：
//   tapekit.serve(url, lang)       → { status, headers, body(base64) }   url 形如 tape://1.2.0.tape/a.html
//   tapekit.normalize(text, lang)  → { ok, name, path } | { ok:false, message }  地址栏输入规范化
//   tapekit.setBlocklist(text)     → 原生取到的官方屏蔽名单（官方地址不给跨域，网页自己取不到）
//   tapekit.refresh(host)          → 用户点「刷新」：这个网站下次请求重新读链（有频率上限）
// 安卓用 tapekit.serveTo(id, …) / normalizeTo(id, …)，结果经 TapekitBridge.done(id, json) 回传。

import { createKernel, createIdbCache, createMemoryCache, InputError, parseInput, formatName, scanHtml, scanCss, statusText } from '@tapekit/kernel/src/index.js';
import { statusPage, messagePage, pickLocale } from '@tapekit/sw-gateway/pages.js';
import { docPage as html, fileResponse, LOCKED_CSP, GATEWAY_PATHS, isHtml, isCss } from '@tapekit/sw-gateway/serve.js';
import gatewayConfig from '@tapekit/sw-gateway/config.json';
import bundledBlocklist from '@tapekit/sw-gateway/blocklist.txt';
import policyPage from '@tapekit/sw-gateway/policy.html';
import { resilientCache } from './resilient-cache.js';

const MAX_LOG = 200;
const HANDLE_MAX_AGE = 2 * 60_000;
const REFRESH_MIN_GAP = 10_000;   // 同一个网站两次「重新读链」至少隔 10 秒

// ---- 屏蔽名单：内置一份，原生层取到官方最新的再补进来
const parseList = (text) => new Set(String(text).split('\n').map((l) => l.trim().toLowerCase()).filter((l) => l && !l.startsWith('#')));
let blocklist = parseList(bundledBlocklist);
let blocklistText = String(bundledBlocklist);
function setBlocklist(text) {
  if (typeof text !== 'string' || !text.trim()) return { ok: false };
  blocklist = new Set([...parseList(bundledBlocklist), ...parseList(text)]);
  blocklistText = text;
  sites.clear();   // 已打开的网站下次请求按新名单重新判断
  return { ok: true, size: blocklist.size };
}

let kernel = null, cache = null;
async function boot() {
  if (kernel) return kernel;
  cache = resilientCache(() => createIdbCache('tapetape-browser'), () => createMemoryCache());
  kernel = createKernel({
    cache, rpcUrls: gatewayConfig.rpcUrls,
    isBlocked: ({ container, name }) => blocklist.has(String(container).toLowerCase()) || blocklist.has(name),
  });
  return kernel;
}

// ---- 每个网站一个句柄：解析结果 + 按需读取 + 本次读到的文件
const sites = new Map();
const lastFresh = new Map();   // 名字 → 上次重新读链的时间
async function siteFor(name) {
  const old = sites.get(name);
  if (old && !old.stale && Date.now() - old.at < HANDLE_MAX_AGE) return old;
  if (old && old.loading) return old.loading;
  const h = old || { name, files: [], external: [], at: 0 };
  h.loading = (async () => {
    try {
      const res = await kernel.resolve(name, { fresh: !!old });
      const showable = res.status === 'ok';
      if (!h.site || !h.res || h.stale || h.res.block !== res.block || h.res.status !== res.status) { h.site = showable ? await kernel.openSite(res) : null; h.res = res; }
      h.at = Date.now(); h.showable = showable; h.stale = false;
      sites.set(name, h);
      return h;
    } finally { h.loading = null; }   // 失败也要清掉，否则之后一直等一个已经失败的读取
  })();
  return h.loading;
}

/** 用户点「刷新」：只能由原生层调（网站脚本碰不到内核宿主），同一个网站 10 秒内只认一次 */
function refresh(host) {
  const name = nameOfHost(String(host || ''));
  if (!name) return { ok: false };
  const at = lastFresh.get(name) || 0;
  if (Date.now() - at < REFRESH_MIN_GAP) return { ok: true, throttled: true };
  lastFresh.set(name, Date.now());
  const h = sites.get(name);
  if (h) h.stale = true;
  return { ok: true };
}

const push = (arr, item, key) => { if (arr.length < MAX_LOG && !arr.some((x) => x[key] === item[key])) arr.push(item); };
const nodeSummary = (rpc) => Object.fromEntries(Object.entries(rpc.stats()).map(([u, s]) => [new URL(u).host, { ok: s.ok, fail: s.fail, rateLimited: s.rateLimited, lastMs: s.lastMs, cooldownUntil: s.cooldownUntil }]));
const chainRpc = (res) => (res && res.chainId && kernel.kernelFor(res.chainId)?.rpc) || kernel.rpc;
// 文案按这次请求的语言取，不改全局语言：并发请求互不串
const statusTextOf = (locale) => (s) => statusText(s, 'both')[locale] ?? s;
const errorText = (err, locale) => err?.messages?.[locale] ?? err?.message ?? String(err);

/** 主机名（1.2.0.tape）→ 链上名字；不是链上名字返回 null */
function nameOfHost(host) {
  try {
    const p = parseInput(host);
    return p.kind === 'name' ? formatName(p.tokenId, p.cpu, 'tape', p.area) : null;
  } catch { return null; }
}

const locked = (body, status, type = 'text/plain; charset=utf-8') => new Response(body, { status, headers: { 'content-type': type, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'content-security-policy': LOCKED_CSP } });

/**
 * 网关保留路径：永远由内核宿主自己回答，网站在链上放同名文件也顶不掉（与官方网关同一份正则）。
 * 否则网站能伪造「举报这个网站」页，或者放一个 /sw.js 装上自己的 Service Worker。
 */
function reserved(path, locale, host) {
  if (path === '/.tape/policy.html') return html(policyPage);
  if (path === '/.tape/blocklist.txt') return locked(blocklistText, 200);
  if (path === '/.tape/csp-report') return new Response(null, { status: 204 });
  if (path === '/.tape/settings') return html(messagePage({ locale, kind: 'not-found', host }), 404);
  if (GATEWAY_PATHS.test(path)) return locked('', 404);
  return null;
}

async function respond(rawUrl, lang) {
  await boot();
  const url = new URL(rawUrl);
  const locale = pickLocale(lang);
  const host = url.hostname;
  const own = reserved(url.pathname, locale, host);
  if (own) return own;
  const name = nameOfHost(host);
  if (!name) return html(messagePage({ locale, kind: 'bad-host', host, vars: { root: host } }), 400);
  let h;
  try { h = await siteFor(name); }
  catch (err) {
    if (err instanceof InputError) return html(messagePage({ locale, kind: 'bad-host', host, vars: { root: host } }), 400);
    return html(messagePage({ locale, kind: 'rpc', host, vars: { quorum: kernel.rpc.quorum, err: errorText(err, locale) } }), 502, { 'retry-after': '5' });
  }
  const res = h.res;
  if (url.pathname === '/.tape/status') {
    return html(statusPage({ locale, res, host, files: h.files, external: h.external, blocked: [], offchain: [], nodes: nodeSummary(chainRpc(res)), quorum: chainRpc(res).quorum, settings: { devPreview: false }, blockedByUser: false, reportUrl: '/.tape/policy.html', statusTextOf: statusTextOf(locale) })
      // 状态页里的设置表单是给网关用的（App 里没有设置后台），去掉
      .replace(/<form[\s\S]*?<\/form>/g, ''));
  }
  if (!h.showable) {
    const code = res.status === 'blocked' ? 451 : res.status === 'unpaid' ? 402 : res.status === 'store-changed' ? 503 : 404;
    return html(messagePage({ locale, kind: res.status === 'unpaid' ? 'unpaid' : 'status', res, host, statusTextOf: statusTextOf(locale) }), code, { 'x-tape-name': res.name || '', 'x-tape-status': res.status });
  }
  const file = await h.site.get(url.pathname);
  const idHeaders = { 'x-tape-name': res.name, 'x-tape-status': res.status, 'x-tape-container': res.container, 'x-tape-block': res.block };
  if (!file) return html(messagePage({ locale, kind: 'not-found', res, host, vars: { path: url.pathname }, statusTextOf: statusTextOf(locale) }), 404, idHeaders);
  if (file.status === 'too-large') return html(messagePage({ locale, kind: 'too-large', res, host, vars: { path: file.path }, statusTextOf: statusTextOf(locale) }), 413, idHeaders);
  if (file.status !== 'ok' && file.status !== 'no-hash') return html(messagePage({ locale, kind: 'not-verified', res, host, vars: { path: file.path }, statusTextOf: statusTextOf(locale) }), 502, idHeaders);
  push(h.files, { path: file.path, size: file.size, status: file.status, sha: file.sha256 }, 'path');
  if (isHtml(file) || isCss(file)) {
    const text = new TextDecoder('utf-8', { fatal: false }).decode(file.bytes);
    for (const x of (isHtml(file) ? scanHtml(text) : scanCss(text))) push(h.external, { ...x, path: file.path }, 'url');
  }
  return fileResponse(file, idHeaders, 'client');
}

function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

async function serve(rawUrl, lang) {
  let r;
  try { r = await respond(rawUrl, lang); }
  catch (err) { r = html(`<!doctype html><meta charset="utf-8"><pre>${String(err?.stack || err).replace(/</g, '&lt;')}</pre>`, 500); }
  const headers = {};
  r.headers.forEach((v, k) => { headers[k] = v; });
  const body = new Uint8Array(await r.arrayBuffer());
  return { status: r.status, headers, body: b64(body) };
}

/** 地址栏输入 → 链上名字。容器地址、处理器合约#ID 这两种写法要先读链才知道名字 */
async function normalize(raw, lang) {
  const locale = pickLocale(lang);
  // 中文输入法会把 . # @ / 和数字打成全角（4246。0、＃4246＠0），先换回半角
  const text = String(raw ?? '')
    .replace(/[。．｡]/g, '.').replace(/＃/g, '#').replace(/＠/g, '@').replace(/／/g, '/').replace(/：/g, ':')
    .replace(/[０-９ａ-ｚＡ-Ｚ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));
  try {
    const p = parseInput(text);
    if (p.kind === 'name') return { ok: true, name: formatName(p.tokenId, p.cpu, 'tape', p.area), path: p.path };
    await boot();
    const res = await kernel.resolve(text);
    if (!res.name) return { ok: false, message: statusTextOf(locale)(res.status) };
    return { ok: true, name: res.name, path: p.path };
  } catch (err) {
    return { ok: false, message: errorText(err, locale) };
  }
}

const bridge = (fn) => async (id, ...args) => {
  let out;
  try { out = await fn(...args); } catch (err) { out = { ok: false, message: String(err?.message || err) }; }
  globalThis.TapekitBridge.done(id, JSON.stringify(out));
};

globalThis.tapekit = { serve, normalize, setBlocklist, refresh, serveTo: bridge(serve), normalizeTo: bridge(normalize), version: '__TAPEKIT_VERSION__' };
boot().catch(() => {});
globalThis.TapekitBridge?.ready?.();
