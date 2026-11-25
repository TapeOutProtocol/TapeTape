// TapeTape 广场浏览器注入到网页里的钱包（EIP-1193 + EIP-6963）。
// 只负责把网页的请求转给 App 原生层；连不连、签不签、发不发，都由原生层弹窗问用户，私钥永远不进网页。
//
// 安全规定（2026-11-24 三轮审计）：
// - 只在顶层的 https / tape:// 页面生效；框架里、其他协议的页面什么都不留；
// - 和原生层之间走私有通道，网页里不挂任何全局对象（除了标准的 window.ethereum）：
//     iOS：原生层在另一个脚本世界（网页看不到）里放了一个转发脚本，两边用随机名字的 DOM 事件传 JSON 文本，
//          名字每个页面换一次；
//     安卓：原生层在页面显示后用 postWebMessage 把一个消息端口送到主页面（带随机口令）；
//          只收原生送来的（没有 source、origin 为空），网页或框架用 postMessage 塞的不收；
// - 用到的所有内置函数、属性读取函数都在最开始拿在手里，之后只通过 Reflect.apply 调用：
//   网页脚本之后再改原型（事件的 detail/data/ports 读取函数、Function.prototype.call 等）也截不到通道内容；
// - EIP-6963：页面一打开就在网页的钱包选择框里显示「TapeTape」（用户找得到入口）；地址要用户在原生弹窗里同意连接才给。
(function () {
  'use strict';
  if (window.top !== window) return;
  if (location.protocol !== 'https:' && location.protocol !== 'tape:') return;
  var CHANNEL = '__TT_CHANNEL__';
  var PLATFORM = '__TT_PLATFORM__';
  if (CHANNEL.indexOf('__TT_') === 0 || (PLATFORM !== 'ios' && PLATFORM !== 'android')) return;
  if (window.ethereum) return;

  // ---- 内置函数先拿在手里
  var apply = Reflect.apply;
  var getter = function (proto, name) { var d = Object.getOwnPropertyDescriptor(proto, name); return d && d.get; };
  var addListener = EventTarget.prototype.addEventListener;
  var dispatch = EventTarget.prototype.dispatchEvent;
  var stop = Event.prototype.stopImmediatePropagation;
  var Custom = window.CustomEvent;
  var detailOf = getter(CustomEvent.prototype, 'detail');
  var dataOf = getter(MessageEvent.prototype, 'data');
  var portsOf = getter(MessageEvent.prototype, 'ports');
  var sourceOf = getter(MessageEvent.prototype, 'source');
  var originOf = getter(MessageEvent.prototype, 'origin');
  var portPost = window.MessagePort && MessagePort.prototype.postMessage;
  var portStart = window.MessagePort && MessagePort.prototype.start;
  var portClose = window.MessagePort && MessagePort.prototype.close;
  var parse = JSON.parse, stringify = JSON.stringify;
  var P = Promise, keys = Object.keys, freeze = Object.freeze, defineProp = Object.defineProperty;
  var later = window.setTimeout;
  var Err = Error;

  var pending = {}, seq = 0, queue = [], port = null;

  function rpcError(code, message) {
    var e = new Err(message || 'Internal error.');
    e.code = code || -32603;
    return e;
  }

  function settle(m) {
    var p = pending[m.id];
    if (!p) return;
    delete pending[m.id];
    if (m.error) {
      // iOS 的拒绝是字符串 "代码|说明"；安卓是 {code, message}
      var err = m.error;
      if (typeof err === 'string') {
        var bar = err.indexOf('|');
        var code = bar > 0 ? Number(err.slice(0, bar)) : NaN;
        p.reject(code === code ? rpcError(code, err.slice(bar + 1)) : rpcError(-32603, 'Internal error.'));
      } else {
        p.reject(rpcError(err.code, err.message));
      }
    } else {
      var r;
      try { r = parse(m.result); } catch (_) { return p.reject(rpcError(-32603, 'Internal error.')); }
      p.resolve(r);
    }
  }

  // ---- 通道
  if (PLATFORM === 'ios') {
    apply(addListener, window, [CHANNEL + ':res', function (e) {
      var m; try { m = parse(apply(detailOf, e, [])); } catch (_) { return; }
      settle(m);
    }]);
    apply(addListener, window, [CHANNEL + ':evt', function (e) {
      var m; try { m = parse(apply(detailOf, e, [])); } catch (_) { return; }
      emit(m.event, m.data);
    }]);
  } else {
    // 捕获阶段、最先注册：只收原生 postWebMessage 送来的端口（没有 source、origin 为空），收下后拦下这条消息
    apply(addListener, window, ['message', function (e) {
      if (apply(dataOf, e, []) !== CHANNEL) return;
      if (apply(sourceOf, e, []) !== null || apply(originOf, e, []) !== '') return;
      var ports = apply(portsOf, e, []);
      if (!ports || !ports[0]) return;
      apply(stop, e, []);
      if (port) { try { apply(portClose, ports[0], []); } catch (_) {} return; }
      port = ports[0];
      apply(addListener, port, ['message', function (ev) {
        var m; try { m = parse(apply(dataOf, ev, [])); } catch (_) { return; }
        if (m.event) emit(m.event, m.data); else settle(m);
      }]);
      apply(portStart, port, []);
      var q = queue; queue = [];
      for (var i = 0; i < q.length; i++) apply(portPost, port, [q[i]]);
    }, true]);
    // 端口迟迟不来（系统 WebView 太旧等）：挂着的请求报「未连接」
    later(function () {
      if (port) return;
      queue = [];
      var ids = keys(pending);
      for (var i = 0; i < ids.length; i++) settle({ id: ids[i], error: { code: 4900, message: 'The provider is disconnected from all chains.' } });
    }, 15000);
  }

  function send(method, params) {
    return new P(function (resolve, reject) {
      var id = String(++seq);
      pending[id] = { resolve: resolve, reject: reject };
      var msg = stringify({ id: id, method: method, params: params });
      if (PLATFORM === 'ios') {
        apply(dispatch, window, [new Custom(CHANNEL + ':req', { detail: msg })]);
      } else if (port) {
        apply(portPost, port, [msg]);
      } else {
        queue[queue.length] = msg;
      }
    });
  }

  // ---- 事件
  var listeners = {};
  function on(ev, fn) { (listeners[ev] = listeners[ev] || []).push(fn); return provider; }
  function off(ev, fn) { var l = listeners[ev]; if (l) listeners[ev] = l.filter(function (x) { return x !== fn; }); return provider; }
  function emit(ev, data) {
    if (ev === 'chainChanged') provider.chainId = data;
    if (ev === 'accountsChanged') provider.selectedAddress = (data && data[0]) || null;
    var l = (listeners[ev] || []).slice();
    for (var i = 0; i < l.length; i++) { try { l[i](data); } catch (_) {} }
  }

  var provider = {
    chainId: null,
    selectedAddress: null,
    request: function (args) {
      if (!args || typeof args.method !== 'string') return P.reject(rpcError(-32600, 'Invalid request.'));
      var method = args.method;
      var params = args.params === undefined ? [] : args.params;
      return send(method, params).then(function (r) {
        if (method === 'eth_requestAccounts' || method === 'eth_accounts') {
          provider.selectedAddress = (r && r[0]) || null;
        }
        if (method === 'eth_chainId') provider.chainId = r;
        return r;
      });
    },
    on: on, addListener: on, once: function (ev, fn) { var w = function (d) { off(ev, w); fn(d); }; return on(ev, w); },
    removeListener: off, off: off,
    removeAllListeners: function (ev) { if (ev) delete listeners[ev]; else listeners = {}; return provider; },
    isConnected: function () { return true; },
    // 老写法
    enable: function () { return provider.request({ method: 'eth_requestAccounts' }); },
    send: function (a, b) {
      if (typeof a === 'string') return provider.request({ method: a, params: b });
      if (typeof b === 'function') return provider.sendAsync(a, b);
      throw rpcError(4200, 'The requested method is not supported.');
    },
    sendAsync: function (payload, cb) {
      provider.request(payload).then(function (r) { cb(null, { id: payload.id, jsonrpc: '2.0', result: r }); }, function (e) { cb(e, { id: payload.id, jsonrpc: '2.0', error: { code: e.code, message: e.message } }); });
    },
  };

  // 可以被网页覆盖（有的库会重新定义 window.ethereum，设成只读会让它们报错）；覆盖了只影响网页自己
  try {
    defineProp(window, 'ethereum', { value: provider, configurable: true, writable: true, enumerable: true });
  } catch (_) {
    window.ethereum = provider;
  }

  // ---- EIP-6963：一打开就在网页的钱包选择框里显示「TapeTape」
  var info = freeze({
    uuid: (crypto.randomUUID && crypto.randomUUID()) || '7a9e0000-0000-4000-8000-' + Date.now().toString(16).padStart(12, '0').slice(-12),
    name: 'TapeTape',
    rdns: 'org.tapetape',
    icon: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCA2NCA2NCI+PHJlY3Qgd2lkdGg9IjY0IiBoZWlnaHQ9IjY0IiByeD0iMTQiIGZpbGw9IiMxMTEiLz48cGF0aCBkPSJNMTQgMjBsMTggMTIgMTgtMTJ2MjRsLTE4LTEyLTE4IDEyeiIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjZmZmIiBzdHJva2Utd2lkdGg9IjMiIHN0cm9rZS1saW5lam9pbj0icm91bmQiLz48L3N2Zz4=',
  });
  function announce() {
    apply(dispatch, window, [new Custom('eip6963:announceProvider', { detail: freeze({ info: info, provider: provider }) })]);
  }
  apply(addListener, window, ['eip6963:requestProvider', announce]);
  announce();
  apply(dispatch, window, [new Event('ethereum#initialized')]);
})();
