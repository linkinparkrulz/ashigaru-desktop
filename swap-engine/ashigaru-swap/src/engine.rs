//! Assembles eigenwallet's taker from its public parts, the way its own `Context` does
//! (`swap/src/cli/api.rs`), but with Ashigaru's Tor in place of the embedded one and Ashigaru's
//! Badbank in place of eigenwallet's internal Bitcoin wallet.

use std::collections::HashMap;
use std::panic::AssertUnwindSafe;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use futures::FutureExt as _;
use libp2p::core::muxing::StreamMuxerBox;
use libp2p::core::transport::{Boxed, Transport as _};
use libp2p::core::upgrade::Version;
use libp2p::{Multiaddr, PeerId, SwarmBuilder, identity, noise, websocket, yamux};
use serde::Deserialize;
use serde_json::{Value, json};
use swap::cli::api::tauri_bindings::TauriHandle;
use swap::cli::{Behaviour, EventLoop, EventLoopHandle, QuoteWithAddress};
use swap::database::{AccessMode, open_db};
use swap::monero::wallet::witness::DaemonWitness;
use swap::monero::{Daemon, Wallets};
use swap::network::rendezvous::XmrBtcNamespace;
use swap::protocol::bob::{self, BobState};
use swap::protocol::{Database, State};
use swap::seed::Seed;
use swap_env::env::{Config as EnvConfig, GetConfig, Mainnet};
use swap_p2p::libp2p_ext::MultiAddrVecExt;
use tokio::sync::Mutex;
use tokio::task::JoinHandle;
use uuid::Uuid;

use crate::events::AshigaruSink;
use crate::forward;
use crate::socks::Socks5Transport;
use crate::wallet::{HybridWallet, QuoteOnlyWallet};

/// Matches eigenwallet's CLI swarm (`swap/src/network/swarm.rs`)
const IDLE_CONNECTION_TIMEOUT: Duration = Duration::from_secs(60 * 2);
/// eigenwallet allows 15 seconds to connect to a maker and set up the session; building a circuit to
/// an onion maker through Tor often takes longer than that
const CONNECT_TIMEOUT: Duration = Duration::from_secs(60);
/// As in eigenwallet's `swap/src/network/transport.rs`
const MAX_NUM_STREAMS: usize = 5;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineConfig {
    /// Swap database and state live here, one directory per engine identity
    pub data_dir: PathBuf,
    /// Ashigaru's Tor SOCKS5 proxy. Required: there is no direct-connection mode.
    pub socks_host: String,
    pub socks_port: u16,
    /// 32-byte engine identity, hex. Ashigaru picks it at random and keeps it beside the swap
    /// database; a swap's own keys are random too and live in that database, not in any seed.
    pub seed_hex: String,
    /// Rendezvous points to discover makers through. Empty uses eigenwallet's defaults.
    #[serde(default)]
    pub rendezvous_points: Vec<String>,
    /// Swapping needs both of these. Without them the engine only fetches quotes.
    #[serde(default)]
    pub swap: Option<SwapConfig>,
    /// The data directories of earlier identities. Ashigaru takes a new identity once no swap is
    /// unfinished, so a finished swap's record stays behind in its identity's directory; these are
    /// read once, read-only, so past swaps stay in the list.
    #[serde(default)]
    pub past_data_dirs: Vec<PathBuf>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SwapConfig {
    /// Electrum servers, e.g. `tcp://host.onion:50001`. Reached through the SOCKS proxy.
    pub electrum_urls: Vec<String>,
    /// Monero nodes `host:port`, reached through the SOCKS proxy over plain HTTP: onion nodes.
    /// The first is the engine's node; every other one is a witness that must agree the maker's
    /// XMR is locked before the engine releases its side.
    pub monero_nodes: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartSwap {
    pub peer_id: String,
    pub multiaddr: String,
    pub btc_sats: u64,
    pub tx_lock_fee_sats: u64,
    pub xmr_address: String,
    /// A Badbank address: receives the lock's change and any refund
    pub refund_address: String,
}

struct SwapParts {
    bitcoin_wallet: Arc<dyn bitcoin_wallet::BitcoinWallet>,
    monero_wallets: Arc<Wallets>,
    monero_witnesses: Vec<Arc<DaemonWitness>>,
    /// The configured `host:port` of each node, in the same order: main first, then witnesses
    monero_nodes: Vec<String>,
}

/// Longest a node check waits for one node
const NODE_CHECK_TIMEOUT: Duration = Duration::from_secs(60);

pub struct Engine {
    event_loop: Mutex<EventLoopHandle>,
    db: Arc<dyn Database + Send + Sync>,
    env_config: EnvConfig,
    handle: TauriHandle,
    swap: Option<SwapParts>,
    running: Mutex<HashMap<Uuid, JoinHandle<()>>>,
    /// Swaps recorded under earlier identities, as `list_swaps` entries
    past_swaps: Vec<Value>,
    /// The event loop and the Tor relays, stopped with the engine
    tasks: Vec<JoinHandle<()>>,
}

impl Drop for Engine {
    /// Stops everything the engine started, so a new engine (a new identity) can take its place.
    /// A swap stopped here resumes from its database the next time an engine with that database starts.
    fn drop(&mut self) {
        for task in self.running.get_mut().values() {
            task.abort();
        }
        for task in &self.tasks {
            task.abort();
        }
    }
}

impl Engine {
    /// Mainnet only: Ashigaru has no swap testing network.
    pub async fn start(config: EngineConfig) -> Result<Self> {
        let seed_bytes: [u8; 32] = hex::decode(config.seed_hex.trim())
            .context("Swap seed is not hex")?
            .try_into()
            .map_err(|_| anyhow::anyhow!("Swap seed must be 32 bytes"))?;
        let seed = Seed::from(seed_bytes);
        let socks_proxy = format!("{}:{}", config.socks_host, config.socks_port);

        std::fs::create_dir_all(&config.data_dir).context("Could not create swap data directory")?;
        let sqlite = open_db(config.data_dir.join("sqlite"), AccessMode::ReadWrite, None)
            .await
            .context("Could not open swap database")?;

        let env_config = Mainnet::get_config();
        let handle = TauriHandle::embedded(Arc::new(AshigaruSink));

        let mut tasks = Vec::new();
        let swap = match &config.swap {
            Some(swap_config) => {
                Some(Self::swap_parts(swap_config, &config.data_dir, &socks_proxy, env_config, &mut tasks).await?)
            }
            None => None,
        };
        let wallet: Arc<dyn bitcoin_wallet::BitcoinWallet> = match &swap {
            Some(parts) => parts.bitcoin_wallet.clone(),
            None => Arc::new(QuoteOnlyWallet),
        };

        let rendezvous_addresses: Vec<Multiaddr> = if config.rendezvous_points.is_empty() {
            swap_env::defaults::default_rendezvous_points()
        } else {
            config
                .rendezvous_points
                .iter()
                .map(|a| a.parse().with_context(|| format!("Bad rendezvous address {a}")))
                .collect::<Result<_>>()?
        };
        let rendezvous_points = rendezvous_addresses.extract_peer_addresses();
        let rendezvous_peer_ids: Vec<PeerId> = rendezvous_points.iter().map(|(p, _)| *p).collect();

        let identity = seed.derive_libp2p_identity();

        // Websocket addresses (/wss) are unwrapped by WsConfig and dialled through the same proxy
        let socks = Socks5Transport::new(&config.socks_host, config.socks_port);
        let transport = websocket::WsConfig::new(socks.clone())
            .or_transport(socks)
            .boxed();
        let transport = authenticate_and_multiplex(transport, &identity)?;

        let behaviour_identity = identity.clone();
        let behaviour_db = sqlite.clone();
        let mut swarm = SwarmBuilder::with_existing_identity(identity)
            .with_tokio()
            .with_other_transport(|_| transport)?
            .with_relay_client(noise::Config::new, yamux::Config::default)?
            .with_behaviour(|_, relay| {
                Behaviour::new(
                    env_config,
                    wallet,
                    behaviour_identity,
                    relay,
                    XmrBtcNamespace::Mainnet,
                    rendezvous_peer_ids,
                    behaviour_db,
                )
            })?
            .with_swarm_config(|cfg| cfg.with_idle_connection_timeout(IDLE_CONNECTION_TIMEOUT))
            .build();

        for (peer_id, addrs) in rendezvous_points {
            for addr in addrs {
                swarm.add_peer_address(peer_id, addr);
            }
        }
        let db: Arc<dyn Database + Send + Sync> = sqlite;
        for (peer_id, addrs) in db
            .get_all_peer_addresses()
            .await
            .context("Could not read known maker addresses")?
        {
            for addr in addrs {
                swarm.add_peer_address(peer_id, addr);
            }
        }

        let (event_loop, event_loop_handle) =
            EventLoop::new(swarm, db.clone(), Some(handle.clone()), None)?;
        tasks.push(tokio::spawn(event_loop.run()));

        let past_swaps = read_past_swaps(&config.past_data_dirs, &config.data_dir).await;

        Ok(Self {
            event_loop: Mutex::new(event_loop_handle),
            db,
            env_config,
            handle,
            swap,
            running: Mutex::new(HashMap::new()),
            past_swaps,
            tasks,
        })
    }

    async fn swap_parts(
        config: &SwapConfig,
        data_dir: &std::path::Path,
        socks_proxy: &str,
        env_config: EnvConfig,
        tasks: &mut Vec<JoinHandle<()>>,
    ) -> Result<SwapParts> {
        if config.electrum_urls.is_empty() {
            bail!("No Electrum server configured");
        }
        // Tor refuses to connect to a private address ("Rejecting SOCKS request for anonymous connection
        // to private address"), so a server on the user's own network (a Dojo or Electrum server at
        // 192.168.x.x, or on this computer) is reached directly. Nothing leaves the user's own network
        // that way. Any other server is reached through Tor and nothing else.
        let electrum = if config.electrum_urls.iter().all(|url| is_local_server(url)) {
            tracing::info!("The Electrum server is on this network or this computer: connecting directly, not through Tor");
            bitcoin_wallet::Client::new(&config.electrum_urls, env_config.bitcoin_sync_interval())
                .await
                .context("Could not connect to the Electrum server on your network")?
        } else {
            bitcoin_wallet::Client::new_with_socks5(
                &config.electrum_urls,
                env_config.bitcoin_sync_interval(),
                socks_proxy.to_string(),
            )
            .await
            .context("Could not connect to the Electrum server through Tor")?
        };
        let bitcoin_wallet = Arc::new(HybridWallet::new(
            Arc::new(electrum),
            env_config.bitcoin_finality_confirmations,
        ));

        // The Monero wallet code cannot use a proxy, so each node is reached through a local relay
        // into Tor
        if config.monero_nodes.is_empty() {
            bail!("No Monero node configured");
        }
        let mut relays = Vec::new();
        for node in &config.monero_nodes {
            let (node_host, node_port) = node.rsplit_once(':').context("Monero node must be host:port")?;
            let node_port: u16 = node_port.parse().context("Monero node port is not a number")?;
            let (relay, task) =
                forward::start_tcp_relay(socks_proxy.to_string(), node_host.to_string(), node_port).await?;
            tasks.push(task);
            relays.push(relay);
        }
        let relay = relays[0];
        let monero_witnesses = relays[1..]
            .iter()
            .map(|witness| Arc::new(DaemonWitness::new(format!("http://{witness}"))))
            .collect();
        let daemon = Daemon {
            hostname: relay.ip().to_string(),
            port: relay.port(),
            ssl: false,
        };
        let monero_dir = data_dir.join("monero");
        std::fs::create_dir_all(&monero_dir).context("Could not create Monero wallet directory")?;
        let monero_wallets = Arc::new(
            Wallets::new(
                monero_dir,
                "main".to_string(),
                daemon,
                env_config.monero_network,
                false,
                None,
                None,
                env_config.monero_lock_construction_cooldown,
            )
            .await
            .context("Could not open the Monero wallet")?,
        );

        Ok(SwapParts {
            bitcoin_wallet,
            monero_wallets,
            monero_witnesses,
            monero_nodes: config.monero_nodes.clone(),
        })
    }

    fn parts(&self) -> Result<&SwapParts> {
        self.swap.as_ref().context("The engine was started for quotes only")
    }

    /// Quotes from every maker discovered so far. Discovery runs continuously in the background, so
    /// the list fills in over the first minute or two after start.
    pub async fn quotes(&self) -> Vec<QuoteWithAddress> {
        self.event_loop.lock().await.cached_quotes().borrow().clone()
    }

    /// Starts a swap with the chosen maker. Ashigaru has already picked the amount, worked out the
    /// lock transaction's fee and the user has chosen where the XMR goes; the engine asks for the
    /// lock transaction, its approval and its signature as the swap progresses.
    pub async fn start_swap(&self, request: StartSwap) -> Result<Uuid> {
        let parts = self.parts()?;
        if !self.running.lock().await.values().all(|task| task.is_finished()) {
            bail!("A swap is already running; one at a time");
        }

        let peer_id: PeerId = request.peer_id.parse().context("Bad maker peer id")?;
        let multiaddr: Multiaddr = request.multiaddr.parse().context("Bad maker address")?;
        let xmr_address = swap::monero::Address::from_str_with_unchecked_network(request.xmr_address.trim())
            .context("Not a Monero address")?;
        let refund_address = request
            .refund_address
            .parse::<bitcoin::Address<bitcoin::address::NetworkUnchecked>>()
            .context("Bad refund address")?
            .require_network(bitcoin::Network::Bitcoin)?;
        let monero_receive_pool = swap::monero::MoneroAddressPool::from(xmr_address);
        monero_receive_pool
            .assert_network(self.env_config.monero_network)
            .context("The Monero address is not a mainnet address")?;

        let swap_id = Uuid::new_v4();
        self.db.insert_peer_id(swap_id, peer_id).await?;
        self.db.insert_address(peer_id, multiaddr.clone()).await?;
        self.db.insert_monero_address_pool(swap_id, monero_receive_pool.clone()).await?;

        let mut event_loop = self.event_loop.lock().await;
        event_loop.queue_peer_address(peer_id, multiaddr).await?;
        let swap_handle = event_loop.swap_handle(peer_id, swap_id).await?;
        drop(event_loop);

        let swap = bob::Swap::new(
            self.db.clone(),
            swap_id,
            parts.bitcoin_wallet.clone(),
            parts.monero_wallets.clone(),
            self.env_config,
            swap_handle,
            monero_receive_pool,
            refund_address,
            bitcoin::Amount::from_sat(request.btc_sats),
            bitcoin::Amount::from_sat(request.tx_lock_fee_sats),
        )
        .with_event_emitter(Some(self.handle.clone()))
        .with_monero_witnesses(parts.monero_witnesses.clone());

        self.run(swap_id, swap).await;
        Ok(swap_id)
    }

    /// Picks every unfinished swap back up, e.g. after Ashigaru restarted. Swaps with timelocks
    /// running must not be left alone, so this is called whenever the engine starts.
    pub async fn resume_all(&self) -> Result<Vec<Uuid>> {
        let parts = self.parts()?;
        let mut resumed = Vec::new();
        for (peer_id, swap_id, state) in self.db.all().await? {
            let State::Bob(bob_state) = state else { continue };
            if bob::is_complete(&bob_state) || self.is_running(swap_id).await {
                continue;
            }

            match self.close_stale_lock(swap_id, &bob_state).await {
                Ok(false) => {}
                Ok(true) => continue,
                Err(e) => {
                    // Skipping the swap could orphan one whose lock is public, and with it the refund,
                    // so a swap that cannot be judged resumes as it always did
                    tracing::warn!(%swap_id, "Could not tell whether a late Bitcoin lock was published, so the swap resumes: {e:#}");
                }
            }

            let mut event_loop = self.event_loop.lock().await;
            for address in self.db.get_addresses(peer_id).await? {
                event_loop.queue_peer_address(peer_id, address).await?;
            }
            let swap_handle = event_loop.swap_handle(peer_id, swap_id).await?;
            drop(event_loop);

            let monero_receive_pool = self.db.get_monero_address_pool(swap_id).await?;
            let swap = bob::Swap::from_db(
                self.db.clone(),
                swap_id,
                parts.bitcoin_wallet.clone(),
                parts.monero_wallets.clone(),
                self.env_config,
                swap_handle,
                monero_receive_pool,
            )
            .await?
            .with_event_emitter(Some(self.handle.clone()))
            .with_monero_witnesses(parts.monero_witnesses.clone());

            self.run(swap_id, swap).await;
            resumed.push(swap_id);
        }
        Ok(resumed)
    }

    /// A swap that stopped while publishing its Bitcoin lock republishes the lock whenever the engine
    /// next starts, however late. The maker stops waiting for it after about ten minutes, so by then
    /// the lock would only tie up the BTC until the cancel timelock and cost network fees. When the
    /// deadline is past and the Bitcoin servers have never seen the lock, nobody can have it, so the
    /// swap is closed instead. Returns true when it was; an error means the servers could not say, and the
    /// swap is then resumed like any other.
    async fn close_stale_lock(&self, swap_id: Uuid, state: &BobState) -> Result<bool> {
        let BobState::BtcLockReadyToPublish {
            btc_lock_tx_signed,
            publish_deadline: Some(deadline),
            ..
        } = state
        else {
            return Ok(false);
        };
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|elapsed| elapsed.as_secs() as i64)
            .unwrap_or(0);
        if now <= *deadline {
            return Ok(false);
        }

        let lock_txid = btc_lock_tx_signed.compute_txid();
        let seen = self
            .parts()?
            .bitcoin_wallet
            .get_raw_transaction(lock_txid)
            .await
            .context("Could not ask the Bitcoin server whether the lock was published")?;
        if seen.is_some() {
            // It is public after all, so the swap carries on and refunds if it must
            return Ok(false);
        }

        tracing::warn!(%swap_id, %lock_txid, "The Bitcoin lock was never published and is too late now, so the swap is closed and no BTC is locked");
        self.db
            .insert_latest_state(swap_id, State::Bob(BobState::SafelyAborted))
            .await?;
        crate::bridge::notify(
            "swap_stopped",
            &json!({ "swapId": swap_id, "state": BobState::SafelyAborted.to_string() }),
        );
        Ok(true)
    }

    async fn is_running(&self, swap_id: Uuid) -> bool {
        self.running
            .lock()
            .await
            .get(&swap_id)
            .is_some_and(|task| !task.is_finished())
    }

    async fn run(&self, swap_id: Uuid, swap: bob::Swap) {
        let task = tokio::spawn(async move {
            // Ashigaru shows how the swap ended, so the reason travels with the notification
            // A panic deep in eigenwallet would otherwise end the task without a word
            let outcome = match AssertUnwindSafe(bob::run(swap)).catch_unwind().await {
                Err(panic) => {
                    let error = format!("The swap engine hit an internal error: {}", crate::panic_message(&*panic));
                    tracing::error!(%swap_id, "{error}");
                    json!({ "swapId": swap_id, "error": error })
                }
                Ok(Ok(state)) => {
                    tracing::info!(%swap_id, %state, "Swap finished");
                    json!({ "swapId": swap_id, "state": state.to_string() })
                }
                Ok(Err(e)) => {
                    tracing::error!(%swap_id, "Swap stopped with an error: {e:#}");
                    json!({ "swapId": swap_id, "error": format!("{e:#}") })
                }
            };
            crate::bridge::notify("swap_stopped", &outcome);
        });
        self.running.lock().await.insert(swap_id, task);
    }

    /// Asks each Monero node for its height through its Tor relay, so a dead or unreachable node is
    /// found before a swap depends on it
    pub async fn check_nodes(&self) -> Value {
        let Ok(parts) = self.parts() else {
            return json!([]);
        };
        let main = async {
            match tokio::time::timeout(NODE_CHECK_TIMEOUT, parts.monero_wallets.direct_rpc_block_height()).await {
                Ok(Ok(height)) => json!({ "ok": true, "height": height }),
                Ok(Err(e)) => json!({ "ok": false, "error": format!("{e:#}") }),
                Err(_) => json!({ "ok": false, "error": "No answer within 60 seconds" }),
            }
        };
        let witnesses = futures::future::join_all(parts.monero_witnesses.iter().map(|witness| async move {
            match tokio::time::timeout(NODE_CHECK_TIMEOUT, witness.block_height()).await {
                Ok(Ok(height)) => json!({ "ok": true, "height": height }),
                Ok(Err(e)) => json!({ "ok": false, "error": format!("{e:#}") }),
                Err(_) => json!({ "ok": false, "error": "No answer within 60 seconds" }),
            }
        }));
        // The Bitcoin server is where the lock is published and watched. A swap that cannot reach it
        // stops at the worst moment (after the user approved), so it is checked first
        let bitcoin = async {
            match tokio::time::timeout(NODE_CHECK_TIMEOUT, parts.bitcoin_wallet.health_check()).await {
                Ok(Ok(())) => json!({ "ok": true }),
                Ok(Err(e)) => json!({ "ok": false, "error": format!("{e:#}") }),
                Err(_) => json!({ "ok": false, "error": "No answer within 60 seconds" }),
            }
        };
        let (bitcoin, main, witnesses) = tokio::join!(bitcoin, main, witnesses);
        let mut results: Vec<Value> = vec![{
            let mut result = bitcoin;
            result["node"] = json!("Bitcoin server (Electrum)");
            result
        }];
        results.extend(
            std::iter::once(main)
                .chain(witnesses)
                .zip(&parts.monero_nodes)
                .map(|(mut result, node)| {
                    result["node"] = json!(node);
                    result
                }),
        );
        Value::Array(results)
    }

    pub async fn list_swaps(&self) -> Result<Value> {
        let running = self.running.lock().await;
        let mut swaps = Vec::new();
        for (peer_id, swap_id, state) in self.db.all().await? {
            let State::Bob(bob_state) = state else { continue };
            let running = running.get(&swap_id).is_some_and(|t| !t.is_finished());
            swaps.push(swap_entry(self.db.as_ref(), peer_id, swap_id, &bob_state, running, false).await);
        }
        for past in &self.past_swaps {
            if !swaps.iter().any(|swap| swap["swapId"] == past["swapId"]) {
                swaps.push(past.clone());
            }
        }
        Ok(Value::Array(swaps))
    }

    /// Takes the BTC back once the cancel timelock has expired. Fails before then.
    pub async fn cancel_and_refund(&self, swap_id: Uuid) -> Result<String> {
        let parts = self.parts()?;
        if self.is_running(swap_id).await {
            bail!("The swap is still running; it refunds by itself once the timelock allows");
        }
        let state: BobState =
            swap::cli::cancel_and_refund(swap_id, parts.bitcoin_wallet.clone(), self.db.clone()).await?;
        Ok(state.to_string())
    }
}

/// eigenwallet's `swap::network::transport::authenticate_and_multiplex` with a timeout long enough
/// for onion makers
fn authenticate_and_multiplex<T>(transport: Boxed<T>, identity: &identity::Keypair) -> Result<Boxed<(PeerId, StreamMuxerBox)>>
where
    T: futures::AsyncRead + futures::AsyncWrite + Unpin + Send + 'static,
{
    let mut multiplex = yamux::Config::default();
    multiplex.set_max_num_streams(MAX_NUM_STREAMS);
    Ok(transport
        .upgrade(Version::V1)
        .authenticate(noise::Config::new(identity)?)
        .multiplex(multiplex)
        .timeout(CONNECT_TIMEOUT)
        .map(|(peer, muxer), _| (peer, StreamMuxerBox::new(muxer)))
        .boxed())
}

/// One `list_swaps` entry. `past` marks a swap recorded under an earlier identity: it can only be
/// shown, as no engine holds its database for writing.
async fn swap_entry(db: &(dyn Database + Send + Sync), peer_id: PeerId, swap_id: Uuid, state: &BobState, running: bool, past: bool) -> Value {
    json!({
        "swapId": swap_id,
        "peerId": peer_id.to_string(),
        "state": state.to_string(),
        "complete": bob::is_complete(state),
        "running": running,
        "past": past,
        "startedAt": db.get_swap_start_date(swap_id).await.ok(),
    })
}

/// Reads the swaps in earlier identities' databases. A directory that cannot be read is skipped:
/// the list is for showing, and the current swaps must not depend on it.
async fn read_past_swaps(dirs: &[PathBuf], current: &std::path::Path) -> Vec<Value> {
    let mut swaps = Vec::new();
    for dir in dirs {
        let sqlite = dir.join("sqlite");
        // open_db creates a missing database, so only open one that exists
        if dir == current || !sqlite.exists() {
            continue;
        }
        let db = match open_db(&sqlite, AccessMode::ReadOnly, None).await {
            Ok(db) => db,
            Err(e) => {
                tracing::warn!(dir = %dir.display(), "Could not read past swaps: {e:#}");
                continue;
            }
        };
        match db.all().await {
            Ok(all) => {
                for (peer_id, swap_id, state) in all {
                    let State::Bob(bob_state) = state else { continue };
                    swaps.push(swap_entry(db.as_ref(), peer_id, swap_id, &bob_state, false, true).await);
                }
            }
            Err(e) => tracing::warn!(dir = %dir.display(), "Could not read past swaps: {e:#}"),
        }
    }
    swaps
}

/// True when an Electrum URL (`tcp://host:port`, `ssl://host:port`) names a machine on the user's own
/// network or this computer: `localhost`, a `.local` name, or a loopback, private or link-local address.
/// Onion and ordinary internet hosts are not local.
pub fn is_local_server(url: &str) -> bool {
    use std::net::IpAddr;

    let rest = url.split_once("://").map_or(url, |(_, rest)| rest);
    let authority = rest.split(['/', '?']).next().unwrap_or(rest);
    let authority = authority.rsplit_once('@').map_or(authority, |(_, host)| host);
    let host = match authority.strip_prefix('[') {
        // [::1]:50001
        Some(bracketed) => bracketed.split(']').next().unwrap_or(bracketed),
        None => authority.rsplit_once(':').map_or(authority, |(host, _)| host),
    }
    .to_ascii_lowercase();

    if host == "localhost" || host.ends_with(".local") || host.ends_with(".localhost") {
        return true;
    }
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => ip.is_loopback() || ip.is_private() || ip.is_link_local(),
        Ok(IpAddr::V6(ip)) => {
            let first = ip.segments()[0];
            ip.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
        Err(_) => false,
    }
}

#[cfg(test)]
mod local_server_tests {
    use super::is_local_server;

    #[test]
    fn servers_on_the_users_own_network_are_local() {
        for url in [
            "tcp://127.0.0.1:50001",
            "ssl://localhost:50002",
            "tcp://192.168.1.20:50001",
            "ssl://10.0.0.5:50002",
            "tcp://172.16.4.2:50001",
            "tcp://[::1]:50001",
            "tcp://[fd12:3456::1]:50001",
            "tcp://169.254.1.9:50001",
            "ssl://dojo.local:50002",
            "tcp://user@192.168.0.2:50001",
            "192.168.1.20:50001",
        ] {
            assert!(is_local_server(url), "{url} is local");
        }
    }

    #[test]
    fn onion_and_internet_servers_are_not_local() {
        for url in [
            "tcp://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion:50001",
            "ssl://electrum.blockstream.info:50002",
            "tcp://8.8.8.8:50001",
            "tcp://172.32.0.1:50001",
            "tcp://[2001:db8::1]:50001",
            "ssl://localhost.example.com:50002",
            "",
        ] {
            assert!(!is_local_server(url), "{url} is not local");
        }
    }
}
