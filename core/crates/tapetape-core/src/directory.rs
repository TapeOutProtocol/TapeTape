//! The TapeRelay node list, read straight from the chain (no central directory server).
//!
//! TapeRelayDirectory on X Layer lists every operator's servers; the stake contract it points to says which
//! operators are in good standing. A node counts only when (contract design, TapeRelayDirectory.sol):
//! 1. its operator is in good standing,
//! 2. the operator's on-chain list has `host:4246`,
//! 3. the node's own `/v1/info` names that same operator id for X Layer (so nobody can list someone else's host),
//! 4. the node offers self-sovereign identities (`self-id`), and answers at all (= online).

use std::collections::BTreeMap;
use std::time::Duration;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, warn};
use reqwest::Client;
use serde_json::{json, Value};
use taperelay::evm::{self, Abi};

use crate::chain::hex0x;
use crate::transport::PORT;

/// TapeRelayDirectory on X Layer.
pub(crate) const DIRECTORY: &str = "0x45e1a892ec4b9db3f0206e4b8c1f09f7a8f67a5b";
const CHAIN_KEY: &str = "eip155:196";
/// Tried in order: TapeTape's RPC proxy, then two public X Layer endpoints. All accept JSON-RPC batches.
const RPCS: [&str; 3] = ["https://tapetape.org/rpc/196", "https://rpc.xlayer.tech", "https://xlayerrpc.okx.com"];
const PAGE: u128 = 100; // TapeRelayDirectory.MAX_PAGE
const RPC_TIMEOUT: Duration = Duration::from_secs(15);
const INFO_TIMEOUT: Duration = Duration::from_secs(10);

/// One operator from the chain: id and the hosts it lists with port 4246.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Listed {
    pub id: u64,
    pub hosts: Vec<String>,
}

fn call(to: &str, data: Vec<u8>) -> Value {
    json!({"to": to, "data": hex0x(&data)})
}

/// Both X Layer endpoints refuse batches of more than 10 calls ("too many RPC calls in batch request").
const BATCH_MAX: usize = 10;

/// `eth_call`s as JSON-RPC batches of at most BATCH_MAX; results in request order.
async fn batch(http: &Client, url: &str, calls: &[Value]) -> Result<Vec<Vec<u8>>> {
    let mut out = Vec::with_capacity(calls.len());
    for chunk in calls.chunks(BATCH_MAX) {
        out.extend(batch_once(http, url, chunk).await?);
    }
    Ok(out)
}

async fn batch_once(http: &Client, url: &str, calls: &[Value]) -> Result<Vec<Vec<u8>>> {
    let body: Vec<Value> = calls
        .iter()
        .enumerate()
        .map(|(i, c)| json!({"jsonrpc": "2.0", "id": i, "method": "eth_call", "params": [c, "latest"]}))
        .collect();
    let res: Value = http
        .post(url)
        .json(&body)
        .timeout(RPC_TIMEOUT)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let Some(items) = res.as_array() else {
        bail!("RPC batch refused: {}", res.get("error").unwrap_or(&res));
    };
    ensure!(items.len() == calls.len(), "RPC batch: {} replies for {} calls", items.len(), calls.len());
    let mut out = vec![Vec::new(); calls.len()];
    for item in items {
        let i = item["id"].as_u64().context("RPC reply without id")? as usize;
        ensure!(i < calls.len(), "RPC reply id out of range");
        if let Some(err) = item.get("error") {
            bail!("RPC error: {err}");
        }
        let hex = item["result"].as_str().context("RPC reply without result")?;
        out[i] = hex::decode(hex.trim_start_matches("0x"))?;
    }
    Ok(out)
}

fn offset(out: &[u8], word: usize) -> Result<usize> {
    Ok(usize::try_from(evm::uint(out, word)?)?)
}

/// `operators(offset, limit)` → (total, ids)
fn decode_operators(out: &[u8]) -> Result<(u64, Vec<u64>)> {
    let total = u64::try_from(evm::uint(out, 0)?)?;
    let body = out.get(offset(out, 1)?..).context("short operators reply")?;
    let n = offset(body, 0)?;
    ensure!(n <= PAGE as usize, "too many operators in one page");
    let ids = (0..n)
        .map(|i| Ok(u64::try_from(evm::uint(body, 1 + i)?)?))
        .collect::<Result<_>>()?;
    Ok((total, ids))
}

/// `serversOf(id)` → names (the `at` timestamp is ignored)
fn decode_servers(out: &[u8]) -> Result<Vec<String>> {
    let arr = out.get(offset(out, 0)?..).context("short servers reply")?;
    let n = offset(arr, 0)?;
    ensure!(n <= 16, "too many servers"); // TapeRelayDirectory.MAX_SERVERS
    let heads = &arr[32..];
    (0..n)
        .map(|i| {
            let s = heads.get(offset(heads, i)?..).context("short server name")?;
            let len = offset(s, 0)?;
            ensure!(len <= 260, "server name too long");
            let bytes = s.get(32..32 + len).context("short server name")?;
            Ok(String::from_utf8(bytes.to_vec())?)
        })
        .collect()
}

/// Hosts (lower case, no port) listed with the messaging port.
fn messaging_hosts(names: &[String]) -> Vec<String> {
    let mut hosts: Vec<String> = names
        .iter()
        .filter_map(|n| n.strip_suffix(&format!(":{PORT}")))
        .filter(|h| !h.is_empty() && h.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-'))
        .map(str::to_ascii_lowercase)
        .collect();
    hosts.dedup();
    hosts
}

/// Operators in good standing and their messaging hosts, read from the chain through `url`.
async fn listed_via(http: &Client, url: &str) -> Result<Vec<Listed>> {
    let first = batch(
        http,
        url,
        &[
            call(DIRECTORY, evm::encode_call("stake()", &[])),
            call(DIRECTORY, evm::encode_call("operators(uint256,uint256)", &[Abi::Uint(0), Abi::Uint(PAGE)])),
        ],
    )
    .await?;
    let stake = hex0x(&evm::address(&first[0], 0)?);
    let (total, mut ids) = decode_operators(&first[1])?;
    let mut from = ids.len() as u128;
    while (from as u64) < total && from < 10 * PAGE {
        let page = batch(
            http,
            url,
            &[call(DIRECTORY, evm::encode_call("operators(uint256,uint256)", &[Abi::Uint(from), Abi::Uint(PAGE)]))],
        )
        .await?;
        let (_, more) = decode_operators(&page[0])?;
        if more.is_empty() {
            break;
        }
        from += more.len() as u128;
        ids.extend(more);
    }
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    let calls: Vec<Value> = ids
        .iter()
        .flat_map(|id| {
            let id = u128::from(*id);
            [
                call(&stake, evm::encode_call("inGoodStanding(uint64)", &[Abi::Uint(id)])),
                call(DIRECTORY, evm::encode_call("serversOf(uint64)", &[Abi::Uint(id)])),
            ]
        })
        .collect();
    let replies = batch(http, url, &calls).await?;
    let mut listed = Vec::new();
    for (i, id) in ids.iter().enumerate() {
        let good = evm::uint(&replies[2 * i], 0)? != 0;
        if !good {
            debug!("operator {id} is not in good standing");
            continue;
        }
        let hosts = messaging_hosts(&decode_servers(&replies[2 * i + 1])?);
        if !hosts.is_empty() {
            listed.push(Listed { id: *id, hosts });
        }
    }
    Ok(listed)
}

/// The chain read, trying each public RPC endpoint in turn.
pub(crate) async fn listed(http: &Client) -> Result<Vec<Listed>> {
    let mut last = None;
    for url in RPCS {
        match listed_via(http, url).await {
            Ok(l) => return Ok(l),
            Err(e) => {
                warn!("reading the TapeRelay directory via {url} failed: {e:#}");
                last = Some(e);
            }
        }
    }
    Err(last.unwrap()).context("Cannot read the TapeRelay node directory from X Layer")
}

/// Does this `/v1/info` belong to operator `id` and offer self-sovereign identities?
pub(crate) fn info_matches(info: &Value, id: u64) -> bool {
    let named = info["operator"]["ids"][CHAIN_KEY]
        .as_str()
        .is_some_and(|v| v == id.to_string());
    let self_id = info["features"]
        .as_array()
        .is_some_and(|f| f.iter().any(|x| x == "self-id"));
    named && self_id
}

/// Online nodes: listed on chain by an operator in good standing and confirmed by the node itself.
pub(crate) async fn online_nodes(http: &Client) -> Result<Vec<String>> {
    // A host claimed by several operators is checked against each; the node's own info decides.
    let mut claims: BTreeMap<String, Vec<u64>> = BTreeMap::new();
    for l in listed(http).await? {
        for h in l.hosts {
            claims.entry(h).or_default().push(l.id);
        }
    }
    let mut set = tokio::task::JoinSet::new();
    for (host, ids) in claims {
        let http = http.clone();
        set.spawn(async move {
            let url = format!("{}/v1/info", taperelay::server_url(&format!("{host}:{PORT}")));
            let info: Value = http.get(url).timeout(INFO_TIMEOUT).send().await.ok()?.error_for_status().ok()?.json().await.ok()?;
            ids.iter().any(|id| info_matches(&info, *id)).then_some(host)
        });
    }
    let mut nodes = Vec::new();
    while let Some(r) = set.join_next().await {
        if let Ok(Some(host)) = r {
            nodes.push(host);
        }
    }
    nodes.sort();
    debug!("online nodes from the chain: {nodes:?}");
    Ok(nodes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn word(v: u128) -> String {
        format!("{v:064x}")
    }

    #[test]
    fn decodes_operators_page() {
        let out = hex::decode([word(9), word(0x40), word(3), word(1), word(6), word(9)].concat()).unwrap();
        assert_eq!(decode_operators(&out).unwrap(), (9, vec![1, 6, 9]));
    }

    #[test]
    fn decodes_server_names() {
        // abi.encode(string[] ["a.io:4246", "a.io:443"], uint64 at)
        let s1 = "a.io:4246";
        let s2 = "a.io:443";
        let pad = |s: &str| format!("{}{}", hex::encode(s), "0".repeat(64 - 2 * s.len()));
        let out = [
            word(0x40),
            word(1234),
            word(2),
            word(0x40),
            word(0x80),
            word(s1.len() as u128),
            pad(s1),
            word(s2.len() as u128),
            pad(s2),
        ]
        .concat();
        let names = decode_servers(&hex::decode(out).unwrap()).unwrap();
        assert_eq!(names, vec![s1.to_string(), s2.to_string()]);
        assert_eq!(messaging_hosts(&names), vec!["a.io".to_string()]);
    }

    #[test]
    fn only_messaging_port_and_sane_hosts() {
        let names = ["X.io:4246", "y.io:443", "bad host:4246", ":4246", "z.io:42460"].map(String::from);
        assert_eq!(messaging_hosts(&names), vec!["x.io".to_string()]);
    }

    #[test]
    fn info_must_name_the_operator_and_offer_self_id() {
        let info = json!({"operator": {"ids": {"eip155:196": "6"}}, "features": ["push", "self-id"]});
        assert!(info_matches(&info, 6));
        assert!(!info_matches(&info, 2), "an old listing of the same host by another operator");
        assert!(!info_matches(&json!({"operator": {"ids": {"eip155:196": "6"}}, "features": ["push"]}), 6));
        assert!(!info_matches(&json!({"features": ["self-id"]}), 6));
    }

    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "reads X Layer mainnet and the live TapeRelay nodes"]
    async fn live_nodes_from_chain() {
        let http = crate::transport::http_client();
        let listed = listed(&http).await.unwrap();
        println!("listed on chain (in good standing): {listed:?}");
        assert!(!listed.is_empty());
        let nodes = online_nodes(&http).await.unwrap();
        println!("online nodes: {nodes:?}");
        assert!(nodes.contains(&"relay1.taperelay.com".to_string()));
    }
}
