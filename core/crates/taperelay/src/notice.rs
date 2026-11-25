use anyhow::{bail, ensure, Context, Result};
use serde::Serialize;
use serde_json::Value;

use crate::eip712::{lower_hex, recover, self_key_digest, self_notice_digest, MAX_SAFE_INTEGER};
use crate::identity::{is_self_account, self_key_of, EncKeys, Identity};
use crate::inline::{inline_body_ok, open_inline, seal_inline, Role};
use crate::sealedbox::check_public_key;

#[derive(Debug, Clone)]
pub struct SignedNotice {
    pub id: String,
    pub json: String,
}

#[derive(Debug, Clone)]
pub struct VerifiedNotice {
    pub id: String,
    pub from: String,
    pub to: String,
    pub body: String,
    pub created_at: u64,
}

#[derive(Debug, Clone)]
pub struct OpenedNotice {
    pub id: String,
    pub from: String,
    pub to: String,
    pub created_at: u64,
    pub message: Vec<u8>,
}

#[derive(Serialize)]
struct NoticeJson<'a> {
    from: &'a str,
    to: &'a str,
    body: &'a str,
    #[serde(rename = "createdAt")]
    created_at: u64,
}

#[derive(Serialize)]
struct SignedNoticeJson<'a> {
    notice: NoticeJson<'a>,
    signature: &'a str,
}

#[derive(Serialize)]
struct KeyRecordJson<'a> {
    account: &'a str,
    key: &'a str,
    #[serde(rename = "createdAt")]
    created_at: u64,
}

#[derive(Serialize)]
struct KeyJson<'a> {
    record: KeyRecordJson<'a>,
    signature: &'a str,
}

fn empty(v: &&[String]) -> bool {
    v.is_empty()
}

#[derive(Serialize)]
struct SendersJson<'a> {
    #[serde(skip_serializing_if = "empty")]
    allow: &'a [String],
    #[serde(skip_serializing_if = "empty")]
    deny: &'a [String],
    #[serde(skip_serializing_if = "empty")]
    block: &'a [String],
    #[serde(skip_serializing_if = "empty")]
    unblock: &'a [String],
}

fn to_json(v: &impl Serialize) -> String {
    serde_json::to_string(v).expect("json of plain strings")
}

pub fn sign_self_notice(id: &Identity, to: &str, body: &str, created_at: u64) -> SignedNotice {
    let from = id.account();
    let digest = self_notice_digest(from, to, body, created_at);
    let signature = id.sign_digest(&digest);
    let json = to_json(&SignedNoticeJson {
        notice: NoticeJson {
            from,
            to,
            body,
            created_at,
        },
        signature: &signature,
    });
    SignedNotice {
        id: hex::encode(digest),
        json,
    }
}

pub fn make_self_notice(
    id: &Identity,
    to: &str,
    message: &[u8],
    recipient_key: &[u8; 32],
    own: &EncKeys,
    created_at: u64,
) -> Result<SignedNotice> {
    ensure!(is_self_account(to), "bad-address: {to}");
    let body = seal_inline(message, recipient_key, &own.public(), None)?;
    Ok(sign_self_notice(id, to, &body, created_at))
}

pub fn verify_self_notice(item: &Value, me: &str, role: Role) -> Result<VerifiedNotice> {
    let notice = item.get("notice").and_then(Value::as_object);
    let signature = item.get("signature").and_then(Value::as_str);
    let (Some(n), Some(signature)) = (notice, signature) else {
        bail!("bad-item");
    };
    ensure!(
        n.len() == 4
            && ["from", "to", "body", "createdAt"]
                .iter()
                .all(|k| n.contains_key(*k)),
        "bad-notice"
    );
    let text = |k: &str| n.get(k).and_then(Value::as_str);
    let (Some(from), Some(to)) = (text("from"), text("to")) else {
        bail!("bad-address");
    };
    ensure!(is_self_account(from) && is_self_account(to), "bad-address");
    let mine = match role {
        Role::Recipient => to,
        Role::Sender => from,
    };
    ensure!(mine == me, "not-mine");
    let body = text("body")
        .filter(|b| inline_body_ok(b))
        .context("not-sealed")?;
    let created_at = n
        .get("createdAt")
        .and_then(Value::as_u64)
        .filter(|t| *t <= MAX_SAFE_INTEGER)
        .context("bad-notice")?;
    let digest = self_notice_digest(from, to, body, created_at);
    let id = hex::encode(digest);
    if let Some(claimed) = item.get("id").and_then(Value::as_str) {
        ensure!(claimed == id, "bad-id");
    }
    ensure!(
        recover(&digest, signature).as_deref() == self_key_of(from),
        "bad-signature"
    );
    Ok(VerifiedNotice {
        id,
        from: from.to_string(),
        to: to.to_string(),
        body: body.to_string(),
        created_at,
    })
}

pub fn open_self_notice(item: &Value, keys: &EncKeys, me: &str) -> Result<OpenedNotice> {
    let v = verify_self_notice(item, me, Role::Recipient)?;
    let message = open_inline(&v.body, keys, Role::Recipient).context("cannot-open")?;
    Ok(OpenedNotice {
        id: v.id,
        from: v.from,
        to: v.to,
        created_at: v.created_at,
        message,
    })
}

pub fn publish_key_json(id: &Identity, public: &[u8; 32], now: u64) -> String {
    let digest = self_key_digest(id.account(), public, now);
    to_json(&KeyJson {
        record: KeyRecordJson {
            account: id.account(),
            key: &format!("0x{}", hex::encode(public)),
            created_at: now,
        },
        signature: &id.sign_digest(&digest),
    })
}

pub fn verify_key_record(account: &str, json: &Value) -> Result<[u8; 32]> {
    ensure!(is_self_account(account), "bad-address: {account}");
    let record = json.get("record").and_then(Value::as_object);
    let signature = json.get("signature").and_then(Value::as_str);
    let (Some(r), Some(signature)) = (record, signature) else {
        bail!("bad-key-record");
    };
    ensure!(
        r.len() == 3 && r.get("account").and_then(Value::as_str) == Some(account),
        "bad-key-record"
    );
    let key = r
        .get("key")
        .and_then(Value::as_str)
        .and_then(lower_hex::<32>)
        .context("bad-key-record")?;
    let created_at = r
        .get("createdAt")
        .and_then(Value::as_u64)
        .filter(|t| *t <= MAX_SAFE_INTEGER)
        .context("bad-key-record")?;
    let signer = recover(&self_key_digest(account, &key, created_at), signature);
    ensure!(signer.as_deref() == self_key_of(account), "bad-key-record");
    check_public_key(&key).context("bad-key-record")?;
    Ok(key)
}

pub fn senders_json(
    allow: &[String],
    deny: &[String],
    block: &[String],
    unblock: &[String],
) -> String {
    to_json(&SendersJson {
        allow,
        deny,
        block,
        unblock,
    })
}
