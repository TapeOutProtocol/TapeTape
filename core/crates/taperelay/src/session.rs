use sha2::{Digest, Sha256};

use crate::eip712::request_digest;
use crate::identity::Identity;

#[allow(clippy::too_many_arguments)]
pub fn signed_headers(
    id: &Identity,
    server: &str,
    method: &str,
    path: &str,
    query: &str,
    body: &[u8],
    now: u64,
    nonce: [u8; 16],
) -> Vec<(String, String)> {
    let body_hash: [u8; 32] = Sha256::digest(body).into();
    let digest = request_digest(
        id.account(),
        server,
        method,
        path,
        query,
        &body_hash,
        now,
        &nonce,
    );
    vec![
        ("TR-Account".to_string(), id.account().to_string()),
        ("TR-Timestamp".to_string(), now.to_string()),
        ("TR-Nonce".to_string(), hex::encode(nonce)),
        ("TR-Signature".to_string(), id.sign_digest(&digest)),
    ]
}
