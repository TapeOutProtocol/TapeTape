use std::collections::BTreeSet;

use anyhow::{ensure, Context, Result};
use log::{debug, info, warn};
use rusqlite::{params, Connection, OptionalExtension};

use crate::account::Account;
use crate::api::*;
use crate::chat::{abs, allow_contact, display_name, upsert_contact, upsert_member, CHAT_GROUP};
use crate::invite::make_link;
use crate::protocol::{self, now_ms, Body, Envelope};
use crate::transport::percent_encode;

struct Group {
    grp: String,
    name: String,
    avatar: Option<String>,
    ver: i64,
    request: bool,
}

fn group(conn: &Connection, chat_id: u32) -> Result<Group> {
    conn.query_row(
        "SELECT grp, name, avatar, ver, request FROM chats WHERE id=? AND kind=?",
        params![chat_id, CHAT_GROUP],
        |r| {
            Ok(Group {
                grp: r.get(0)?,
                name: r.get(1)?,
                avatar: r.get(2)?,
                ver: r.get(3)?,
                request: r.get(4)?,
            })
        },
    )
    .optional()?
    .context("Group not found")
}

fn members(conn: &Connection, chat_id: u32) -> Result<BTreeSet<u32>> {
    let mut stmt = conn.prepare("SELECT contact_id FROM members WHERE chat_id=?")?;
    let rows = stmt.query_map([chat_id], |r| r.get(0))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Fails with `group-full` when the group has more than MAX_GROUP_MEMBERS members (counting yourself).
fn check_group_size(conn: &Connection, chat_id: u32) -> Result<()> {
    let n: usize = conn.query_row(
        "SELECT COUNT(*) FROM members WHERE chat_id=?",
        [chat_id],
        |r| r.get(0),
    )?;
    anyhow::ensure!(n <= crate::protocol::MAX_GROUP_MEMBERS, "group-full");
    Ok(())
}

/// Whether `account` can join without going over MAX_GROUP_MEMBERS (an existing member always can).
fn can_join(conn: &Connection, chat_id: u32, account: &str) -> Result<bool> {
    let (n, already): (usize, bool) = conn.query_row(
        "SELECT COUNT(*), COALESCE(SUM(ct.account=?2), 0) > 0 FROM members g
         LEFT JOIN contacts ct ON ct.id=g.contact_id WHERE g.chat_id=?1",
        params![chat_id, account],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    Ok(already || n < crate::protocol::MAX_GROUP_MEMBERS)
}

fn require_member(conn: &Connection, chat_id: u32) -> Result<Group> {
    let g = group(conn, chat_id)?;
    ensure!(
        g.ver > 0 && members(conn, chat_id)?.contains(&0),
        "You are not a member of this group"
    );
    Ok(g)
}

pub(crate) fn contact_name(conn: &Connection, contact_id: u32) -> Result<String> {
    Ok(conn.query_row(
        "SELECT local_name, name, account FROM contacts WHERE id=?",
        [contact_id],
        |r| {
            Ok(display_name(
                &r.get::<_, String>(0)?,
                &r.get::<_, String>(1)?,
                &r.get::<_, String>(2)?,
            ))
        },
    )?)
}

impl Account {
    pub(crate) fn group_chat_of(
        &self,
        conn: &Connection,
        grp: &str,
        contact_id: u32,
    ) -> Result<Option<u32>> {
        Ok(conn
            .query_row(
                "SELECT c.id FROM chats c WHERE c.grp=? AND c.ver>0
                 AND EXISTS(SELECT 1 FROM members g WHERE g.chat_id=c.id AND g.contact_id=0)
                 AND EXISTS(SELECT 1 FROM members g WHERE g.chat_id=c.id AND g.contact_id=?)",
                params![grp, contact_id],
                |r| r.get(0),
            )
            .optional()?)
    }

    fn send_group_state(&self, conn: &Connection, chat_id: u32, extra: &[String]) -> Result<()> {
        let g = group(conn, chat_id)?;
        let ver = now_ms().max(g.ver + 1);
        conn.execute("UPDATE chats SET ver=? WHERE id=?", params![ver, chat_id])?;
        let rows: Vec<(u32, bool, Option<String>, Option<String>)> = {
            let mut stmt = conn.prepare(
                "SELECT g.contact_id, g.admin, ct.account, ct.name FROM members g
                 LEFT JOIN contacts ct ON ct.id=g.contact_id WHERE g.chat_id=? ORDER BY g.contact_id",
            )?;
            let rows = stmt.query_map([chat_id], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
            })?;
            rows.collect::<Result<_, _>>()?
        };
        let my_name = self.my_name(conn)?;
        let (mut members, mut admins, mut names) = (Vec::new(), Vec::new(), Vec::new());
        for (id, admin, account, name) in rows {
            let (account, name) = if id == 0 {
                (self.keys.account().to_string(), my_name.clone())
            } else {
                (account.unwrap_or_default(), name.unwrap_or_default())
            };
            if admin {
                admins.push(account.clone());
            }
            members.push(account);
            names.push(name);
        }
        let (avatar, blob) = match abs(&self.dir, g.avatar) {
            Some(path) => (
                Some(
                    mime_guess::from_path(&path)
                        .first_or_octet_stream()
                        .to_string(),
                ),
                std::fs::read(&path).unwrap_or_default(),
            ),
            None => (None, Vec::new()),
        };
        let mut env = self.envelope(
            conn,
            Body::GroupState {
                gname: g.name,
                members,
                admins,
                names,
                ver,
                avatar,
            },
        )?;
        env.group = Some(g.grp);
        let mut to = crate::chat::recipients(conn, chat_id)?;
        to.extend(extra.iter().cloned());
        for r in &to {
            self.enqueue(conn, r, None, &env, &blob)?;
        }
        debug!(
            "queued group state of chat {chat_id} for {} member(s)",
            to.len()
        );
        Ok(())
    }

    pub(crate) fn receive_group_hello(
        &self,
        conn: &Connection,
        env: &Envelope,
        grp: &str,
        token: &str,
        events: &mut Vec<Event>,
    ) -> Result<()> {
        let found: Option<(u32, Option<String>)> = conn
            .query_row(
                "SELECT id, invite FROM chats WHERE grp=? AND kind=?",
                params![grp, CHAT_GROUP],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let chat_id = match found {
            Some((id, Some(t))) if t == token && members(conn, id)?.contains(&0) => id,
            _ => {
                warn!("group hello from {} with an invalid token", env.from);
                return Ok(());
            }
        };
        if !can_join(conn, chat_id, &env.from)? {
            warn!("group chat {chat_id} is full; ignoring invite join from {}", env.from);
            return Ok(());
        }
        let (contact_id, _) = upsert_contact(conn, &env.from, &env.name)?;
        conn.execute("UPDATE contacts SET verified=1 WHERE id=?", [contact_id])?;
        self.share_profile(conn, allow_contact(conn, contact_id)?)?;
        if conn.execute(
            "INSERT OR IGNORE INTO members (chat_id, contact_id, admin) VALUES (?,?,1)",
            [chat_id, contact_id],
        )? > 0
        {
            let name = contact_name(conn, contact_id)?;
            self.add_system(
                conn,
                chat_id,
                None,
                SystemMessage::MemberAdded {
                    contact_id: Some(contact_id),
                    name,
                },
            )?;
        }
        let mut ok = self.envelope(conn, Body::HelloOk)?;
        ok.group = Some(grp.to_string());
        self.enqueue(conn, &env.from, None, &ok, &[])?;
        self.send_group_state(conn, chat_id, &[])?;
        info!("{} joined group chat {chat_id} by invite", env.from);
        events.extend([
            Event::ContactsChanged,
            Event::ChatListChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }

    pub(crate) fn receive_group_state(
        &self,
        conn: &Connection,
        env: &Envelope,
        sender: u32,
        blob: &[u8],
        events: &mut Vec<Event>,
    ) -> Result<()> {
        let Body::GroupState {
            gname,
            members: accounts,
            admins,
            names,
            ver,
            avatar,
        } = &env.body
        else {
            return Ok(());
        };
        let grp = env
            .group
            .as_deref()
            .context("group state without group id")?;
        if accounts.len() > crate::protocol::MAX_GROUP_MEMBERS {
            warn!(
                "group state for {grp} from {} has {} members, over the limit",
                env.from,
                accounts.len()
            );
            return Ok(());
        }
        let me = self.keys.account();
        let existing: Option<(u32, i64)> = conn
            .query_row(
                "SELECT id, ver FROM chats WHERE grp=? AND kind=?",
                params![grp, CHAT_GROUP],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let (chat_id, old_ver) = match existing {
            Some((_, old)) if *ver <= old => {
                debug!("stale group state for {grp} from {}", env.from);
                return Ok(());
            }
            Some((id, old)) => {
                let admin: bool = conn.query_row(
                    "SELECT EXISTS(SELECT 1 FROM members WHERE chat_id=? AND contact_id=? AND admin=1)",
                    [id, sender],
                    |r| r.get(0),
                )?;
                if !admin {
                    warn!("group state for {grp} from non-admin {}", env.from);
                    return Ok(());
                }
                (id, old)
            }
            None if !accounts.iter().any(|a| a == me) => {
                debug!("group state for unknown group {grp} without me");
                return Ok(());
            }
            None => {
                let allowed: bool =
                    conn.query_row("SELECT allowed FROM contacts WHERE id=?", [sender], |r| {
                        r.get(0)
                    })?;
                conn.execute(
                    "INSERT INTO chats (kind, grp, name, created, request) VALUES (?,?,?,?,?)",
                    params![CHAT_GROUP, grp, gname, now_ms(), !allowed],
                )?;
                (conn.last_insert_rowid() as u32, 0)
            }
        };
        let g = group(conn, chat_id)?;
        let before = members(conn, chat_id)?;
        let mut after = BTreeSet::new();
        conn.execute("DELETE FROM members WHERE chat_id=?", [chat_id])?;
        for (i, account) in accounts.iter().enumerate() {
            if !taperelay::is_self_account(account) {
                continue;
            }
            let id = if account == me {
                0
            } else {
                upsert_member(conn, account, names.get(i).map_or("", |n| n.as_str()))?
            };
            conn.execute(
                "INSERT OR IGNORE INTO members (chat_id, contact_id, admin) VALUES (?,?,?)",
                params![chat_id, id, admins.contains(account)],
            )?;
            after.insert(id);
        }
        let new_avatar = match avatar {
            Some(mime) if !blob.is_empty() => {
                let old = abs(&self.dir, g.avatar.clone()).and_then(|p| std::fs::read(p).ok());
                if old.as_deref() == Some(blob) {
                    g.avatar.clone()
                } else {
                    let ext = mime_guess::get_mime_extensions_str(mime)
                        .and_then(|e| e.first())
                        .unwrap_or(&"jpg");
                    Some(self.save_blob(&format!("group.{ext}"), blob)?)
                }
            }
            _ => None,
        };
        conn.execute(
            "UPDATE chats SET name=?, avatar=?, ver=? WHERE id=?",
            params![gname, new_avatar, ver, chat_id],
        )?;
        let from = Some(sender);
        let name_of = |id: u32| -> Result<String> {
            if id == 0 {
                self.my_name(conn)
            } else {
                contact_name(conn, id)
            }
        };
        let some = |id: u32| (id != 0).then_some(id);
        if old_ver == 0 {
            if after.contains(&0) {
                let name = self.my_name(conn)?;
                self.add_system(
                    conn,
                    chat_id,
                    from,
                    SystemMessage::MemberAdded {
                        contact_id: None,
                        name,
                    },
                )?;
            }
        } else {
            for &id in after.difference(&before) {
                let name = name_of(id)?;
                self.add_system(
                    conn,
                    chat_id,
                    from,
                    SystemMessage::MemberAdded {
                        contact_id: some(id),
                        name,
                    },
                )?;
            }
            for &id in before.difference(&after) {
                let system = if id == sender {
                    SystemMessage::MemberLeft
                } else {
                    SystemMessage::MemberRemoved {
                        contact_id: some(id),
                        name: name_of(id)?,
                    }
                };
                self.add_system(conn, chat_id, from, system)?;
            }
            if g.name != *gname {
                self.add_system(
                    conn,
                    chat_id,
                    from,
                    SystemMessage::GroupNameChanged {
                        name: gname.clone(),
                        old_name: g.name.clone(),
                    },
                )?;
            }
            if g.avatar != new_avatar {
                self.add_system(conn, chat_id, from, SystemMessage::GroupAvatarChanged)?;
            }
        }
        if g.avatar != new_avatar {
            if let Some(old) = abs(&self.dir, g.avatar) {
                let _ = std::fs::remove_file(old);
            }
        }
        if !g.request {
            self.share_profile(conn, crate::chat::accept_group(conn, chat_id)?)?;
            self.wake_sender();
        }
        info!(
            "group chat {chat_id} updated by {}: {} member(s)",
            env.from,
            after.len()
        );
        events.extend([
            Event::ContactsChanged,
            Event::ChatListChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }

    fn change_group(
        &self,
        chat_id: u32,
        f: impl FnOnce(&Connection, &Group) -> Result<Vec<String>>,
    ) -> Result<()> {
        let mut conn = self.db();
        let tx = conn.transaction()?;
        let g = require_member(&tx, chat_id)?;
        let extra = f(&tx, &g)?;
        self.send_group_state(&tx, chat_id, &extra)?;
        tx.commit()?;
        drop(conn);
        self.emit(vec![
            Event::ChatListChanged,
            Event::ContactsChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }

    fn store_avatar(&self, path: Option<String>) -> Result<Option<String>> {
        path.map(|p| self.copy_avatar(&p)).transpose()
    }
}

#[uniffi::export]
impl Account {
    pub async fn create_group(
        &self,
        name: String,
        avatar_path: Option<String>,
        members: Vec<u32>,
    ) -> Result<u32, CoreError> {
        let name = name.trim().to_string();
        if name.is_empty() {
            return Err(anyhow::anyhow!("Please enter a group name").into());
        }
        if members.len() + 1 > crate::protocol::MAX_GROUP_MEMBERS {
            return Err(anyhow::anyhow!("group-full").into());
        }
        let avatar = self.store_avatar(avatar_path)?;
        let mut conn = self.db();
        let tx = conn.transaction()?;
        tx.execute(
            "INSERT INTO chats (kind, grp, name, avatar, created, ver) VALUES (?,?,?,?,?,1)",
            params![CHAT_GROUP, protocol::new_id(), name, avatar, now_ms()],
        )?;
        let chat_id = tx.last_insert_rowid() as u32;
        tx.execute(
            "INSERT INTO members (chat_id, contact_id, admin) VALUES (?, 0, 1)",
            [chat_id],
        )?;
        for id in &members {
            tx.execute(
                "INSERT OR IGNORE INTO members (chat_id, contact_id, admin)
                 SELECT ?, id, 1 FROM contacts WHERE id=?",
                [chat_id, *id],
            )?;
            self.share_profile(&tx, allow_contact(&tx, *id)?)?;
        }
        self.add_system(
            &tx,
            chat_id,
            None,
            SystemMessage::GroupCreated { name: name.clone() },
        )?;
        self.send_group_state(&tx, chat_id, &[])?;
        tx.commit()?;
        drop(conn);
        info!(
            "created group chat {chat_id} with {} member(s)",
            members.len()
        );
        self.emit(vec![Event::ChatListChanged, Event::ContactsChanged]);
        Ok(chat_id)
    }

    pub fn group_info(&self, chat_id: u32) -> Result<GroupInfo, CoreError> {
        let me = self.info();
        let conn = self.db();
        let g = group(&conn, chat_id)?;
        let mut stmt = conn.prepare(
            "SELECT g.contact_id, g.admin, COALESCE(ct.local_name,''), COALESCE(ct.name,''),
                    COALESCE(ct.account,''), ct.avatar
             FROM members g LEFT JOIN contacts ct ON ct.id=g.contact_id WHERE g.chat_id=?
             ORDER BY g.contact_id<>0, COALESCE(NULLIF(ct.local_name,''), NULLIF(ct.name,''), ct.account) COLLATE NOCASE",
        )?;
        let rows = stmt.query_map([chat_id], |r| {
            let id: u32 = r.get(0)?;
            let admin: bool = r.get(1)?;
            if id == 0 {
                return Ok(GroupMember {
                    contact_id: None,
                    name: me.name.clone(),
                    account: me.account.clone(),
                    address: me.address.clone(),
                    avatar_path: me.avatar_path.clone(),
                    admin,
                });
            }
            let account: String = r.get(4)?;
            Ok(GroupMember {
                contact_id: Some(id),
                name: display_name(&r.get::<_, String>(2)?, &r.get::<_, String>(3)?, &account),
                address: taperelay::self_key_of(&account)
                    .unwrap_or_default()
                    .to_string(),
                account,
                avatar_path: abs(&self.dir, r.get(5)?),
                admin,
            })
        })?;
        let members: Vec<GroupMember> = rows.collect::<Result<_, _>>()?;
        let mine = members.iter().find(|m| m.contact_id.is_none());
        Ok(GroupInfo {
            chat_id,
            name: g.name,
            avatar_path: abs(&self.dir, g.avatar),
            is_member: mine.is_some(),
            is_admin: mine.is_some_and(|m| m.admin),
            members,
        })
    }

    pub async fn set_group_name(&self, chat_id: u32, name: String) -> Result<(), CoreError> {
        let name = name.trim().to_string();
        if name.is_empty() {
            return Err(anyhow::anyhow!("Please enter a group name").into());
        }
        self.change_group(chat_id, |conn, g| {
            if g.name != name {
                conn.execute("UPDATE chats SET name=? WHERE id=?", params![name, chat_id])?;
                self.add_system(
                    conn,
                    chat_id,
                    None,
                    SystemMessage::GroupNameChanged {
                        name: name.clone(),
                        old_name: g.name.clone(),
                    },
                )?;
            }
            Ok(Vec::new())
        })?;
        info!("renamed group chat {chat_id}");
        Ok(())
    }

    pub async fn set_group_avatar(
        &self,
        chat_id: u32,
        avatar_path: Option<String>,
    ) -> Result<(), CoreError> {
        let avatar = self.store_avatar(avatar_path)?;
        let mut old = None;
        self.change_group(chat_id, |conn, g| {
            conn.execute(
                "UPDATE chats SET avatar=? WHERE id=?",
                params![avatar, chat_id],
            )?;
            self.add_system(conn, chat_id, None, SystemMessage::GroupAvatarChanged)?;
            old = g.avatar.clone();
            Ok(Vec::new())
        })?;
        if let Some(old) = abs(&self.dir, old) {
            let _ = std::fs::remove_file(old);
        }
        info!("changed avatar of group chat {chat_id}");
        Ok(())
    }

    pub async fn add_group_members(
        &self,
        chat_id: u32,
        contact_ids: Vec<u32>,
    ) -> Result<(), CoreError> {
        self.change_group(chat_id, |conn, _| {
            for id in &contact_ids {
                let added = conn.execute(
                    "INSERT OR IGNORE INTO members (chat_id, contact_id, admin)
                     SELECT ?, id, 1 FROM contacts WHERE id=?",
                    [chat_id, *id],
                )?;
                if added > 0 {
                    self.share_profile(conn, allow_contact(conn, *id)?)?;
                    self.add_system(
                        conn,
                        chat_id,
                        None,
                        SystemMessage::MemberAdded {
                            contact_id: Some(*id),
                            name: contact_name(conn, *id)?,
                        },
                    )?;
                }
            }
            check_group_size(conn, chat_id)?;
            Ok(Vec::new())
        })?;
        info!(
            "added {} member(s) to group chat {chat_id}",
            contact_ids.len()
        );
        Ok(())
    }

    pub async fn remove_group_member(
        &self,
        chat_id: u32,
        contact_id: u32,
    ) -> Result<(), CoreError> {
        self.change_group(chat_id, |conn, _| {
            let n = conn.execute(
                "DELETE FROM members WHERE chat_id=? AND contact_id=? AND contact_id<>0",
                [chat_id, contact_id],
            )?;
            ensure!(n > 0, "Not a member of this group");
            self.add_system(
                conn,
                chat_id,
                None,
                SystemMessage::MemberRemoved {
                    contact_id: Some(contact_id),
                    name: contact_name(conn, contact_id)?,
                },
            )?;
            let account: String = conn.query_row(
                "SELECT account FROM contacts WHERE id=?",
                [contact_id],
                |r| r.get(0),
            )?;
            Ok(vec![account])
        })?;
        info!("removed contact {contact_id} from group chat {chat_id}");
        Ok(())
    }

    pub async fn leave_group(&self, chat_id: u32) -> Result<(), CoreError> {
        self.change_group(chat_id, |conn, _| {
            conn.execute(
                "DELETE FROM members WHERE chat_id=? AND contact_id=0",
                [chat_id],
            )?;
            self.add_system(conn, chat_id, None, SystemMessage::MemberLeft)?;
            Ok(Vec::new())
        })?;
        info!("left group chat {chat_id}");
        Ok(())
    }

    pub fn group_invite_link(&self, chat_id: u32) -> Result<String, CoreError> {
        let conn = self.db();
        let g = require_member(&conn, chat_id)?;
        let token: Option<String> =
            conn.query_row("SELECT invite FROM chats WHERE id=?", [chat_id], |r| {
                r.get(0)
            })?;
        let token = match token {
            Some(t) => t,
            None => {
                let t = protocol::new_id();
                conn.execute("UPDATE chats SET invite=? WHERE id=?", params![t, chat_id])?;
                t
            }
        };
        Ok(format!(
            "{}&g={}&gn={}",
            make_link(self.keys.account(), &self.my_name(&conn)?, &token),
            percent_encode(&g.grp),
            percent_encode(&g.name)
        ))
    }

    pub fn reset_group_invite(&self, chat_id: u32) -> Result<(), CoreError> {
        let conn = self.db();
        group(&conn, chat_id)?;
        conn.execute(
            "UPDATE chats SET invite=? WHERE id=?",
            params![protocol::new_id(), chat_id],
        )?;
        info!("group chat {chat_id} invite reset");
        Ok(())
    }

    pub fn message_deliveries(&self, msg_id: u32) -> Vec<Delivery> {
        let conn = self.db();
        let result = (|| -> Result<Vec<Delivery>> {
            let mut stmt = conn.prepare(
                "SELECT d.contact_id, ct.local_name, ct.name, ct.account, d.state, d.error
                 FROM deliveries d JOIN contacts ct ON ct.id=d.contact_id WHERE d.msg_id=? ORDER BY d.rowid",
            )?;
            let rows = stmt.query_map([msg_id], |r| {
                Ok(Delivery {
                    contact_id: r.get(0)?,
                    name: display_name(
                        &r.get::<_, String>(1)?,
                        &r.get::<_, String>(2)?,
                        &r.get::<_, String>(3)?,
                    ),
                    state: MessageState::from_code(r.get(4)?),
                    error: r.get(5)?,
                })
            })?;
            Ok(rows.collect::<Result<_, _>>()?)
        })();
        result.unwrap_or_else(|e| {
            log::error!("deliveries query failed: {e:#}");
            Vec::new()
        })
    }
}
