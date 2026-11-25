use std::path::Path;

use anyhow::Result;
use log::info;
use rusqlite::{Connection, OptionalExtension, TransactionBehavior};

const MIGRATIONS: &[&str] = &[
    r#"
CREATE TABLE config (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE contacts (
    id INTEGER PRIMARY KEY,
    account TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL DEFAULT '',
    avatar TEXT,
    verified INTEGER NOT NULL DEFAULT 0,
    allowed INTEGER NOT NULL DEFAULT 0,
    allow_synced INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE chats (
    id INTEGER PRIMARY KEY,
    contact_id INTEGER UNIQUE REFERENCES contacts(id),
    pinned INTEGER NOT NULL DEFAULT 0,
    muted INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,
    draft TEXT,
    created INTEGER NOT NULL
);
CREATE TABLE messages (
    id INTEGER PRIMARY KEY,
    chat_id INTEGER NOT NULL REFERENCES chats(id),
    contact_id INTEGER REFERENCES contacts(id),
    env_id TEXT NOT NULL,
    kind INTEGER NOT NULL,
    text TEXT NOT NULL,
    time INTEGER NOT NULL,
    state INTEGER NOT NULL,
    error TEXT,
    reply_to INTEGER
);
CREATE INDEX messages_chat ON messages(chat_id, id);
CREATE INDEX messages_env ON messages(env_id);
CREATE TABLE seen (sender TEXT NOT NULL, env_id TEXT NOT NULL, PRIMARY KEY (sender, env_id));
CREATE TABLE send_queue (
    id INTEGER PRIMARY KEY,
    recipient TEXT NOT NULL,
    msg_id INTEGER,
    inner BLOB NOT NULL,
    created INTEGER NOT NULL
);
CREATE TABLE sent_notices (notice TEXT PRIMARY KEY, msg_id INTEGER NOT NULL, ts INTEGER NOT NULL);
CREATE TABLE parts (
    sender TEXT NOT NULL,
    msg_id BLOB NOT NULL,
    idx INTEGER NOT NULL,
    total INTEGER NOT NULL,
    data BLOB NOT NULL,
    ts INTEGER NOT NULL,
    PRIMARY KEY (sender, msg_id, idx)
);
CREATE TABLE peer_keys (account TEXT PRIMARY KEY, key BLOB NOT NULL, fetched INTEGER NOT NULL);
"#,
    r#"
ALTER TABLE contacts ADD COLUMN local_name TEXT NOT NULL DEFAULT '';
ALTER TABLE contacts ADD COLUMN bio TEXT NOT NULL DEFAULT '';
ALTER TABLE contacts ADD COLUMN last_seen INTEGER;
ALTER TABLE contacts ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0;
UPDATE contacts SET allow_synced=1 WHERE allowed=0;
ALTER TABLE chats ADD COLUMN kind INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chats ADD COLUMN muted_until INTEGER;
ALTER TABLE chats ADD COLUMN ephemeral INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN file TEXT;
ALTER TABLE messages ADD COLUMN file_name TEXT;
ALTER TABLE messages ADD COLUMN mime TEXT;
ALTER TABLE messages ADD COLUMN size INTEGER;
ALTER TABLE messages ADD COLUMN width INTEGER;
ALTER TABLE messages ADD COLUMN height INTEGER;
ALTER TABLE messages ADD COLUMN duration INTEGER;
ALTER TABLE messages ADD COLUMN vcard_name TEXT;
ALTER TABLE messages ADD COLUMN vcard_account TEXT;
ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN original_id INTEGER;
ALTER TABLE messages ADD COLUMN ephemeral INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN expires_at INTEGER;
ALTER TABLE messages ADD COLUMN system TEXT;
CREATE INDEX messages_original ON messages(original_id);
CREATE INDEX messages_expires ON messages(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE reactions (
    msg_id INTEGER NOT NULL,
    contact_id INTEGER NOT NULL,
    emoji TEXT NOT NULL,
    time INTEGER NOT NULL,
    PRIMARY KEY (msg_id, contact_id)
);
ALTER TABLE send_queue ADD COLUMN done INTEGER NOT NULL DEFAULT 0;
"#,
    r#"
UPDATE messages SET file=substr(file, instr(file, '/blobs/') + 1) WHERE file LIKE '/%/blobs/%';
UPDATE contacts SET avatar=substr(avatar, instr(avatar, '/blobs/') + 1) WHERE avatar LIKE '/%/blobs/%';
UPDATE config SET value=substr(value, instr(value, '/blobs/') + 1) WHERE key='avatar' AND value LIKE '/%/blobs/%';
UPDATE messages SET system='{"TimerChanged":{"seconds":' || substr(system, 7) || '}}' WHERE system LIKE 'timer:%';
ALTER TABLE chats ADD COLUMN grp TEXT;
ALTER TABLE chats ADD COLUMN name TEXT NOT NULL DEFAULT '';
ALTER TABLE chats ADD COLUMN avatar TEXT;
ALTER TABLE chats ADD COLUMN ver INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chats ADD COLUMN invite TEXT;
ALTER TABLE chats ADD COLUMN request INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX chats_grp ON chats(grp);
CREATE TABLE members (
    chat_id INTEGER NOT NULL,
    contact_id INTEGER NOT NULL,
    admin INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (chat_id, contact_id)
);
CREATE TABLE deliveries (
    msg_id INTEGER NOT NULL,
    contact_id INTEGER NOT NULL,
    state INTEGER NOT NULL,
    error TEXT,
    PRIMARY KEY (msg_id, contact_id)
);
ALTER TABLE sent_notices ADD COLUMN recipient TEXT NOT NULL DEFAULT '';
UPDATE sent_notices SET recipient=COALESCE((SELECT ct.account FROM messages m JOIN chats c ON c.id=m.chat_id
    JOIN contacts ct ON ct.id=c.contact_id WHERE m.id=sent_notices.msg_id), '');
INSERT OR IGNORE INTO deliveries (msg_id, contact_id, state, error)
    SELECT m.id, c.contact_id, m.state, m.error FROM messages m JOIN chats c ON c.id=m.chat_id
    WHERE c.kind=0 AND m.contact_id IS NULL AND m.kind<>1 AND m.state<=4;
"#,
    r#"
ALTER TABLE messages ADD COLUMN packet TEXT;
"#,
    r#"
CREATE TABLE transfers (
    msg_id INTEGER PRIMARY KEY,
    dir INTEGER NOT NULL,
    state INTEGER NOT NULL,
    reference BLOB,
    size INTEGER NOT NULL
);
"#,
    r#"
ALTER TABLE contacts ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
"#,
];

pub(crate) fn open(path: &Path) -> Result<Connection> {
    let mut conn = Connection::open(path)?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "foreign_keys", true)?;
    let version: usize = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if version < MIGRATIONS.len() {
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let version: usize = tx.pragma_query_value(None, "user_version", |r| r.get(0))?;
        for (i, sql) in MIGRATIONS.iter().enumerate().skip(version) {
            tx.execute_batch(sql)?;
            tx.pragma_update(None, "user_version", i + 1)?;
            info!("migrated {} to schema {}", path.display(), i + 1);
        }
        tx.commit()?;
    }
    Ok(conn)
}

pub(crate) fn get_config(conn: &Connection, key: &str) -> Result<Option<String>> {
    Ok(conn
        .query_row("SELECT value FROM config WHERE key=?", [key], |r| r.get(0))
        .optional()?)
}

pub(crate) fn set_config(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO config (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        [key, value],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrates_once() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("db.sqlite");
        let conn = open(&path).unwrap();
        set_config(&conn, "a", "1").unwrap();
        set_config(&conn, "a", "2").unwrap();
        drop(conn);
        let conn = open(&path).unwrap();
        assert_eq!(get_config(&conn, "a").unwrap().as_deref(), Some("2"));
        let v: usize = conn
            .pragma_query_value(None, "user_version", |r| r.get(0))
            .unwrap();
        assert_eq!(v, MIGRATIONS.len());
    }
}

#[cfg(test)]
mod migration_tests {
    use super::*;

    #[test]
    fn v2_paths_and_timer_become_portable() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("db.sqlite");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(MIGRATIONS[0]).unwrap();
        conn.execute_batch(MIGRATIONS[1]).unwrap();
        conn.pragma_update(None, "user_version", 2).unwrap();
        conn.execute_batch(
            "INSERT INTO contacts (id, account, avatar) VALUES (1, 'k', '/var/x/accounts/1/blobs/ab-a.png');
             INSERT INTO chats (id, contact_id, created) VALUES (1, 1, 0);
             INSERT INTO messages (chat_id, env_id, kind, text, time, state, file, system)
                 VALUES (1, 'e1', 2, '', 0, 1, '/var/x/accounts/1/blobs/cd-p.png', NULL),
                        (1, 'e2', 1, '', 0, 6, NULL, 'timer:30');",
        )
        .unwrap();
        drop(conn);
        let conn = open(&path).unwrap();
        let (file, system): (String, String) = conn
            .query_row(
                "SELECT (SELECT file FROM messages WHERE env_id='e1'), (SELECT system FROM messages WHERE env_id='e2')",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(file, "blobs/cd-p.png");
        assert_eq!(system, r#"{"TimerChanged":{"seconds":30}}"#);
        let avatar: String = conn
            .query_row("SELECT avatar FROM contacts", [], |r| r.get(0))
            .unwrap();
        assert_eq!(avatar, "blobs/ab-a.png");
        let d: (u32, i64) = conn
            .query_row("SELECT contact_id, state FROM deliveries", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap();
        assert_eq!(d, (1, 1));
    }
}
