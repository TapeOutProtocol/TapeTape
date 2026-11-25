use std::sync::Arc;

use anyhow::{bail, Context, Result};
use log::{info, warn};
use rusqlite::Connection;
use taperelay::evm;

use crate::account::Account;
use crate::api::*;
use crate::chain::{self, format_amount, parse_amount, Call, Rpc, TokenCfg, TOKENS};
use crate::packet_abi;
use crate::protocol::now_ms;
use crate::store;
use crate::Core;

const PENDING_KEY: &str = "wallet_pending";
const PENDING_DROP_MS: i64 = 10 * 60 * 1000;

fn native_decimals(chain: u64) -> u8 {
    TOKENS
        .iter()
        .find(|n| n.chain == chain && n.address.is_none())
        .map_or(18, |n| n.decimals)
}

pub(crate) fn check_fee(t: &TokenCfg, est: &SendEstimate, confirmed: Option<&str>) -> Result<()> {
    let Some(confirmed) = confirmed else {
        return Ok(());
    };
    let d = native_decimals(t.chain);
    let confirmed = parse_amount(confirmed, d).context("invalid-fee")?;
    let fresh = parse_amount(&est.fee, d).context("invalid-fee")?;
    if fresh.saturating_mul(2) > confirmed.saturating_mul(3) {
        warn!("fee rose from {confirmed} to {fresh} on {}", t.chain().name);
        bail!("fee-changed");
    }
    Ok(())
}

pub(crate) fn quote(
    t: &TokenCfg,
    value: Option<u128>,
    balance: u128,
    native: u128,
    fee: u128,
    error: Option<&str>,
) -> SendEstimate {
    let native_token = t.address.is_none();
    let max = if native_token {
        balance.saturating_sub(fee)
    } else {
        balance
    };
    let error = error.or(match value {
        None => Some("invalid-amount"),
        Some(v) if v > max => Some("insufficient-balance"),
        Some(_) if native < fee => Some("insufficient-gas"),
        Some(_) => None,
    });
    SendEstimate {
        amount: format_amount(value.unwrap_or(0), t.decimals),
        fee: format_amount(fee, native_decimals(t.chain)),
        fee_symbol: t.chain().native.into(),
        balance: format_amount(balance, t.decimals),
        max: format_amount(max, t.decimals),
        error: error.map(str::to_string),
    }
}

impl Account {
    pub(crate) async fn plan_send(
        &self,
        t: &TokenCfg,
        to: &str,
        amount: &str,
    ) -> Result<(SendEstimate, Call, u128, u128)> {
        let rpc = Rpc::new(&self.http(), t.chain)?;
        let me = self.keys.id.address();
        let dest = evm::parse_address(to.trim())
            .ok()
            .filter(|_| to.trim().starts_with("0x"));
        let value = parse_amount(amount, t.decimals).filter(|v| *v > 0);
        let (balance, native, price) =
            tokio::try_join!(rpc.balance(t, me), rpc.native_balance(me), rpc.gas_price())?;
        let to_bytes = dest.unwrap_or(evm::parse_address(me)?);
        let probe = match t.address {
            None => Call {
                to: to_bytes,
                value: 0,
                data: Vec::new(),
            },
            Some(_) => packet_abi::erc20_transfer(
                t.address_bytes(),
                to_bytes,
                value.unwrap_or(0).min(balance),
            ),
        };
        let gas = rpc.estimate(me, &probe).await?;
        let est = quote(
            t,
            value,
            balance,
            if t.address.is_none() { balance } else { native },
            gas.checked_mul(price).context("invalid-fee")?,
            dest.is_none().then_some("invalid-address"),
        );
        let v = value.unwrap_or(0);
        let call = match t.address {
            None => Call {
                to: to_bytes,
                value: v,
                data: Vec::new(),
            },
            Some(_) => packet_abi::erc20_transfer(t.address_bytes(), to_bytes, v),
        };
        Ok((est, call, gas, price))
    }

    fn pending(conn: &Connection) -> Result<Vec<WalletTx>> {
        Ok(store::get_config(conn, PENDING_KEY)?
            .map(|j| serde_json::from_str(&j))
            .transpose()?
            .unwrap_or_default())
    }

    pub(crate) fn update_pending(&self, f: impl FnOnce(&mut Vec<WalletTx>)) -> Result<()> {
        let conn = self.db();
        let mut list = Self::pending(&conn)?;
        let before = list.len();
        f(&mut list);
        if list.len() != before {
            store::set_config(&conn, PENDING_KEY, &serde_json::to_string(&list)?)?;
        }
        Ok(())
    }
}

#[uniffi::export]
impl Core {
    pub fn wallet_tokens(&self) -> Vec<Token> {
        TOKENS.iter().map(TokenCfg::record).collect()
    }
}

#[uniffi::export]
impl Account {
    pub fn wallet_address(&self) -> String {
        self.keys.id.address().to_string()
    }

    pub async fn balances(self: Arc<Self>) -> Vec<Balance> {
        crate::run(async move {
            let me = self.keys.id.address().to_string();
            let mut set = tokio::task::JoinSet::new();
            for (i, t) in TOKENS.iter().enumerate() {
                let http = self.http();
                let me = me.clone();
                set.spawn(async move {
                    let r = async { Rpc::new(&http, t.chain)?.balance(t, &me).await }.await;
                    if let Err(e) = &r {
                        warn!("balance of {} failed: {e:#}", t.id());
                    }
                    (i, r.ok().map(|v| format_amount(v, t.decimals)))
                });
            }
            let mut out: Vec<Balance> = TOKENS
                .iter()
                .map(|t| Balance {
                    token_id: t.id(),
                    amount: None,
                })
                .collect();
            while let Some(Ok((i, amount))) = set.join_next().await {
                out[i].amount = amount;
            }
            Ok(out)
        })
        .await
        .unwrap_or_default()
    }

    pub async fn estimate_send(
        self: Arc<Self>,
        token_id: String,
        to: String,
        amount: String,
    ) -> Result<SendEstimate, CoreError> {
        crate::run(async move {
            let t = chain::token(&token_id)?;
            Ok(self.plan_send(t, &to, &amount).await?.0)
        })
        .await
    }

    #[uniffi::method(default(confirmed_fee = None))]
    pub async fn send_token(
        self: Arc<Self>,
        token_id: String,
        to: String,
        amount: String,
        confirmed_fee: Option<String>,
    ) -> Result<String, CoreError> {
        crate::run(async move {
            let t = chain::token(&token_id)?;
            let _guard = self.tx_lock.lock().await;
            let (est, call, gas, price) = self.plan_send(t, &to, &amount).await?;
            if let Some(e) = est.error {
                bail!("{e}");
            }
            check_fee(t, &est, confirmed_fee.as_deref())?;
            let rpc = Rpc::new(&self.http(), t.chain)?;
            let tx = rpc.sign(&self.keys.id, &call, gas, price).await?;
            let record = WalletTx {
                chain_id: t.chain,
                token_id: t.id(),
                to: to.trim().into(),
                amount: est.amount.clone(),
                tx_hash: tx.hash.clone(),
                created_at: now_ms(),
                dapp: false,
            };
            self.update_pending(|l| l.push(record))?;
            if let Err(e) = rpc.broadcast(&tx).await {
                // 说不清有没有发出去：记录留着（钱包里能看到这笔），只有确定没发出去才删
                if e.to_string() != chain::MAYBE_SENT {
                    self.update_pending(|l| l.retain(|r| r.tx_hash != tx.hash))?;
                }
                return Err(e);
            }
            info!("sent {} {} on {}", est.amount, t.symbol, t.chain().name);
            Ok(tx.hash)
        })
        .await
    }

    pub fn pending_sends(&self) -> Result<Vec<WalletTx>, CoreError> {
        Ok(Self::pending(&self.db())?)
    }

    pub async fn tx_status(
        self: Arc<Self>,
        chain_id: u64,
        tx_hash: String,
    ) -> Result<TxStatus, CoreError> {
        crate::run(async move {
            let s = Rpc::new(&self.http(), chain_id)?.status(&tx_hash).await?;
            let stale = now_ms() - PENDING_DROP_MS;
            let keep = |r: &WalletTx| {
                r.tx_hash != tx_hash
                    || s == TxStatus::Pending
                    || (s == TxStatus::NotFound && r.created_at > stale)
            };
            self.update_pending(|l| l.retain(keep))?;
            Ok(s)
        })
        .await
    }
}
