use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{anyhow, ensure, Context, Result};
use log::{info, warn};
use rusqlite::{params, Connection, OptionalExtension};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use taperelay::{
    EncKeys, FileObject, Identity, UploadRequest, ENCRYPTED_CHUNK, MAX_RECIPIENTS, OBJECT_MAX,
};

use crate::account::Account;
use crate::api::*;
use crate::chat::recipients;
use crate::protocol::{now_ms, Body, MAX_ATTACHMENT};
use crate::store;

const SIGN_PATH: &str = "/api/relay/sign";
const SIGN_SERVER: &str = "tapetape-backend";
const BACKEND_KEY: &str = "relay_files_backend";
const REF_VERSION: u8 = 1;
pub(crate) const MAX_FILE: u64 = 100 << 20;
const MAX_SERVERS: usize = 8;
const NEED: usize = 2;
const SIGN_BYTES: usize = 2 << 20;
const RETRY_SECS: [u64; 3] = [5, 30, 120];
const HTTP_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_REPLY: usize = 64 << 10;

pub(crate) const UPLOAD: i64 = 0;
pub(crate) const DOWNLOAD: i64 = 1;
pub(crate) const WAITING: i64 = 0;
pub(crate) const ACTIVE: i64 = 1;
pub(crate) const FAILED: i64 = 2;

pub(crate) fn download_state(code: Option<i64>) -> Option<DownloadState> {
    match code? {
        WAITING => Some(DownloadState::Available),
        ACTIVE => Some(DownloadState::Downloading),
        _ => Some(DownloadState::Failed),
    }
}

pub(crate) fn backend(conn: &Connection) -> Option<String> {
    store::get_config(conn, BACKEND_KEY)
        .ok()
        .flatten()
        .map(|b| b.trim().trim_end_matches('/').to_string())
        .filter(|b| !b.is_empty())
}

pub(crate) fn max_size(conn: &Connection) -> u64 {
    if backend(conn).is_some() {
        MAX_FILE
    } else {
        MAX_ATTACHMENT
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Reference {
    envelopes: Vec<[u8; 32]>,
    servers: Vec<String>,
}

fn valid_server(name: &str) -> bool {
    let Some((host, port)) = name.split_once(':') else {
        return false;
    };
    name.len() <= 255
        && !host.is_empty()
        && host
            .bytes()
            .all(|c| matches!(c, b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-'))
        && !port.starts_with('0')
        && port.parse::<u16>().is_ok_and(|p| p > 0)
}

impl Reference {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = vec![REF_VERSION, self.envelopes.len() as u8];
        self.envelopes.iter().for_each(|e| out.extend(e));
        out.push(self.servers.len() as u8);
        for s in &self.servers {
            out.push(s.len() as u8);
            out.extend(s.as_bytes());
        }
        out
    }

    pub fn decode(mut b: &[u8]) -> Result<Reference> {
        fn take<'a>(b: &mut &'a [u8], n: usize) -> Result<&'a [u8]> {
            let (head, rest) = b.split_at_checked(n).context("Truncated file reference")?;
            *b = rest;
            Ok(head)
        }
        ensure!(
            take(&mut b, 1)? == [REF_VERSION],
            "Unsupported file reference version"
        );
        let count = take(&mut b, 1)?[0];
        ensure!(count > 0, "File reference without envelope");
        let envelopes = (0..count)
            .map(|_| Ok(take(&mut b, 32)?.try_into()?))
            .collect::<Result<Vec<[u8; 32]>>>()?;
        let count = usize::from(take(&mut b, 1)?[0]);
        ensure!(
            (1..=MAX_SERVERS).contains(&count),
            "Bad server count in file reference"
        );
        let servers = (0..count)
            .map(|_| {
                let len = usize::from(take(&mut b, 1)?[0]);
                let name = std::str::from_utf8(take(&mut b, len)?)?;
                ensure!(valid_server(name), "Bad server {name:?} in file reference");
                Ok(name.to_string())
            })
            .collect::<Result<Vec<_>>>()?;
        ensure!(b.is_empty(), "Trailing bytes in file reference");
        Ok(Reference { envelopes, servers })
    }
}

#[derive(Debug, Deserialize)]
struct Circuit {
    account: String,
    servers: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct Signed {
    signatures: Vec<String>,
    delegation: Option<String>,
}

#[derive(Debug)]
struct Failure {
    transient: bool,
    code: String,
    error: anyhow::Error,
}

impl Failure {
    fn transient(error: anyhow::Error) -> Self {
        Failure {
            transient: true,
            code: "upload-failed".into(),
            error,
        }
    }

    fn reply(what: &str, status: u16, reply: &[u8]) -> Self {
        let code = serde_json::from_slice::<serde_json::Value>(reply)
            .ok()
            .and_then(|v| v.get("error")?.as_str().map(str::to_string))
            .unwrap_or_else(|| format!("http-{status}"));
        Failure {
            transient: status >= 500
                || matches!(
                    code.as_str(),
                    "rate-limited" | "chain-unavailable" | "internal"
                ),
            error: anyhow!("{what} HTTP {status} {code}"),
            code,
        }
    }
}

fn server_base(server: &str) -> String {
    if cfg!(test) {
        format!("http://{server}")
    } else {
        taperelay::server_url(server)
    }
}

async fn request(
    http: &reqwest::Client,
    method: reqwest::Method,
    url: &str,
    headers: Vec<(String, String)>,
    body: Option<(&str, Vec<u8>)>,
    limit: usize,
) -> Result<(u16, Vec<u8>)> {
    let mut req = http.request(method, url).timeout(HTTP_TIMEOUT);
    for (k, v) in headers {
        req = req.header(k, v);
    }
    if let Some((content_type, bytes)) = body {
        req = req
            .header(reqwest::header::CONTENT_TYPE, content_type)
            .body(bytes);
    }
    let mut res = req.send().await?;
    let status = res.status().as_u16();
    let mut out = Vec::new();
    while let Some(chunk) = res.chunk().await? {
        ensure!(
            out.len() + chunk.len() <= limit,
            "Reply from {url} is too large"
        );
        out.extend_from_slice(&chunk);
    }
    Ok((status, out))
}

async fn circuit(http: &reqwest::Client, backend: &str) -> Result<Circuit, Failure> {
    let url = format!("{backend}{SIGN_PATH}");
    let (status, reply) = request(
        http,
        reqwest::Method::GET,
        &url,
        Vec::new(),
        None,
        MAX_REPLY,
    )
    .await
    .map_err(Failure::transient)?;
    if status != 200 {
        return Err(Failure::reply("Relay files backend", status, &reply));
    }
    let c: Circuit = serde_json::from_slice(&reply).map_err(|e| Failure::transient(e.into()))?;
    if !(taperelay::is_circuit(&c.account)
        && (1..=MAX_SERVERS).contains(&c.servers.len())
        && c.servers.iter().all(|s| valid_server(s)))
    {
        return Err(Failure::reply(
            "Relay files backend",
            502,
            b"{\"error\":\"bad-circuit\"}",
        ));
    }
    Ok(c)
}

struct Job {
    http: reqwest::Client,
    me: Identity,
    backend: String,
    account: String,
    objects: Vec<FileObject>,
    total: u64,
    progress: Mutex<Vec<u64>>,
    report: Box<dyn Fn(u64, u64) + Send + Sync>,
}

fn next_batch(objects: &[FileObject], done: usize) -> &[FileObject] {
    let mut size = 0;
    let mut n = 0;
    for o in &objects[done..] {
        if n > 0 && size + o.bytes.len() > SIGN_BYTES {
            break;
        }
        size += o.bytes.len();
        n += 1;
    }
    &objects[done..done + n]
}

impl Job {
    async fn sign(&self, requests: &[UploadRequest]) -> Result<Signed, Failure> {
        let body = taperelay::remote_batch_json(requests);
        let headers = taperelay::signed_headers(
            &self.me,
            SIGN_SERVER,
            "POST",
            SIGN_PATH,
            "",
            body.as_bytes(),
            (now_ms() / 1000) as u64,
            rand::random(),
        );
        let url = format!("{}{SIGN_PATH}", self.backend);
        let (status, reply) = request(
            &self.http,
            reqwest::Method::POST,
            &url,
            headers,
            Some(("application/json", body.into_bytes())),
            MAX_REPLY,
        )
        .await
        .map_err(Failure::transient)?;
        if status != 200 {
            return Err(Failure::reply("Signing", status, &reply));
        }
        let signed: Signed =
            serde_json::from_slice(&reply).map_err(|e| Failure::transient(e.into()))?;
        if signed.signatures.len() != requests.len() {
            return Err(Failure::transient(anyhow!("Signing reply count mismatch")));
        }
        Ok(signed)
    }

    fn advance(&self, slot: usize, bytes: usize) {
        let done = {
            let mut p = self.progress.lock().unwrap();
            p[slot] += bytes as u64;
            let mut sorted = p.clone();
            sorted.sort_unstable_by(|a, b| b.cmp(a));
            sorted[NEED.min(sorted.len()) - 1]
        };
        (self.report)(done, self.total);
    }

    async fn upload_from(
        &self,
        slot: usize,
        server: &str,
        done: &mut usize,
    ) -> Result<(), Failure> {
        while *done < self.objects.len() {
            let batch = next_batch(&self.objects, *done);
            let now = (now_ms() / 1000) as u64;
            let requests: Vec<UploadRequest> = batch
                .iter()
                .map(|o| UploadRequest {
                    account: self.account.clone(),
                    server: server.to_string(),
                    object: o.sha256,
                    timestamp: now,
                    nonce: rand::random(),
                })
                .collect();
            let signed = self.sign(&requests).await?;
            for ((req, sig), o) in requests.iter().zip(&signed.signatures).zip(batch) {
                let url = format!("{}/v1/objects", server_base(server));
                let headers = req.headers(sig, signed.delegation.as_deref());
                let (status, reply) = request(
                    &self.http,
                    reqwest::Method::POST,
                    &url,
                    headers,
                    Some(("application/octet-stream", o.bytes.clone())),
                    MAX_REPLY,
                )
                .await
                .map_err(Failure::transient)?;
                if !matches!(status, 200 | 201) {
                    return Err(Failure::reply("Upload", status, &reply));
                }
                *done += 1;
                self.advance(slot, o.bytes.len());
            }
        }
        Ok(())
    }

    async fn replicate(&self, slot: usize, server: &str) -> Result<(), Failure> {
        let mut done = 0;
        let mut attempt = 0;
        loop {
            let failure = match self.upload_from(slot, server, &mut done).await {
                Ok(()) => {
                    info!("stored {} file objects on {server}", self.objects.len());
                    return Ok(());
                }
                Err(f) => f,
            };
            match RETRY_SECS.get(attempt).filter(|_| failure.transient) {
                Some(secs) => {
                    warn!(
                        "upload to {server} failed, retrying in {secs}s: {:#}",
                        failure.error
                    );
                    tokio::time::sleep(Duration::from_secs(*secs)).await;
                    attempt += 1;
                }
                None => {
                    warn!("upload to {server} failed: {:#}", failure.error);
                    return Err(failure);
                }
            }
        }
    }
}

async fn store_objects(job: Job, servers: Vec<String>) -> Result<(), Failure> {
    let need = NEED.min(servers.len());
    let job = Arc::new(job);
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    for (slot, server) in servers.into_iter().enumerate() {
        let (job, tx) = (job.clone(), tx.clone());
        tokio::spawn(async move {
            let r = job.replicate(slot, &server).await;
            tx.send(r).ok();
        });
    }
    drop(tx);
    let mut ok = 0;
    let mut last = None;
    while ok < need {
        match rx.recv().await {
            Some(Ok(())) => ok += 1,
            Some(Err(f)) => last = Some(f),
            None => break,
        }
    }
    match last {
        Some(f) if ok < need => Err(f),
        _ => Ok(()),
    }
}

async fn upload(
    http: &reqwest::Client,
    me: &Identity,
    backend: &str,
    plain: &[u8],
    own: &[u8; 32],
    to: &[[u8; 32]],
    report: Box<dyn Fn(u64, u64) + Send + Sync>,
) -> Result<Vec<u8>, Failure> {
    let c = circuit(http, backend).await?;
    let fatal = |e: anyhow::Error| Failure {
        transient: false,
        code: "upload-failed".into(),
        error: e,
    };
    let mut groups = to.chunks(MAX_RECIPIENTS - 1);
    let plan = taperelay::plan_encrypted_file(
        plain,
        &c.account,
        own,
        groups.next().unwrap_or_default(),
        None,
    )
    .map_err(fatal)?;
    let mut envelopes = vec![plan.envelope.clone()];
    for group in groups {
        envelopes.push(plan.envelope_for(own, group, None).map_err(fatal)?);
    }
    let reference = Reference {
        envelopes: envelopes.iter().map(|e| e.sha256).collect(),
        servers: c.servers.clone(),
    }
    .encode();
    let objects: Vec<FileObject> = std::iter::once(plan.index)
        .chain(plan.chunks)
        .chain(envelopes)
        .collect();
    let total = objects.iter().map(|o| o.bytes.len() as u64).sum();
    let count = objects.len();
    let job = Job {
        http: http.clone(),
        me: me.clone(),
        backend: backend.to_string(),
        account: c.account,
        objects,
        total,
        progress: Mutex::new(vec![0; c.servers.len()]),
        report,
    };
    store_objects(job, c.servers).await?;
    info!("stored file of {} bytes as {count} objects", plain.len());
    Ok(reference)
}

async fn get_object(http: &reqwest::Client, servers: &[String], sha: &[u8; 32]) -> Result<Vec<u8>> {
    let mut errors = Vec::new();
    for server in servers {
        let url = format!("{}/v1/objects/{}", server_base(server), hex::encode(sha));
        match request(
            http,
            reqwest::Method::GET,
            &url,
            Vec::new(),
            None,
            OBJECT_MAX,
        )
        .await
        {
            Ok((200, body)) if Sha256::digest(&body).as_slice() == sha => return Ok(body),
            Ok((200, _)) => errors.push(format!("{server}: hash mismatch")),
            Ok((status, _)) => errors.push(format!("{server}: HTTP {status}")),
            Err(e) => errors.push(format!("{server}: {e:#}")),
        }
    }
    Err(anyhow!(
        "Object {} unavailable: {}",
        hex::encode(sha),
        errors.join("; ")
    ))
}

async fn download(
    http: &reqwest::Client,
    reference: &[u8],
    trusted: &[String],
    keys: &EncKeys,
    mut report: impl FnMut(u64, u64),
) -> Result<Vec<u8>> {
    let mut r = Reference::decode(reference)?;
    let all = r.servers.clone();
    r.servers.retain(|s| trusted.contains(s));
    ensure!(
        !r.servers.is_empty(),
        "File is on servers outside the backend's list: {}",
        all.join(", ")
    );
    let mut opened = None;
    for sha in &r.envelopes {
        let bytes = get_object(http, &r.servers, sha).await?;
        match taperelay::open_envelope(&bytes, keys) {
            Err(e) if e.to_string() == "no-slot" => continue,
            res => {
                opened = Some(res?);
                break;
            }
        }
    }
    let env = opened.context("File is not encrypted to us")?;
    let size = env.manifest.size;
    ensure!(size <= MAX_FILE, "File of {size} bytes is over the limit");
    let index = get_object(http, &r.servers, &env.manifest.index).await?;
    let mut chunks = Vec::new();
    for (i, sha) in env.chunk_hashes(&index)?.iter().enumerate() {
        chunks.push(get_object(http, &r.servers, sha).await?);
        report(((i + 1) as u64 * ENCRYPTED_CHUNK as u64).min(size), size);
    }
    env.read(&index, &chunks)
}

impl Account {
    pub(crate) fn wants_upload(&self, conn: &Connection, msg_id: u32, size: u64) -> Result<bool> {
        if size <= MAX_ATTACHMENT || size > max_size(conn) {
            return Ok(false);
        }
        conn.execute(
            "INSERT OR REPLACE INTO transfers (msg_id, dir, state, reference, size) VALUES (?,?,?,NULL,?)",
            params![msg_id, UPLOAD, ACTIVE, size],
        )?;
        self.wake_transfers();
        Ok(true)
    }

    pub(crate) fn add_download(
        &self,
        conn: &Connection,
        msg_id: u32,
        reference: &[u8],
        size: u64,
    ) -> Result<()> {
        let state = if backend(conn).is_some() && size <= crate::settings::auto_download_limit(conn)
        {
            ACTIVE
        } else {
            WAITING
        };
        conn.execute(
            "INSERT OR REPLACE INTO transfers (msg_id, dir, state, reference, size) VALUES (?,?,?,?,?)",
            params![msg_id, DOWNLOAD, state, reference, size],
        )?;
        if state == ACTIVE {
            self.wake_transfers();
        }
        Ok(())
    }

    pub(crate) async fn transfer_loop(self: Arc<Self>) {
        loop {
            if let Err(e) = self.run_transfers().await {
                warn!("file transfers failed: {e:#}");
            }
            tokio::select! {
                _ = self.transfers.notified() => {}
                _ = tokio::time::sleep(Duration::from_secs(300)) => {}
            }
        }
    }

    pub(crate) async fn run_transfers(self: &Arc<Self>) -> Result<()> {
        loop {
            let next: Option<(u32, i64)> = self
                .db()
                .query_row(
                    "SELECT msg_id, dir FROM transfers WHERE state=? ORDER BY dir, msg_id LIMIT 1",
                    [ACTIVE],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            match next {
                None => return Ok(()),
                Some((msg_id, UPLOAD)) => self.upload_message(msg_id).await?,
                Some((msg_id, _)) => self.download_file(msg_id).await?,
            }
        }
    }

    fn progress(self: &Arc<Self>, msg_id: u32) -> impl Fn(u64, u64) + Send + Sync + 'static {
        let me = self.clone();
        move |done, total| {
            me.emit(vec![Event::TransferProgress {
                msg_id,
                done,
                total,
            }])
        }
    }

    async fn upload_message(self: &Arc<Self>, msg_id: u32) -> Result<()> {
        let prepared = {
            let conn = self.db();
            match self.outgoing_envelope(&conn, msg_id) {
                Ok((chat_id, env, blob)) => Some((
                    chat_id,
                    env,
                    blob,
                    recipients(&conn, chat_id)?,
                    backend(&conn),
                )),
                Err(e) => {
                    warn!("message {msg_id} cannot be uploaded: {e:#}");
                    None
                }
            }
        };
        let Some((chat_id, mut env, blob, to, backend)) = prepared else {
            return self.fail_upload(msg_id, "upload-failed");
        };
        let Some(backend) = backend else {
            return self.fail_upload(msg_id, "too-large");
        };
        let mut keys = Vec::new();
        for r in &to {
            if let Some(k) = self.peer_key(r).await? {
                keys.push(k);
            }
        }
        info!(
            "uploading message {msg_id} ({} bytes) for {} recipient(s)",
            blob.len(),
            keys.len()
        );
        let result = upload(
            &self.http(),
            &self.keys.id,
            &backend,
            &blob,
            &self.keys.enc.public(),
            &keys,
            Box::new(self.progress(msg_id)),
        )
        .await;
        let reference = match result {
            Ok(r) => r,
            Err(f) => {
                warn!("upload of message {msg_id} failed: {:#}", f.error);
                return self.fail_upload(msg_id, &f.code);
            }
        };
        if let Body::Media { reference: r, .. } = &mut env.body {
            *r = Some(taperelay::base64url_encode(&reference));
        }
        let mut conn = self.db();
        let tx = conn.transaction()?;
        if tx.execute(
            "DELETE FROM transfers WHERE msg_id=? AND dir=?",
            params![msg_id, UPLOAD],
        )? == 0
        {
            return Ok(());
        }
        for r in &to {
            self.enqueue(&tx, r, Some(msg_id), &env, &[])?;
        }
        tx.commit()?;
        drop(conn);
        info!("message {msg_id} uploaded, chat {chat_id}");
        Ok(())
    }

    fn fail_upload(&self, msg_id: u32, reason: &str) -> Result<()> {
        let conn = self.db();
        conn.execute(
            "DELETE FROM transfers WHERE msg_id=? AND dir=?",
            params![msg_id, UPLOAD],
        )?;
        let pending: Vec<String> = {
            let mut stmt = conn.prepare(
                "SELECT c.account FROM deliveries d JOIN contacts c ON c.id=d.contact_id WHERE d.msg_id=?",
            )?;
            let rows = stmt.query_map([msg_id], |r| r.get(0))?;
            rows.collect::<Result<_, _>>()?
        };
        let mut events = Vec::new();
        for r in pending {
            events.extend(crate::account::fail_delivery(&conn, msg_id, &r, reason)?);
        }
        drop(conn);
        self.emit(events);
        Ok(())
    }

    async fn download_file(self: &Arc<Self>, msg_id: u32) -> Result<()> {
        let row: Option<(Vec<u8>, String, u32)> = self
            .db()
            .query_row(
                "SELECT t.reference, COALESCE(m.file_name, 'file'), m.chat_id FROM transfers t JOIN messages m ON m.id=t.msg_id
                 WHERE t.msg_id=? AND t.dir=?",
                params![msg_id, DOWNLOAD],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        let Some((reference, name, chat_id)) = row else {
            self.db()
                .execute("DELETE FROM transfers WHERE msg_id=?", [msg_id])?;
            return Ok(());
        };
        let backend = backend(&self.db());
        let http = self.http();
        let result = async {
            let backend = backend.context("No relay files backend configured")?;
            let trusted = circuit(&http, &backend).await.map_err(|f| f.error)?.servers;
            download(
                &http,
                &reference,
                &trusted,
                &self.keys.enc,
                self.progress(msg_id),
            )
            .await
        }
        .await;
        let conn = self.db();
        match result.and_then(|plain| Ok((self.save_blob(&name, &plain)?, plain.len()))) {
            Ok((file, size)) => {
                conn.execute(
                    "UPDATE messages SET file=?, size=? WHERE id=?",
                    params![file, size as i64, msg_id],
                )?;
                conn.execute("DELETE FROM transfers WHERE msg_id=?", [msg_id])?;
                info!("message {msg_id} downloaded ({size} bytes)");
            }
            Err(e) => {
                warn!("download of message {msg_id} failed: {e:#}");
                conn.execute(
                    "UPDATE transfers SET state=? WHERE msg_id=?",
                    params![FAILED, msg_id],
                )?;
            }
        }
        drop(conn);
        self.emit(vec![Event::MessagesChanged { chat_id }]);
        Ok(())
    }
}

#[uniffi::export]
impl Account {
    pub fn relay_files_backend(&self) -> String {
        backend(&self.db()).unwrap_or_default()
    }

    pub fn set_relay_files_backend(&self, url: String) -> Result<(), CoreError> {
        store::set_config(&self.db(), BACKEND_KEY, url.trim())?;
        info!("account {} relay files backend set", self.id);
        Ok(())
    }

    pub fn max_attachment_size(&self) -> u64 {
        max_size(&self.db())
    }

    pub fn download(&self, msg_id: u32) -> Result<(), CoreError> {
        let conn = self.db();
        let changed = conn.execute(
            "UPDATE transfers SET state=? WHERE msg_id=? AND dir=? AND state<>?",
            params![ACTIVE, msg_id, DOWNLOAD, ACTIVE],
        )?;
        let chat_id: u32 =
            conn.query_row("SELECT chat_id FROM messages WHERE id=?", [msg_id], |r| {
                r.get(0)
            })?;
        drop(conn);
        if changed > 0 {
            self.wake_transfers();
            self.emit(vec![Event::MessagesChanged { chat_id }]);
        }
        Ok(())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    fn reference() -> Reference {
        Reference {
            envelopes: vec![[7; 32]],
            servers: vec![
                "relay1.taperelay.com:4246".into(),
                "relay2.taperelay.com:4246".into(),
            ],
        }
    }

    #[test]
    fn reference_roundtrip() {
        let r = reference();
        let blob = r.encode();
        assert_eq!(blob.len(), 2 + 32 + 1 + 2 * 26);
        assert_eq!(Reference::decode(&blob).unwrap(), r);
        assert!(Reference::decode(&blob[..blob.len() - 1]).is_err());
        assert!(Reference::decode(&[blob.as_slice(), &[0]].concat()).is_err());
        let mut v2 = blob.clone();
        v2[0] = 2;
        assert!(Reference::decode(&v2).is_err());
        let mut none = blob.clone();
        none[1] = 0;
        assert!(Reference::decode(&none).is_err());
        for bad in [
            "evil.com/x:443",
            "Relay.com:443",
            "a.b:0",
            "a.b:080",
            "a.b",
            ":443",
        ] {
            let r = Reference {
                envelopes: vec![[7; 32]],
                servers: vec![bad.into()],
            };
            assert!(Reference::decode(&r.encode()).is_err(), "{bad}");
        }
    }

    #[test]
    fn batches_respect_sign_budget() {
        let obj = |n: usize| FileObject {
            sha256: [0; 32],
            bytes: vec![0; n],
        };
        let objects = vec![
            obj(72),
            obj(OBJECT_MAX),
            obj(OBJECT_MAX),
            obj(OBJECT_MAX),
            obj(500),
        ];
        assert_eq!(next_batch(&objects, 0).len(), 2);
        assert_eq!(next_batch(&objects, 2).len(), 2);
        assert_eq!(next_batch(&objects, 4).len(), 1);
        assert!(next_batch(&objects, 5).is_empty());
        assert_eq!(next_batch(&[obj(3 << 20)], 0).len(), 1);
    }

    #[test]
    fn failure_classes() {
        assert!(Failure::reply("x", 503, b"").transient);
        assert!(Failure::reply("x", 429, br#"{"error":"rate-limited"}"#).transient);
        let quota = Failure::reply("x", 429, br#"{"error":"quota-exceeded"}"#);
        assert!(!quota.transient);
        assert_eq!(quota.code, "quota-exceeded");
        assert!(!Failure::reply("x", 401, br#"{"error":"bad-auth"}"#).transient);
        assert_eq!(
            Failure::reply("Upload", 400, b"nope").error.to_string(),
            "Upload HTTP 400 http-400"
        );
    }
}

#[cfg(test)]
pub(crate) mod fake {
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};

    use super::*;

    #[derive(Default)]
    pub struct State {
        pub objects: Mutex<HashMap<String, Vec<u8>>>,
        pub signed: AtomicUsize,
        pub quota_exceeded: AtomicBool,
        servers: Mutex<Vec<String>>,
    }

    pub struct Backend {
        pub url: String,
        pub state: Arc<State>,
    }

    async fn read_request(s: &mut TcpStream) -> Option<(String, Vec<u8>)> {
        let mut buf = Vec::new();
        let mut chunk = [0u8; 65536];
        loop {
            let n = s.read(&mut chunk).await.ok()?;
            if n == 0 {
                return None;
            }
            buf.extend_from_slice(&chunk[..n]);
            if let Some(end) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                let head = String::from_utf8_lossy(&buf[..end]).to_string();
                let len: usize = head
                    .lines()
                    .find_map(|l| {
                        l.to_lowercase()
                            .strip_prefix("content-length: ")
                            .map(|v| v.parse().unwrap())
                    })
                    .unwrap_or(0);
                while buf.len() < end + 4 + len {
                    let n = s.read(&mut chunk).await.ok()?;
                    buf.extend_from_slice(&chunk[..n]);
                }
                return Some((head, buf[end + 4..end + 4 + len].to_vec()));
            }
        }
    }

    fn header<'a>(head: &'a str, name: &str) -> &'a str {
        head.lines()
            .find_map(|l| {
                let (k, v) = l.split_once(": ")?;
                k.eq_ignore_ascii_case(name).then_some(v)
            })
            .unwrap_or("")
    }

    fn handle(state: &State, head: &str, body: &[u8]) -> (u16, Vec<u8>) {
        let line = head.lines().next().unwrap_or("");
        let mut parts = line.split(' ');
        let (method, path) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
        match (method, path) {
            ("GET", SIGN_PATH) => {
                let servers = state.servers.lock().unwrap().clone();
                (
                    200,
                    serde_json::to_vec(
                        &serde_json::json!({"account": "1.2.0.tape", "servers": servers}),
                    )
                    .unwrap(),
                )
            }
            ("POST", SIGN_PATH) => {
                let account = header(head, "tr-account");
                let nonce: [u8; 16] = hex::decode(header(head, "tr-nonce"))
                    .unwrap()
                    .try_into()
                    .unwrap();
                let digest = taperelay::request_digest(
                    account,
                    SIGN_SERVER,
                    "POST",
                    SIGN_PATH,
                    "",
                    &Sha256::digest(body).into(),
                    header(head, "tr-timestamp").parse().unwrap(),
                    &nonce,
                );
                if taperelay::recover(&digest, header(head, "tr-signature")).as_deref()
                    != taperelay::self_key_of(account)
                {
                    return (401, br#"{"error":"bad-auth"}"#.to_vec());
                }
                if state.quota_exceeded.load(Ordering::SeqCst) {
                    return (
                        429,
                        br#"{"error":"quota-exceeded","message":"daily quota"}"#.to_vec(),
                    );
                }
                let batch: serde_json::Value = serde_json::from_slice(body).unwrap();
                let n = batch["requests"].as_array().unwrap().len();
                assert!(batch["requests"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .all(|r| r["message"]["path"] == "/v1/objects"
                        && r["message"]["account"] == "1.2.0.tape"));
                state.signed.fetch_add(n, Ordering::SeqCst);
                let sigs: Vec<String> = (0..n).map(|i| format!("0x{i:02x}")).collect();
                (200, serde_json::to_vec(&serde_json::json!({"signatures": sigs, "delegation": "ZGVs", "servers": []})).unwrap())
            }
            ("POST", "/v1/objects") => {
                let sha = hex::encode(Sha256::digest(body));
                if header(head, "tr-object") != sha || header(head, "tr-delegation") != "ZGVs" {
                    return (400, br#"{"error":"bad-object"}"#.to_vec());
                }
                state.objects.lock().unwrap().insert(sha, body.to_vec());
                (201, b"{}".to_vec())
            }
            ("GET", p) if p.starts_with("/v1/objects/") => {
                match state
                    .objects
                    .lock()
                    .unwrap()
                    .get(&p["/v1/objects/".len()..])
                {
                    Some(b) => (200, b.clone()),
                    None => (404, b"{}".to_vec()),
                }
            }
            _ => (404, b"{}".to_vec()),
        }
    }

    async fn serve(listener: TcpListener, state: Arc<State>) {
        loop {
            let Ok((mut s, _)) = listener.accept().await else {
                return;
            };
            let state = state.clone();
            tokio::spawn(async move {
                while let Some((head, body)) = read_request(&mut s).await {
                    let (status, reply) = handle(&state, &head, &body);
                    let out = [
                        format!("HTTP/1.1 {status} X\r\ncontent-type: application/json\r\ncontent-length: {}\r\n\r\n", reply.len())
                            .into_bytes(),
                        reply,
                    ]
                    .concat();
                    if s.write_all(&out).await.is_err() {
                        return;
                    }
                }
            });
        }
    }

    pub async fn start() -> Backend {
        let state = Arc::new(State::default());
        let mut names = Vec::new();
        for _ in 0..2 {
            let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
            names.push(l.local_addr().unwrap().to_string());
            tokio::spawn(serve(l, state.clone()));
        }
        *state.servers.lock().unwrap() = names.clone();
        Backend {
            url: format!("http://{}/", names[0]),
            state,
        }
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn group_sized_upload_and_download() {
        let fake = start().await;
        let http = crate::transport::http_client();
        let me = Identity::generate("relay1.taperelay.com:4246").unwrap();
        let own = EncKeys::generate();
        let members: Vec<EncKeys> = (0..20).map(|_| EncKeys::generate()).collect();
        let keys: Vec<[u8; 32]> = members.iter().map(|k| k.public()).collect();
        let plain: Vec<u8> = (0..(ENCRYPTED_CHUNK * 2 + 1234))
            .map(|_| rand::random())
            .collect();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let s2 = seen.clone();
        let reference = upload(
            &http,
            &me,
            fake.url.trim_end_matches('/'),
            &plain,
            &own.public(),
            &keys,
            Box::new(move |d, t| s2.lock().unwrap().push((d, t))),
        )
        .await
        .unwrap();
        let r = Reference::decode(&reference).unwrap();
        assert_eq!(r.envelopes.len(), 2);
        assert_eq!(r.servers.len(), 2);
        let progress = seen.lock().unwrap().clone();
        let total = progress[0].1;
        assert_eq!(progress.last().unwrap().0, total);
        assert!(progress.windows(2).all(|w| w[0].0 <= w[1].0));
        assert_eq!(fake.state.objects.lock().unwrap().len(), 1 + 3 + 2);
        for k in [&own, &members[0], &members[19]] {
            let mut got = Vec::new();
            let out = download(&http, &reference, &r.servers, k, |d, t| got.push((d, t)))
                .await
                .unwrap();
            assert!(out == plain);
            assert_eq!(got.last(), Some(&(plain.len() as u64, plain.len() as u64)));
        }
        assert!(download(
            &http,
            &reference,
            &r.servers,
            &EncKeys::generate(),
            |_, _| {}
        )
        .await
        .is_err());
        let trusted = vec![r.servers[1].clone(), "elsewhere.example:4246".into()];
        assert!(
            download(&http, &reference, &trusted, &own, |_, _| {})
                .await
                .unwrap()
                == plain
        );
        let untrusted = download(
            &http,
            &reference,
            &["elsewhere.example:4246".into()],
            &own,
            |_, _| {},
        )
        .await
        .unwrap_err()
        .to_string();
        assert!(
            untrusted.contains("outside the backend's list"),
            "{untrusted}"
        );
        fake.state
            .quota_exceeded
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let f = upload(
            &http,
            &me,
            fake.url.trim_end_matches('/'),
            b"x",
            &own.public(),
            &[],
            Box::new(|_, _| {}),
        )
        .await
        .unwrap_err();
        assert_eq!((f.code.as_str(), f.transient), ("quota-exceeded", false));
    }
}
