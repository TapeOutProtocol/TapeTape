use anyhow::{ensure, Context, Result};
use bip39::{Language, Mnemonic};
use hmac::{Hmac, Mac};
use k256::elliptic_curve::sec1::ToEncodedPoint;
use k256::elliptic_curve::PrimeField;
use k256::{Scalar, SecretKey};
use sha2::{Digest, Sha256, Sha512};
use taperelay::{EncKeys, Identity};

use crate::transport::PORT;

const PATH: [u32; 5] = [44 | HARD, 60 | HARD, HARD, 0, 0];
const HARD: u32 = 1 << 31;

#[derive(Clone)]
pub(crate) struct Keys {
    pub id: Identity,
    pub enc: EncKeys,
}

impl Keys {
    pub fn new(secret: &[u8; 32], node: &str) -> Result<Keys> {
        Ok(Keys {
            id: Identity::new(secret, &format!("{node}:{PORT}"))?,
            enc: EncKeys::from_secret(&x25519_secret(secret)),
        })
    }

    pub fn account(&self) -> &str {
        self.id.account()
    }
}

pub(crate) fn generate_mnemonic() -> String {
    Mnemonic::from_entropy(&rand::random::<[u8; 16]>())
        .expect("16 bytes of entropy")
        .to_string()
}

pub(crate) fn normalize_mnemonic(words: &str) -> String {
    words
        .split_whitespace()
        .map(str::to_lowercase)
        .collect::<Vec<_>>()
        .join(" ")
}

pub(crate) fn secret_from_mnemonic(words: &str) -> Result<[u8; 32]> {
    let m = Mnemonic::parse_in_normalized(Language::English, &normalize_mnemonic(words))
        .context("Invalid recovery phrase")?;
    ensure!(
        matches!(m.word_count(), 12 | 24),
        "The recovery phrase must have 12 or 24 words"
    );
    derive(&m.to_seed(""))
}

pub(crate) fn x25519_secret(secret: &[u8; 32]) -> [u8; 32] {
    Sha256::new()
        .chain_update(b"tapetape/x25519/v1")
        .chain_update(secret)
        .finalize()
        .into()
}

fn hmac512(key: &[u8], data: &[u8]) -> [u8; 64] {
    let mut mac = Hmac::<Sha512>::new_from_slice(key).expect("any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

fn derive(seed: &[u8]) -> Result<[u8; 32]> {
    let i = hmac512(b"Bitcoin seed", seed);
    let mut key: [u8; 32] = i[..32].try_into().unwrap();
    let mut chain: [u8; 32] = i[32..].try_into().unwrap();
    for index in PATH {
        let mut data = Vec::with_capacity(37);
        if index & HARD != 0 {
            data.push(0);
            data.extend_from_slice(&key);
        } else {
            let sk = SecretKey::from_slice(&key)?;
            data.extend_from_slice(sk.public_key().to_encoded_point(true).as_bytes());
        }
        data.extend_from_slice(&index.to_be_bytes());
        let i = hmac512(&chain, &data);
        let tweak = Option::<Scalar>::from(Scalar::from_repr(
            <[u8; 32]>::try_from(&i[..32]).unwrap().into(),
        ))
        .context("invalid derivation")?;
        let parent = Option::<Scalar>::from(Scalar::from_repr(key.into())).unwrap();
        let child = tweak + parent;
        ensure!(!bool::from(child.is_zero()), "invalid derivation");
        key = child.to_bytes().into();
        chain = i[32..].try_into().unwrap();
    }
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ABANDON: &str =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    #[test]
    fn bip44_eth_vector() {
        let sk = secret_from_mnemonic(ABANDON).unwrap();
        assert_eq!(
            hex::encode(sk),
            "1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727"
        );
        assert_eq!(
            taperelay::address_of(&sk).unwrap(),
            "0x9858effd232b4033e47d90003d41ec34ecaeda94"
        );
    }

    #[test]
    fn mnemonic_normalization_and_errors() {
        let messy = format!("  {}  ", ABANDON.to_uppercase().replace(' ', "\n  "));
        assert_eq!(
            secret_from_mnemonic(&messy).unwrap(),
            secret_from_mnemonic(ABANDON).unwrap()
        );
        assert!(secret_from_mnemonic("abandon abandon").is_err());
        assert!(secret_from_mnemonic(&ABANDON.replace("about", "abandon")).is_err());
        let words = generate_mnemonic();
        assert_eq!(words.split(' ').count(), 12);
        assert!(secret_from_mnemonic(&words).is_ok());
    }

    #[test]
    fn x25519_derivation() {
        let sk = secret_from_mnemonic(ABANDON).unwrap();
        let keys = Keys::new(&sk, "relay1.taperelay.com").unwrap();
        let mut h = Sha256::new();
        h.update(b"tapetape/x25519/v1");
        h.update(sk);
        let expect: [u8; 32] = h.finalize().into();
        assert_eq!(keys.enc.secret(), expect);
        assert_eq!(keys.enc.public(), EncKeys::from_secret(&expect).public());
        assert_eq!(
            keys.account(),
            "key:0x9858effd232b4033e47d90003d41ec34ecaeda94@relay1.taperelay.com:4246"
        );
    }
}
