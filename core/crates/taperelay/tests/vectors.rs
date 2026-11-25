use std::sync::LazyLock;

use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use taperelay::*;

static V: LazyLock<Value> =
    LazyLock::new(|| serde_json::from_str(include_str!("vectors.json")).unwrap());

fn cases(name: &str) -> &'static Vec<Value> {
    V[name].as_array().unwrap()
}

fn s(v: &Value) -> &str {
    v.as_str().unwrap()
}

fn opt(v: &Value) -> Option<&str> {
    v.as_str()
}

fn bytes(v: &Value) -> Vec<u8> {
    hex::decode(s(v)).unwrap()
}

fn arr<const N: usize>(v: &Value) -> [u8; N] {
    bytes(v).try_into().unwrap()
}

fn outcome<T>(r: anyhow::Result<T>) -> String {
    match r {
        Ok(_) => "ok".to_string(),
        Err(e) => e.to_string(),
    }
}

fn identity(c: &Value, key: &str) -> Identity {
    Identity::new(&arr(&c[key]), s(&c["host"])).unwrap()
}

#[test]
fn identities() {
    for c in cases("identities") {
        let id = identity(c, "privateKey");
        assert_eq!(id.account(), s(&c["account"]));
        assert_eq!(id.address(), s(&c["address"]));
        assert_eq!(id.server(), s(&c["server"]));
        assert_eq!(server_url(id.server()), s(&c["url"]));
        assert_eq!(
            address_of(&arr(&c["privateKey"])).unwrap(),
            s(&c["address"])
        );
        assert_eq!(id.private_key(), arr::<32>(&c["privateKey"]));
    }
    assert!(Identity::new(&[0; 32], "a.b").is_err());
    assert!(Identity::new(&[1; 32], "localhost").is_err());
    assert!(Identity::new(&[1; 32], "").is_err());
}

#[test]
fn accounts() {
    for c in cases("accounts") {
        let input = s(&c["input"]);
        assert_eq!(
            is_self_account(input),
            c["isSelf"].as_bool().unwrap(),
            "{input}"
        );
        assert_eq!(self_key_of(input), opt(&c["key"]), "{input}");
        assert_eq!(self_host_of(input), opt(&c["host"]), "{input}");
        assert_eq!(node_of(input).as_deref(), opt(&c["node"]), "{input}");
        assert_eq!(
            node_of(input).map(|n| server_url(&n)).as_deref(),
            opt(&c["url"]),
            "{input}"
        );
    }
}

#[test]
fn requests() {
    for c in cases("requests") {
        let id = identity(c, "privateKey");
        let body = bytes(&c["body"]);
        let body_hash: [u8; 32] = Sha256::digest(&body).into();
        assert_eq!(hex::encode(body_hash), s(&c["bodyHash"]));
        let (server, method, path, query) = (
            s(&c["server"]),
            s(&c["method"]),
            s(&c["path"]),
            s(&c["query"]),
        );
        let timestamp = c["timestamp"].as_u64().unwrap();
        let nonce = arr::<16>(&c["nonce"]);
        let d = request_digest(
            id.account(),
            server,
            method,
            path,
            query,
            &body_hash,
            timestamp,
            &nonce,
        );
        assert_eq!(hex::encode(d), s(&c["digest"]));
        let headers = signed_headers(&id, server, method, path, query, &body, timestamp, nonce);
        let expected: Vec<(String, String)> = serde_json::from_value(c["headers"].clone()).unwrap();
        assert_eq!(headers, expected);
    }
}

#[test]
fn self_notice_digests() {
    for c in cases("selfNoticeDigests") {
        let id = identity(c, "privateKey");
        let d = self_notice_digest(
            s(&c["from"]),
            s(&c["to"]),
            s(&c["body"]),
            c["createdAt"].as_u64().unwrap(),
        );
        assert_eq!(hex::encode(d), s(&c["digest"]));
        assert_eq!(id.sign_digest(&d), s(&c["signature"]));
        assert_eq!(
            recover(&d, s(&c["signature"])).as_deref(),
            Some(id.address())
        );
    }
}

#[test]
fn recover_cases() {
    for c in cases("recover") {
        let d = arr::<32>(&c["digest"]);
        assert_eq!(
            recover(&d, s(&c["signature"])).as_deref(),
            opt(&c["signer"]),
            "{}",
            c["signature"]
        );
    }
}

#[test]
fn public_key_checks() {
    for c in cases("checkPublicKey") {
        assert_eq!(
            outcome(check_public_key(&arr(&c["key"]))),
            s(&c["result"]),
            "{}",
            c["key"]
        );
    }
}

#[test]
fn seal_boxes() {
    for c in cases("sealBox") {
        let keys = EncKeys::from_secret(&arr(&c["recipientSecret"]));
        assert_eq!(keys.public(), arr::<32>(&c["recipientPublic"]));
        let message = bytes(&c["message"]);
        let sealed = seal_box(&message, &keys.public(), Some(&arr(&c["ephemeralSecret"]))).unwrap();
        assert_eq!(hex::encode(&sealed), s(&c["sealed"]));
        assert_eq!(
            open_box(&sealed, &keys.public(), &keys.secret()),
            Some(message.clone())
        );
        let random = seal_box(&message, &keys.public(), None).unwrap();
        assert_eq!(
            open_box(&random, &keys.public(), &keys.secret()),
            Some(message)
        );
        let other = EncKeys::generate();
        assert_eq!(open_box(&sealed, &other.public(), &other.secret()), None);
    }
    assert_eq!(outcome(seal_box(b"x", &[0; 32], None)), "bad-public-key");
}

#[test]
fn seal_inlines() {
    assert_eq!(
        MAX_INLINE_PLAINTEXT,
        V["maxInlinePlaintext"].as_u64().unwrap() as usize
    );
    for c in cases("sealInline") {
        let recipient = EncKeys::from_secret(&arr(&c["recipientSecret"]));
        let sender = EncKeys::from_secret(&arr(&c["senderSecret"]));
        let fixed = InlineFixed {
            one_time_key: arr(&c["oneTimeKey"]),
            nonce: arr(&c["nonce"]),
            ephemeral: [arr(&c["ephemeral"][0]), arr(&c["ephemeral"][1])],
        };
        let plain = bytes(&c["plain"]);
        let body =
            seal_inline(&plain, &recipient.public(), &sender.public(), Some(&fixed)).unwrap();
        assert_eq!(body, s(&c["body"]));
        assert_eq!(
            open_inline(&body, &recipient, Role::Recipient),
            Some(plain.clone())
        );
        assert_eq!(
            open_inline(&body, &sender, Role::Sender),
            Some(plain.clone())
        );
        assert_eq!(open_inline(&body, &recipient, Role::Sender), None);
        assert_eq!(
            open_inline(&body, &EncKeys::generate(), Role::Recipient),
            None
        );
        assert_eq!(bytes(&c["openedRecipient"]), plain);
        assert_eq!(bytes(&c["openedSender"]), plain);
        if plain.len() == MAX_INLINE_PLAINTEXT {
            assert_eq!(body.len(), SEALED_BODY_MAX);
        }
    }
    let keys = EncKeys::generate();
    let max = vec![7u8; MAX_INLINE_PLAINTEXT];
    assert!(seal_inline(&max, &keys.public(), &keys.public(), None).is_ok());
    let over = vec![7u8; MAX_INLINE_PLAINTEXT + 1];
    assert_eq!(
        outcome(seal_inline(&over, &keys.public(), &keys.public(), None)),
        s(&V["tooLarge"])
    );
    assert_eq!(
        outcome(seal_inline(b"", &keys.public(), &keys.public(), None)),
        "bad-body"
    );
}

#[test]
fn inline_body_checks() {
    for c in cases("inlineBodyOk") {
        assert_eq!(
            inline_body_ok(s(&c["body"])),
            c["ok"].as_bool().unwrap(),
            "{}",
            c["body"]
        );
    }
}

#[test]
fn base64url() {
    for c in cases("base64url") {
        let decoded = base64url_decode(s(&c["s"])).map(hex::encode);
        assert_eq!(decoded.as_deref(), opt(&c["bytes"]), "{}", c["s"]);
        if let Some(b) = c["bytes"].as_str() {
            assert_eq!(base64url_encode(&hex::decode(b).unwrap()), s(&c["s"]));
        }
    }
}

#[test]
fn notices() {
    for c in cases("notices") {
        let sender = Identity::new(&arr(&c["senderKey"]), s(&c["host"])).unwrap();
        let recipient = Identity::new(&arr(&c["recipientKey"]), s(&c["host"])).unwrap();
        assert_eq!(recipient.account(), s(&c["to"]));
        let recipient_keys = EncKeys::from_secret(&arr(&c["recipientEncSecret"]));
        let sender_keys = EncKeys::from_secret(&arr(&c["senderEncSecret"]));
        let message = bytes(&c["message"]);
        let created_at = c["createdAt"].as_u64().unwrap();
        let fixed = InlineFixed {
            one_time_key: arr(&c["oneTimeKey"]),
            nonce: arr(&c["nonce"]),
            ephemeral: [arr(&c["ephemeral"][0]), arr(&c["ephemeral"][1])],
        };
        let body = seal_inline(
            &message,
            &recipient_keys.public(),
            &sender_keys.public(),
            Some(&fixed),
        )
        .unwrap();
        let signed = sign_self_notice(&sender, s(&c["to"]), &body, created_at);
        assert_eq!(signed.id, s(&c["id"]));
        assert_eq!(signed.json, s(&c["json"]));

        let mut item: Value = serde_json::from_str(s(&c["json"])).unwrap();
        item["id"] = c["id"].clone();
        let opened = open_self_notice(&item, &recipient_keys, recipient.account()).unwrap();
        assert_eq!(opened.id, s(&c["id"]));
        assert_eq!(opened.from, sender.account());
        assert_eq!(opened.to, recipient.account());
        assert_eq!(opened.created_at, created_at);
        assert_eq!(opened.message, message);

        let sent = verify_self_notice(&item, sender.account(), Role::Sender).unwrap();
        assert_eq!(
            open_inline(&sent.body, &sender_keys, Role::Sender),
            Some(message.clone())
        );

        let fresh = make_self_notice(
            &sender,
            recipient.account(),
            &message,
            &recipient_keys.public(),
            &sender_keys,
            created_at,
        )
        .unwrap();
        let item: Value = serde_json::from_str(&fresh.json).unwrap();
        let opened = open_self_notice(&item, &recipient_keys, recipient.account()).unwrap();
        assert_eq!((opened.id, opened.message), (fresh.id, message));
    }
}

#[test]
fn make_self_notice_errors() {
    let sender = Identity::generate("relay1.taperelay.com:4246").unwrap();
    let keys = EncKeys::generate();
    let to = Identity::generate("relay1.taperelay.com:4246").unwrap();
    let too_big = vec![0u8; MAX_INLINE_PLAINTEXT + 1];
    assert_eq!(
        outcome(make_self_notice(
            &sender,
            to.account(),
            &too_big,
            &keys.public(),
            &keys,
            1
        )),
        "too-large"
    );
    assert!(make_self_notice(&sender, "bob@example.com", b"hi", &keys.public(), &keys, 1).is_err());
    assert_eq!(
        outcome(make_self_notice(
            &sender,
            to.account(),
            b"hi",
            &[0; 32],
            &keys,
            1
        )),
        "bad-public-key"
    );
}

#[test]
fn verify_cases() {
    let default_keys = EncKeys::from_secret(&arr(&cases("notices")[0]["recipientEncSecret"]));
    for c in cases("verify") {
        let name = s(&c["name"]);
        let keys = match c.get("encSecret") {
            Some(k) => EncKeys::from_secret(&arr(k)),
            None => default_keys.clone(),
        };
        let me = s(&c["me"]);
        assert_eq!(
            outcome(verify_self_notice(&c["item"], me, Role::Recipient)),
            s(&c["verify"]),
            "{name}"
        );
        let opened = open_self_notice(&c["item"], &keys, me);
        if let (Ok(o), Some(m)) = (&opened, c["message"].as_str()) {
            assert_eq!(hex::encode(&o.message), m, "{name}");
        }
        assert_eq!(outcome(opened), s(&c["open"]), "{name}");
    }
}

#[test]
fn key_records() {
    for c in cases("keyRecords") {
        let id = identity(c, "privateKey");
        let public = arr::<32>(&c["public"]);
        let created_at = c["createdAt"].as_u64().unwrap();
        assert_eq!(
            hex::encode(self_key_digest(id.account(), &public, created_at)),
            s(&c["digest"])
        );
        let text = publish_key_json(&id, &public, created_at);
        assert_eq!(text, s(&c["json"]));
        assert_eq!(
            s(&c["url"]),
            format!("{}/v1/keys/{}", server_url(id.server()), id.account())
        );
        let parsed: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(
            hex::encode(verify_key_record(id.account(), &parsed).unwrap()),
            s(&c["fetched"])
        );
    }
}

#[test]
fn key_verify_cases() {
    for c in cases("keyVerify") {
        let r = verify_key_record(s(&c["account"]), &c["json"]).map(hex::encode);
        let got = match r {
            Ok(k) => k,
            Err(e) => e.to_string(),
        };
        assert_eq!(got, s(&c["result"]), "{}", c["name"]);
    }
    assert!(verify_key_record("nope", &json!({})).is_err());
}

#[test]
fn senders() {
    for c in cases("senders") {
        let list = |k: &str| -> Vec<String> { serde_json::from_value(c[k].clone()).unwrap() };
        assert_eq!(
            senders_json(
                &list("allow"),
                &list("deny"),
                &list("block"),
                &list("unblock")
            ),
            s(&c["json"])
        );
    }
}

#[test]
#[ignore]
fn emit_for_js() {
    let host = "relay1.taperelay.com:4246";
    let sender = Identity::generate(host).unwrap();
    let recipient = Identity::generate(host).unwrap();
    let sender_keys = EncKeys::generate();
    let recipient_keys = EncKeys::generate();
    let message = "Rust → JS ✓ 你好".as_bytes();
    let signed = make_self_notice(
        &sender,
        recipient.account(),
        message,
        &recipient_keys.public(),
        &sender_keys,
        1763460000,
    )
    .unwrap();
    let path = format!("/v1/inbox/{}", recipient.account());
    let headers = signed_headers(
        &recipient,
        recipient.server(),
        "GET",
        &path,
        "limit=100",
        b"",
        1763460001,
        rand::random(),
    );
    let out = json!({
        "item": { "id": signed.id, "json": signed.json },
        "me": recipient.account(),
        "recipientEncSecret": hex::encode(recipient_keys.secret()),
        "senderEncSecret": hex::encode(sender_keys.secret()),
        "message": hex::encode(message),
        "keyAccount": sender.account(),
        "keyJson": publish_key_json(&sender, &sender_keys.public(), 1763460002),
        "senderEncPublic": hex::encode(sender_keys.public()),
        "request": { "server": recipient.server(), "method": "GET", "path": path, "query": "limit=100", "headers": headers },
    });
    println!("JSON-FOR-JS {out}");
}
