pub(crate) mod keys;

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, info, warn};
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::Value;
use taperelay::OpenedNotice;
use tokio::sync::Notify;
use tokio::task::JoinHandle;

use crate::api::*;
use crate::chat;
use crate::protocol::{self, Body, Envelope, Frame};
use crate::store;
use crate::transport::{self, Relay};
use keys::Keys;

type QueueRow = (i64, String, Option<u32>, Vec<u8>, usize);

const KEY_TTL_MS: i64 = 24 * 3600 * 1000;
const SENT_TTL_MS: i64 = 72 * 3600 * 1000;
const PARTS_TTL_MS: i64 = 24 * 3600 * 1000;

#[derive(uniffi::Object)]
pub struct Account {
    pub(crate) id: u32,
    pub(crate) dir: PathBuf,
    db: Mutex<Connection>,
    pub(crate) keys: Keys,
    node: String,
    pub(crate) net: Arc<crate::net::Net>,
    relay: Relay,
    listener: Arc<dyn EventListener>,
    wake: Notify,
    expiry: Notify,
    pub(crate) transfers: Notify,
    connectivity: Mutex<Connectivity>,
    tasks: Mutex<Vec<JoinHandle<()>>>,
    recv_lock: tokio::sync::Mutex<()>,
    pub(crate) tx_lock: tokio::sync::Mutex<()>,
}

impl Account {
    pub(crate) fn create(
        id: u32,
        dir: PathBuf,
        mnemonic: &str,
        node: &str,
        net: Arc<crate::net::Net>,
        listener: Arc<dyn EventListener>,
    ) -> Result<Arc<Account>> {
        std::fs::create_dir_all(dir.join("blobs"))?;
        let conn = store::open(&dir.join("db.sqlite"))?;
        store::set_config(&conn, "mnemonic", mnemonic)?;
        store::set_config(&conn, "node", node)?;
        store::set_config(&conn, "invite_token", &protocol::new_id())?;
        drop(conn);
        Self::open(id, dir, net, listener)
    }

    pub(crate) fn open(
        id: u32,
        dir: PathBuf,
        net: Arc<crate::net::Net>,
        listener: Arc<dyn EventListener>,
    ) -> Result<Arc<Account>> {
        let conn = store::open(&dir.join("db.sqlite"))?;
        let mnemonic = store::get_config(&conn, "mnemonic")?.context("no mnemonic")?;
        let node = store::get_config(&conn, "node")?.context("no node")?;
        let keys = Keys::new(&keys::secret_from_mnemonic(&mnemonic)?, &node)?;
        Ok(Arc::new(Account {
            id,
            dir,
            db: Mutex::new(conn),
            relay: Relay::new(net.clone(), keys.id.clone()),
            keys,
            node,
            net,
            listener,
            wake: Notify::new(),
            expiry: Notify::new(),
            transfers: Notify::new(),
            connectivity: Mutex::new(Connectivity::NotConnected),
            tasks: Mutex::new(Vec::new()),
            recv_lock: tokio::sync::Mutex::new(()),
            tx_lock: tokio::sync::Mutex::new(()),
        }))
    }

    pub(crate) fn http(&self) -> reqwest::Client {
        self.net.client()
    }

    pub(crate) fn restart(self: Arc<Self>) {
        if self.tasks.lock().unwrap().is_empty() {
            return;
        }
        self.stop();
        self.start();
    }

    pub(crate) fn db(&self) -> MutexGuard<'_, Connection> {
        self.db.lock().unwrap()
    }

    pub(crate) fn emit(&self, events: Vec<Event>) {
        let mut done: Vec<Event> = Vec::new();
        for e in events {
            if !done.contains(&e) {
                self.listener.on_event(self.id, e.clone());
                done.push(e);
            }
        }
    }

    pub(crate) fn my_name(&self, conn: &Connection) -> Result<String> {
        Ok(store::get_config(conn, "name")?.unwrap_or_default())
    }

    pub(crate) fn enqueue(
        &self,
        conn: &Connection,
        recipient: &str,
        msg_id: Option<u32>,
        env: &Envelope,
        blob: &[u8],
    ) -> Result<()> {
        let inner = protocol::encode_inner(env, blob);
        // Receivers drop envelopes over MAX_JSON; refuse to send them instead of failing silently.
        anyhow::ensure!(
            inner.len() - 4 - blob.len() <= protocol::MAX_JSON,
            "too-large"
        );
        conn.execute(
            "INSERT INTO send_queue (recipient, msg_id, inner, created) VALUES (?,?,?,?)",
            params![recipient, msg_id, inner, protocol::now_ms()],
        )?;
        self.wake.notify_one();
        Ok(())
    }

    pub(crate) fn wake_sender(&self) {
        self.wake.notify_one();
    }

    pub(crate) fn wake_transfers(&self) {
        self.transfers.notify_one();
    }

    pub(crate) fn wake_expiry(&self) {
        self.expiry.notify_one();
    }

    pub(crate) fn envelope(&self, conn: &Connection, body: Body) -> Result<Envelope> {
        Ok(Envelope::new(
            self.keys.account(),
            &self.my_name(conn)?,
            body,
        ))
    }

    fn set_connectivity(&self, c: Connectivity) {
        let changed = {
            let mut cur = self.connectivity.lock().unwrap();
            std::mem::replace(&mut *cur, c) != c
        };
        if changed {
            debug!("account {} connectivity {c:?}", self.id);
            self.emit(vec![Event::ConnectivityChanged]);
        }
    }

    async fn send_loop(self: Arc<Self>) {
        loop {
            let wait = match self.flush().await {
                Ok(true) => Duration::from_secs(3),
                Ok(false) => Duration::from_secs(300),
                Err(e) => {
                    warn!("send failed, will retry: {e:#}");
                    Duration::from_secs(15)
                }
            };
            tokio::select! {
                _ = self.wake.notified() => {}
                _ = tokio::time::sleep(wait) => {}
            }
        }
    }

    pub(crate) async fn flush(&self) -> Result<bool> {
        self.sync_allowed().await?;
        self.send_queue().await?;
        self.check_sent().await
    }

    async fn sync_allowed(&self) -> Result<()> {
        let rows: Vec<(String, bool, bool)> = {
            let conn = self.db();
            let mut stmt = conn
                .prepare("SELECT account, allowed, blocked FROM contacts WHERE allow_synced=0")?;
            let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
            rows.collect::<Result<_, _>>()?
        };
        if rows.is_empty() {
            return Ok(());
        }
        let pick = |f: &dyn Fn(bool, bool) -> bool| -> Vec<String> {
            rows.iter()
                .filter(|(_, a, b)| f(*a, *b))
                .map(|(acc, _, _)| acc.clone())
                .collect()
        };
        self.relay
            .senders(
                &pick(&|a, b| a && !b),
                &pick(&|a, b| !a && !b),
                &pick(&|_, b| b),
                &pick(&|_, b| !b),
            )
            .await?;
        let conn = self.db();
        for (account, allowed, blocked) in &rows {
            conn.execute(
                "UPDATE contacts SET allow_synced=1 WHERE account=? AND allowed=? AND blocked=?",
                params![account, allowed, blocked],
            )?;
        }
        Ok(())
    }

    pub(crate) async fn peer_key(&self, account: &str) -> Result<Option<[u8; 32]>> {
        let cached: Option<Vec<u8>> = self
            .db()
            .query_row(
                "SELECT key FROM peer_keys WHERE account=? AND fetched>?",
                params![account, protocol::now_ms() - KEY_TTL_MS],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(key) = cached.and_then(|k| k.try_into().ok()) {
            return Ok(Some(key));
        }
        let Some(key) = transport::fetch_key(&self.http(), account).await? else {
            return Ok(None);
        };
        self.db().execute(
            "INSERT OR REPLACE INTO peer_keys (account, key, fetched) VALUES (?,?,?)",
            params![account, key.to_vec(), protocol::now_ms()],
        )?;
        debug!("fetched key of {account}");
        Ok(Some(key))
    }

    fn reject(
        &self,
        row_id: i64,
        msg_id: Option<u32>,
        recipient: &str,
        reason: &str,
    ) -> Result<()> {
        let conn = self.db();
        conn.execute("DELETE FROM send_queue WHERE id=?", [row_id])?;
        let Some(msg_id) = msg_id else {
            warn!("control envelope to {recipient} rejected: {reason}");
            return Ok(());
        };
        warn!("message {msg_id} to {recipient} rejected: {reason}");
        let events = fail_delivery(&conn, msg_id, recipient, reason)?;
        drop(conn);
        self.emit(events);
        Ok(())
    }

    async fn send_queue(&self) -> Result<()> {
        loop {
            let row: Option<QueueRow> = self
                .db()
                .query_row(
                    "SELECT id, recipient, msg_id, inner, done FROM send_queue ORDER BY id LIMIT 1",
                    [],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
                )
                .optional()?;
            let Some((row_id, recipient, msg_id, inner, done)) = row else {
                return Ok(());
            };
            let Some(key) = self.peer_key(&recipient).await? else {
                self.reject(row_id, msg_id, &recipient, "recipient-no-key")?;
                continue;
            };
            let Ok(frames) = protocol::frames(&inner) else {
                self.reject(row_id, msg_id, &recipient, "too-large")?;
                continue;
            };
            let mut rejected = None;
            for (i, frame) in frames.iter().enumerate().skip(done) {
                let notice = taperelay::make_self_notice(
                    &self.keys.id,
                    &recipient,
                    frame,
                    &key,
                    &self.keys.enc,
                    (protocol::now_ms() / 1000) as u64,
                )?;
                let res = self.relay.submit(&notice.json).await?;
                match res.status {
                    200 | 202 => {
                        let conn = self.db();
                        if let Some(msg_id) = msg_id {
                            conn.execute(
                                "INSERT OR REPLACE INTO sent_notices (notice, msg_id, ts, recipient) VALUES (?,?,?,?)",
                                params![notice.id, msg_id, protocol::now_ms(), recipient],
                            )?;
                        }
                        conn.execute(
                            "UPDATE send_queue SET done=? WHERE id=?",
                            params![i + 1, row_id],
                        )?;
                    }
                    429 | 500.. => bail!("outbox HTTP {}", res.status),
                    s => {
                        rejected = Some(res.error.unwrap_or_else(|| format!("http-{s}")));
                        break;
                    }
                }
            }
            if let Some(reason) = rejected {
                self.reject(row_id, msg_id, &recipient, &reason)?;
                continue;
            }
            let conn = self.db();
            conn.execute("DELETE FROM send_queue WHERE id=?", [row_id])?;
            let Some(msg_id) = msg_id else {
                debug!("sent control envelope to {recipient}");
                continue;
            };
            info!(
                "sent message {msg_id} to {recipient} as {} notice(s)",
                frames.len()
            );
            let events = set_delivery(
                &conn,
                msg_id,
                &recipient,
                MessageState::Sent,
                MessageState::Pending,
            )?;
            drop(conn);
            self.emit(events);
        }
    }

    async fn check_sent(&self) -> Result<bool> {
        let now = protocol::now_ms();
        let rows: Vec<(String, u32, String)> = {
            let conn = self.db();
            conn.execute("DELETE FROM sent_notices WHERE ts<?", [now - SENT_TTL_MS])?;
            let mut stmt = conn.prepare(
                "SELECT notice, msg_id, recipient FROM sent_notices WHERE ts<=? ORDER BY ts LIMIT 50",
            )?;
            let rows = stmt.query_map([now - 1000], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
            rows.collect::<Result<_, _>>()?
        };
        for (notice, msg_id, recipient) in rows {
            let status = self.relay.outbox_status(&notice).await?;
            let conn = self.db();
            let events = match status {
                Some((s, _)) if s == "delivered" => {
                    conn.execute("DELETE FROM sent_notices WHERE notice=?", [&notice])?;
                    let left: i64 = conn.query_row(
                        "SELECT (SELECT COUNT(*) FROM sent_notices WHERE msg_id=?1 AND recipient=?2)
                              + (SELECT COUNT(*) FROM send_queue WHERE msg_id=?1 AND recipient=?2)",
                        params![msg_id, recipient],
                        |r| r.get(0),
                    )?;
                    if left > 0 {
                        continue;
                    }
                    info!("message {msg_id} delivered to {recipient}");
                    set_delivery(
                        &conn,
                        msg_id,
                        &recipient,
                        MessageState::Delivered,
                        MessageState::Sent,
                    )?
                }
                Some((s, error)) if s == "failed" => {
                    let reason = error.unwrap_or_else(|| "failed".into());
                    warn!("message {msg_id} to {recipient} failed: {reason}");
                    fail_delivery(&conn, msg_id, &recipient, &reason)?
                }
                Some((s, _)) => {
                    debug!("notice {notice} for message {msg_id}: {s}");
                    continue;
                }
                None => {
                    debug!("notice {notice} for message {msg_id} unknown to node");
                    conn.execute("DELETE FROM sent_notices WHERE notice=?", [&notice])?;
                    continue;
                }
            };
            drop(conn);
            self.emit(events);
        }
        let left: i64 = self
            .db()
            .query_row("SELECT COUNT(*) FROM sent_notices", [], |r| r.get(0))?;
        Ok(left > 0)
    }

    async fn recv_loop(self: Arc<Self>) {
        let mut seq = None;
        let mut backoff = Duration::from_secs(2);
        loop {
            self.set_connectivity(Connectivity::Connecting);
            match self.relay.subscribe(seq).await {
                Ok(mut events) => {
                    info!("account {} subscribed to inbox events", self.id);
                    self.set_connectivity(Connectivity::Connected);
                    backoff = Duration::from_secs(2);
                    if let Err(e) = self.pull().await {
                        warn!("inbox pull failed: {e:#}");
                    }
                    loop {
                        let evs = match events.next().await {
                            Ok(evs) => evs,
                            Err(e) => {
                                info!("account {} event stream ended: {e:#}", self.id);
                                break;
                            }
                        };
                        let mut items = Vec::new();
                        for ev in evs {
                            if let Some(s) = ev.seq() {
                                seq = Some(s);
                            }
                            if ev.data.is_empty() {
                                continue;
                            }
                            match serde_json::from_str::<Value>(&ev.data) {
                                Ok(item) => items.push(item),
                                Err(e) => warn!("bad event data: {e}"),
                            }
                        }
                        if let Err(e) = self.process(items).await {
                            warn!("processing pushed notices failed: {e:#}");
                        }
                    }
                }
                Err(e) => warn!("account {} subscribe failed: {e:#}", self.id),
            }
            self.set_connectivity(Connectivity::Connecting);
            tokio::time::sleep(backoff).await;
            backoff = (backoff * 2).min(Duration::from_secs(60));
        }
    }

    async fn expire_loop(self: Arc<Self>) {
        loop {
            if let Err(e) = self.delete_old() {
                warn!("deleting old messages failed: {e:#}");
            }
            let wait = match self.expire_now() {
                Ok(Some(next)) => (next - protocol::now_ms()).clamp(0, 60_000),
                Ok(None) => 60_000,
                Err(e) => {
                    warn!("expiring messages failed: {e:#}");
                    60_000
                }
            };
            tokio::select! {
                _ = self.expiry.notified() => {}
                _ = tokio::time::sleep(Duration::from_millis(wait as u64)) => {}
            }
        }
    }

    async fn poll_loop(self: Arc<Self>) {
        loop {
            if let Err(e) = self.pull().await {
                debug!("inbox pull failed: {e:#}");
            }
            let connected = *self.connectivity.lock().unwrap() == Connectivity::Connected;
            tokio::time::sleep(Duration::from_secs(if connected { 30 } else { 5 })).await;
        }
    }

    pub(crate) async fn pull(&self) -> Result<Vec<Event>> {
        let mut cursor: Option<String> = None;
        let mut events = Vec::new();
        loop {
            let page = self.relay.inbox_page(cursor.as_deref()).await?;
            let items: Vec<Value> = page["items"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|it| it["read"] != true)
                .cloned()
                .collect();
            events.extend(self.process(items).await?);
            match page["next"].as_str() {
                Some(next) if !next.is_empty() => cursor = Some(next.to_string()),
                _ => return Ok(events),
            }
        }
    }

    async fn process(&self, items: Vec<Value>) -> Result<Vec<Event>> {
        if items.is_empty() {
            return Ok(Vec::new());
        }
        let _guard = self.recv_lock.lock().await;
        let mut ids = Vec::new();
        let mut events = Vec::new();
        let mut claims = Vec::new();
        for item in &items {
            match taperelay::open_self_notice(item, &self.keys.enc, self.keys.account()) {
                Ok(n) => {
                    if let Err(e) = self.receive(&n, &mut events, &mut claims) {
                        warn!("dropping notice {} from {}: {e:#}", n.id, n.from);
                    }
                    ids.push(n.id);
                }
                Err(e) => {
                    warn!("dropping unreadable notice: {e:#}");
                    if let Some(id) = item["id"].as_str() {
                        ids.push(id.to_string());
                    }
                }
            }
        }
        self.emit(events.clone());
        self.relay.mark_read(&ids).await?;
        if !claims.is_empty() {
            let confirmed = self.confirm_claims(claims).await;
            self.emit(confirmed.clone());
            events.extend(confirmed);
        }
        Ok(events)
    }

    fn receive(
        &self,
        n: &OpenedNotice,
        events: &mut Vec<Event>,
        claims: &mut Vec<crate::packet::ClaimNotice>,
    ) -> Result<()> {
        let packed = match protocol::parse_frame(&n.message)? {
            Frame::Single(p) => p.to_vec(),
            Frame::Part {
                msg_id,
                index,
                total,
                chunk,
            } => match self.store_part(&n.from, &msg_id, index, total, chunk)? {
                Some(p) => p,
                None => return Ok(()),
            },
            Frame::File(_) => bail!("frame 0x23 is reserved"),
        };
        let (env, blob) = protocol::decode_inner(protocol::unpack(&packed)?)?;
        ensure!(
            env.from == n.from,
            "envelope from {} does not match sender",
            env.from
        );
        let mut conn = self.db();
        let tx = conn.transaction()?;
        if tx.execute(
            "INSERT OR IGNORE INTO seen (sender, env_id) VALUES (?,?)",
            [&env.from, &env.id],
        )? == 0
        {
            debug!("duplicate envelope {} from {}", env.id, env.from);
            return Ok(());
        }
        let (contact_id, changed) = chat::upsert_contact(&tx, &env.from, &env.name)?;
        if changed {
            events.extend([Event::ContactsChanged, Event::ChatListChanged]);
        }
        let blocked: bool = tx.query_row(
            "UPDATE contacts SET last_seen=? WHERE id=? RETURNING blocked",
            params![protocol::now_ms(), contact_id],
            |r| r.get(0),
        )?;
        if blocked {
            debug!("dropping envelope from blocked {}", env.from);
            tx.commit()?;
            return Ok(());
        }
        match &env.body {
            Body::Hello { token } => self.receive_hello(&tx, &env, token, events)?,
            Body::HelloOk => self.receive_hello_ok(&tx, &env, events)?,
            Body::GroupState { .. } => {
                self.receive_group_state(&tx, &env, contact_id, &blob, events)?
            }
            Body::Profile { avatar, bio } => self.receive_profile(
                &tx,
                contact_id,
                avatar.as_deref(),
                bio.as_deref(),
                &blob,
                events,
            )?,
            Body::Read { targets } => {
                if crate::settings::read_receipts(&tx) {
                    self.receive_read(&tx, contact_id, targets, events)?
                }
            }
            Body::Unknown => debug!("ignoring unknown envelope type from {}", env.from),
            body => {
                let chat_id = match &env.group {
                    Some(g) => match self.group_chat_of(&tx, g, contact_id)? {
                        Some(id) => id,
                        None => {
                            debug!(
                                "dropping envelope for group {g} from non-member {}",
                                env.from
                            );
                            tx.commit()?;
                            return Ok(());
                        }
                    },
                    None => chat::chat_for_contact(&tx, contact_id)?,
                };
                match body {
                    Body::Reaction { target, emoji } => {
                        self.receive_reaction(&tx, chat_id, contact_id, target, emoji, events)?
                    }
                    Body::Timer => {
                        self.apply_timer(&tx, chat_id, Some(contact_id), env.expires, events)?
                    }
                    Body::RedpacketClaim { .. } | Body::TransferClaim { .. } => {
                        claims.extend(self.receive_claim(&tx, chat_id, contact_id, body, events)?)
                    }
                    _ => self.receive_message(&tx, &env, chat_id, contact_id, &blob, events)?,
                }
            }
        }
        tx.commit()?;
        Ok(())
    }

    fn store_part(
        &self,
        sender: &str,
        msg_id: &[u8; 16],
        index: u16,
        total: u16,
        chunk: &[u8],
    ) -> Result<Option<Vec<u8>>> {
        let mut conn = self.db();
        let tx = conn.transaction()?;
        tx.execute(
            "DELETE FROM parts WHERE ts<?",
            [protocol::now_ms() - PARTS_TTL_MS],
        )?;
        tx.execute(
            "INSERT OR IGNORE INTO parts (sender, msg_id, idx, total, data, ts) VALUES (?,?,?,?,?,?)",
            params![sender, msg_id, index, total, chunk, protocol::now_ms()],
        )?;
        let have: u16 = tx.query_row(
            "SELECT COUNT(*) FROM parts WHERE sender=? AND msg_id=? AND total=?",
            params![sender, msg_id, total],
            |r| r.get(0),
        )?;
        if have < total {
            tx.commit()?;
            debug!("part {index}/{total} from {sender}");
            return Ok(None);
        }
        let mut packed = Vec::new();
        {
            let mut stmt =
                tx.prepare("SELECT data FROM parts WHERE sender=? AND msg_id=? ORDER BY idx")?;
            for data in stmt.query_map(params![sender, msg_id], |r| r.get::<_, Vec<u8>>(0))? {
                packed.extend(data?);
            }
        }
        tx.execute(
            "DELETE FROM parts WHERE sender=? AND msg_id=?",
            params![sender, msg_id],
        )?;
        tx.commit()?;
        Ok(Some(packed))
    }
}

pub(crate) fn state_events(conn: &Connection, msg_id: u32) -> Result<Vec<Event>> {
    let chat_id: u32 =
        conn.query_row("SELECT chat_id FROM messages WHERE id=?", [msg_id], |r| {
            r.get(0)
        })?;
    Ok(vec![
        Event::MessageStateChanged { chat_id, msg_id },
        Event::ChatListChanged,
    ])
}

pub(crate) fn fail_delivery(
    conn: &Connection,
    msg_id: u32,
    recipient: &str,
    reason: &str,
) -> Result<Vec<Event>> {
    conn.execute(
        "DELETE FROM sent_notices WHERE msg_id=? AND recipient=?",
        params![msg_id, recipient],
    )?;
    conn.execute(
        "UPDATE deliveries SET state=?, error=? WHERE msg_id=? AND contact_id=(SELECT id FROM contacts WHERE account=?)",
        params![MessageState::Failed.code(), reason, msg_id, recipient],
    )?;
    recompute(conn, msg_id)
}

fn set_delivery(
    conn: &Connection,
    msg_id: u32,
    recipient: &str,
    state: MessageState,
    from: MessageState,
) -> Result<Vec<Event>> {
    conn.execute(
        "UPDATE deliveries SET state=? WHERE msg_id=? AND state=? AND contact_id=(SELECT id FROM contacts WHERE account=?)",
        params![state.code(), msg_id, from.code(), recipient],
    )?;
    recompute(conn, msg_id)
}

pub(crate) fn recompute(conn: &Connection, msg_id: u32) -> Result<Vec<Event>> {
    let rows: Vec<(i64, Option<String>)> = {
        let mut stmt = conn
            .prepare_cached("SELECT state, error FROM deliveries WHERE msg_id=? ORDER BY rowid")?;
        let rows = stmt.query_map([msg_id], |r| Ok((r.get(0)?, r.get(1)?)))?;
        rows.collect::<Result<_, _>>()?
    };
    if rows.is_empty() {
        return Ok(Vec::new());
    }
    let has = |s: MessageState| rows.iter().any(|(c, _)| *c == s.code());
    let (state, error) =
        if let Some((_, e)) = rows.iter().find(|(c, _)| *c == MessageState::Failed.code()) {
            (MessageState::Failed, e.clone())
        } else if has(MessageState::Pending) {
            (MessageState::Pending, None)
        } else if has(MessageState::Read) {
            (MessageState::Read, None)
        } else if rows
            .iter()
            .all(|(c, _)| *c == MessageState::Delivered.code())
        {
            (MessageState::Delivered, None)
        } else {
            (MessageState::Sent, None)
        };
    let n = conn.execute(
        "UPDATE messages SET state=?1, error=?2 WHERE id=?3 AND (state<>?1 OR error IS NOT ?2)",
        params![state.code(), error, msg_id],
    )?;
    if n == 0 {
        return Ok(Vec::new());
    }
    state_events(conn, msg_id)
}

#[uniffi::export]
impl Account {
    pub fn info(&self) -> AccountInfo {
        let conn = self.db();
        AccountInfo {
            id: self.id,
            address: self.keys.id.address().to_string(),
            account: self.keys.account().to_string(),
            name: self.my_name(&conn).unwrap_or_default(),
            avatar_path: chat::abs(&self.dir, store::get_config(&conn, "avatar").ok().flatten()),
            node: self.node.clone(),
            bio: store::get_config(&conn, "bio")
                .ok()
                .flatten()
                .unwrap_or_default(),
            muted: crate::settings::account_muted(&conn),
            tag: store::get_config(&conn, "tag")
                .ok()
                .flatten()
                .unwrap_or_default(),
            unread: self.unread(&conn).unwrap_or(0),
        }
    }

    pub async fn set_profile(
        &self,
        name: String,
        avatar_path: Option<String>,
    ) -> Result<(), CoreError> {
        let current = store::get_config(&self.db(), "avatar")?;
        let avatar = match avatar_path {
            Some(src) if Some(&src) == chat::abs(&self.dir, current.clone()).as_ref() => current,
            Some(src) => Some(self.copy_avatar(&src)?),
            None => None,
        };
        let conn = self.db();
        store::set_config(&conn, "name", name.trim())?;
        match avatar {
            Some(a) => store::set_config(&conn, "avatar", &a)?,
            None => {
                conn.execute("DELETE FROM config WHERE key='avatar'", [])?;
            }
        }
        self.send_profile(&conn, None)?;
        info!("account {} profile updated", self.id);
        Ok(())
    }

    pub fn start(self: Arc<Self>) {
        let mut tasks = self.tasks.lock().unwrap();
        if !tasks.is_empty() {
            return;
        }
        info!("account {} starting as {}", self.id, self.keys.account());
        tasks.push(crate::RT.spawn(self.clone().send_loop()));
        tasks.push(crate::RT.spawn(self.clone().recv_loop()));
        tasks.push(crate::RT.spawn(self.clone().poll_loop()));
        tasks.push(crate::RT.spawn(self.clone().expire_loop()));
        tasks.push(crate::RT.spawn(self.clone().transfer_loop()));
    }

    pub fn stop(&self) {
        let tasks: Vec<_> = self.tasks.lock().unwrap().drain(..).collect();
        if tasks.is_empty() {
            return;
        }
        for t in tasks {
            t.abort();
        }
        info!("account {} stopped", self.id);
        self.set_connectivity(Connectivity::NotConnected);
    }

    pub fn connectivity(&self) -> Connectivity {
        *self.connectivity.lock().unwrap()
    }
}
