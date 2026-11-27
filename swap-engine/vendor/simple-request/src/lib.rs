#![cfg_attr(docsrs, feature(doc_auto_cfg))]
#![doc = include_str!("../README.md")]

use std::sync::Arc;

use tokio::sync::Mutex;

use tower_service::Service as TowerService;
#[cfg(feature = "tls")]
use hyper_rustls::{HttpsConnectorBuilder, HttpsConnector};
use hyper::{Uri, header::HeaderValue, body::Bytes, client::conn::http1::SendRequest};
use hyper_util::{
  rt::tokio::TokioExecutor,
  client::legacy::{Client as HyperClient, connect::HttpConnector},
};
pub use hyper;

mod request;
pub use request::*;

mod response;
pub use response::*;

#[derive(Debug)]
pub enum Error {
  InvalidUri,
  MissingHost,
  InconsistentHost,
  ConnectionError(Box<dyn Send + Sync + std::error::Error>),
  Hyper(hyper::Error),
  HyperUtil(hyper_util::client::legacy::Error),
}

#[cfg(not(feature = "tls"))]
type Connector = HttpConnector;
#[cfg(feature = "tls")]
type Connector = HttpsConnector<HttpConnector>;

#[derive(Clone, Debug)]
enum Connection {
  ConnectionPool(HyperClient<Connector, Full<Bytes>>),
  Connection {
    connector: Connector,
    host: Uri,
    connection: Arc<Mutex<Option<SendRequest<Full<Bytes>>>>>,
  },
}

#[derive(Clone, Debug)]
pub struct Client {
  connection: Connection,
}

impl Client {
  #[allow(clippy::unnecessary_wraps)]
  fn connector() -> Result<Connector, Error> {
    let mut res = HttpConnector::new();
    res.set_keepalive(Some(core::time::Duration::from_secs(60)));
    res.set_nodelay(true);
    res.set_reuse_address(true);

    #[cfg(feature = "tls")]
    res.enforce_http(false);
    #[cfg(feature = "tls")]
    let https = HttpsConnectorBuilder::new().with_native_roots();
    #[cfg(all(feature = "tls", not(feature = "webpki-roots")))]
    let https = https.map_err(|e| {
      Error::ConnectionError(
        format!("couldn't load system's SSL root certificates and webpki-roots unavilable: {e:?}")
          .into(),
      )
    })?;
    // Fallback to `webpki-roots` if present
    #[cfg(all(feature = "tls", feature = "webpki-roots"))]
    let https = https.unwrap_or(HttpsConnectorBuilder::new().with_webpki_roots());
    #[cfg(feature = "tls")]
    let res = https.https_or_http().enable_http1().wrap_connector(res);

    Ok(res)
  }

  pub fn with_connection_pool() -> Result<Client, Error> {
    Ok(Client {
      connection: Connection::ConnectionPool(
        HyperClient::builder(TokioExecutor::new())
          .pool_idle_timeout(core::time::Duration::from_secs(60))
          .build(Self::connector()?),
      ),
    })
  }

  pub fn without_connection_pool(host: &str) -> Result<Client, Error> {
    Ok(Client {
      connection: Connection::Connection {
        connector: Self::connector()?,
        host: {
          let uri: Uri = host.parse().map_err(|_| Error::InvalidUri)?;
          if uri.host().is_none() {
            Err(Error::MissingHost)?;
          };
          uri
        },
        connection: Arc::new(Mutex::new(None)),
      },
    })
  }

  pub async fn request<R: Into<Request>>(&self, request: R) -> Result<Response<'_>, Error> {
    let request: Request = request.into();
    let Request { mut request, response_size_limit } = request;
    if let Some(header_host) = request.headers().get(hyper::header::HOST) {
      match &self.connection {
        Connection::ConnectionPool(_) => {}
        Connection::Connection { host, .. } => {
          if header_host.to_str().map_err(|_| Error::InvalidUri)? != host.host().unwrap() {
            Err(Error::InconsistentHost)?;
          }
        }
      }
    } else {
      let host = match &self.connection {
        Connection::ConnectionPool(_) => {
          request.uri().host().ok_or(Error::MissingHost)?.to_string()
        }
        Connection::Connection { host, .. } => {
          let host_str = host.host().unwrap();
          if let Some(uri_host) = request.uri().host() {
            if host_str != uri_host {
              Err(Error::InconsistentHost)?;
            }
          }
          host_str.to_string()
        }
      };
      request
        .headers_mut()
        .insert(hyper::header::HOST, HeaderValue::from_str(&host).map_err(|_| Error::InvalidUri)?);
    }

    let response = match &self.connection {
      Connection::ConnectionPool(client) => {
        client.request(request).await.map_err(Error::HyperUtil)?
      }
      Connection::Connection { connector, host, connection } => {
        let mut connection_lock = connection.lock().await;

        // Ashigaru: a kept-alive connection the node (or Tor) has closed since the last request is
        // replaced and the request sent on the new one, instead of failing with `ChannelClosed`. A
        // request is only sent again when hyper hands it back, which means none of it was sent.
        for attempt in 0 .. 2 {
          let last_attempt = attempt == 1;

          // If there's not a connection, or the one held has closed...
          if connection_lock.as_ref().is_none_or(SendRequest::is_closed) {
            let call_res = connector.clone().call(host.clone()).await;
            #[cfg(not(feature = "tls"))]
            let call_res = call_res.map_err(|e| Error::ConnectionError(format!("{e:?}").into()));
            #[cfg(feature = "tls")]
            let call_res = call_res.map_err(Error::ConnectionError);
            let (requester, connection) =
              hyper::client::conn::http1::handshake(call_res?).await.map_err(Error::Hyper)?;
            // This will die when we drop the requester, so we don't need to track an AbortHandle
            // for it
            tokio::spawn(connection);
            *connection_lock = Some(requester);
          }

          let connection = connection_lock.as_mut().expect("lock over the connection was poisoned");
          if let Err(e) = connection.ready().await {
            // Nothing was sent: drop this connection and use a new one
            *connection_lock = None;
            if last_attempt {
              return Err(Error::Hyper(e));
            }
            continue;
          }

          match connection.try_send_request(request).await {
            Ok(response) => {
              return Ok(Response { response, size_limit: response_size_limit, client: self });
            }
            Err(mut e) => {
              // Since this connection has been put into an error state, drop it
              *connection_lock = None;
              match e.take_message() {
                Some(unsent) if !last_attempt => request = unsent,
                _ => return Err(Error::Hyper(e.into_error())),
              }
            }
          }
        }
        unreachable!("the last attempt returns or fails")
      }
    };

    Ok(Response { response, size_limit: response_size_limit, client: self })
  }
}

#[cfg(test)]
mod tests {
  use std::sync::atomic::{AtomicUsize, Ordering};

  use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
  };

  use super::*;

  /// Reads one request's head and its `Content-Length` body
  async fn read_request(stream: &mut TcpStream) -> Vec<u8> {
    let mut seen = Vec::new();
    let mut buf = [0u8; 1024];
    loop {
      let n = stream.read(&mut buf).await.unwrap();
      assert!(n > 0, "the client closed before sending a whole request");
      seen.extend_from_slice(&buf[.. n]);
      if let Some(end) = seen.windows(4).position(|w| w == b"\r\n\r\n") {
        let head = String::from_utf8_lossy(&seen[.. end]).to_lowercase();
        let length = head
          .lines()
          .find_map(|l| l.strip_prefix("content-length:"))
          .map_or(0, |l| l.trim().parse::<usize>().unwrap());
        while seen.len() < end + 4 + length {
          let n = stream.read(&mut buf).await.unwrap();
          seen.extend_from_slice(&buf[.. n]);
        }
        return seen;
      }
    }
  }

  /// A node that takes one request per connection and then closes it, as a node or Tor does
  /// with a kept-alive connection that sat idle. `answer` false closes without answering.
  async fn node(answer: bool) -> (String, Arc<AtomicUsize>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let connections = Arc::new(AtomicUsize::new(0));
    let counted = connections.clone();
    tokio::spawn(async move {
      loop {
        let (mut stream, _) = listener.accept().await.unwrap();
        counted.fetch_add(1, Ordering::SeqCst);
        read_request(&mut stream).await;
        if answer {
          stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").await.unwrap();
          stream.flush().await.unwrap();
        }
        drop(stream);
      }
    });
    (url, connections)
  }

  fn post(url: &str) -> Request {
    hyper::Request::post(format!("{url}/json_rpc")).body(Full::new(Bytes::from_static(b"{}"))).unwrap().into()
  }

  async fn body(client: &Client, url: &str) -> Result<Vec<u8>, Error> {
    let mut res = Vec::new();
    std::io::Read::read_to_end(&mut client.request(post(url)).await?.body().await?, &mut res).unwrap();
    Ok(res)
  }

  #[tokio::test]
  async fn a_connection_the_node_closed_is_replaced_instead_of_failing_the_next_request() {
    let (url, connections) = node(true).await;
    let client = Client::without_connection_pool(&url).unwrap();

    assert_eq!(body(&client, &url).await.unwrap(), b"ok");
    // Let the client see the node close the kept-alive connection
    tokio::time::sleep(core::time::Duration::from_millis(200)).await;

    for _ in 0 .. 3 {
      assert_eq!(body(&client, &url).await.unwrap(), b"ok");
      tokio::time::sleep(core::time::Duration::from_millis(200)).await;
    }
    assert_eq!(connections.load(Ordering::SeqCst), 4);
  }

  #[tokio::test]
  async fn a_request_that_was_sent_is_not_sent_again() {
    let (url, connections) = node(false).await;
    let client = Client::without_connection_pool(&url).unwrap();

    assert!(body(&client, &url).await.is_err());
    tokio::time::sleep(core::time::Duration::from_millis(200)).await;
    assert_eq!(connections.load(Ordering::SeqCst), 1);
  }
}
