// 广场浏览器内核的缓存保护层（host.js 用）。缓存只为提速，出错不能连累页面。
// IndexedDB 连接会被系统关掉（App 切后台、回收内存、存储进程重启），之后旧连接一用就抛
// 「The database connection is closing」，内核读缓存不兜错，整页就打不开了（2026-11-25 iOS 实测）。
// 这里包一层：出错就丢掉旧连接、重开一次再试；重开失败就换内存缓存；再出错只当没命中，照样去链上读。
export function resilientCache(openIdb, makeMemory) {
  let inner = null, opening = null, memory = null;
  const useMemory = () => (inner = memory ||= makeMemory());
  const open = () => (opening ||= openIdb().then((c) => (inner = c), useMemory).finally(() => { opening = null; }));
  async function call(op, args, fallback) {
    for (let attempt = 0; attempt < 2; attempt++) {
      const c = inner || await open();
      try { return await c[op](...args); } catch {
        if (c === memory) break;          // 内存缓存都出错就算了
        if (inner === c) inner = null;    // 丢掉坏连接，下一轮重开
      }
    }
    return fallback;
  }
  return {
    kind: 'resilient',
    get: (key) => call('get', [key], undefined),
    set: (key, meta, bytes) => call('set', [key, meta, bytes]),
    delete: (key) => call('delete', [key]),
    clear: () => call('clear', []),
  };
}
