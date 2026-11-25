use std::time::Duration;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, info};
use reqwest::{Client, Method, Response};
use serde_json::{json, Value};
use taperelay::{Identity, SseEvent, SseParser};

pub(crate) const PORT: u16 = 4246;
const TIMEOUT: Duration = Duration::from_secs(60);
pub(crate) const SSE_IDLE: Duration = Duration::from_secs(120);

#[cfg(test)]
pub(crate) fn http_client() -> Client {
    Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .build()
        .expect("http client")
}

fn now_secs() -> u64 {
    (crate::protocol::now_ms() / 1000) as u64
}

pub(crate) fn percent_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

pub(crate) async fn fetch_key(http: &Client, account: &str) -> Result<Option<[u8; 32]>> {
    let server = taperelay::node_of(account).context("bad account")?;
    let url = format!("{}/v1/keys/{account}", taperelay::server_url(&server));
    let res = http.get(url).timeout(TIMEOUT).send().await?;
    match res.status().as_u16() {
        404 => Ok(None),
        200 => Ok(Some(taperelay::verify_key_record(
            account,
            &res.json::<Value>().await?,
        )?)),
        s => bail!("fetching key of {account}: HTTP {s}"),
    }
}

pub(crate) struct Submitted {
    pub status: u16,
    pub error: Option<String>,
}

pub(crate) struct Relay {
    net: std::sync::Arc<crate::net::Net>,
    id: Identity,
}

impl Relay {
    pub fn new(net: std::sync::Arc<crate::net::Net>, id: Identity) -> Relay {
        Relay { net, id }
    }

    fn request(
        &self,
        method: &str,
        path: &str,
        query: &str,
        body: &str,
    ) -> reqwest::RequestBuilder {
        let target = if query.is_empty() {
            path.to_string()
        } else {
            format!("{path}?{query}")
        };
        let url = format!("{}{target}", taperelay::server_url(self.id.server()));
        let mut req = self
            .net
            .client()
            .request(Method::from_bytes(method.as_bytes()).unwrap(), url);
        for (k, v) in taperelay::signed_headers(
            &self.id,
            self.id.server(),
            method,
            path,
            query,
            body.as_bytes(),
            now_secs(),
            rand::random(),
        ) {
            req = req.header(k, v);
        }
        if !body.is_empty() {
            req = req
                .header("Content-Type", "application/json")
                .body(body.to_string());
        }
        req
    }

    async fn call(
        &self,
        method: &str,
        path: &str,
        query: &str,
        body: &str,
    ) -> Result<(u16, String)> {
        let res = self
            .request(method, path, query, body)
            .timeout(TIMEOUT)
            .send()
            .await
            .with_context(|| format!("{method} {path}"))?;
        let status = res.status().as_u16();
        let text = res.text().await?;
        debug!("{method} {path} -> {status}");
        Ok((status, text))
    }

    fn inbox_path(&self) -> String {
        format!("/v1/inbox/{}", self.id.account())
    }

    pub async fn publish_key(&self, public: &[u8; 32]) -> Result<()> {
        let path = format!("/v1/keys/{}", self.id.account());
        let body = taperelay::publish_key_json(&self.id, public, now_secs());
        let (status, text) = self.call("PUT", &path, "", &body).await?;
        ensure!(status == 200, "Publishing key failed: HTTP {status} {text}");
        info!("published encryption key for {}", self.id.account());
        Ok(())
    }

    pub async fn senders(
        &self,
        allow: &[String],
        deny: &[String],
        block: &[String],
        unblock: &[String],
    ) -> Result<()> {
        let path = format!("{}/senders", self.inbox_path());
        let body = taperelay::senders_json(allow, deny, block, unblock);
        let (status, text) = self.call("POST", &path, "", &body).await?;
        ensure!(
            status == 200,
            "Updating senders failed: HTTP {status} {text}"
        );
        info!(
            "senders updated: {} allowed, {} denied, {} blocked, {} unblocked",
            allow.len(),
            deny.len(),
            block.len(),
            unblock.len()
        );
        Ok(())
    }

    pub async fn submit(&self, notice_json: &str) -> Result<Submitted> {
        let (status, text) = self.call("POST", "/v1/outbox", "", notice_json).await?;
        let error = serde_json::from_str::<Value>(&text)
            .ok()
            .and_then(|v| v["error"].as_str().map(str::to_string));
        Ok(Submitted { status, error })
    }

    pub async fn outbox_status(&self, notice: &str) -> Result<Option<(String, Option<String>)>> {
        let url = format!(
            "{}/v1/outbox/{notice}",
            taperelay::server_url(self.id.server())
        );
        let res = self.net.client().get(url).timeout(TIMEOUT).send().await?;
        match res.status().as_u16() {
            404 => Ok(None),
            200 => {
                let v: Value = res.json().await?;
                Ok(Some((
                    v["status"].as_str().unwrap_or_default().to_string(),
                    v["error"].as_str().map(str::to_string),
                )))
            }
            s => bail!("outbox status HTTP {s}"),
        }
    }

    pub async fn inbox_page(&self, cursor: Option<&str>) -> Result<Value> {
        let query = match cursor {
            Some(c) => format!("cursor={}&limit=100", percent_encode(c)),
            None => "limit=100".to_string(),
        };
        let (status, text) = self.call("GET", &self.inbox_path(), &query, "").await?;
        ensure!(status == 200, "Inbox HTTP {status} {text}");
        Ok(serde_json::from_str(&text)?)
    }

    pub async fn mark_read(&self, ids: &[String]) -> Result<()> {
        let path = format!("{}/read", self.inbox_path());
        for chunk in ids.chunks(100) {
            let body = json!({ "ids": chunk }).to_string();
            let (status, text) = self.call("POST", &path, "", &body).await?;
            ensure!(
                status == 204 || status == 200,
                "Mark read HTTP {status} {text}"
            );
        }
        debug!("marked {} notice(s) read", ids.len());
        Ok(())
    }

    pub async fn subscribe(&self, last_seq: Option<u64>) -> Result<Events> {
        let path = format!("{}/events", self.inbox_path());
        let mut req = self.request("GET", &path, "", "");
        if let Some(seq) = last_seq {
            req = req.header("Last-Event-ID", seq.to_string());
        }
        let res = tokio::time::timeout(TIMEOUT, req.send())
            .await
            .context("subscribe timed out")??;
        ensure!(res.status() == 200, "Subscribe HTTP {}", res.status());
        Ok(Events {
            res,
            parser: SseParser::new(),
        })
    }
}

pub(crate) struct Events {
    res: Response,
    parser: SseParser,
}

impl Events {
    pub async fn next(&mut self) -> Result<Vec<SseEvent>> {
        let chunk = tokio::time::timeout(SSE_IDLE, self.res.chunk())
            .await
            .context("event stream idle")??
            .context("event stream closed")?;
        self.parser.push(&chunk)
    }
}
