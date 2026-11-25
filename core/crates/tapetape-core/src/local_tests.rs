use std::sync::Arc;

use crate::account::Account;
use crate::*;

struct Quiet;

impl EventListener for Quiet {
    fn on_event(&self, _: u32, _: Event) {}
}

pub(crate) fn png(w: u32, h: u32, extra: usize) -> Vec<u8> {
    let mut b = vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13];
    b.extend_from_slice(b"IHDR");
    b.extend_from_slice(&w.to_be_bytes());
    b.extend_from_slice(&h.to_be_bytes());
    b.extend_from_slice(&[8, 2, 0, 0, 0, 0, 0, 0, 0]);
    b.extend((0..extra).map(|_| rand::random::<u8>()));
    b
}

fn account(dir: &std::path::Path) -> Arc<Account> {
    Account::create(
        1,
        dir.join("1"),
        &account::keys::generate_mnemonic(),
        "relay1.taperelay.com",
        Arc::new(crate::net::Net::direct()),
        Arc::new(Quiet),
    )
    .unwrap()
}

impl Account {
    fn set_profile_sync(&self, name: &str) {
        crate::store::set_config(&self.db(), "name", name).unwrap();
    }
}

fn block_on<T>(f: impl std::future::Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(f)
}

#[test]
fn saved_device_media_and_chat_ops() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    let me = a.saved_messages_chat().unwrap();
    assert_eq!(a.saved_messages_chat().unwrap(), me);
    let chat = a.chat(me).unwrap();
    assert!(chat.is_self && chat.verified && chat.can_send);

    let note = block_on(a.send_text(me, "note to self".into(), None)).unwrap();
    assert_eq!(a.message(note).unwrap().state, MessageState::Delivered);

    let img = dir.path().join("pic.png");
    std::fs::write(&img, png(64, 48, 100)).unwrap();
    let m = block_on(a.send_media(
        me,
        MediaKind::Image,
        img.to_string_lossy().into(),
        Some("cap".into()),
        Some(note),
        None,
    ))
    .unwrap();
    let m = a.message(m).unwrap();
    assert_eq!(
        (m.kind, m.width, m.height),
        (MessageKind::Image, Some(64), Some(48))
    );
    assert_eq!(
        (m.file_name.as_deref(), m.mime.as_deref(), m.reply_to),
        (Some("pic.png"), Some("image/png"), Some(note))
    );
    assert!(std::path::Path::new(m.file_path.as_ref().unwrap()).exists());
    assert_eq!(a.media_messages(me, vec![MessageKind::Image]).len(), 1);
    assert_eq!(a.search_messages(None, "note".into()).len(), 1);
    assert_eq!(a.search_messages(Some(me), "pic".into()).len(), 1);

    let (other, _) = a
        .open_chat_with(
            "key:0x00000000000000000000000000000000000000aa@relay2.taperelay.com:4246",
            "Zed",
        )
        .unwrap();
    a.set_pinned(other, true).unwrap();
    assert_eq!(a.chats(None)[0].id, other);
    a.mute(other, None).unwrap();
    assert!(a.chat(other).unwrap().muted);
    a.mute(other, Some(1)).unwrap();
    assert!(!a.chat(other).unwrap().muted);
    a.set_archived(other, true).unwrap();
    assert!(a.chats(None).iter().all(|c| c.id != other));
    assert_eq!(a.archived_chats()[0].id, other);
    assert_eq!(a.chats(Some("saved".into())).len(), 1);

    let dev = a.add_device_message("welcome".into()).unwrap();
    let dev_chat = a.message(dev).unwrap().chat_id;
    let c = a.chat(dev_chat).unwrap();
    assert!(c.is_device && !c.can_send);
    assert!(block_on(a.send_text(dev_chat, "x".into(), None)).is_err());
    assert_eq!(
        a.chats(None)
            .iter()
            .find(|c| c.id == dev_chat)
            .unwrap()
            .unread,
        1
    );
    a.mark_all_read(false);
    assert_eq!(
        a.chats(None)
            .iter()
            .find(|c| c.id == dev_chat)
            .unwrap()
            .unread,
        0
    );

    a.set_saved(dev, true).unwrap();
    assert!(a.message(dev).unwrap().saved);
    let copy = a
        .messages(me, None, 10)
        .into_iter()
        .find(|m| m.original_id == Some(dev))
        .unwrap();
    assert_eq!(copy.text, "welcome");
    a.set_saved(dev, false).unwrap();
    assert!(a.message(copy.id).is_err());

    let file = m.file_path.clone().unwrap();
    a.delete_messages(vec![m.id]);
    assert!(a.message(m.id).is_err());
    assert!(!std::path::Path::new(&file).exists());

    block_on(a.set_disappearing(me, 1)).unwrap();
    let sys = a.messages(me, None, 10).pop().unwrap();
    assert_eq!(sys.system, Some(SystemMessage::TimerChanged { seconds: 1 }));
    let gone = block_on(a.send_text(me, "vanish".into(), None)).unwrap();
    assert!(a.message(gone).unwrap().expires_at.is_some());
    std::thread::sleep(std::time::Duration::from_millis(1100));
    assert!(a.message(gone).is_err());
    a.expire_now().unwrap();
    assert!(a.messages(me, None, 10).iter().all(|m| m.text != "vanish"));

    a.clear_chat(me).unwrap();
    assert!(a.messages(me, None, 10).is_empty());
    a.delete_chat(other).unwrap();
    assert!(a.chat(other).is_err());
}

#[test]
fn oversize_attachment_fails_with_reason() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    let (chat, _) = a
        .open_chat_with(
            "key:0x00000000000000000000000000000000000000aa@relay2.taperelay.com:4246",
            "",
        )
        .unwrap();
    let big = dir.path().join("big.bin");
    std::fs::write(&big, vec![7u8; protocol::MAX_ATTACHMENT as usize + 1]).unwrap();
    let id = block_on(a.send_media(
        chat,
        MediaKind::File,
        big.to_string_lossy().into(),
        None,
        None,
        None,
    ))
    .unwrap();
    let m = a.message(id).unwrap();
    assert_eq!(
        (m.state, m.error.as_deref()),
        (MessageState::Failed, Some("too-large"))
    );
}

#[test]
fn address_preview_accepts_24_words() {
    let words = bip39::Mnemonic::from_entropy(&[1u8; 32])
        .unwrap()
        .to_string();
    assert_eq!(words.split(' ').count(), 24);
    assert!(account::keys::secret_from_mnemonic(&words).is_ok());
}

const ZED: &str = "key:0x00000000000000000000000000000000000000aa@relay2.taperelay.com:4246";
const YAN: &str = "key:0x00000000000000000000000000000000000000bb@relay2.taperelay.com:4246";
const XIA: &str = "key:0x00000000000000000000000000000000000000cc@relay1.taperelay.com:4246";

fn queued_to(a: &Account, recipient: &str) -> Vec<protocol::Envelope> {
    let conn = a.db();
    let mut stmt = conn
        .prepare("SELECT inner FROM send_queue WHERE recipient=? ORDER BY id")
        .unwrap();
    let rows = stmt
        .query_map([recipient], |r| r.get::<_, Vec<u8>>(0))
        .unwrap();
    rows.map(|r| protocol::decode_inner(r.unwrap()).unwrap().0)
        .collect()
}

#[test]
fn group_ops_offline() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    a.set_profile_sync("Me");
    let (_, zed) = a.open_chat_with(ZED, "Zed").unwrap();
    let (_, yan) = a.open_chat_with(YAN, "Yan").unwrap();
    let g = block_on(a.create_group("  Team ".into(), None, vec![zed, yan])).unwrap();
    let chat = a.chat(g).unwrap();
    assert!(chat.is_group && chat.can_send && chat.contact_id.is_none());
    assert_eq!((chat.name.as_str(), chat.member_count), ("Team", 3));
    let info = a.group_info(g).unwrap();
    assert!(info.is_member && info.is_admin);
    assert_eq!(info.members[0].contact_id, None);
    assert_eq!(info.members.len(), 3);
    assert_eq!(
        a.messages(g, None, 10)[0].system,
        Some(SystemMessage::GroupCreated {
            name: "Team".into()
        })
    );
    let state = queued_to(&a, ZED).pop().unwrap();
    let protocol::Body::GroupState {
        gname,
        members,
        admins,
        names,
        ver,
        ..
    } = &state.body
    else {
        panic!("expected group state");
    };
    assert_eq!(gname, "Team");
    assert_eq!(members.len(), 3);
    assert_eq!(admins.len(), 3);
    assert!(names.contains(&"Me".to_string()));
    assert!(*ver > 1);
    assert!(state.group.is_some());

    let m = block_on(a.send_text(g, "hi all".into(), None)).unwrap();
    assert_eq!(a.message(m).unwrap().state, MessageState::Pending);
    let d = a.message_deliveries(m);
    assert_eq!(d.len(), 2);
    assert!(d.iter().all(|d| d.state == MessageState::Pending));
    assert_eq!(queued_to(&a, YAN).pop().unwrap().group, state.group);

    block_on(a.set_group_name(g, "Crew".into())).unwrap();
    block_on(a.remove_group_member(g, yan)).unwrap();
    let removed_state = queued_to(&a, YAN).pop().unwrap();
    assert!(
        matches!(&removed_state.body, protocol::Body::GroupState { members, .. } if members.len() == 2)
    );
    let systems: Vec<SystemMessage> = a
        .messages(g, None, 10)
        .into_iter()
        .filter_map(|m| m.system)
        .collect();
    assert_eq!(
        systems[1..],
        [
            SystemMessage::GroupNameChanged {
                name: "Crew".into(),
                old_name: "Team".into()
            },
            SystemMessage::MemberRemoved {
                contact_id: Some(yan),
                name: "Yan".into()
            }
        ]
    );
    let link = a.group_invite_link(g).unwrap();
    let inv = invite::parse_link(&link).unwrap();
    assert_eq!(
        (inv.group, inv.group_name.as_str()),
        (state.group.clone(), "Crew")
    );
    block_on(a.leave_group(g)).unwrap();
    assert!(!a.chat(g).unwrap().can_send);
    assert!(block_on(a.send_text(g, "x".into(), None)).is_err());
    assert!(!a.group_info(g).unwrap().is_member);

    let (xia, _) = {
        let conn = a.db();
        chat::upsert_contact(&conn, XIA, "Xia").unwrap()
    };
    let mut env = protocol::Envelope::new(
        XIA,
        "Xia",
        protocol::Body::GroupState {
            gname: "Strangers".into(),
            members: vec![XIA.into(), a.info().account, ZED.into()],
            admins: vec![XIA.into()],
            names: vec!["Xia".into(), "Me".into(), "".into()],
            ver: 5,
            avatar: None,
        },
    );
    env.group = Some("g-xia".into());
    let mut events = Vec::new();
    {
        let conn = a.db();
        a.receive_group_state(&conn, &env, xia, &[], &mut events)
            .unwrap();
    }
    let sg = a
        .chats(None)
        .into_iter()
        .find(|c| c.name == "Strangers")
        .unwrap();
    assert!(sg.is_group && sg.is_request);
    let first = a.messages(sg.id, None, 10);
    assert_eq!(
        first[0].system,
        Some(SystemMessage::MemberAdded {
            contact_id: None,
            name: "Me".into()
        })
    );
    {
        let conn = a.db();
        a.receive_group_state(&conn, &env, xia, &[], &mut events)
            .unwrap();
    }
    assert_eq!(a.messages(sg.id, None, 10).len(), 1);
    a.accept_chat(sg.id).unwrap();
    assert!(!a.chat(sg.id).unwrap().is_request);
}

#[test]
fn settings_and_account_extras() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    let s = a.settings();
    assert!(s.read_receipts && s.mentions && s.delete_device_after.is_none());
    a.set_settings(Settings {
        read_receipts: false,
        media_quality: MediaQuality::Worse,
        auto_download: AutoDownload::Kib640,
        delete_device_after: Some(3600),
        mentions: false,
    });
    let s = a.settings();
    assert_eq!(
        (
            s.read_receipts,
            s.media_quality,
            s.auto_download,
            s.delete_device_after,
            s.mentions
        ),
        (
            false,
            MediaQuality::Worse,
            AutoDownload::Kib640,
            Some(3600),
            false
        )
    );
    a.set_account_muted(true);
    a.set_account_tag(" Work ".into());
    let info = a.info();
    assert!(info.muted);
    assert_eq!(info.tag, "Work");
    let me = a.saved_messages_chat().unwrap();
    block_on(a.send_text(me, "keep".into(), None)).unwrap();
    assert_eq!(a.count_messages_older_than(0), 0);
    assert_eq!(a.mnemonic().split(' ').count(), 12);
}

fn core_with_account(dir: &std::path::Path) -> (Arc<Core>, Arc<Account>) {
    drop(account(&dir.join("accounts")));
    let core = Core::new(dir.to_string_lossy().into(), Box::new(Quiet)).unwrap();
    let a = core.account(1).unwrap();
    (core, a)
}

#[test]
fn backup_roundtrip_offline() {
    let (d1, d2, out) = (
        tempfile::tempdir().unwrap(),
        tempfile::tempdir().unwrap(),
        tempfile::tempdir().unwrap(),
    );
    let (_core1, a) = core_with_account(d1.path());
    a.set_profile_sync("Backup Me");
    a.set_account_tag("Home".into());
    let me = a.saved_messages_chat().unwrap();
    block_on(a.send_text(me, "remember".into(), None)).unwrap();
    let img = out.path().join("pic.png");
    let bytes = png(10, 10, 300_000);
    std::fs::write(&img, &bytes).unwrap();
    block_on(a.send_media(
        me,
        MediaKind::Image,
        img.to_string_lossy().into(),
        None,
        None,
        None,
    ))
    .unwrap();
    let dest = out.path().to_string_lossy().into_owned();

    let plain = block_on(a.clone().export_backup(dest.clone(), None)).unwrap();
    assert!(plain.ends_with(".tapetapebak") && plain.contains(&a.info().address));
    let core2 = Core::new(d2.path().to_string_lossy().into(), Box::new(Quiet)).unwrap();
    assert_eq!(
        core2.backup_protection(plain.clone()).unwrap(),
        BackupProtection::RecoveryPhrase
    );
    let wrong = block_on(
        core2
            .clone()
            .import_backup(plain.clone(), account::keys::generate_mnemonic()),
    )
    .unwrap_err()
    .to_string();
    assert_eq!(wrong, "This recovery phrase does not belong to this backup");
    let id = block_on(core2.clone().import_backup(plain.clone(), a.mnemonic())).unwrap();
    let b = core2.account(id).unwrap();
    assert_eq!(b.info().address, a.info().address);
    assert_eq!(
        (b.info().name.as_str(), b.info().tag.as_str()),
        ("Backup Me", "Home")
    );
    let msgs = b.messages(b.saved_messages_chat().unwrap(), None, 10);
    assert_eq!(msgs[0].text, "remember");
    let file = msgs[1].file_path.clone().unwrap();
    assert!(file.starts_with(&d2.path().to_string_lossy().into_owned()));
    assert_eq!(std::fs::read(file).unwrap(), bytes);
    let again = block_on(core2.clone().import_backup(plain, a.mnemonic()))
        .unwrap_err()
        .to_string();
    assert_eq!(again, "This account has already been added");

    let locked = block_on(a.clone().export_backup(dest, Some("hunter2".into()))).unwrap();
    let d3 = tempfile::tempdir().unwrap();
    let core3 = Core::new(d3.path().to_string_lossy().into(), Box::new(Quiet)).unwrap();
    assert_eq!(
        core3.backup_protection(locked.clone()).unwrap(),
        BackupProtection::Passphrase
    );
    let bad = block_on(
        core3
            .clone()
            .import_backup(locked.clone(), "hunter3".into()),
    )
    .unwrap_err()
    .to_string();
    assert_eq!(bad, "Wrong passphrase or damaged backup");
    let id3 = block_on(core3.clone().import_backup(locked, "hunter2".into())).unwrap();
    assert_eq!(core3.account(id3).unwrap().mnemonic(), a.mnemonic());
    assert_eq!(
        std::fs::read_dir(d3.path().join("accounts"))
            .unwrap()
            .count(),
        1
    );
}

fn fake_rpc(
    handler: impl Fn(&str, &serde_json::Value) -> serde_json::Value + Send + 'static,
) -> String {
    use std::io::{Read, Write};
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", l.local_addr().unwrap());
    std::thread::spawn(move || {
        for s in l.incoming() {
            let mut s = s.unwrap();
            let mut buf = Vec::new();
            let mut chunk = [0u8; 8192];
            let body = loop {
                let n = s.read(&mut chunk).unwrap_or(0);
                if n == 0 {
                    break None;
                }
                buf.extend_from_slice(&chunk[..n]);
                let Some(end) = buf.windows(4).position(|w| w == b"\r\n\r\n") else {
                    continue;
                };
                let len: usize = String::from_utf8_lossy(&buf[..end])
                    .lines()
                    .find_map(|l| {
                        l.to_lowercase()
                            .strip_prefix("content-length: ")
                            .map(|v| v.parse().unwrap())
                    })
                    .unwrap_or(0);
                if buf.len() >= end + 4 + len {
                    break Some(buf[end + 4..end + 4 + len].to_vec());
                }
            };
            let Some(body) = body else { continue };
            let req: serde_json::Value = serde_json::from_slice(&body).unwrap();
            let mut reply = handler(req["method"].as_str().unwrap(), &req["params"]);
            reply["jsonrpc"] = "2.0".into();
            reply["id"] = req["id"].clone();
            let reply = reply.to_string();
            let _ = write!(
                s,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reply}",
                reply.len()
            );
        }
    });
    url
}

#[test]
fn broadcast_reports_failure_only_when_tx_is_unknown() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    let rpc_for = |accept: bool| {
        let seen = Arc::new(std::sync::Mutex::new(None::<String>));
        let url = fake_rpc(move |method, params| match method {
            "eth_getTransactionCount" => serde_json::json!({ "result": "0x7" }),
            "eth_sendRawTransaction" => {
                let raw = crate::chain::unhex(&params[0]).unwrap();
                *seen.lock().unwrap() = Some(crate::chain::hex0x(&taperelay::evm::keccak256(&raw)));
                serde_json::json!({ "error": { "code": -32000, "message": "connection reset" } })
            }
            "eth_getTransactionReceipt" => serde_json::json!({ "result": null }),
            "eth_getTransactionByHash" => {
                let known = accept && seen.lock().unwrap().as_deref() == params[0].as_str();
                serde_json::json!({ "result": if known { serde_json::json!({}) } else { serde_json::Value::Null } })
            }
            m => panic!("unexpected {m}"),
        });
        crate::chain::Rpc::with_url(&a.http(), 56, &url).unwrap()
    };
    let call = crate::chain::Call {
        to: [1; 20],
        value: 1,
        data: Vec::new(),
    };
    let rpc = rpc_for(true);
    let tx = block_on(rpc.sign(&a.keys.id, &call, 21_000, 1)).unwrap();
    block_on(rpc.broadcast(&tx)).unwrap();
    let rpc = rpc_for(false);
    let tx = block_on(rpc.sign(&a.keys.id, &call, 21_000, 1)).unwrap();
    assert_eq!(
        block_on(rpc.broadcast(&tx)).unwrap_err().to_string(),
        "connection reset"
    );
}

#[test]
fn fee_cap() {
    let t = crate::chain::token("56:BNB").unwrap();
    let est = |fee: &str| SendEstimate {
        amount: "1".into(),
        fee: fee.into(),
        fee_symbol: "BNB".into(),
        balance: "2".into(),
        max: "1".into(),
        error: None,
    };
    let check = |fresh: &str, confirmed: Option<&str>| {
        crate::wallet::check_fee(t, &est(fresh), confirmed).map_err(|e| e.to_string())
    };
    assert_eq!(check("0.0003", None), Ok(()));
    assert_eq!(check("0.0003", Some("0.0002")), Ok(()));
    assert_eq!(
        check("0.00030001", Some("0.0002")),
        Err("fee-changed".into())
    );
    assert_eq!(check("0.0001", Some("0.0002")), Ok(()));
}

fn red_packet_from(a: &Account) -> protocol::Body {
    let id = crate::packet_abi::new_packet_id();
    protocol::Body::Redpacket {
        chain: "bsc".into(),
        contract: crate::chain::TEST_PACKETS.into(),
        claim_key: format!("0x{}", "11".repeat(32)),
        packet: id,
        token: "0x55d398326f99059fF775485246999027B3197955".into(),
        symbol: "FAKE".into(),
        total: "1.5".into(),
        count: 3,
        greeting: "Lucky".into(),
        tx: "0x01".into(),
        creator: a.keys.id.address().to_uppercase().replace("0X", "0x"),
    }
}

#[test]
fn packets_offline() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(&dir.path().join("a"));
    let b = account(&dir.path().join("b"));
    let (_, a_in_b) = b.open_chat_with(a.keys.account(), "Ann").unwrap();
    let g = block_on(b.create_group("Team".into(), None, vec![a_in_b])).unwrap();
    let grp = crate::chat::target(&b.db(), g).unwrap().grp;
    let receive = |body: protocol::Body| {
        let mut env = a.envelope(&a.db(), body).unwrap();
        env.group = grp.clone();
        let mut conn = b.db();
        let tx = conn.transaction().unwrap();
        let mut events = Vec::new();
        let r = match &env.body {
            protocol::Body::RedpacketClaim { .. } => {
                b.receive_claim(&tx, g, a_in_b, &env.body, &mut events)
            }
            _ => b
                .receive_message(&tx, &env, g, a_in_b, &[], &mut events)
                .map(|_| None),
        };
        tx.commit().unwrap();
        r
    };

    let body = red_packet_from(&a);
    let protocol::Body::Redpacket { packet: id, .. } = &body else {
        unreachable!()
    };
    let id = id.clone();
    receive(body).unwrap();
    let m = b.messages(g, None, 10).pop().unwrap();
    assert_eq!((m.kind, m.text.as_str()), (MessageKind::RedPacket, "Lucky"));
    let p = m.packet.unwrap();
    assert_eq!(p.token_id.as_deref(), Some("56:USDT"));
    assert_eq!(
        (p.symbol.as_str(), p.amount.as_str(), p.count, p.mine),
        ("USDT", "1.5", 3, false)
    );
    assert_eq!(p.creator, a.keys.id.address());
    assert_eq!(p.status.state, PacketState::Open);
    let count = b.messages(g, None, 50).len();
    assert!(block_on(b.forward(vec![m.id], g)).unwrap().is_empty());
    assert_eq!(b.messages(g, None, 50).len(), count);

    let bad_address = b.keys.id.address().to_string();
    let forgeries: [fn(&mut protocol::Body, &str); 7] = [
        |b, _| {
            if let protocol::Body::Redpacket { contract, .. } = b {
                *contract = "0x0000000000000000000000000000000000000001".into()
            }
        },
        |b, me| {
            if let protocol::Body::Redpacket { creator, .. } = b {
                *creator = me.into()
            }
        },
        |b, _| {
            if let protocol::Body::Redpacket { token, .. } = b {
                *token = "0x0000000000000000000000000000000000000002".into()
            }
        },
        |b, _| {
            if let protocol::Body::Redpacket { total, .. } = b {
                *total = "1.0000000000000000001".into()
            }
        },
        |b, _| {
            if let protocol::Body::Redpacket { chain, .. } = b {
                *chain = "eth".into()
            }
        },
        |b, _| {
            if let protocol::Body::Redpacket { claim_key, .. } = b {
                *claim_key = format!("0x{}", "00".repeat(32))
            }
        },
        |b, _| {
            if let protocol::Body::Redpacket { claim_key, .. } = b {
                *claim_key = "0x1234".into()
            }
        },
    ];
    for forge in forgeries {
        let mut forged = red_packet_from(&a);
        forge(&mut forged, &bad_address);
        assert!(receive(forged).is_err());
    }

    let notice = receive(protocol::Body::RedpacketClaim {
        packet: id,
        claimer: a.keys.id.address().into(),
        amount: "0.5".into(),
        tx: "0x02".into(),
    })
    .unwrap()
    .unwrap();
    let before = b.messages(g, None, 50).len();
    let (_, stored_in_b) = b.load_packet(m.id).unwrap();
    let records = |who: &str| {
        let mut out = vec![0u8; 31];
        out.push(0x20);
        out.extend([0u8; 31]);
        out.push(1);
        out.extend([0u8; 12]);
        out.extend(taperelay::evm::parse_address(who).unwrap());
        out.extend([0u8; 31]);
        out.push(5);
        serde_json::json!({ "result": crate::chain::hex0x(&out) })
    };
    let other = b.keys.id.address().to_string();
    let url = fake_rpc(move |_, _| records(&other));
    let rpc = crate::chain::Rpc::with_url(&b.http(), 56, &url).unwrap();
    assert!(block_on(b.confirm_claim(&rpc, &stored_in_b, &notice))
        .unwrap()
        .is_empty());
    assert_eq!(b.messages(g, None, 50).len(), before);
    let claimer = a.keys.id.address().to_string();
    let url = fake_rpc(move |_, _| records(&claimer));
    let rpc = crate::chain::Rpc::with_url(&b.http(), 56, &url).unwrap();
    assert!(!block_on(b.confirm_claim(&rpc, &stored_in_b, &notice))
        .unwrap()
        .is_empty());
    let last = b.messages(g, None, 10).pop().unwrap();
    assert_eq!(
        last.system,
        Some(SystemMessage::PacketClaimed {
            claimer: Some(a_in_b),
            owner: Some(a_in_b),
            packet_msg_id: Some(m.id)
        })
    );

    let stored = crate::packet::Stored::from_body(&red_packet_from(&b), b.keys.account()).unwrap();
    let mut creating = stored.clone();
    creating.status.state = PacketState::Creating;
    let own = b
        .store_draft(
            g,
            crate::message::Draft {
                kind: MessageKind::RedPacket.code(),
                text: "Lucky".into(),
                packet: Some(serde_json::to_string(&creating).unwrap()),
                ..Default::default()
            },
            false,
        )
        .unwrap();
    assert!(queued_to(&b, a.keys.account())
        .iter()
        .all(|e| !matches!(e.body, protocol::Body::Redpacket { .. })));
    block_on(b.resend(vec![own])).unwrap();
    assert!(queued_to(&b, a.keys.account())
        .iter()
        .all(|e| !matches!(e.body, protocol::Body::Redpacket { .. })));
    let mine = b.message(own).unwrap().packet.unwrap();
    assert!(mine.mine);
    assert_eq!(mine.status.state, PacketState::Creating);
    b.db()
        .execute(
            "UPDATE messages SET packet=? WHERE id=?",
            rusqlite::params![serde_json::to_string(&stored).unwrap(), own],
        )
        .unwrap();
    block_on(b.resend(vec![own])).unwrap();
    let sent = queued_to(&b, a.keys.account()).pop().unwrap();
    let protocol::Body::Redpacket {
        packet,
        total,
        creator,
        claim_key,
        ..
    } = &sent.body
    else {
        panic!("expected red packet, got {:?}", sent.body);
    };
    assert_eq!(
        (packet, total.as_str(), creator.as_str()),
        (&stored.id, "1.5", b.keys.id.address())
    );
    assert_eq!(claim_key, &stored.claim_key);
    assert_eq!(sent.group, grp);
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "reads BSC and X Layer mainnet; sends no transactions"]
async fn live_wallet_reads() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    let balances = a.clone().balances().await;
    assert_eq!(balances.len(), 6);
    assert!(
        balances.iter().all(|b| b.amount.as_deref() == Some("0")),
        "{balances:?}"
    );
    let to = "0x00000000000000000000000000000000000000aa".to_string();
    for t in crate::chain::TOKENS.iter() {
        let e = a
            .clone()
            .estimate_send(t.id(), to.clone(), "1.25".into())
            .await
            .unwrap();
        println!(
            "{} send estimate: fee {} {} error {:?}",
            t.id(),
            e.fee,
            e.fee_symbol,
            e.error
        );
        assert_eq!(e.amount, "1.25");
        assert_ne!(e.fee, "0");
        assert_eq!(e.error.as_deref(), Some("insufficient-balance"));
        assert_eq!(e.max, "0");
    }
    let bad = a
        .clone()
        .estimate_send("56:BNB".into(), "0x123".into(), "x".into())
        .await
        .unwrap();
    assert_eq!(bad.error.as_deref(), Some("invalid-address"));
    let bad = a
        .clone()
        .estimate_send("56:BNB".into(), to.clone(), "1.2.3".into())
        .await
        .unwrap();
    assert_eq!(bad.error.as_deref(), Some("invalid-amount"));
    for (token, count, err) in [
        ("56:USDT", 3, "insufficient-balance"),
        ("56:BNB", 5, "insufficient-balance"),
        ("56:USDT", 0, "invalid-count"),
    ] {
        let e = a
            .clone()
            .estimate_packet(token.into(), "0.5".into(), count)
            .await
            .unwrap();
        println!(
            "{token} packet estimate: fee {} {} error {:?}",
            e.fee, e.fee_symbol, e.error
        );
        assert_eq!(e.error.as_deref(), Some(err));
    }
    assert!(a
        .clone()
        .estimate_packet("196:BEM".into(), "1".into(), 1)
        .await
        .is_err());
    let status = a
        .clone()
        .tx_status(56, format!("0x{}", "00".repeat(32)))
        .await
        .unwrap();
    assert_eq!(status, TxStatus::NotFound);
    let shop = a.clone().shop_info().await.unwrap();
    println!("shop open cost {} OKB", shop.open_cost);
    assert!(!shop.ready && shop.name.is_none() && shop.balance == "0");
    assert_eq!(shop.address, a.wallet_address());
}

#[derive(Default)]
struct Recorder(std::sync::Mutex<Vec<Event>>);

impl EventListener for Recorder {
    fn on_event(&self, _: u32, e: Event) {
        self.0.lock().unwrap().push(e);
    }
}

impl Recorder {
    fn progress(&self, id: u32) -> Vec<(u64, u64)> {
        self.0
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                Event::TransferProgress {
                    msg_id,
                    done,
                    total,
                } if *msg_id == id => Some((*done, *total)),
                _ => None,
            })
            .collect()
    }
}

fn recorded(dir: &std::path::Path) -> (Arc<Account>, Arc<Recorder>) {
    let rec = Arc::new(Recorder::default());
    let a = Account::create(
        1,
        dir.to_path_buf(),
        &account::keys::generate_mnemonic(),
        "relay1.taperelay.com",
        Arc::new(crate::net::Net::direct()),
        rec.clone(),
    )
    .unwrap();
    (a, rec)
}

#[tokio::test(flavor = "multi_thread")]
async fn large_attachments_offline() {
    let fake = crate::files::fake::start().await;
    let dir = tempfile::tempdir().unwrap();
    let (a, a_events) = recorded(&dir.path().join("a"));
    let (b, b_events) = recorded(&dir.path().join("b"));
    assert_eq!(a.max_attachment_size(), protocol::MAX_ATTACHMENT);
    a.set_relay_files_backend(format!(" {} ", fake.url))
        .unwrap();
    assert_eq!(a.relay_files_backend(), fake.url.trim_end_matches('/'));
    assert_eq!(a.max_attachment_size(), 100 << 20);
    let (chat, _) = a.open_chat_with(b.keys.account(), "Bo").unwrap();
    let (_, a_in_b) = b.open_chat_with(a.keys.account(), "Ann").unwrap();
    a.db()
        .execute(
            "INSERT INTO peer_keys (account, key, fetched) VALUES (?,?,?)",
            rusqlite::params![
                b.keys.account(),
                b.keys.enc.public().to_vec(),
                protocol::now_ms()
            ],
        )
        .unwrap();

    let data: Vec<u8> = (0..(3 << 20) + 77).map(|_| rand::random()).collect();
    let path = dir.path().join("clip.mp4");
    std::fs::write(&path, &data).unwrap();
    let send = |a: &Arc<Account>| {
        let a = a.clone();
        let path = path.to_string_lossy().to_string();
        async move {
            a.send_media(
                chat,
                MediaKind::Video,
                path,
                Some("look".into()),
                None,
                None,
            )
            .await
            .unwrap()
        }
    };
    let id = send(&a).await;
    assert_eq!(a.message(id).unwrap().state, MessageState::Pending);
    assert!(queued_to(&a, b.keys.account()).is_empty());
    a.run_transfers().await.unwrap();
    let progress = a_events.progress(id);
    assert!(!progress.is_empty());
    assert_eq!(progress.last().unwrap().0, progress.last().unwrap().1);
    let env = queued_to(&a, b.keys.account()).pop().unwrap();
    let protocol::Body::Media {
        reference: Some(_),
        size,
        ..
    } = &env.body
    else {
        panic!("expected remote media, got {:?}", env.body);
    };
    assert_eq!(*size, data.len() as u64);
    let raw: Vec<u8> = a
        .db()
        .query_row(
            "SELECT inner FROM send_queue ORDER BY id DESC LIMIT 1",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert!(raw.len() < 4096);
    assert!(protocol::frames(&raw).unwrap().len() == 1);

    let b_chat = b.chat_for(a_in_b);
    let deliver = |b: &Account, env: &protocol::Envelope| {
        let mut conn = b.db();
        let tx = conn.transaction().unwrap();
        b.receive_message(&tx, env, b_chat, a_in_b, &[], &mut Vec::new())
            .unwrap();
        tx.commit().unwrap();
    };
    let mut no_backend = env.clone();
    no_backend.id = protocol::new_id();
    deliver(&b, &no_backend);
    let waiting = b.messages(b_chat, None, 10).pop().unwrap();
    assert_eq!(waiting.download, Some(DownloadState::Available));
    b.download(waiting.id).unwrap();
    b.run_transfers().await.unwrap();
    assert_eq!(
        b.message(waiting.id).unwrap().download,
        Some(DownloadState::Failed)
    );
    b.set_relay_files_backend(fake.url.clone()).unwrap();
    let deliver = |b: &Account, env: &protocol::Envelope| {
        let mut conn = b.db();
        let tx = conn.transaction().unwrap();
        b.receive_message(&tx, env, b_chat, a_in_b, &[], &mut Vec::new())
            .unwrap();
        tx.commit().unwrap();
    };
    deliver(&b, &env);
    let got = b.messages(b.chat_for(a_in_b), None, 10).pop().unwrap();
    assert_eq!(
        (got.kind, got.text.as_str(), got.size),
        (MessageKind::Video, "look", Some(data.len() as u64))
    );
    assert_eq!(got.download, Some(DownloadState::Downloading));
    assert!(got.file_path.is_none());
    assert!(b
        .forward(vec![got.id], got.chat_id)
        .await
        .unwrap()
        .is_empty());
    b.run_transfers().await.unwrap();
    let done = b.message(got.id).unwrap();
    assert_eq!(done.download, None);
    assert!(std::fs::read(done.file_path.unwrap()).unwrap() == data);
    assert_eq!(
        b_events.progress(got.id).last(),
        Some(&(data.len() as u64, data.len() as u64))
    );

    let mut s = b.settings();
    s.auto_download = AutoDownload::Mib25;
    b.set_settings(s.clone());
    let mut small_enough = env.clone();
    small_enough.id = protocol::new_id();
    deliver(&b, &small_enough);
    let auto = b.messages(b.chat_for(a_in_b), None, 10).pop().unwrap();
    assert_eq!(auto.download, Some(DownloadState::Downloading));
    s.auto_download = AutoDownload::Kib640;
    b.set_settings(s);
    let mut manual = env.clone();
    manual.id = protocol::new_id();
    deliver(&b, &manual);
    let m = b.messages(b.chat_for(a_in_b), None, 10).pop().unwrap();
    assert_eq!(m.download, Some(DownloadState::Available));
    b.download(m.id).unwrap();
    assert_eq!(
        b.message(m.id).unwrap().download,
        Some(DownloadState::Downloading)
    );
    b.run_transfers().await.unwrap();
    assert!(std::fs::read(b.message(m.id).unwrap().file_path.unwrap()).unwrap() == data);

    fake.state.objects.lock().unwrap().clear();
    let mut gone = env.clone();
    gone.id = protocol::new_id();
    deliver(&b, &gone);
    let g = b.messages(b.chat_for(a_in_b), None, 10).pop().unwrap();
    b.download(g.id).unwrap();
    b.run_transfers().await.unwrap();
    assert_eq!(
        b.message(g.id).unwrap().download,
        Some(DownloadState::Failed)
    );

    fake.state
        .quota_exceeded
        .store(true, std::sync::atomic::Ordering::SeqCst);
    let id = send(&a).await;
    a.run_transfers().await.unwrap();
    let m = a.message(id).unwrap();
    assert_eq!(
        (m.state, m.error.as_deref()),
        (MessageState::Failed, Some("quota-exceeded"))
    );

    a.set_relay_files_backend(String::new()).unwrap();
    let id = send(&a).await;
    let m = a.message(id).unwrap();
    assert_eq!(
        (m.state, m.error.as_deref()),
        (MessageState::Failed, Some("too-large"))
    );
}

impl Account {
    fn chat_for(&self, contact_id: u32) -> u32 {
        crate::chat::chat_for_contact(&self.db(), contact_id).unwrap()
    }
}

#[test]
fn shared_chats_and_delete_contact() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    let (zed_chat, zed) = a.open_chat_with(ZED, "Zed").unwrap();
    let (_, yan) = a.open_chat_with(YAN, "Yan").unwrap();
    let g1 = block_on(a.create_group("One".into(), None, vec![zed, yan])).unwrap();
    let g2 = block_on(a.create_group("Two".into(), None, vec![zed])).unwrap();
    a.set_archived(g2, true).unwrap();
    let mut shared: Vec<u32> = a.shared_chats(zed).iter().map(|c| c.id).collect();
    shared.sort();
    assert_eq!(shared, vec![g1, g2]);
    assert_eq!(
        a.shared_chats(yan).iter().map(|c| c.id).collect::<Vec<_>>(),
        vec![g1]
    );
    assert!(a.shared_chats(zed).iter().all(|c| c.is_group));

    block_on(a.send_text(zed_chat, "hi".into(), None)).unwrap();
    let flags = |id: u32| -> (bool, bool, bool) {
        a.db()
            .query_row(
                "SELECT allowed, allow_synced, hidden FROM contacts WHERE id=?",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap()
    };
    assert_eq!(flags(zed), (true, false, false));
    a.delete_contact(zed).unwrap();
    assert_eq!(flags(zed), (false, false, true));
    assert!(a.contacts().iter().all(|c| c.id != zed));
    assert!(a.contacts().iter().any(|c| c.id == yan));
    assert!(a.chats(None).iter().any(|c| c.id == zed_chat));
    assert_eq!(a.messages(zed_chat, None, 10).len(), 1);
    assert_eq!(a.group_info(g1).unwrap().members.len(), 3);
    assert!(a.delete_contact(9999).is_err());

    a.open_chat_with(ZED, "Zed").unwrap();
    assert!(a.contacts().iter().any(|c| c.id == zed));
}

#[test]
fn group_rename_keeps_old_name_and_reads_old_rows() {
    let old: SystemMessage =
        serde_json::from_str(r#"{"GroupNameChanged":{"name":"New"}}"#).unwrap();
    assert_eq!(
        old,
        SystemMessage::GroupNameChanged {
            name: "New".into(),
            old_name: String::new()
        }
    );
}

#[test]
fn group_member_limit() {
    use crate::protocol::MAX_GROUP_MEMBERS;
    let dir = tempfile::tempdir().unwrap();
    let a = account(&dir.path().join("a"));
    let ids: Vec<u32> = {
        let conn = a.db();
        (0..MAX_GROUP_MEMBERS)
            .map(|i| {
                let acct = format!("key:0x{:040x}@relay1.taperelay.com:4246", i + 1);
                crate::chat::upsert_contact(&conn, &acct, &format!("成员{i}")).unwrap().0
            })
            .collect()
    };
    // creating with yourself + MAX members is over the limit
    let err = block_on(a.create_group("大群".into(), None, ids.clone())).unwrap_err();
    assert!(err.to_string().contains("group-full"), "{err}");
    // yourself + MAX-1 members is exactly full, and its group state can be sent
    let g = block_on(a.create_group("大群".into(), None, ids[..MAX_GROUP_MEMBERS - 1].to_vec())).unwrap();
    assert_eq!(a.group_info(g).unwrap().members.len(), MAX_GROUP_MEMBERS);
    // one more is refused and nothing changes
    let err = block_on(a.add_group_members(g, vec![ids[MAX_GROUP_MEMBERS - 1]])).unwrap_err();
    assert!(err.to_string().contains("group-full"), "{err}");
    assert_eq!(a.group_info(g).unwrap().members.len(), MAX_GROUP_MEMBERS);
    // re-adding an existing member is fine
    block_on(a.add_group_members(g, vec![ids[0]])).unwrap();
}

// Envelopes over MAX_JSON are refused when queued instead of being dropped silently by every receiver.
#[test]
fn oversized_envelope_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(&dir.path().join("a"));
    let ids: Vec<String> = (0..1000)
        .map(|i| format!("key:0x{:040x}@relay1.taperelay.com:4246", i + 1))
        .collect();
    let env = protocol::Envelope::new(
        a.keys.account(),
        "",
        protocol::Body::GroupState {
            gname: "g".into(),
            members: ids.clone(),
            admins: ids,
            names: vec!["张三丰的小号".into(); 1000],
            ver: 1,
            avatar: None,
        },
    );
    let conn = a.db();
    let err = a
        .enqueue(&conn, "key:0x0000000000000000000000000000000000000001@relay1.taperelay.com:4246", None, &env, &[])
        .unwrap_err();
    assert_eq!(err.to_string(), "too-large");
    let n: i64 = conn.query_row("SELECT COUNT(*) FROM send_queue", [], |r| r.get(0)).unwrap();
    assert_eq!(n, 0);
}

#[test]
fn profile_reaches_contacts_allowed_later() {
    let dir = tempfile::tempdir().unwrap();
    let a = account(dir.path());
    // 先写签名档（这时 Xia 还不是已通过的联系人，不会发给她）
    block_on(a.set_bio("我的签名档".into())).unwrap();
    let queued = |a: &Account| -> i64 {
        a.db()
            .query_row("SELECT COUNT(*) FROM send_queue WHERE recipient=?", [XIA], |r| r.get(0))
            .unwrap()
    };
    let (xia, _) = {
        let conn = a.db();
        chat::upsert_contact(&conn, XIA, "Xia").unwrap()
    };
    assert_eq!(queued(&a), 0);
    // 之后才通过 Xia（接受她的聊天请求）：要把签名档补发给她
    let chat_id = a.chat_for_contact(xia).unwrap();
    a.accept_chat(chat_id).unwrap();
    assert_eq!(queued(&a), 1, "刚通过的联系人要收到一份资料");
    // 已经通过的，再接受一次不重复发
    a.accept_chat(chat_id).unwrap();
    assert_eq!(queued(&a), 1);
}
