use std::path::Path;
use std::sync::Arc;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, info, warn};
use rusqlite::{params, Connection};

use crate::account::Account;
use crate::api::*;
use crate::backup::{self, Header, MODE_PASSPHRASE};
use crate::chat::live;
use crate::protocol::now_ms;
use crate::store::{get_config, set_config};
use crate::{run, Core};

const OLD_CHATS: &str = "chat_id IN (SELECT id FROM chats WHERE kind IN (0,3))";

fn flag(conn: &Connection, key: &str, default: bool) -> bool {
    match get_config(conn, key).ok().flatten() {
        Some(v) => v == "1",
        None => default,
    }
}

pub(crate) fn read_receipts(conn: &Connection) -> bool {
    flag(conn, "read_receipts", true)
}

pub(crate) fn account_muted(conn: &Connection) -> bool {
    flag(conn, "muted", false)
}

fn bit(b: bool) -> &'static str {
    if b {
        "1"
    } else {
        "0"
    }
}

const DOWNLOADS: [(AutoDownload, &str); 5] = [
    (AutoDownload::All, "all"),
    (AutoDownload::Kib160, "160k"),
    (AutoDownload::Kib640, "640k"),
    (AutoDownload::Mib5, "5m"),
    (AutoDownload::Mib25, "25m"),
];

pub(crate) fn auto_download_limit(conn: &Connection) -> u64 {
    match get_config(conn, "auto_download").ok().flatten().as_deref() {
        Some("160k") => 160 << 10,
        Some("640k") => 640 << 10,
        Some("5m") => 5 << 20,
        Some("25m") => 25 << 20,
        _ => u64::MAX,
    }
}

fn summarize(account_id: u32, events: Vec<Event>) -> FetchResult {
    let mut out = FetchResult::default();
    for e in events {
        match e {
            Event::IncomingMessage { .. } => out.new_messages += 1,
            Event::Notification { notification } => out.notifications.push(notification),
            _ => {}
        }
    }
    debug!(
        "account {account_id} fetched {} new message(s)",
        out.new_messages
    );
    out
}

impl Account {
    pub(crate) fn unread(&self, conn: &Connection) -> Result<u32> {
        Ok(conn.query_row(
            &format!(
                "SELECT COUNT(*) FROM messages m JOIN chats c ON c.id=m.chat_id
                 LEFT JOIN contacts ct ON ct.id=c.contact_id
                 WHERE m.state=?1 AND COALESCE(ct.blocked,0)=0
                 AND (c.muted_until IS NULL OR (c.muted_until>=0 AND c.muted_until<?2)) AND {}",
                live("m")
            ),
            params![MessageState::Incoming.code(), now_ms()],
            |r| r.get(0),
        )?)
    }

    pub(crate) fn delete_old(&self) -> Result<()> {
        let conn = self.db();
        let Some(secs) =
            get_config(&conn, "delete_device_after")?.and_then(|s| s.parse::<i64>().ok())
        else {
            return Ok(());
        };
        let cutoff = now_ms() - secs * 1000;
        let filter = format!("time<? AND {OLD_CHATS}");
        let chats: Vec<u32> = {
            let mut stmt = conn.prepare(&format!(
                "SELECT DISTINCT chat_id FROM messages WHERE {filter}"
            ))?;
            let rows = stmt.query_map([cutoff], |r| r.get(0))?;
            rows.collect::<Result<_, _>>()?
        };
        if chats.is_empty() {
            return Ok(());
        }
        let files = self.delete_messages_where(&conn, &filter, [cutoff])?;
        drop(conn);
        self.remove_unused(files);
        info!("deleted old messages in {} chat(s)", chats.len());
        let mut events: Vec<Event> = chats
            .into_iter()
            .map(|chat_id| Event::MessagesChanged { chat_id })
            .collect();
        events.push(Event::ChatListChanged);
        self.emit(events);
        Ok(())
    }

    pub(crate) async fn fetch(&self) -> Result<FetchResult> {
        let events = self.pull().await?;
        if let Err(e) = self.flush().await {
            debug!("sending during fetch failed: {e:#}");
        }
        Ok(summarize(self.id, events))
    }

    fn export(&self, dest_dir: &str, passphrase: Option<&str>) -> Result<String> {
        let snapshot = self.dir.join("backup-snapshot.sqlite");
        let _ = std::fs::remove_file(&snapshot);
        let mnemonic = {
            let conn = self.db();
            conn.execute("VACUUM INTO ?", [snapshot.to_string_lossy().as_ref()])?;
            get_config(&conn, "mnemonic")?.context("no mnemonic")?
        };
        let address = self.keys.id.address();
        let dest = Path::new(dest_dir).join(format!(
            "tapetape-backup-{address}-{}.tapetapebak",
            backup::local_date(now_ms())
        ));
        let res = backup::write(
            &dest,
            address,
            passphrase,
            &mnemonic,
            &snapshot,
            &self.dir.join("blobs"),
        );
        let _ = std::fs::remove_file(&snapshot);
        res?;
        info!(
            "account {} backup written ({})",
            self.id,
            if passphrase.is_some() {
                "passphrase"
            } else {
                "recovery phrase"
            }
        );
        Ok(dest.to_string_lossy().into_owned())
    }
}

#[uniffi::export]
impl Account {
    pub fn settings(&self) -> Settings {
        let conn = self.db();
        let auto = get_config(&conn, "auto_download").ok().flatten();
        Settings {
            read_receipts: read_receipts(&conn),
            media_quality: match get_config(&conn, "media_quality").ok().flatten().as_deref() {
                Some("worse") => MediaQuality::Worse,
                _ => MediaQuality::Balanced,
            },
            auto_download: DOWNLOADS
                .iter()
                .find(|(_, s)| Some(*s) == auto.as_deref())
                .map_or(AutoDownload::All, |(d, _)| *d),
            delete_device_after: get_config(&conn, "delete_device_after")
                .ok()
                .flatten()
                .and_then(|s| s.parse().ok()),
            mentions: flag(&conn, "mentions", true),
        }
    }

    pub fn set_settings(&self, settings: Settings) {
        let result = (|| -> Result<()> {
            let conn = self.db();
            set_config(&conn, "read_receipts", bit(settings.read_receipts))?;
            set_config(
                &conn,
                "media_quality",
                match settings.media_quality {
                    MediaQuality::Balanced => "balanced",
                    MediaQuality::Worse => "worse",
                },
            )?;
            let auto = DOWNLOADS
                .iter()
                .find(|(d, _)| *d == settings.auto_download)
                .map_or("all", |(_, s)| *s);
            set_config(&conn, "auto_download", auto)?;
            match settings.delete_device_after {
                Some(s) => set_config(&conn, "delete_device_after", &s.to_string())?,
                None => {
                    conn.execute("DELETE FROM config WHERE key='delete_device_after'", [])?;
                }
            }
            set_config(&conn, "mentions", bit(settings.mentions))?;
            Ok(())
        })();
        match result {
            Ok(()) => {
                info!("account {} settings updated", self.id);
                self.wake_expiry();
            }
            Err(e) => log::error!("saving settings failed: {e:#}"),
        }
    }

    pub fn count_messages_older_than(&self, seconds: u32) -> u32 {
        self.db()
            .query_row(
                &format!("SELECT COUNT(*) FROM messages WHERE time<? AND {OLD_CHATS}"),
                [now_ms() - i64::from(seconds) * 1000],
                |r| r.get(0),
            )
            .unwrap_or(0)
    }

    pub fn set_account_muted(&self, muted: bool) {
        if let Err(e) = set_config(&self.db(), "muted", bit(muted)) {
            log::error!("saving account mute failed: {e:#}");
        }
    }

    pub fn set_account_tag(&self, tag: String) {
        if let Err(e) = set_config(&self.db(), "tag", tag.trim()) {
            log::error!("saving account tag failed: {e:#}");
        }
    }

    pub fn mnemonic(&self) -> String {
        get_config(&self.db(), "mnemonic")
            .ok()
            .flatten()
            .unwrap_or_default()
    }

    pub async fn export_backup(
        self: Arc<Self>,
        dest_dir: String,
        passphrase: Option<String>,
    ) -> Result<String, CoreError> {
        run(async move {
            tokio::task::spawn_blocking(move || {
                self.export(&dest_dir, passphrase.as_deref().filter(|p| !p.is_empty()))
            })
            .await?
        })
        .await
    }

    pub async fn fetch_once(self: Arc<Self>) -> Result<FetchResult, CoreError> {
        run(async move { self.fetch().await }).await
    }

    pub async fn send_pending(self: Arc<Self>) -> Result<(), CoreError> {
        run(async move { self.flush().await.map(|_| ()) }).await
    }
}

impl Core {
    fn import(&self, path: &str, secret: &str) -> Result<u32> {
        let (header, file) = Header::read(Path::new(path))?;
        let key = header.key(secret)?;
        if self
            .accounts()
            .iter()
            .any(|a| a.address.eq_ignore_ascii_case(&header.address))
        {
            bail!("This account has already been added");
        }
        let tmp = self
            .accounts_dir()
            .join(format!("import-{}", hex::encode(rand::random::<[u8; 4]>())));
        std::fs::create_dir_all(tmp.join("blobs"))?;
        if let Err(e) = backup::unpack(&header, file, key, &tmp) {
            let _ = std::fs::remove_dir_all(&tmp);
            return Err(e);
        }
        let mut accounts = self.accounts.lock().unwrap();
        let id = accounts.keys().next_back().map_or(1, |id| id + 1);
        let dir = self.accounts_dir().join(id.to_string());
        std::fs::rename(&tmp, &dir)?;
        let opened = Account::open(id, dir.clone(), self.net.clone(), self.listener.clone())
            .and_then(|a| {
                ensure!(
                    a.keys.id.address().eq_ignore_ascii_case(&header.address),
                    "The backup is damaged"
                );
                Ok(a)
            });
        let account = match opened {
            Ok(a) => a,
            Err(e) => {
                let _ = std::fs::remove_dir_all(&dir);
                return Err(e);
            }
        };
        info!("imported account {id}: {}", account.keys.account());
        accounts.insert(id, account);
        drop(accounts);
        if self.selected_account().is_none() {
            self.save_selected(Some(id))?;
        }
        Ok(id)
    }
}

#[uniffi::export]
impl Core {
    pub fn move_account_to_top(&self, id: u32) -> Result<(), CoreError> {
        let mut ids: Vec<u32> = self.accounts().into_iter().map(|a| a.id).collect();
        let pos = ids
            .iter()
            .position(|i| *i == id)
            .context("Account not found")?;
        let id = ids.remove(pos);
        ids.insert(0, id);
        self.save_order(&ids)?;
        Ok(())
    }

    pub fn backup_protection(&self, path: String) -> Result<BackupProtection, CoreError> {
        let (h, _) = Header::read(Path::new(&path))?;
        Ok(if h.mode == MODE_PASSPHRASE {
            BackupProtection::Passphrase
        } else {
            BackupProtection::RecoveryPhrase
        })
    }

    pub async fn import_backup(
        self: Arc<Self>,
        path: String,
        secret: String,
    ) -> Result<u32, CoreError> {
        run(async move { tokio::task::spawn_blocking(move || self.import(&path, &secret)).await? })
            .await
    }

    pub async fn fetch_all(&self) -> FetchResult {
        let accounts: Vec<Arc<Account>> = self.accounts.lock().unwrap().values().cloned().collect();
        let res = run(async move {
            let mut set = tokio::task::JoinSet::new();
            for a in accounts {
                set.spawn(async move { (a.id, a.fetch().await) });
            }
            let mut out = FetchResult::default();
            while let Some(joined) = set.join_next().await {
                match joined {
                    Ok((_, Ok(f))) => {
                        out.new_messages += f.new_messages;
                        out.notifications.extend(f.notifications);
                    }
                    Ok((id, Err(e))) => warn!("fetch for account {id} failed: {e:#}"),
                    Err(e) => warn!("fetch task failed: {e}"),
                }
            }
            Ok(out)
        })
        .await;
        res.unwrap_or_default()
    }
}
