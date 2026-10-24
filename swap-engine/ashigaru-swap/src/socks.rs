//! libp2p transport that dials every peer through a SOCKS5 proxy - Ashigaru's Tor.
//!
//! eigenwallet's own transport uses its embedded Tor client and falls back to plain TCP and system
//! DNS when there is none. Ashigaru runs exactly one Tor and never connects directly, so this
//! transport has no fallback: an address it cannot hand to the proxy is refused, and DNS names are
//! always resolved by the proxy (Tor) rather than locally.

use std::pin::Pin;
use std::task::{Context, Poll};

use futures::future::BoxFuture;
use libp2p::core::multiaddr::Protocol;
use libp2p::core::transport::{DialOpts, ListenerId, TransportError, TransportEvent};
use libp2p::{Multiaddr, Transport};

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
}
