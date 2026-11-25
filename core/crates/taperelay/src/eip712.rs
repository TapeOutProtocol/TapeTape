use k256::ecdsa::{RecoveryId, Signature, VerifyingKey};
use sha3::{Digest, Keccak256};

pub(crate) const MAX_SAFE_INTEGER: u64 = (1 << 53) - 1;

pub(crate) fn keccak256(data: &[u8]) -> [u8; 32] {
    Keccak256::digest(data).into()
}

fn string(v: &str) -> [u8; 32] {
    keccak256(v.as_bytes())
}

fn uint(v: u64) -> [u8; 32] {
    let mut w = [0u8; 32];
    w[24..].copy_from_slice(&v.to_be_bytes());
    w
}

fn hash_struct(def: &str, words: &[[u8; 32]]) -> [u8; 32] {
    let mut h = Keccak256::new();
    h.update(keccak256(def.as_bytes()));
    for w in words {
        h.update(w);
    }
    h.finalize().into()
}

fn digest(struct_hash: [u8; 32]) -> [u8; 32] {
    let domain = hash_struct(
        "EIP712Domain(string name,string version,uint256 chainId)",
        &[string("TapeRelay"), string("1"), uint(0)],
    );
    let mut h = Keccak256::new();
    h.update([0x19, 0x01]);
    h.update(domain);
    h.update(struct_hash);
    h.finalize().into()
}

#[allow(clippy::too_many_arguments)]
pub fn request_digest(
    account: &str,
    server: &str,
    method: &str,
    path: &str,
    query: &str,
    body_hash: &[u8; 32],
    timestamp: u64,
    nonce: &[u8; 16],
) -> [u8; 32] {
    let mut nonce_word = [0u8; 32];
    nonce_word[..16].copy_from_slice(nonce);
    digest(hash_struct(
        "Request(string account,string server,string method,string path,string query,bytes32 bodyHash,uint64 timestamp,bytes16 nonce)",
        &[
            string(account),
            string(server),
            string(method),
            string(path),
            string(query),
            *body_hash,
            uint(timestamp),
            nonce_word,
        ],
    ))
}

pub fn self_notice_digest(from: &str, to: &str, body: &str, created_at: u64) -> [u8; 32] {
    digest(hash_struct(
        "SelfNotice(string from,string to,string body,uint64 createdAt)",
        &[string(from), string(to), string(body), uint(created_at)],
    ))
}

pub fn self_key_digest(account: &str, key: &[u8; 32], created_at: u64) -> [u8; 32] {
    digest(hash_struct(
        "SelfEncryptionKey(string account,bytes32 key,uint64 createdAt)",
        &[string(account), *key, uint(created_at)],
    ))
}

pub(crate) fn lower_hex<const N: usize>(s: &str) -> Option<[u8; N]> {
    let h = s.strip_prefix("0x")?;
    if h.len() != 2 * N || !h.bytes().all(|c| matches!(c, b'0'..=b'9' | b'a'..=b'f')) {
        return None;
    }
    let mut out = [0u8; N];
    hex::decode_to_slice(h, &mut out).ok()?;
    Some(out)
}

pub(crate) fn address_of_key(key: &VerifyingKey) -> String {
    let point = key.to_encoded_point(false);
    format!(
        "0x{}",
        hex::encode(&keccak256(&point.as_bytes()[1..])[12..])
    )
}

pub fn recover(digest: &[u8; 32], signature: &str) -> Option<String> {
    let b = lower_hex::<65>(signature)?;
    let recid = RecoveryId::from_byte(b[64].checked_sub(27).filter(|v| *v < 2)?)?;
    let sig = Signature::from_slice(&b[..64]).ok()?;
    if sig.normalize_s().is_some() {
        return None;
    }
    let key = VerifyingKey::recover_from_prehash(digest, &sig, recid).ok()?;
    Some(address_of_key(&key))
}
