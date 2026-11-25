use anyhow::{ensure, Result};
use taperelay::evm::{self, Abi};

use crate::chain::Call;

/// `keccak256("TapeTapeRedPacket.claim.v1")`
pub(crate) const CLAIM_TAG: [u8; 32] =
    hex_literal(b"6f067f14bd95906c7f8d92cdb3e1a7139bc1c698e96a8bd6a4d83c4acd39574d");

const fn hex_literal(h: &[u8; 64]) -> [u8; 32] {
    const fn nib(c: u8) -> u8 {
        match c {
            b'0'..=b'9' => c - b'0',
            b'a'..=b'f' => c - b'a' + 10,
            _ => panic!("bad hex"),
        }
    }
    let mut out = [0u8; 32];
    let mut i = 0;
    while i < 32 {
        out[i] = nib(h[2 * i]) << 4 | nib(h[2 * i + 1]);
        i += 1;
    }
    out
}

/// `getRedPacketInfo(address,string)`: 11 static words, all zero for an unknown packet.
pub(crate) struct OnChain {
    pub creator: [u8; 20],
    pub token: [u8; 20],
    pub claim_key: [u8; 20],
    pub recipient: [u8; 20],
    pub total_amount: u128,
    pub remain_amount: u128,
    pub fee: u128,
    pub total_count: u128,
    pub remain_count: u128,
    pub create_time: u128,
    pub refunded: bool,
}

pub(crate) fn approve(token: [u8; 20], spender: [u8; 20], amount: u128) -> Call {
    Call {
        to: token,
        value: 0,
        data: evm::encode_call(
            "approve(address,uint256)",
            &[Abi::Address(spender), Abi::Uint(amount)],
        ),
    }
}

pub(crate) fn erc20_transfer(token: [u8; 20], to: [u8; 20], amount: u128) -> Call {
    Call {
        to: token,
        value: 0,
        data: evm::encode_call(
            "transfer(address,uint256)",
            &[Abi::Address(to), Abi::Uint(amount)],
        ),
    }
}

pub(crate) struct Create<'a> {
    pub id: &'a str,
    pub token: [u8; 20],
    pub amount: u128,
    pub count: u32,
    pub claim_key: [u8; 20],
    /// Zero for a red packet; the receiver's address for a transfer.
    pub recipient: [u8; 20],
    /// The fee rate (per mille) the sender agreed to; the contract rejects a higher one.
    pub max_fee_rate: u128,
}

pub(crate) fn create(contract: [u8; 20], c: &Create) -> Call {
    Call {
        to: contract,
        value: if c.token == [0; 20] { c.amount } else { 0 },
        data: evm::encode_call(
            "createRedPacket(string,address,uint256,uint256,address,address,uint256)",
            &[
                Abi::Str(c.id),
                Abi::Address(c.token),
                Abi::Uint(c.amount),
                Abi::Uint(c.count.into()),
                Abi::Address(c.claim_key),
                Abi::Address(c.recipient),
                Abi::Uint(c.max_fee_rate),
            ],
        ),
    }
}

pub(crate) fn claim(contract: [u8; 20], creator: [u8; 20], id: &str, signature: &[u8]) -> Call {
    Call {
        to: contract,
        value: 0,
        data: evm::encode_call(
            "claimRedPacket(address,string,bytes)",
            &[Abi::Address(creator), Abi::Str(id), Abi::Bytes(signature)],
        ),
    }
}

pub(crate) fn refund(contract: [u8; 20], id: &str, to: [u8; 20]) -> Call {
    Call {
        to: contract,
        value: 0,
        data: evm::encode_call(
            "refundRedPacket(string,address)",
            &[Abi::Str(id), Abi::Address(to)],
        ),
    }
}

/// The 192 bytes `abi.encode(CLAIM_TAG, chainId, contract, creator, keccak256(id), claimer)`.
/// The claim key signs them with `personal_sign` (which hashes them first and adds the EIP-191 prefix).
pub(crate) fn claim_preimage(
    chain_id: u64,
    contract: [u8; 20],
    creator: [u8; 20],
    id: &str,
    claimer: [u8; 20],
) -> Vec<u8> {
    let addr = |a: [u8; 20]| [[0u8; 12].as_slice(), &a].concat();
    let mut chain = [0u8; 32];
    chain[24..].copy_from_slice(&chain_id.to_be_bytes());
    [
        CLAIM_TAG.to_vec(),
        chain.to_vec(),
        addr(contract),
        addr(creator),
        evm::keccak256(id.as_bytes()).to_vec(),
        addr(claimer),
    ]
    .concat()
}

pub(crate) fn decode_info(out: &[u8]) -> Result<OnChain> {
    ensure!(out.len() >= 32 * 11, "short red packet info");
    Ok(OnChain {
        creator: evm::address(out, 0)?,
        token: evm::address(out, 1)?,
        claim_key: evm::address(out, 2)?,
        recipient: evm::address(out, 3)?,
        total_amount: evm::uint(out, 4)?,
        remain_amount: evm::uint(out, 5)?,
        fee: evm::uint(out, 6)?,
        total_count: evm::uint(out, 7)?,
        remain_count: evm::uint(out, 8)?,
        create_time: evm::uint(out, 9)?,
        refunded: evm::uint(out, 10)? != 0,
    })
}

pub(crate) fn decode_records(out: &[u8]) -> Result<Vec<([u8; 20], u128)>> {
    let offset = usize::try_from(evm::uint(out, 0)?)?;
    let body = out.get(offset..).unwrap_or_default();
    let n = usize::try_from(evm::uint(body, 0)?)?;
    ensure!(n <= 10_000, "too many claim records");
    let items = &body[32..];
    (0..n)
        .map(|i| Ok((evm::address(items, 2 * i)?, evm::uint(items, 2 * i + 1)?)))
        .collect()
}

pub(crate) fn new_packet_id() -> String {
    let b: [u8; 16] = rand::random();
    let h = hex::encode(b);
    format!(
        "{}-{}-4{}-{:x}{}-{}",
        &h[0..8],
        &h[8..12],
        &h[13..16],
        (b[8] & 0x3) | 0x8,
        &h[17..20],
        &h[20..32]
    )
    .to_uppercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn call_encodings_match_known_selectors() {
        let c = [0x11; 20];
        assert_eq!(hex::encode(&approve(c, c, 1).data[..4]), "095ea7b3");
        assert_eq!(hex::encode(&erc20_transfer(c, c, 1).data[..4]), "a9059cbb");
        // selectors from `forge inspect TapeTapeRedPacket methodIdentifiers`
        let mk = |token| Create {
            id: "ID",
            token,
            amount: 5,
            count: 3,
            claim_key: [0x22; 20],
            recipient: [0; 20],
            max_fee_rate: 7,
        };
        let create = create(c, &mk([0; 20]));
        assert_eq!(create.value, 5);
        let expected = [
            hex::encode(evm::selector(
                "createRedPacket(string,address,uint256,uint256,address,address,uint256)",
            )),
            format!("{:064x}", 0xe0),
            "0".repeat(64),
            format!("{:064x}", 5),
            format!("{:064x}", 3),
            format!("{:0>64}", "22".repeat(20)),
            "0".repeat(64),
            format!("{:064x}", 7),
            format!("{:064x}", 2),
            format!("4944{}", "0".repeat(60)),
        ]
        .concat();
        assert_eq!(hex::encode(&create.data), expected);
        assert_eq!(super::create(c, &mk([9; 20])).value, 0);
        assert_eq!(hex::encode(&claim(c, c, "x", &[1]).data[..4]), "8f79b54f");
        assert_eq!(hex::encode(&refund(c, "x", c).data[..4]), "353e691b");
        assert_eq!(hex::encode(&create.data[..4]), "fe0520dc");
        assert_eq!(
            hex::encode(evm::keccak256(b"TapeTapeRedPacket.claim.v1")),
            hex::encode(CLAIM_TAG)
        );
    }

    // Vector from the contract audit (SpecAudit.t.sol), checked against forge's own signing.
    #[test]
    fn claim_signature_vector() {
        let p = |b: u8| [b; 20];
        let pre = claim_preimage(56, p(0x11), p(0x22), "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50", p(0x33));
        assert_eq!(pre.len(), 192);
        assert_eq!(
            hex::encode(evm::keccak256(&pre)),
            "9b1af278c4ed2fece0e1219cadfe41a580afb9dc186318408654c3d5e8878908"
        );
        let mut k = [0u8; 32];
        k[31] = 1;
        let key = taperelay::Identity::new(&k, "relay1.taperelay.com:4246").unwrap();
        assert_eq!(
            key.address().to_lowercase(),
            "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
        );
        assert_eq!(
            hex::encode(crate::chain::personal_sign(&key, &pre)),
            "a9f494e1cc1d4c3bad19af52a0289092a5a52831871d5fe70ebac42843db3ee1553ac08c4c001af7dc5932bec5c7c747fa0a1d9ad401b443225eabe03781d0221c"
        );
    }

    #[test]
    fn info_decodes_eleven_words() {
        let mut w = vec![0u8; 32 * 11];
        w[12..32].copy_from_slice(&[0xaa; 20]);
        w[32 * 4 + 31] = 9;
        w[32 * 6 + 31] = 1;
        w[32 * 10 + 31] = 1;
        let i = decode_info(&w).unwrap();
        assert_eq!((i.creator, i.total_amount, i.fee, i.refunded), ([0xaa; 20], 9, 1, true));
        assert!(decode_info(&w[..320]).is_err());
    }

    #[test]
    fn records_decode() {
        let mut out = format!("{:064x}{:064x}", 0x20, 2);
        out += &format!("{:0>64}{:064x}", "aa".repeat(20), 7);
        out += &format!("{:0>64}{:064x}", "bb".repeat(20), 9);
        let r = decode_records(&hex::decode(out).unwrap()).unwrap();
        assert_eq!(r, vec![([0xaa; 20], 7), ([0xbb; 20], 9)]);
    }

    #[test]
    fn packet_ids_look_like_uuids() {
        let id = new_packet_id();
        assert_eq!(id.len(), 36);
        assert_eq!(id.as_bytes()[14], b'4');
        assert_ne!(id, new_packet_id());
    }
}
