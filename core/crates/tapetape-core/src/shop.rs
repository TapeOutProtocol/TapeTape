use std::sync::Arc;
use std::time::Duration;

use anyhow::{ensure, Context, Result};
use base64::Engine as _;
use log::info;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use taperelay::evm::{self, Abi};

use crate::account::Account;
use crate::api::*;
use crate::chain::{format_amount, unhex, Call, Rpc};
use crate::store;

const CHAIN_ID: u64 = 196;
const CPU: &str = "0x69f663931209096037474d7c20402232e5c762ca";
const CPU_INDEX: u32 = 265;
const OPENER: &str = "0x536add8f30f03b69f6fbf29d425a816a0dc50106";
const REGISTRY: &str = "0xd6efb7adcc9c83dc4924ad56f6a8e4e969b9adb6";
const BINDING: &str = "0x68809fd2fb343aa57d0aeb7f33defe477c9666f9";
const TAPED_OUT: &str = "0xc11215e417669c143c8a07aeb778034c0a0a85ebdf305d64a629b19a7a9ce031";
const GATEWAY: &str = "tapekit.org";
const BIND_MONTHS: u128 = 120;
const CHUNK: usize = 24_000;
const READ_WINDOW: usize = 96 << 10;
const GAS_RESERVE: u128 = 5_000_000_000_000_000;
const RECEIPT_WAIT: Duration = Duration::from_secs(180);
const MAX_IMAGES: usize = 5;
const MAX_IMAGE: usize = 200 << 10;
const MAX_DESCRIPTION: usize = 500;
const CONFIG_KEY: &str = "shop_circuit";
const PAGE: &[u8] = include_bytes!("../assets/shop/index.html");

#[derive(Serialize, Deserialize)]
struct Products {
    version: u32,
    products: Vec<Product>,
}

struct FileInfo {
    size: usize,
    sha: [u8; 32],
    chunks: usize,
}

fn a(s: &str) -> [u8; 20] {
    evm::parse_address(s).expect("constant address")
}

fn name_of(id: u128) -> String {
    format!("{id}.2.{CPU_INDEX}.tape")
}

fn host_of(id: u128, open: bool) -> String {
    let suffix = if open { "-open" } else { "" };
    format!("{id}-2-{CPU_INDEX}{suffix}.{GATEWAY}")
}

fn netlist() -> Vec<u8> {
    hex::decode(format!(
        "02{}{:016x}0201000002000003",
        CPU.trim_start_matches("0x"),
        1
    ))
    .expect("netlist hex")
}

fn sha256(data: &[u8]) -> [u8; 32] {
    Sha256::digest(data).into()
}

fn call(to: &str, value: u128, signature: &str, args: &[Abi<'_>]) -> Call {
    Call {
        to: a(to),
        value,
        data: evm::encode_call(signature, args),
    }
}

fn tapeout(fee: u128) -> Call {
    call(
        CPU,
        fee,
        "tapeout(bytes,uint32,uint32)",
        &[Abi::Bytes(&netlist()), Abi::Uint(2), Abi::Uint(1)],
    )
}

fn open_call(id: u128, fee: u128) -> Call {
    call(
        OPENER,
        fee,
        "open(address,uint256)",
        &[Abi::Address(a(CPU)), Abi::Uint(id)],
    )
}

fn bind_call(id: u128, container: [u8; 20], fee: u128) -> Call {
    call(
        BINDING,
        fee,
        "bind(string,address,uint256)",
        &[
            Abi::Str(&name_of(id)),
            Abi::Address(container),
            Abi::Uint(BIND_MONTHS),
        ],
    )
}

fn chunk_calls(
    container: [u8; 20],
    path: &str,
    content_type: &str,
    data: &[u8],
    start: usize,
) -> Vec<Call> {
    let sha = sha256(data);
    data.chunks(CHUNK)
        .enumerate()
        .skip(start)
        .map(|(i, chunk)| {
            if i == 0 {
                call(
                    REGISTRY,
                    0,
                    "putFile(address,string,string,bytes32,bytes)",
                    &[
                        Abi::Address(container),
                        Abi::Str(path),
                        Abi::Str(content_type),
                        Abi::Bytes32(sha),
                        Abi::Bytes(chunk),
                    ],
                )
            } else {
                call(
                    REGISTRY,
                    0,
                    "appendChunk(address,string,uint256,bytes)",
                    &[
                        Abi::Address(container),
                        Abi::Str(path),
                        Abi::Uint(i as u128),
                        Abi::Bytes(chunk),
                    ],
                )
            }
        })
        .collect()
}

fn fallback_call(container: [u8; 20]) -> Call {
    call(
        REGISTRY,
        0,
        "setFallback(address,string)",
        &[Abi::Address(container), Abi::Str("index.html")],
    )
}

fn taped_out_id(receipt: &Value) -> Result<u128> {
    let topic = receipt["logs"]
        .as_array()
        .context("Receipt has no logs")?
        .iter()
        .filter(|log| {
            log["address"]
                .as_str()
                .is_some_and(|a| a.eq_ignore_ascii_case(CPU))
        })
        .filter_map(|log| log["topics"].as_array())
        .find(|topics| topics.first().and_then(Value::as_str) == Some(TAPED_OUT))
        .and_then(|topics| topics.get(1))
        .context("No TapedOut event in receipt")?;
    evm::uint(&unhex(topic)?, 0)
}

struct Shop<'a> {
    rpc: Rpc,
    account: &'a Account,
}

impl Shop<'_> {
    fn address(&self) -> &str {
        self.account.keys.id.address()
    }

    async fn flag(&self, to: &str, signature: &str, args: &[Abi<'_>]) -> Result<bool> {
        Ok(self.rpc.uint(a(to), signature, args).await? != 0)
    }

    async fn send(&self, what: &str, c: &Call) -> Result<Value> {
        let gas = self.rpc.estimate(self.address(), c).await?;
        let price = self.rpc.gas_price().await?;
        let hash = self.rpc.send(&self.account.keys.id, c, gas, price).await?;
        info!("shop: {what} sent {hash}");
        self.rpc
            .wait(&hash, RECEIPT_WAIT)
            .await
            .with_context(|| format!("Shop {what} did not complete ({hash})"))
    }

    async fn container(&self, id: u128) -> Result<[u8; 20]> {
        evm::address(
            &self
                .rpc
                .call(
                    a(OPENER),
                    "accountOf(address,uint256)",
                    &[Abi::Address(a(CPU)), Abi::Uint(id)],
                )
                .await?,
            0,
        )
    }

    async fn opened(&self, id: u128) -> Result<bool> {
        self.flag(
            OPENER,
            "isOpened(address,uint256)",
            &[Abi::Address(a(CPU)), Abi::Uint(id)],
        )
        .await
    }

    async fn live(&self, container: [u8; 20]) -> Result<bool> {
        self.flag(
            BINDING,
            "isContainerLive(address)",
            &[Abi::Address(container)],
        )
        .await
    }

    async fn fees(&self) -> Result<(u128, u128)> {
        Ok((
            self.rpc.uint(a(CPU), "TAPEOUT_FEE()", &[]).await?,
            self.rpc.uint(a(OPENER), "FEE()", &[]).await?,
        ))
    }

    async fn file_info(&self, container: [u8; 20], path: &str) -> Result<FileInfo> {
        let out = self
            .rpc
            .call(
                a(REGISTRY),
                "fileInfo(address,string)",
                &[Abi::Address(container), Abi::Str(path)],
            )
            .await?;
        Ok(FileInfo {
            size: usize::try_from(evm::uint(&out, 0)?)?,
            sha: evm::word(&out, 2)?.try_into()?,
            chunks: usize::try_from(evm::uint(&out, 4)?)?,
        })
    }

    async fn read_file(&self, container: [u8; 20], path: &str) -> Result<Option<Vec<u8>>> {
        let info = self.file_info(container, path).await?;
        if info.chunks == 0 {
            return Ok(None);
        }
        let mut data = Vec::with_capacity(info.size);
        while data.len() < info.size {
            let out = self
                .rpc
                .call(
                    a(REGISTRY),
                    "readRange(address,string,uint256,uint256)",
                    &[
                        Abi::Address(container),
                        Abi::Str(path),
                        Abi::Uint(data.len() as u128),
                        Abi::Uint(READ_WINDOW as u128),
                    ],
                )
                .await?;
            let part = evm::dynamic(&out, 0)?;
            ensure!(!part.is_empty(), "Short read of {path}");
            data.extend_from_slice(part);
        }
        ensure!(sha256(&data) == info.sha, "Hash mismatch reading {path}");
        Ok(Some(data))
    }

    async fn write_file(
        &self,
        container: [u8; 20],
        path: &str,
        content_type: &str,
        data: &[u8],
    ) -> Result<()> {
        let sha = sha256(data);
        let total = data.len().div_ceil(CHUNK);
        let info = self.file_info(container, path).await?;
        if info.sha == sha && info.chunks == total && info.size == data.len() {
            return Ok(());
        }
        let start = if info.sha == sha && info.chunks < total && info.size == info.chunks * CHUNK {
            info.chunks
        } else {
            0
        };
        for (i, c) in chunk_calls(container, path, content_type, data, start)
            .iter()
            .enumerate()
        {
            self.send(&format!("write {path} {}/{total}", start + i + 1), c)
                .await?;
        }
        Ok(())
    }

    async fn products(&self, container: [u8; 20]) -> Result<Products> {
        Ok(match self.read_file(container, "products.json").await? {
            Some(data) => serde_json::from_slice(&data)?,
            None => Products {
                version: 1,
                products: Vec::new(),
            },
        })
    }

    async fn ready_container(&self) -> Result<[u8; 20]> {
        let id = self.account.shop_id()?.context("Shop is not open")?;
        self.container(id).await
    }
}

impl Account {
    fn shop(&self) -> Result<Shop<'_>> {
        Ok(Shop {
            rpc: Rpc::new(&self.http(), CHAIN_ID)?,
            account: self,
        })
    }

    fn shop_id(&self) -> Result<Option<u128>> {
        Ok(store::get_config(&self.db(), CONFIG_KEY)?.and_then(|s| s.parse().ok()))
    }

    fn shop_json(&self) -> Result<Vec<u8>> {
        let name = self.my_name(&self.db())?;
        Ok(serde_json::to_vec(
            &json!({"version": 1, "name": name, "invite": self.invite_link()}),
        )?)
    }

    async fn shop_state(&self) -> Result<ShopInfo> {
        let shop = self.shop()?;
        let balance = shop.rpc.native_balance(shop.address()).await?;
        let id = self.shop_id()?;
        let (opened, ready) = match id {
            Some(id) => {
                let opened = shop.opened(id).await?;
                let ready = opened && {
                    let container = shop.container(id).await?;
                    shop.live(container).await?
                        && shop.file_info(container, "index.html").await?.chunks > 0
                };
                (opened, ready)
            }
            None => (false, false),
        };
        let open_cost = if ready {
            0
        } else {
            let (tapeout_fee, open_fee) = shop.fees().await?;
            GAS_RESERVE
                + if id.is_none() { tapeout_fee } else { 0 }
                + if opened { 0 } else { open_fee }
        };
        let url = |open| {
            id.filter(|_| ready)
                .map(|id| format!("https://{}/", host_of(id, open)))
        };
        Ok(ShopInfo {
            address: shop.address().to_string(),
            balance: format_amount(balance, 18),
            open_cost: format_amount(open_cost, 18),
            ready,
            name: id.map(name_of),
            app_url: url(true),
            share_url: url(false),
        })
    }

    async fn open_shop(&self) -> Result<ShopInfo> {
        let _guard = self.tx_lock.lock().await;
        let shop = self.shop()?;
        let id = match self.shop_id()? {
            Some(id) => id,
            None => {
                let (tapeout_fee, open_fee) = shop.fees().await?;
                let need = tapeout_fee + open_fee + GAS_RESERVE;
                let balance = shop.rpc.native_balance(shop.address()).await?;
                ensure!(
                    balance >= need,
                    "Not enough OKB on X Layer: need {} OKB, have {} OKB",
                    format_amount(need, 18),
                    format_amount(balance, 18)
                );
                let receipt = shop.send("tapeout", &tapeout(tapeout_fee)).await?;
                let id = taped_out_id(&receipt)?;
                store::set_config(&self.db(), CONFIG_KEY, &id.to_string())?;
                info!("shop: circuit {} created", name_of(id));
                id
            }
        };
        let container = shop.container(id).await?;
        if !shop.opened(id).await? {
            let fee = shop.rpc.uint(a(OPENER), "FEE()", &[]).await?;
            shop.send("open container", &open_call(id, fee)).await?;
        }
        if !shop.live(container).await? {
            let fee = shop.rpc.uint(a(BINDING), "monthlyFee()", &[]).await? * BIND_MONTHS;
            shop.send("bind name", &bind_call(id, container, fee))
                .await?;
        }
        shop.write_file(container, "index.html", "text/html; charset=utf-8", PAGE)
            .await?;
        shop.write_file(
            container,
            "shop.json",
            "application/json",
            &self.shop_json()?,
        )
        .await?;
        let fallback = shop
            .rpc
            .call(
                a(REGISTRY),
                "fallbackPath(address)",
                &[Abi::Address(container)],
            )
            .await?;
        if evm::dynamic(&fallback, 0)? != b"index.html" {
            shop.send("set fallback", &fallback_call(container)).await?;
        }
        info!("shop: {} is ready", name_of(id));
        drop(_guard);
        self.shop_state().await
    }

    async fn publish_product(&self, input: ProductInput) -> Result<Product> {
        let description = input.description.trim().to_string();
        ensure!(
            (1..=MAX_DESCRIPTION).contains(&description.chars().count()),
            "Description must be 1–{MAX_DESCRIPTION} characters"
        );
        ensure!(
            input.discount_price <= input.original_price,
            "Sale price cannot exceed list price"
        );
        ensure!(
            (1..=MAX_IMAGES).contains(&input.images.len()),
            "Add 1–{MAX_IMAGES} images"
        );
        let images = input
            .images
            .iter()
            .map(|b64| {
                let data = base64::engine::general_purpose::STANDARD.decode(b64.trim())?;
                ensure!(data.len() <= MAX_IMAGE, "Image is too large");
                ensure!(data.starts_with(&[0xff, 0xd8]), "Image is not a JPEG");
                Ok(data)
            })
            .collect::<Result<Vec<_>>>()?;
        let _guard = self.tx_lock.lock().await;
        let shop = self.shop()?;
        let container = shop.ready_container().await?;
        let id = hex::encode(rand::random::<[u8; 4]>());
        let mut paths = Vec::with_capacity(images.len());
        for (n, image) in images.iter().enumerate() {
            let path = format!("img/{id}-{n}.jpg");
            shop.write_file(container, &path, "image/jpeg", image)
                .await?;
            paths.push(format!("/{path}"));
        }
        let product = Product {
            id,
            description,
            original_price: input.original_price,
            discount_price: input.discount_price,
            images: paths,
            created_at: crate::protocol::now_ms() / 1000,
        };
        let mut list = shop.products(container).await?;
        list.products.insert(0, product.clone());
        shop.write_file(
            container,
            "products.json",
            "application/json",
            &serde_json::to_vec(&list)?,
        )
        .await?;
        info!("shop: product {} published", product.id);
        Ok(product)
    }

    async fn remove_product(&self, product_id: &str) -> Result<()> {
        let _guard = self.tx_lock.lock().await;
        let shop = self.shop()?;
        let container = shop.ready_container().await?;
        let mut list = shop.products(container).await?;
        let before = list.products.len();
        list.products.retain(|p| p.id != product_id);
        if list.products.len() == before {
            return Ok(());
        }
        shop.write_file(
            container,
            "products.json",
            "application/json",
            &serde_json::to_vec(&list)?,
        )
        .await?;
        info!("shop: product {product_id} removed");
        Ok(())
    }
}

#[uniffi::export]
impl Account {
    pub async fn shop_info(self: Arc<Self>) -> Result<ShopInfo, CoreError> {
        crate::run(async move { self.shop_state().await }).await
    }

    pub async fn shop_open(self: Arc<Self>) -> Result<ShopInfo, CoreError> {
        crate::run(async move { self.open_shop().await }).await
    }

    pub async fn shop_publish(self: Arc<Self>, input: ProductInput) -> Result<Product, CoreError> {
        crate::run(async move { self.publish_product(input).await }).await
    }

    pub async fn shop_remove(self: Arc<Self>, product_id: String) -> Result<(), CoreError> {
        crate::run(async move { self.remove_product(&product_id).await }).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chain::quantity;

    const SIM_RPC: &str = "https://xlayer-mainnet.rpc.sentio.xyz";

    async fn simulate(rpc: &Rpc, from: &str, calls: &[Call]) -> Result<Vec<Value>> {
        let calls: Vec<Value> = calls
            .iter()
            .map(|c| {
                let mut v = c.json(from);
                v["gas"] = json!("0x1000000");
                v
            })
            .collect();
        let result = rpc
            .request(
                "eth_simulateV1",
                json!([{
                    "blockStateCalls": [{
                        "stateOverrides": {from: {"balance": "0xde0b6b3a7640000"}},
                        "calls": calls
                    }]
                }, "latest"]),
            )
            .await?;
        let results = result[0]["calls"].as_array().context("no calls")?.clone();
        for (i, r) in results.iter().enumerate() {
            ensure!(r["status"] == "0x1", "simulated call {i} failed: {r}");
        }
        Ok(results)
    }

    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "reads X Layer mainnet and simulates the open flow; sends no transactions"]
    async fn live_simulate_shop_open() -> Result<()> {
        let http = crate::transport::http_client();
        let rpc = Rpc::new(&http, CHAIN_ID)?;
        let sim = Rpc::with_url(&http, CHAIN_ID, SIM_RPC)?;
        let from = taperelay::Identity::generate("relay1.taperelay.com:4246")?
            .address()
            .to_string();
        let cpu_at = rpc
            .call(
                a("0x1f09daefa827f02cbb40967cc91b259763760761"),
                "cpuAt(uint256)",
                &[Abi::Uint(CPU_INDEX.into())],
            )
            .await?;
        assert_eq!(evm::address(&cpu_at, 0)?, a(CPU));
        let tapeout_fee = rpc.uint(a(CPU), "TAPEOUT_FEE()", &[]).await?;
        let open_fee = rpc.uint(a(OPENER), "FEE()", &[]).await?;
        let monthly = rpc.uint(a(BINDING), "monthlyFee()", &[]).await?;
        println!(
            "tapeout fee {} OKB, open fee {} OKB, bind {} OKB",
            format_amount(tapeout_fee, 18),
            format_amount(open_fee, 18),
            format_amount(monthly * BIND_MONTHS, 18)
        );
        let first = simulate(&sim, &from, &[tapeout(tapeout_fee)]).await?;
        let id = taped_out_id(&json!({"logs": first[0]["logs"]}))?;
        let container = evm::address(
            &rpc.call(
                a(OPENER),
                "accountOf(address,uint256)",
                &[Abi::Address(a(CPU)), Abi::Uint(id)],
            )
            .await?,
            0,
        )?;
        println!(
            "next circuit {} container {}",
            name_of(id),
            crate::chain::hex0x(&container)
        );
        let mut calls = vec![
            tapeout(tapeout_fee),
            open_call(id, open_fee),
            bind_call(id, container, monthly * BIND_MONTHS),
        ];
        calls.extend(chunk_calls(
            container,
            "index.html",
            "text/html; charset=utf-8",
            PAGE,
            0,
        ));
        calls.push(fallback_call(container));
        calls.push(call(
            REGISTRY,
            0,
            "fileInfo(address,string)",
            &[Abi::Address(container), Abi::Str("index.html")],
        ));
        calls.push(call(
            BINDING,
            0,
            "isContainerLive(address)",
            &[Abi::Address(container)],
        ));
        calls.push(call(
            REGISTRY,
            0,
            "readRange(address,string,uint256,uint256)",
            &[
                Abi::Address(container),
                Abi::Str("index.html"),
                Abi::Uint(0),
                Abi::Uint(READ_WINDOW as u128),
            ],
        ));
        let results = simulate(&sim, &from, &calls).await?;
        let n = results.len();
        let gas: u128 = results[..n - 3]
            .iter()
            .map(|r| quantity(&r["gasUsed"]).unwrap())
            .sum();
        println!("open flow: {} transactions, {gas} gas", n - 3);
        let info = unhex(&results[n - 3]["returnData"])?;
        assert_eq!(evm::uint(&info, 0)?, PAGE.len() as u128);
        assert_eq!(evm::word(&info, 2)?, sha256(PAGE));
        assert_eq!(evm::uint(&info, 4)?, PAGE.len().div_ceil(CHUNK) as u128);
        assert_eq!(evm::uint(&unhex(&results[n - 2]["returnData"])?, 0)?, 1);
        let read = unhex(&results[n - 1]["returnData"])?;
        assert_eq!(
            evm::dynamic(&read, 0)?,
            &PAGE[..PAGE.len().min(READ_WINDOW)]
        );
        Ok(())
    }

    #[test]
    fn netlist_and_names() {
        assert_eq!(
            hex::encode(netlist()),
            "0269f663931209096037474d7c20402232e5c762ca00000000000000010201000002000003"
        );
        assert_eq!(name_of(12), "12.2.265.tape");
        assert_eq!(host_of(12, true), "12-2-265-open.tapekit.org");
        assert_eq!(host_of(12, false), "12-2-265.tapekit.org");
    }

    #[test]
    fn taped_out_id_from_receipt() {
        let receipt = json!({"logs": [
            {"address": "0x0000000000000000000000000000000000000001", "topics": [TAPED_OUT, format!("0x{:064x}", 1)]},
            {"address": CPU.to_uppercase().replace("0X", "0x"), "topics": [TAPED_OUT, format!("0x{:064x}", 77)]}
        ]});
        assert_eq!(taped_out_id(&receipt).unwrap(), 77);
    }
}
