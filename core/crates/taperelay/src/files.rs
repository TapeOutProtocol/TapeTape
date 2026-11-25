use anyhow::{ensure, Context, Result};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand::RngCore;
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::eip712::MAX_SAFE_INTEGER;
use crate::identity::{is_circuit, EncKeys};
use crate::sealedbox::{check_public_key, open_box, seal_box};

pub const ENCRYPTED_CHUNK: usize = 1_048_536;
pub const OBJECT_MAX: usize = 1_048_576;
pub const MAX_RECIPIENTS: usize = 16;
const FANOUT: u64 = 32766;
const MAX_MANIFEST: usize = 16384;
const MAGIC: &[u8; 4] = b"TREN";
const SLOT: usize = 80;
const SLOTS_AT: usize = 38;
const NONCE: usize = 24;
const MIN_OBJECT: usize = NONCE + 16;

pub type Random<'a> = Option<&'a mut dyn FnMut(&mut [u8])>;

struct Rng<'a>(Random<'a>);

impl Rng<'_> {
    fn fill(&mut self, buf: &mut [u8]) {
        match &mut self.0 {
            Some(f) => f(buf),
            None => rand::thread_rng().fill_bytes(buf),
        }
    }

    fn arr<const N: usize>(&mut self) -> [u8; N] {
        let mut b = [0; N];
        self.fill(&mut b);
        b
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileObject {
    pub sha256: [u8; 32],
    pub bytes: Vec<u8>,
}

impl FileObject {
    fn new(bytes: Vec<u8>) -> Self {
        Self {
            sha256: sha256(&bytes),
            bytes,
        }
    }
}

pub struct EncryptedFile {
    pub file_key: [u8; 32],
    pub manifest_json: String,
    pub index: FileObject,
    pub chunks: Vec<FileObject>,
    pub envelope: FileObject,
}

impl EncryptedFile {
    pub fn envelope_for(
        &self,
        own: &[u8; 32],
        to: &[[u8; 32]],
        random: Random,
    ) -> Result<FileObject> {
        seal_envelope(
            &self.file_key,
            self.manifest_json.as_bytes(),
            &recipients(own, to)?,
            &mut Rng(random),
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Manifest {
    pub size: u64,
    pub index: [u8; 32],
    pub from: String,
    pub sha256: Option<[u8; 32]>,
}

pub struct OpenedEnvelope {
    pub file_key: [u8; 32],
    pub manifest: Manifest,
}

#[derive(Serialize)]
struct ManifestJson<'a> {
    version: u8,
    #[serde(rename = "type")]
    kind: &'a str,
    name: &'a str,
    #[serde(rename = "contentType")]
    content_type: &'a str,
    size: usize,
    #[serde(rename = "chunkSize")]
    chunk_size: usize,
    index: &'a str,
    from: &'a str,
}

fn sha256(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn chunk_aad(i: usize, last: bool) -> Vec<u8> {
    let mut aad = b"TapeRelay/v1/chunk".to_vec();
    aad.extend((i as u32).to_be_bytes());
    aad.push(u8::from(last));
    aad
}

fn index_aad() -> Vec<u8> {
    let mut aad = b"TapeRelay/v1/index".to_vec();
    aad.extend([0, 0, 0, 0, 0]);
    aad
}

fn commitment(file_key: &[u8; 32]) -> [u8; 32] {
    Sha256::new()
        .chain_update(b"TapeRelay/v1/commit")
        .chain_update(file_key)
        .finalize()
        .into()
}

fn pad_target(len: usize) -> usize {
    let mut t = 4096;
    while t < len {
        t *= 2;
    }
    t.min(ENCRYPTED_CHUNK)
}

fn seal_object(key: &[u8; 32], aad: &[u8], plain: &[u8], nonce: [u8; NONCE]) -> FileObject {
    let ct = XChaCha20Poly1305::new(key.into())
        .encrypt(&nonce.into(), Payload { msg: plain, aad })
        .expect("xchacha20poly1305 encrypt");
    FileObject::new([nonce.as_slice(), &ct].concat())
}

fn open_object(key: &[u8; 32], aad: &[u8], obj: &[u8]) -> Result<Vec<u8>> {
    ensure!(obj.len() >= MIN_OBJECT, "undecryptable");
    let (nonce, ct) = obj.split_at(NONCE);
    XChaCha20Poly1305::new(key.into())
        .decrypt(XNonce::from_slice(nonce), Payload { msg: ct, aad })
        .ok()
        .context("undecryptable")
}

fn recipients(own: &[u8; 32], to: &[[u8; 32]]) -> Result<Vec<[u8; 32]>> {
    let mut out: Vec<[u8; 32]> = Vec::new();
    for pk in std::iter::once(own).chain(to) {
        check_public_key(pk).context("bad-public-key")?;
        if !out.contains(pk) {
            out.push(*pk);
        }
    }
    ensure!(out.len() <= MAX_RECIPIENTS, "too-many-recipients");
    Ok(out)
}

fn seal_envelope(
    file_key: &[u8; 32],
    manifest: &[u8],
    keys: &[[u8; 32]],
    rng: &mut Rng,
) -> Result<FileObject> {
    let mut slots = Vec::with_capacity(MAX_RECIPIENTS);
    for pk in keys {
        slots.push(seal_box(file_key, pk, Some(&rng.arr()))?);
    }
    let target = [2, 4, 8, 16]
        .into_iter()
        .find(|t| *t >= slots.len())
        .context("too-many-recipients")?;
    while slots.len() < target {
        let mut pad = vec![0; SLOT];
        rng.fill(&mut pad);
        slots.push(pad);
    }
    for i in (1..slots.len()).rev() {
        let j = usize::from(rng.arr::<1>()[0]) % (i + 1);
        slots.swap(i, j);
    }
    let nonce = rng.arr::<NONCE>();
    let mut env = MAGIC.to_vec();
    env.extend([1, slots.len() as u8]);
    env.extend(commitment(file_key));
    slots.iter().for_each(|s| env.extend(s));
    env.extend(nonce);
    let ct = XChaCha20Poly1305::new(file_key.into())
        .encrypt(
            &nonce.into(),
            Payload {
                msg: manifest,
                aad: &env,
            },
        )
        .expect("xchacha20poly1305 encrypt");
    env.extend(ct);
    ensure!(env.len() <= OBJECT_MAX, "too-large");
    Ok(FileObject::new(env))
}

pub fn plan_encrypted_file(
    plain: &[u8],
    from: &str,
    own: &[u8; 32],
    to: &[[u8; 32]],
    random: Random,
) -> Result<EncryptedFile> {
    ensure!(is_circuit(from), "bad-address");
    let keys = recipients(own, to)?;
    let n = plain.len().div_ceil(ENCRYPTED_CHUNK);
    ensure!(n >= 1 && n as u64 <= FANOUT, "bad-size");
    let mut rng = Rng(random);
    let file_key = rng.arr::<32>();
    let mut chunks = Vec::with_capacity(n);
    for (i, pt) in plain.chunks(ENCRYPTED_CHUNK).enumerate() {
        let nonce = rng.arr();
        chunks.push(if i + 1 < n {
            seal_object(&file_key, &chunk_aad(i, false), pt, nonce)
        } else {
            let mut padded = pt.to_vec();
            padded.resize(pad_target(pt.len()), 0);
            seal_object(&file_key, &chunk_aad(i, true), &padded, nonce)
        });
    }
    let hashes: Vec<u8> = chunks.iter().flat_map(|c| c.sha256).collect();
    let index = seal_object(&file_key, &index_aad(), &hashes, rng.arr());
    let manifest_json = serde_json::to_string(&ManifestJson {
        version: 1,
        kind: "encrypted",
        name: "",
        content_type: "application/octet-stream",
        size: plain.len(),
        chunk_size: ENCRYPTED_CHUNK,
        index: &hex::encode(index.sha256),
        from,
    })
    .expect("json of plain values");
    let envelope = seal_envelope(&file_key, manifest_json.as_bytes(), &keys, &mut rng)?;
    Ok(EncryptedFile {
        file_key,
        manifest_json,
        index,
        chunks,
        envelope,
    })
}

fn safe_int(v: &Value) -> Option<u64> {
    let f = v.as_f64()?;
    (f.fract() == 0.0 && (0.0..=MAX_SAFE_INTEGER as f64).contains(&f)).then_some(f as u64)
}

fn hex64(v: &Value) -> Option<[u8; 32]> {
    let s = v.as_str()?;
    if s.len() != 64 || !s.bytes().all(|c| matches!(c, b'0'..=b'9' | b'a'..=b'f')) {
        return None;
    }
    hex::decode(s).ok()?.try_into().ok()
}

fn check_manifest(text: &str) -> Option<Manifest> {
    if text.len() > MAX_MANIFEST {
        return None;
    }
    let m: Value = serde_json::from_str(text).ok()?;
    let m = m.as_object()?;
    let num = |k: &str| m.get(k).and_then(Value::as_f64);
    let is_text = |k: &str| m.get(k).is_some_and(Value::is_string);
    if !(num("version") == Some(1.0)
        && m.get("type").and_then(Value::as_str) == Some("encrypted")
        && num("chunkSize") == Some(ENCRYPTED_CHUNK as f64)
        && is_text("name")
        && is_text("contentType"))
    {
        return None;
    }
    let size = safe_int(m.get("size")?).filter(|s| *s >= 1)?;
    let index = hex64(m.get("index")?)?;
    let sha256 = match m.get("sha256") {
        Some(v) => Some(hex64(v)?),
        None => None,
    };
    let from = m
        .get("from")
        .and_then(Value::as_str)
        .filter(|f| is_circuit(f))?;
    if size.div_ceil(ENCRYPTED_CHUNK as u64) > FANOUT * FANOUT {
        return None;
    }
    Some(Manifest {
        size,
        index,
        from: from.to_string(),
        sha256,
    })
}

pub fn open_envelope(bytes: &[u8], keys: &EncKeys) -> Result<OpenedEnvelope> {
    ensure!(bytes.len() <= OBJECT_MAX, "too-large");
    ensure!(bytes.len() >= 6, "bad-length");
    ensure!(bytes[..4] == MAGIC[..], "bad-magic");
    ensure!(bytes[4] == 1, "bad-version");
    let n = usize::from(bytes[5]);
    ensure!((1..=MAX_RECIPIENTS).contains(&n), "bad-count");
    let end = SLOTS_AT + SLOT * n + NONCE;
    ensure!(bytes.len() >= end + 16, "bad-length");
    let file_key: [u8; 32] = bytes[SLOTS_AT..SLOTS_AT + SLOT * n]
        .chunks(SLOT)
        .find_map(|slot| {
            open_box(slot, &keys.public(), &keys.secret())?
                .try_into()
                .ok()
        })
        .context("no-slot")?;
    ensure!(
        commitment(&file_key) == bytes[6..SLOTS_AT],
        "commitment-mismatch"
    );
    let (head, ct) = bytes.split_at(end);
    let plain = XChaCha20Poly1305::new(&file_key.into())
        .decrypt(
            XNonce::from_slice(&head[end - NONCE..]),
            Payload { msg: ct, aad: head },
        )
        .ok()
        .context("manifest-undecryptable")?;
    let manifest = std::str::from_utf8(&plain)
        .ok()
        .and_then(check_manifest)
        .context("bad-manifest")?;
    Ok(OpenedEnvelope { file_key, manifest })
}

impl OpenedEnvelope {
    fn chunk_count(&self) -> u64 {
        self.manifest.size.div_ceil(ENCRYPTED_CHUNK as u64)
    }

    pub fn chunk_hashes(&self, index: &[u8]) -> Result<Vec<[u8; 32]>> {
        ensure!(sha256(index) == self.manifest.index, "hash-mismatch");
        let pt = open_object(&self.file_key, &index_aad(), index)?;
        ensure!(pt.len() as u64 == self.chunk_count() * 32, "bad-length");
        Ok(pt
            .chunks(32)
            .map(|h| h.try_into().expect("32-byte hash"))
            .collect())
    }

    pub fn read(&self, index: &[u8], chunks: &[Vec<u8>]) -> Result<Vec<u8>> {
        let hashes = self.chunk_hashes(index)?;
        ensure!(chunks.len() == hashes.len(), "bad-length");
        let size = usize::try_from(self.manifest.size)?;
        let last_len = size - (hashes.len() - 1) * ENCRYPTED_CHUNK;
        let mut out = Vec::with_capacity(size);
        for (i, (obj, hash)) in chunks.iter().zip(&hashes).enumerate() {
            ensure!(sha256(obj) == *hash, "hash-mismatch");
            let last = i + 1 == hashes.len();
            let pt = open_object(&self.file_key, &chunk_aad(i, last), obj)?;
            if !last {
                ensure!(pt.len() == ENCRYPTED_CHUNK, "bad-length");
                out.extend(pt);
                continue;
            }
            ensure!(
                (last_len..=ENCRYPTED_CHUNK).contains(&pt.len()),
                "bad-length"
            );
            ensure!(pt[last_len..].iter().all(|b| *b == 0), "bad-padding");
            out.extend(&pt[..last_len]);
        }
        if let Some(whole) = self.manifest.sha256 {
            ensure!(sha256(&out) == whole, "file-hash-mismatch");
        }
        Ok(out)
    }
}

pub fn open_encrypted_file(
    envelope: &[u8],
    index: &[u8],
    chunks: &[Vec<u8>],
    keys: &EncKeys,
) -> Result<Vec<u8>> {
    open_envelope(envelope, keys)?.read(index, chunks)
}
