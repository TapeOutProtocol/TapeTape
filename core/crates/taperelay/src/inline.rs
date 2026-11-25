use anyhow::{ensure, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};

use crate::identity::EncKeys;
use crate::sealedbox::{open_box, seal_box};

pub const SEALED_BODY_MAX: usize = 4096;
const MAGIC: u8 = 0xe1;
const SLOTS: u8 = 2;
const SLOT: usize = 80;
const NONCE_AT: usize = 2 + 2 * SLOT;
const HEAD: usize = NONCE_AT + 24;
const TAG: usize = 16;
const MIN: usize = HEAD + TAG + 1;
const LABEL: &[u8] = b"TapeRelay/v1/inline";
pub const MAX_INLINE_PLAINTEXT: usize = SEALED_BODY_MAX / 4 * 3 - HEAD - TAG;

pub struct InlineFixed {
    pub one_time_key: [u8; 32],
    pub nonce: [u8; 24],
    pub ephemeral: [[u8; 32]; 2],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Recipient,
    Sender,
}

pub fn base64url_encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn base64url_decode(s: &str) -> Option<Vec<u8>> {
    let bytes = URL_SAFE_NO_PAD.decode(s).ok()?;
    (URL_SAFE_NO_PAD.encode(&bytes) == s).then_some(bytes)
}

fn inline_bytes(body: &str) -> Option<Vec<u8>> {
    if body.len() > SEALED_BODY_MAX {
        return None;
    }
    let bytes = base64url_decode(body)?;
    (bytes.len() >= MIN && bytes[0] == MAGIC && bytes[1] == SLOTS).then_some(bytes)
}

pub fn inline_body_ok(body: &str) -> bool {
    inline_bytes(body).is_some()
}

fn aad(head: &[u8]) -> Vec<u8> {
    [LABEL, head].concat()
}

pub fn seal_inline(
    plain: &[u8],
    recipient: &[u8; 32],
    own: &[u8; 32],
    fixed: Option<&InlineFixed>,
) -> Result<String> {
    ensure!(!plain.is_empty(), "bad-body");
    let key: [u8; 32] = fixed.map_or_else(rand::random, |f| f.one_time_key);
    let nonce: [u8; 24] = fixed.map_or_else(rand::random, |f| f.nonce);
    let mut out = vec![MAGIC, SLOTS];
    for (i, pk) in [recipient, own].into_iter().enumerate() {
        out.extend(seal_box(&key, pk, fixed.map(|f| &f.ephemeral[i]))?);
    }
    out.extend(nonce);
    let ct = XChaCha20Poly1305::new(&key.into())
        .encrypt(
            &nonce.into(),
            Payload {
                msg: plain,
                aad: &aad(&out),
            },
        )
        .expect("xchacha20poly1305 encrypt");
    out.extend(ct);
    let body = base64url_encode(&out);
    ensure!(body.len() <= SEALED_BODY_MAX, "too-large");
    Ok(body)
}

pub fn open_inline(body: &str, keys: &EncKeys, role: Role) -> Option<Vec<u8>> {
    let bytes = inline_bytes(body)?;
    let (head, ct) = bytes.split_at(HEAD);
    let slot = 2 + SLOT * role as usize;
    let key = open_box(&head[slot..slot + SLOT], &keys.public(), &keys.secret())?;
    let key: [u8; 32] = key.try_into().ok()?;
    XChaCha20Poly1305::new(&key.into())
        .decrypt(
            XNonce::from_slice(&head[NONCE_AT..]),
            Payload {
                msg: ct,
                aad: &aad(head),
            },
        )
        .ok()
}
