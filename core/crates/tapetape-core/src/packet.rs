use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, info, warn};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use taperelay::evm::{self, Abi};
use taperelay::Identity;

use crate::account::Account;
use crate::api::*;
use crate::chain::{self, format_amount, hex0x, parse_amount, personal_sign, quantity, Rpc, TokenCfg};
use crate::chat::{target, CHAT_CONTACT, CHAT_GROUP};
use crate::message::Draft;
use crate::packet_abi::{self, OnChain};
use crate::protocol::{now_ms, Body};
use crate::wallet::{check_fee, quote};

const EXPIRY_MS: i64 = 604_800_000;
const TX_WAIT: Duration = Duration::from_secs(180);
const CREATE_GAS: u128 = 420_000;

#[derive(Serialize, Deserialize, Clone)]
pub(crate) struct Stored {
    pub kind: PacketKind,
    pub chain: u64,
    pub contract: String,
    pub id: String,
    pub token: Option<String>,
    pub symbol: String,
    pub amount: String,
    pub count: u32,
    pub text: String,
    pub tx: String,
    pub creator: String,
    /// Private key of the packet's one-time claim key (0x + 64 hex).
    pub claim_key: String,
    /// Transfers: the receiver's address (set by the sender). Red packets: None.
    #[serde(default)]
    pub recipient: Option<String>,
    /// Transfers paid straight into the receiver's wallet (no contract, no claim key, nothing to claim).
    /// `id` and `tx` are both the payment's transaction hash.
    #[serde(default)]
    pub direct: bool,
    pub status: PacketStatus,
}

/// keccak256("Transfer(address,address,uint256)")
const ERC20_TRANSFER_TOPIC: &str = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

fn is_tx_hash(s: &str) -> bool {
    s.len() == 66 && s.starts_with("0x") && s[2..].bytes().all(|b| b.is_ascii_hexdigit())
}

fn topic_address(a: &str) -> String {
    format!("0x{:0>64}", a.trim_start_matches("0x").to_lowercase())
}

/// Whether a mined transaction really paid the card: from the creator, to the bound receiver, this token,
/// exactly this amount. `tx` (the transaction itself) is needed for native coin, whose value is not in the receipt.
fn direct_paid(p: &Stored, t: &TokenCfg, receipt: &Value, tx: Option<&Value>) -> bool {
    let (Some(value), Some(recipient)) = (parse_amount(&p.amount, t.decimals), p.recipient.as_deref()) else {
        return false;
    };
    let eq = |v: &Value, s: &str| v.as_str().is_some_and(|x| x.eq_ignore_ascii_case(s));
    if !eq(&receipt["status"], "0x1") || !eq(&receipt["transactionHash"], &p.tx) || !eq(&receipt["from"], &p.creator) {
        return false;
    }
    match t.address {
        None => tx.is_some_and(|tx| {
            eq(&tx["hash"], &p.tx)
                && eq(&tx["from"], &p.creator)
                && eq(&tx["to"], recipient)
                && quantity(&tx["value"]).ok() == Some(value)
        }),
        Some(token) => receipt["logs"].as_array().is_some_and(|logs| {
            logs.iter().any(|l| {
                let topics = l["topics"].as_array();
                eq(&l["address"], token)
                    && topics.is_some_and(|tp| {
                        tp.len() == 3
                            && eq(&tp[0], ERC20_TRANSFER_TOPIC)
                            && eq(&tp[1], &topic_address(&p.creator))
                            && eq(&tp[2], &topic_address(recipient))
                    })
                    && l["data"].as_str().is_some_and(|d| {
                        let h = d.strip_prefix("0x").unwrap_or_default();
                        h.len() == 64
                            && h[..32].bytes().all(|b| b == b'0')
                            && u128::from_str_radix(&h[32..], 16).ok() == Some(value)
                    })
            })
        }),
    }
}

/// The claim key as a signing identity. The host only satisfies `Identity`; it is never used.
fn claim_identity(key: &str) -> Result<Identity> {
    let raw = hex::decode(key.strip_prefix("0x").context("bad claim key")?)?;
    let raw: [u8; 32] = raw.try_into().ok().context("bad claim key")?;
    Identity::new(&raw, "relay1.taperelay.com:4246").context("bad claim key")
}

fn new_claim_key() -> Result<String> {
    loop {
        let raw: [u8; 32] = rand::random();
        if let Ok(id) = Identity::new(&raw, "relay1.taperelay.com:4246") {
            return Ok(hex0x(&id.private_key()));
        }
    }
}

/// `key:0x…@host` → the 0x address.
fn account_address(account: &str) -> Option<String> {
    let a = account.strip_prefix("key:")?.split('@').next()?;
    evm::parse_address(a).ok().map(|b| hex0x(&b))
}

fn initial(state: PacketState, count: u32, can_claim: bool) -> PacketStatus {
    PacketStatus {
        state,
        claimed_count: 0,
        total_count: count,
        claimed_amount: "0".into(),
        my_amount: None,
        can_claim,
        can_refund: false,
        expires_at: None,
        records: Vec::new(),
        checked_at: 0,
    }
}

impl Stored {
    fn token_cfg(&self) -> Result<&'static TokenCfg> {
        chain::token_by_address(self.chain, self.token.as_deref()).context("Unknown token")
    }

    fn mine(&self, me: &str) -> bool {
        self.creator.eq_ignore_ascii_case(me)
    }

    pub fn record(&self, me: &str) -> Packet {
        Packet {
            kind: self.kind,
            packet_id: self.id.clone(),
            chain_id: self.chain,
            token_id: self.token_cfg().ok().map(|t| t.id()),
            symbol: self.symbol.clone(),
            amount: self.amount.clone(),
            count: self.count,
            text: self.text.clone(),
            tx: self.tx.clone(),
            creator: self.creator.clone(),
            mine: self.mine(me),
            status: self.status.clone(),
        }
    }

    pub fn body(&self) -> Result<Body> {
        let chain = chain::chain(self.chain)?.key.to_string();
        let token = self.token.clone().unwrap_or_else(|| "native".into());
        Ok(match self.kind {
            PacketKind::Transfer if self.direct => Body::TransferDirect {
                chain,
                token,
                symbol: self.symbol.clone(),
                amount: self.amount.clone(),
                note: self.text.clone(),
                tx: self.tx.clone(),
                creator: self.creator.clone(),
                recipient: self.recipient.clone().context("transfer has no receiver")?,
            },
            PacketKind::RedPacket => Body::Redpacket {
                chain,
                contract: self.contract.clone(),
                packet: self.id.clone(),
                token,
                symbol: self.symbol.clone(),
                total: self.amount.clone(),
                count: self.count,
                greeting: self.text.clone(),
                tx: self.tx.clone(),
                creator: self.creator.clone(),
                claim_key: self.claim_key.clone(),
            },
            PacketKind::Transfer => Body::Transfer {
                chain,
                contract: self.contract.clone(),
                transfer: self.id.clone(),
                token,
                symbol: self.symbol.clone(),
                amount: self.amount.clone(),
                note: self.text.clone(),
                tx: self.tx.clone(),
                creator: self.creator.clone(),
                claim_key: self.claim_key.clone(),
            },
        })
    }

    pub fn from_body(body: &Body, from: &str) -> Result<Stored> {
        if let Body::TransferDirect {
            chain,
            token,
            amount,
            note,
            tx,
            creator,
            recipient,
            ..
        } = body
        {
            let cfg = chain::chain_by_key(chain).context("Unknown chain")?;
            let token = (token != "native").then(|| token.to_lowercase());
            let t = chain::token_by_address(cfg.id, token.as_deref()).context("Unknown token")?;
            ensure!(
                parse_amount(amount, t.decimals).is_some_and(|v| v > 0),
                "bad transfer amount"
            );
            ensure!(
                from.starts_with(&format!("key:{}@", creator.to_lowercase())),
                "transfer creator does not match sender"
            );
            evm::parse_address(recipient).context("bad transfer receiver")?;
            ensure!(is_tx_hash(tx), "bad transfer tx");
            return Ok(Stored {
                kind: PacketKind::Transfer,
                chain: cfg.id,
                contract: String::new(),
                id: tx.to_lowercase(),
                token,
                symbol: t.symbol.into(),
                amount: amount.clone(),
                count: 1,
                text: note.clone(),
                tx: tx.to_lowercase(),
                creator: creator.to_lowercase(),
                claim_key: String::new(),
                recipient: Some(recipient.to_lowercase()),
                direct: true,
                // Not received until read_direct has checked the payment on chain.
                status: initial(PacketState::Open, 1, false),
            });
        }
        let (kind, chain, contract, id, token, _, amount, count, text, tx, creator, claim_key) =
            match body {
                Body::Redpacket {
                    chain,
                    contract,
                    packet,
                    token,
                    symbol,
                    total,
                    count,
                    greeting,
                    tx,
                    creator,
                    claim_key,
                } => (
                    PacketKind::RedPacket,
                    chain,
                    contract,
                    packet,
                    token,
                    symbol,
                    total,
                    *count,
                    greeting,
                    tx,
                    creator,
                    claim_key,
                ),
                Body::Transfer {
                    chain,
                    contract,
                    transfer,
                    token,
                    symbol,
                    amount,
                    note,
                    tx,
                    creator,
                    claim_key,
                } => (
                    PacketKind::Transfer,
                    chain,
                    contract,
                    transfer,
                    token,
                    symbol,
                    amount,
                    1,
                    note,
                    tx,
                    creator,
                    claim_key,
                ),
                _ => bail!("not a packet"),
            };
        let cfg = chain::chain_by_key(chain).context("Unknown chain")?;
        ensure!(
            cfg.packets
                .is_some_and(|c| c.eq_ignore_ascii_case(contract)),
            "unknown packet contract {contract}"
        );
        let token = (token != "native").then(|| token.to_lowercase());
        let t = chain::token_by_address(cfg.id, token.as_deref()).context("Unknown token")?;
        ensure!(
            parse_amount(amount, t.decimals).is_some() && count > 0,
            "bad packet amount"
        );
        ensure!(
            from.starts_with(&format!("key:{}@", creator.to_lowercase())),
            "packet creator does not match sender"
        );
        claim_identity(claim_key).context("bad packet claim key")?;
        Ok(Stored {
            kind,
            chain: cfg.id,
            contract: contract.clone(),
            id: id.clone(),
            token,
            symbol: t.symbol.into(),
            amount: amount.clone(),
            count,
            text: text.clone(),
            tx: tx.clone(),
            creator: creator.to_lowercase(),
            claim_key: claim_key.to_lowercase(),
            recipient: None,
            direct: false,
            // Not claimable until refresh_packet has checked it against the chain.
            status: initial(PacketState::Open, count, false),
        })
    }
}

pub(crate) fn sendable(conn: &Connection, msg_id: u32) -> Result<bool> {
    let raw: Option<String> =
        conn.query_row("SELECT packet FROM messages WHERE id=?", [msg_id], |r| {
            r.get(0)
        })?;
    Ok(match raw {
        None => true,
        Some(raw) => !matches!(
            serde_json::from_str::<Stored>(&raw)?.status.state,
            PacketState::Creating | PacketState::Failed
        ),
    })
}

fn lower(a: &[u8; 20]) -> String {
    hex0x(a)
}

impl Account {
    pub(crate) fn load_packet(&self, msg_id: u32) -> Result<(u32, Stored)> {
        let (chat_id, raw): (u32, Option<String>) = self.db().query_row(
            "SELECT chat_id, packet FROM messages WHERE id=?",
            [msg_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        Ok((
            chat_id,
            serde_json::from_str(&raw.context("Not a red packet or transfer")?)?,
        ))
    }

    fn save_packet(&self, conn: &Connection, msg_id: u32, p: &Stored) -> Result<()> {
        conn.execute(
            "UPDATE messages SET packet=? WHERE id=?",
            params![serde_json::to_string(p)?, msg_id],
        )?;
        Ok(())
    }

    fn store_status(
        &self,
        msg_id: u32,
        chat_id: u32,
        mut p: Stored,
        s: PacketStatus,
    ) -> Result<()> {
        let changed = PacketStatus {
            checked_at: 0,
            ..s.clone()
        } != PacketStatus {
            checked_at: 0,
            ..p.status.clone()
        };
        p.status = s;
        self.save_packet(&self.db(), msg_id, &p)?;
        if changed {
            self.emit(vec![Event::MessagesChanged { chat_id }]);
        }
        Ok(())
    }

    /// A direct transfer: confirmed once its payment is mined and matches the card. Pending → the receiver
    /// sees it as on its way; the sender gets None so `settle_creating` keeps waiting.
    async fn read_direct(&self, p: &Stored) -> Result<Option<PacketStatus>> {
        let t = p.token_cfg()?;
        let rpc = Rpc::new(&self.http(), p.chain)?;
        let me = self.keys.id.address().to_lowercase();
        let mine = p.mine(&me);
        let recipient = p.recipient.clone().context("transfer has no receiver")?;
        if !mine && !recipient.eq_ignore_ascii_case(&me) {
            warn!("transfer {} is not for me", p.tx);
            return Ok(None);
        }
        let Some(receipt) = rpc.receipt(&p.tx).await? else {
            let pending = rpc.status(&p.tx).await? == TxStatus::Pending;
            return Ok((pending && !mine).then(|| initial(PacketState::Open, 1, false)));
        };
        let tx = match t.address {
            None => Some(rpc.request("eth_getTransactionByHash", json!([p.tx])).await?),
            Some(_) => None,
        };
        if !direct_paid(p, t, &receipt, tx.as_ref()) {
            warn!("transfer {} does not match its payment on chain", p.tx);
            return Ok(None);
        }
        let contact_id = self
            .db()
            .query_row(
                "SELECT id FROM contacts WHERE account LIKE 'key:' || ? || '@%'",
                [&recipient],
                |r| r.get(0),
            )
            .optional()?;
        let amount = p.amount.clone();
        Ok(Some(PacketStatus {
            state: PacketState::Finished,
            claimed_count: 1,
            total_count: 1,
            claimed_amount: amount.clone(),
            my_amount: (!mine).then(|| amount.clone()),
            can_claim: false,
            can_refund: false,
            expires_at: None,
            records: vec![ClaimRecord {
                me: !mine,
                address: recipient,
                amount,
                contact_id,
            }],
            checked_at: now_ms(),
        }))
    }

    async fn read_chain(&self, p: &Stored) -> Result<Option<PacketStatus>> {
        if p.direct {
            return self.read_direct(p).await;
        }
        let t = p.token_cfg()?;
        let rpc = Rpc::new(&self.http(), p.chain)?;
        let contract = evm::parse_address(&p.contract)?;
        let creator = evm::parse_address(&p.creator)?;
        let key = [Abi::Address(creator), Abi::Str(&p.id)];
        let info = packet_abi::decode_info(
            &rpc.call(contract, "getRedPacketInfo(address,string)", &key)
                .await?,
        )?;
        let me = self.keys.id.address();
        if !matches_message(&info, p, t, me)? {
            debug!("packet {} not on chain or does not match its card", p.id);
            return Ok(None);
        }
        let records = packet_abi::decode_records(
            &rpc.call(contract, "getClaimRecords(address,string)", &key)
                .await?,
        )?;
        let conn = self.db();
        let records: Vec<ClaimRecord> = records
            .into_iter()
            .map(|(a, amount)| {
                let address = lower(&a);
                let contact_id = conn
                    .query_row(
                        "SELECT id FROM contacts WHERE account LIKE 'key:' || ? || '@%'",
                        [&address],
                        |r| r.get(0),
                    )
                    .optional()?;
                Ok(ClaimRecord {
                    me: address == me,
                    address,
                    amount: format_amount(amount, t.decimals),
                    contact_id,
                })
            })
            .collect::<Result<_>>()?;
        drop(conn);
        let now = now_ms();
        let expires_at = info.create_time as i64 * 1000 + EXPIRY_MS;
        let state = if info.refunded {
            PacketState::Refunded
        } else if info.remain_count == 0 {
            PacketState::Finished
        } else if now >= expires_at {
            PacketState::Expired
        } else {
            PacketState::Open
        };
        let my_amount = records.iter().find(|r| r.me).map(|r| r.amount.clone());
        let mine = p.mine(me);
        Ok(Some(PacketStatus {
            state,
            claimed_count: (info.total_count - info.remain_count) as u32,
            total_count: info.total_count as u32,
            claimed_amount: format_amount(info.total_amount - info.remain_amount, t.decimals),
            can_claim: state == PacketState::Open
                && my_amount.is_none()
                && !(p.kind == PacketKind::Transfer && mine),
            can_refund: mine && state == PacketState::Expired,
            my_amount,
            expires_at: Some(expires_at),
            records,
            checked_at: now,
        }))
    }

    async fn settle_creating(
        &self,
        msg_id: u32,
        chat_id: u32,
        mut p: Stored,
        error: Option<String>,
    ) -> Result<PacketStatus> {
        if let Some(s) = self.read_chain(&p).await? {
            p.status = s.clone();
            let mut conn = self.db();
            let tx = conn.transaction()?;
            self.save_packet(&tx, msg_id, &p)?;
            match self.chat_target_for_send(&tx, chat_id) {
                Ok(t) => self.dispatch(&tx, &t, chat_id, msg_id)?,
                Err(e) => warn!("{:?} {} created but cannot be sent: {e}", p.kind, p.id),
            }
            tx.commit()?;
            drop(conn);
            info!("{:?} {} created in message {msg_id}", p.kind, p.id);
            self.emit(vec![
                Event::MessagesChanged { chat_id },
                Event::ChatListChanged,
            ]);
            return Ok(s);
        }
        if !p.tx.is_empty()
            && Rpc::new(&self.http(), p.chain)?.status(&p.tx).await? == TxStatus::Pending
        {
            return Ok(p.status);
        }
        warn!("{:?} {} was not created: {error:?}", p.kind, p.id);
        p.status.state = PacketState::Failed;
        p.status.can_claim = false;
        let conn = self.db();
        self.save_packet(&conn, msg_id, &p)?;
        conn.execute(
            "UPDATE messages SET state=?, error=? WHERE id=?",
            params![
                MessageState::Failed.code(),
                error.unwrap_or_else(|| "tx-failed".into()),
                msg_id
            ],
        )?;
        drop(conn);
        self.emit(vec![
            Event::MessagesChanged { chat_id },
            Event::ChatListChanged,
        ]);
        Ok(p.status)
    }

    pub(crate) async fn refresh_packet(&self, msg_id: u32) -> Result<(u32, Stored, PacketStatus)> {
        let (chat_id, p) = self.load_packet(msg_id)?;
        let s = match p.status.state {
            PacketState::Creating => match self.tx_lock.try_lock() {
                Ok(_guard) => {
                    self.settle_creating(msg_id, chat_id, p.clone(), None)
                        .await?
                }
                Err(_) => p.status.clone(),
            },
            // A mined, checked payment cannot change any more.
            PacketState::Finished if p.direct => p.status.clone(),
            _ => {
                let mut s = self
                    .read_chain(&p)
                    .await?
                    .unwrap_or_else(|| initial(PacketState::Failed, p.count, false));
                s.checked_at = now_ms();
                self.store_status(msg_id, chat_id, p.clone(), s.clone())?;
                s
            }
        };
        Ok((chat_id, p, s))
    }

    pub(crate) async fn plan_packet(
        &self,
        t: &'static TokenCfg,
        amount: &str,
        count: u32,
    ) -> Result<(SendEstimate, u128, bool, u128)> {
        let contract = evm::parse_address(
            t.chain()
                .packets
                .context("This token does not support red packets")?,
        )?;
        let rpc = Rpc::new(&self.http(), t.chain)?;
        let me = self.keys.id.address();
        let value = parse_amount(amount, t.decimals).filter(|v| *v > 0);
        let me_bytes = evm::parse_address(me)?;
        let token_arg = [Abi::Address(t.address_bytes())];
        let (balance, native, price, min, rate, allowance) = tokio::try_join!(
            rpc.balance(t, me),
            rpc.native_balance(me),
            rpc.gas_price(),
            rpc.uint_saturating(contract, "minAmount(address)", &token_arg),
            rpc.uint(contract, "feeRate()", &[]),
            async {
                match t.address {
                    None => Ok(u128::MAX),
                    Some(_) => {
                        rpc.uint(
                            t.address_bytes(),
                            "allowance(address,address)",
                            &[Abi::Address(me_bytes), Abi::Address(contract)],
                        )
                        .await
                    }
                }
            }
        )?;
        let v = value.unwrap_or(0);
        let max_rate = t.chain().max_packet_fee_rate;
        let error = if count == 0 {
            Some("invalid-count")
        } else if rate > max_rate {
            Some("fee-too-high")
        } else if value.is_some()
            && (v < min || v - v.saturating_mul(rate) / 1000 < u128::from(count))
        {
            Some("amount-too-small")
        } else {
            None
        };
        let approve = allowance < v.max(1);
        let approve_gas = if approve {
            rpc.estimate(me, &packet_abi::approve(t.address_bytes(), contract, v))
                .await?
        } else {
            0
        };
        let probe = quote(
            t,
            value,
            balance,
            native,
            approve_gas.checked_add(CREATE_GAS).and_then(|g| g.checked_mul(price)).context("invalid-fee")?,
            error,
        );
        let create_gas = if approve || probe.error.is_some() {
            CREATE_GAS
        } else {
            let call = packet_abi::create(
                contract,
                &packet_abi::Create {
                    id: &packet_abi::new_packet_id(),
                    token: t.address_bytes(),
                    amount: v,
                    count,
                    claim_key: [0x01; 20],
                    recipient: [0; 20],
                    max_fee_rate: max_rate,
                },
            );
            rpc.estimate(me, &call).await?
        };
        let fee = approve_gas.checked_add(create_gas).and_then(|g| g.checked_mul(price)).context("invalid-fee")?;
        Ok((quote(t, value, balance, native, fee, error), v, approve, max_rate))
    }

    async fn create_on_chain(
        &self,
        p: &mut Stored,
        msg_id: u32,
        value: u128,
        approve: bool,
        fee_rate: u128,
    ) -> Result<()> {
        let t = p.token_cfg()?;
        let rpc = Rpc::new(&self.http(), p.chain)?;
        let contract = evm::parse_address(&p.contract)?;
        let id = &self.keys.id;
        if approve {
            info!(
                "approving {} {} for packets",
                format_amount(value, t.decimals),
                t.symbol
            );
            rpc.execute(
                id,
                &packet_abi::approve(t.address_bytes(), contract, value),
                TX_WAIT,
            )
            .await?;
        }
        let recipient = match &p.recipient {
            Some(r) => evm::parse_address(r)?,
            None => [0; 20],
        };
        let call = packet_abi::create(
            contract,
            &packet_abi::Create {
                id: &p.id,
                token: t.address_bytes(),
                amount: value,
                count: p.count,
                claim_key: evm::parse_address(claim_identity(&p.claim_key)?.address())?,
                recipient,
                max_fee_rate: fee_rate,
            },
        );
        let gas = rpc.estimate(id.address(), &call).await?;
        let price = rpc.gas_price().await?;
        let tx = rpc.sign(id, &call, gas, price).await?;
        p.tx = tx.hash.clone();
        self.save_packet(&self.db(), msg_id, p)?;
        rpc.broadcast(&tx).await?;
        rpc.wait(&p.tx, TX_WAIT).await?;
        Ok(())
    }

    fn transfer_recipient(&self, chat_id: u32) -> Result<String> {
        let chat = target(&self.db(), chat_id)?;
        ensure!(
            chat.kind == CHAT_CONTACT,
            "Transfers can only be sent in one-to-one chats"
        );
        chat.account
            .as_deref()
            .and_then(account_address)
            .context("Transfer receiver has no wallet address")
    }

    /// Pays the contact's wallet directly (sender pays the network fee), then sends the card once mined.
    async fn send_direct(
        &self,
        chat_id: u32,
        token_id: &str,
        amount: &str,
        text: String,
        confirmed_fee: Option<&str>,
    ) -> Result<u32> {
        let t = chain::token(token_id)?;
        let recipient = self.transfer_recipient(chat_id)?;
        self.chat_target_for_send(&self.db(), chat_id)?;
        let _guard = self.tx_lock.lock().await;
        let (est, call, gas, price) = self.plan_send(t, &recipient, amount).await?;
        if let Some(e) = &est.error {
            bail!("{e}");
        }
        check_fee(t, &est, confirmed_fee)?;
        let rpc = Rpc::new(&self.http(), t.chain)?;
        let signed = rpc.sign(&self.keys.id, &call, gas, price).await?;
        let p = Stored {
            kind: PacketKind::Transfer,
            chain: t.chain,
            contract: String::new(),
            id: signed.hash.clone(),
            token: t.address.map(str::to_lowercase),
            symbol: t.symbol.into(),
            amount: est.amount.clone(),
            count: 1,
            text: text.clone(),
            tx: signed.hash.clone(),
            creator: self.keys.id.address().to_lowercase(),
            claim_key: String::new(),
            recipient: Some(recipient),
            direct: true,
            status: initial(PacketState::Creating, 1, false),
        };
        let msg_id = self.store_draft(
            chat_id,
            Draft {
                kind: MessageKind::Transfer.code(),
                text,
                packet: Some(serde_json::to_string(&p)?),
                ..Draft::default()
            },
            false,
        )?;
        info!("paying transfer {} in message {msg_id}", p.tx);
        let error = async {
            rpc.broadcast(&signed).await?;
            rpc.wait(&p.tx, TX_WAIT).await?;
            anyhow::Ok(())
        }
        .await
        .err();
        let s = self
            .settle_creating(msg_id, chat_id, p, error.as_ref().map(|e| e.to_string()))
            .await?;
        match (s.state, error) {
            (PacketState::Failed | PacketState::Creating, Some(e)) => Err(e),
            (PacketState::Failed, None) => bail!("tx-failed"),
            _ => Ok(msg_id),
        }
    }

    #[allow(clippy::too_many_arguments)]
    async fn send_packet(
        &self,
        kind: PacketKind,
        chat_id: u32,
        token_id: &str,
        amount: &str,
        count: u32,
        text: String,
        confirmed_fee: Option<&str>,
    ) -> Result<u32> {
        let t = chain::token(token_id)?;
        let contract = t
            .chain()
            .packets
            .context("This token does not support red packets")?;
        let chat = target(&self.db(), chat_id)?;
        let chat_kind = chat.kind;
        match kind {
            PacketKind::RedPacket => ensure!(
                chat_kind == CHAT_GROUP,
                "Red packets can only be sent in groups"
            ),
            PacketKind::Transfer => ensure!(
                chat_kind == CHAT_CONTACT,
                "Transfers can only be sent in one-to-one chats"
            ),
        }
        self.chat_target_for_send(&self.db(), chat_id)?;
        let _guard = self.tx_lock.lock().await;
        let recipient = match kind {
            PacketKind::Transfer => Some(
                chat.account
                    .as_deref()
                    .and_then(account_address)
                    .context("Transfer receiver has no wallet address")?,
            ),
            PacketKind::RedPacket => None,
        };
        let (est, value, approve, fee_rate) = self.plan_packet(t, amount, count).await?;
        if let Some(e) = &est.error {
            bail!("{e}");
        }
        check_fee(t, &est, confirmed_fee)?;
        let id = packet_abi::new_packet_id();
        let me = self.keys.id.address().to_string();
        let mut p = Stored {
            kind,
            chain: t.chain,
            contract: contract.to_lowercase(),
            claim_key: new_claim_key()?,
            recipient,
            direct: false,
            id,
            token: t.address.map(str::to_lowercase),
            symbol: t.symbol.into(),
            amount: format_amount(value, t.decimals),
            count,
            text: text.clone(),
            tx: String::new(),
            creator: me.clone(),
            status: initial(PacketState::Creating, count, false),
        };
        let msg_id = self.store_draft(
            chat_id,
            Draft {
                kind: MessageKind::from(kind).code(),
                text,
                packet: Some(serde_json::to_string(&p)?),
                ..Draft::default()
            },
            false,
        )?;
        info!("creating {kind:?} {} in message {msg_id}", p.id);
        let error = self
            .create_on_chain(&mut p, msg_id, value, approve, fee_rate)
            .await
            .err();
        let s = self
            .settle_creating(msg_id, chat_id, p, error.as_ref().map(|e| e.to_string()))
            .await?;
        match (s.state, error) {
            (PacketState::Failed | PacketState::Creating, Some(e)) => Err(e),
            (PacketState::Failed, None) => bail!("tx-failed"),
            _ => Ok(msg_id),
        }
    }

    async fn claim(&self, msg_id: u32) -> Result<String> {
        let _guard = self.tx_lock.lock().await;
        let (chat_id, p, s) = self.refresh_packet(msg_id).await?;
        if !s.can_claim {
            bail!(match s.state {
                _ if s.my_amount.is_some() => "already-claimed",
                PacketState::Finished => "finished",
                PacketState::Expired => "expired",
                PacketState::Refunded => "refunded",
                _ => "not-claimable",
            });
        }
        let t = p.token_cfg()?;
        let rpc = Rpc::new(&self.http(), p.chain)?;
        let contract = evm::parse_address(&p.contract)?;
        let me = self.keys.id.address();
        let creator = evm::parse_address(&p.creator)?;
        let preimage =
            packet_abi::claim_preimage(p.chain, contract, creator, &p.id, evm::parse_address(me)?);
        let signature = personal_sign(&claim_identity(&p.claim_key)?, &preimage);
        let call = packet_abi::claim(contract, creator, &p.id, &signature);
        let (gas, price, native) = tokio::try_join!(
            rpc.estimate(me, &call),
            rpc.gas_price(),
            rpc.native_balance(me)
        )?;
        ensure!(gas.checked_mul(price).is_some_and(|f| native >= f), "insufficient-gas");
        let hash = rpc.send(&self.keys.id, &call, gas, price).await?;
        rpc.wait(&hash, TX_WAIT).await?;
        let amount = rpc
            .uint(
                contract,
                "getClaimedAmount(address,string,address)",
                &[
                    Abi::Address(creator),
                    Abi::Str(&p.id),
                    Abi::Address(evm::parse_address(me)?),
                ],
            )
            .await?;
        let amount = format_amount(amount, t.decimals);
        info!("claimed {amount} {} from {:?} {}", t.symbol, p.kind, p.id);
        {
            let mut conn = self.db();
            let tx = conn.transaction()?;
            let target = target(&tx, chat_id)?;
            let body = match p.kind {
                PacketKind::RedPacket => Body::RedpacketClaim {
                    packet: p.id.clone(),
                    claimer: me.into(),
                    amount: amount.clone(),
                    tx: hash,
                },
                PacketKind::Transfer => Body::TransferClaim {
                    transfer: p.id.clone(),
                    tx: hash,
                },
            };
            let env = self.envelope(&tx, body)?;
            self.fan_out(&tx, chat_id, target.grp, env)?;
            if p.kind == PacketKind::RedPacket {
                let owner = tx.query_row(
                    "SELECT contact_id FROM messages WHERE id=?",
                    [msg_id],
                    |r| r.get(0),
                )?;
                self.add_system(
                    &tx,
                    chat_id,
                    None,
                    SystemMessage::PacketClaimed {
                        claimer: None,
                        owner,
                        packet_msg_id: Some(msg_id),
                    },
                )?;
            }
            tx.commit()?;
        }
        self.emit(vec![
            Event::MessagesChanged { chat_id },
            Event::ChatListChanged,
        ]);
        self.refresh_packet(msg_id).await?;
        Ok(amount)
    }

    async fn refund(&self, msg_id: u32) -> Result<String> {
        let _guard = self.tx_lock.lock().await;
        let (_, p, s) = self.refresh_packet(msg_id).await?;
        ensure!(s.can_refund, "not-refundable");
        let rpc = Rpc::new(&self.http(), p.chain)?;
        let call = packet_abi::refund(
            evm::parse_address(&p.contract)?,
            &p.id,
            evm::parse_address(self.keys.id.address())?,
        );
        let hash = rpc.execute(&self.keys.id, &call, TX_WAIT).await?;
        info!("refunded {:?} {}", p.kind, p.id);
        self.refresh_packet(msg_id).await?;
        Ok(hash)
    }

    pub(crate) fn receive_claim(
        &self,
        conn: &Connection,
        chat_id: u32,
        contact_id: u32,
        body: &Body,
        events: &mut Vec<Event>,
    ) -> Result<Option<ClaimNotice>> {
        let (id, red) = match body {
            Body::RedpacketClaim { packet, .. } => (packet, true),
            Body::TransferClaim { transfer, .. } => (transfer, false),
            _ => bail!("not a claim"),
        };
        let row: Option<(u32, Option<u32>)> = conn
            .query_row(
                "SELECT id, contact_id FROM messages WHERE chat_id=? AND json_extract(packet, '$.id')=?",
                params![chat_id, id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let Some((msg_id, owner)) = row else {
            debug!("claim for unknown packet {id}");
            return Ok(None);
        };
        events.push(Event::MessagesChanged { chat_id });
        Ok(red.then_some(ClaimNotice {
            chat_id,
            contact_id,
            msg_id,
            owner,
        }))
    }

    pub(crate) async fn confirm_claims(&self, claims: Vec<ClaimNotice>) -> Vec<Event> {
        let mut events = Vec::new();
        for c in claims {
            let r = async {
                let (_, p) = self.load_packet(c.msg_id)?;
                let rpc = Rpc::new(&self.http(), p.chain)?;
                self.confirm_claim(&rpc, &p, &c).await
            }
            .await;
            match r {
                Ok(e) => events.extend(e),
                Err(e) => warn!("cannot verify claim of message {}: {e:#}", c.msg_id),
            }
        }
        events
    }

    pub(crate) async fn confirm_claim(
        &self,
        rpc: &Rpc,
        p: &Stored,
        c: &ClaimNotice,
    ) -> Result<Vec<Event>> {
        let records = packet_abi::decode_records(
            &rpc.call(
                evm::parse_address(&p.contract)?,
                "getClaimRecords(address,string)",
                &[Abi::Address(evm::parse_address(&p.creator)?), Abi::Str(&p.id)],
            )
            .await?,
        )?;
        let conn = self.db();
        let account: String = conn.query_row(
            "SELECT account FROM contacts WHERE id=?",
            [c.contact_id],
            |r| r.get(0),
        )?;
        if !records
            .iter()
            .any(|(a, _)| account.starts_with(&format!("key:{}@", lower(a))))
        {
            info!(
                "ignoring unconfirmed claim of {} by contact {}",
                p.id, c.contact_id
            );
            return Ok(Vec::new());
        }
        self.add_system(
            &conn,
            c.chat_id,
            Some(c.contact_id),
            SystemMessage::PacketClaimed {
                claimer: Some(c.contact_id),
                owner: c.owner,
                packet_msg_id: Some(c.msg_id),
            },
        )?;
        Ok(vec![
            Event::MessagesChanged { chat_id: c.chat_id },
            Event::ChatListChanged,
        ])
    }
}

pub(crate) struct ClaimNotice {
    chat_id: u32,
    contact_id: u32,
    msg_id: u32,
    owner: Option<u32>,
}

/// The card must match the chain exactly: creator, token, count, gross amount (net + fee), the claim key in
/// the message, and for a transfer the bound receiver (me, or for the sender the contact it was sent to).
fn matches_message(info: &OnChain, p: &Stored, t: &TokenCfg, me: &str) -> Result<bool> {
    let expected_recipient = match p.kind {
        PacketKind::RedPacket => None,
        PacketKind::Transfer if p.mine(me) => p.recipient.clone(),
        PacketKind::Transfer => Some(me.to_lowercase()),
    };
    let recipient = (info.recipient != [0; 20]).then(|| lower(&info.recipient));
    let claim_key = claim_identity(&p.claim_key)?.address().to_lowercase();
    Ok(info.creator != [0; 20]
        && lower(&info.creator) == p.creator
        && info.token == t.address_bytes()
        && info.total_count == u128::from(p.count)
        && info.total_amount.checked_add(info.fee) == parse_amount(&p.amount, t.decimals)
        && lower(&info.claim_key) == claim_key
        && (p.kind == PacketKind::RedPacket || recipient.is_some())
        && recipient.map(|r| r.to_lowercase()) == expected_recipient.map(|r| r.to_lowercase()))
}

#[uniffi::export]
impl Account {
    pub async fn estimate_packet(
        self: Arc<Self>,
        token_id: String,
        amount: String,
        count: u32,
    ) -> Result<SendEstimate, CoreError> {
        crate::run(async move {
            let t = chain::token(&token_id)?;
            Ok(self.plan_packet(t, &amount, count).await?.0)
        })
        .await
    }

    #[uniffi::method(default(confirmed_fee = None))]
    pub async fn send_red_packet(
        self: Arc<Self>,
        chat_id: u32,
        token_id: String,
        amount: String,
        count: u32,
        greeting: String,
        confirmed_fee: Option<String>,
    ) -> Result<u32, CoreError> {
        crate::run(async move {
            self.send_packet(
                PacketKind::RedPacket,
                chat_id,
                &token_id,
                &amount,
                count,
                greeting,
                confirmed_fee.as_deref(),
            )
            .await
        })
        .await
    }

    #[uniffi::method(default(confirmed_fee = None))]
    pub async fn send_transfer(
        self: Arc<Self>,
        chat_id: u32,
        token_id: String,
        amount: String,
        note: String,
        confirmed_fee: Option<String>,
    ) -> Result<u32, CoreError> {
        crate::run(async move {
            self.send_direct(chat_id, &token_id, &amount, note, confirmed_fee.as_deref())
                .await
        })
        .await
    }

    /// Fee and limits for a direct transfer to this one-to-one chat's contact.
    pub async fn estimate_transfer(
        self: Arc<Self>,
        chat_id: u32,
        token_id: String,
        amount: String,
    ) -> Result<SendEstimate, CoreError> {
        crate::run(async move {
            let t = chain::token(&token_id)?;
            let to = self.transfer_recipient(chat_id)?;
            Ok(self.plan_send(t, &to, &amount).await?.0)
        })
        .await
    }

    pub async fn packet_status(self: Arc<Self>, msg_id: u32) -> Result<PacketStatus, CoreError> {
        crate::run(async move { Ok(self.refresh_packet(msg_id).await?.2) }).await
    }

    pub async fn claim_packet(self: Arc<Self>, msg_id: u32) -> Result<String, CoreError> {
        crate::run(async move { self.claim(msg_id).await }).await
    }

    pub async fn refund_packet(self: Arc<Self>, msg_id: u32) -> Result<String, CoreError> {
        crate::run(async move { self.refund(msg_id).await }).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stored(kind: PacketKind, creator: &str, recipient: Option<&str>) -> Stored {
        Stored {
            kind,
            chain: 56,
            contract: chain::TEST_PACKETS.into(),
            id: "ID".into(),
            token: None,
            symbol: "BNB".into(),
            amount: "1".into(),
            count: 1,
            text: String::new(),
            tx: String::new(),
            creator: creator.into(),
            claim_key: format!("0x{}", "11".repeat(32)),
            recipient: recipient.map(str::to_string),
            direct: false,
            status: initial(PacketState::Open, 1, true),
        }
    }

    fn info(p: &Stored, recipient: [u8; 20]) -> OnChain {
        OnChain {
            creator: evm::parse_address(&p.creator).unwrap(),
            token: [0; 20],
            claim_key: evm::parse_address(claim_identity(&p.claim_key).unwrap().address()).unwrap(),
            recipient,
            total_amount: 980_000_000_000_000_000,
            remain_amount: 980_000_000_000_000_000,
            fee: 20_000_000_000_000_000,
            total_count: 1,
            remain_count: 1,
            create_time: 1,
            refunded: false,
        }
    }

    #[test]
    fn card_must_match_chain() {
        let t = chain::token("56:BNB").unwrap();
        let alice = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let bob = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        let carol = "0xcccccccccccccccccccccccccccccccccccccccc";
        let (b, c) = ([0xbb; 20], [0xcc; 20]);

        // red packet: gross = net + fee, no recipient
        let red = stored(PacketKind::RedPacket, alice, None);
        assert!(matches_message(&info(&red, [0; 20]), &red, t, bob).unwrap());
        assert!(!matches_message(&info(&red, b), &red, t, bob).unwrap());
        let mut i = info(&red, [0; 20]);
        i.fee = 0;
        assert!(!matches_message(&i, &red, t, bob).unwrap(), "gross must include the fee");
        let mut i = info(&red, [0; 20]);
        i.claim_key = [0x99; 20];
        assert!(!matches_message(&i, &red, t, bob).unwrap(), "claim key must match the message");
        let mut i = info(&red, [0; 20]);
        i.creator = [0; 20];
        assert!(!matches_message(&i, &red, t, bob).unwrap(), "unknown packet");

        // transfer as the receiver: bound to me
        let tr = stored(PacketKind::Transfer, alice, None);
        assert!(matches_message(&info(&tr, b), &tr, t, bob).unwrap());
        assert!(!matches_message(&info(&tr, c), &tr, t, bob).unwrap());
        assert!(!matches_message(&info(&tr, [0; 20]), &tr, t, bob).unwrap(), "unbound transfer");
        // transfer as the sender: bound to the contact it was sent to
        let mine = stored(PacketKind::Transfer, alice, Some(carol));
        assert!(matches_message(&info(&mine, c), &mine, t, alice).unwrap());
        assert!(!matches_message(&info(&mine, b), &mine, t, alice).unwrap());
    }

    #[test]
    fn claim_keys() {
        let k = new_claim_key().unwrap();
        assert_eq!(k.len(), 66);
        assert!(claim_identity(&k).is_ok());
        assert_ne!(k, new_claim_key().unwrap());
        assert!(claim_identity("0x1234").is_err());
        assert!(claim_identity(&format!("0x{}", "00".repeat(32))).is_err());
        assert_eq!(
            account_address("key:0xAbCdEFabcdefABCDEFabcdefabcdefABCDEFabcd@relay1.taperelay.com:4246").as_deref(),
            Some("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
        );
    }

    fn direct(token: Option<&str>, symbol: &str, amount: &str) -> Stored {
        Stored {
            kind: PacketKind::Transfer,
            chain: 56,
            contract: String::new(),
            id: format!("0x{}", "ab".repeat(32)),
            token: token.map(str::to_string),
            symbol: symbol.into(),
            amount: amount.into(),
            count: 1,
            text: "hi".into(),
            tx: format!("0x{}", "ab".repeat(32)),
            creator: "0x1111111111111111111111111111111111111111".into(),
            claim_key: String::new(),
            recipient: Some("0x2222222222222222222222222222222222222222".into()),
            direct: true,
            status: initial(PacketState::Creating, 1, false),
        }
    }

    #[test]
    fn direct_native_payment() {
        let p = direct(None, "BNB", "0.5");
        let t = p.token_cfg().unwrap();
        let receipt = json!({"status": "0x1", "transactionHash": p.tx, "from": p.creator, "logs": []});
        let tx = json!({"hash": p.tx, "from": p.creator, "to": p.recipient, "value": "0x6f05b59d3b20000"});
        assert!(direct_paid(&p, t, &receipt, Some(&tx)));
        let mut bad = tx.clone();
        bad["value"] = json!("0x6f05b59d3b1ffff");
        assert!(!direct_paid(&p, t, &receipt, Some(&bad)), "amount must match exactly");
        let mut bad = tx.clone();
        bad["to"] = json!("0x3333333333333333333333333333333333333333");
        assert!(!direct_paid(&p, t, &receipt, Some(&bad)), "must go to the receiver");
        let mut bad = tx.clone();
        bad["from"] = json!("0x3333333333333333333333333333333333333333");
        assert!(!direct_paid(&p, t, &receipt, Some(&bad)), "must come from the sender");
        let mut failed = receipt.clone();
        failed["status"] = json!("0x0");
        assert!(!direct_paid(&p, t, &failed, Some(&tx)), "reverted transaction");
        let mut other = receipt.clone();
        other["transactionHash"] = json!(format!("0x{}", "cd".repeat(32)));
        assert!(!direct_paid(&p, t, &other, Some(&tx)), "receipt of another transaction");
        assert!(!direct_paid(&p, t, &receipt, None));
    }

    #[test]
    fn direct_token_payment() {
        let usdt = chain::TOKENS.iter().find(|t| t.chain == 56 && t.symbol == "USDT").unwrap();
        let addr = usdt.address.unwrap();
        let p = direct(Some(addr), "USDT", "2");
        let t = p.token_cfg().unwrap();
        let value = parse_amount("2", t.decimals).unwrap();
        let log = |token: &str, from: &str, to: &str, v: u128| json!({
            "address": token,
            "topics": [ERC20_TRANSFER_TOPIC, topic_address(from), topic_address(to)],
            "data": format!("0x{:064x}", v),
        });
        let rec = |logs: Value| json!({"status": "0x1", "transactionHash": p.tx, "from": p.creator, "to": addr, "logs": logs});
        let me = p.recipient.clone().unwrap();
        assert!(direct_paid(&p, t, &rec(json!([log(addr, &p.creator, &me, value)])), None));
        assert!(!direct_paid(&p, t, &rec(json!([log(addr, &p.creator, &me, value - 1)])), None), "amount");
        assert!(!direct_paid(&p, t, &rec(json!([log(addr, &p.creator, "0x3333333333333333333333333333333333333333", value)])), None), "receiver");
        assert!(!direct_paid(&p, t, &rec(json!([log("0x4444444444444444444444444444444444444444", &p.creator, &me, value)])), None), "other token");
        assert!(!direct_paid(&p, t, &rec(json!([])), None), "no transfer at all");
    }

    #[test]
    fn direct_card_round_trip() {
        let p = direct(None, "BNB", "0.5");
        let body = p.body().unwrap();
        assert!(matches!(body, Body::TransferDirect { .. }));
        let from = format!("key:{}@relay1.taperelay.com:4246", p.creator);
        let got = Stored::from_body(&body, &from).unwrap();
        assert!(got.direct && got.kind == PacketKind::Transfer);
        assert_eq!((got.tx.as_str(), got.id.as_str()), (p.tx.as_str(), p.tx.as_str()));
        assert_eq!(got.recipient, p.recipient);
        assert!(!got.status.can_claim);
        assert!(
            Stored::from_body(&body, "key:0x3333333333333333333333333333333333333333@relay1.taperelay.com:4246").is_err(),
            "only the payer can send the card"
        );
        let mut bad = body.clone();
        if let Body::TransferDirect { tx, .. } = &mut bad {
            *tx = "0x1234".into();
        }
        assert!(Stored::from_body(&bad, &from).is_err(), "bad tx hash");
        // wire name old clients ignore
        let v = serde_json::to_value(&body).unwrap();
        assert_eq!(v["type"], "transfer_direct");
    }

    /// Real payments from mainnet must pass `direct_paid` exactly as the RPC returns them.
    #[tokio::test]
    #[ignore = "reads BSC and X Layer mainnet"]
    async fn direct_paid_on_real_payments() {
        let http = reqwest::Client::new();
        // USDT on BSC: a plain transfer(to, amount) call in a recent block
        let rpc = Rpc::new(&http, 56).unwrap();
        let head = quantity(&rpc.request("eth_blockNumber", json!([])).await.unwrap()).unwrap();
        let usdt = chain::TOKENS.iter().find(|t| t.chain == 56 && t.symbol == "USDT").unwrap();
        let ua = usdt.address.unwrap().to_lowercase();
        let mut checked = 0;
        'bsc: for n in (head - 20..head - 2).rev() {
            let b = rpc.request("eth_getBlockByNumber", json!([format!("0x{n:x}"), true])).await.unwrap();
            for tx in b["transactions"].as_array().unwrap() {
                let input = tx["input"].as_str().unwrap_or("");
                if !tx["to"].as_str().is_some_and(|t| t.eq_ignore_ascii_case(&ua)) || !input.starts_with("0xa9059cbb") || input.len() != 138 {
                    continue;
                }
                let hash = tx["hash"].as_str().unwrap();
                let receipt = rpc.receipt(hash).await.unwrap().unwrap();
                if receipt["status"] != "0x1" {
                    continue;
                }
                let to = format!("0x{}", &input[34..74]);
                let v = u128::from_str_radix(&input[74 + 32..], 16).unwrap();
                let mut p = direct(usdt.address, "USDT", &format_amount(v, usdt.decimals));
                p.chain = 56;
                p.tx = hash.to_lowercase();
                p.creator = tx["from"].as_str().unwrap().to_lowercase();
                p.recipient = Some(to);
                let t = p.token_cfg().unwrap();
                assert!(direct_paid(&p, t, &receipt, None), "real USDT transfer {hash}");
                p.amount = format_amount(v + 1, usdt.decimals);
                assert!(!direct_paid(&p, t, &receipt, None));
                checked += 1;
                break 'bsc;
            }
        }
        assert_eq!(checked, 1, "no plain USDT transfer found");

        // OKB on X Layer: a plain value transfer in a recent block
        let rpc = Rpc::new(&http, 196).unwrap();
        let head = quantity(&rpc.request("eth_blockNumber", json!([])).await.unwrap()).unwrap();
        let mut found = false;
        'blocks: for n in (head - 200..head - 2).rev() {
            let b = rpc.request("eth_getBlockByNumber", json!([format!("0x{n:x}"), true])).await.unwrap();
            for tx in b["transactions"].as_array().unwrap() {
                if tx["input"] != "0x" || tx["value"] == "0x0" || tx["to"].is_null() {
                    continue;
                }
                let hash = tx["hash"].as_str().unwrap();
                let receipt = rpc.receipt(hash).await.unwrap().unwrap();
                if receipt["status"] != "0x1" {
                    continue;
                }
                let okb = chain::token_by_address(196, None).unwrap();
                let v = quantity(&tx["value"]).unwrap();
                let mut p = direct(None, "OKB", &format_amount(v, okb.decimals));
                p.chain = 196;
                p.tx = hash.to_lowercase();
                p.creator = tx["from"].as_str().unwrap().to_lowercase();
                p.recipient = Some(tx["to"].as_str().unwrap().to_lowercase());
                let fetched = rpc.request("eth_getTransactionByHash", json!([hash])).await.unwrap();
                let t = p.token_cfg().unwrap();
                assert!(direct_paid(&p, t, &receipt, Some(&fetched)), "real OKB transfer {hash}");
                p.recipient = Some("0x2222222222222222222222222222222222222222".into());
                assert!(!direct_paid(&p, t, &receipt, Some(&fetched)));
                found = true;
                break 'blocks;
            }
        }
        assert!(found, "no plain OKB transfer in the last 200 blocks");
    }
}
