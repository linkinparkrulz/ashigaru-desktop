# Swap engine spike: embedding eigenwallet for Badbank → XMR

*Draft, 2026-10-24. Read against [eigenwallet/core](https://github.com/eigenwallet/core) tag `4.16.1`
(GPL-3.0). Paths below are relative to that repository. Nothing was built; this is a source reading.*

## Requirements (decided)

1. **Direct funding.** Ashigaru signs Badbank UTXOs straight into the swap's BTC lock transaction.
   There is no hop through another wallet.
2. **One Tor.** Every connection goes through Ashigaru's own Tor: peer-to-peer, Electrum and Monero.
3. **Takers of market makers.** We swap with eigenwallet's existing ASB (maker) network.
4. **All platforms on day one,** inside the normal installers.

## Verdict

**Route A, embedding eigenwallet's swap engine as a native library, is viable with four contained
patches.** Requirement 1, the deciding question, is already supported by eigenwallet's design and
needs no protocol change. The patches are listed under *Patches*. All four are small, and three of
them look upstreamable.

## 1. External lock funding — supported by design

- **The lock transaction is built through a wallet trait.** The taker's state machine creates it with
  `TxLock::new(wallet: &dyn BitcoinWallet, amount, spending_fee, A, B, change)`
  (`swap-core/src/bitcoin/lock.rs:23`). That function asks the wallet for a PSBT paying the 2-of-2 lock
  address: `wallet.send_to_address(address, amount, spending_fee, Some(change))`.
- **The taker's swap runs entirely on a trait object.** `bob::Swap` holds
  `bitcoin_wallet: Arc<dyn BitcoinWallet>` (`swap/src/protocol/bob.rs:22-32`), and
  `bob::run(swap)` (`swap/src/protocol/bob/swap.rs:74`) uses only that trait. Ashigaru can supply
  its own implementation of `BitcoinWallet` (`bitcoin-wallet/src/lib.rs:15-73`):
  - `send_to_address` → Ashigaru selects Badbank UTXOs and returns an unsigned PSBT paying the lock
    address.
  - `sign_and_finalize` → Ashigaru signs with the Badbank keys, reusing the signing path the Tx0
    flow already uses.
- **The order is safe.** All signatures, including the presigned cancel/refund transactions, are
  exchanged during swap setup (`BobState::Started` → `setup_swap` → `SwapSetupCompleted`,
  `swap.rs:146-226`). Only then is the lock signed (`state2.lock_btc()` + `sign_and_finalize`,
  `swap.rs:238-242`). The user is then asked for approval with the final amounts
  (`request_bitcoin_approval`, `swap.rs:260-265`). Only after that is it broadcast
  (`BtcLockReadyToPublish` → `ensure_broadcasted(.., "lock")`, `swap.rs:327-345`).
- **The maker's side checks the transaction.** `TxLock::from_psbt` checks that the PSBT has at most
  2 outputs and exactly one output paying the agreed amount to the shared descriptor
  (`lock.rs:51-95`). A lock built by Ashigaru therefore has to follow the same shape: one lock output
  and at most one change output.

**Consequence:** the hybrid wallet needs no hop. Ashigaru does the funding and signing. The chain
side of the trait (`ensure_broadcasted`, `subscribe_to`, `status_of_script`, `get_raw_transaction`,
`estimate_fee`, `sync`) stays on eigenwallet's Electrum client, pointed at Ashigaru's Electrum
server through Tor. Fee estimates should come from the same source Ashigaru already uses.

## 2. Refund and change destination — one address, set per swap

- The taker passes one `change_address` when starting a swap. It becomes the swap's
  `bitcoin_refund_address` (`swap.rs:222`).
- `TxLock::new` uses that same address for the lock transaction's change (`swap-machine/src/bob/mod.rs:500-507`).
- Passing a fresh **Badbank address** therefore sends both the lock change and every refund path
  (full, partial, early refund) back into Ashigaru. No internal wallet is involved.

**Protocol fact the UI must show (since 4.0.0, `CHANGELOG.md` §4.0.0):**
- Refunds can be *partial*. A maker may require an "anti-spam deposit" of up to 20% of the BTC,
  which it can withhold for 30 minutes after a refund.
- Each quote states its guaranteed refund percentage. The swap carries `btc_amnesty_amount` and
  `has_full_refund_signature`.
- The cancel timelock is 24 blocks (about 4 hours). The refund window then lasts another 72 blocks.

## 3. Tor and networking — needs patches

| Traffic | Today | What we need |
|---|---|---|
| libp2p to makers and rendezvous points (`swap/src/cli/transport.rs:66-140`) | Embedded **arti** only. With no arti client it **falls back to clearnet** TCP/DNS. There's no SOCKS option. | A SOCKS5 transport that dials through Ashigaru's Tor, with the clearnet fallback removed. |
| Electrum (`electrum-pool/src/lib.rs:520-535`) | `ConfigBuilder` with no proxy | Set `.socks5(...)` on the config. `electrum_client` already supports this. |
| Monero daemon from the embedded wallet (`monero-sys/src/lib.rs:2078-2100`) | `wallet2::init(.., proxy_address = "")`, hard-coded empty | Pass Ashigaru's SOCKS address. wallet2 supports a SOCKS proxy for the daemon. |
| Monero node pool (`monero-rpc-pool`) | Uses arti | Not used. Point the wallet at a single user-chosen node, own or onion. |
| Node availability check (`swap/src/cli/api/request.rs:1735`) | Plain `reqwest`, clearnet | Not used. It belongs to the GUI-facing `Context` layer, which we bypass. |

## 4. Embeddability

- **Use the lower-level engine.** The high-level `Context` (`swap/src/cli/api.rs:238-250`) is
  hard-wired to eigenwallet's own bdk wallet, arti client and seed handling. The swap itself is not:
  `bob::Swap::new(..)` + `bob::run(..)` take the database, an `Arc<dyn BitcoinWallet>`,
  `monero::Wallets`, the event-loop handle and the receive address pool. A shim crate can assemble
  these itself, following `Context` as a reference.
- **Events and approvals are tied to Tauri.** `TauriHandle` is a concrete struct
  (`swap/src/cli/api/tauri_bindings.rs:263-290`). With no handle, progress events are
  dropped and `request_bitcoin_approval` **auto-approves** (`tauri_bindings.rs:768-790`). We need a
  non-Tauri sink so Ashigaru receives every event and makes the approval decision itself.
- **Persistence.** Swaps are stored in eigenwallet's SQLite database (`swap-db`). State resumes from
  `BobState` after a restart, and `cli/cancel_and_refund.rs` provides manual cancel/refund.
- **Keys can be derived from Ashigaru's seed.** The swap seed is 32 bytes (`swap/src/seed.rs:18-21`)
  and implements `From<[u8; 32]>` (`seed.rs:173`). It can be derived deterministically from the
  Ashigaru wallet seed, for example a hardened BIP32 child plus HMAC, so swap keys can be recovered
  from the user's backup. The libp2p identity is derived from it too (`seed.rs:46`).
- **Monero receipt.** XMR is received through the embedded wallet2 over FFI and sent on to the
  user's `MoneroAddressPool` address.

## 5. Build and maintenance

- **Native code.** `monero-sys/build.rs` (720 lines) builds Monero C++ from source with Monero's
  `depends` system (CMake, Boost and the rest). eigenwallet's release CI already builds
  `x86_64-unknown-linux-gnu`, `aarch64-apple-darwin`, `x86_64-apple-darwin` and
  `x86_64-pc-windows-gnu` (`.github/workflows/build-release-binaries.yml:22-39`). Those are exactly
  our four targets, so their build recipe can be reused.
- **Toolchain.** Rust 1.90.0 (`rust-toolchain.toml`).
- **Release pace.** Releases are frequent, roughly weekly in 2026 (`CHANGELOG.md`: 4.6.x in May
  through 4.16.1 on Oct 13).
- **Breaking protocol changes are rarer.** The latest is 4.0.0 (2026-03-16: timelocks and the
  anti-spam deposit). Before that came 1.0/1.1 (libp2p 0.53, bdk). We would pin a tag and follow
  protocol-breaking releases promptly, with the in-app updater covering delivery.

## Patches

| # | Patch | Size | Upstreamable |
|---|---|---|---|
| P1 | SOCKS5 libp2p transport (for example via `tokio-socks`) selectable instead of arti; no clearnet fallback when a proxy is configured | Medium | Likely (useful for Whonix/Tails users) |
| P2 | Electrum `socks5` option threaded into `electrum-pool` | Small | Yes |
| P3 | Monero wallet `proxy_address` threaded through `monero-sys` / `monero::Wallets` | Small | Yes |
| P4 | Pluggable event/approval sink instead of the concrete `TauriHandle` (a trait or callback) | Small–medium | Possibly |

The hybrid `BitcoinWallet` (Ashigaru funding and signing, eigenwallet chain watching) lives in our
own shim crate. It needs no upstream change.

## Proposed shape

**Shim crate `ashigaru-swap`** (Rust, depends on the pinned eigenwallet crates with P1–P4,
exposes a C ABI used from Java through JNA):

- `init(network, dataDir, socksProxy, electrumUrl, moneroNodeUrl, seed32)`
- `listQuotes()` → makers with price, min/max, refund percentage and deposit requirement
- `startSwap(maker, btcAmount, xmrAddress, badbankRefundAddress)`
- Callbacks into Java:
  - `buildLockPsbt(address, amount, fee, change) → psbt`
  - `signPsbt(psbt) → tx`
  - `approveLock(details) → bool`
  - `onEvent(swapId, event)`
- `resumeAll()`, `cancelAndRefund(swapId)`, `listSwaps()`

**Ashigaru side:**
- Badbank coin selection and PSBT construction, built so the lock has at most one change output.
- Signing through the existing Tx0 path.
- The Swap screen (quotes, approval with refund/deposit terms, progress, history).
- BIP-329 labels on lock and refund transactions.
- Monero node settings.
- Backup and recovery of the swap database alongside the wallet.

**Packaging:** the shim adds one native library per platform, built in our release workflow with
eigenwallet's `monero-depends` recipe.

## Risks and open items

- **Partial refunds and deposits** are protocol features the user must understand before
  approving. The UI has to show them.
- **Patch upkeep** if P1–P4 are not accepted upstream.
- **Build time and size** of `monero-sys` in our CI. Not yet measured.
- **Testing** needs testnet BTC, Monero stagenet and an ASB. eigenwallet's `monero-harness` and the
  ASB in `swap-asb` could provide a local regtest setup for CI.
