use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::{Client, Method, Response};
use serde_json::{json, Value};
use taperelay::*;
use tokio::time::{timeout, timeout_at, Instant};

const HOST: &str = "relay1.taperelay.com:4246";

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn shown(path: &str, query: &str) -> String {
    match query {
        "" => path.to_string(),
        q => format!("{path}?{q}"),
    }
}

async fn send(
    client: &Client,
    id: Option<&Identity>,
    method: &str,
    path: &str,
    query: &str,
    body: &str,
) -> Response {
    let url = format!("{}{}", server_url(HOST), shown(path, query));
    let mut req = client.request(Method::from_bytes(method.as_bytes()).unwrap(), url);
    if let Some(id) = id {
        for (k, v) in signed_headers(
            id,
            id.server(),
            method,
            path,
            query,
            body.as_bytes(),
            now(),
            rand::random(),
        ) {
            req = req.header(k, v);
        }
    }
    if !body.is_empty() {
        req = req
            .header("Content-Type", "application/json")
            .body(body.to_string());
    }
    req.send().await.unwrap()
}

async fn call(
    client: &Client,
    id: Option<&Identity>,
    method: &str,
    path: &str,
    query: &str,
    body: &str,
) -> (u16, String) {
    let res = send(client, id, method, path, query, body).await;
    let status = res.status().as_u16();
    let content_type = res
        .headers()
        .get("content-type")
        .map(|v| v.to_str().unwrap().to_string());
    let text = res.text().await.unwrap();
    println!(
        "{method} {} -> {status} {content_type:?} {text}",
        shown(path, query)
    );
    (status, text)
}

fn parse(text: &str) -> Value {
    serde_json::from_str(text).unwrap()
}

#[tokio::test]
#[ignore]
async fn live_relay1() {
    let client = Client::new();
    let a = Identity::generate(HOST).unwrap();
    let b = Identity::generate(HOST).unwrap();
    let (ka, kb) = (EncKeys::generate(), EncKeys::generate());
    println!("A = {}\nB = {}", a.account(), b.account());

    for (id, keys) in [(&a, &ka), (&b, &kb)] {
        let path = format!("/v1/keys/{}", id.account());
        let (status, _) = call(
            &client,
            Some(id),
            "PUT",
            &path,
            "",
            &publish_key_json(id, &keys.public(), now()),
        )
        .await;
        assert_eq!(status, 200);
    }
    let mut fetched = Vec::new();
    for id in [&a, &b] {
        let (status, text) = call(
            &client,
            None,
            "GET",
            &format!("/v1/keys/{}", id.account()),
            "",
            "",
        )
        .await;
        assert_eq!(status, 200);
        fetched.push(verify_key_record(id.account(), &parse(&text)).unwrap());
    }
    assert_eq!(fetched, [ka.public(), kb.public()]);
    let b_key = fetched[1];

    for (id, other) in [(&a, &b), (&b, &a)] {
        let body = senders_json(&[other.account().to_string()], &[], &[], &[]);
        let path = format!("/v1/inbox/{}/senders", id.account());
        let (status, _) = call(&client, Some(id), "POST", &path, "", &body).await;
        assert_eq!(status, 200);
    }

    let mut sent = Vec::new();
    for text in ["hello from A #1", "第二条 #2"] {
        let notice =
            make_self_notice(&a, b.account(), text.as_bytes(), &b_key, &ka, now()).unwrap();
        let (status, _) = call(&client, Some(&a), "POST", "/v1/outbox", "", &notice.json).await;
        assert_eq!(status, 202);
        sent.push((notice.id, text.to_string()));
    }
    call(
        &client,
        None,
        "GET",
        &format!("/v1/outbox/{}", sent[0].0),
        "",
        "",
    )
    .await;

    let inbox = format!("/v1/inbox/{}", b.account());
    let mut items = Vec::new();
    for _ in 0..5 {
        let (status, text) = call(&client, Some(&b), "GET", &inbox, "limit=100", "").await;
        assert_eq!(status, 200);
        items = parse(&text)["items"].as_array().unwrap().clone();
        if items.len() >= 2 {
            break;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    let (_, text) = call(&client, Some(&b), "GET", &inbox, "limit=1", "").await;
    if let Some(next) = parse(&text)["next"].as_str() {
        call(
            &client,
            Some(&b),
            "GET",
            &inbox,
            &format!("limit=1&cursor={next}"),
            "",
        )
        .await;
    }

    let mut received: Vec<(String, String)> = items
        .iter()
        .map(|it| {
            let o = open_self_notice(it, &kb, b.account()).unwrap();
            assert_eq!(o.from, a.account());
            (o.id, String::from_utf8(o.message).unwrap())
        })
        .collect();
    received.sort();
    sent.sort();
    assert_eq!(received, sent);

    let ids: Vec<&str> = items.iter().map(|it| it["id"].as_str().unwrap()).collect();
    let (status, _) = call(
        &client,
        Some(&b),
        "POST",
        &format!("{inbox}/read"),
        "",
        &json!({ "ids": ids }).to_string(),
    )
    .await;
    assert_eq!(status, 204);
    call(
        &client,
        None,
        "GET",
        &format!("/v1/outbox/{}", sent[0].0),
        "",
        "",
    )
    .await;

    let events = format!("{inbox}/events");
    let mut res = send(&client, Some(&b), "GET", &events, "", "").await;
    println!(
        "GET {events} -> {} {:?}",
        res.status(),
        res.headers().get("content-type")
    );
    assert_eq!(res.status(), 200);
    let mut parser = SseParser::new();
    let mut got = Vec::new();
    if let Ok(chunk) = timeout(Duration::from_secs(5), res.chunk()).await {
        let chunk = chunk.unwrap().expect("sse closed");
        println!("SSE chunk {:?}", String::from_utf8_lossy(&chunk));
        got.extend(parser.push(&chunk).unwrap());
    }

    let third =
        make_self_notice(&a, b.account(), "pushed #3".as_bytes(), &b_key, &ka, now()).unwrap();
    let (status, _) = call(&client, Some(&a), "POST", "/v1/outbox", "", &third.json).await;
    assert_eq!(status, 202);
    let deadline = Instant::now() + Duration::from_secs(20);
    let pushed = loop {
        if let Some(ev) = got.iter().find(|ev| !ev.data.is_empty()) {
            break ev.clone();
        }
        let chunk = timeout_at(deadline, res.chunk())
            .await
            .expect("no sse event")
            .unwrap()
            .expect("sse closed");
        println!("SSE chunk {:?}", String::from_utf8_lossy(&chunk));
        got.extend(parser.push(&chunk).unwrap());
    };
    println!("SSE events {got:?} seq={:?}", pushed.seq());
    let o = open_self_notice(&parse(&pushed.data), &kb, b.account()).unwrap();
    assert_eq!(
        (o.id.as_str(), o.message.as_slice()),
        (third.id.as_str(), "pushed #3".as_bytes())
    );
    drop(res);
    let (status, _) = call(
        &client,
        Some(&b),
        "POST",
        &format!("{inbox}/read"),
        "",
        &json!({ "ids": [third.id] }).to_string(),
    )
    .await;
    assert_eq!(status, 204);

    let stranger = Identity::generate(HOST).unwrap();
    let stranger_keys = EncKeys::generate();
    let mut stranger_ids = Vec::new();
    for text in ["stranger #1", "stranger #2"] {
        let notice = make_self_notice(
            &stranger,
            b.account(),
            text.as_bytes(),
            &b_key,
            &stranger_keys,
            now(),
        )
        .unwrap();
        let (status, _) = call(
            &client,
            Some(&stranger),
            "POST",
            "/v1/outbox",
            "",
            &notice.json,
        )
        .await;
        assert_eq!(status, 202);
        stranger_ids.push(notice.id);
    }
    for id in &stranger_ids {
        call(&client, None, "GET", &format!("/v1/outbox/{id}"), "", "").await;
    }
    call(
        &client,
        Some(&b),
        "GET",
        &format!("{inbox}/senders"),
        "state=pending&limit=50",
        "",
    )
    .await;
    let (_, text) = call(&client, Some(&b), "GET", &inbox, "limit=100", "").await;
    let page = parse(&text);
    let ids: Vec<&str> = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|it| it["id"].as_str().unwrap())
        .collect();
    assert_eq!(ids, [stranger_ids[0].as_str()]);
    let (status, _) = call(
        &client,
        Some(&b),
        "POST",
        &format!("{inbox}/read"),
        "",
        &json!({ "ids": ids }).to_string(),
    )
    .await;
    assert_eq!(status, 204);

    call(
        &client,
        None,
        "GET",
        &format!("/v1/keys/{}", stranger.account()),
        "",
        "",
    )
    .await;
    let mut tampered = parse(&third.json);
    tampered["notice"]["createdAt"] = json!(1);
    call(
        &client,
        Some(&a),
        "POST",
        "/v1/outbox",
        "",
        &tampered.to_string(),
    )
    .await;
    call(&client, Some(&a), "GET", &inbox, "limit=100", "").await;
}
