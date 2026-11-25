use anyhow::{ensure, Context, Result};
use blake2::digest::consts::{U10, U24};
use blake2::{Blake2b, Digest};
use crypto_secretbox::aead::{Aead, KeyInit};
use crypto_secretbox::XSalsa20Poly1305;
use salsa20::hsalsa;
use x25519_dalek::{x25519, X25519_BASEPOINT_BYTES};

const P_BE: [u8; 32] = {
    let mut p = [0xff; 32];
    p[0] = 0x7f;
    p[31] = 0xed;
    p
};

fn probe() -> [u8; 32] {
    std::array::from_fn(|i| ((i * 29 + 7) & 255) as u8)
}

pub fn check_public_key(pk: &[u8; 32]) -> Result<()> {
    let mut be = *pk;
    be.reverse();
    ensure!(be < P_BE, "non-canonical");
    ensure!(x25519(probe(), *pk) != [0; 32], "small-order");
    Ok(())
}

fn box_key(secret: &[u8; 32], public: &[u8; 32]) -> Option<XSalsa20Poly1305> {
    let shared = x25519(*secret, *public);
    if shared == [0; 32] {
        return None;
    }
    let key = hsalsa::<U10>(&shared.into(), &Default::default());
    Some(XSalsa20Poly1305::new(&key))
}

fn box_nonce(epk: &[u8; 32], pk: &[u8; 32]) -> [u8; 24] {
    Blake2b::<U24>::new()
        .chain_update(epk)
        .chain_update(pk)
        .finalize()
        .into()
}

pub fn seal_box(
    message: &[u8],
    recipient: &[u8; 32],
    ephemeral: Option<&[u8; 32]>,
) -> Result<Vec<u8>> {
    check_public_key(recipient).context("bad-public-key")?;
    let esk = ephemeral.copied().unwrap_or_else(rand::random);
    let epk = x25519(esk, X25519_BASEPOINT_BYTES);
    let cipher = box_key(&esk, recipient).context("bad-public-key")?;
    let ct = cipher
        .encrypt(&box_nonce(&epk, recipient).into(), message)
        .expect("secretbox encrypt");
    Ok([epk.as_slice(), &ct].concat())
}

pub fn open_box(sealed: &[u8], public: &[u8; 32], secret: &[u8; 32]) -> Option<Vec<u8>> {
    if sealed.len() < 48 {
        return None;
    }
    let (epk, ct) = sealed.split_at(32);
    let epk: [u8; 32] = epk.try_into().ok()?;
    box_key(secret, &epk)?
        .decrypt(&box_nonce(&epk, public).into(), ct)
        .ok()
}
