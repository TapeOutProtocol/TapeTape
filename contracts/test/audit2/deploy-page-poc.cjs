// 审计用：在 node 里用最小 DOM 桩跑 deploy/index.html 的脚本，复现「切换钱包后旧钱包的事件仍改写全局链号」。
// 用法：node test/audit2/deploy-page-poc.cjs
const fs = require("fs"), path = require("path"), vm = require("vm");
const root = path.join(__dirname, "../../deploy");
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const inline = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const els = {};
function el(id) {
  return els[id] || (els[id] = { id, value: "", textContent: "", disabled: false, _h: "", children: [],
    set innerHTML(v) { this._h = v; this.firstChild = { set textContent(t) { this._t = t; }, get textContent() { return this._t; } }; },
    get innerHTML() { return this._h; }, appendChild(c) { this.children.push(c); } });
}
const listeners = {};
const ctx = {
  document: { getElementById: el, createElement: () => ({}) },
  localStorage: { _s: {}, getItem(k) { return this._s[k] || null; }, setItem(k, v) { this._s[k] = v; }, removeItem(k) { delete this._s[k]; } },
  confirm: () => true, setTimeout: () => 0, console, BigInt, Number, String, JSON, Object, Error, Promise, Event: function (t) { this.type = t; },
  navigator: {},
};
ctx.window = ctx;
ctx.addEventListener = (t, f) => (listeners[t] = listeners[t] || []).push(f);
ctx.dispatchEvent = () => {};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(root, "artifact.js"), "utf8"), ctx);

const sent = [];
function fakeWallet(name, chain) {
  const h = {};
  return { name, chain, on: (e, f) => (h[e] = f), emit: (e, v) => h[e] && h[e](v),
    async request({ method, params }) {
      if (method === "eth_requestAccounts") return ["0x00000000000000000000000000000000000000a1"];
      if (method === "eth_chainId") return "0x" + this.chain.toString(16);
      if (method === "eth_getBalance") return "0x0";
      if (method === "wallet_switchEthereumChain") { this.chain = parseInt(params[0].chainId, 16); return null; }
      if (method === "eth_call") return "0x";
      if (method === "eth_sendTransaction") { sent.push({ wallet: name, chain: this.chain }); return "0xhash"; }
      if (method === "eth_getTransactionReceipt") return { status: "0x1", contractAddress: "0x00000000000000000000000000000000000000c0", transactionHash: "0xhash", blockNumber: "0x1" };
      if (method === "eth_getCode") return "0x";
      return null;
    } };
}
vm.runInContext(inline, ctx);
(async () => {
  const A = fakeWallet("钱包A", 196), B = fakeWallet("钱包B", 56);
  await vm.runInContext("connect", ctx)(A, "A");          // 先连钱包 A（X Layer）
  await vm.runInContext("pickChain", ctx)(196);            // 选 X Layer，A 已在 196
  await vm.runInContext("connect", ctx)(B, "B");           // 改连钱包 B，B 在 BSC(56)
  // 此时页面应判定「不在 X Layer」。但用户在钱包 A 里切了一下链，A 发出 chainChanged(196)：
  A.emit("chainChanged", "0xc4");
  const chainVar = vm.runInContext("chainId", ctx);
  const ready = vm.runInContext('ready("deployMsg")', ctx);
  console.log("当前用来发交易的是钱包B，实际链 =", B.chain, "；页面以为链 =", chainVar, "；ready() =", ready);
  el("owner").value = "0x00000000000000000000000000000000000000a1"; el("fee").value = "5";
  await el("deployBtn").onclick();
  console.log("发出的交易：", JSON.stringify(sent), "；页面记录：", ctx.localStorage._s["tapetape-redpacket-deployments"]);
  if (sent.length && sent[0].chain !== 196) console.log("复现：页面以为在 X Layer 部署，交易实际发到链", sent[0].chain, "，并记成 X Layer 的地址");
})();
