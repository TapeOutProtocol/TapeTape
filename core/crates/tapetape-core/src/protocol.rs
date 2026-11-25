use std::io::Read;

use anyhow::{bail, ensure, Context, Result};
use serde::{Deserialize, Serialize};
use taperelay::MAX_INLINE_PLAINTEXT;

pub(crate) const VERSION: u32 = 1;
pub(crate) const FRAME_SINGLE: u8 = 0x21;
pub(crate) const FRAME_PART: u8 = 0x22;
pub(crate) const FRAME_FILE: u8 = 0x23;
pub(crate) const PART_HEAD: usize = 21;
/// Fragments per message. 128 fits a full group's state (MAX_GROUP_MEMBERS) plus the largest group avatar.
pub(crate) const MAX_PARTS: usize = 128;
/// Envelope JSON limit (both directions). A `group_state` for MAX_GROUP_MEMBERS members with 6-character
/// Chinese nicknames is about 86 KiB.
pub(crate) const MAX_JSON: usize = 128 << 10;
/// Members of a group, including yourself.
pub(crate) const MAX_GROUP_MEMBERS: usize = 500;
pub(crate) const MAX_ATTACHMENT: u64 = 170 << 10;
const MAX_INNER: u64 = 4 + MAX_JSON as u64 + MAX_ATTACHMENT;
const FAST_ABOVE: usize = 256 << 10;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub(crate) struct Envelope {
    pub v: u32,
    pub id: String,
    pub ts: i64,
    pub from: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub expires: u32,
    #[serde(default, skip_serializing_if = "is_false")]
    pub fwd: bool,
    #[serde(flatten)]
    pub body: Body,
}

fn is_zero(v: &u32) -> bool {
    *v == 0
}

fn is_false(v: &bool) -> bool {
    !*v
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(crate) enum Body {
    Text {
        text: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        reply: Option<String>,
    },
    Media {
        kind: String,
        mime: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        fname: Option<String>,
        size: u64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        w: Option<u32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        h: Option<u32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        dur: Option<u32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        text: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        reply: Option<String>,
        #[serde(rename = "ref", default, skip_serializing_if = "Option::is_none")]
        reference: Option<String>,
    },
    Contact {
        account: String,
        #[serde(default)]
        cname: String,
    },
    Reaction {
        target: String,
        emoji: String,
    },
    Read {
        targets: Vec<String>,
    },
    Profile {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        avatar: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        bio: Option<String>,
    },
    Timer,
    Hello {
        token: String,
    },
    HelloOk,
    GroupState {
        gname: String,
        members: Vec<String>,
        #[serde(default)]
        admins: Vec<String>,
        #[serde(default)]
        names: Vec<String>,
        ver: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        avatar: Option<String>,
    },
    Redpacket {
        chain: String,
        contract: String,
        packet: String,
        token: String,
        symbol: String,
        total: String,
        count: u32,
        #[serde(default)]
        greeting: String,
        tx: String,
        creator: String,
        /// The one-time claim key's private key (0x + 64 hex). Its address is stored on chain.
        claim_key: String,
    },
    RedpacketClaim {
        packet: String,
        claimer: String,
        amount: String,
        tx: String,
    },
    Transfer {
        chain: String,
        contract: String,
        transfer: String,
        token: String,
        symbol: String,
        amount: String,
        #[serde(default)]
        note: String,
        tx: String,
        creator: String,
        /// The one-time claim key's private key (0x + 64 hex). Its address is stored on chain.
        claim_key: String,
    },
    TransferClaim {
        transfer: String,
        tx: String,
    },
    /// A transfer paid straight into the receiver's wallet: no escrow, nothing to claim. The receiver checks
    /// `tx` on chain (sender, receiver, token, amount) before showing it as received.
    TransferDirect {
        chain: String,
        token: String,
        symbol: String,
        amount: String,
        #[serde(default)]
        note: String,
        tx: String,
        creator: String,
        recipient: String,
    },
    #[serde(other)]
    Unknown,
}

impl Envelope {
    pub fn new(from: &str, name: &str, body: Body) -> Envelope {
        Envelope {
            v: VERSION,
            id: new_id(),
            ts: now_ms(),
            from: from.to_string(),
            name: name.to_string(),
            group: None,
            expires: 0,
            fwd: false,
            body,
        }
    }
}

pub(crate) fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64
}

pub(crate) fn new_id() -> String {
    taperelay::base64url_encode(&rand::random::<[u8; 16]>())
}

pub(crate) fn encode_inner(env: &Envelope, blob: &[u8]) -> Vec<u8> {
    let json = serde_json::to_vec(env).expect("envelope json");
    let mut out = Vec::with_capacity(4 + json.len() + blob.len());
    out.extend_from_slice(&(json.len() as u32).to_be_bytes());
    out.extend_from_slice(&json);
    out.extend_from_slice(blob);
    out
}

pub(crate) fn decode_inner(mut inner: Vec<u8>) -> Result<(Envelope, Vec<u8>)> {
    let head = inner.first_chunk::<4>().context("short inner")?;
    let len = u32::from_be_bytes(*head) as usize;
    ensure!(
        len <= MAX_JSON && 4 + len <= inner.len(),
        "bad json length {len}"
    );
    ensure!(
        (inner.len() - 4 - len) as u64 <= MAX_ATTACHMENT,
        "attachment too large"
    );
    let env: Envelope = serde_json::from_slice(&inner[4..4 + len]).context("bad envelope")?;
    inner.drain(..4 + len);
    Ok((env, inner))
}

pub(crate) fn pack(inner: &[u8]) -> Vec<u8> {
    let quality = if inner.len() > FAST_ABOVE { 5 } else { 11 };
    let mut packed = Vec::new();
    brotli::BrotliCompress(
        &mut std::io::Cursor::new(inner),
        &mut packed,
        &brotli::enc::BrotliEncoderParams {
            quality,
            ..Default::default()
        },
    )
    .expect("brotli to memory");
    packed
}

pub(crate) fn unpack(packed: &[u8]) -> Result<Vec<u8>> {
    let mut inner = Vec::new();
    brotli::Decompressor::new(packed, 4096)
        .take(MAX_INNER + 1)
        .read_to_end(&mut inner)?;
    ensure!(inner.len() as u64 <= MAX_INNER, "message too large");
    Ok(inner)
}

pub(crate) fn frames(inner: &[u8]) -> Result<Vec<Vec<u8>>> {
    use sha2::Digest;
    let packed = pack(inner);
    if packed.len() < MAX_INLINE_PLAINTEXT {
        return Ok(vec![[&[FRAME_SINGLE][..], &packed].concat()]);
    }
    let room = MAX_INLINE_PLAINTEXT - PART_HEAD;
    let total = packed.len().div_ceil(room);
    ensure!(total <= MAX_PARTS, "message too large");
    let msg_id: [u8; 16] = sha2::Sha256::digest(inner)[..16].try_into().unwrap();
    Ok(packed
        .chunks(room)
        .enumerate()
        .map(|(i, chunk)| {
            let mut f = Vec::with_capacity(PART_HEAD + chunk.len());
            f.push(FRAME_PART);
            f.extend_from_slice(&msg_id);
            f.extend_from_slice(&(i as u16).to_be_bytes());
            f.extend_from_slice(&(total as u16).to_be_bytes());
            f.extend_from_slice(chunk);
            f
        })
        .collect())
}

#[derive(Debug, PartialEq)]
pub(crate) enum Frame<'a> {
    Single(&'a [u8]),
    Part {
        msg_id: [u8; 16],
        index: u16,
        total: u16,
        chunk: &'a [u8],
    },
    File(&'a [u8]),
}

pub(crate) fn parse_frame(payload: &[u8]) -> Result<Frame<'_>> {
    let (kind, rest) = payload.split_first().context("empty payload")?;
    match *kind {
        FRAME_SINGLE => Ok(Frame::Single(rest)),
        FRAME_FILE => Ok(Frame::File(rest)),
        FRAME_PART => {
            ensure!(rest.len() > PART_HEAD - 1, "short part");
            let msg_id: [u8; 16] = rest[..16].try_into().unwrap();
            let index = u16::from_be_bytes([rest[16], rest[17]]);
            let total = u16::from_be_bytes([rest[18], rest[19]]);
            ensure!(
                total > 0 && usize::from(total) <= MAX_PARTS && index < total,
                "bad part header"
            );
            Ok(Frame::Part {
                msg_id,
                index,
                total,
                chunk: &rest[20..],
            })
        }
        k => bail!("unknown frame 0x{k:02x}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env(body: Body) -> Envelope {
        Envelope::new(
            "key:0x00000000000000000000000000000000000000aa@relay1.taperelay.com:4246",
            "小王",
            body,
        )
    }

    #[test]
    fn envelope_json_shape() {
        let e = env(Body::Text {
            text: "hi".into(),
            reply: Some("abc".into()),
        });
        let v: serde_json::Value = serde_json::to_value(&e).unwrap();
        assert_eq!(v["v"], 1);
        assert_eq!(v["type"], "text");
        assert_eq!(v["text"], "hi");
        assert_eq!(v["reply"], "abc");
        assert_eq!(v["name"], "小王");
        assert!(v.get("group").is_none());
        assert_eq!(taperelay::base64url_decode(&e.id).unwrap().len(), 16);

        let ok = serde_json::to_value(env(Body::HelloOk)).unwrap();
        assert_eq!(ok["type"], "hello_ok");
        let hello = serde_json::to_value(env(Body::Hello { token: "t".into() })).unwrap();
        assert_eq!(hello["token"], "t");
    }

    #[test]
    fn unknown_type_and_fields_are_ignored() {
        let json = r#"{"v":1,"id":"x","ts":1,"from":"f","type":"poll","target":"y","future":true}"#;
        let e: Envelope = serde_json::from_str(json).unwrap();
        assert_eq!(e.body, Body::Unknown);
        let json =
            r#"{"v":1,"id":"x","ts":1,"from":"f","type":"text","text":"a","future":{"x":1}}"#;
        let e: Envelope = serde_json::from_str(json).unwrap();
        assert_eq!(
            e.body,
            Body::Text {
                text: "a".into(),
                reply: None
            }
        );
        assert_eq!(e.name, "");
    }

    #[test]
    fn m2_envelope_shapes() {
        let mut e = env(Body::Media {
            kind: "voice".into(),
            mime: "audio/mp4".into(),
            fname: Some("a.m4a".into()),
            size: 3,
            w: None,
            h: None,
            dur: Some(1500),
            text: None,
            reply: Some("r".into()),
            reference: None,
        });
        e.expires = 60;
        e.fwd = true;
        let v = serde_json::to_value(&e).unwrap();
        assert_eq!(v["type"], "media");
        assert_eq!(v["kind"], "voice");
        assert_eq!(v["fname"], "a.m4a");
        assert_eq!(v["name"], "小王");
        assert_eq!(v["dur"], 1500);
        assert_eq!(v["expires"], 60);
        assert_eq!(v["fwd"], true);
        assert!(v.get("w").is_none());
        assert_eq!(serde_json::from_value::<Envelope>(v).unwrap(), e);

        let plain = serde_json::to_value(env(Body::Timer)).unwrap();
        assert!(plain.get("expires").is_none() && plain.get("fwd").is_none());
        let c = serde_json::to_value(env(Body::Contact {
            account: "key:x".into(),
            cname: "Carol".into(),
        }))
        .unwrap();
        assert_eq!(
            (c["type"].as_str(), c["cname"].as_str()),
            (Some("contact"), Some("Carol"))
        );
        let r: Envelope = serde_json::from_str(
            r#"{"v":1,"id":"x","ts":1,"from":"f","type":"reaction","target":"y","emoji":""}"#,
        )
        .unwrap();
        assert_eq!(
            r.body,
            Body::Reaction {
                target: "y".into(),
                emoji: "".into()
            }
        );
        let p: Envelope =
            serde_json::from_str(r#"{"v":1,"id":"x","ts":1,"from":"f","type":"profile"}"#).unwrap();
        assert_eq!(
            p.body,
            Body::Profile {
                avatar: None,
                bio: None
            }
        );
    }

    #[test]
    fn part_ids_are_deterministic() {
        let big = random_text(20_000);
        assert_eq!(frames(&big).unwrap(), frames(&big).unwrap());
    }

    #[test]
    fn inner_roundtrip() {
        let e = env(Body::Read {
            targets: vec!["a".into(), "b".into()],
        });
        let inner = encode_inner(&e, b"blob");
        let (back, blob) = decode_inner(inner).unwrap();
        assert_eq!(back, e);
        assert_eq!(blob, b"blob");
        assert!(decode_inner(vec![0, 0, 1, 0]).is_err());
        let mut huge = ((MAX_JSON + 1) as u32).to_be_bytes().to_vec();
        huge.extend(vec![b' '; MAX_JSON + 1]);
        assert!(decode_inner(huge).is_err());
        let max = vec![0u8; MAX_ATTACHMENT as usize];
        assert_eq!(
            decode_inner(encode_inner(&e, &max)).unwrap().1.len(),
            max.len()
        );
        let big = vec![0u8; MAX_ATTACHMENT as usize + 1];
        assert!(decode_inner(encode_inner(&e, &big)).is_err());
        assert!(unpack(&pack(&vec![0; MAX_INNER as usize])).is_ok());
        assert!(unpack(&pack(&vec![0; MAX_INNER as usize + 1])).is_err());
    }

    fn reassemble(frames: &[Vec<u8>]) -> Vec<u8> {
        let mut packed = Vec::new();
        let mut id = None;
        for (i, f) in frames.iter().enumerate() {
            match parse_frame(f).unwrap() {
                Frame::Single(p) => packed.extend_from_slice(p),
                Frame::Part {
                    msg_id,
                    index,
                    total,
                    chunk,
                } => {
                    assert_eq!(*id.get_or_insert(msg_id), msg_id);
                    assert_eq!((index as usize, total as usize), (i, frames.len()));
                    packed.extend_from_slice(chunk);
                }
                Frame::File(_) => unreachable!(),
            }
        }
        unpack(&packed).unwrap()
    }

    fn random_text(n: usize) -> Vec<u8> {
        (0..n).map(|_| b'a' + rand::random::<u8>() % 26).collect()
    }

    #[test]
    fn framing_single_and_parts() {
        let small = encode_inner(
            &env(Body::Text {
                text: "hello".into(),
                reply: None,
            }),
            &[],
        );
        let f = frames(&small).unwrap();
        assert_eq!(f.len(), 1);
        assert_eq!(f[0][0], FRAME_SINGLE);
        assert_eq!(reassemble(&f), small);

        let big = random_text(20_000);
        let f = frames(&big).unwrap();
        assert!(f.len() > 1);
        for p in &f {
            assert_eq!(p[0], FRAME_PART);
            assert!(p.len() <= MAX_INLINE_PLAINTEXT);
        }
        assert_eq!(reassemble(&f), big);

        assert!(frames(&random_text(800_000)).is_err());
    }

    #[test]
    fn frame_errors() {
        assert!(parse_frame(&[]).is_err());
        assert!(parse_frame(&[0x20, 1]).is_err());
        assert!(parse_frame(&[FRAME_PART; 10]).is_err());
        let mut bad = vec![FRAME_PART];
        bad.extend([0u8; 16]);
        bad.extend([0, 2, 0, 2, 9]);
        assert!(parse_frame(&bad).is_err());
        assert_eq!(parse_frame(&[FRAME_FILE, 7]).unwrap(), Frame::File(&[7]));
    }

    fn big_group_state(n: usize) -> Envelope {
        let ids: Vec<String> = (0..n)
            .map(|_| format!("key:0x{}@relay1.taperelay.com:4246", hex::encode(rand::random::<[u8; 20]>())))
            .collect();
        let from = ids[0].clone();
        let mut e = Envelope::new(
            &from,
            "我的昵称",
            Body::GroupState {
                gname: "一个很长的群名称一二三四五".into(),
                members: ids.clone(),
                admins: ids,
                names: vec!["张三丰的小号".into(); n],
                ver: now_ms(),
                avatar: Some("image/jpeg".into()),
            },
        );
        e.group = Some(new_id());
        e
    }

    // A full group's state (MAX_GROUP_MEMBERS members, 6-character Chinese nicknames) fits the envelope
    // limit and, together with the largest group avatar, the frame limit.
    #[test]
    fn full_group_state_fits() {
        let e = big_group_state(MAX_GROUP_MEMBERS);
        let json = serde_json::to_vec(&e).unwrap();
        println!("group state for {MAX_GROUP_MEMBERS} members: {} bytes JSON", json.len());
        assert!(json.len() <= MAX_JSON, "{} > {MAX_JSON}", json.len());
        let avatar: Vec<u8> = (0..MAX_ATTACHMENT).map(|_| rand::random::<u8>()).collect();
        let inner = encode_inner(&e, &avatar);
        let parts = frames(&inner).unwrap();
        println!("with a {} KiB avatar: {} parts", MAX_ATTACHMENT >> 10, parts.len());
        let (back, blob) = decode_inner(inner).unwrap();
        assert_eq!(blob.len(), avatar.len());
        assert!(matches!(back.body, Body::GroupState { ref members, .. } if members.len() == MAX_GROUP_MEMBERS));
    }
}
