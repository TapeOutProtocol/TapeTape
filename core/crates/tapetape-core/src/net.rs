use std::future::Future;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use anyhow::{bail, ensure, Context, Result};
use log::{debug, info, warn};
use reqwest::{Client, Url};
use serde::{Deserialize, Serialize};
use shadowsocks::config::{ServerAddr, ServerConfig, ServerType};
use shadowsocks::context::Context as SsContext;
use shadowsocks::relay::socks5::Address;
use shadowsocks::relay::tcprelay::proxy_stream::ProxyClientStream;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::api::*;
use crate::Core;

const FILE: &str = "proxy.json";

fn builder() -> reqwest::ClientBuilder {
    Client::builder().connect_timeout(Duration::from_secs(15))
}

pub(crate) struct Parsed {
    pub info: ProxyInfo,
    target: Target,
}

enum Target {
    Url(String),
    Shadowsocks(Box<ServerConfig>),
}

fn info(url: String, kind: ProxyKind, host: &str, port: u16) -> ProxyInfo {
    ProxyInfo {
        url,
        kind,
        host: host.to_string(),
        port,
    }
}

fn socks(user: &str, pass: &str, host: &str, port: u16) -> Result<Parsed> {
    ensure!(!host.is_empty(), "invalid-proxy");
    let host = &host.to_ascii_lowercase();
    let auth = match (user, pass) {
        ("", "") => String::new(),
        _ => format!(
            "{}:{}@",
            crate::transport::percent_encode(user),
            crate::transport::percent_encode(pass)
        ),
    };
    let host_part = if host.contains(':') {
        format!("[{host}]")
    } else {
        host.to_string()
    };
    Ok(Parsed {
        info: info(
            format!("socks5://{auth}{host_part}:{port}"),
            ProxyKind::Socks5,
            host,
            port,
        ),
        target: Target::Url(format!("socks5h://{auth}{host_part}:{port}")),
    })
}

fn query(u: &Url, key: &str) -> String {
    u.query_pairs()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.into_owned())
        .unwrap_or_default()
}

pub(crate) fn parse(link: &str) -> Result<Parsed> {
    let link = link.trim();
    let lower = link.to_ascii_lowercase();
    if lower.starts_with("ss://") {
        let cfg = ServerConfig::from_url(link).map_err(|e| {
            debug!("bad ss link: {e:?}");
            anyhow::anyhow!("invalid-proxy")
        })?;
        let (host, port) = match cfg.addr() {
            ServerAddr::SocketAddr(a) => (a.ip().to_string(), a.port()),
            ServerAddr::DomainName(h, p) => (h.clone(), *p),
        };
        return Ok(Parsed {
            info: info(link.to_string(), ProxyKind::Shadowsocks, &host, port),
            target: Target::Shadowsocks(Box::new(cfg)),
        });
    }
    let u = Url::parse(link).context("invalid-proxy")?;
    let host = u
        .host_str()
        .unwrap_or_default()
        .trim_matches(['[', ']'])
        .to_string();
    match u.scheme() {
        "socks5" | "socks5h" => socks(
            &percent_decode(u.username()),
            &percent_decode(u.password().unwrap_or_default()),
            &host,
            u.port().unwrap_or(1080),
        ),
        "tg" if host == "socks" => socks(
            &query(&u, "user"),
            &query(&u, "pass"),
            &query(&u, "server"),
            query(&u, "port").parse().context("invalid-proxy")?,
        ),
        "https" | "http" if host == "t.me" && u.path() == "/socks" => socks(
            &query(&u, "user"),
            &query(&u, "pass"),
            &query(&u, "server"),
            query(&u, "port").parse().context("invalid-proxy")?,
        ),
        scheme @ ("http" | "https") => {
            ensure!(
                !host.is_empty()
                    && matches!(u.path(), "" | "/")
                    && u.query().is_none()
                    && u.fragment().is_none(),
                "invalid-proxy"
            );
            let port = u.port_or_known_default().context("invalid-proxy")?;
            let kind = if scheme == "http" {
                ProxyKind::Http
            } else {
                ProxyKind::Https
            };
            let auth = match (u.username(), u.password()) {
                ("", None) => String::new(),
                (user, pass) => format!("{user}:{}@", pass.unwrap_or_default()),
            };
            let host_part = u.host_str().unwrap_or_default();
            let url = format!("{scheme}://{auth}{host_part}:{port}");
            Ok(Parsed {
                info: info(url.clone(), kind, &host, port),
                target: Target::Url(url),
            })
        }
        _ => bail!("invalid-proxy"),
    }
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match (
            b[i],
            b.get(i + 1..i + 3)
                .and_then(|h| u8::from_str_radix(std::str::from_utf8(h).ok()?, 16).ok()),
        ) {
            (b'%', Some(v)) => {
                out.push(v);
                i += 3;
            }
            (c, _) => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[derive(Default, Serialize, Deserialize)]
struct Stored {
    enabled: bool,
    active: Option<String>,
    list: Vec<String>,
}

pub(crate) struct Net {
    client: RwLock<Client>,
    bridge: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl Net {
    pub fn direct() -> Net {
        Net {
            client: RwLock::new(builder().build().expect("http client")),
            bridge: Mutex::new(None),
        }
    }

    pub fn client(&self) -> Client {
        self.client.read().unwrap().clone()
    }

    pub fn apply(&self, proxy: Option<&Parsed>) -> Result<()> {
        if let Some(old) = self.bridge.lock().unwrap().take() {
            old.abort();
        }
        let client = match proxy.map(|p| &p.target) {
            None => builder().no_proxy().build()?,
            Some(Target::Url(url)) => builder()
                .proxy(reqwest::Proxy::all(url.as_str())?)
                .build()?,
            Some(Target::Shadowsocks(cfg)) => {
                let listener = std::net::TcpListener::bind("127.0.0.1:0")?;
                listener.set_nonblocking(true)?;
                let addr = listener.local_addr()?;
                let cfg = (**cfg).clone();
                let ctx = SsContext::new_shared(ServerType::Local);
                let auth = Arc::new((
                    hex::encode(rand::random::<[u8; 16]>()),
                    hex::encode(rand::random::<[u8; 16]>()),
                ));
                let url = format!("socks5h://{}:{}@{addr}", auth.0, auth.1);
                let task = crate::RT.spawn(async move {
                    let listener = TcpListener::from_std(listener).expect("tokio listener");
                    serve_socks(listener, auth, move |target| {
                        let (ctx, cfg) = (ctx.clone(), cfg.clone());
                        async move { ProxyClientStream::connect(ctx, &cfg, target).await }
                    })
                    .await
                });
                *self.bridge.lock().unwrap() = Some(task);
                builder().proxy(reqwest::Proxy::all(url)?).build()?
            }
        };
        *self.client.write().unwrap() = client;
        info!(
            "network: {}",
            proxy.map_or("direct".to_string(), |p| format!(
                "{:?} proxy {}:{}",
                p.info.kind, p.info.host, p.info.port
            ))
        );
        Ok(())
    }
}

pub(crate) async fn serve_socks<F, Fut, S>(
    listener: TcpListener,
    auth: Arc<(String, String)>,
    dial: F,
) where
    F: Fn(Address) -> Fut + Send + Sync + Clone + 'static,
    Fut: Future<Output = std::io::Result<S>> + Send,
    S: AsyncRead + AsyncWrite + Unpin + Send,
{
    loop {
        let Ok((client, _)) = listener.accept().await else {
            return;
        };
        let (dial, auth) = (dial.clone(), auth.clone());
        tokio::spawn(async move {
            if let Err(e) = socks_session(client, &auth, dial).await {
                debug!("proxy session ended: {e}");
            }
        });
    }
}

async fn socks_session<F, Fut, S>(
    mut c: TcpStream,
    auth: &(String, String),
    dial: F,
) -> std::io::Result<()>
where
    F: Fn(Address) -> Fut,
    Fut: Future<Output = std::io::Result<S>>,
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut head = [0u8; 2];
    c.read_exact(&mut head).await?;
    let mut methods = vec![0u8; head[1].into()];
    c.read_exact(&mut methods).await?;
    if !methods.contains(&2) {
        c.write_all(&[5, 0xff]).await?;
        return Err(std::io::Error::other(
            "socks client offered no password auth",
        ));
    }
    c.write_all(&[5, 2]).await?;
    let mut ver_len = [0u8; 2];
    c.read_exact(&mut ver_len).await?;
    let mut user = vec![0u8; usize::from(ver_len[1]) + 1];
    c.read_exact(&mut user).await?;
    let mut pass = vec![0u8; usize::from(user.pop().unwrap_or(0))];
    c.read_exact(&mut pass).await?;
    if user != auth.0.as_bytes() || pass != auth.1.as_bytes() {
        c.write_all(&[1, 1]).await?;
        return Err(std::io::Error::other("socks auth rejected"));
    }
    c.write_all(&[1, 0]).await?;
    let mut req = [0u8; 4];
    c.read_exact(&mut req).await?;
    let target = match req[3] {
        1 => {
            let mut b = [0u8; 6];
            c.read_exact(&mut b).await?;
            Address::SocketAddress(SocketAddr::from((
                [b[0], b[1], b[2], b[3]],
                u16::from_be_bytes([b[4], b[5]]),
            )))
        }
        3 => {
            let mut len = [0u8; 1];
            c.read_exact(&mut len).await?;
            let mut b = vec![0u8; usize::from(len[0]) + 2];
            c.read_exact(&mut b).await?;
            let port = u16::from_be_bytes([b[b.len() - 2], b[b.len() - 1]]);
            b.truncate(b.len() - 2);
            Address::DomainNameAddress(String::from_utf8_lossy(&b).into_owned(), port)
        }
        4 => {
            let mut b = [0u8; 18];
            c.read_exact(&mut b).await?;
            let ip: [u8; 16] = b[..16].try_into().unwrap();
            Address::SocketAddress(SocketAddr::from((ip, u16::from_be_bytes([b[16], b[17]]))))
        }
        _ => return Err(std::io::Error::other("bad socks address")),
    };
    match dial(target).await {
        Ok(mut upstream) => {
            c.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
            tokio::io::copy_bidirectional(&mut c, &mut upstream).await?;
            Ok(())
        }
        Err(e) => {
            c.write_all(&[5, 1, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
            Err(e)
        }
    }
}

impl Core {
    fn proxy_file(&self) -> PathBuf {
        self.dir.join(FILE)
    }

    fn load_proxies(&self) -> Stored {
        std::fs::read(self.proxy_file())
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub(crate) fn apply_proxy(&self) {
        let s = self.load_proxies();
        let active = s
            .active
            .as_deref()
            .filter(|_| s.enabled)
            .and_then(|a| parse(a).ok());
        if let Err(e) = self.net.apply(active.as_ref()) {
            warn!("cannot apply proxy: {e:#}");
        }
    }

    fn save_proxies(&self, s: &Stored) -> Result<ProxySettings> {
        std::fs::write(self.proxy_file(), serde_json::to_vec(s)?)?;
        self.apply_proxy();
        for a in self.accounts.lock().unwrap().values() {
            a.clone().restart();
        }
        Ok(self.proxy_settings())
    }
}

#[uniffi::export]
impl Core {
    pub fn proxy_settings(&self) -> ProxySettings {
        let s = self.load_proxies();
        ProxySettings {
            enabled: s.enabled && s.active.is_some(),
            active: s.active,
            proxies: s
                .list
                .iter()
                .filter_map(|u| parse(u).ok())
                .map(|p| p.info)
                .collect(),
        }
    }

    pub fn parse_proxy(&self, link: String) -> Result<ProxyInfo, CoreError> {
        Ok(parse(&link)?.info)
    }

    pub fn add_proxy(&self, link: String) -> Result<ProxySettings, CoreError> {
        let url = parse(&link)?.info.url;
        let mut s = self.load_proxies();
        s.list.retain(|u| *u != url);
        s.list.insert(0, url.clone());
        s.active = Some(url);
        s.enabled = true;
        Ok(self.save_proxies(&s)?)
    }

    pub fn select_proxy(&self, url: String) -> Result<ProxySettings, CoreError> {
        let mut s = self.load_proxies();
        if !s.list.contains(&url) {
            return Err(anyhow::anyhow!("Proxy not found").into());
        }
        s.active = Some(url);
        s.enabled = true;
        Ok(self.save_proxies(&s)?)
    }

    pub fn remove_proxy(&self, url: String) -> Result<ProxySettings, CoreError> {
        let mut s = self.load_proxies();
        s.list.retain(|u| *u != url);
        if s.active.as_deref() == Some(url.as_str()) {
            s.active = None;
            s.enabled = false;
        }
        Ok(self.save_proxies(&s)?)
    }

    pub fn set_proxy_enabled(&self, enabled: bool) -> Result<ProxySettings, CoreError> {
        let mut s = self.load_proxies();
        if enabled && s.active.is_none() {
            s.active = s.list.first().cloned();
        }
        s.enabled = enabled && s.active.is_some();
        Ok(self.save_proxies(&s)?)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    use base64::Engine as _;
    use shadowsocks::crypto::CipherKind;
    use shadowsocks::relay::tcprelay::proxy_listener::ProxyListener;

    use super::*;

    #[test]
    fn proxy_links() {
        let p = parse(" socks5://us%40r:p%3Ass@Example.com:1081 ")
            .unwrap()
            .info;
        assert_eq!(
            p,
            info(
                "socks5://us%40r:p%3Ass@example.com:1081".into(),
                ProxyKind::Socks5,
                "example.com",
                1081
            )
        );
        assert_eq!(
            parse("socks5h://1.2.3.4").unwrap().info.url,
            "socks5://1.2.3.4:1080"
        );
        let tg = parse("tg://socks?server=9.9.9.9&port=443&user=a&pass=b").unwrap();
        assert_eq!(tg.info.url, "socks5://a:b@9.9.9.9:443");
        let tme = parse("https://t.me/socks?server=proxy.example&port=1080").unwrap();
        assert_eq!(
            (tme.info.kind, tme.info.host.as_str(), tme.info.port),
            (ProxyKind::Socks5, "proxy.example", 1080)
        );
        let h = parse("http://proxy.example:3128").unwrap().info;
        assert_eq!(
            (h.kind, h.url.as_str()),
            (ProxyKind::Http, "http://proxy.example:3128")
        );
        let hs = parse("https://user:pw@proxy.example/").unwrap().info;
        assert_eq!(
            (hs.kind, hs.port, hs.url.as_str()),
            (ProxyKind::Https, 443, "https://user:pw@proxy.example:443")
        );
        let user = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("aes-256-gcm:secret");
        let ss = parse(&format!("ss://{user}@5.6.7.8:8388#My%20node"))
            .unwrap()
            .info;
        assert_eq!(
            (ss.kind, ss.host.as_str(), ss.port),
            (ProxyKind::Shadowsocks, "5.6.7.8", 8388)
        );
        let legacy = base64::engine::general_purpose::STANDARD
            .encode("chacha20-ietf-poly1305:pw@ss.example:443");
        let ss = parse(&format!("ss://{legacy}")).unwrap().info;
        assert_eq!((ss.host.as_str(), ss.port), ("ss.example", 443));
        for bad in [
            "",
            "hello",
            "ftp://proxy.example:21",
            "https://example.com/page",
            "http://example.com:80/?q=1",
            "ss://garbage",
            "tg://socks?server=x",
            "https://i.tapetape.org/#a=b",
        ] {
            assert!(parse(bad).is_err(), "{bad}");
        }
    }

    async fn target() -> (String, Arc<AtomicUsize>) {
        let hits = Arc::new(AtomicUsize::new(0));
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        let h = hits.clone();
        tokio::spawn(async move {
            loop {
                let (mut s, _) = l.accept().await.unwrap();
                let h = h.clone();
                tokio::spawn(async move {
                    let mut buf = [0u8; 4096];
                    while let Ok(n) = s.read(&mut buf).await {
                        if n == 0 {
                            return;
                        }
                        h.fetch_add(1, Ordering::SeqCst);
                        let _ = s
                            .write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\nok")
                            .await;
                    }
                });
            }
        });
        (format!("localhost:{port}"), hits)
    }

    async fn plain_dial(a: Address) -> std::io::Result<TcpStream> {
        match a {
            Address::SocketAddress(a) => TcpStream::connect(a).await,
            Address::DomainNameAddress(h, p) => TcpStream::connect((h.as_str(), p)).await,
        }
    }

    async fn http_proxy() -> (u16, Arc<AtomicUsize>) {
        let used = Arc::new(AtomicUsize::new(0));
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        let u = used.clone();
        tokio::spawn(async move {
            loop {
                let (mut c, _) = l.accept().await.unwrap();
                let u = u.clone();
                tokio::spawn(async move {
                    let mut buf = Vec::new();
                    let mut chunk = [0u8; 4096];
                    let end = loop {
                        let n = c.read(&mut chunk).await.unwrap();
                        buf.extend_from_slice(&chunk[..n]);
                        if let Some(e) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                            break e + 4;
                        }
                    };
                    u.fetch_add(1, Ordering::SeqCst);
                    let head = String::from_utf8_lossy(&buf[..end]).to_string();
                    let line = head.lines().next().unwrap().to_string();
                    let mut parts = line.split(' ');
                    let (method, uri) = (parts.next().unwrap(), parts.next().unwrap());
                    if method == "CONNECT" {
                        let mut up = TcpStream::connect(uri).await.unwrap();
                        c.write_all(b"HTTP/1.1 200 OK\r\n\r\n").await.unwrap();
                        let _ = tokio::io::copy_bidirectional(&mut c, &mut up).await;
                        return;
                    }
                    let rest = uri.strip_prefix("http://").unwrap();
                    let (authority, path) = rest.split_at(rest.find('/').unwrap_or(rest.len()));
                    let mut up = TcpStream::connect(authority).await.unwrap();
                    let rewritten = head.replacen(&line, &format!("{method} {path} HTTP/1.1"), 1);
                    up.write_all(rewritten.as_bytes()).await.unwrap();
                    up.write_all(&buf[end..]).await.unwrap();
                    let _ = tokio::io::copy_bidirectional(&mut c, &mut up).await;
                });
            }
        });
        (port, used)
    }

    async fn get(net: &Net, url: &str) -> Result<String> {
        Ok(net
            .client()
            .get(url)
            .timeout(Duration::from_secs(10))
            .send()
            .await?
            .text()
            .await?)
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn traffic_goes_through_each_proxy_kind() {
        let (host, hits) = target().await;
        let url = format!("http://{host}/x");
        let net = Net::direct();
        assert_eq!(get(&net, &url).await.unwrap(), "ok");

        let (port, used) = http_proxy().await;
        net.apply(Some(&parse(&format!("http://127.0.0.1:{port}")).unwrap()))
            .unwrap();
        assert_eq!(get(&net, &url).await.unwrap(), "ok");
        assert_eq!(used.load(Ordering::SeqCst), 1);

        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        let sessions = Arc::new(AtomicUsize::new(0));
        let s = sessions.clone();
        tokio::spawn(serve_socks(
            l,
            Arc::new(("u".into(), "p".into())),
            move |a| {
                s.fetch_add(1, Ordering::SeqCst);
                plain_dial(a)
            },
        ));
        net.apply(Some(
            &parse(&format!("socks5://u:p@127.0.0.1:{port}")).unwrap(),
        ))
        .unwrap();
        assert_eq!(get(&net, &url).await.unwrap(), "ok");
        assert_eq!(sessions.load(Ordering::SeqCst), 1);
        for bad in ["socks5://u:x@", "socks5://"] {
            net.apply(Some(&parse(&format!("{bad}127.0.0.1:{port}")).unwrap()))
                .unwrap();
            assert!(get(&net, &url).await.is_err());
        }
        assert_eq!(sessions.load(Ordering::SeqCst), 1);

        let cfg =
            ServerConfig::new(("127.0.0.1", 0), "test-password", CipherKind::AES_256_GCM).unwrap();
        let ss = ProxyListener::bind(SsContext::new_shared(ServerType::Server), &cfg)
            .await
            .unwrap();
        let ss_port = ss.local_addr().unwrap().port();
        let relayed = Arc::new(AtomicUsize::new(0));
        let r = relayed.clone();
        tokio::spawn(async move {
            loop {
                let (mut s, _) = ss.accept().await.unwrap();
                let r = r.clone();
                tokio::spawn(async move {
                    let target = s.handshake().await.unwrap();
                    r.fetch_add(1, Ordering::SeqCst);
                    let mut up = plain_dial(target).await.unwrap();
                    let _ = tokio::io::copy_bidirectional(&mut s, &mut up).await;
                });
            }
        });
        let user =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("aes-256-gcm:test-password");
        net.apply(Some(
            &parse(&format!("ss://{user}@127.0.0.1:{ss_port}")).unwrap(),
        ))
        .unwrap();
        assert_eq!(get(&net, &url).await.unwrap(), "ok");
        assert_eq!(relayed.load(Ordering::SeqCst), 1);
        let user = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("aes-256-gcm:wrong");
        net.apply(Some(
            &parse(&format!("ss://{user}@127.0.0.1:{ss_port}")).unwrap(),
        ))
        .unwrap();
        assert!(get(&net, &url).await.is_err());

        net.apply(Some(&parse("socks5://127.0.0.1:1").unwrap()))
            .unwrap();
        assert!(get(&net, &url).await.is_err());
        net.apply(None).unwrap();
        let before = hits.load(Ordering::SeqCst);
        assert_eq!(get(&net, &url).await.unwrap(), "ok");
        assert_eq!(hits.load(Ordering::SeqCst), before + 1);
    }

    struct Quiet;

    impl EventListener for Quiet {
        fn on_event(&self, _: u32, _: Event) {}
    }

    #[test]
    fn proxy_list_persists() {
        let dir = tempfile::tempdir().unwrap();
        let core = Core::new(dir.path().to_string_lossy().into(), Box::new(Quiet)).unwrap();
        assert_eq!(
            core.proxy_settings(),
            ProxySettings {
                enabled: false,
                active: None,
                proxies: vec![]
            }
        );
        assert!(core.add_proxy("nonsense".into()).is_err());
        core.add_proxy("socks5://127.0.0.1:9050".into()).unwrap();
        let s = core.add_proxy("http://proxy.example:3128".into()).unwrap();
        assert!(s.enabled);
        assert_eq!(s.active.as_deref(), Some("http://proxy.example:3128"));
        assert_eq!(s.proxies.len(), 2);
        let s = core.select_proxy("socks5://127.0.0.1:9050".into()).unwrap();
        assert_eq!(s.active.as_deref(), Some("socks5://127.0.0.1:9050"));
        assert!(!core.set_proxy_enabled(false).unwrap().enabled);
        drop(core);
        let core = Core::new(dir.path().to_string_lossy().into(), Box::new(Quiet)).unwrap();
        let s = core.proxy_settings();
        assert!(!s.enabled && s.proxies.len() == 2);
        assert!(core.set_proxy_enabled(true).unwrap().enabled);
        let s = core.remove_proxy("socks5://127.0.0.1:9050".into()).unwrap();
        assert!(!s.enabled && s.active.is_none() && s.proxies.len() == 1);
        assert!(core.select_proxy("socks5://127.0.0.1:9050".into()).is_err());
        let s = core.set_proxy_enabled(true).unwrap();
        assert_eq!(
            (s.enabled, s.active.as_deref()),
            (true, Some("http://proxy.example:3128"))
        );
    }
}
