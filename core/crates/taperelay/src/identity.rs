use std::sync::LazyLock;

use anyhow::{ensure, Result};
use k256::ecdsa::SigningKey;
use regex::Regex;
use x25519_dalek::{x25519, X25519_BASEPOINT_BYTES};

use crate::eip712::address_of_key;

static CIRCUIT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^([1-9][0-9]*)\.(?:([1-9][0-9]*)\.)?(0|[1-9][0-9]*)\.tape$").unwrap()
});

static SELF_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^key:(0x[0-9a-f]{40})@([a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[1-9][0-9]{0,4})?)$").unwrap()
});

fn parse_self(s: &str) -> Option<(&str, &str)> {
    let c = SELF_RE.captures(s)?;
    if s.len() > 128 {
        return None;
    }
    if let Some(port) = c.get(6) {
        if port.as_str()[1..].parse::<u32>().ok()? > 65535 {
            return None;
        }
    }
    Some((c.get(1)?.as_str(), c.get(2)?.as_str()))
}

pub fn is_circuit(s: &str) -> bool {
    let at_most = |v: &str, max: u128| v.parse::<u128>().is_ok_and(|v| v <= max);
    let Some(c) = CIRCUIT_RE.captures(s).filter(|_| s.len() <= 64) else {
        return false;
    };
    at_most(&c[1], 1_000_000_000_000_000_000)
        && at_most(&c[3], 1_000_000_000)
        && c.get(2)
            .is_none_or(|area| matches!(area.as_str(), "2" | "3"))
}

pub fn is_self_account(s: &str) -> bool {
    parse_self(s).is_some()
}

pub fn self_key_of(account: &str) -> Option<&str> {
    parse_self(account).map(|(key, _)| key)
}

pub fn self_host_of(account: &str) -> Option<&str> {
    parse_self(account).map(|(_, host)| host)
}

pub fn node_of(account: &str) -> Option<String> {
    let host = self_host_of(account)?;
    Some(if host.contains(':') {
        host.to_string()
    } else {
        format!("{host}:443")
    })
}

pub fn server_url(server: &str) -> String {
    match server.strip_suffix(":443") {
        Some(host) => format!("https://{host}"),
        None => format!("https://{server}"),
    }
}

pub fn address_of(private_key: &[u8; 32]) -> Result<String> {
    Ok(address_of_key(
        SigningKey::from_slice(private_key)?.verifying_key(),
    ))
}

#[derive(Clone)]
pub struct Identity {
    key: SigningKey,
    address: String,
    account: String,
    server: String,
}

impl Identity {
    pub fn new(private_key: &[u8; 32], host: &str) -> Result<Identity> {
        Self::from_key(SigningKey::from_slice(private_key)?, host)
    }

    pub fn generate(host: &str) -> Result<Identity> {
        Self::from_key(SigningKey::random(&mut rand::rngs::OsRng), host)
    }

    fn from_key(key: SigningKey, host: &str) -> Result<Identity> {
        let address = address_of_key(key.verifying_key());
        let account = format!("key:{address}@{}", host.to_lowercase());
        ensure!(is_self_account(&account), "bad-host: {host}");
        let server = node_of(&account).unwrap();
        Ok(Identity {
            key,
            address,
            account,
            server,
        })
    }

    pub fn account(&self) -> &str {
        &self.account
    }

    pub fn address(&self) -> &str {
        &self.address
    }

    pub fn server(&self) -> &str {
        &self.server
    }

    pub fn private_key(&self) -> [u8; 32] {
        self.key.to_bytes().into()
    }

    pub fn sign_recoverable(&self, digest: &[u8; 32]) -> ([u8; 64], u8) {
        let (sig, recid) = self
            .key
            .sign_prehash_recoverable(digest)
            .expect("secp256k1 signing");
        (sig.to_bytes().into(), recid.to_byte())
    }

    pub fn sign_digest(&self, digest: &[u8; 32]) -> String {
        let (sig, recid) = self.sign_recoverable(digest);
        format!("0x{}{:02x}", hex::encode(sig), 27 + recid)
    }
}

#[derive(Clone)]
pub struct EncKeys {
    secret: [u8; 32],
    public: [u8; 32],
}

impl EncKeys {
    pub fn from_secret(secret: &[u8; 32]) -> EncKeys {
        EncKeys {
            secret: *secret,
            public: x25519(*secret, X25519_BASEPOINT_BYTES),
        }
    }

    pub fn generate() -> EncKeys {
        Self::from_secret(&rand::random())
    }

    pub fn public(&self) -> [u8; 32] {
        self.public
    }

    pub fn secret(&self) -> [u8; 32] {
        self.secret
    }
}
