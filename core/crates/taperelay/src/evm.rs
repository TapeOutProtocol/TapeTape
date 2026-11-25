use anyhow::{ensure, Context as _, Result};
use sha3::{Digest, Keccak256};

use crate::Identity;

pub fn keccak256(data: &[u8]) -> [u8; 32] {
    Keccak256::digest(data).into()
}

pub fn selector(signature: &str) -> [u8; 4] {
    let h = keccak256(signature.as_bytes());
    [h[0], h[1], h[2], h[3]]
}

pub fn parse_address(s: &str) -> Result<[u8; 20]> {
    let raw = hex::decode(s.trim_start_matches("0x"))?;
    raw.try_into()
        .map_err(|_| anyhow::anyhow!("bad address: {s}"))
}

pub enum Abi<'a> {
    Address([u8; 20]),
    Uint(u128),
    Bytes32([u8; 32]),
    Bytes(&'a [u8]),
    Str(&'a str),
}

fn word_uint(v: u128) -> [u8; 32] {
    let mut w = [0u8; 32];
    w[16..].copy_from_slice(&v.to_be_bytes());
    w
}

fn padded(data: &[u8]) -> Vec<u8> {
    let mut out = word_uint(data.len() as u128).to_vec();
    out.extend_from_slice(data);
    out.resize(32 + data.len().div_ceil(32) * 32, 0);
    out
}

pub fn encode_call(signature: &str, args: &[Abi]) -> Vec<u8> {
    let mut head = Vec::with_capacity(32 * args.len());
    let mut tail = Vec::new();
    for arg in args {
        match arg {
            Abi::Address(a) => {
                let mut w = [0u8; 32];
                w[12..].copy_from_slice(a);
                head.extend_from_slice(&w);
            }
            Abi::Uint(v) => head.extend_from_slice(&word_uint(*v)),
            Abi::Bytes32(b) => head.extend_from_slice(b),
            Abi::Bytes(data) => {
                head.extend_from_slice(&word_uint((32 * args.len() + tail.len()) as u128));
                tail.extend(padded(data));
            }
            Abi::Str(s) => {
                head.extend_from_slice(&word_uint((32 * args.len() + tail.len()) as u128));
                tail.extend(padded(s.as_bytes()));
            }
        }
    }
    [&selector(signature)[..], &head, &tail].concat()
}

pub fn word(out: &[u8], i: usize) -> Result<&[u8]> {
    out.get(32 * i..32 * (i + 1))
        .with_context(|| format!("ABI result too short for word {i}"))
}

pub fn uint(out: &[u8], i: usize) -> Result<u128> {
    let w = word(out, i)?;
    ensure!(w[..16].iter().all(|b| *b == 0), "ABI uint overflow");
    Ok(u128::from_be_bytes(w[16..].try_into()?))
}

pub fn address(out: &[u8], i: usize) -> Result<[u8; 20]> {
    Ok(word(out, i)?[12..].try_into()?)
}

pub fn dynamic(out: &[u8], i: usize) -> Result<&[u8]> {
    let offset = usize::try_from(uint(out, i)?)?;
    let len = usize::try_from(uint(out.get(offset..).context("ABI offset")?, 0)?)?;
    out.get(offset + 32..offset + 32 + len)
        .context("ABI dynamic value out of range")
}

fn rlp_header(offset: u8, len: usize) -> Vec<u8> {
    if len < 56 {
        return vec![offset + len as u8];
    }
    let be = trim(&len.to_be_bytes()).to_vec();
    [vec![offset + 55 + be.len() as u8], be].concat()
}

fn rlp_bytes(b: &[u8]) -> Vec<u8> {
    if b.len() == 1 && b[0] < 0x80 {
        return b.to_vec();
    }
    [rlp_header(0x80, b.len()), b.to_vec()].concat()
}

fn rlp_list(items: &[Vec<u8>]) -> Vec<u8> {
    let payload = items.concat();
    [rlp_header(0xc0, payload.len()), payload].concat()
}

fn trim(b: &[u8]) -> &[u8] {
    let start = b.iter().position(|x| *x != 0).unwrap_or(b.len());
    &b[start..]
}

fn rlp_uint(v: u128) -> Vec<u8> {
    rlp_bytes(trim(&v.to_be_bytes()))
}

pub struct LegacyTx<'a> {
    pub nonce: u128,
    pub gas_price: u128,
    pub gas: u128,
    pub to: [u8; 20],
    pub value: u128,
    pub data: &'a [u8],
    pub chain_id: u64,
}

pub fn sign_legacy(key: &Identity, tx: &LegacyTx) -> Vec<u8> {
    let fields = vec![
        rlp_uint(tx.nonce),
        rlp_uint(tx.gas_price),
        rlp_uint(tx.gas),
        rlp_bytes(&tx.to),
        rlp_uint(tx.value),
        rlp_bytes(tx.data),
    ];
    let unsigned = rlp_list(
        &[
            fields.clone(),
            vec![rlp_uint(tx.chain_id.into()), rlp_uint(0), rlp_uint(0)],
        ]
        .concat(),
    );
    let (sig, recid) = key.sign_recoverable(&keccak256(&unsigned));
    let v = u128::from(recid) + 35 + 2 * u128::from(tx.chain_id);
    rlp_list(
        &[
            fields,
            vec![
                rlp_uint(v),
                rlp_bytes(trim(&sig[..32])),
                rlp_bytes(trim(&sig[32..])),
            ],
        ]
        .concat(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_selector() {
        assert_eq!(
            hex::encode(selector("transfer(address,uint256)")),
            "a9059cbb"
        );
    }

    #[test]
    fn test_eip155_vector() {
        let key = Identity::new(&[0x46; 32], "relay1.taperelay.com:4246").unwrap();
        let tx = LegacyTx {
            nonce: 9,
            gas_price: 20_000_000_000,
            gas: 21000,
            to: [0x35; 20],
            value: 1_000_000_000_000_000_000,
            data: &[],
            chain_id: 1,
        };
        assert_eq!(
            hex::encode(sign_legacy(&key, &tx)),
            "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83"
        );
    }

    #[test]
    fn test_encode_dynamic() {
        let data = encode_call(
            "f(address,string,uint256,bytes)",
            &[
                Abi::Address([0x11; 20]),
                Abi::Str("ab"),
                Abi::Uint(7),
                Abi::Bytes(&[1, 2, 3]),
            ],
        );
        let out = &data[4..];
        assert_eq!(address(out, 0).unwrap(), [0x11; 20]);
        assert_eq!(dynamic(out, 1).unwrap(), b"ab");
        assert_eq!(uint(out, 2).unwrap(), 7);
        assert_eq!(dynamic(out, 3).unwrap(), &[1, 2, 3]);
        assert_eq!(out.len(), 32 * 4 + 64 + 64);
    }
}
