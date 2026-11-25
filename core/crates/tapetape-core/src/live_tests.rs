use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::*;

#[derive(Clone, Default)]
struct Recorder(Arc<Mutex<Vec<(u32, Event)>>>);

impl EventListener for Recorder {
    fn on_event(&self, account_id: u32, event: Event) {
        self.0.lock().unwrap().push((account_id, event));
    }
}

impl Recorder {
    fn saw(&self, f: impl Fn(&Event) -> bool) -> bool {
        self.0.lock().unwrap().iter().any(|(_, e)| f(e))
    }
}

async fn wait<T>(what: &str, f: impl Fn() -> Option<T>) -> T {
    for _ in 0..240 {
        if let Some(v) = f() {
            println!("ok: {what}");
            return v;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    panic!("timed out waiting for {what}");
}

fn find(a: &Account, chat_id: u32, text: &str) -> Option<Message> {
    a.messages(chat_id, None, 100)
        .into_iter()
        .find(|m| m.text == text)
}

fn find_by(a: &Account, chat_id: u32, f: impl Fn(&Message) -> bool) -> Option<Message> {
    a.messages(chat_id, None, 200).into_iter().find(|m| f(m))
}

fn write_file(dir: &tempfile::TempDir, name: &str, bytes: &[u8]) -> String {
    let p = dir.path().join(name);
    std::fs::write(&p, bytes).unwrap();
    p.to_string_lossy().into_owned()
}

fn state_of(a: &Account, chat_id: u32, msg_id: u32) -> Message {
    a.messages(chat_id, None, 100)
        .into_iter()
        .find(|m| m.id == msg_id)
        .unwrap()
}

async fn new_account(core: &Arc<Core>, node: &str, name: &str) -> Arc<Account> {
    let id = core
        .clone()
        .add_account(core.generate_mnemonic(), Some(node.to_string()))
        .await
        .unwrap();
    let a = core.account(id).unwrap();
    a.set_profile(name.to_string(), None).await.unwrap();
    println!("{name} = {}", a.info().account);
    a
}

#[tokio::test(flavor = "multi_thread")]
#[ignore]
async fn live_invite_chat_and_stranger() {
    let (dir_a, dir_b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let path = |d: &tempfile::TempDir| d.path().to_string_lossy().into_owned();
    let (rec_a, rec_b) = (Recorder::default(), Recorder::default());
    let core_a = Core::new(path(&dir_a), Box::new(rec_a.clone())).unwrap();
    let core_b = Core::new(path(&dir_b), Box::new(rec_b.clone())).unwrap();

    let nodes = core_a.nodes().await.unwrap();
    println!("nodes: {nodes:?}");
    assert!(!nodes.is_empty());
    let node_a = &nodes[0];
    let node_b = &nodes[1 % nodes.len()];

    let alice = new_account(&core_a, node_a, "Alice").await;
    let bob = new_account(&core_b, node_b, "Bob").await;
    assert_eq!(core_a.selected_account(), Some(alice.info().id));
    assert_eq!(core_a.accounts().len(), 1);
    alice.clone().start();
    bob.clone().start();
    wait("both connected", || {
        (alice.connectivity() == Connectivity::Connected
            && bob.connectivity() == Connectivity::Connected)
            .then_some(())
    })
    .await;

    let link = alice.invite_link();
    println!("invite link: {link}");
    let bob_chat = bob.accept_invite(link).await.unwrap();
    wait("bob sees verified chat", || {
        bob.chat(bob_chat).unwrap().verified.then_some(())
    })
    .await;
    let alice_chat = wait("alice has verified chat", || {
        let c = alice.chats(None).into_iter().next()?;
        alice.chat(c.id).unwrap().verified.then_some(c.id)
    })
    .await;
    assert_eq!(alice.chat(alice_chat).unwrap().name, "Bob");
    assert_eq!(bob.chat(bob_chat).unwrap().name, "Alice");

    let m1 = bob
        .send_text(bob_chat, "hello alice 你好".into(), None)
        .await
        .unwrap();
    let got = wait("alice receives", || {
        find(&alice, alice_chat, "hello alice 你好")
    })
    .await;
    assert_eq!(got.state, MessageState::Incoming);
    assert_eq!(got.sender_name, "Bob");
    assert_eq!(alice.chats(None)[0].unread, 1);
    wait("bob's message delivered", || {
        matches!(
            state_of(&bob, bob_chat, m1).state,
            MessageState::Delivered | MessageState::Read
        )
        .then_some(())
    })
    .await;

    let long: String = (0..6000)
        .map(|_| char::from(b'a' + rand::random::<u8>() % 26))
        .chain("长消息".chars())
        .collect();
    let m2 = alice
        .send_text(alice_chat, long.clone(), Some(got.id))
        .await
        .unwrap();
    let reply = wait("bob receives long reply", || find(&bob, bob_chat, &long)).await;
    assert_eq!(reply.reply_to, Some(m1));
    wait("alice's reply delivered", || {
        (state_of(&alice, alice_chat, m2).state == MessageState::Delivered).then_some(())
    })
    .await;

    alice.mark_read(alice_chat);
    assert_eq!(alice.chats(None)[0].unread, 0);
    wait("bob sees read receipt", || {
        (state_of(&bob, bob_chat, m1).state == MessageState::Read).then_some(())
    })
    .await;

    alice.set_draft(alice_chat, Some("draft".into()));
    assert_eq!(alice.chats(None)[0].draft.as_deref(), Some("draft"));

    let files = tempfile::tempdir().unwrap();
    alice.set_bio("alice bio".into()).await.unwrap();
    let avatar = write_file(&files, "me.png", &crate::local_tests::png(32, 32, 500));
    alice
        .set_profile("Alice".into(), Some(avatar))
        .await
        .unwrap();
    let alice_contact = bob.chat(bob_chat).unwrap().contact_id.unwrap();
    wait("bob gets alice's bio and avatar", || {
        let c = bob.contact(alice_contact).ok()?;
        (c.bio == "alice bio" && c.avatar_path.is_some()).then_some(())
    })
    .await;

    let image = crate::local_tests::png(64, 48, 20_000);
    let img_path = write_file(&files, "photo.png", &image);
    let img = bob
        .send_media(
            bob_chat,
            MediaKind::Image,
            img_path,
            Some("pic".into()),
            None,
            None,
        )
        .await
        .unwrap();
    let got_img = wait("alice receives image", || {
        find_by(&alice, alice_chat, |m| m.kind == MessageKind::Image)
    })
    .await;
    assert_eq!((got_img.width, got_img.height), (Some(64), Some(48)));
    assert_eq!(got_img.text, "pic");
    assert_eq!(got_img.file_name.as_deref(), Some("photo.png"));
    assert_eq!(
        std::fs::read(got_img.file_path.as_ref().unwrap()).unwrap(),
        image
    );
    wait("image delivered", || {
        (state_of(&bob, bob_chat, img).state == MessageState::Delivered).then_some(())
    })
    .await;

    let voice_path = write_file(
        &files,
        "voice.m4a",
        &(0..3000).map(|_| rand::random::<u8>()).collect::<Vec<u8>>(),
    );
    let voice = alice
        .send_media(
            alice_chat,
            MediaKind::Voice,
            voice_path,
            None,
            Some(got_img.id),
            Some(3200),
        )
        .await
        .unwrap();
    let got_voice = wait("bob receives voice", || {
        find_by(&bob, bob_chat, |m| m.kind == MessageKind::Voice)
    })
    .await;
    assert_eq!(got_voice.duration_ms, Some(3200));
    assert_eq!(got_voice.reply_to, Some(img));
    assert_eq!(got_voice.mime, alice.message(voice).unwrap().mime);
    assert_eq!(got_voice.size, Some(3000));

    alice.react(got_img.id, "👍".into()).await.unwrap();
    wait("bob sees reaction", || {
        let r = bob.message(img).ok()?.reactions;
        (r == vec![Reaction {
            emoji: "👍".into(),
            count: 1,
            mine: false,
        }])
        .then_some(())
    })
    .await;
    assert!(rec_b.saw(|e| *e
        == Event::IncomingReaction {
            chat_id: bob_chat,
            msg_id: img
        }));
    assert_eq!(bob.message_reactions(img)[0].name, "Alice");
    alice.react(got_img.id, "".into()).await.unwrap();
    wait("bob sees reaction removed", || {
        bob.message(img).ok()?.reactions.is_empty().then_some(())
    })
    .await;

    bob.forward(vec![got_voice.id], bob_chat).await.unwrap();
    wait("alice receives forwarded voice", || {
        find_by(&alice, alice_chat, |m| {
            m.kind == MessageKind::Voice && m.forwarded
        })
    })
    .await;

    alice.set_disappearing(alice_chat, 4).await.unwrap();
    wait("bob gets timer", || {
        (bob.chat(bob_chat).unwrap().ephemeral_seconds == 4).then_some(())
    })
    .await;
    assert!(find_by(&bob, bob_chat, |m| m.system
        == Some(SystemMessage::TimerChanged { seconds: 4 }))
    .is_some());
    let vanish = alice
        .send_text(alice_chat, "vanish".into(), None)
        .await
        .unwrap();
    assert!(alice.message(vanish).unwrap().expires_at.is_some());
    wait("bob receives vanishing message", || {
        find(&bob, bob_chat, "vanish")
    })
    .await;
    bob.mark_read(bob_chat);
    assert!(find(&bob, bob_chat, "vanish").unwrap().expires_at.is_some());
    wait("vanishing message gone on both sides", || {
        (find(&bob, bob_chat, "vanish").is_none() && alice.message(vanish).is_err()).then_some(())
    })
    .await;
    alice.set_disappearing(alice_chat, 0).await.unwrap();
    wait("bob gets timer off", || {
        (bob.chat(bob_chat).unwrap().ephemeral_seconds == 0).then_some(())
    })
    .await;

    let bob_id = bob.info().id;
    bob.stop();
    let queued = bob
        .send_text(bob_chat, "sent while offline".into(), None)
        .await
        .unwrap();
    assert_eq!(
        state_of(&bob, bob_chat, queued).state,
        MessageState::Pending
    );
    drop(bob);
    drop(core_b);
    let core_b = Core::new(path(&dir_b), Box::new(rec_b.clone())).unwrap();
    let bob = core_b.account(bob_id).unwrap();
    bob.clone().start();
    wait("queued message survives restart", || {
        find(&alice, alice_chat, "sent while offline")
    })
    .await;
    wait("queued message delivered", || {
        (state_of(&bob, bob_chat, queued).state == MessageState::Delivered).then_some(())
    })
    .await;

    let carol = new_account(&core_b, node_b, "Carol").await;
    carol.clone().start();
    let (carol_chat, _) = carol.open_chat_with(&alice.info().account, "").unwrap();
    let s1 = carol
        .send_text(carol_chat, "stranger 1".into(), None)
        .await
        .unwrap();
    wait("stranger's first message delivered", || {
        (state_of(&carol, carol_chat, s1).state == MessageState::Delivered).then_some(())
    })
    .await;
    let s2 = carol
        .send_text(carol_chat, "stranger 2".into(), None)
        .await
        .unwrap();
    let failed = wait("stranger's second message fails", || {
        let m = state_of(&carol, carol_chat, s2);
        (m.state == MessageState::Failed).then_some(m)
    })
    .await;
    assert_eq!(failed.error.as_deref(), Some("sender-not-allowed"));
    let stranger_chat = wait("alice sees stranger chat", || {
        alice.chats(None).into_iter().find(|c| c.name == "Carol")
    })
    .await;
    assert!(!alice.chat(stranger_chat.id).unwrap().verified);
    let texts: Vec<String> = alice
        .messages(stranger_chat.id, None, 10)
        .into_iter()
        .map(|m| m.text)
        .collect();
    assert_eq!(texts, ["stranger 1"]);
    assert!(stranger_chat.is_request);

    let card = alice
        .send_vcard(
            alice_chat,
            alice.chat(stranger_chat.id).unwrap().contact_id.unwrap(),
        )
        .await
        .unwrap();
    let got_card = wait("bob receives vcard", || {
        find_by(&bob, bob_chat, |m| m.kind == MessageKind::Vcard)
    })
    .await;
    let v = got_card.vcard.unwrap();
    assert_eq!(
        (v.name.as_str(), v.account.as_str()),
        ("Carol", carol.info().account.as_str())
    );
    let carol_on_bob = bob.create_chat(v.account, v.name).unwrap();
    assert_eq!(bob.chat(carol_on_bob).unwrap().name, "Carol");
    wait("vcard delivered", || {
        (state_of(&alice, alice_chat, card).state == MessageState::Delivered).then_some(())
    })
    .await;

    alice.accept_chat(stranger_chat.id).unwrap();
    assert!(!alice.chat(stranger_chat.id).unwrap().is_request);
    tokio::time::sleep(Duration::from_secs(3)).await;
    carol.resend(vec![s2]).await.unwrap();
    wait("resent message delivered after accept", || {
        (state_of(&carol, carol_chat, s2).state == MessageState::Delivered).then_some(())
    })
    .await;
    wait("alice gets resent message", || {
        find(&alice, stranger_chat.id, "stranger 2")
    })
    .await;

    let carol_id = alice.chat(stranger_chat.id).unwrap().contact_id.unwrap();
    alice.block_contact(carol_id).unwrap();
    assert!(alice.chats(None).iter().all(|c| c.id != stranger_chat.id));
    assert_eq!(alice.blocked_contacts()[0].id, carol_id);
    tokio::time::sleep(Duration::from_secs(3)).await;
    let s3 = carol
        .send_text(carol_chat, "after block".into(), None)
        .await
        .unwrap();
    let s3_state = wait("message to blocker settles", || {
        let m = state_of(&carol, carol_chat, s3);
        matches!(m.state, MessageState::Delivered | MessageState::Failed).then_some(m)
    })
    .await;
    println!("after block: {:?} {:?}", s3_state.state, s3_state.error);
    tokio::time::sleep(Duration::from_secs(5)).await;
    assert!(alice.search_messages(None, "after block".into()).is_empty());
    alice.unblock_contact(carol_id).unwrap();
    assert!(alice.chats(None).iter().any(|c| c.id == stranger_chat.id));

    let alice_texts: Vec<String> = alice
        .messages(alice_chat, None, 100)
        .into_iter()
        .map(|m| m.text)
        .collect();
    for t in ["hello alice 你好", "sent while offline"] {
        assert_eq!(
            alice_texts.iter().filter(|x| *x == t).count(),
            1,
            "duplicates: {alice_texts:?}"
        );
    }
    assert!(rec_a.saw(|e| matches!(e, Event::IncomingMessage { .. })));
    assert!(rec_b.saw(|e| matches!(e, Event::MessageStateChanged { .. })));
    assert!(rec_a.saw(|e| *e == Event::ConnectivityChanged));

    for a in [&alice, &bob, &carol] {
        a.stop();
    }
    core_b.remove_account(carol.info().id).unwrap();
    assert_eq!(core_b.accounts().len(), 1);
}

fn systems(a: &Account, chat_id: u32) -> Vec<(Option<u32>, SystemMessage)> {
    a.messages(chat_id, None, 200)
        .into_iter()
        .filter_map(|m| Some((m.from_contact, m.system?)))
        .collect()
}

fn group_chat(a: &Account) -> Option<u32> {
    a.chats(None).into_iter().find(|c| c.is_group).map(|c| c.id)
}

#[tokio::test(flavor = "multi_thread")]
#[ignore]
async fn live_group_and_backup() {
    let dirs: Vec<tempfile::TempDir> = (0..4).map(|_| tempfile::tempdir().unwrap()).collect();
    let path = |i: usize| dirs[i].path().to_string_lossy().into_owned();
    let recs: Vec<Recorder> = (0..4).map(|_| Recorder::default()).collect();
    let cores: Vec<Arc<Core>> = (0..4)
        .map(|i| Core::new(path(i), Box::new(recs[i].clone())).unwrap())
        .collect();
    let nodes = cores[0].nodes().await.unwrap();
    println!("nodes: {nodes:?}");
    let node = |i: usize| nodes[i % nodes.len()].clone();
    let alice = new_account(&cores[0], &node(0), "Alice").await;
    let bob = new_account(&cores[1], &node(1), "Bob").await;
    let carol = new_account(&cores[2], &node(2), "Carol").await;
    for a in [&alice, &bob, &carol] {
        a.clone().start();
    }
    wait("all connected", || {
        [&alice, &bob, &carol]
            .iter()
            .all(|a| a.connectivity() == Connectivity::Connected)
            .then_some(())
    })
    .await;

    let bob_dm = bob.accept_invite(alice.invite_link()).await.unwrap();
    let alice_dm = wait("alice and bob are contacts", || {
        let c = alice.chats(None).into_iter().find(|c| !c.is_group)?;
        alice.chat(c.id).unwrap().verified.then_some(c.id)
    })
    .await;
    let bob_on_alice = alice.chat(alice_dm).unwrap().contact_id.unwrap();

    let files = tempfile::tempdir().unwrap();
    let avatar = write_file(&files, "team.png", &crate::local_tests::png(16, 16, 200));
    let ga = alice
        .create_group("Trio".into(), Some(avatar), vec![bob_on_alice])
        .await
        .unwrap();
    let gb = wait("bob gets the group", || group_chat(&bob)).await;
    let bob_group = bob.chat(gb).unwrap();
    assert!(bob_group.can_send && bob_group.name == "Trio" && bob_group.member_count == 2);
    assert!(bob_group.avatar_path.is_some() && !bob_group.is_request);
    assert_eq!(
        systems(&bob, gb)[0].1,
        SystemMessage::MemberAdded {
            contact_id: None,
            name: "Bob".into()
        }
    );

    let link = alice.group_invite_link(ga).unwrap();
    println!("group link: {link}");
    let gc = carol.accept_invite(link).await.unwrap();
    assert!(!carol.chat(gc).unwrap().can_send);
    wait("carol joins", || {
        let c = carol.chat(gc).unwrap();
        (c.can_send && c.member_count == 3).then_some(())
    })
    .await;
    wait("bob sees carol", || {
        (bob.chat(gb).unwrap().member_count == 3).then_some(())
    })
    .await;
    let carol_on_bob = bob
        .group_info(gb)
        .unwrap()
        .members
        .into_iter()
        .find(|m| m.name == "Carol")
        .unwrap()
        .contact_id;
    assert!(systems(&bob, gb).iter().any(|(_, s)| matches!(s,
        SystemMessage::MemberAdded { contact_id, name } if *contact_id == carol_on_bob && name == "Carol")));

    let hi = carol
        .send_text(gc, "hi from carol".into(), None)
        .await
        .unwrap();
    let on_bob = wait("bob gets carol's group message", || {
        find(&bob, gb, "hi from carol")
    })
    .await;
    wait("alice gets carol's group message", || {
        find(&alice, ga, "hi from carol")
    })
    .await;
    assert_eq!(on_bob.sender_name, "Carol");
    wait("carol's message delivered to both", || {
        (state_of(&carol, gc, hi).state == MessageState::Delivered).then_some(())
    })
    .await;
    let d = carol.message_deliveries(hi);
    assert_eq!(d.len(), 2);
    assert!(d.iter().all(|d| d.state == MessageState::Delivered));
    assert!(recs[1].saw(|e| matches!(e,
        Event::Notification { notification } if notification.is_group && notification.chat_name == "Trio"
            && notification.sender_name == "Carol" && notification.text == "hi from carol")));

    bob.mark_read(gb);
    wait("carol sees read", || {
        (state_of(&carol, gc, hi).state == MessageState::Read).then_some(())
    })
    .await;

    let carol_dm = bob.chat_for_contact(carol_on_bob.unwrap()).unwrap();
    bob.send_text(carol_dm, "psst".into(), Some(on_bob.id))
        .await
        .unwrap();
    let private = wait("carol gets the private reply", || {
        let c = carol.chats(None).into_iter().find(|c| c.name == "Bob")?;
        find(&carol, c.id, "psst")
    })
    .await;
    assert_eq!(private.reply_to, Some(hi));
    assert!(recs[2].saw(|e| matches!(e,
        Event::Notification { notification } if notification.text == "psst" && notification.mention && !notification.is_group)));

    alice.set_group_name(ga, "Trio 2".into()).await.unwrap();
    for (a, g) in [(&bob, gb), (&carol, gc)] {
        wait("rename arrives", || {
            (a.chat(g).unwrap().name == "Trio 2").then_some(())
        })
        .await;
        assert!(systems(a, g).iter().any(|(_, s)| *s
            == SystemMessage::GroupNameChanged {
                name: "Trio 2".into(),
                old_name: "Trio".into()
            }));
    }

    carol.stop();
    let bg = alice
        .send_text(ga, "background".into(), None)
        .await
        .unwrap();
    let fetched = wait("carol fetches in background", || {
        let carol = carol.clone();
        let r = std::thread::spawn(move || block_on(carol.fetch_once()))
            .join()
            .unwrap();
        r.ok().filter(|r| r.new_messages > 0)
    })
    .await;
    let n = &fetched.notifications[0];
    assert_eq!(
        (
            n.chat_name.as_str(),
            n.sender_name.as_str(),
            n.text.as_str(),
            n.is_group
        ),
        ("Trio 2", "Alice", "background", true)
    );
    carol.clone().start();
    wait("alice's message delivered", || {
        (state_of(&alice, ga, bg).state == MessageState::Delivered).then_some(())
    })
    .await;

    alice.remove_group_member(ga, bob_on_alice).await.unwrap();
    wait("bob is removed", || {
        (!bob.chat(gb).unwrap().can_send).then_some(())
    })
    .await;
    assert!(systems(&bob, gb).iter().any(|(_, s)| *s
        == SystemMessage::MemberRemoved {
            contact_id: None,
            name: "Bob".into()
        }));
    wait("carol sees bob removed", || {
        (carol.chat(gc).unwrap().member_count == 2).then_some(())
    })
    .await;

    carol.leave_group(gc).await.unwrap();
    assert!(!carol.chat(gc).unwrap().can_send);
    wait("alice sees carol leave", || {
        (alice.chat(ga).unwrap().member_count == 1).then_some(())
    })
    .await;
    assert!(systems(&alice, ga)
        .iter()
        .any(|(from, s)| from.is_some() && *s == SystemMessage::MemberLeft));

    let backup = alice
        .clone()
        .export_backup(files.path().to_string_lossy().into(), None)
        .await
        .unwrap();
    alice.stop();
    assert_eq!(
        cores[3].backup_protection(backup.clone()).unwrap(),
        BackupProtection::RecoveryPhrase
    );
    let id = cores[3]
        .clone()
        .import_backup(backup, alice.mnemonic())
        .await
        .unwrap();
    let restored = cores[3].account(id).unwrap();
    assert_eq!(restored.info().account, alice.info().account);
    let restored_dm = restored
        .chats(None)
        .into_iter()
        .find(|c| c.name == "Bob")
        .unwrap()
        .id;
    assert!(find(&restored, ga, "hi from carol").is_some());
    restored.clone().start();
    bob.send_text(bob_dm, "after restore".into(), None)
        .await
        .unwrap();
    wait("restored account receives", || {
        find(&restored, restored_dm, "after restore")
    })
    .await;
}

fn block_on<T>(f: impl std::future::Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(f)
}

#[tokio::test(flavor = "multi_thread")]
#[ignore]
async fn live_delete_contact_syncs_deny() {
    let (dir_a, dir_b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let path = |d: &tempfile::TempDir| d.path().to_string_lossy().into_owned();
    let core_a = Core::new(path(&dir_a), Box::new(Recorder::default())).unwrap();
    let core_b = Core::new(path(&dir_b), Box::new(Recorder::default())).unwrap();
    let nodes = core_a.nodes().await.unwrap();
    let alice = new_account(&core_a, &nodes[0], "Alice").await;
    let bob = new_account(&core_b, &nodes[1 % nodes.len()], "Bob").await;
    let chat = alice.create_chat(bob.info().account, "Bob".into()).unwrap();
    alice.send_text(chat, "hello".into(), None).await.unwrap();
    alice.flush().await.unwrap();
    let bob_id = alice.contacts()[0].id;
    alice.delete_contact(bob_id).unwrap();
    alice.flush().await.unwrap();
    let synced: bool = alice
        .db()
        .query_row(
            "SELECT allow_synced FROM contacts WHERE id=?",
            [bob_id],
            |r| r.get(0),
        )
        .unwrap();
    assert!(synced);
    bob.clone().start();
    let bob_chat = wait("bob gets hello", || bob.chats(None).first().map(|c| c.id)).await;
    wait("hello arrives", || find(&bob, bob_chat, "hello")).await;
    let reply = bob.send_text(bob_chat, "reply".into(), None).await.unwrap();
    let m = wait("reply settles", || {
        let m = state_of(&bob, bob_chat, reply);
        matches!(m.state, MessageState::Failed | MessageState::Delivered).then_some(m)
    })
    .await;
    assert_eq!(m.state, MessageState::Delivered);
    bob.stop();
}
