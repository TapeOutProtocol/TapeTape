use std::time::{Duration, Instant};

use anyhow::{bail, ensure, Context, Result};
use log::{debug, warn};
use serde_json::{json, Value};
use taperelay::evm::{self, Abi, LegacyTx};
use taperelay::Identity;

use crate::api::{Token, TxStatus};

pub(crate) struct ChainCfg {
    pub id: u64,
    pub name: &'static str,
    pub key: &'static str,
    pub native: &'static str,
    /// Public RPC, used when the TapeTape proxy (`RPC_PROXY` + chain id) is unreachable.
    pub rpc: &'static str,
    pub packets: Option<&'static str>,
    /// Highest red-packet fee rate (per mille) this client accepts; passed to the contract as `maxFeeRate`.
    /// Set it to the rate the contract was deployed with: if the owner raises the rate later, sending fails
    /// with `fee-too-high` instead of silently charging users more. Update together with a client release.
    pub max_packet_fee_rate: u128,
    pub min_gas_price: u128,
}

pub(crate) struct TokenCfg {
    pub chain: u64,
    pub symbol: &'static str,
    pub address: Option<&'static str>,
    pub decimals: u8,
}

/// TapeTapeRedPacket（`tapetape-v2/contracts`）在各链上的地址。用 `contracts/deploy/` 部署页部署后填进来；
/// 没填（None）时这条链不支持红包和转账，客户端不会往任何地址打钱。
/// 测试里用一个固定地址，模拟测试会把合约代码注入到这个地址。
/// 2026-11-23 部署，交易 0xf258f77240fa544b53dcb0451dcbab72b3a5a7c848ba40386c08a17a5a9ce5c4，
/// 主人 0x571d447f4f24688ec35ccf07f1d6993655f6af15，费率 10‰，Sourcify 完全匹配。
pub(crate) const DEPLOYED_XLAYER_PACKETS: Option<&str> = Some("0x02e0d72021c301371255f2b9469783cd5290384c");
/// 2026-11-23 部署（部署者 nonce 1360），主人 0x571d447f4f24688ec35ccf07f1d6993655f6af15，费率 10‰。
pub(crate) const DEPLOYED_BSC_PACKETS: Option<&str> = Some("0xc5319274ffc978ae02d4354bc89f04cdcda94703");
#[cfg(not(test))]
const XLAYER_PACKETS: Option<&str> = DEPLOYED_XLAYER_PACKETS;
#[cfg(not(test))]
const BSC_PACKETS: Option<&str> = DEPLOYED_BSC_PACKETS;
#[cfg(test)]
pub(crate) const TEST_PACKETS: &str = "0x7a9e7a9e00000000000000000000000000c0ffee";
#[cfg(test)]
const XLAYER_PACKETS: Option<&str> = Some(TEST_PACKETS);
#[cfg(test)]
const BSC_PACKETS: Option<&str> = Some(TEST_PACKETS);

/// TapeTape's RPC proxy (files-backend on tapetape.org): forwards to the operator's own RPC node, whose URL and key
/// stay on the server. Tried first for every request; the chain's public RPC is the fallback.
/// 节点拒绝原因的类别（日志里只写类别，不写原话：原话可能带地址、余额）
fn reject_kind(msg: &str) -> &'static str {
    let m = msg.to_ascii_lowercase();
    if m.contains("nonce") {
        "nonce"
    } else if m.contains("fund") || m.contains("balance") {
        "funds"
    } else if m.contains("gas") || m.contains("underpriced") || m.contains("fee") {
        "fee"
    } else {
        "other"
    }
}

fn is_rate_limit(msg: &str) -> bool {
    let m = msg.to_ascii_lowercase();
    m.contains("rate limit") || m.contains("too many") || m.contains("exceeded") || m.contains("rate-limited")
}

pub(crate) const RPC_PROXY: &str = "https://tapetape.org/rpc/";

pub(crate) const CHAINS: [ChainCfg; 2] = [
    ChainCfg {
        id: 196,
        name: "X Layer",
        key: "xlayer",
        native: "OKB",
        rpc: "https://rpc.xlayer.tech",
        packets: XLAYER_PACKETS,
        max_packet_fee_rate: 10,
        min_gas_price: 20_000_000,
    },
    ChainCfg {
        id: 56,
        name: "BNB Smart Chain",
        key: "bsc",
        native: "BNB",
        rpc: "https://bsc-dataseed.bnbchain.org",
        packets: BSC_PACKETS,
        max_packet_fee_rate: 10,
        min_gas_price: 0,
    },
];

pub(crate) const TOKENS: [TokenCfg; 5] = [
    TokenCfg {
        chain: 196,
        symbol: "OKB",
        address: None,
        decimals: 18,
    },
    TokenCfg {
        chain: 196,
        symbol: "BEM",
        address: Some("0x60e62efa9405d6873c5deabd4e6cc91c25363952"),
        decimals: 8,
    },
    TokenCfg {
        chain: 56,
        symbol: "BNB",
        address: None,
        decimals: 18,
    },
    // BSC 上的 BEM 和 X Layer 上的不是同一个地址（经 BEM 跨链桥 0xa84B…5E72 的 token() 核对）。
    TokenCfg {
        chain: 56,
        symbol: "BEM",
        address: Some("0x5ce033b2bfca3af30b3e8c8457deaf776a8b695a"),
        decimals: 8,
    },
    TokenCfg {
        chain: 56,
        symbol: "USDT",
        address: Some("0x55d398326f99059fF775485246999027B3197955"),
        decimals: 18,
    },
];

pub(crate) const NATIVE_GAS: u128 = 21_000;
const RECEIPT_POLL: Duration = Duration::from_secs(2);
const BROADCAST_TRIES: u32 = 3;

impl TokenCfg {
    pub fn id(&self) -> String {
        format!("{}:{}", self.chain, self.symbol)
    }

    pub fn chain(&self) -> &'static ChainCfg {
        chain(self.chain).expect("token chain in table")
    }

    pub fn record(&self) -> Token {
        let c = self.chain();
        Token {
            id: self.id(),
            chain_id: c.id,
            chain_name: c.name.into(),
            symbol: self.symbol.into(),
            address: self.address.map(str::to_string),
            decimals: self.decimals,
            packets: c.packets.is_some(),
        }
    }

    pub fn address_bytes(&self) -> [u8; 20] {
        self.address
            .map(|a| evm::parse_address(a).expect("token address in table"))
            .unwrap_or([0; 20])
    }
}

pub(crate) fn chain(id: u64) -> Result<&'static ChainCfg> {
    CHAINS
        .iter()
        .find(|c| c.id == id)
        .with_context(|| format!("Unknown chain {id}"))
}

pub(crate) fn chain_by_key(key: &str) -> Option<&'static ChainCfg> {
    CHAINS.iter().find(|c| c.key == key)
}

pub(crate) fn token(id: &str) -> Result<&'static TokenCfg> {
    TOKENS
        .iter()
        .find(|t| t.id() == id)
        .context("Unknown token")
}

pub(crate) fn token_by_address(chain: u64, address: Option<&str>) -> Option<&'static TokenCfg> {
    TOKENS.iter().find(|t| {
        t.chain == chain
            && match (t.address, address) {
                (None, None) => true,
                (Some(a), Some(b)) => a.eq_ignore_ascii_case(b),
                _ => false,
            }
    })
}

pub(crate) fn parse_amount(s: &str, decimals: u8) -> Option<u128> {
    let s = s.trim().replace('。', ".");
    let (whole, frac) = s.split_once('.').unwrap_or((&s, ""));
    if whole.is_empty() && frac.is_empty()
        || !whole
            .chars()
            .chain(frac.chars())
            .all(|c| c.is_ascii_digit())
        || frac.len() > decimals as usize
    {
        return None;
    }
    let scale = 10u128.checked_pow(decimals.into())?;
    let whole: u128 = if whole.is_empty() {
        0
    } else {
        whole.parse().ok()?
    };
    let frac: u128 = if frac.is_empty() {
        0
    } else {
        format!("{frac:0<width$}", width = decimals as usize)
            .parse()
            .ok()?
    };
    whole.checked_mul(scale)?.checked_add(frac)
}

pub(crate) fn format_amount(v: u128, decimals: u8) -> String {
    let scale = 10u128.pow(decimals.into());
    let frac = format!("{:0width$}", v % scale, width = decimals as usize);
    let frac = frac.trim_end_matches('0');
    if frac.is_empty() {
        (v / scale).to_string()
    } else {
        format!("{}.{frac}", v / scale)
    }
}

pub(crate) fn hex0x(data: &[u8]) -> String {
    format!("0x{}", hex::encode(data))
}

pub(crate) fn unhex(v: &Value) -> Result<Vec<u8>> {
    let s = v.as_str().context("Expected hex string")?;
    Ok(hex::decode(s.trim_start_matches("0x"))?)
}

pub(crate) fn quantity(v: &Value) -> Result<u128> {
    let s = v.as_str().context("Expected hex quantity")?;
    Ok(u128::from_str_radix(s.trim_start_matches("0x"), 16)?)
}

pub(crate) fn personal_sign(id: &Identity, message: &[u8]) -> Vec<u8> {
    let inner = evm::keccak256(message);
    let digest = evm::keccak256(&[b"\x19Ethereum Signed Message:\n32".as_slice(), &inner].concat());
    let (sig, recid) = id.sign_recoverable(&digest);
    [&sig[..], &[27 + recid]].concat()
}

pub struct SignedTx {
    pub hash: String,
    raw: String,
    nonce: u128,
    from: String,
}

/// 广播结果说不清（中转可能已经收下、只是回应丢了）：调用方必须保留待确认记录、提示用户先去钱包查看，
/// 不能当成失败（否则用户重发 = 重复付款）
pub const MAYBE_SENT: &str = "maybe-sent";

/// 本机记住的「每条链、每个地址最后用过的序号」和记录时间。只有确认节点收下了才记（说不清的不记）
fn used_nonces() -> &'static std::sync::Mutex<std::collections::HashMap<(u64, String), (u128, Instant)>> {
    static M: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<(u64, String), (u128, Instant)>>> = std::sync::OnceLock::new();
    M.get_or_init(Default::default)
}

/// 两个节点给的序号相差超过这个数就不发（有节点出错或作恶）
const NONCE_SPREAD: u128 = 3;
/// 本机记录只在这么长时间内有效（之后以节点为准）
const NONCE_MEMORY: Duration = Duration::from_secs(600);

/// 下一笔的序号：以中转节点（base）为准，公共节点（other）只用来核对；本机记录只在节点还没看到我们刚发出的那几笔时才用，
/// 而且最多领先节点 NONCE_SPREAD 个号（不会因为某个节点多报而跳号）
fn choose_nonce(base: u128, other: Option<u128>, used: Option<u128>) -> Result<u128> {
    if let Some(o) = other {
        ensure!(base.abs_diff(o) <= NONCE_SPREAD, "nonce-mismatch");
    }
    Ok(match used {
        Some(u) if u + 1 > base && u + 1 - base <= NONCE_SPREAD => u + 1,
        _ => base,
    })
}

#[derive(Clone)]
pub(crate) struct Call {
    pub to: [u8; 20],
    pub value: u128,
    pub data: Vec<u8>,
}

impl Call {
    pub fn json(&self, from: &str) -> Value {
        json!({"from": from, "to": hex0x(&self.to), "value": format!("0x{:x}", self.value), "data": hex0x(&self.data)})
    }
}

pub(crate) struct Rpc {
    http: reqwest::Client,
    pub cfg: &'static ChainCfg,
    urls: Vec<String>,
}

impl Rpc {
    pub fn new(http: &reqwest::Client, chain_id: u64) -> Result<Rpc> {
        let cfg = chain(chain_id)?;
        Ok(Rpc {
            http: http.clone(),
            cfg,
            urls: vec![format!("{RPC_PROXY}{}", cfg.id), cfg.rpc.to_string()],
        })
    }

    #[cfg(test)]
    pub fn with_url(http: &reqwest::Client, chain_id: u64, url: &str) -> Result<Rpc> {
        Ok(Rpc {
            urls: vec![url.to_string()],
            ..Rpc::new(http, chain_id)?
        })
    }

    /// One JSON-RPC request: the proxy first, then the public RPC when the proxy cannot be reached, answers with an
    /// HTTP error or reports a rate limit. A JSON-RPC error from a node that did answer is returned as is.
    pub async fn request(&self, method: &str, params: Value) -> Result<Value> {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
        let mut last = None;
        for (i, url) in self.urls.iter().enumerate() {
            let more = i + 1 < self.urls.len();
            match self.request_at(url, &body).await {
                Ok(Ok(v)) => return Ok(v),
                Ok(Err(msg)) if more && is_rate_limit(&msg) => {
                    warn!("{} {method}: {msg}; trying the next RPC", self.cfg.name);
                    last = Some(anyhow::anyhow!("{msg}"));
                }
                Ok(Err(msg)) => {
                    debug!("{} {method} failed: {msg}", self.cfg.name);
                    bail!("{msg}");
                }
                Err(e) => {
                    if more {
                        warn!("{} {method}: RPC {} failed: {e:#}; trying the next RPC", self.cfg.name, i + 1);
                    }
                    last = Some(e);
                }
            }
        }
        Err(last.unwrap_or_else(|| anyhow::anyhow!("no RPC configured")))
    }

    /// Outer error: transport or HTTP failure (try the next RPC). Inner error: the node's JSON-RPC error message.
    async fn request_at(&self, url: &str, body: &Value) -> Result<std::result::Result<Value, String>> {
        let reply = self
            .http
            .post(url)
            .json(body)
            .timeout(Duration::from_secs(30))
            .send()
            .await
            .with_context(|| format!("Cannot reach {}", self.cfg.name))?;
        ensure!(
            reply.status().is_success(),
            "{} RPC HTTP {}",
            self.cfg.name,
            reply.status()
        );
        let mut reply: Value = reply.json().await?;
        if let Some(e) = reply.get("error") {
            let msg = e
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("RPC error")
                .to_string();
            return Ok(Err(msg));
        }
        Ok(Ok(reply["result"].take()))
    }

    pub async fn call(&self, to: [u8; 20], signature: &str, args: &[Abi<'_>]) -> Result<Vec<u8>> {
        let data = evm::encode_call(signature, args);
        unhex(
            &self
                .request(
                    "eth_call",
                    json!([{"to": hex0x(&to), "data": hex0x(&data)}, "latest"]),
                )
                .await?,
        )
    }

    pub async fn uint(&self, to: [u8; 20], signature: &str, args: &[Abi<'_>]) -> Result<u128> {
        evm::uint(&self.call(to, signature, args).await?, 0)
    }

    /// Like `uint`, but a value above u128::MAX reads as u128::MAX instead of failing.
    pub async fn uint_saturating(&self, to: [u8; 20], signature: &str, args: &[Abi<'_>]) -> Result<u128> {
        let out = self.call(to, signature, args).await?;
        let w = evm::word(&out, 0)?;
        Ok(if w[..16].iter().any(|b| *b != 0) {
            u128::MAX
        } else {
            u128::from_be_bytes(w[16..].try_into()?)
        })
    }

    pub async fn native_balance(&self, address: &str) -> Result<u128> {
        quantity(
            &self
                .request("eth_getBalance", json!([address, "latest"]))
                .await?,
        )
    }

    pub async fn balance(&self, token: &TokenCfg, address: &str) -> Result<u128> {
        match token.address {
            None => self.native_balance(address).await,
            Some(_) => {
                self.uint(
                    token.address_bytes(),
                    "balanceOf(address)",
                    &[Abi::Address(evm::parse_address(address)?)],
                )
                .await
            }
        }
    }

    pub async fn gas_price(&self) -> Result<u128> {
        Ok(quantity(&self.request("eth_gasPrice", json!([])).await?)?.max(self.cfg.min_gas_price))
    }

    pub async fn estimate(&self, from: &str, call: &Call) -> Result<u128> {
        let gas = quantity(
            &self
                .request("eth_estimateGas", json!([call.json(from)]))
                .await?,
        )?;
        Ok(if call.data.is_empty() && gas == NATIVE_GAS {
            gas
        } else {
            gas.checked_mul(6).context("gas overflow")? / 5
        })
    }

    /// 下一笔交易的序号：中转和公共节点同时问；以中转为准（它是我们自己的入口），公共节点用来核对；
    /// 中转没回答就不发（只信一个外部节点太危险）
    async fn pending_nonce(&self, address: &str) -> Result<u128> {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": "eth_getTransactionCount", "params": [address, "pending"]});
        let ask = |url: &str| {
            let body = &body;
            let url = url.to_string();
            async move {
                match self.request_at(&url, body).await {
                    Ok(Ok(v)) => quantity(&v),
                    Ok(Err(msg)) => Err(anyhow::anyhow!("{msg}")),
                    Err(e) => Err(e),
                }
            }
        };
        let (base, other) = match self.urls.as_slice() {
            [] => bail!("no RPC configured"),
            [a] => (ask(a).await, None),
            [a, b, ..] => {
                let (x, y) = tokio::join!(ask(a), ask(b));
                (x, y.ok())
            }
        };
        let base = base.map_err(|e| {
            warn!("{} nonce lookup failed", self.cfg.name);
            debug!("nonce lookup: {e:#}");
            anyhow::anyhow!("nonce-mismatch")
        })?;
        let used = used_nonces()
            .lock()
            .unwrap()
            .get(&(self.cfg.id, address.to_ascii_lowercase()))
            .filter(|(_, at)| at.elapsed() < NONCE_MEMORY)
            .map(|(n, _)| *n);
        choose_nonce(base, other, used)
    }

    /// 节点确认收下了这笔：记住它的序号
    fn remember(&self, tx: &SignedTx) {
        let mut used = used_nonces().lock().unwrap();
        let slot = used.entry((self.cfg.id, tx.from.clone())).or_insert((tx.nonce, Instant::now()));
        if tx.nonce >= slot.0 {
            *slot = (tx.nonce, Instant::now());
        }
    }

    pub async fn sign(
        &self,
        id: &Identity,
        call: &Call,
        gas: u128,
        gas_price: u128,
    ) -> Result<SignedTx> {
        let from = id.address().to_string();
        let nonce = self.pending_nonce(&from).await?;
        let raw = evm::sign_legacy(
            id,
            &LegacyTx {
                nonce,
                gas_price,
                gas,
                to: call.to,
                value: call.value,
                data: &call.data,
                chain_id: self.cfg.id,
            },
        );
        Ok(SignedTx {
            hash: hex0x(&evm::keccak256(&raw)),
            raw: hex0x(&raw),
            nonce,
            from: from.to_ascii_lowercase(),
        })
    }

    /// 广播。三种结果：
    /// - 有节点收下（或说「已经有这笔了」、或按哈希查得到）→ Ok；
    /// - 所有节点都明确拒绝、等几秒按哈希也查不到 → 返回节点的拒绝原因（确定没发出去）；
    /// - 有节点连不上、HTTP 出错、限频，事后又查不到 → MAYBE_SENT（说不清，调用方不能当成失败）
    pub async fn broadcast(&self, tx: &SignedTx) -> Result<()> {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": "eth_sendRawTransaction", "params": [tx.raw]});
        let mut rejected: Option<String> = None;
        let mut unclear = false;
        for url in &self.urls {
            match self.request_at(url, &body).await {
                Ok(Ok(_)) => {
                    self.remember(tx);
                    debug!("{} transaction sent: {}", self.cfg.name, tx.hash);
                    return Ok(());
                }
                Ok(Err(msg)) => {
                    let lower = msg.to_ascii_lowercase();
                    // 「已经有这笔了」：只有哈希完全相同才会这么回，说明前一次其实送到了
                    if lower.contains("already known") || lower.contains("known transaction") || lower.contains("alreadyknown") {
                        self.remember(tx);
                        return Ok(());
                    }
                    if is_rate_limit(&msg) {
                        unclear = true;
                    } else {
                        rejected = Some(msg);
                    }
                }
                Err(e) => {
                    // 日志里不写节点原话（可能带地址、余额），原话只在调试级别
                    warn!("{} broadcast transport failed", self.cfg.name);
                    debug!("broadcast transport: {e:#}");
                    unclear = true;
                }
            }
        }
        // 没有节点说收下了：等一会儿按哈希再查几次（中转后面的节点可能还没传开）
        for _ in 0..BROADCAST_TRIES {
            tokio::time::sleep(RECEIPT_POLL).await;
            match self.status(&tx.hash).await {
                Ok(TxStatus::NotFound) => {}
                Ok(_) => {
                    self.remember(tx);
                    return Ok(());
                }
                Err(e) => {
                    debug!("transaction lookup: {e:#}");
                    unclear = true;
                }
            }
        }
        if unclear {
            warn!("{} broadcast result unknown", self.cfg.name);
            bail!(MAYBE_SENT);
        }
        let reason = rejected.unwrap_or_else(|| "rejected".into());
        warn!("{} broadcast rejected ({})", self.cfg.name, reject_kind(&reason));
        debug!("broadcast rejected: {reason}");
        bail!("{reason}")
    }

    pub async fn send(
        &self,
        id: &Identity,
        call: &Call,
        gas: u128,
        gas_price: u128,
    ) -> Result<String> {
        let tx = self.sign(id, call, gas, gas_price).await?;
        self.broadcast(&tx).await?;
        Ok(tx.hash)
    }

    pub async fn receipt(&self, hash: &str) -> Result<Option<Value>> {
        let r = self
            .request("eth_getTransactionReceipt", json!([hash]))
            .await?;
        Ok((!r.is_null()).then_some(r))
    }

    pub async fn status(&self, hash: &str) -> Result<TxStatus> {
        if let Some(r) = self.receipt(hash).await? {
            return Ok(if r["status"] == "0x1" {
                TxStatus::Confirmed
            } else {
                TxStatus::Failed
            });
        }
        let tx = self
            .request("eth_getTransactionByHash", json!([hash]))
            .await?;
        Ok(if tx.is_null() {
            TxStatus::NotFound
        } else {
            TxStatus::Pending
        })
    }

    pub async fn wait(&self, hash: &str, timeout: Duration) -> Result<Value> {
        let deadline = Instant::now() + timeout;
        loop {
            tokio::time::sleep(RECEIPT_POLL).await;
            if let Some(r) = self.receipt(hash).await? {
                ensure!(r["status"] == "0x1", "tx-failed");
                return Ok(r);
            }
            ensure!(Instant::now() < deadline, "tx-timeout");
        }
    }

    pub async fn execute(&self, id: &Identity, call: &Call, timeout: Duration) -> Result<String> {
        let gas = self.estimate(id.address(), call).await?;
        let price = self.gas_price().await?;
        let hash = self.send(id, call, gas, price).await?;
        self.wait(&hash, timeout).await?;
        Ok(hash)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn amounts_are_exact() {
        assert_eq!(parse_amount("1.5", 18), Some(1_500_000_000_000_000_000));
        assert_eq!(parse_amount(".25", 8), Some(25_000_000));
        assert_eq!(parse_amount("3。1", 2), Some(310));
        assert_eq!(parse_amount("0.000000001", 8), None);
        assert_eq!(parse_amount("1.2.3", 18), None);
        assert_eq!(parse_amount("", 18), None);
        assert_eq!(parse_amount(".", 18), None);
        assert_eq!(parse_amount("-1", 18), None);
        assert_eq!(parse_amount("1e3", 18), None);
        assert_eq!(format_amount(1_500_000_000_000_000_000, 18), "1.5");
        assert_eq!(format_amount(0, 18), "0");
        assert_eq!(format_amount(1, 18), "0.000000000000000001");
        assert_eq!(format_amount(12_345_000_000, 8), "123.45");
    }

    #[test]
    fn table_is_consistent() {
        let ids: Vec<String> = TOKENS.iter().map(|t| t.id()).collect();
        assert_eq!(
            ids,
            ["196:OKB", "196:BEM", "56:BNB", "56:BEM", "56:USDT"]
        );
        for t in &TOKENS {
            assert_eq!(t.address.is_none(), t.symbol == t.chain().native);
            t.address_bytes();
        }
        assert!(token("56:USDT").unwrap().record().packets);
        assert!(token("196:BEM").unwrap().record().packets);
        assert_eq!(
            token_by_address(56, Some("0x55d398326f99059ff775485246999027b3197955"))
                .unwrap()
                .symbol,
            "USDT"
        );
    }

    #[test]
    fn personal_sign_shape() {
        let id = Identity::new(&[0x11; 32], "relay1.taperelay.com:4246").unwrap();
        let sig = personal_sign(&id, b"hello");
        assert_eq!(sig.len(), 65);
        assert!(sig[64] == 27 || sig[64] == 28);
    }
}

#[cfg(test)]
mod nonce_tests {
    use super::choose_nonce;

    #[test]
    fn nonce_choice() {
        assert_eq!(choose_nonce(5, Some(5), None).unwrap(), 5);
        assert_eq!(choose_nonce(5, Some(6), None).unwrap(), 5, "以中转为准，公共节点多报不跟");
        assert_eq!(choose_nonce(6, Some(5), None).unwrap(), 6);
        assert!(choose_nonce(5, Some(100), None).is_err(), "节点差太多不发");
        assert_eq!(choose_nonce(5, None, None).unwrap(), 5);
        assert_eq!(choose_nonce(5, Some(5), Some(5)).unwrap(), 6, "节点还没看到刚发的那笔");
        assert_eq!(choose_nonce(7, Some(7), Some(5)).unwrap(), 7);
        assert_eq!(choose_nonce(5, Some(5), Some(20)).unwrap(), 5, "本机记录离谱（交易被丢）时以节点为准");
    }
}
