(function () {
'use strict';
if (window.top !== window) return;
if (location.protocol !== 'https:' && location.protocol !== 'tape:') return;
var CHANNEL = '__TT_CHANNEL__';
var PLATFORM = '__TT_PLATFORM__';
if (CHANNEL.indexOf('__TT_') === 0 || (PLATFORM !== 'ios' && PLATFORM !== 'android')) return;
if (window.ethereum) return;
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
try {
defineProp(window, 'ethereum', { value: provider, configurable: true, writable: true, enumerable: true });
} catch (_) {
window.ethereum = provider;
}
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
