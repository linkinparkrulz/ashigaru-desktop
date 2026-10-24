//! Assembles eigenwallet's taker networking from its public parts, the way its own `Context` does
//! (`swap/src/cli/api.rs`), but with Ashigaru's Tor in place of the embedded one.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use libp2p::core::transport::Transport as _;
use libp2p::{Multiaddr, PeerId, SwarmBuilder, noise, websocket, yamux};
use serde::Deserialize;
use swap::cli::{Behaviour, EventLoop, EventLoopHandle, QuoteWithAddress};
use swap::database::{AccessMode, open_db};
use swap::network::rendezvous::XmrBtcNamespace;
use swap::network::transport::authenticate_and_multiplex;
use swap::protocol::Database as _;
use swap::seed::Seed;
use swap_env::env::{GetConfig, Mainnet};
use swap_p2p::libp2p_ext::MultiAddrVecExt;

use crate::socks::Socks5Transport;
use crate::wallet::QuoteOnlyWallet;

/// Matches eigenwallet's CLI swarm (`swap/src/network/swarm.rs`)
const IDLE_CONNECTION_TIMEOUT: Duration = Duration::from_secs(60 * 2);

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineConfig {
    /// Swap database and state live here, one directory per Ashigaru wallet
    pub data_dir: PathBuf,
    /// Ashigaru's Tor SOCKS5 proxy. Required: there is no direct-connection mode.
    pub socks_host: String,
    pub socks_port: u16,
    /// 32-byte swap seed, hex. Derived by Ashigaru from the wallet seed so swaps can be recovered.
    pub seed_hex: String,
    /// Rendezvous points to discover makers through. Empty uses eigenwallet's defaults.
    #[serde(default)]
    pub rendezvous_points: Vec<String>,
}

pub struct Engine {
    event_loop: EventLoopHandle,
}

impl Engine {
    /// Mainnet only: Ashigaru has no swap testing network.
    pub async fn start(config: EngineConfig) -> Result<Self> {
        let seed_bytes: [u8; 32] = hex::decode(config.seed_hex.trim())
            .context("Swap seed is not hex")?
            .try_into()
            .map_err(|_| anyhow::anyhow!("Swap seed must be 32 bytes"))?;
        let seed = Seed::from(seed_bytes);

        std::fs::create_dir_all(&config.data_dir).context("Could not create swap data directory")?;
        let db = open_db(config.data_dir.join("sqlite"), AccessMode::ReadWrite, None)
            .await
            .context("Could not open swap database")?;

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

        let env_config = Mainnet::get_config();
        let identity = seed.derive_libp2p_identity();

        // Websocket addresses (/wss) are unwrapped by WsConfig and dialled through the same proxy
        let socks = Socks5Transport::new(&config.socks_host, config.socks_port);
        let transport = websocket::WsConfig::new(socks.clone())
            .or_transport(socks)
            .boxed();
        let transport = authenticate_and_multiplex(transport, &identity)?;

        let wallet: Arc<dyn bitcoin_wallet::BitcoinWallet> = Arc::new(QuoteOnlyWallet);
        let behaviour_identity = identity.clone();
        let behaviour_db = db.clone();
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
        for (peer_id, addrs) in db
            .get_all_peer_addresses()
            .await
            .context("Could not read known maker addresses")?
        {
            for addr in addrs {
                swarm.add_peer_address(peer_id, addr);
            }
        }

        let (event_loop, event_loop_handle) = EventLoop::new(swarm, db, None, None)?;
        tokio::spawn(event_loop.run());

        Ok(Self {
            event_loop: event_loop_handle,
        })
    }

    /// Quotes from every maker discovered so far. Discovery runs continuously in the background, so
    /// the list fills in over the first minute or two after start.
    pub fn quotes(&self) -> Vec<QuoteWithAddress> {
        self.event_loop.cached_quotes().borrow().clone()
    }
}
