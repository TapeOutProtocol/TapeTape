uniffi::setup_scaffolding!();

mod account;
mod api;
mod backup;
mod chain;
mod chat;
mod dapp;
mod directory;
mod files;
mod group;
mod invite;
mod message;
mod net;
mod packet;
mod packet_abi;
mod protocol;
mod settings;
mod shop;
mod store;
mod transport;
mod wallet;

#[cfg(test)]
mod chain_sim_tests;
#[cfg(test)]
mod live_tests;
#[cfg(test)]
mod local_tests;

use std::collections::BTreeMap;
use std::future::Future;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock, Mutex};

use anyhow::{bail, Context, Result};
use log::{error, info};
use rand::seq::SliceRandom;
use tokio::runtime::Runtime;

pub use account::Account;
pub use api::*;

use account::keys;

pub(crate) static RT: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("tapetape-core")
        .build()
        .expect("tokio runtime")
});

pub(crate) async fn run<T: Send + 'static>(
    f: impl Future<Output = Result<T>> + Send + 'static,
) -> Result<T, CoreError> {
    Ok(RT.spawn(f).await.context("task failed")??)
}

fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("TapeTapeCore"),
    );
    #[cfg(not(target_os = "android"))]
    let _ = env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info,hyper=warn,reqwest=warn,rustls=warn"),
    )
    .try_init();
}

#[derive(uniffi::Object)]
pub struct Core {
    dir: PathBuf,
    pub(crate) net: Arc<net::Net>,
    listener: Arc<dyn EventListener>,
    accounts: Mutex<BTreeMap<u32, Arc<Account>>>,
    selected: Mutex<Option<u32>>,
}

impl Core {
    fn accounts_dir(&self) -> PathBuf {
        self.dir.join("accounts")
    }

    fn save_order(&self, ids: &[u32]) -> Result<()> {
        let list: Vec<String> = ids.iter().map(|i| i.to_string()).collect();
        std::fs::write(self.dir.join("order"), list.join(","))?;
        Ok(())
    }

    fn order(&self) -> Vec<u32> {
        std::fs::read_to_string(self.dir.join("order"))
            .unwrap_or_default()
            .split(',')
            .filter_map(|s| s.trim().parse().ok())
            .collect()
    }

    fn save_selected(&self, id: Option<u32>) -> Result<()> {
        *self.selected.lock().unwrap() = id;
        let path = self.dir.join("selected");
        match id {
            Some(id) => std::fs::write(path, id.to_string())?,
            None => {
                let _ = std::fs::remove_file(path);
            }
        }
        Ok(())
    }

    async fn pick_node(http: reqwest::Client, address: String) -> Result<String> {
        let nodes = directory::online_nodes(&http).await?;
        let mut set = tokio::task::JoinSet::new();
        for node in nodes.clone() {
            let http = http.clone();
            let account = format!("key:{address}@{node}:{}", transport::PORT);
            set.spawn(async move {
                matches!(transport::fetch_key(&http, &account).await, Ok(Some(_))).then_some(node)
            });
        }
        while let Some(found) = set.join_next().await {
            if let Ok(Some(node)) = found {
                info!("found existing identity on {node}");
                return Ok(node);
            }
        }
        nodes
            .choose(&mut rand::thread_rng())
            .cloned()
            .context("No TapeRelay node is online")
    }

    async fn add(self: Arc<Self>, mnemonic: String, node: Option<String>) -> Result<u32> {
        let secret = keys::secret_from_mnemonic(&mnemonic)?;
        let address = taperelay::address_of(&secret)?;
        if self.accounts().iter().any(|a| a.address == address) {
            bail!("This account has already been added");
        }
        let node = match node {
            Some(n) => n.trim().to_lowercase(),
            None => Self::pick_node(self.net.client(), address)
                .await
                .context("Cannot connect to the TapeRelay network")?,
        };
        let k = keys::Keys::new(&secret, &node)?;
        transport::Relay::new(self.net.clone(), k.id.clone())
            .publish_key(&k.enc.public())
            .await
            .with_context(|| format!("Cannot register on {node}"))?;
        let mut accounts = self.accounts.lock().unwrap();
        let id = accounts.keys().next_back().map_or(1, |id| id + 1);
        let account = Account::create(
            id,
            self.accounts_dir().join(id.to_string()),
            &keys::normalize_mnemonic(&mnemonic),
            &node,
            self.net.clone(),
            self.listener.clone(),
        )?;
        info!("added account {id}: {}", k.account());
        accounts.insert(id, account);
        drop(accounts);
        if self.selected_account().is_none() {
            self.save_selected(Some(id))?;
        }
        Ok(id)
    }
}

#[uniffi::export]
impl Core {
    #[uniffi::constructor]
    pub fn new(data_dir: String, listener: Box<dyn EventListener>) -> Result<Arc<Self>, CoreError> {
        init_logging();
        let dir = PathBuf::from(data_dir);
        let core = Core {
            dir: dir.clone(),
            net: Arc::new(net::Net::direct()),
            listener: Arc::from(listener),
            accounts: Mutex::new(BTreeMap::new()),
            selected: Mutex::new(None),
        };
        std::fs::create_dir_all(core.accounts_dir()).context("Cannot create data directory")?;
        core.apply_proxy();
        let mut accounts = BTreeMap::new();
        for entry in std::fs::read_dir(core.accounts_dir()).context("Cannot read data directory")? {
            let path = entry.context("Cannot read data directory")?.path();
            let Some(id) = path
                .file_name()
                .and_then(|n| n.to_str()?.parse::<u32>().ok())
            else {
                continue;
            };
            match Account::open(id, path, core.net.clone(), core.listener.clone()) {
                Ok(a) => {
                    accounts.insert(id, a);
                }
                Err(e) => error!("cannot open account {id}: {e:#}"),
            }
        }
        let selected = std::fs::read_to_string(dir.join("selected"))
            .ok()
            .and_then(|s| s.trim().parse().ok())
            .filter(|id| accounts.contains_key(id))
            .or_else(|| accounts.keys().next().copied());
        info!("core opened with {} account(s)", accounts.len());
        *core.accounts.lock().unwrap() = accounts;
        *core.selected.lock().unwrap() = selected;
        Ok(Arc::new(core))
    }

    pub fn accounts(&self) -> Vec<AccountInfo> {
        let order = self.order();
        let accounts: Vec<Arc<Account>> = self.accounts.lock().unwrap().values().cloned().collect();
        let mut list: Vec<AccountInfo> = accounts.iter().map(|a| a.info()).collect();
        list.sort_by_key(|a| {
            (
                order.iter().position(|i| *i == a.id).unwrap_or(usize::MAX),
                a.id,
            )
        });
        list
    }

    pub fn selected_account(&self) -> Option<u32> {
        *self.selected.lock().unwrap()
    }

    pub fn select_account(&self, id: u32) -> Result<(), CoreError> {
        if !self.accounts.lock().unwrap().contains_key(&id) {
            return Err(anyhow::anyhow!("Account not found").into());
        }
        Ok(self.save_selected(Some(id))?)
    }

    pub async fn nodes(&self) -> Result<Vec<String>, CoreError> {
        let http = self.net.client();
        run(async move {
            directory::online_nodes(&http)
                .await
                .context("Cannot connect to the TapeRelay network")
        })
        .await
    }

    pub fn generate_mnemonic(&self) -> String {
        keys::generate_mnemonic()
    }

    pub async fn add_account(
        self: Arc<Self>,
        mnemonic: String,
        node: Option<String>,
    ) -> Result<u32, CoreError> {
        run(self.add(mnemonic, node)).await
    }

    pub fn remove_account(&self, id: u32) -> Result<(), CoreError> {
        let account = self
            .accounts
            .lock()
            .unwrap()
            .remove(&id)
            .context("Account not found")?;
        account.stop();
        std::fs::remove_dir_all(&account.dir).context("Cannot delete account data")?;
        info!("removed account {id}");
        if self.selected_account() == Some(id) {
            let next = self.accounts.lock().unwrap().keys().next().copied();
            self.save_selected(next)?;
        }
        Ok(())
    }

    pub fn address_for_mnemonic(&self, mnemonic: String) -> Result<String, CoreError> {
        Ok(taperelay::address_of(&keys::secret_from_mnemonic(
            &mnemonic,
        )?)?)
    }

    pub fn max_attachment_size(&self) -> u64 {
        protocol::MAX_ATTACHMENT
    }

    pub fn account(&self, id: u32) -> Result<Arc<Account>, CoreError> {
        Ok(self
            .accounts
            .lock()
            .unwrap()
            .get(&id)
            .cloned()
            .context("Account not found")?)
    }
}
