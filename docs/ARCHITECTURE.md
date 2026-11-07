# Ashigaru Desktop — Architecture

A technical breakdown of how Ashigaru Desktop works, with a deep dive into the Whirlpool
coinjoin architecture. Ashigaru Desktop is a fork of [Sparrow Wallet](https://sparrowwallet.com)
re-oriented around a single purpose: providing a full Whirlpool coinjoin experience on desktop,
backed by the Ashigaru Terminal ecosystem.

Reviewed against `v1.4.5` @ `0c3356f`.

---

## Table of Contents

1. [Module Layout](#1-module-layout)
2. [Application Lifecycle](#2-application-lifecycle)
3. [UI Architecture](#3-ui-architecture)
4. [Event-Driven Architecture](#4-event-driven-architecture)
5. [Wallet Model (drongo)](#5-wallet-model-drongo)
6. [Persistence](#6-persistence)
7. [Network Layer](#7-network-layer)
8. [Whirlpool Architecture (Deep Dive)](#8-whirlpool-architecture-deep-dive)
9. [Other Privacy Features](#9-other-privacy-features)
10. [Build & Packaging](#10-build--packaging)
11. [darkjar: Dependencies & Provenance](#11-darkjar-dependencies--provenance)
12. [Notable Observations](#12-notable-observations)

---

## 1. Module Layout

The build is a three-module Gradle project (`settings.gradle`):

| Module | Path | Role |
|---|---|---|
| `sparrow` (root) | `src/` | The application: JavaFX GUI, Ashigaru-specific UI layer, Electrum connectivity, Tor, Whirlpool integration glue |
| `drongo` | `drongo/` | Bitcoin library: keys, scripts, transactions, PSBT, wallet model, BIP39/32/47, descriptors |
| `darkjar` | `darkjar/` | Forked Samourai libraries: the Whirlpool client (`com.samourai.whirlpool.*`), Soroban client, Cahoots (StonewallX2/Stowaway), BIP47 RPC utils, and the `nightjar` HTTP/STOMP/Tor service wrappers |

Dependency direction: `sparrow → drongo` and `sparrow → darkjar`. The darkjar Whirlpool client
knows nothing about Sparrow's wallet model — the `sparrow` module adapts between the two via
bridge classes (see [§8.7](#87-the-sparrowdarkjar-bridge)).

```
┌─────────────────────────────────────────────────────────┐
│ sparrow (app)                                           │
│  gui/ (Ashigaru UI)   whirlpool/ (bridges)   net/ (Tor, │
│  event/ (EventBus)    soroban/  paynym/      Electrum)  │
└───────────┬─────────────────────────┬───────────────────┘
            │                         │
   ┌────────▼────────┐      ┌─────────▼──────────────────┐
   │ drongo          │      │ darkjar                    │
   │ wallet model,   │      │ whirlpool-client, soroban, │
   │ crypto, PSBT,   │      │ cahoots, bip47, nightjar   │
   │ BIP32/39/47     │      │ (http/stomp/tor services)  │
   └─────────────────┘      └────────────────────────────┘
```

---

## 2. Application Lifecycle

**Entry point:** `AshigaruTerminal.main()`
(`src/main/java/com/sparrowwallet/sparrow/AshigaruTerminal.java:33`)

- Parses CLI args with JCommander (network selection, home directory, `--terminal` flag).
- Enforces single-instance via a custom `Instance extends InstanceList`
  (`AshigaruTerminal.java:167`) — a second launch forwards file URIs to the running instance
  and exits.
- Selects the interface mode via the `Interface` enum (`DESKTOP`, `TERMINAL`, `SERVER`) and
  launches either the Lanterna-based `SparrowTerminal` TUI or the JavaFX `AshigaruGui`.

**Desktop lifecycle:** `AshigaruGui`
(`src/main/java/com/sparrowwallet/sparrow/gui/AshigaruGui.java:30`) extends
`javafx.application.Application`:

- `init()` — installs the uncaught exception handler, calls `AppServices.initialize(this)`,
  registers with the `EventManager`.
- `start()` — loads `ashigaru-main.fxml`, builds the main scene, shows a splash
  (`SparrowWalletPreloader`), and reveals the main window once connected.

`AppServices` is the application-level service locator: it owns the `ConnectionService`
(Electrum polling), rate/fee services, the shared `HttpClientService`, Tor state, and
per-window wallet registries.

**Data directory:** `~/.ashigaru` (Linux/macOS) or `%APPDATA%\Ashigaru` (Windows), containing
`config` (JSON app settings), `wallets/`, `backup/`, and `certs/`.

---

## 3. UI Architecture

Ashigaru replaces Sparrow's multi-tab, per-wallet-window UI with a single-window,
Whirlpool-centric layout. The Ashigaru-specific UI lives in
`src/main/java/com/sparrowwallet/sparrow/gui/` with FXML under
`src/main/resources/com/sparrowwallet/sparrow/gui/`.

| Screen | Controller | Notes |
|---|---|---|
| Main window | `AshigaruMainController` | Wallet selector ComboBox, account sidebar with four toggles (**Deposit / Premix / Postmix / Badbank**), wallet actions (delete, view seed, import/export BIP-329 labels, lock) |
| Wallet panel | `AshigaruWalletController` | UTXO table (with custom `AshigaruMixesCell` mix-count renderer and MixStage column), transaction table behind a UTXOs/Transactions toggle, mix controls (`startMixBtn`, `mixToBtn`, `mixSelectedBtn`), Badbank info label |
| Receive dialog | `AshigaruReceiveController` | Deposit-only; fresh address via `wallet.getFreshNode(KeyPurpose.RECEIVE)` |
| Tx0 dialog | `AshigaruTx0Controller` | Pool selection, fee target, Tx0 preview + broadcast |
| Mix To dialog | `AshigaruMixToController` | Select an external wallet as the postmix destination |
| Tools | `ToolsController`, `Bip47MessageVerifierController` | Whirlpool stats; BIP47 message verification (derives the notification address from a payment code, recovers the pubkey from the signature, compares) |

Deliberately absent relative to upstream Sparrow: no send/spend UI (the wallet is
**receive-and-mix only** — spending happens on mobile), no hardware wallet support, no address
list or advanced wallet settings screens. This shrinks the attack surface, matching the README's
"Receive Only" positioning.

---

## 4. Event-Driven Architecture

Components communicate through a Guava `EventBus` singleton
(`src/main/java/com/sparrowwallet/sparrow/EventManager.java`). Controllers register in
`initialize()` and receive events via `@Subscribe` methods; anything can `post()`.

~60 event classes live in `src/main/java/com/sparrowwallet/sparrow/event/`. The load-bearing
groups:

- **Wallet:** `WalletHistoryChangedEvent`, `WalletAddressesChangedEvent`,
  `WalletUtxoMixesChangedEvent`, `WalletLockEvent`/`WalletUnlockEvent`
- **Connection:** `ConnectionStartEvent`, `ConnectionEvent`, `ConnectionFailedEvent`,
  `DisconnectionEvent`
- **Whirlpool:** `WhirlpoolMixEvent`, `WhirlpoolMixSuccessEvent`,
  `WhirlpoolIndexHighFrequencyEvent`

This bus is also the seam where the Whirlpool client's own event system
(`WhirlpoolEventService`, darkjar) is re-published into the UI: `Whirlpool`/`WhirlpoolServices`
subscribe to darkjar mix events and re-post Sparrow events that the wallet panel consumes to
animate mix progress per UTXO.

---

## 5. Wallet Model (drongo)

`drongo/src/main/java/com/sparrowwallet/drongo/wallet/Wallet.java` is the core model:

- `policyType` + `scriptType` define the wallet policy (Ashigaru wallets are single-sig P2WPKH).
- `keystores` hold BIP32 xprv/xpub material, the BIP39 seed, and the wallet's **BIP47 payment
  code**.
- `purposeNodes` is the tree of derived `WalletNode` addresses (receive/change chains).
- `childWallets` — the Whirlpool accounts are modeled as *child wallets* of the master (deposit)
  wallet, sharing its seed but derived at different hardened account indices.
- `utxoMixes` (`Map<Sha256Hash, UtxoMixData>`) persists per-UTXO mix counters inside the wallet
  file itself.
- `MixConfig` stores coinjoin settings (SCODE, mix-on-startup, mix-to wallet, min mixes).

**Whirlpool accounts** are first-class members of the `StandardAccount` enum
(`drongo/src/main/java/com/sparrowwallet/drongo/wallet/StandardAccount.java:40-42`):

| Account | Hardened index | Script type | Gap limit |
|---|---|---|---|
| Deposit (`ACCOUNT_0`) | `0'` | wallet's own | default |
| `WHIRLPOOL_BADBANK` | `2147483644'` | forced P2WPKH | default |
| `WHIRLPOOL_PREMIX` | `2147483645'` | forced P2WPKH | default |
| `WHIRLPOOL_POSTMIX` | `2147483646'` | forced P2WPKH | 2× default lookahead |

So for a standard BIP84 wallet the full paths are `m/84'/0'/2147483645'/0/i` (premix), etc. —
the same layout as Samourai/Ashigaru mobile, which is what makes the wallet portable between
desktop and mobile. `MIXABLE_ACCOUNTS` (`StandardAccount.java:45`) defines which accounts can
feed a Tx0: Deposit and Badbank (plus Postmix for remixing via the mix accounts list).

The darkjar side has its own mirror of this in `WhirlpoolAccount`
(`darkjar/.../whirlpool/client/wallet/beans/WhirlpoolAccount.java`): `DEPOSIT`, `PREMIX`
(`Integer.MAX_VALUE - 2`), `POSTMIX` (`Integer.MAX_VALUE - 1`), `BADBANK`, with
premix/postmix/badbank pinned to `AddressType.SEGWIT_NATIVE`.

---

## 6. Persistence

`src/main/java/com/sparrowwallet/sparrow/io/Storage.java` fronts wallet file I/O:

- **Formats:** legacy JSON and the current **H2 database** (`.mv.db`), selected by the
  `PersistenceType` enum; legacy files are auto-migrated. `DbPersistence` uses JDBI3 for
  mapping, Flyway for schema migrations, HikariCP for pooling.
- **Encryption:** password → Argon2 key derivation → ECIES encryption keyed by an `ECKey`.
- **App config:** `Config.java` — a JSON singleton for server type/URL, network, Tor proxy
  settings, unit/format preferences, and Whirlpool-adjacent flags (`usePayNym`,
  `sameAppMixing`).

Whirlpool mix state (per-UTXO `mixsDone`, wallet indices) is deliberately **not** kept in
separate files as upstream whirlpool-client does — it is persisted into the Sparrow wallet
storage through the `SparrowDataPersister` bridge (see §8.7), so wallet file + seed is a
complete backup.

---

## 7. Network Layer

### 7.1 Tor

- **Embedded Tor** via **kmp-tor** (`src/main/java/com/sparrowwallet/sparrow/net/Tor.java`),
  managed by `TorService` (a JavaFX `ScheduledService` with a 5-minute startup timeout).
  Control port is auto-negotiated; the SOCKS proxy defaults to 9050 and auto-increments if
  taken.
- `TorUtils.changeIdentity()` sends the `NEWNYM` control signal to rotate circuits — Whirlpool
  uses this between mix identities (see §8.5).
- Users may alternatively point Ashigaru at an external SOCKS proxy via `Config`.

### 7.2 Electrum connectivity

`src/main/java/com/sparrowwallet/sparrow/net/ElectrumServer.java` is the blockchain data
facade:

- **Transports** are layered via the `Protocol` enum (TCP/SSL): `TcpTransport`,
  `TcpOverTlsTransport`, `ProxyTcpOverTlsTransport`, and Tor-specific `TorTcpTransport` /
  `TorTcpOverTlsTransport` — `.onion` hosts are automatically routed through the Tor SOCKS
  proxy.
- **RPC** is JSON-RPC 2.0 (`SimpleElectrumServerRpc` / `BatchedElectrumServerRpc`, the latter
  batching with retry: 5 attempts, 1s delay).
- **Subscriptions:** script-hash subscriptions feed `WalletHistoryChangedEvent`s;
  `WalletForm.refreshHistory()` diffs server history against the local model.
- **Preset servers** (`PublicElectrumServer`) are privacy-vetted defaults (Foundation Devices,
  SethForPrivacy, etc., mostly onion endpoints). Upstream's Blockstream server and the OXT fee
  source were removed.
- Bitcoin Core can be used directly via the Cormorant bridge (server type `BITCOIN_CORE`).

### 7.3 HTTP client abstraction (nightjar)

The darkjar Whirlpool/Soroban clients consume a provider-agnostic `IHttpClient`
(`com.samourai.http.client`). The implementation is Jetty-based:

- `JavaHttpClient` / `JavaHttpClientService`
  (`darkjar/src/main/java/com/sparrowwallet/nightjar/http/`) pool one Jetty client per
  `HttpUsage` (e.g. `COORDINATOR_REST`, output registration), route through a `Socks4Proxy`
  when a Tor proxy is set, suppress the User-Agent header, and support identity-scoped
  restarts (`changeIdentityRest()`).
- The `sparrow` module exposes this via `HttpClientService`
  (`src/main/java/com/sparrowwallet/sparrow/net/HttpClientService.java`), shared by PayNym,
  Payjoin, Soroban, and Whirlpool.
- STOMP-over-WebSocket (for the mix protocol) is provided by `JavaStompClientService` on the
  same stack.

---

## 8. Whirlpool Architecture (Deep Dive)

Whirlpool is a **ZeroLink-style, coordinator-based, equal-output coinjoin**. Every mix
transaction has 5+ inputs and 5+ outputs of exactly one pool denomination, so any output is
unlinkable to any particular input within the mix (and unlinkability compounds with remixing).
The coordinator matches participants and assembles transactions but — by construction of the
protocol — *cannot* link a participant's input to their output, and never touches funds.

The implementation is split across three layers:

```
┌──────────────────────────────────────────────────────────────────┐
│ sparrow: UI + glue                                               │
│   AshigaruWalletController / AshigaruTx0Controller (UI)          │
│   WhirlpoolServices (per-wallet lifecycle)                       │
│   Whirlpool (facade)                                             │
│   dataSource/, dataPersister/, tor/ (bridges)                    │
├──────────────────────────────────────────────────────────────────┤
│ darkjar: whirlpool-client                                        │
│   WhirlpoolWallet ── MixOrchestrator ── MixClient/MixProcess     │
│   Tx0Service / Tx0ParamService        ClientCryptoService (RSA)  │
│   ServerApi (REST) / MixSession (STOMP over WebSocket)           │
├──────────────────────────────────────────────────────────────────┤
│ network: Tor (kmp-tor) → coordinator onion endpoints             │
└──────────────────────────────────────────────────────────────────┘
```

### 8.1 Core concepts

**Pools** (`darkjar/.../whirlpool/client/whirlpool/beans/Pool.java`): a pool is a fixed
denomination (e.g. 0.5, 0.05, 0.01 BTC) with coordinator-published parameters:

- `denomination` — the exact output value of every mix output
- `feeValue` — the flat coordinator fee, paid **once at Tx0**, not per mix
- `mustMixBalanceMin/Max` — acceptable premix input range (denomination + fee headroom)
- `minAnonymitySet`, `minMustMix` — round formation constraints
- `tx0MaxOutputs` — cap on premix outputs a single Tx0 may create

**Mustmix vs liquidity:** a fresh premix UTXO (balance > denomination, carrying miner-fee
headroom) is a *mustmix*; an already-mixed UTXO (balance == denomination) re-entering as a
free remixer is a *liquidity* input. Remixing is free — miner fees for the mix tx are paid by
the mustmix inputs' overspend. This is the economic core of Whirlpool: your anonymity set keeps
growing at no cost as long as you leave postmix UTXOs available for remixing.

**Accounts:** the four hardened accounts (§5) partition coin state:

```
 Deposit ──Tx0──► Premix ──mix──► Postmix ──remix──► Postmix ...
    │                                  │
    └──Tx0 change──► Badbank           └──Mix To──► external wallet
```

Badbank holds Tx0 change ("doxxic change" — still linked to your deposit history). It is
quarantined by design and can only re-enter Whirlpool via a new Tx0 into a smaller pool, never
merged with mixed coins.

### 8.2 UTXO state machine

Every tracked UTXO is wrapped in a `WhirlpoolUtxo` with a `WhirlpoolUtxoStatus`
(`darkjar/.../wallet/beans/WhirlpoolUtxoStatus.java`):

```
READY → TX0 → TX0_SUCCESS / TX0_FAILED
READY → MIX_QUEUE → MIX_STARTED → MIX_SUCCESS / MIX_FAILED → (requeue)
                                   STOP (user)
```

`UtxoConfig` carries the persisted `mixsDone` counter, surfaced in the UI's "Mixes" column.

### 8.3 Tx0: entering the pool

Tx0 is the pool-entry transaction that splits a deposit UTXO into uniform premix outputs. Built
by `Tx0Service` (`darkjar/.../whirlpool/client/tx0/Tx0Service.java`) with parameters from
`Tx0ParamService`/`Tx0Param`:

```
inputs:  deposit UTXO(s)
outputs: fee output            → coordinator (address derived from the coordinator's
                                 BIP47 payment code in Tx0Data; OP_RETURN-adjacent
                                 fee payload identifies pool/partner)
         N × premix outputs    → PREMIX account, value = denomination + overspend
         change output         → BADBANK account
```

- The client first fetches `Tx0Data` from the coordinator (`POST /api/tx0`): per-pool
  `feePaymentCode`, `feeValue`, and a Z85-encoded `feePayload64` (fee address index + partner
  SCODE payload). Ashigaru identifies itself with partner ID `"ashigaruterminal"`
  (`src/.../whirlpool/Whirlpool.java:58,114`); SCODEs can discount the coordinator fee.
- **Premix overspend** pre-pays each output's share of future mix miner fees:
  `premixValue = denomination + mixFeeEstimate/minMustMix`, clamped to
  `[mustMixBalanceMin, mustMixBalanceMax]`.
- `nbPremix` is computed to fit as many premix outputs as the input balance allows, up to
  `tx0MaxOutputs`.
- **Strict mode** (`tx0StrictMode`, default on) validates against address reuse server-side and
  retries with fresh indices up to `tx0MaxRetry` (5).
- Fee targets are selectable (`Tx0FeeTarget`, `BLOCKS_2`…`BLOCKS_24`); Ashigaru defaults both
  Tx0 and mix targets to `BLOCKS_4` (`Whirlpool.java:74-75`).

Sparrow-side flow: `AshigaruTx0Controller` → `Whirlpool.getTx0Previews()` (previews across all
eligible pools) → user picks pool → `Whirlpool.broadcastTx0()` → `WhirlpoolWallet.tx0()`
(`Whirlpool.java:142-160`). The Tx0 change account is hardcoded to `BADBANK`
(`Whirlpool.java:163`).

### 8.4 The mix protocol (blind-signature round)

The per-round protocol is driven by `MixClient`/`MixProcess`
(`darkjar/.../whirlpool/client/mix/`) over a STOMP WebSocket session (`MixSession`,
`dialog/MixSession.java`). The cryptographic centerpiece is an **RSA blind signature**
(Chaumian ecash style) implemented in `ClientCryptoService`
(`darkjar/.../whirlpool/client/utils/ClientCryptoService.java`) with BouncyCastle
(`PSSSigner`/`RSABlindingEngine`, SHA-256):

```
      INPUT IDENTITY (Tor circuit A)            OUTPUT IDENTITY (Tor circuit B)
 ┌────────────────────────────────────┐   ┌─────────────────────────────────────┐
 │ 1. REGISTER_INPUT                  │   │                                     │
 │    utxo + sig(poolId) + liquidity? │   │                                     │
 │                                    │   │                                     │
 │ 2. CONFIRM_INPUT                   │   │                                     │
 │    blind(bordereau) w/ round RSA   │   │                                     │
 │    pubkey + userHash               │   │                                     │
 │                                    │   │                                     │
 │ 3. ← signed(blind(bordereau))      │   │                                     │
 │    client unblinds locally         │   │                                     │
 │                                    │   │ 4. REGISTER_OUTPUT (REST, new       │
 │                                    │   │    circuit): bordereau + unblinded  │
 │                                    │   │    coordinator sig + postmix addr   │
 │ 5. SIGNING                         │   │                                     │
 │    verify own output in tx,        │   │                                     │
 │    sign own input (BIP143)         │   │                                     │
 │                                    │   │                                     │
 │ 6. SUCCESS (tx broadcast)          │   │                                     │
 └────────────────────────────────────┘   └─────────────────────────────────────┘
```

Why the coordinator can't link input → output:

1. At **CONFIRM_INPUT** the client generates a random *bordereau* (a one-time token), blinds it
   against the round's RSA public key, and submits it from the same identity that registered
   the input. The coordinator signs the blinded value — it never sees the bordereau itself.
2. The client unblinds the signature locally (only it knows the blinding factor).
3. At **REGISTER_OUTPUT** the client connects with a **fresh network identity** — a different
   Tor circuit, a separate HTTP client (`HttpUsage`-scoped), and even a *different onion
   endpoint* (`WhirlpoolServer.serverUrlOutputReg`) — and presents the bordereau plus its
   now-valid coordinator signature alongside a fresh postmix address. The signature proves
   "this output belongs to *some* registered input of this round" without revealing which.
4. **SIGNING**: the coordinator distributes the assembled transaction; each client verifies its
   postmix address is present with the exact denomination before signing its own input
   (BIP143 segwit sighash). Any discrepancy → refuse to sign, round fails safely.
5. `REVEAL_OUTPUT` exists as a blame phase for failed rounds (identify the participant who
   registered an input but no output) so the round can be retried without them.

Supporting details:

- Round integrity: clients receive a SHA-512 `inputsHash` of the sorted input set at
  confirmation, preventing the coordinator from swapping inputs after the fact.
- `userHash` (SHA-256 over mixId + premix address hash) prevents duplicate/confused client
  registrations without giving the coordinator linkage material.
- Protocol version `0.23` is enforced via STOMP headers
  (`darkjar/.../whirlpool/protocol/WhirlpoolProtocol.java`).
- Each `MixClient` is single-use (one round); reconnection/retry logic lives above it.

### 8.5 Client orchestration

`WhirlpoolWallet` (`darkjar/.../whirlpool/client/wallet/WhirlpoolWallet.java`) is the
long-running per-wallet engine, with lifecycle `open() → start() → stop() → close()`. It owns:

- **`MixOrchestratorImpl`** (`.../wallet/orchestrator/MixOrchestratorImpl.java`) — a polling
  loop managing a pool of concurrent `WhirlpoolClient`s: up to `maxClients` (5 on desktop)
  with at most **1 client per pool** at a time, `clientDelay` (30s) between connection
  attempts. Auto-mix re-queues eligible premix/postmix UTXOs; postmix UTXOs join as free
  liquidity. Listener chain: `MixProcess → MixClient → MixOrchestrator → WhirlpoolWallet →
  WhirlpoolEventService → (Sparrow EventManager → UI)`.
- **`AutoTx0Orchestrator`** — optional automatic Tx0 creation from deposit balance (60s poll);
  not driven by the Ashigaru UI, which keeps Tx0 an explicit user action.
- **Suppliers** — `UtxoSupplier`, `PoolSupplier` (`ExpirablePoolSupplier`, periodic refresh),
  `MinerFeeSupplier`, `ChainSupplier`, `WalletStateSupplier` — all fed by the `DataSource`
  (which in Ashigaru is the Sparrow bridge, §8.7).
- **`PostmixIndexService`** — reconciles the postmix derivation index with the coordinator to
  prevent postmix address reuse when the same seed mixes from multiple devices (relevant here:
  desktop + mobile sharing a wallet). `postmixIndexAutoFix` recovers from detected reuse;
  Ashigaru sets `IndexRange.FULL` (`Whirlpool.java:115`).
- **Tor identity rotation** — `SparrowTorClientService`
  (`src/.../whirlpool/tor/SparrowTorClientService.java`) maps whirlpool-client's
  `changeIdentity()` calls onto Tor `NEWNYM`, so each mix (and each identity within a mix)
  rides a fresh circuit.

### 8.6 Coordinator communication

Two channels, both Tor-only in practice:

1. **REST** (`ServerApi`, `darkjar/.../whirlpool/client/whirlpool/ServerApi.java`): `/api/pools`
   (pool list), `/api/tx0` (fee data), `/api/tx0/notify`, and — on the *separate output
   identity* — `/api/output/check` and `/api/output/register`.
2. **STOMP over WebSocket** (`MixSession`): the interactive mix round — subscribe to a private
   reply queue, exchange `RegisterInputRequest` / `ConfirmInputRequest` /
   `SigningRequest` messages against coordinator round notifications.

Endpoints are baked into the `WhirlpoolServer` enum
(`darkjar/.../wallet/beans/WhirlpoolServer.java`): per network a clearnet URL, a primary onion
URL, and a **distinct onion for output registration**. `Whirlpool.computeWhirlpoolWalletConfig()`
passes `getServerUrl(true)` unconditionally (`Whirlpool.java:108`) — coordinator traffic always
targets the onion endpoint, making Tor effectively mandatory for mixing.

Note: **Soroban** (the BIP47-encrypted RPC-over-Tor mailbox layer in
`com.samourai.soroban.*`) is *not* part of the mix protocol in this codebase — here it is the
transport for Cahoots collaborative transactions (§9). The mix protocol remains the classic
REST + STOMP design.

### 8.7 The Sparrow↔darkjar bridge

whirlpool-client was designed for Samourai's backend (Dojo). Ashigaru Desktop instead feeds it
from Sparrow's Electrum-based wallet model through adapter implementations in
`src/main/java/com/sparrowwallet/sparrow/whirlpool/`:

| darkjar interface | Sparrow implementation | What it does |
|---|---|---|
| `DataSource` | `dataSource/SparrowDataSource.java` | Converts the drongo `Wallet` (master + whirlpool children) into whirlpool-client's `WalletResponse` shape: UTXOs per account, tx history, receive/change indices. Listens for `WalletHistoryChangedEvent` and refreshes the `UtxoSupplier`. |
| `DataPersister` | `dataPersister/SparrowDataPersister.java` | Persists mix state through Sparrow's wallet storage instead of standalone JSON files (10s poll → persist on change). |
| `MinerFeeSupplier` | `dataSource/SparrowMinerFeeSupplier.java` | Feeds Sparrow's Electrum fee estimates into Tx0/mix fee math. |
| `WalletStateSupplier` | `dataSource/SparrowWalletStateSupplier.java` | Wallet-level indices/state backed by Sparrow persistence. |
| `IPostmixHandler` | `SparrowPostmixHandler` | Derives the next postmix receive address — from the local postmix account or, with **Mix To**, from an external wallet's account. |
| `TorClientService` | `tor/SparrowTorClientService.java` | Bridges `changeIdentity()` to the embedded Tor's NEWNYM. |

Lifecycle glue: `WhirlpoolServices`
(`src/.../whirlpool/WhirlpoolServices.java`) keeps a map of wallet-id → `Whirlpool` instance,
starts/stops them on `ConnectionEvent`/`DisconnectionEvent`, honors `MixConfig.mixOnStartup`,
and re-wires the Tor proxy on changes. `Whirlpool` (`src/.../whirlpool/Whirlpool.java`) is the
per-wallet facade: it converts the Sparrow keystore/seed into a darkjar `HD_Wallet`, opens the
`WhirlpoolWallet` via `WhirlpoolWalletService`, and exposes `broadcastTx0()`, `mix()`,
`mixStop()`, and pool/preview queries to the UI. JavaFX `BooleanProperty`s
(`startingProperty`, `mixingProperty`, …) let the UI bind directly to engine state.

**Mix To:** postmix outputs can be directed at a *different* wallet (open in the same app, or
an offline/watch-only one), with `DEFAULT_MIXTO_MIN_MIXES = 3` and a randomization factor to
avoid a deterministic hop count. This gives a clean break between the mixing wallet and
long-term cold storage.

### 8.8 Configuration summary

`WhirlpoolWalletConfig` (darkjar) — key knobs as configured by Ashigaru:

| Setting | Value | Meaning |
|---|---|---|
| `maxClients` | 5 | concurrent mix clients (desktop) |
| `maxClientsPerPool` | 1 | never two simultaneous mixes in one pool |
| `clientDelay` | 30s | spacing between client connections |
| `autoMix` | on | re-queue eligible UTXOs automatically while mixing |
| `tx0StrictMode` / `tx0MaxRetry` | on / 5 | address-reuse defense on Tx0 |
| `partner` | `ashigaruterminal` | fee-payload partner tag |
| `indexRangePostmix` | `FULL` | postmix index range strategy |

---

## 9. Other Privacy Features

- **BIP47 / PayNym** — payment code crypto lives in
  `drongo/.../bip47/PaymentCode.java` (80-byte payload, notification address at `m/…/0`,
  ECDH + outpoint-masked blinding for notification transactions). `paynym/PayNymService`
  integrates the PayNym directory (following/followers, segwit-capable code variants). Release
  binaries are themselves signed with the maintainer's BIP47 notification key, verifiable with
  the built-in **BIP47 Message Verifier** tool (`gui/Bip47MessageVerifierController.java`).
- **Cahoots (StonewallX2 / Stowaway)** — two-party collaborative transactions
  (`darkjar com.samourai.wallet.cahoots.*`), coordinated peer-to-peer over **Soroban**
  (`soroban/Soroban.java`, `SorobanCahootsService`): BIP47-authenticated, ECDH-encrypted
  message exchange through Tor, no direct connection between participants.
- **Payjoin (BIP78)** — `payjoin/Payjoin.java`, with full PSBT proposal validation and onion
  endpoint support.

---

## 10. Build & Packaging

- Gradle + **jlink/jpackage**, JDK 21, main class `AshigaruTerminal`, module
  `com.sparrowwallet.sparrow` (`build.gradle:162+`).
- Key dependencies: JavaFX 21, Guava (EventBus), Gson, H2 2.x + JDBI3 + Flyway, BouncyCastle,
  Lanterna (TUI), kmp-tor, Jetty (nightjar HTTP/STOMP).
- Artifacts: Windows `.exe`/`.msi`, macOS `.dmg` (ad-hoc signed, not notarized), Linux
  `.deb`/`.rpm`/`.tar.gz`/`.AppImage`, plus a headless `ashigaru-server` package (Monocle).
- Reproducible-build flags (`preserveFileTimestamps=false`, `reproducibleFileOrder=true`);
  see `docs/ReproducibleBuilds.md`. Releases ship `SHA256SUMS` + a BIP47-signed message.

---

## 11. darkjar: Dependencies & Provenance

The Whirlpool client is **not** consumed as a Maven artifact — all of it is vendored source
inside `darkjar` (633 Java files), compiled to a single jar that the root build re-badges as
the Java module `com.sparrowwallet.nightjar` version `0.2.41`. That
version/name impersonates the original `nightjar` Maven artifact upstream Sparrow depended on,
so Sparrow-side imports (`com.sparrowwallet.nightjar.http/stomp/tor`) work unchanged.

**Vendored source trees** (`darkjar/src/main/java/`):

| Package | Files | Origin |
|---|---|---|
| `com.samourai.whirlpool.client` / `.protocol` | 162 | Samourai's `whirlpool-client` + `whirlpool-protocol` libraries |
| `com.samourai.wallet.*` (bip47, bip69, hd, segwit, send, cahoots, crypto, util, api) | 132 | Samourai's `extlibj` — includes `SendFactoryGeneric`, `BoltzmannUtil`, STONEWALL/cahoots builders |
| `com.samourai.soroban.*` | 27 | Samourai's `soroban-client-java` (BIP47-encrypted RPC dialogs for Cahoots) |
| `com.samourai.http/stomp/tor/websocket` | 12 | Samourai's transport abstraction interfaces (`IHttpClient`, `IStompClient`, …) |
| `org.bitcoinj` + `org.bitcoin` | 284 | A vendored, Samourai-patched **bitcoinj** — the whirlpool client's own Bitcoin stack |
| `com.sparrowwallet.nightjar.*` | 11 | Craig Raw's nightjar glue (`JavaHttpClient/JavaStompClient/JavaTor` services on Jetty) |
| `com.zeroleak.throwingsupplier` | 5 | Tiny checked-exception-supplier helper lib |

**Maven dependencies** (`darkjar/build.gradle`): BouncyCastle 1.77 (RSA blinding, EC crypto),
Spring `spring-websocket`/`spring-messaging` 5.2.2 (the STOMP client used by `MixSession`),
Jetty `websocket-client` 9.4.19, okhttp 2.7.5, protobuf 2.6.1 (bitcoinj payment channels),
Jackson, Gson-era `org.json`, RxJava 2, Guava, scrypt, streamsupport (Java 8 streams backport —
Android heritage), java-jwt, hibernate-validator, logback. Several of these are notably old
pins inherited from the Android-compatible Samourai codebase.

**Provenance chain:** Samourai Wallet's open-source client libraries → bundled by Sparrow's
author as `nightjar` (so upstream Sparrow could do Whirlpool) → forked and vendored in-tree by
Ashigaru as `darkjar` (single squashed "Initial commit"). Ashigaru's own changes to this layer
are thin — `PARTNER_ID_ASHIGARU` in `WhirlpoolProtocol`, config tweaks — while the
`com.samourai.*` namespaces, protocol version (0.23), and even the Samourai-era coordinator
endpoints remain as inherited.

**No server code ships in this repo.** The coordinator (`whirlpool-server`) is an external
service; the client merely implements the wire protocol against the onion endpoints in
`WhirlpoolServer`.

**Two Bitcoin stacks coexist in the app:** drongo (Sparrow's own library) drives the wallet
model, PSBTs, and Electrum sync, while the vendored bitcoinj drives Whirlpool transaction
construction and signing. The `SparrowDataSource` bridge (§8.7) is what keeps them consistent,
converting drongo UTXOs/transactions into the bitcoinj-shaped structures whirlpool-client
expects.

---

## 12. Notable Observations

Things worth knowing that aren't obvious from the code's surface:

1. **Coordinator endpoints are inherited Samourai-era constants.** `WhirlpoolServer` still
   lists `pool.whirl.mx` clearnet URLs and the historical onion addresses. Since the main API
   path always uses the onion URL (`Whirlpool.java:108`), the clearnet entries are effectively
   dead config. Whoever operates the coordinator behind those onions today (post-Samourai,
   the Ashigaru project) is a code-external fact — the client protocol is unchanged.
2. **Network names do not match `WhirlpoolServer`'s constants.** `Network.TESTNET.getName()` is
   `"testnet3"` and `Network.TESTNET4.getName()` is `"testnet"`, while the testnet constant is
   `WhirlpoolServer.TESTNET`. Looking the server up by name therefore threw for testnet3 and worked
   for testnet4 only by accident. `WhirlpoolServerResolver.builtInServer` now maps networks to servers
   explicitly (mainnet → `MAINNET`, either testnet → `TESTNET`), and applies the server set in
   Settings → Network on top (`NetworkSettings`, stored in `network.json` in the home folder because the
   per-network config cannot hold both networks' servers).
3. **The dual-identity design is the protocol's soul.** Everything privacy-critical hangs on
   the input identity and output identity being unlinkable: separate Tor circuits, separate
   HTTP clients, separate onion endpoints, blind signature in between. The
   `changeIdentity()` plumbing through `SparrowTorClientService` is not an optional nicety.
4. **Mix state rides in the wallet file.** Unlike upstream whirlpool-client's standalone JSON
   persistence, `SparrowDataPersister` folds mix counters and indices into Sparrow's encrypted
   wallet storage — a single-file backup story, at the cost of coupling mix state to wallet
   persistence.
5. **Receive-only by construction.** There is no spend path in the UI at all; the desktop app's
   job is deposit → Tx0 → mix → (Mix To). Spending mixed coins is delegated to Ashigaru mobile.
   This is an unusually clean privilege separation for a wallet.
6. **Known TODOs in code:** zeroleak change-index revert not yet implemented; BIP69 output
   sorting marked as TODO in Tx0 construction.
