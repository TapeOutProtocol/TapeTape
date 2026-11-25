use serde_json::{json, Map, Value};
use taperelay::evm::{self, Abi};
use taperelay::Identity;

use crate::chain::{self, hex0x, personal_sign, quantity, unhex, Call, Rpc};
use crate::packet_abi;

const BSC_SIM: &str = "https://bsc-rpc.publicnode.com";
const XLAYER_SIM: &str = "https://xlayer-mainnet.rpc.sentio.xyz";
const SIMS: [(u64, &str); 2] = [(196, XLAYER_SIM), (56, BSC_SIM)];
const ONE: u128 = 1_000_000_000_000_000_000;

fn ident() -> Identity {
    Identity::new(&rand::random::<[u8; 32]>(), "relay1.taperelay.com:4246").unwrap()
}

fn addr(id: &Identity) -> [u8; 20] {
    evm::parse_address(id.address()).unwrap()
}

fn ok(r: &Value) -> bool {
    r["status"] == "0x1"
}

fn expect_ok(r: &Value) {
    assert!(ok(r), "call failed: {}", revert_text(r));
}

fn expect_revert(r: &Value, text: &str) {
    assert!(!ok(r), "call should have reverted with {text}");
    assert_eq!(revert_text(r), text);
}

fn ret(r: &Value) -> Vec<u8> {
    unhex(&r["returnData"]).unwrap()
}

fn view(to: [u8; 20], sig: &str, args: &[Abi]) -> Call {
    Call {
        to,
        value: 0,
        data: evm::encode_call(sig, args),
    }
}

fn balance_of(token: [u8; 20], who: [u8; 20]) -> Call {
    view(token, "balanceOf(address)", &[Abi::Address(who)])
}

async fn balance_key(rpc: &Rpc, token: [u8; 20], who: [u8; 20]) -> String {
    let list = rpc
        .request(
            "eth_createAccessList",
            json!([balance_of(token, who).json(&hex0x(&who)), "latest"]),
        )
        .await
        .unwrap();
    let keys: Vec<String> = list["accessList"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|e| {
            e["address"]
                .as_str()
                .unwrap()
                .eq_ignore_ascii_case(&hex0x(&token))
        })
        .flat_map(|e| {
            e["storageKeys"]
                .as_array()
                .unwrap()
                .iter()
                .map(|k| k.as_str().unwrap().to_string())
        })
        .collect();
    for key in keys {
        let r = rpc
            .request(
                "eth_call",
                json!([balance_of(token, who).json(&hex0x(&who)), "latest", {hex0x(&token): {"stateDiff": {&key: format!("0x{:064x}", 12345u128)}}}]),
            )
            .await
            .unwrap();
        if evm::uint(&unhex(&r).unwrap(), 0).ok() == Some(12345) {
            return key;
        }
    }
    panic!("no balance slot found for {}", hex0x(&token));
}

fn token_override(key: &str, amount: u128) -> Value {
    json!({"stateDiff": {key: format!("0x{amount:064x}")}})
}

struct Block {
    time_offset: Option<u64>,
    calls: Vec<(String, Call)>,
}

async fn simulate(rpc: &Rpc, overrides: Value, blocks: Vec<Block>) -> Vec<Vec<Value>> {
    let latest = rpc
        .request("eth_getBlockByNumber", json!(["latest", false]))
        .await
        .unwrap();
    let now = quantity(&latest["timestamp"]).unwrap() as u64;
    let mut states = Vec::new();
    for (i, b) in blocks.iter().enumerate() {
        let mut s = Map::new();
        if i == 0 {
            s.insert("stateOverrides".into(), overrides.clone());
        }
        if let Some(off) = b.time_offset {
            s.insert(
                "blockOverrides".into(),
                json!({"time": format!("0x{:x}", now + off)}),
            );
        }
        let calls: Vec<Value> = b
            .calls
            .iter()
            .map(|(from, c)| {
                let mut v = c.json(from);
                v["gas"] = json!("0x200000");
                v
            })
            .collect();
        s.insert("calls".into(), json!(calls));
        states.push(Value::Object(s));
    }
    let result = rpc
        .request(
            "eth_simulateV1",
            json!([{"blockStateCalls": states, "validation": false}, "latest"]),
        )
        .await
        .unwrap();
    result
        .as_array()
        .unwrap()
        .iter()
        .map(|b| b["calls"].as_array().unwrap().clone())
        .collect()
}

const RUNTIME: &str = include_str!("testdata/redpacket_runtime.hex");
/// Fee rate used in the simulations (per mille), written into storage slot 6 (`feeRate`).
const SIM_FEE_RATE: u128 = 20;

fn contract() -> [u8; 20] {
    evm::parse_address(chain::TEST_PACKETS).unwrap()
}

/// Puts the new contract's code at the test address with `feeRate = SIM_FEE_RATE`.
fn contract_override(overrides: &mut Value) {
    overrides[chain::TEST_PACKETS] = json!({
        "code": RUNTIME.trim(),
        "stateDiff": {format!("0x{:064x}", 6): format!("0x{SIM_FEE_RATE:064x}")}
    });
}

fn net_of_fee(amount: u128) -> u128 {
    amount - amount * SIM_FEE_RATE / 1000
}

fn claim_key() -> Identity {
    ident()
}

fn claim_sig(key: &Identity, chain_id: u64, creator: &Identity, id: &str, claimer: &Identity) -> Vec<u8> {
    personal_sign(
        key,
        &packet_abi::claim_preimage(chain_id, contract(), addr(creator), id, addr(claimer)),
    )
}

fn create_call(id: &str, token: [u8; 20], amount: u128, count: u32, key: &Identity, recipient: [u8; 20]) -> Call {
    packet_abi::create(
        contract(),
        &packet_abi::Create {
            id,
            token,
            amount,
            count,
            claim_key: addr(key),
            recipient,
            max_fee_rate: SIM_FEE_RATE,
        },
    )
}

fn revert_text(r: &Value) -> String {
    let data = unhex(&r["returnData"])
        .ok()
        .filter(|d| !d.is_empty())
        .or_else(|| unhex(&r["error"]["data"]).ok())
        .unwrap_or_default();
    if data.len() > 4 + 64 && data[..4] == evm::selector("Error(string)") {
        return String::from_utf8_lossy(evm::dynamic(&data[4..], 0).unwrap_or_default()).into();
    }
    format!("{} {}", r["error"], hex0x(&data))
}

/// Reverted with the contract's custom error `name()`.
fn expect_error(r: &Value, name: &str) {
    assert!(!ok(r), "call should have reverted with {name}");
    let data = unhex(&r["returnData"])
        .ok()
        .filter(|d| !d.is_empty())
        .or_else(|| unhex(&r["error"]["data"]).ok())
        .unwrap_or_default();
    assert_eq!(
        hex0x(data.get(..4).unwrap_or_default()),
        hex0x(&evm::selector(&format!("{name}()"))),
        "expected {name}, got {}",
        revert_text(r)
    );
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "reads X Layer and BSC mainnet and simulates; sends no transactions"]
async fn simulate_native_red_packet_lifecycle() {
    let http = crate::transport::http_client();
    for (chain_id, url) in SIMS {
        native_red_packet_lifecycle(&Rpc::with_url(&http, chain_id, url).unwrap(), chain_id).await;
    }
}

async fn native_red_packet_lifecycle(rpc: &Rpc, chain_id: u64) {
    let contract = contract();
    let (a, b, c, d, e) = (ident(), ident(), ident(), ident(), ident());
    let key = claim_key();
    let rich = json!({"balance": format!("0x{:x}", 10 * ONE)});
    let mut overrides = json!({a.address(): rich, b.address(): rich, c.address(): rich, d.address(): rich, e.address(): rich});
    contract_override(&mut overrides);
    let id = packet_abi::new_packet_id();
    let total = ONE / 100;
    let pk = [Abi::Address(addr(&a)), Abi::Str(&id)];
    let info = view(contract, "getRedPacketInfo(address,string)", &pk);
    let records = view(contract, "getClaimRecords(address,string)", &pk);
    let claimed = |who: &Identity| {
        view(
            contract,
            "getClaimedAmount(address,string,address)",
            &[Abi::Address(addr(&a)), Abi::Str(&id), Abi::Address(addr(who))],
        )
    };
    let claim = |_who: &Identity, sig: &[u8]| packet_abi::claim(contract, addr(&a), &id, sig);
    let sig_b = claim_sig(&key, chain_id, &a, &id, &b);
    let from = |i: &Identity| i.address().to_string();
    let me = |i: &Identity| addr(i);
    let blocks = vec![
        Block {
            time_offset: Some(100),
            calls: vec![
                (from(&a), create_call(&id, [0; 20], total, 3, &key, [0; 20])),
                (from(&a), create_call(&id, [0; 20], total, 3, &key, [0; 20])),
                (from(&b), claim(&b, &sig_b)),
                (from(&b), claim(&b, &sig_b)),
                // the original bug: someone else reusing b's signature from the chain
                (from(&e), claim(&e, &sig_b)),
                // a signature by the creator's own key is not the claim key
                (from(&c), claim(&c, &claim_sig(&a, chain_id, &a, &id, &c))),
                (from(&c), claim(&c, &claim_sig(&key, chain_id, &a, &id, &c))),
                (from(&a), packet_abi::refund(contract, &id, me(&a))),
                (from(&a), info.clone()),
                (from(&a), records.clone()),
                (from(&a), claimed(&b)),
            ],
        },
        Block {
            time_offset: Some(100 + 604_799),
            calls: vec![(from(&a), packet_abi::refund(contract, &id, me(&a)))],
        },
        Block {
            time_offset: Some(100 + 604_800),
            calls: vec![
                (from(&b), packet_abi::refund(contract, &id, me(&b))),
                (from(&a), packet_abi::refund(contract, &id, me(&a))),
                (from(&a), info.clone()),
                (from(&d), claim(&d, &claim_sig(&key, chain_id, &a, &id, &d))),
            ],
        },
    ];
    let out = simulate(rpc, overrides, blocks).await;
    let b0 = &out[0];
    expect_ok(&b0[0]);
    expect_error(&b0[1], "PacketExists");
    expect_ok(&b0[2]);
    expect_error(&b0[3], "AlreadyClaimed");
    expect_error(&b0[4], "InvalidSignature");
    expect_error(&b0[5], "InvalidSignature");
    expect_ok(&b0[6]);
    expect_error(&b0[7], "RefundTooEarly");
    let p = packet_abi::decode_info(&ret(&b0[8])).unwrap();
    assert_eq!(
        (
            p.creator,
            p.token,
            p.claim_key,
            p.recipient,
            p.total_amount,
            p.fee,
            p.total_count,
            p.remain_count,
            p.refunded
        ),
        (
            addr(&a),
            [0; 20],
            addr(&key),
            [0; 20],
            net_of_fee(total),
            total - net_of_fee(total),
            3,
            1,
            false
        )
    );
    let recs = packet_abi::decode_records(&ret(&b0[9])).unwrap();
    assert_eq!(
        recs.iter().map(|r| r.0).collect::<Vec<_>>(),
        vec![addr(&b), addr(&c)]
    );
    assert_eq!(
        recs.iter().map(|r| r.1).sum::<u128>(),
        p.total_amount - p.remain_amount
    );
    assert_eq!(evm::uint(&ret(&b0[10]), 0).unwrap(), recs[0].1);
    expect_error(&out[1][0], "RefundTooEarly");
    let b2 = &out[2];
    expect_error(&b2[0], "NoSuchPacket");
    expect_ok(&b2[1]);
    let p = packet_abi::decode_info(&ret(&b2[2])).unwrap();
    assert_eq!((p.remain_amount, p.refunded), (0, true));
    expect_error(&b2[3], "PacketRefunded");
    println!(
        "{chain_id} native red packet: create {} gas, claim {} gas, refund {} gas",
        b0[0]["gasUsed"], b0[2]["gasUsed"], b2[1]["gasUsed"]
    );
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "reads X Layer and BSC mainnet and simulates; sends no transactions"]
async fn simulate_erc20_red_packet_and_transfer() {
    let http = crate::transport::http_client();
    for t in chain::TOKENS.iter().filter(|t| t.address.is_some()) {
        let symbol = t.symbol;
        let (_, url) = SIMS.into_iter().find(|s| s.0 == t.chain).unwrap();
        let rpc = Rpc::with_url(&http, t.chain, url).unwrap();
        let contract = contract();
        let token = t.address_bytes();
        let (a, b, c) = (ident(), ident(), ident());
        let (k_red, k_tr) = (claim_key(), claim_key());
        let key = balance_key(&rpc, token, addr(&a)).await;
        let gas = json!({"balance": format!("0x{:x}", ONE)});
        let mut overrides = json!({a.address(): gas, b.address(): gas, c.address(): gas});
        overrides[hex0x(&token)] = token_override(&key, 100 * ONE);
        contract_override(&mut overrides);
        let red = packet_abi::new_packet_id();
        let tr = packet_abi::new_packet_id();
        let from = |i: &Identity| i.address().to_string();
        let amount = 5 * ONE;
        let blocks = vec![Block {
            time_offset: None,
            calls: vec![
                (from(&a), create_call(&red, token, amount, 2, &k_red, [0; 20])),
                (from(&a), packet_abi::approve(token, contract, amount)),
                (from(&a), create_call(&red, token, amount, 2, &k_red, [0; 20])),
                (
                    from(&b),
                    packet_abi::claim(contract, addr(&a), &red, &claim_sig(&k_red, t.chain, &a, &red, &b)),
                ),
                (from(&a), packet_abi::approve(token, contract, ONE)),
                // transfer bound to c
                (from(&a), create_call(&tr, token, ONE, 1, &k_tr, addr(&c))),
                // b holds the claim key too (e.g. a leaked message) but is not the receiver
                (
                    from(&b),
                    packet_abi::claim(contract, addr(&a), &tr, &claim_sig(&k_tr, t.chain, &a, &tr, &b)),
                ),
                (
                    from(&c),
                    packet_abi::claim(contract, addr(&a), &tr, &claim_sig(&k_tr, t.chain, &a, &tr, &c)),
                ),
                (
                    from(&a),
                    packet_abi::erc20_transfer(token, addr(&c), 2 * ONE),
                ),
                (from(&a), balance_of(token, addr(&a))),
                (from(&a), balance_of(token, addr(&b))),
                (from(&a), balance_of(token, addr(&c))),
                (
                    from(&a),
                    view(
                        contract,
                        "getRedPacketInfo(address,string)",
                        &[Abi::Address(addr(&a)), Abi::Str(&tr)],
                    ),
                ),
            ],
        }];
        let r = &simulate(&rpc, overrides, blocks).await[0];
        assert!(!ok(&r[0]), "{symbol} create without approve must fail");
        for i in [1, 2, 3, 4, 5, 7, 8] {
            expect_ok(&r[i]);
        }
        expect_error(&r[6], "NotRecipient");
        let bal = |i: usize| evm::uint(&ret(&r[i]), 0).unwrap();
        assert_eq!(bal(9), 100 * ONE - amount - ONE - 2 * ONE);
        let got_b = bal(10);
        assert!(got_b > 0 && got_b <= net_of_fee(amount));
        assert_eq!(bal(11), net_of_fee(ONE) + 2 * ONE);
        let p = packet_abi::decode_info(&ret(&r[12])).unwrap();
        assert_eq!(
            (p.remain_count, p.remain_amount, p.token, p.recipient),
            (0, 0, token, addr(&c))
        );
        println!(
            "{symbol}: approve {} gas, create {} gas, claim {} gas, transfer {} gas",
            r[1]["gasUsed"], r[2]["gasUsed"], r[3]["gasUsed"], r[8]["gasUsed"]
        );
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "reads X Layer and BSC mainnet and simulates; sends no transactions"]
async fn simulate_wallet_sends() {
    let http = crate::transport::http_client();
    for (chain_id, url) in [(196, XLAYER_SIM), (56, BSC_SIM)] {
        let rpc = Rpc::with_url(&http, chain_id, url).unwrap();
        let (a, b) = (ident(), ident());
        let mut overrides = json!({a.address(): {"balance": format!("0x{:x}", 10 * ONE)}});
        let mut calls = Vec::new();
        let mut checks = Vec::new();
        for t in chain::TOKENS.iter().filter(|t| t.chain == chain_id) {
            let amount = crate::chain::parse_amount("1.5", t.decimals).unwrap();
            match t.address {
                None => calls.push((
                    a.address().to_string(),
                    Call {
                        to: addr(&b),
                        value: amount,
                        data: Vec::new(),
                    },
                )),
                Some(_) => {
                    let key = balance_key(&rpc, t.address_bytes(), addr(&a)).await;
                    overrides[hex0x(&t.address_bytes())] = token_override(&key, 10 * amount);
                    calls.push((
                        a.address().to_string(),
                        packet_abi::erc20_transfer(t.address_bytes(), addr(&b), amount),
                    ));
                    checks.push((t.symbol, calls.len(), amount));
                    calls.push((
                        a.address().to_string(),
                        balance_of(t.address_bytes(), addr(&b)),
                    ));
                }
            }
        }
        let r = &simulate(
            &rpc,
            overrides,
            vec![Block {
                time_offset: None,
                calls,
            }],
        )
        .await[0];
        assert_eq!(r[0]["gasUsed"], "0x5208");
        expect_ok(&r[0]);
        for (symbol, i, amount) in checks {
            expect_ok(&r[i - 1]);
            assert_eq!(evm::uint(&ret(&r[i]), 0).unwrap(), amount, "{symbol}");
            println!(
                "chain {chain_id} {symbol}: transfer {} gas",
                r[i - 1]["gasUsed"]
            );
        }
    }
}

/// The deployed contracts run exactly the audited code, and their fee rate is one this client accepts.
#[tokio::test(flavor = "multi_thread")]
#[ignore = "reads X Layer and BSC mainnet; sends no transactions"]
async fn deployed_contracts_match_audited_code() {
    let http = crate::transport::http_client();
    for (chain_id, deployed) in [
        (196, chain::DEPLOYED_XLAYER_PACKETS),
        (56, chain::DEPLOYED_BSC_PACKETS),
    ] {
        let Some(address) = deployed else {
            println!("chain {chain_id}: not deployed yet");
            continue;
        };
        let (_, url) = SIMS.into_iter().find(|s| s.0 == chain_id).unwrap();
        let rpc = Rpc::with_url(&http, chain_id, url).unwrap();
        let code = rpc
            .request("eth_getCode", json!([address, "latest"]))
            .await
            .unwrap();
        assert_eq!(
            code.as_str().unwrap().to_lowercase(),
            RUNTIME.trim().to_lowercase(),
            "chain {chain_id}: deployed code differs from the audited runtime"
        );
        let contract = evm::parse_address(address).unwrap();
        let rate = rpc.uint(contract, "feeRate()", &[]).await.unwrap();
        let max = chain::chain(chain_id).unwrap().max_packet_fee_rate;
        assert!(rate <= max, "chain {chain_id}: fee rate {rate} above client max {max}");
        println!("chain {chain_id}: {address} matches audited code, fee rate {rate}/1000 (client max {max})");
    }
}

/// Red packet, transfer and the claim checks against the real deployed X Layer contract (its own storage:
/// fee rate and minimum amounts), simulated only.
#[tokio::test(flavor = "multi_thread")]
#[ignore = "reads X Layer mainnet and simulates; sends no transactions"]
async fn simulate_on_deployed_xlayer() {
    let http = crate::transport::http_client();
    for (chain_id, url, deployed, bem, min_native) in [
        (196, XLAYER_SIM, chain::DEPLOYED_XLAYER_PACKETS, "196:BEM", true),
        (56, BSC_SIM, chain::DEPLOYED_BSC_PACKETS, "56:BEM", false),
    ] {
        let Some(deployed) = deployed else { continue };
        let rpc = Rpc::with_url(&http, chain_id, url).unwrap();
        on_deployed(&rpc, chain_id, deployed, bem, min_native).await;
    }
}

async fn on_deployed(rpc: &Rpc, chain_id: u64, deployed: &str, bem: &str, min_native: bool) {
    let contract = evm::parse_address(deployed).unwrap();
    let rate = rpc.uint(contract, "feeRate()", &[]).await.unwrap();
    let max = chain::chain(chain_id).unwrap().max_packet_fee_rate;
    let bem = chain::token(bem).unwrap();
    let (a, b, c, e) = (ident(), ident(), ident(), ident());
    let (k_red, k_tr) = (claim_key(), claim_key());
    let slot = balance_key(&rpc, bem.address_bytes(), addr(&a)).await;
    let gas = json!({"balance": format!("0x{:x}", ONE)});
    let mut overrides = json!({a.address(): gas, b.address(): gas, c.address(): gas, e.address(): gas});
    overrides[hex0x(&bem.address_bytes())] = token_override(&slot, 1_000_000_000_000);
    let from = |i: &Identity| i.address().to_string();
    let mk = |id: &str, token: [u8; 20], amount: u128, count: u32, key: &Identity, to: [u8; 20]| {
        packet_abi::create(
            contract,
            &packet_abi::Create { id, token, amount, count, claim_key: addr(key), recipient: to, max_fee_rate: max },
        )
    };
    let sig = |key: &Identity, id: &str, who: &Identity| {
        personal_sign(key, &packet_abi::claim_preimage(chain_id, contract, addr(&a), id, addr(who)))
    };
    let (red, tr, small) = (packet_abi::new_packet_id(), packet_abi::new_packet_id(), packet_abi::new_packet_id());
    let bem_amount = 100_000_000; // 1 BEM
    let blocks = vec![Block {
        time_offset: None,
        calls: vec![
            (from(&a), packet_abi::approve(bem.address_bytes(), contract, 10 * bem_amount)),
            (from(&a), mk(&red, bem.address_bytes(), bem_amount, 3, &k_red, [0; 20])),
            (from(&b), packet_abi::claim(contract, addr(&a), &red, &sig(&k_red, &red, &b))),
            (from(&e), packet_abi::claim(contract, addr(&a), &red, &sig(&k_red, &red, &b))),
            (from(&a), mk(&tr, [0; 20], ONE / 10, 1, &k_tr, addr(&c))),
            (from(&b), packet_abi::claim(contract, addr(&a), &tr, &sig(&k_tr, &tr, &b))),
            (from(&c), packet_abi::claim(contract, addr(&a), &tr, &[])),
            // below the 0.01 OKB minimum
            (from(&a), mk(&small, [0; 20], ONE / 1000, 1, &k_tr, [0; 20])),
            (from(&a), view(contract, "getRedPacketInfo(address,string)", &[Abi::Address(addr(&a)), Abi::Str(&red)])),
        ],
    }];
    let r = &simulate(&rpc, overrides, blocks).await[0];
    for i in [0, 1, 2, 4, 6] {
        expect_ok(&r[i]);
    }
    expect_error(&r[3], "InvalidSignature");
    expect_error(&r[5], "NotRecipient");
    if min_native {
        expect_error(&r[7], "AmountTooSmall");
    } else {
        expect_ok(&r[7]);
    }
    let p = packet_abi::decode_info(&ret(&r[8])).unwrap();
    assert_eq!(p.total_amount + p.fee, bem_amount);
    assert_eq!(p.fee, bem_amount * rate / 1000);
    assert_eq!(p.remain_count, 2);
    println!(
        "deployed contract on {chain_id}: fee {rate}/1000, BEM create {} gas, claim {} gas, OKB transfer create {} gas, claim {} gas",
        r[1]["gasUsed"], r[2]["gasUsed"], r[4]["gasUsed"], r[6]["gasUsed"]
    );
}
