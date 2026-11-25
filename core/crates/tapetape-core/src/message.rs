use std::path::Path;

use anyhow::{ensure, Context, Result};
use log::{debug, info, warn};
use rusqlite::{params, Connection, OptionalExtension, Params, Row};

use crate::account::Account;
use crate::api::*;
use crate::chat::{
    abs, accept_group, allow_contact, chat_title, display_name, live, recipients, special_chat,
    target, Target, CHAT_CONTACT, CHAT_DEVICE, CHAT_GROUP, CHAT_SELF,
};
use crate::packet::Stored;
use crate::protocol::{self, now_ms, Body, Envelope, MAX_ATTACHMENT};
use crate::settings;

const COLUMNS: &str = "m.id, m.chat_id, m.contact_id, COALESCE(ct.name,''), COALESCE(ct.local_name,''),
    COALESCE(ct.account,''), m.text, m.time, m.state, m.error, m.reply_to, m.kind, m.file, m.file_name,
    m.mime, m.size, m.width, m.height, m.duration, m.vcard_name, m.vcard_account, m.forwarded,
    m.original_id, m.expires_at, m.system, EXISTS(SELECT 1 FROM messages s WHERE s.original_id=m.id), m.packet,
    (SELECT t.state FROM transfers t WHERE t.msg_id=m.id AND t.dir=1)";

#[derive(Default)]
pub(crate) struct Draft {
    pub kind: i64,
    pub text: String,
    pub file: Option<String>,
    pub file_name: Option<String>,
    pub mime: Option<String>,
    pub size: Option<i64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration: Option<u32>,
    pub vcard_name: Option<String>,
    pub vcard_account: Option<String>,
    pub reply_to: Option<u32>,
    pub forwarded: bool,
    pub packet: Option<String>,
    pub remote: Option<Vec<u8>>,
}

fn reactions(conn: &Connection, msg_id: u32) -> rusqlite::Result<Vec<Reaction>> {
    let mut stmt = conn.prepare_cached(
        "SELECT emoji, COUNT(*), MAX(contact_id=0) FROM reactions WHERE msg_id=?
         GROUP BY emoji ORDER BY MIN(time)",
    )?;
    let rows = stmt.query_map([msg_id], |r| {
        Ok(Reaction {
            emoji: r.get(0)?,
            count: r.get(1)?,
            mine: r.get(2)?,
        })
    })?;
    rows.collect()
}

fn system_of(s: Option<String>) -> Option<SystemMessage> {
    serde_json::from_str(&s?).ok()
}

fn row_to_message(
    conn: &Connection,
    dir: &Path,
    me: &str,
    my_address: &str,
    r: &Row,
) -> rusqlite::Result<Message> {
    let id: u32 = r.get(0)?;
    let from_contact: Option<u32> = r.get(2)?;
    let sender_name = match from_contact {
        Some(_) => display_name(
            &r.get::<_, String>(4)?,
            &r.get::<_, String>(3)?,
            &r.get::<_, String>(5)?,
        ),
        None => me.to_string(),
    };
    let vcard = match (
        r.get::<_, Option<String>>(19)?,
        r.get::<_, Option<String>>(20)?,
    ) {
        (Some(name), Some(account)) => Some(Vcard {
            address: taperelay::self_key_of(&account)
                .unwrap_or_default()
                .to_string(),
            name,
            account,
        }),
        _ => None,
    };
    Ok(Message {
        id,
        chat_id: r.get(1)?,
        from_contact,
        sender_name,
        text: r.get(6)?,
        time: r.get(7)?,
        state: MessageState::from_code(r.get(8)?),
        error: r.get(9)?,
        reply_to: r.get(10)?,
        kind: MessageKind::from_code(r.get(11)?),
        file_path: abs(dir, r.get(12)?),
        file_name: r.get(13)?,
        mime: r.get(14)?,
        size: r.get(15)?,
        width: r.get(16)?,
        height: r.get(17)?,
        duration_ms: r.get(18)?,
        vcard,
        reactions: reactions(conn, id)?,
        forwarded: r.get(21)?,
        original_id: r.get(22)?,
        expires_at: r.get(23)?,
        system: system_of(r.get(24)?),
        packet: r
            .get::<_, Option<String>>(26)?
            .and_then(|p| serde_json::from_str::<Stored>(&p).ok())
            .map(|p| p.record(my_address)),
        download: crate::files::download_state(r.get(27)?),
        saved: r.get(25)?,
    })
}

fn sanitize(name: &str) -> String {
    let base = Path::new(name)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file");
    let clean: String = base
        .chars()
        .filter(|c| !c.is_control() && !matches!(c, '/' | '\\' | ':'))
        .collect();
    let clean = clean.trim_start_matches('.');
    let clean: String = clean
        .chars()
        .rev()
        .take(80)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    if clean.is_empty() {
        "file".into()
    } else {
        clean
    }
}

impl Account {
    pub(crate) fn load_messages<P: Params>(
        &self,
        conn: &Connection,
        filter: &str,
        params: P,
    ) -> Result<Vec<Message>> {
        let me = self.my_name(conn)?;
        let mut stmt = conn.prepare(&format!(
            "SELECT {COLUMNS} FROM messages m LEFT JOIN contacts ct ON ct.id=m.contact_id
             WHERE {} AND {filter}",
            live("m")
        ))?;
        let address = self.keys.id.address();
        let rows = stmt.query_map(params, |r| row_to_message(conn, &self.dir, &me, address, r))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    fn blob_name(name: &str) -> String {
        format!(
            "blobs/{}-{}",
            hex::encode(rand::random::<[u8; 4]>()),
            sanitize(name)
        )
    }

    pub(crate) fn save_blob(&self, name: &str, bytes: &[u8]) -> Result<String> {
        let rel = Self::blob_name(name);
        std::fs::write(self.dir.join(&rel), bytes).context("Cannot store the file")?;
        Ok(rel)
    }

    pub(crate) fn copy_avatar(&self, src: &str) -> Result<String> {
        let len = std::fs::metadata(src)
            .context("Cannot read the image")?
            .len();
        ensure!(len <= MAX_ATTACHMENT, "too-large");
        self.copy_blob(src)
    }

    pub(crate) fn copy_blob(&self, src: &str) -> Result<String> {
        let rel = Self::blob_name(src);
        std::fs::copy(src, self.dir.join(&rel)).context("Cannot read the file")?;
        Ok(rel)
    }

    pub(crate) fn delete_messages_where<P: Params>(
        &self,
        conn: &Connection,
        filter: &str,
        params: P,
    ) -> Result<Vec<String>> {
        let rows: Vec<(u32, Option<String>)> = {
            let mut stmt =
                conn.prepare(&format!("SELECT id, file FROM messages WHERE {filter}"))?;
            let rows = stmt.query_map(params, |r| Ok((r.get(0)?, r.get(1)?)))?;
            rows.collect::<Result<_, _>>()?
        };
        for (id, _) in &rows {
            conn.execute("DELETE FROM reactions WHERE msg_id=?", [id])?;
            conn.execute("DELETE FROM deliveries WHERE msg_id=?", [id])?;
            conn.execute("DELETE FROM send_queue WHERE msg_id=?", [id])?;
            conn.execute("DELETE FROM sent_notices WHERE msg_id=?", [id])?;
            conn.execute("DELETE FROM messages WHERE id=?", [id])?;
        }
        Ok(rows.into_iter().filter_map(|(_, f)| f).collect())
    }

    pub(crate) fn remove_unused(&self, files: Vec<String>) {
        let conn = self.db();
        for f in files {
            let used: bool = conn
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM messages WHERE file=?)",
                    [&f],
                    |r| r.get(0),
                )
                .unwrap_or(true);
            if !used {
                let _ = std::fs::remove_file(self.dir.join(&f));
            }
        }
    }

    pub(crate) fn outgoing_envelope(
        &self,
        conn: &Connection,
        msg_id: u32,
    ) -> Result<(u32, Envelope, Vec<u8>)> {
        let m = self
            .load_messages(conn, "m.id=?", [msg_id])?
            .pop()
            .context("Message not found")?;
        let (env_id, grp, ephemeral, reply): (String, Option<String>, u32, Option<String>) = conn.query_row(
            "SELECT m.env_id, c.grp, c.ephemeral, (SELECT r.env_id FROM messages r WHERE r.id=m.reply_to)
             FROM messages m JOIN chats c ON c.id=m.chat_id WHERE m.id=?",
            [msg_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )?;
        let mut blob = Vec::new();
        let body = match m.kind {
            MessageKind::RedPacket | MessageKind::Transfer => {
                let raw: String =
                    conn.query_row("SELECT packet FROM messages WHERE id=?", [msg_id], |r| {
                        r.get(0)
                    })?;
                serde_json::from_str::<Stored>(&raw)?.body()?
            }
            MessageKind::Text => Body::Text {
                text: m.text,
                reply,
            },
            MessageKind::Vcard => {
                let v = m.vcard.context("vcard missing")?;
                Body::Contact {
                    account: v.account,
                    cname: v.name,
                }
            }
            k => {
                blob = std::fs::read(m.file_path.as_deref().context("file missing")?)
                    .context("Cannot read the attachment")?;
                Body::Media {
                    kind: k.wire().into(),
                    mime: m.mime.unwrap_or_else(|| "application/octet-stream".into()),
                    fname: m.file_name,
                    size: blob.len() as u64,
                    w: m.width,
                    h: m.height,
                    dur: m.duration_ms,
                    text: (!m.text.is_empty()).then_some(m.text),
                    reply,
                    reference: None,
                }
            }
        };
        let mut env = self.envelope(conn, body)?;
        env.id = env_id;
        env.ts = m.time;
        env.expires = ephemeral;
        env.fwd = m.forwarded;
        env.group = grp;
        Ok((m.chat_id, env, blob))
    }

    pub(crate) fn fan_out(
        &self,
        conn: &Connection,
        chat_id: u32,
        grp: Option<String>,
        mut env: Envelope,
    ) -> Result<()> {
        env.group = grp;
        for to in recipients(conn, chat_id)? {
            self.enqueue(conn, &to, None, &env, &[])?;
        }
        Ok(())
    }

    pub(crate) fn add_system(
        &self,
        conn: &Connection,
        chat_id: u32,
        from: Option<u32>,
        system: SystemMessage,
    ) -> Result<()> {
        conn.execute(
            "INSERT INTO messages (chat_id, contact_id, env_id, kind, text, time, state, system)
             VALUES (?,?,?,?,'',?,?,?)",
            params![
                chat_id,
                from,
                protocol::new_id(),
                MessageKind::System.code(),
                now_ms(),
                MessageState::Seen.code(),
                serde_json::to_string(&system)?
            ],
        )?;
        Ok(())
    }

    pub(crate) fn queue_message(&self, conn: &Connection, msg_id: u32) -> Result<()> {
        let (chat_id, env, blob) = self.outgoing_envelope(conn, msg_id)?;
        let to = recipients(conn, chat_id)?;
        conn.execute("DELETE FROM deliveries WHERE msg_id=?", [msg_id])?;
        if !to.is_empty() && self.wants_upload(conn, msg_id, blob.len() as u64)? {
            for r in &to {
                conn.execute(
                    "INSERT INTO deliveries (msg_id, contact_id, state) SELECT ?, id, ? FROM contacts WHERE account=?",
                    params![msg_id, MessageState::Pending.code(), r],
                )?;
            }
            info!("message {msg_id} waits for upload of {} bytes", blob.len());
            return Ok(());
        }
        if blob.len() as u64 > MAX_ATTACHMENT {
            warn!(
                "message {msg_id} attachment too large: {} bytes",
                blob.len()
            );
            conn.execute(
                "UPDATE messages SET state=?, error='too-large' WHERE id=?",
                params![MessageState::Failed.code(), msg_id],
            )?;
            return Ok(());
        }
        for r in &to {
            self.enqueue(conn, r, Some(msg_id), &env, &blob)?;
            conn.execute(
                "INSERT INTO deliveries (msg_id, contact_id, state) SELECT ?, id, ? FROM contacts WHERE account=?",
                params![msg_id, MessageState::Pending.code(), r],
            )?;
        }
        if to.is_empty() {
            conn.execute(
                "UPDATE messages SET state=? WHERE id=?",
                params![MessageState::Delivered.code(), msg_id],
            )?;
        }
        Ok(())
    }

    pub(crate) fn send_draft(&self, chat_id: u32, d: Draft) -> Result<u32> {
        self.store_draft(chat_id, d, true)
    }

    pub(crate) fn store_draft(&self, chat_id: u32, d: Draft, queue: bool) -> Result<u32> {
        let mut conn = self.db();
        let tx = conn.transaction()?;
        let t = self.chat_target_for_send(&tx, chat_id)?;
        if let Some(r) = d.reply_to {
            let ok: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM messages WHERE id=?)",
                [r],
                |row| row.get(0),
            )?;
            ensure!(ok, "The quoted message no longer exists");
        }
        let now = now_ms();
        let state = if t.kind == CHAT_SELF {
            MessageState::Delivered
        } else {
            MessageState::Pending
        };
        let expires_at = (t.ephemeral > 0).then(|| now + i64::from(t.ephemeral) * 1000);
        tx.execute(
            "INSERT INTO messages (chat_id, env_id, kind, text, time, state, reply_to, file, file_name, mime,
                size, width, height, duration, vcard_name, vcard_account, forwarded, ephemeral, expires_at, packet)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            params![
                chat_id,
                protocol::new_id(),
                d.kind,
                d.text,
                now,
                state.code(),
                d.reply_to,
                d.file,
                d.file_name,
                d.mime,
                d.size,
                d.width,
                d.height,
                d.duration,
                d.vcard_name,
                d.vcard_account,
                d.forwarded,
                t.ephemeral,
                expires_at,
                d.packet
            ],
        )?;
        let msg_id = tx.last_insert_rowid() as u32;
        if queue {
            self.dispatch(&tx, &t, chat_id, msg_id)?;
        }
        tx.execute("UPDATE chats SET draft=NULL WHERE id=?", [chat_id])?;
        tx.commit()?;
        drop(conn);
        if expires_at.is_some() {
            self.wake_expiry();
        }
        info!("queued message {msg_id} in chat {chat_id}");
        self.emit(vec![
            Event::MessagesChanged { chat_id },
            Event::ChatListChanged,
        ]);
        Ok(msg_id)
    }

    pub(crate) fn dispatch(
        &self,
        conn: &Connection,
        t: &Target,
        chat_id: u32,
        msg_id: u32,
    ) -> Result<()> {
        match (t.kind, t.contact_id) {
            (CHAT_CONTACT, Some(contact_id)) => {
                self.share_profile(conn, allow_contact(conn, contact_id)?)?;
                self.queue_message(conn, msg_id)?;
            }
            (CHAT_GROUP, _) => {
                if t.request {
                    self.share_profile(conn, accept_group(conn, chat_id)?)?;
                }
                self.queue_message(conn, msg_id)?;
            }
            _ => {}
        }
        Ok(())
    }

    pub(crate) fn apply_timer(
        &self,
        conn: &Connection,
        chat_id: u32,
        from: Option<u32>,
        seconds: u32,
        events: &mut Vec<Event>,
    ) -> Result<()> {
        let current: u32 =
            conn.query_row("SELECT ephemeral FROM chats WHERE id=?", [chat_id], |r| {
                r.get(0)
            })?;
        if current == seconds {
            return Ok(());
        }
        conn.execute(
            "UPDATE chats SET ephemeral=? WHERE id=?",
            params![seconds, chat_id],
        )?;
        self.add_system(conn, chat_id, from, SystemMessage::TimerChanged { seconds })?;
        info!("chat {chat_id} disappearing timer set to {seconds}s");
        events.extend([Event::MessagesChanged { chat_id }, Event::ChatListChanged]);
        Ok(())
    }

    pub(crate) fn receive_message(
        &self,
        conn: &Connection,
        env: &Envelope,
        chat_id: u32,
        contact_id: u32,
        blob: &[u8],
        events: &mut Vec<Event>,
    ) -> Result<()> {
        self.apply_timer(conn, chat_id, Some(contact_id), env.expires, events)?;
        let mut d = Draft {
            forwarded: env.fwd,
            ..Draft::default()
        };
        let reply = match &env.body {
            Body::Text { text, reply } => {
                d.kind = MessageKind::Text.code();
                d.text = text.clone();
                reply
            }
            Body::Media {
                kind,
                mime,
                fname,
                size,
                w,
                h,
                dur,
                text,
                reply,
                reference,
            } => {
                let k = MessageKind::from_wire(kind);
                d.kind = k.code();
                d.text = text.clone().unwrap_or_default();
                let name = fname.clone().unwrap_or_else(|| {
                    let ext = mime_guess::get_mime_extensions_str(mime)
                        .and_then(|e| e.first())
                        .unwrap_or(&"bin");
                    format!("{}.{ext}", k.wire())
                });
                match reference {
                    Some(r) => {
                        let r = taperelay::base64url_decode(r).context("bad file reference")?;
                        crate::files::Reference::decode(&r)?;
                        ensure!(
                            blob.is_empty() && *size <= crate::files::MAX_FILE,
                            "bad remote file"
                        );
                        d.remote = Some(r);
                        d.size = Some(*size as i64);
                    }
                    None => {
                        d.file = Some(self.save_blob(&name, blob)?);
                        d.size = Some(blob.len() as i64);
                    }
                }
                d.file_name = Some(sanitize(&name));
                d.mime = Some(mime.clone());
                d.width = *w;
                d.height = *h;
                d.duration = *dur;
                reply
            }
            Body::Contact { account, cname } => {
                ensure!(taperelay::is_self_account(account), "bad contact card");
                d.kind = MessageKind::Vcard.code();
                d.vcard_name = Some(cname.clone());
                d.vcard_account = Some(account.clone());
                &None
            }
            Body::Redpacket { .. } | Body::Transfer { .. } | Body::TransferDirect { .. } => {
                let p = Stored::from_body(&env.body, &env.from)?;
                if p.direct {
                    // The same payment can only back one card, or a sender could replay an old transaction.
                    let seen: bool = conn.query_row(
                        "SELECT EXISTS(SELECT 1 FROM messages WHERE json_extract(packet, '$.tx')=?)",
                        [&p.tx],
                        |r| r.get(0),
                    )?;
                    ensure!(!seen, "transfer {} already shown", p.tx);
                }
                d.kind = MessageKind::from(p.kind).code();
                d.text = p.text.clone();
                d.packet = Some(serde_json::to_string(&p)?);
                &None
            }
            _ => return Ok(()),
        };
        d.reply_to = match reply {
            Some(r) => conn
                .query_row(
                    "SELECT id FROM messages WHERE env_id=? ORDER BY chat_id<>? LIMIT 1",
                    params![r, chat_id],
                    |row| row.get(0),
                )
                .optional()?,
            None => None,
        };
        conn.execute(
            "INSERT INTO messages (chat_id, contact_id, env_id, kind, text, time, state, reply_to, file, file_name,
                mime, size, width, height, duration, vcard_name, vcard_account, forwarded, ephemeral, packet)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            params![
                chat_id,
                contact_id,
                env.id,
                d.kind,
                d.text,
                env.ts,
                MessageState::Incoming.code(),
                d.reply_to,
                d.file,
                d.file_name,
                d.mime,
                d.size,
                d.width,
                d.height,
                d.duration,
                d.vcard_name,
                d.vcard_account,
                d.forwarded,
                env.expires,
                d.packet
            ],
        )?;
        let msg_id = conn.last_insert_rowid() as u32;
        if let (Some(r), Some(size)) = (&d.remote, d.size) {
            self.add_download(conn, msg_id, r, size as u64)?;
        }
        info!(
            "received message {msg_id} in chat {chat_id} from {}",
            env.from
        );
        events.extend([
            Event::IncomingMessage { chat_id, msg_id },
            Event::MessagesChanged { chat_id },
            Event::ChatListChanged,
            Event::Notification {
                notification: self.notification(conn, msg_id, None)?,
            },
        ]);
        Ok(())
    }

    pub(crate) fn notification(
        &self,
        conn: &Connection,
        msg_id: u32,
        reaction: Option<(u32, &str)>,
    ) -> Result<Notification> {
        let m = self
            .load_messages(conn, "m.id=?", [msg_id])?
            .pop()
            .context("Message not found")?;
        let (chat_name, is_group, chat_muted) = chat_title(conn, m.chat_id)?;
        let mention = match (reaction, m.reply_to) {
            (Some(_), _) => true,
            (None, Some(r)) => conn
                .query_row(
                    "SELECT contact_id IS NULL FROM messages WHERE id=?",
                    [r],
                    |row| row.get(0),
                )
                .optional()?
                .unwrap_or(false),
            _ => false,
        };
        let sender_name = match reaction {
            Some((contact_id, _)) => crate::group::contact_name(conn, contact_id)?,
            None => m.sender_name,
        };
        Ok(Notification {
            account_id: self.id,
            chat_id: m.chat_id,
            msg_id,
            chat_name,
            sender_name,
            is_group,
            kind: m.kind,
            text: m.text,
            reaction: reaction.map(|(_, e)| e.to_string()),
            muted: chat_muted || settings::account_muted(conn),
            mention,
        })
    }

    pub(crate) fn receive_reaction(
        &self,
        conn: &Connection,
        chat_id: u32,
        contact_id: u32,
        target_env: &str,
        emoji: &str,
        events: &mut Vec<Event>,
    ) -> Result<()> {
        let found: Option<(u32, bool)> = conn
            .query_row(
                "SELECT id, contact_id IS NULL FROM messages WHERE chat_id=? AND env_id=?",
                params![chat_id, target_env],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let Some((msg_id, mine)) = found else {
            debug!("reaction for unknown message {target_env}");
            return Ok(());
        };
        if emoji.is_empty() {
            conn.execute(
                "DELETE FROM reactions WHERE msg_id=? AND contact_id=?",
                params![msg_id, contact_id],
            )?;
        } else {
            conn.execute(
                "INSERT OR REPLACE INTO reactions (msg_id, contact_id, emoji, time) VALUES (?,?,?,?)",
                params![msg_id, contact_id, emoji, now_ms()],
            )?;
            if mine {
                events.push(Event::IncomingReaction { chat_id, msg_id });
                events.push(Event::Notification {
                    notification: self.notification(conn, msg_id, Some((contact_id, emoji)))?,
                });
            }
        }
        debug!("reaction on message {msg_id} from contact {contact_id}");
        events.push(Event::MessagesChanged { chat_id });
        Ok(())
    }

    pub(crate) fn receive_read(
        &self,
        conn: &Connection,
        contact_id: u32,
        targets: &[String],
        events: &mut Vec<Event>,
    ) -> Result<()> {
        for t in targets {
            let ids: Vec<u32> = {
                let mut stmt = conn.prepare(
                    "UPDATE deliveries SET state=?1 WHERE contact_id=?2 AND state IN (?3, ?4)
                     AND msg_id IN (SELECT id FROM messages WHERE env_id=?5 AND contact_id IS NULL) RETURNING msg_id",
                )?;
                let rows = stmt.query_map(
                    params![
                        MessageState::Read.code(),
                        contact_id,
                        MessageState::Sent.code(),
                        MessageState::Delivered.code(),
                        t
                    ],
                    |r| r.get(0),
                )?;
                rows.collect::<Result<_, _>>()?
            };
            for msg_id in ids {
                events.extend(crate::account::recompute(conn, msg_id)?);
            }
        }
        debug!(
            "read receipt for {} message(s) from contact {contact_id}",
            targets.len()
        );
        Ok(())
    }

    pub(crate) fn expire_now(&self) -> Result<Option<i64>> {
        let now = now_ms();
        let conn = self.db();
        let chats: Vec<u32> = {
            let mut stmt = conn.prepare(
                "SELECT DISTINCT chat_id FROM messages WHERE expires_at IS NOT NULL AND expires_at<=?",
            )?;
            let rows = stmt.query_map([now], |r| r.get(0))?;
            rows.collect::<Result<_, _>>()?
        };
        let files =
            self.delete_messages_where(&conn, "expires_at IS NOT NULL AND expires_at<=?", [now])?;
        let next: Option<i64> = conn.query_row(
            "SELECT MIN(expires_at) FROM messages WHERE expires_at IS NOT NULL",
            [],
            |r| r.get(0),
        )?;
        drop(conn);
        if !chats.is_empty() {
            debug!("expired messages in {} chat(s)", chats.len());
            self.remove_unused(files);
            let mut events: Vec<Event> = chats
                .into_iter()
                .map(|chat_id| Event::MessagesChanged { chat_id })
                .collect();
            events.push(Event::ChatListChanged);
            self.emit(events);
        }
        Ok(next)
    }
}

#[uniffi::export]
impl Account {
    pub fn messages(&self, chat_id: u32, before: Option<u32>, limit: u32) -> Vec<Message> {
        let conn = self.db();
        let mut list = self
            .load_messages(
                &conn,
                "m.chat_id=?1 AND (?2 IS NULL OR m.id<?2) ORDER BY m.id DESC LIMIT ?3",
                params![chat_id, before, limit],
            )
            .unwrap_or_else(|e| {
                log::error!("messages query failed: {e:#}");
                Vec::new()
            });
        list.reverse();
        list
    }

    pub fn message(&self, msg_id: u32) -> Result<Message, CoreError> {
        let conn = self.db();
        Ok(self
            .load_messages(&conn, "m.id=?", [msg_id])?
            .pop()
            .context("Message not found")?)
    }

    pub fn search_messages(&self, chat_id: Option<u32>, query: String) -> Vec<Message> {
        let q = query.trim();
        if q.is_empty() {
            return Vec::new();
        }
        let conn = self.db();
        self.load_messages(
            &conn,
            "(?1 IS NULL OR m.chat_id=?1) AND m.kind<>?3 AND (m.text LIKE ?2 OR m.file_name LIKE ?2 OR m.vcard_name LIKE ?2)
             AND m.chat_id NOT IN (SELECT c.id FROM chats c JOIN contacts b ON b.id=c.contact_id WHERE b.blocked=1)
             ORDER BY m.id",
            params![chat_id, format!("%{q}%"), MessageKind::System.code()],
        )
        .unwrap_or_else(|e| {
            log::error!("search failed: {e:#}");
            Vec::new()
        })
    }

    pub fn media_messages(&self, chat_id: u32, kinds: Vec<MessageKind>) -> Vec<Message> {
        let codes: Vec<String> = kinds.iter().map(|k| k.code().to_string()).collect();
        if codes.is_empty() {
            return Vec::new();
        }
        let conn = self.db();
        self.load_messages(
            &conn,
            &format!(
                "m.chat_id=? AND m.kind IN ({}) ORDER BY m.id",
                codes.join(",")
            ),
            [chat_id],
        )
        .unwrap_or_else(|e| {
            log::error!("media query failed: {e:#}");
            Vec::new()
        })
    }

    pub async fn send_text(
        &self,
        chat_id: u32,
        text: String,
        reply_to: Option<u32>,
    ) -> Result<u32, CoreError> {
        Ok(self.send_draft(
            chat_id,
            Draft {
                kind: MessageKind::Text.code(),
                text,
                reply_to,
                ..Draft::default()
            },
        )?)
    }

    pub async fn send_media(
        &self,
        chat_id: u32,
        kind: MediaKind,
        path: String,
        text: Option<String>,
        reply_to: Option<u32>,
        duration_ms: Option<u32>,
    ) -> Result<u32, CoreError> {
        let kind = MessageKind::from(kind);
        let file = self.copy_blob(&path)?;
        let full = self.dir.join(&file);
        let size = std::fs::metadata(&full)
            .context("Cannot read the file")?
            .len();
        let mime = mime_guess::from_path(&path)
            .first_or_octet_stream()
            .to_string();
        let (width, height) = match kind {
            MessageKind::Image | MessageKind::Gif | MessageKind::Sticker => {
                match imagesize::size(&full) {
                    Ok(s) => (Some(s.width as u32), Some(s.height as u32)),
                    Err(e) => {
                        debug!("no image size for {path}: {e}");
                        (None, None)
                    }
                }
            }
            _ => (None, None),
        };
        Ok(self.send_draft(
            chat_id,
            Draft {
                kind: kind.code(),
                text: text.unwrap_or_default(),
                file: Some(file),
                file_name: Some(sanitize(&path)),
                mime: Some(mime),
                size: Some(size as i64),
                width,
                height,
                duration: duration_ms,
                reply_to,
                ..Draft::default()
            },
        )?)
    }

    pub async fn send_vcard(&self, chat_id: u32, contact_id: u32) -> Result<u32, CoreError> {
        let c = self.contact(contact_id)?;
        Ok(self.send_draft(
            chat_id,
            Draft {
                kind: MessageKind::Vcard.code(),
                vcard_name: Some(c.name),
                vcard_account: Some(c.account),
                ..Draft::default()
            },
        )?)
    }

    pub async fn react(&self, msg_id: u32, emoji: String) -> Result<(), CoreError> {
        let conn = self.db();
        let (chat_id, env_id): (u32, String) = conn
            .query_row(
                "SELECT chat_id, env_id FROM messages WHERE id=?",
                [msg_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?
            .context("Message not found")?;
        let t = self.chat_target_for_send(&conn, chat_id)?;
        if emoji.is_empty() {
            conn.execute(
                "DELETE FROM reactions WHERE msg_id=? AND contact_id=0",
                [msg_id],
            )?;
        } else {
            conn.execute(
                "INSERT OR REPLACE INTO reactions (msg_id, contact_id, emoji, time) VALUES (?,0,?,?)",
                params![msg_id, emoji, now_ms()],
            )?;
        }
        if matches!(t.kind, CHAT_CONTACT | CHAT_GROUP) {
            let env = self.envelope(
                &conn,
                Body::Reaction {
                    target: env_id,
                    emoji,
                },
            )?;
            self.fan_out(&conn, chat_id, t.grp, env)?;
        }
        drop(conn);
        self.emit(vec![Event::MessagesChanged { chat_id }]);
        Ok(())
    }

    pub fn message_reactions(&self, msg_id: u32) -> Vec<ReactionBy> {
        let conn = self.db();
        let me = self.my_name(&conn).unwrap_or_default();
        let result = (|| -> Result<Vec<ReactionBy>> {
            let mut stmt = conn.prepare(
                "SELECT r.contact_id, COALESCE(ct.local_name,''), COALESCE(ct.name,''), COALESCE(ct.account,''), r.emoji
                 FROM reactions r LEFT JOIN contacts ct ON ct.id=r.contact_id WHERE r.msg_id=? ORDER BY r.time",
            )?;
            let rows = stmt.query_map([msg_id], |r| {
                let id: u32 = r.get(0)?;
                Ok(ReactionBy {
                    contact_id: (id != 0).then_some(id),
                    name: if id == 0 {
                        me.clone()
                    } else {
                        display_name(
                            &r.get::<_, String>(1)?,
                            &r.get::<_, String>(2)?,
                            &r.get::<_, String>(3)?,
                        )
                    },
                    emoji: r.get(4)?,
                })
            })?;
            Ok(rows.collect::<Result<_, _>>()?)
        })();
        result.unwrap_or_default()
    }

    pub async fn forward(&self, msg_ids: Vec<u32>, to_chat_id: u32) -> Result<Vec<u32>, CoreError> {
        let mut out = Vec::new();
        for id in msg_ids {
            let m = self.message(id)?;
            if matches!(
                m.kind,
                MessageKind::System | MessageKind::RedPacket | MessageKind::Transfer
            ) || m.download.is_some()
            {
                continue;
            }
            let file = match &m.file_path {
                Some(f) => Some(self.copy_blob(f)?),
                None => None,
            };
            let vcard = m.vcard.clone();
            out.push(self.send_draft(
                to_chat_id,
                Draft {
                    kind: m.kind.code(),
                    text: m.text,
                    file,
                    file_name: m.file_name,
                    mime: m.mime,
                    size: m.size.map(|s| s as i64),
                    width: m.width,
                    height: m.height,
                    duration: m.duration_ms,
                    vcard_name: vcard.as_ref().map(|v| v.name.clone()),
                    vcard_account: vcard.map(|v| v.account),
                    reply_to: None,
                    forwarded: true,
                    packet: None,
                    remote: None,
                },
            )?);
        }
        info!("forwarded {} message(s) to chat {to_chat_id}", out.len());
        Ok(out)
    }

    pub fn delete_messages(&self, msg_ids: Vec<u32>) {
        let result = (|| -> Result<Vec<u32>> {
            let mut conn = self.db();
            let tx = conn.transaction()?;
            let mut chats = Vec::new();
            let mut files = Vec::new();
            for id in &msg_ids {
                if let Some(chat_id) = tx
                    .query_row("SELECT chat_id FROM messages WHERE id=?", [id], |r| {
                        r.get::<_, u32>(0)
                    })
                    .optional()?
                {
                    chats.push(chat_id);
                    files.extend(self.delete_messages_where(&tx, "id=?", [id])?);
                }
            }
            tx.commit()?;
            drop(conn);
            self.remove_unused(files);
            Ok(chats)
        })();
        match result {
            Ok(mut chats) => {
                chats.dedup();
                info!("deleted {} message(s) locally", msg_ids.len());
                let mut events: Vec<Event> = chats
                    .into_iter()
                    .map(|chat_id| Event::MessagesChanged { chat_id })
                    .collect();
                events.push(Event::ChatListChanged);
                self.emit(events);
            }
            Err(e) => log::error!("delete messages failed: {e:#}"),
        }
    }

    pub async fn resend(&self, msg_ids: Vec<u32>) -> Result<(), CoreError> {
        let mut conn = self.db();
        let tx = conn.transaction()?;
        let mut chats = Vec::new();
        for id in msg_ids {
            let row: Option<(u32, Option<u32>, i64)> = tx
                .query_row(
                    "SELECT m.chat_id, m.contact_id, c.kind FROM messages m JOIN chats c ON c.id=m.chat_id WHERE m.id=?",
                    [id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )
                .optional()?;
            let Some((chat_id, None, CHAT_CONTACT | CHAT_GROUP)) = row else {
                continue;
            };
            let kind: i64 =
                tx.query_row("SELECT kind FROM messages WHERE id=?", [id], |r| r.get(0))?;
            if kind == MessageKind::System.code() || !crate::packet::sendable(&tx, id)? {
                continue;
            }
            self.chat_target_for_send(&tx, chat_id)?;
            tx.execute("DELETE FROM send_queue WHERE msg_id=?", [id])?;
            tx.execute("DELETE FROM sent_notices WHERE msg_id=?", [id])?;
            tx.execute(
                "UPDATE messages SET state=?, error=NULL WHERE id=?",
                params![MessageState::Pending.code(), id],
            )?;
            self.queue_message(&tx, id)?;
            chats.push(chat_id);
        }
        tx.commit()?;
        drop(conn);
        info!("resending {} message(s)", chats.len());
        chats.dedup();
        let mut events: Vec<Event> = chats
            .into_iter()
            .map(|chat_id| Event::MessagesChanged { chat_id })
            .collect();
        events.push(Event::ChatListChanged);
        self.emit(events);
        Ok(())
    }

    pub fn set_saved(&self, msg_id: u32, saved: bool) -> Result<(), CoreError> {
        let conn = self.db();
        let self_chat = special_chat(&conn, CHAT_SELF)?;
        let chat_id: u32 = conn
            .query_row("SELECT chat_id FROM messages WHERE id=?", [msg_id], |r| {
                r.get(0)
            })
            .optional()?
            .context("Message not found")?;
        let files = if saved {
            let exists: bool = conn.query_row(
                "SELECT EXISTS(SELECT 1 FROM messages WHERE original_id=?)",
                [msg_id],
                |r| r.get(0),
            )?;
            if !exists && chat_id != self_chat {
                let m = self
                    .load_messages(&conn, "m.id=?", [msg_id])?
                    .pop()
                    .context("Message not found")?;
                let file = match &m.file_path {
                    Some(f) => Some(self.copy_blob(f)?),
                    None => None,
                };
                conn.execute(
                    "INSERT INTO messages (chat_id, contact_id, env_id, kind, text, time, state, file, file_name,
                        mime, size, width, height, duration, vcard_name, vcard_account, forwarded, original_id)
                     SELECT ?, contact_id, ?, kind, text, time, ?, ?, file_name, mime, size, width, height, duration,
                        vcard_name, vcard_account, forwarded, id FROM messages WHERE id=?",
                    params![self_chat, protocol::new_id(), MessageState::Delivered.code(), file, msg_id],
                )?;
            }
            Vec::new()
        } else if chat_id == self_chat {
            self.delete_messages_where(&conn, "id=?", [msg_id])?
        } else {
            self.delete_messages_where(&conn, "original_id=?", [msg_id])?
        };
        drop(conn);
        self.remove_unused(files);
        self.emit(vec![
            Event::MessagesChanged { chat_id },
            Event::MessagesChanged { chat_id: self_chat },
            Event::ChatListChanged,
        ]);
        Ok(())
    }

    pub fn add_device_message(&self, text: String) -> Result<u32, CoreError> {
        let conn = self.db();
        let chat_id = special_chat(&conn, CHAT_DEVICE)?;
        conn.execute(
            "INSERT INTO messages (chat_id, env_id, kind, text, time, state) VALUES (?,?,?,?,?,?)",
            params![
                chat_id,
                protocol::new_id(),
                MessageKind::Text.code(),
                text,
                now_ms(),
                MessageState::Incoming.code()
            ],
        )?;
        let msg_id = conn.last_insert_rowid() as u32;
        drop(conn);
        self.emit(vec![
            Event::IncomingMessage { chat_id, msg_id },
            Event::MessagesChanged { chat_id },
            Event::ChatListChanged,
        ]);
        Ok(msg_id)
    }

    pub async fn set_disappearing(&self, chat_id: u32, seconds: u32) -> Result<(), CoreError> {
        let mut conn = self.db();
        let tx = conn.transaction()?;
        let t = self.chat_target_for_send(&tx, chat_id)?;
        let mut events = Vec::new();
        self.apply_timer(&tx, chat_id, None, seconds, &mut events)?;
        if matches!(t.kind, CHAT_CONTACT | CHAT_GROUP) && !events.is_empty() {
            let mut env = self.envelope(&tx, Body::Timer)?;
            env.expires = seconds;
            self.fan_out(&tx, chat_id, t.grp, env)?;
        }
        tx.commit()?;
        drop(conn);
        self.emit(events);
        Ok(())
    }

    pub fn mark_read(&self, chat_id: u32) {
        let result = (|| -> Result<bool> {
            let mut conn = self.db();
            let tx = conn.transaction()?;
            let t = target(&tx, chat_id)?;
            let now = now_ms();
            let read: Vec<(String, Option<u32>)> = {
                let mut stmt = tx.prepare(
                    "UPDATE messages SET state=?1,
                        expires_at=CASE WHEN ephemeral>0 THEN ?4 + ephemeral*1000 END
                     WHERE chat_id=?2 AND state=?3 RETURNING env_id, contact_id",
                )?;
                let rows = stmt.query_map(
                    params![
                        MessageState::Seen.code(),
                        chat_id,
                        MessageState::Incoming.code(),
                        now
                    ],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )?;
                rows.collect::<Result<_, _>>()?
            };
            if read.is_empty() {
                return Ok(false);
            }
            let send = match t.kind {
                CHAT_CONTACT => t.allowed && !t.blocked,
                CHAT_GROUP => t.member && !t.request,
                _ => false,
            };
            if send && settings::read_receipts(&tx) {
                let mut by_sender: Vec<(u32, Vec<String>)> = Vec::new();
                for (env_id, contact) in read {
                    let Some(c) = contact else { continue };
                    match by_sender.iter_mut().find(|(id, _)| *id == c) {
                        Some((_, list)) => list.push(env_id),
                        None => by_sender.push((c, vec![env_id])),
                    }
                }
                for (contact_id, targets) in by_sender {
                    let to: Option<String> = tx
                        .query_row(
                            "SELECT account FROM contacts WHERE id=? AND blocked=0",
                            [contact_id],
                            |r| r.get(0),
                        )
                        .optional()?;
                    if let Some(to) = to {
                        let mut env = self.envelope(&tx, Body::Read { targets })?;
                        env.group = t.grp.clone();
                        self.enqueue(&tx, &to, None, &env, &[])?;
                    }
                }
            }
            tx.commit()?;
            Ok(true)
        })();
        match result {
            Ok(true) => {
                self.wake_expiry();
                self.emit(vec![
                    Event::MessagesChanged { chat_id },
                    Event::ChatListChanged,
                ]);
            }
            Ok(false) => {}
            Err(e) => log::error!("mark_read {chat_id} failed: {e:#}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_names() {
        assert_eq!(sanitize("/tmp/x/photo.jpg"), "photo.jpg");
        assert_eq!(sanitize("..\u{0}evil"), "evil");
        assert_eq!(sanitize(""), "file");
        assert_eq!(sanitize(&format!("{}.png", "a".repeat(200))).len(), 80);
    }
}
