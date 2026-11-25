use anyhow::{ensure, Context, Result};
use log::{info, warn};
use rusqlite::{Connection, OptionalExtension};

use crate::account::Account;
use crate::api::*;
use crate::chat::{allow_contact, chat_for_contact, upsert_contact, CHAT_GROUP};
use crate::protocol::{self, Body, Envelope};
use crate::store;
use crate::transport::percent_encode;

const BASE: &str = "https://i.tapetape.org/#";

#[derive(Debug, PartialEq)]
pub(crate) struct Invite {
    pub account: String,
    pub name: String,
    pub token: String,
    pub group: Option<String>,
    pub group_name: String,
}

pub(crate) fn make_link(account: &str, name: &str, token: &str) -> String {
    format!(
        "{BASE}a={}&n={}&t={}",
        percent_encode(account),
        percent_encode(name),
        percent_encode(token)
    )
}

fn percent_decode(s: &str) -> Result<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' => {
                let hex = s.get(i + 1..i + 3).context("bad escape")?;
                out.push(u8::from_str_radix(hex, 16)?);
                i += 3;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    Ok(String::from_utf8(out)?)
}

pub(crate) fn parse_link(link: &str) -> Result<Invite> {
    let (_, fragment) = link.trim().split_once('#').context("Invalid invite link")?;
    let mut invite = Invite {
        account: String::new(),
        name: String::new(),
        token: String::new(),
        group: None,
        group_name: String::new(),
    };
    for pair in fragment.split('&') {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        let v = percent_decode(v).context("Invalid invite link")?;
        match k {
            "a" => invite.account = v,
            "n" => invite.name = v,
            "t" => invite.token = v,
            "g" => invite.group = Some(v),
            "gn" => invite.group_name = v,
            _ => {}
        }
    }
    ensure!(
        taperelay::is_self_account(&invite.account) && !invite.token.is_empty(),
        "Invalid invite link"
    );
    Ok(invite)
}

impl Account {
    fn join_group(&self, invite: Invite) -> Result<u32> {
        let grp = invite.group.clone().context("Invalid invite link")?;
        let mut conn = self.db();
        let tx = conn.transaction()?;
        let (contact_id, _) = upsert_contact(&tx, &invite.account, &invite.name)?;
        self.share_profile(&tx, allow_contact(&tx, contact_id)?)?;
        let existing: Option<(u32, i64, bool)> = tx
            .query_row(
                "SELECT id, ver, EXISTS(SELECT 1 FROM members g WHERE g.chat_id=chats.id AND g.contact_id=0)
                 FROM chats WHERE grp=?",
                [&grp],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        let chat_id = match existing {
            Some((id, ver, true)) if ver > 0 => {
                info!("already a member of group {grp}");
                return Ok(id);
            }
            Some((id, _, _)) => id,
            None => {
                tx.execute(
                    "INSERT INTO chats (kind, grp, name, created) VALUES (?,?,?,?)",
                    rusqlite::params![CHAT_GROUP, grp, invite.group_name, protocol::now_ms()],
                )?;
                tx.last_insert_rowid() as u32
            }
        };
        tx.execute(
            "INSERT OR IGNORE INTO members (chat_id, contact_id, admin) VALUES (?1, 0, 1), (?1, ?2, 1)",
            [chat_id, contact_id],
        )?;
        let mut hello = self.envelope(
            &tx,
            Body::Hello {
                token: invite.token,
            },
        )?;
        hello.group = Some(grp);
        self.enqueue(&tx, &invite.account, None, &hello, &[])?;
        tx.commit()?;
        drop(conn);
        info!("asked {} to join group chat {chat_id}", invite.account);
        self.emit(vec![Event::ContactsChanged, Event::ChatListChanged]);
        Ok(chat_id)
    }

    fn invite_token(&self, conn: &Connection) -> Result<String> {
        store::get_config(conn, "invite_token")?.context("no invite token")
    }

    pub(crate) fn receive_hello(
        &self,
        conn: &Connection,
        env: &Envelope,
        token: &str,
        events: &mut Vec<Event>,
    ) -> Result<()> {
        if let Some(g) = &env.group {
            return self.receive_group_hello(conn, env, g, token, events);
        }
        if token != self.invite_token(conn)? {
            warn!("hello from {} with an invalid token", env.from);
            return Ok(());
        }
        let (contact_id, _) = upsert_contact(conn, &env.from, &env.name)?;
        conn.execute("UPDATE contacts SET verified=1 WHERE id=?", [contact_id])?;
        allow_contact(conn, contact_id)?;
        let chat_id = chat_for_contact(conn, contact_id)?;
        let ok = self.envelope(conn, Body::HelloOk)?;
        self.enqueue(conn, &env.from, None, &ok, &[])?;
        self.send_profile(conn, Some(&env.from))?;
        info!("accepted invite from {}", env.from);
        events.extend([
            Event::ContactsChanged,
            Event::ChatListChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }

    pub(crate) fn receive_hello_ok(
        &self,
        conn: &Connection,
        env: &Envelope,
        events: &mut Vec<Event>,
    ) -> Result<()> {
        let contact: Option<u32> = conn
            .query_row(
                "SELECT id FROM contacts WHERE account=? AND allowed=1",
                [&env.from],
                |r| r.get(0),
            )
            .optional()?;
        let Some(contact_id) = contact else {
            warn!("unexpected hello_ok from {}", env.from);
            return Ok(());
        };
        conn.execute("UPDATE contacts SET verified=1 WHERE id=?", [contact_id])?;
        if env.group.is_some() {
            info!("group join confirmed by {}", env.from);
            events.push(Event::ContactsChanged);
            return Ok(());
        }
        let chat_id = chat_for_contact(conn, contact_id)?;
        self.send_profile(conn, Some(&env.from))?;
        info!("invite handshake with {} complete", env.from);
        events.extend([
            Event::ContactsChanged,
            Event::ChatListChanged,
            Event::MessagesChanged { chat_id },
        ]);
        Ok(())
    }
}

#[uniffi::export]
impl Account {
    pub fn invite_link(&self) -> String {
        let conn = self.db();
        let token = self.invite_token(&conn).unwrap_or_default();
        let name = self.my_name(&conn).unwrap_or_default();
        make_link(self.keys.account(), &name, &token)
    }

    pub fn reset_invite(&self) {
        if let Err(e) = store::set_config(&self.db(), "invite_token", &protocol::new_id()) {
            log::error!("reset invite failed: {e:#}");
        }
        info!("account {} invite token reset", self.id);
    }

    pub async fn accept_invite(&self, link: String) -> Result<u32, CoreError> {
        let invite = parse_link(&link)?;
        if invite.account == self.keys.account() {
            return Err(anyhow::anyhow!("This is your own invite link").into());
        }
        if invite.group.is_some() {
            return Ok(self.join_group(invite)?);
        }
        let (chat_id, contact_id) = self.open_chat_with(&invite.account, &invite.name)?;
        let conn = self.db();
        allow_contact(&conn, contact_id)?;
        let hello = self.envelope(
            &conn,
            Body::Hello {
                token: invite.token,
            },
        )?;
        self.enqueue(&conn, &invite.account, None, &hello, &[])?;
        drop(conn);
        info!("sent hello to {}", invite.account);
        self.emit(vec![Event::ContactsChanged, Event::ChatListChanged]);
        Ok(chat_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "key:0x9858effd232b4033e47d90003d41ec34ecaeda94@relay1.taperelay.com:4246";

    #[test]
    fn link_roundtrip() {
        let link = make_link(A, "小王 & co", "q1w2-e3_r4");
        assert!(link.starts_with("https://i.tapetape.org/#a=key%3A0x9858"));
        let inv = parse_link(&link).unwrap();
        assert_eq!(
            inv,
            Invite {
                account: A.into(),
                name: "小王 & co".into(),
                token: "q1w2-e3_r4".into(),
                group: None,
                group_name: String::new(),
            }
        );
        let g = parse_link(&format!("{link}&g=abc&gn=Team")).unwrap();
        assert_eq!(g.group.as_deref(), Some("abc"));
        assert_eq!(g.group_name, "Team");
    }

    #[test]
    fn bad_links() {
        assert!(parse_link("https://i.tapetape.org/").is_err());
        assert!(parse_link("https://i.tapetape.org/#a=alice&t=x").is_err());
        assert!(parse_link(&format!("https://i.tapetape.org/#a={A}")).is_err());
        assert!(parse_link("https://i.tapetape.org/#a=%zz&t=x").is_err());
    }

    #[test]
    fn unencoded_account_is_accepted() {
        let inv = parse_link(&format!("https://i.tapetape.org/#a={A}&n=Bob+B&t=tok")).unwrap();
        assert_eq!(
            (inv.account.as_str(), inv.name.as_str(), inv.token.as_str()),
            (A, "Bob B", "tok")
        );
    }
}
