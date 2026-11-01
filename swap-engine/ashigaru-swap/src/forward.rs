//! Local forwarder that carries the engine's Monero node traffic over Ashigaru's Tor.
//!
//! eigenwallet reaches the Monero node two ways: the embedded wallet (wallet2, over FFI) and a
//! direct RPC client used for health checks, block height and transaction publishing. Neither can
//! be pointed at a SOCKS proxy from outside. Instead the engine is told the node lives on a
//! localhost port, and every connection to that port is relayed through the SOCKS proxy to the
//! real node. Name resolution happens inside Tor, so onion nodes work.
//!
//! The relay is plain TCP, so the node is reached over plain HTTP. That is the normal way to use an
//! onion node, where Tor itself encrypts and authenticates the connection.

use std::net::SocketAddr;
use std::time::Duration;

use anyhow::{Context, Result};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

/// Starts relaying a fresh localhost port to `target_host:target_port` through the SOCKS proxy.
/// Returns the local address to give the engine as its Monero node, and the relay's task: it runs
/// until aborted or the runtime stops.
pub async fn start_tcp_relay(
    socks_proxy: String,
    target_host: String,
    target_port: u16,
) -> Result<(SocketAddr, JoinHandle<()>)> {
    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .context("Could not open the local Monero relay port")?;
    let local = listener.local_addr()?;

    let task = tokio::spawn(async move {
        loop {
            let (inbound, _) = match listener.accept().await {
                Ok(connection) => connection,
                Err(e) => {
                    tracing::warn!(error = %e, "Monero relay stopped accepting connections");
                    return;
                }
            };
            let socks_proxy = socks_proxy.clone();
            let target_host = target_host.clone();
            tokio::spawn(async move {
                if let Err(e) = relay(inbound, &socks_proxy, &target_host, target_port, CONNECT_TIMEOUT).await {
                    tracing::debug!(error = %e, "Monero relay connection ended with an error");
                }
            });
        }
    });

    Ok((local, task))
}

/// How long Tor gets to reach the node. Onion circuits usually take seconds; without a limit a node
/// that never answers hangs the swap at its first step instead of failing it with a reason. Kept
/// under eigenwallet's 45s retry window for the node check, so a slow first attempt gets a retry.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(40);

/// The first byte of a TLS handshake record
const TLS_HANDSHAKE: u8 = 0x16;
/// How long to wait for a client's first byte before relaying regardless
const FIRST_BYTE_WAIT: Duration = Duration::from_secs(10);

async fn relay(
    mut inbound: TcpStream,
    socks_proxy: &str,
    target_host: &str,
    target_port: u16,
    connect_timeout: Duration,
) -> Result<()> {
    // Every node is reached as plain HTTP, but wallet2 opens its first connection with SSL
    // "autodetect": it sends a TLS handshake and falls back to plain HTTP only once that fails.
    // Passed on through Tor the handshake just hangs until wallet2 gives up 20 seconds later, so
    // refuse it here at once, before spending a Tor stream on it.
    let mut first = [0u8; 1];
    if let Ok(Ok(1)) = tokio::time::timeout(FIRST_BYTE_WAIT, inbound.peek(&mut first)).await
        && first[0] == TLS_HANDSHAKE
    {
        tracing::debug!("Refused a TLS handshake on the plain HTTP relay to {target_host}");
        return Ok(());
    }

    let outbound = tokio::time::timeout(
        connect_timeout,
        tokio_socks::tcp::Socks5Stream::connect(socks_proxy, (target_host, target_port)),
    )
    .await
    .with_context(|| format!("Timed out reaching the Monero node {target_host} through Tor"))?
    .with_context(|| format!("Could not reach the Monero node {target_host} through Tor"))?;
    let mut outbound = outbound.into_inner();
    tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// Minimal SOCKS5 server (no auth, CONNECT by domain) that records the requested target and
    /// connects it to `upstream` instead.
    async fn fake_socks(upstream: SocketAddr) -> (SocketAddr, tokio::sync::oneshot::Receiver<(String, u16)>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (tx, rx) = tokio::sync::oneshot::channel();
        tokio::spawn(async move {
            let (mut client, _) = listener.accept().await.unwrap();
            let mut greeting = [0u8; 3];
            client.read_exact(&mut greeting).await.unwrap();
            client.write_all(&[5, 0]).await.unwrap();
            let mut head = [0u8; 4];
            client.read_exact(&mut head).await.unwrap();
            assert_eq!(head[3], 3, "the relay must hand the host name to the proxy, not resolve it");
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await.unwrap();
            let mut host = vec![0u8; len[0] as usize];
            client.read_exact(&mut host).await.unwrap();
            let mut port = [0u8; 2];
            client.read_exact(&mut port).await.unwrap();
            tx.send((String::from_utf8(host).unwrap(), u16::from_be_bytes(port))).unwrap();
            client.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).await.unwrap();
            let mut server = TcpStream::connect(upstream).await.unwrap();
            tokio::io::copy_bidirectional(&mut client, &mut server).await.ok();
        });
        (addr, rx)
    }

    #[tokio::test]
    async fn relays_through_the_proxy_by_host_name() {
        let echo = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let echo_addr = echo.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut s, _) = echo.accept().await.unwrap();
            let mut buf = [0u8; 4];
            s.read_exact(&mut buf).await.unwrap();
            s.write_all(&buf).await.unwrap();
        });

        let (proxy, requested) = fake_socks(echo_addr).await;
        let (local, _relay) = start_tcp_relay(proxy.to_string(), "examplenode.onion".into(), 18089)
            .await
            .unwrap();

        let mut client = TcpStream::connect(local).await.unwrap();
        client.write_all(b"ping").await.unwrap();
        let mut reply = [0u8; 4];
        client.read_exact(&mut reply).await.unwrap();

        assert_eq!(&reply, b"ping");
        assert_eq!(requested.await.unwrap(), ("examplenode.onion".to_string(), 18089));
    }

    /// A proxy that accepts but never answers must not hang the relay
    #[tokio::test]
    async fn unanswered_proxy_times_out() {
        let silent = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = silent.local_addr().unwrap();
        tokio::spawn(async move {
            let _held = silent.accept().await;
            std::future::pending::<()>().await;
        });

        let client_side = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client_addr = client_side.local_addr().unwrap();
        let mut client = TcpStream::connect(client_addr).await.unwrap();
        // HTTP clients send their request at once, before the relay connects onwards
        client.write_all(b"GET /get_info HTTP/1.1\r\n").await.unwrap();
        let (inbound, _) = client_side.accept().await.unwrap();

        let started = std::time::Instant::now();
        let result = relay(inbound, &proxy.to_string(), "silentnode.onion", 18081, Duration::from_millis(300)).await;
        let error = result.unwrap_err().to_string();
        assert!(error.contains("Timed out"), "{error}");
        assert!(started.elapsed() < Duration::from_secs(5));
    }

    /// wallet2's SSL autodetect must be refused at once, without reaching Tor
    #[tokio::test]
    async fn tls_handshake_is_refused_without_reaching_tor() {
        let proxy = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = proxy.local_addr().unwrap();
        let contacted = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let flag = contacted.clone();
        tokio::spawn(async move {
            if proxy.accept().await.is_ok() {
                flag.store(true, std::sync::atomic::Ordering::SeqCst);
            }
        });
        let (local, _relay) = start_tcp_relay(proxy_addr.to_string(), "examplenode.onion".into(), 18089)
            .await
            .unwrap();

        let mut client = TcpStream::connect(local).await.unwrap();
        // The start of a TLS ClientHello record
        client.write_all(&[0x16, 0x03, 0x01, 0x02, 0x00]).await.unwrap();
        let mut buf = [0u8; 8];
        let read = tokio::time::timeout(Duration::from_secs(1), client.read(&mut buf)).await;
        assert!(matches!(read, Ok(Ok(0)) | Ok(Err(_))), "the relay should hang up: {read:?}");
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(!contacted.load(std::sync::atomic::Ordering::SeqCst));
    }
}

