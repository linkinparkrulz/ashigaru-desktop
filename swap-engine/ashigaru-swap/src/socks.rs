//! libp2p transport that dials every peer through a SOCKS5 proxy - Ashigaru's Tor.
//!
//! eigenwallet's own transport uses its embedded Tor client and falls back to plain TCP and system
//! DNS when there is none. Ashigaru runs exactly one Tor and never connects directly, so this
//! transport has no fallback: an address it cannot hand to the proxy is refused, and DNS names are
//! always resolved by the proxy (Tor) rather than locally.
//!
//! At start the engine dials every maker it knows at once: thirty or more, many of them onion
//! services that each need a fresh circuit and, under Tor 0.4.9, possibly a proof-of-work puzzle.
//! Ashigaru's Tor hung and fell over under that burst, so only a few connections are set up at a
//! time and the rest wait their turn ([`SlotLimited`]). A connection's time limit starts when it
//! gets its turn, not when it starts waiting for one: a limit that also had to cover the queue
//! would leave most onion makers no time for the circuit and the handshakes.

use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use std::time::Duration;

use futures::future::BoxFuture;
use libp2p::core::multiaddr::Protocol;
use libp2p::core::transport::{Boxed, DialOpts, ListenerId, TransportError, TransportEvent};
use libp2p::{Multiaddr, Transport};
use tokio::sync::Semaphore;

/// How many connections may be in the middle of being set up through Tor at once
pub const MAX_PENDING_DIALS: usize = 6;

#[derive(Debug, Clone)]
pub struct Socks5Transport {
    proxy: String,
}

impl Socks5Transport {
    pub fn new(proxy_host: &str, proxy_port: u16) -> Self {
        Self {
            proxy: format!("{proxy_host}:{proxy_port}"),
        }
    }
}

/// Lets only `slots` dials be set up at once, and gives each `timeout` from the moment it gets a
/// slot: for the SOCKS connect, the Tor circuit and every handshake above it (websocket, noise,
/// yamux). The slot is held until the connection is set up or has failed. The inner dial is only
/// started once it has a slot, so waiting for one costs a dial nothing of its time.
pub struct SlotLimited<O> {
    inner: Boxed<O>,
    slots: Arc<Semaphore>,
    timeout: Duration,
}

impl<O> SlotLimited<O> {
    pub fn new(inner: Boxed<O>, slots: usize, timeout: Duration) -> Self {
        Self {
            inner,
            slots: Arc::new(Semaphore::new(slots)),
            timeout,
        }
    }
}

impl<O: Send + 'static> Transport for SlotLimited<O> {
    type Output = O;
    type Error = std::io::Error;
    type Dial = BoxFuture<'static, Result<O, std::io::Error>>;
    type ListenerUpgrade = futures::future::Pending<Result<O, std::io::Error>>;

    fn listen_on(
        &mut self,
        _id: ListenerId,
        addr: Multiaddr,
    ) -> Result<(), TransportError<Self::Error>> {
        // The taker never accepts inbound connections
        Err(TransportError::MultiaddrNotSupported(addr))
    }

    fn remove_listener(&mut self, _id: ListenerId) -> bool {
        false
    }

    fn dial(
        &mut self,
        addr: Multiaddr,
        opts: DialOpts,
    ) -> Result<Self::Dial, TransportError<Self::Error>> {
        // Building the dial does no work yet: nothing is sent until it is polled, below, once it has a slot
        let dial = self.inner.dial(addr, opts)?;
        let slots = self.slots.clone();
        let timeout = self.timeout;
        Ok(Box::pin(async move {
            let _slot = slots
                .acquire_owned()
                .await
                .map_err(|e| std::io::Error::other(e.to_string()))?;
            match tokio::time::timeout(timeout, dial).await {
                Ok(result) => result,
                Err(_) => Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    format!("connection not set up within {} s of its turn", timeout.as_secs()),
                )),
            }
        }))
    }

    fn poll(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<TransportEvent<Self::ListenerUpgrade, Self::Error>> {
        Poll::Pending
    }
}

/// The host and port a multiaddr asks to reach, in the form the proxy resolves. Onion addresses
/// become `<base32>.onion`. Only the leading host and tcp port are read, so trailing protocols
/// such as `/p2p/<id>` are ignored.
pub fn proxy_target(addr: &Multiaddr) -> Option<(String, u16)> {
    let mut protocols = addr.iter();
    match (protocols.next()?, protocols.next()) {
        (Protocol::Onion3(onion), _) => {
            let host = format!("{}.onion", data_encoding_base32_lower(onion.hash()));
            Some((host, onion.port()))
        }
        (Protocol::Dns(host) | Protocol::Dns4(host) | Protocol::Dns6(host), Some(Protocol::Tcp(port))) => {
            Some((host.to_string(), port))
        }
        (Protocol::Ip4(ip), Some(Protocol::Tcp(port))) => Some((ip.to_string(), port)),
        (Protocol::Ip6(ip), Some(Protocol::Tcp(port))) => Some((ip.to_string(), port)),
        _ => None,
    }
}

/// Onion v3 hostnames are the lowercase RFC 4648 base32 encoding of the 35-byte address.
fn data_encoding_base32_lower(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 32] = b"abcdefghijklmnopqrstuvwxyz234567";
    let mut out = String::with_capacity((bytes.len() * 8).div_ceil(5));
    let mut buffer: u32 = 0;
    let mut bits = 0;
    for &byte in bytes {
        buffer = (buffer << 8) | u32::from(byte);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(ALPHABET[((buffer >> bits) & 0x1f) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(ALPHABET[((buffer << (5 - bits)) & 0x1f) as usize] as char);
    }
    out
}

impl Transport for Socks5Transport {
    type Output = libp2p::tcp::tokio::TcpStream;
    type Error = std::io::Error;
    type Dial = BoxFuture<'static, Result<Self::Output, Self::Error>>;
    type ListenerUpgrade = futures::future::Pending<Result<Self::Output, Self::Error>>;

    fn listen_on(
        &mut self,
        _id: ListenerId,
        addr: Multiaddr,
    ) -> Result<(), TransportError<Self::Error>> {
        // The taker never accepts inbound connections
        Err(TransportError::MultiaddrNotSupported(addr))
    }

    fn remove_listener(&mut self, _id: ListenerId) -> bool {
        false
    }

    fn dial(
        &mut self,
        addr: Multiaddr,
        _opts: DialOpts,
    ) -> Result<Self::Dial, TransportError<Self::Error>> {
        let (host, port) =
            proxy_target(&addr).ok_or_else(|| TransportError::MultiaddrNotSupported(addr.clone()))?;
        let proxy = self.proxy.clone();

        Ok(Box::pin(async move {
            let stream = tokio_socks::tcp::Socks5Stream::connect(proxy.as_str(), (host.as_str(), port))
                .await
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::ConnectionRefused, e))?;
            tracing::debug!(%addr, "Connected to peer through Tor");
            Ok(libp2p::tcp::tokio::TcpStream(stream.into_inner()))
        }))
    }

    fn poll(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<TransportEvent<Self::ListenerUpgrade, Self::Error>> {
        Poll::Pending
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn onion_address_maps_to_onion_hostname() {
        let addr: Multiaddr = "/onion3/3xl2zfur4tpebogsrgn3l7l2illzkhwi3755jplmycmn4q77nxsrl6qd:8888/p2p/12D3KooWGRvf7qVQDrNR5nfYD6rKrbgeTi9x8RrbdxbmsPvxL4mw"
            .parse()
            .unwrap();
        assert_eq!(
            proxy_target(&addr),
            Some((
                "3xl2zfur4tpebogsrgn3l7l2illzkhwi3755jplmycmn4q77nxsrl6qd.onion".to_string(),
                8888
            ))
        );
    }

    #[test]
    fn dns_address_is_left_for_the_proxy_to_resolve() {
        let addr: Multiaddr = "/dns4/discovery.eigenwallet.org/tcp/443".parse().unwrap();
        assert_eq!(
            proxy_target(&addr),
            Some(("discovery.eigenwallet.org".to_string(), 443))
        );
    }

    #[test]
    fn unsupported_address_is_refused_rather_than_dialled_directly() {
        let addr: Multiaddr = "/ip4/127.0.0.1/udp/1234/quic-v1".parse().unwrap();
        assert_eq!(proxy_target(&addr), None);

        let mut transport = Socks5Transport::new("127.0.0.1", 9);
        let opts = DialOpts {
            role: libp2p::core::Endpoint::Dialer,
            port_use: libp2p::core::transport::PortUse::New,
        };
        assert!(matches!(
            transport.dial(addr, opts),
            Err(TransportError::MultiaddrNotSupported(_))
        ));
    }

    #[tokio::test]
    async fn unreachable_proxy_fails_instead_of_connecting_directly() {
        // Port 9 on localhost has no SOCKS proxy. A transport with a clearnet fallback would try
        // the target itself; this one must fail.
        let mut transport = Socks5Transport::new("127.0.0.1", 9);
        let opts = DialOpts {
            role: libp2p::core::Endpoint::Dialer,
            port_use: libp2p::core::transport::PortUse::New,
        };
        let addr: Multiaddr = "/dns4/example.com/tcp/80".parse().unwrap();
        let result = transport.dial(addr, opts).unwrap().await;
        assert!(result.is_err());
    }

    /// A proxy that accepts connections and never answers, like a Tor that is busy. Returns its
    /// port, how many connections it accepted, and a way to drop them all.
    async fn silent_proxy() -> (u16, Arc<std::sync::atomic::AtomicUsize>, Arc<tokio::sync::Notify>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let accepted = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hang_up = Arc::new(tokio::sync::Notify::new());
        let (counter, hang_up_signal) = (accepted.clone(), hang_up.clone());
        tokio::spawn(async move {
            let mut held = Vec::new();
            loop {
                tokio::select! {
                    Ok((socket, _)) = listener.accept() => {
                        counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                        held.push(socket);
                    }
                    _ = hang_up_signal.notified() => held.clear(),
                }
            }
        });
        (port, accepted, hang_up)
    }

    fn opts() -> DialOpts {
        DialOpts {
            role: libp2p::core::Endpoint::Dialer,
            port_use: libp2p::core::transport::PortUse::New,
        }
    }

    #[tokio::test]
    async fn only_a_few_dials_are_set_up_at_once() {
        let (port, accepted, hang_up) = silent_proxy().await;
        let mut transport = SlotLimited::new(Socks5Transport::new("127.0.0.1", port).boxed(), 2, Duration::from_secs(60));
        for i in 0..3u16 {
            let addr: Multiaddr = format!("/dns4/maker{i}.example/tcp/9939").parse().unwrap();
            tokio::spawn(transport.dial(addr, opts()).unwrap());
        }
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert_eq!(accepted.load(std::sync::atomic::Ordering::SeqCst), 2);

        // Once a dial in progress ends, failed here, its place goes to the one waiting
        hang_up.notify_one();
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert_eq!(accepted.load(std::sync::atomic::Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn a_dials_time_starts_when_it_gets_its_slot() {
        let (port, _accepted, _hang_up) = silent_proxy().await;
        let limit = Duration::from_millis(400);
        let mut transport = SlotLimited::new(Socks5Transport::new("127.0.0.1", port).boxed(), 1, limit);
        let started = std::time::Instant::now();
        let first = tokio::spawn(transport.dial("/dns4/a.example/tcp/1".parse().unwrap(), opts()).unwrap());
        let second = tokio::spawn(transport.dial("/dns4/b.example/tcp/1".parse().unwrap(), opts()).unwrap());

        let first = first.await.unwrap();
        assert_eq!(first.err().map(|e| e.kind()), Some(std::io::ErrorKind::TimedOut));
        let second = second.await.unwrap();
        assert_eq!(second.err().map(|e| e.kind()), Some(std::io::ErrorKind::TimedOut));
        // The second waited for the first's slot, then had its own full time: not one limit for both
        assert!(started.elapsed() >= limit * 2 - Duration::from_millis(50), "{:?}", started.elapsed());
    }
}
