use std::sync::LazyLock;

use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use taperelay::*;

static V: LazyLock<Value> =
    LazyLock::new(|| serde_json::from_str(include_str!("vectors.json")).unwrap());

const CIRCUIT: &str = "1.2.0.tape";

fn cases(name: &str) -> &'static Vec<Value> {
    V[name].as_array().unwrap()
}

fn s(v: &Value) -> &str {
    v.as_str().unwrap()
}

fn bytes(v: &Value) -> Vec<u8> {
    hex::decode(s(v)).unwrap()
}

fn arr<const N: usize>(v: &Value) -> [u8; N] {
    bytes(v).try_into().unwrap()
}

fn num(v: &Value) -> u64 {
    v.as_u64().unwrap()
}

fn pat(seed: u64, n: usize) -> Vec<u8> {
    (0..n as u64)
        .map(|i| ((seed + i * 13 + ((i * i) >> 3)) & 255) as u8)
        .collect()
}

fn big(seed: u64, n: usize) -> Vec<u8> {
    (0..n as u64)
        .map(|i| ((seed + i * 7 + i / 251) & 255) as u8)
        .collect()
}

fn seq(seed: u64) -> impl FnMut(&mut [u8]) {
    let mut c = 0;
    move |b: &mut [u8]| {
        b.copy_from_slice(&pat(seed + c, b.len()));
        c += 1;
    }
}

fn secret(i: usize) -> [u8; 32] {
    pat(160 + i as u64, 32).try_into().unwrap()
}

fn keys(i: usize) -> EncKeys {
    EncKeys::from_secret(&secret(i))
}

fn indices(v: &Value) -> Vec<usize> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|x| num(x) as usize)
        .collect()
}

fn outcome(r: anyhow::Result<Vec<u8>>, plain: &[u8]) -> String {
    match r {
        Ok(b) if b == plain => "ok".to_string(),
        Ok(_) => "wrong-plaintext".to_string(),
        Err(e) => e.to_string(),
    }
}

fn chunks_of(plan: &EncryptedFile) -> Vec<Vec<u8>> {
    plan.chunks.iter().map(|c| c.bytes.clone()).collect()
}

#[test]
fn circuits() {
    for c in cases("circuits") {
        assert_eq!(
            is_circuit(s(&c["input"])),
            c["ok"].as_bool().unwrap(),
            "{}",
            c["input"]
        );
    }
}

#[test]
fn files_match_js() {
    for c in cases("files") {
        let name = s(&c["name"]);
        let plain = big(num(&c["plainSeed"]), num(&c["size"]) as usize);
        let to: Vec<[u8; 32]> = indices(&c["to"])
            .iter()
            .map(|i| keys(*i).public())
            .collect();
        let mut random = seq(num(&c["randomSeed"]));
        let plan = plan_encrypted_file(&plain, CIRCUIT, &keys(0).public(), &to, Some(&mut random))
            .unwrap();
        assert_eq!(hex::encode(plan.file_key), s(&c["fileKey"]), "{name}");
        let hashes: Vec<String> = plan.chunks.iter().map(|o| hex::encode(o.sha256)).collect();
        let want: Vec<String> = serde_json::from_value(c["chunkHashes"].clone()).unwrap();
        assert_eq!(hashes, want, "{name}");
        assert_eq!(hex::encode(&plan.index.bytes), s(&c["index"]), "{name}");
        assert_eq!(hex::encode(plan.index.sha256), s(&c["indexSha"]), "{name}");
        assert_eq!(plan.manifest_json, s(&c["manifestJson"]), "{name}");
        assert_eq!(
            hex::encode(&plan.envelope.bytes),
            s(&c["envelope"]),
            "{name}"
        );
        assert_eq!(
            hex::encode(plan.envelope.sha256),
            s(&c["envelopeSha"]),
            "{name}"
        );
        if let Some(js) = c["chunks"].as_array() {
            let js: Vec<Vec<u8>> = js.iter().map(bytes).collect();
            assert_eq!(chunks_of(&plan), js, "{name}");
        }
        for (i, want) in c["openedBy"].as_object().unwrap() {
            let r = open_encrypted_file(
                &plan.envelope.bytes,
                &plan.index.bytes,
                &chunks_of(&plan),
                &keys(i.parse().unwrap()),
            );
            assert_eq!(outcome(r, &plain), s(want), "{name} key {i}");
        }
        let opened = open_envelope(&plan.envelope.bytes, &keys(0)).unwrap();
        assert_eq!(opened.file_key, plan.file_key);
        assert_eq!(opened.manifest.size, plain.len() as u64);
        assert_eq!(opened.manifest.from, CIRCUIT);
        assert_eq!(opened.manifest.index, plan.index.sha256);
        assert_eq!(opened.manifest.sha256, None);
        assert_eq!(
            opened.chunk_hashes(&plan.index.bytes).unwrap(),
            plan.chunks.iter().map(|o| o.sha256).collect::<Vec<_>>()
        );
    }
}

#[test]
fn file_errors() {
    for c in cases("fileErrors") {
        let to: Vec<[u8; 32]> = c["to"].as_array().unwrap().iter().map(arr).collect();
        let r = plan_encrypted_file(
            &vec![0; num(&c["size"]) as usize],
            s(&c["from"]),
            &arr(&c["own"]),
            &to,
            None,
        );
        let got = r
            .map(|_| "ok".to_string())
            .unwrap_or_else(|e| e.to_string());
        assert_eq!(got, s(&c["code"]), "{}", c["name"]);
    }
}

#[test]
fn crafted_files() {
    for c in cases("crafted") {
        let name = s(&c["name"]);
        let chunks: Vec<Vec<u8>> = c["chunks"].as_array().unwrap().iter().map(bytes).collect();
        let r = open_encrypted_file(
            &bytes(&c["envelope"]),
            &bytes(&c["index"]),
            &chunks,
            &keys(0),
        );
        match c["plain"].as_str() {
            Some(p) => assert_eq!(hex::encode(r.unwrap()), p, "{name}"),
            None => assert_eq!(r.unwrap_err().to_string(), s(&c["result"]), "{name}"),
        }
    }
}

#[test]
fn hash_checks_and_limits() {
    let plain = big(5, ENCRYPTED_CHUNK + 10);
    let plan = plan_encrypted_file(&plain, CIRCUIT, &keys(0).public(), &[], None).unwrap();
    let env = &plan.envelope.bytes;
    let mut chunks = chunks_of(&plan);
    assert_eq!(
        open_encrypted_file(env, &plan.index.bytes, &chunks, &keys(0)).unwrap(),
        plain
    );
    let mut index = plan.index.bytes.clone();
    index[30] ^= 1;
    assert_eq!(
        open_encrypted_file(env, &index, &chunks, &keys(0))
            .unwrap_err()
            .to_string(),
        "hash-mismatch"
    );
    let short = &chunks[..1];
    assert_eq!(
        open_encrypted_file(env, &plan.index.bytes, short, &keys(0))
            .unwrap_err()
            .to_string(),
        "bad-length"
    );
    chunks[1][40] ^= 1;
    assert_eq!(
        open_encrypted_file(env, &plan.index.bytes, &chunks, &keys(0))
            .unwrap_err()
            .to_string(),
        "hash-mismatch"
    );
    assert_eq!(
        open_envelope(&vec![0; OBJECT_MAX + 1], &keys(0))
            .err()
            .unwrap()
            .to_string(),
        "too-large"
    );
    assert_eq!(plan.chunks[0].bytes.len(), OBJECT_MAX);
    assert_eq!(plan.chunks[1].bytes.len(), 4096 + 40);
}

#[test]
fn extra_envelopes_share_objects() {
    let plain = b"multi envelope payload".to_vec();
    let others: Vec<[u8; 32]> = (1..18).map(|i| keys(i).public()).collect();
    let (first, rest) = others.split_at(MAX_RECIPIENTS - 1);
    let plan = plan_encrypted_file(&plain, CIRCUIT, &keys(0).public(), first, None).unwrap();
    let extra = plan.envelope_for(&keys(0).public(), rest, None).unwrap();
    assert_ne!(extra.sha256, plan.envelope.sha256);
    let chunks = chunks_of(&plan);
    for i in 0..18 {
        let open = |env: &FileObject| {
            open_encrypted_file(&env.bytes, &plan.index.bytes, &chunks, &keys(i))
                .map_err(|e| e.to_string())
        };
        let (a, b) = (open(&plan.envelope), open(&extra));
        match i {
            0 => assert!(a.is_ok() && b.is_ok()),
            1..=15 => assert!(a.is_ok() && b.as_ref().unwrap_err() == "no-slot"),
            _ => assert!(a.unwrap_err() == "no-slot" && b.is_ok()),
        }
    }
}

#[test]
fn remote_upload_batch() {
    let v = &V["remoteUpload"];
    let requests: Vec<UploadRequest> = v["requests"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| UploadRequest {
            account: s(&v["account"]).to_string(),
            server: s(&r["server"]).to_string(),
            object: arr(&r["object"]),
            timestamp: num(&r["timestamp"]),
            nonce: arr(&r["nonce"]),
        })
        .collect();
    assert_eq!(remote_batch_json(&requests), s(&v["batchJson"]));
    for (req, js) in requests.iter().zip(v["headers"].as_array().unwrap()) {
        let js: Vec<(String, String)> = serde_json::from_value(js.clone()).unwrap();
        let get = |k: &str| js.iter().find(|(n, _)| n == k).map(|(_, v)| v.as_str());
        let headers = req.headers(get("TR-Signature").unwrap(), get("TR-Delegation"));
        assert_eq!(headers, js);
    }
}

#[test]
fn backend_auth() {
    let v = &V["backendAuth"];
    let id = Identity::new(&arr(&v["privateKey"]), s(&v["host"])).unwrap();
    let headers = signed_headers(
        &id,
        "tapetape-backend",
        "POST",
        "/api/relay/sign",
        "",
        s(&v["body"]).as_bytes(),
        num(&v["timestamp"]),
        arr(&v["nonce"]),
    );
    let js: Vec<(String, String)> = serde_json::from_value(v["headers"].clone()).unwrap();
    assert_eq!(headers, js);
}

#[test]
#[ignore]
fn emit_file_for_js() {
    let plain: Vec<u8> = (0..2 * ENCRYPTED_CHUNK + 777)
        .map(|_| rand::random::<u8>())
        .collect();
    let others: Vec<[u8; 32]> = (1..18).map(|i| keys(i).public()).collect();
    let (first, rest) = others.split_at(MAX_RECIPIENTS - 1);
    let own = keys(0).public();
    let plan = plan_encrypted_file(&plain, CIRCUIT, &own, first, None).unwrap();
    let extra = plan.envelope_for(&own, rest, None).unwrap();
    let mut objects = serde_json::Map::new();
    for o in std::iter::once(&plan.index)
        .chain(&plan.chunks)
        .chain([&plan.envelope, &extra])
    {
        objects.insert(hex::encode(o.sha256), hex::encode(&o.bytes).into());
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let servers = [
        "relay1.taperelay.com:4246",
        "relay2.taperelay.com:4246",
        "relay3.taperelay.com:4246",
    ];
    let requests: Vec<UploadRequest> = servers
        .iter()
        .map(|server| UploadRequest {
            account: CIRCUIT.to_string(),
            server: server.to_string(),
            object: plan.envelope.sha256,
            timestamp: now,
            nonce: rand::random(),
        })
        .collect();
    let batch = remote_batch_json(&requests);
    let me = Identity::generate("relay1.taperelay.com:4246").unwrap();
    let auth = signed_headers(
        &me,
        "tapetape-backend",
        "POST",
        "/api/relay/sign",
        "",
        batch.as_bytes(),
        now,
        rand::random(),
    );
    let out = json!({
        "circuit": CIRCUIT,
        "secrets": (0..18).map(|i| hex::encode(secret(i))).collect::<Vec<_>>(),
        "envelopes": [hex::encode(plan.envelope.sha256), hex::encode(extra.sha256)],
        "objects": objects,
        "size": plain.len(),
        "plainSha256": hex::encode(Sha256::digest(&plain)),
        "servers": servers,
        "now": now,
        "batch": batch,
        "auth": auth,
    });
    println!("FILE-FOR-JS {out}");
}
