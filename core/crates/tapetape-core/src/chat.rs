use std::path::Path;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, info};
use rusqlite::{params, Connection, OptionalExtension, Row};

use crate::account::Account;
use crate::api::*;
use crate::protocol::{now_ms, Body};
use crate::store;

pub(crate) const CHAT_CONTACT: i64 = 0;
pub(crate) const CHAT_SELF: i64 = 1;
pub(crate) const CHAT_DEVICE: i64 = 2;
pub(crate) const CHAT_GROUP: i64 = 3;

pub(crate) fn abs(dir: &Path, rel: Option<String>) -> Option<String> {
    rel.map(|r| dir.join(r).to_string_lossy().into_owned())
}

pub(crate) fn live(alias: &str) -> String {
    format!(
        "({alias}.expires_at IS NULL OR {alias}.expires_at > {})",
        now_ms()
    )
}

pub(crate) fn display_name(local: &str, name: &str, account: &str) -> String {
    if !local.is_empty() {
        local.to_string()
    } else if !name.is_empty() {
        name.to_string()
    } else {
        taperelay::self_key_of(account)
            .unwrap_or(account)
            .to_string()
    }
}

pub(crate) fn upsert_contact(conn: &Connection, account: &str, name: &str) -> Result<(u32, bool)> {
    let existing: Option<(u32, String)> = conn
        .query_row(
            "SELECT id, name FROM contacts WHERE account=?",
            [account],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    match existing {
        Some((id, old)) if name.is_empty() || old == name => Ok((id, false)),
        Some((id, _)) => {
            conn.execute("UPDATE contacts SET name=? WHERE id=?", params![name, id])?;
            Ok((id, true))
        }
        None => {
            conn.execute(
                "INSERT INTO contacts (account, name, allow_synced) VALUES (?,?,1)",
                [account, name],
            )?;
            debug!("new contact {account}");
            Ok((conn.last_insert_rowid() as u32, true))
        }
    }
}

pub(crate) fn upsert_member(conn: &Connection, account: &str, name: &str) -> Result<u32> {
    conn.execute(
        "INSERT INTO contacts (account, name, allow_synced) VALUES (?1,?2,1)
         ON CONFLICT(account) DO UPDATE SET name=excluded.name WHERE contacts.name=''",
        [account, name],
    )?;
    Ok(
        conn.query_row("SELECT id FROM contacts WHERE account=?", [account], |r| {
            r.get(0)
        })?,
    )
}

pub(crate) fn recipients(conn: &Connection, chat_id: u32) -> Result<Vec<String>> {
    let mut stmt = conn.prepare(
        "SELECT ct.account FROM chats c JOIN contacts ct ON ct.id=c.contact_id WHERE c.id=?1 AND c.kind=0
         UNION SELECT ct.account FROM members g JOIN contacts ct ON ct.id=g.contact_id WHERE g.chat_id=?1",
    )?;
    let rows = stmt.query_map([chat_id], |r| r.get(0))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

pub(crate) fn chat_for_contact(conn: &Connection, contact_id: u32) -> Result<u32> {
    if let Some(id) = conn
        .query_row(
            "SELECT id FROM chats WHERE contact_id=?",
            [contact_id],
            |r| r.get(0),
        )
        .optional()?
    {
        return Ok(id);
    }
    conn.execute(
        "INSERT INTO chats (contact_id, created) VALUES (?,?)",
        params![contact_id, now_ms()],
    )?;
    Ok(conn.last_insert_rowid() as u32)
}

pub(crate) fn special_chat(conn: &Connection, kind: i64) -> Result<u32> {
    if let Some(id) = conn
        .query_row("SELECT id FROM chats WHERE kind=?", [kind], |r| r.get(0))
        .optional()?
    {
        return Ok(id);
    }
    conn.execute(
        "INSERT INTO chats (kind, created) VALUES (?,?)",
        params![kind, now_ms()],
    )?;
    Ok(conn.last_insert_rowid() as u32)
}

/// 通过一个联系人。返回：这次才刚通过的话，返回他的账号（调用方据此把自己的头像、签名档补发给他）
pub(crate) fn allow_contact(conn: &Connection, contact_id: u32) -> Result<Option<String>> {
    let newly: Option<(String, bool)> = conn
        .query_row(
            "SELECT account, allowed FROM contacts WHERE id=?",
            [contact_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    conn.execute(
        "UPDATE contacts SET allowed=1, allow_synced=0, hidden=0 WHERE id=? AND (allowed=0 OR hidden=1)",
        [contact_id],
    )?;
    Ok(newly.and_then(|(account, allowed)| (!allowed).then_some(account)))
}

/// 接受一个群。返回：因此才刚通过的成员账号
pub(crate) fn accept_group(conn: &Connection, chat_id: u32) -> Result<Vec<String>> {
    conn.execute("UPDATE chats SET request=0 WHERE id=?", [chat_id])?;
    let mut stmt = conn.prepare(
        "SELECT account FROM contacts WHERE allowed=0 AND id IN (SELECT contact_id FROM members WHERE chat_id=?)",
    )?;
    let newly: Vec<String> = stmt.query_map([chat_id], |r| r.get(0))?.collect::<Result<_, _>>()?;
    conn.execute(
        "UPDATE contacts SET allowed=1, allow_synced=0
         WHERE allowed=0 AND id IN (SELECT contact_id FROM members WHERE chat_id=?)",
        [chat_id],
    )?;
    Ok(newly)
}

pub(crate) fn chat_title(conn: &Connection, chat_id: u32) -> Result<(String, bool, bool)> {
    Ok(conn.query_row(
        "SELECT c.kind, c.name, COALESCE(ct.local_name,''), COALESCE(ct.name,''), COALESCE(ct.account,''), c.muted_until
         FROM chats c LEFT JOIN contacts ct ON ct.id=c.contact_id WHERE c.id=?",
        [chat_id],
        |r| {
            let kind: i64 = r.get(0)?;
            Ok((
                chat_name(
                    kind,
                    &r.get::<_, String>(1)?,
                    &r.get::<_, String>(2)?,
                    &r.get::<_, String>(3)?,
                    &r.get::<_, String>(4)?,
                ),
                kind == CHAT_GROUP,
                mute_state(r.get(5)?).0,
            ))
        },
    )?)
}

pub(crate) struct Target {
    pub kind: i64,
    pub contact_id: Option<u32>,
    pub account: Option<String>,
    pub allowed: bool,
    pub blocked: bool,
    pub ephemeral: u32,
    pub grp: Option<String>,
    pub member: bool,
    pub joined: bool,
    pub request: bool,
}

pub(crate) fn target(conn: &Connection, chat_id: u32) -> Result<Target> {
    conn.query_row(
        "SELECT c.kind, c.contact_id, ct.account, COALESCE(ct.allowed,0), COALESCE(ct.blocked,0), c.ephemeral,
                c.grp, EXISTS(SELECT 1 FROM members g WHERE g.chat_id=c.id AND g.contact_id=0), c.ver, c.request
         FROM chats c LEFT JOIN contacts ct ON ct.id=c.contact_id WHERE c.id=?",
        [chat_id],
        |r| {
            Ok(Target {
                kind: r.get(0)?,
                contact_id: r.get(1)?,
                account: r.get(2)?,
                allowed: r.get(3)?,
                blocked: r.get(4)?,
                ephemeral: r.get(5)?,
                grp: r.get(6)?,
                member: r.get(7)?,
                joined: r.get::<_, i64>(8)? > 0,
                request: r.get(9)?,
            })
        },
    )
    .optional()?
    .context("Chat not found")
}

const CONTACT_COLUMNS: &str =
    "id, account, name, local_name, avatar, verified, bio, last_seen, blocked";

fn contact_row(dir: &Path, r: &Row) -> rusqlite::Result<Contact> {
    let account: String = r.get(1)?;
    Ok(Contact {
        id: r.get(0)?,
        address: taperelay::self_key_of(&account)
            .unwrap_or_default()
            .to_string(),
        name: display_name(&r.get::<_, String>(3)?, &r.get::<_, String>(2)?, &account),
        account,
        avatar_path: abs(dir, r.get(4)?),
        verified: r.get(5)?,
        bio: r.get(6)?,
        last_seen: r.get(7)?,
        blocked: r.get(8)?,
    })
}

fn chat_name(kind: i64, group: &str, local: &str, name: &str, account: &str) -> String {
    match kind {
        CHAT_SELF => "Saved Messages".into(),
        CHAT_DEVICE => "Device Messages".into(),
        CHAT_GROUP => group.into(),
        _ => display_name(local, name, account),
    }
}

pub(crate) fn mute_state(muted_until: Option<i64>) -> (bool, Option<i64>) {
    match muted_until {
        Some(-1) => (true, None),
        Some(t) if t > now_ms() => (true, Some(t)),
        _ => (false, None),
    }
}

impl Account {
    pub(crate) fn open_chat_with(&self, account: &str, name: &str) -> Result<(u32, u32)> {
        ensure!(
            taperelay::is_self_account(account),
            "Invalid account {account}"
        );
        ensure!(account != self.keys.account(), "This is your own account");
        let conn = self.db();
        let (contact_id, _) = upsert_contact(&conn, account, name)?;
        conn.execute("UPDATE contacts SET hidden=0 WHERE id=?", [contact_id])?;
        Ok((chat_for_contact(&conn, contact_id)?, contact_id))
    }

    fn summaries(&self, filter: &str, query: Option<String>) -> Result<Vec<ChatSummary>> {
        let conn = self.db();
        let sql = format!(
            "SELECT c.id, c.kind, COALESCE(ct.name,''), COALESCE(ct.local_name,''), COALESCE(ct.account,''),
                    COALESCE(ct.avatar, c.avatar), c.pinned, c.muted_until, c.draft, c.archived,
                    m.text, m.time, m.state, m.kind, m.contact_id IS NULL AND m.id IS NOT NULL AND c.kind IN (0,3),
                    (SELECT COUNT(*) FROM messages u WHERE u.chat_id=c.id AND u.state=?1 AND {}),
                    COALESCE(m.time, c.created) AS t, COALESCE(ct.allowed,1), ct.last_seen,
                    EXISTS(SELECT 1 FROM messages i WHERE i.chat_id=c.id AND i.contact_id IS NOT NULL),
                    c.name, c.request, ms.id, COALESCE(ms.local_name,''), COALESCE(ms.name,''), COALESCE(ms.account,'')
             FROM chats c
             LEFT JOIN contacts ct ON ct.id=c.contact_id
             LEFT JOIN messages m ON m.id=(SELECT MAX(x.id) FROM messages x WHERE x.chat_id=c.id AND {})
             LEFT JOIN contacts ms ON ms.id=m.contact_id
             WHERE {filter} AND COALESCE(ct.blocked,0)=0
             ORDER BY c.pinned DESC, t DESC",
            live("u"),
            live("x")
        );
        let mut stmt = conn.prepare(&sql)?;
        let rows = stmt.query_map(params![MessageState::Incoming.code()], |r| {
            let kind: i64 = r.get(1)?;
            let account: String = r.get(4)?;
            let allowed: bool = r.get(17)?;
            let has_incoming: bool = r.get(19)?;
            let request: bool = r.get(21)?;
            let last_sender = match (kind, r.get::<_, Option<u32>>(22)?) {
                (CHAT_GROUP, Some(_)) => Some(display_name(
                    &r.get::<_, String>(23)?,
                    &r.get::<_, String>(24)?,
                    &r.get::<_, String>(25)?,
                )),
                _ => None,
            };
            Ok(ChatSummary {
                id: r.get(0)?,
                name: chat_name(
                    kind,
                    &r.get::<_, String>(20)?,
                    &r.get::<_, String>(3)?,
                    &r.get::<_, String>(2)?,
                    &account,
                ),
                avatar_path: abs(&self.dir, r.get(5)?),
                is_group: kind == CHAT_GROUP,
                pinned: r.get(6)?,
                muted: mute_state(r.get(7)?).0,
                draft: r.get(8)?,
                archived: r.get(9)?,
                last_text: r.get(10)?,
                last_state: r.get::<_, Option<i64>>(12)?.map(MessageState::from_code),
                last_kind: r.get::<_, Option<i64>>(13)?.map(MessageKind::from_code),
                last_from_me: r.get(14)?,
                unread: r.get(15)?,
                last_time: r.get(16)?,
                is_self: kind == CHAT_SELF,
                is_device: kind == CHAT_DEVICE,
                is_request: (kind == CHAT_CONTACT && !allowed && has_incoming) || request,
                last_seen: r.get(18)?,
                last_sender,
            })
        })?;
        let all: Vec<ChatSummary> = rows.collect::<Result<_, _>>()?;
        let Some(q) = query
            .map(|q| q.trim().to_lowercase())
            .filter(|q| !q.is_empty())
        else {
            return Ok(all);
        };
        Ok(all
            .into_iter()
            .filter(|c| c.name.to_lowercase().contains(&q))
            .collect())
    }

    fn chat_events(&self) -> Vec<Event> {
        vec![Event::ChatListChanged]
    }

    fn update_chat(&self, chat_id: u32, sql: &str, value: rusqlite::types::Value) -> Result<()> {
        let n = self.db().execute(sql, params![value, chat_id])?;
        ensure!(n > 0, "Chat not found");
        self.emit(self.chat_events());
        Ok(())
    }

    /// 联系人刚被通过：把自己的头像、签名档补发给他们（之前只在修改资料和邀请加好友时发，后来才通过的联系人一直看不到）。
    /// 自己没设头像也没写签名档就不发
    pub(crate) fn share_profile(&self, conn: &Connection, accounts: impl IntoIterator<Item = String>) -> Result<()> {
        let has_bio = store::get_config(conn, "bio")?.is_some_and(|b| !b.is_empty());
        let has_avatar = store::get_config(conn, "avatar")?.is_some();
        if !has_bio && !has_avatar {
            return Ok(());
        }
        for a in accounts {
            self.send_profile(conn, Some(&a))?;
        }
        Ok(())
    }

    pub(crate) fn send_profile(&self, conn: &Connection, to: Option<&str>) -> Result<()> {
        let avatar = store::get_config(conn, "avatar")?;
        let bio = store::get_config(conn, "bio")?.unwrap_or_default();
        let avatar = abs(&self.dir, avatar);
        let (mime, blob) = match &avatar {
            Some(path) => match std::fs::read(path) {
                Ok(bytes) => (
                    Some(
                        mime_guess::from_path(path)
                            .first_or_octet_stream()
                            .to_string(),
                    ),
                    bytes,
                ),
                Err(_) => (None, Vec::new()),
            },
            None => (None, Vec::new()),
        };
        let recipients: Vec<String> = match to {
            Some(a) => vec![a.to_string()],
            None => {
                let mut stmt =
                    conn.prepare("SELECT account FROM contacts WHERE allowed=1 AND blocked=0")?;
                let rows = stmt.query_map([], |r| r.get(0))?;
                rows.collect::<Result<_, _>>()?
            }
        };
        for r in &recipients {
            let env = self.envelope(
                conn,
                Body::Profile {
                    avatar: mime.clone(),
                    bio: (!bio.is_empty()).then(|| bio.clone()),
                },
            )?;
            self.enqueue(conn, r, None, &env, &blob)?;
        }
        debug!("queued profile for {} contact(s)", recipients.len());
        Ok(())
    }

    pub(crate) fn receive_profile(
        &self,
        conn: &Connection,
        contact_id: u32,
        avatar: Option<&str>,
        bio: Option<&str>,
        blob: &[u8],
        events: &mut Vec<Event>,
    ) -> Result<()> {
        let old: Option<String> = conn.query_row(
            "SELECT avatar FROM contacts WHERE id=?",
            [contact_id],
            |r| r.get(0),
        )?;
        let new = match avatar {
            Some(mime) if !blob.is_empty() => {
                let ext = mime_guess::get_mime_extensions_str(mime)
                    .and_then(|e| e.first())
                    .unwrap_or(&"jpg");
                Some(self.save_blob(&format!("avatar.{ext}"), blob)?)
            }
            _ => None,
        };
        if let Some(old) = abs(&self.dir, old) {
            let _ = std::fs::remove_file(old);
        }
        conn.execute(
            "UPDATE contacts SET avatar=?, bio=? WHERE id=?",
            params![new, bio.unwrap_or_default(), contact_id],
        )?;
        debug!("profile of contact {contact_id} updated");
        events.extend([Event::ContactsChanged, Event::ChatListChanged]);
        Ok(())
    }
}

#[uniffi::export]
impl Account {
    pub fn chats(&self, query: Option<String>) -> Vec<ChatSummary> {
        self.summaries("c.archived=0", query).unwrap_or_else(|e| {
            log::error!("chats query failed: {e:#}");
            Vec::new()
        })
    }

    pub fn archived_chats(&self) -> Vec<ChatSummary> {
        self.summaries("c.archived=1", None).unwrap_or_else(|e| {
            log::error!("archived chats query failed: {e:#}");
            Vec::new()
        })
    }

    pub fn chat(&self, chat_id: u32) -> Result<Chat, CoreError> {
        let conn = self.db();
        Ok(conn
            .query_row(
                "SELECT c.id, c.kind, COALESCE(ct.name,''), COALESCE(ct.local_name,''), COALESCE(ct.account,''),
                        COALESCE(ct.avatar, c.avatar), ct.id, COALESCE(ct.verified,0), COALESCE(ct.allowed,1),
                        COALESCE(ct.blocked,0), c.archived, c.pinned, c.muted_until, c.ephemeral,
                        EXISTS(SELECT 1 FROM messages i WHERE i.chat_id=c.id AND i.contact_id IS NOT NULL),
                        c.name, c.request, c.ver,
                        EXISTS(SELECT 1 FROM members g WHERE g.chat_id=c.id AND g.contact_id=0),
                        (SELECT COUNT(*) FROM members g WHERE g.chat_id=c.id)
                 FROM chats c LEFT JOIN contacts ct ON ct.id=c.contact_id WHERE c.id=?",
                [chat_id],
                |r| {
                    let kind: i64 = r.get(1)?;
                    let allowed: bool = r.get(8)?;
                    let blocked: bool = r.get(9)?;
                    let has_incoming: bool = r.get(14)?;
                    let (muted, muted_until) = mute_state(r.get(12)?);
                    let request: bool = r.get(16)?;
                    let joined = r.get::<_, i64>(17)? > 0 && r.get::<_, bool>(18)?;
                    Ok(Chat {
                        id: r.get(0)?,
                        name: chat_name(
                            kind,
                            &r.get::<_, String>(15)?,
                            &r.get::<_, String>(3)?,
                            &r.get::<_, String>(2)?,
                            &r.get::<_, String>(4)?,
                        ),
                        avatar_path: abs(&self.dir, r.get(5)?),
                        is_group: kind == CHAT_GROUP,
                        contact_id: r.get(6)?,
                        verified: r.get::<_, bool>(7)? || kind == CHAT_SELF,
                        is_self: kind == CHAT_SELF,
                        is_device: kind == CHAT_DEVICE,
                        is_request: (kind == CHAT_CONTACT && !allowed && has_incoming) || request,
                        can_send: match kind {
                            CHAT_DEVICE => false,
                            CHAT_GROUP => joined,
                            _ => !blocked,
                        },
                        archived: r.get(10)?,
                        pinned: r.get(11)?,
                        muted,
                        muted_until,
                        ephemeral_seconds: r.get(13)?,
                        blocked,
                        member_count: r.get(19)?,
                    })
                },
            )
            .optional()?
            .context("Chat not found")?)
    }

    pub fn set_draft(&self, chat_id: u32, text: Option<String>) {
        let text = text.filter(|t| !t.trim().is_empty());
        let changed = self
            .db()
            .execute(
                "UPDATE chats SET draft=? WHERE id=? AND draft IS NOT ?",
                params![text, chat_id, text],
            )
            .unwrap_or(0);
        if changed > 0 {
            self.emit(vec![Event::ChatListChanged]);
        }
    }

    pub fn set_pinned(&self, chat_id: u32, pinned: bool) -> Result<(), CoreError> {
        Ok(self.update_chat(
            chat_id,
            "UPDATE chats SET pinned=? WHERE id=?",
            (pinned as i64).into(),
        )?)
    }

    pub fn mute(&self, chat_id: u32, until: Option<i64>) -> Result<(), CoreError> {
        Ok(self.update_chat(
            chat_id,
            "UPDATE chats SET muted_until=? WHERE id=?",
            until.unwrap_or(-1).into(),
        )?)
    }

    pub fn unmute(&self, chat_id: u32) -> Result<(), CoreError> {
        Ok(self.update_chat(
            chat_id,
            "UPDATE chats SET muted_until=? WHERE id=?",
            rusqlite::types::Value::Null,
        )?)
    }

    pub fn set_archived(&self, chat_id: u32, archived: bool) -> Result<(), CoreError> {
        Ok(self.update_chat(
            chat_id,
            "UPDATE chats SET archived=? WHERE id=?",
            (archived as i64).into(),
        )?)
    }

    pub fn delete_chat(&self, chat_id: u32) -> Result<(), CoreError> {
        let conn = self.db();
        target(&conn, chat_id)?;
        let files = self.delete_messages_where(&conn, "chat_id=?", [chat_id])?;
        conn.execute("DELETE FROM members WHERE chat_id=?", [chat_id])?;
        conn.execute("DELETE FROM chats WHERE id=?", [chat_id])?;
        drop(conn);
        self.remove_unused(files);
        info!("deleted chat {chat_id}");
        self.emit(vec![
            Event::ChatListChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }

    pub fn clear_chat(&self, chat_id: u32) -> Result<(), CoreError> {
        let conn = self.db();
        target(&conn, chat_id)?;
        let files = self.delete_messages_where(&conn, "chat_id=?", [chat_id])?;
        drop(conn);
        self.remove_unused(files);
        info!("cleared chat {chat_id}");
        self.emit(vec![
            Event::ChatListChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }

    pub fn mark_all_read(&self, archived: bool) {
        let ids: Vec<u32> = self
            .summaries(
                if archived {
                    "c.archived=1"
                } else {
                    "c.archived=0"
                },
                None,
            )
            .unwrap_or_default()
            .into_iter()
            .filter(|c| c.unread > 0)
            .map(|c| c.id)
            .collect();
        for id in ids {
            self.mark_read(id);
        }
    }

    pub fn accept_chat(&self, chat_id: u32) -> Result<(), CoreError> {
        let conn = self.db();
        let t = target(&conn, chat_id)?;
        match t.contact_id {
            Some(contact_id) => self.share_profile(&conn, allow_contact(&conn, contact_id)?)?,
            None => self.share_profile(&conn, accept_group(&conn, chat_id)?)?,
        }
        drop(conn);
        self.wake_sender();
        info!("accepted chat {chat_id}");
        self.emit(vec![Event::ChatListChanged, Event::ContactsChanged]);
        Ok(())
    }

    pub fn saved_messages_chat(&self) -> Result<u32, CoreError> {
        let id = special_chat(&self.db(), CHAT_SELF)?;
        Ok(id)
    }

    pub fn encryption_info(&self, chat_id: u32) -> Result<String, CoreError> {
        let conn = self.db();
        let t = target(&conn, chat_id)?;
        let mine = hex::encode(self.keys.enc.public());
        let mut text = format!(
            "End-to-end encrypted (X25519 + XChaCha20-Poly1305, TapeRelay sealed messages).\n\nYour key:\n{}\n{mine}",
            self.keys.account()
        );
        if let Some(account) = t.account {
            let theirs: Option<Vec<u8>> = conn
                .query_row(
                    "SELECT key FROM peer_keys WHERE account=?",
                    [&account],
                    |r| r.get(0),
                )
                .optional()?;
            text += &format!(
                "\n\nContact key:\n{account}\n{}",
                theirs.map(hex::encode).unwrap_or_else(|| "-".into())
            );
        }
        Ok(text)
    }

    pub fn contacts(&self) -> Vec<Contact> {
        self.contact_list("blocked=0 AND hidden=0")
            .unwrap_or_else(|e| {
                log::error!("contacts query failed: {e:#}");
                Vec::new()
            })
    }

    pub fn blocked_contacts(&self) -> Vec<Contact> {
        self.contact_list("blocked=1").unwrap_or_else(|e| {
            log::error!("blocked contacts query failed: {e:#}");
            Vec::new()
        })
    }

    pub fn contact(&self, contact_id: u32) -> Result<Contact, CoreError> {
        let conn = self.db();
        Ok(conn
            .query_row(
                &format!("SELECT {CONTACT_COLUMNS} FROM contacts WHERE id=?"),
                [contact_id],
                |r| contact_row(&self.dir, r),
            )
            .optional()?
            .context("Contact not found")?)
    }

    pub fn create_chat(&self, account: String, name: String) -> Result<u32, CoreError> {
        if account == self.keys.account() {
            return self.saved_messages_chat();
        }
        let (chat_id, _) = self.open_chat_with(&account, &name)?;
        self.emit(vec![Event::ChatListChanged, Event::ContactsChanged]);
        Ok(chat_id)
    }

    pub fn chat_for_contact(&self, contact_id: u32) -> Result<u32, CoreError> {
        let conn = self.db();
        let exists: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM contacts WHERE id=?)",
            [contact_id],
            |r| r.get(0),
        )?;
        if !exists {
            return Err(anyhow::anyhow!("Contact not found").into());
        }
        let id = chat_for_contact(&conn, contact_id)?;
        drop(conn);
        self.emit(vec![Event::ChatListChanged]);
        Ok(id)
    }

    pub fn set_contact_name(&self, contact_id: u32, name: String) -> Result<(), CoreError> {
        let n = self.db().execute(
            "UPDATE contacts SET local_name=? WHERE id=?",
            params![name.trim(), contact_id],
        )?;
        if n == 0 {
            return Err(anyhow::anyhow!("Contact not found").into());
        }
        self.emit(vec![Event::ContactsChanged, Event::ChatListChanged]);
        Ok(())
    }

    pub fn shared_chats(&self, contact_id: u32) -> Vec<ChatSummary> {
        self.summaries(
            &format!(
                "c.kind={CHAT_GROUP} AND c.id IN (SELECT chat_id FROM members WHERE contact_id={contact_id})"
            ),
            None,
        )
        .unwrap_or_else(|e| {
            log::error!("shared chats query failed: {e:#}");
            Vec::new()
        })
    }

    pub fn delete_contact(&self, contact_id: u32) -> Result<(), CoreError> {
        let n = self.db().execute(
            "UPDATE contacts SET hidden=1, allowed=0, verified=0, allow_synced=0 WHERE id=?",
            [contact_id],
        )?;
        if n == 0 {
            return Err(anyhow::anyhow!("Contact not found").into());
        }
        self.wake_sender();
        info!("contact {contact_id} deleted");
        self.emit(vec![Event::ContactsChanged, Event::ChatListChanged]);
        Ok(())
    }

    pub fn block_contact(&self, contact_id: u32) -> Result<(), CoreError> {
        self.set_blocked(contact_id, true)
    }

    pub fn unblock_contact(&self, contact_id: u32) -> Result<(), CoreError> {
        self.set_blocked(contact_id, false)
    }

    pub async fn set_bio(&self, bio: String) -> Result<(), CoreError> {
        let conn = self.db();
        store::set_config(&conn, "bio", bio.trim())?;
        self.send_profile(&conn, None)?;
        info!("account {} bio updated", self.id);
        Ok(())
    }
}

impl Account {
    fn contact_list(&self, filter: &str) -> Result<Vec<Contact>> {
        let conn = self.db();
        let mut stmt = conn.prepare(&format!(
            "SELECT {CONTACT_COLUMNS} FROM contacts WHERE {filter}
             ORDER BY COALESCE(NULLIF(local_name,''), NULLIF(name,''), account) COLLATE NOCASE"
        ))?;
        let rows = stmt.query_map([], |r| contact_row(&self.dir, r))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    fn set_blocked(&self, contact_id: u32, blocked: bool) -> Result<(), CoreError> {
        let n = self.db().execute(
            "UPDATE contacts SET blocked=?, allow_synced=0 WHERE id=?",
            params![blocked, contact_id],
        )?;
        if n == 0 {
            return Err(anyhow::anyhow!("Contact not found").into());
        }
        self.wake_sender();
        info!("contact {contact_id} blocked={blocked}");
        self.emit(vec![Event::ContactsChanged, Event::ChatListChanged]);
        Ok(())
    }

    pub(crate) fn chat_target_for_send(&self, conn: &Connection, chat_id: u32) -> Result<Target> {
        let t = target(conn, chat_id)?;
        if t.kind == CHAT_DEVICE {
            bail!("You cannot write to this chat");
        }
        if t.blocked {
            bail!("This contact is blocked");
        }
        if t.kind == CHAT_GROUP && !(t.member && t.joined) {
            bail!("You are not a member of this group");
        }
        Ok(t)
    }
}
