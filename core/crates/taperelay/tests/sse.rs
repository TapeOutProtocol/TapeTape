use taperelay::{SseEvent, SseParser};

fn ev(id: Option<&str>, event: &str, data: &str) -> SseEvent {
    SseEvent {
        id: id.map(str::to_string),
        event: event.to_string(),
        data: data.to_string(),
    }
}

#[test]
fn parses_events_across_chunks() {
    let mut p = SseParser::new();
    assert_eq!(
        p.push(b"id: 5\n\n: ping\n\nevent: notice\nid: 6\nda")
            .unwrap(),
        vec![ev(Some("5"), "message", "")]
    );
    assert_eq!(
        p.push(b"ta: {\"a\":1}\ndata:x\n\n").unwrap(),
        vec![ev(Some("6"), "notice", "{\"a\":1}\nx")]
    );
    assert_eq!(
        p.push(b"data\n\ndata:  two\nretry: 5\nnoise\n\n").unwrap(),
        vec![ev(None, "message", " two")]
    );
    assert_eq!(
        p.push(b"data:\ndata: a\n\n").unwrap(),
        vec![ev(None, "message", "a")]
    );
    assert_eq!(p.push(b"event: x\n\n").unwrap(), vec![]);
    assert_eq!(
        p.push("data: 你好\n\nid:".as_bytes()).unwrap(),
        vec![ev(None, "message", "你好")]
    );
    assert_eq!(p.push(b"\n\n").unwrap(), vec![ev(Some(""), "message", "")]);
}

#[test]
fn seq_of_event_id() {
    assert_eq!(ev(Some("42"), "message", "").seq(), Some(42));
    assert_eq!(
        ev(Some("999999999999999"), "message", "").seq(),
        Some(999_999_999_999_999)
    );
    assert_eq!(ev(Some("1000000000000000"), "message", "").seq(), None);
    assert_eq!(ev(Some("-1"), "message", "").seq(), None);
    assert_eq!(ev(Some(""), "message", "").seq(), None);
    assert_eq!(ev(None, "message", "").seq(), None);
}

#[test]
fn rejects_oversized_block() {
    let mut p = SseParser::new();
    assert!(p.push(&vec![b'a'; 65536]).is_ok());
    assert!(p.push(b"a").is_err());
}
