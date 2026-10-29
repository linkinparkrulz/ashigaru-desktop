# Ashigaru Desktop — Development Guide

Operational companion to [ARCHITECTURE.md](ARCHITECTURE.md). That doc explains how the system
works; this one explains how to build, run, debug, and safely change it. Written as a
knowledge-transfer document: a developer (or AI assistant) on a fresh machine should be able
to work from these two files plus the source.

---

## 1. Environment & Build

**Requirements:** JDK 21 (Temurin recommended). Nothing else — Gradle comes via the wrapper,
Tor is embedded in the app (Tor 0.4.9.5 via kmp-tor runtime 2.5.0 / resource-exec-tor-gpl 409.5.0;
the filterjar plugin in `build.gradle` keeps only the build platform's Tor binary).

```sh
./gradlew jar          # build
./gradlew test         # run tests (JUnit 5)
./gradlew run          # launch the GUI from source
./gradlew jpackage     # full platform installer (see §10 of ARCHITECTURE.md)
```

The application plugin runs main class `com.sparrowwallet.sparrow.AshigaruTerminal` in module
`com.sparrowwallet.sparrow` with a long list of `--add-opens` JVM args (`build.gradle`,
`application` block) — always launch via Gradle or the packaged binary, never `java -jar`.

**CLI flags** (`src/main/java/com/sparrowwallet/sparrow/Args.java`):

| Flag | Effect |
|---|---|
| `--network` / `-n` | `mainnet` (default), `testnet`, `testnet4`, `signet`, `regtest` |
| `--dir` / `-d` | Override the home folder (useful for throwaway test profiles) |
| `--level` / `-l` | Log level (`DEBUG`, `INFO`, …) |
| `--terminal` / `-t` | Lanterna TUI instead of the JavaFX GUI |
| `--version`, `--help` | As expected |

With `./gradlew run`, pass flags as `./gradlew run --args="--network testnet --level DEBUG"`.

**Headless/server:** setting `-Djava.awt.headless=true` at build/run time switches JavaFX to
Monocle software rendering (`build.gradle` `headless` conditional). Releases no longer ship a
headless package; build one locally with `./gradlew -Djava.awt.headless=true jpackage` if needed.

**Build quirks:**

- **Module definitions must mirror dependencies.** The build uses `extra-java-module-info` to
  modularize non-modular jars: every dependency change must be reflected in the `module()` blocks
  in the lower half of `build.gradle`, keyed by the resolved jar's exact filename. Miss one (or
  export too little) and jlink fails. The darkjar project jar is deliberately modularized as
  `com.sparrowwallet.nightjar` version `0.2.41` — keep name and version in sync with
  `darkjar/build.gradle` if either changes.
- Reproducible-build flags are set on all archive tasks; don't add tasks that break
  `preserveFileTimestamps=false` / `reproducibleFileOrder=true`.

## 2. Tests

| Module | Location | Coverage |
|---|---|---|
| `drongo` | `drongo/src/test/...` (18 files) | The valuable ones: PSBT, BIP47, script/address, wallet, output descriptors |
| `sparrow` | `src/test/...` (13 files) | Mostly `io/` wallet-import round-trips (Electrum, Coldcard, etc.) and storage |
| `darkjar` | none | Vendored code ships without tests — behavior is pinned by upstream provenance, not local tests |

There are **no tests over the Whirlpool integration layer** (`sparrow/whirlpool/*` bridges).
Changes there must be verified by running against testnet (see §4).

## 3. Runtime Layout & Debugging

**Home directory:** `~/.ashigaru` (Linux/macOS), `%APPDATA%\Ashigaru` (Windows), overridable
with `--dir`. Contents:

```
~/.ashigaru/
  config          # app settings (JSON) — server, proxy, network flags
  ashigaru.log    # rolling log (logback)
  wallets/        # encrypted wallet files (.mv.db H2 databases)
  backup/         # wallet backups
  certs/          # pinned Electrum server certificates
```

Network-specific subdirectories are used for non-mainnet (e.g. `~/.ashigaru/testnet/`).

**Logging:** logback config at `src/main/resources/logback.xml`, file output to
`<home>/ashigaru.log`. Bump verbosity with `--level DEBUG`. During a mix, the useful log lines
come from `com.samourai.whirlpool.client.*` — `MixClient`/`MixSession` prefix entries with the
STOMP session, so a multi-client mixing session remains readable. `MixProgress` states track
the protocol phases described in ARCHITECTURE.md §8.4.

**Tor options.** Two modes, selected in Settings → Server (persisted in `config`):

1. **Embedded** (default): kmp-tor boots inside the app; SOCKS proxy on 9050 (auto-increments
   if taken). No setup needed.
2. **External proxy**: set `useProxy=true` + `proxyServer=host:port` in config (or via the
   settings UI) to use a machine-local Tor daemon instead. On a machine that already runs Tor,
   this is the better mode — faster startup, shared circuits with system Tor policy.
   `autoSwitchProxy` (default true) falls back to the embedded Tor when the external proxy
   dies.

Note the constraint from ARCHITECTURE.md §8.6: the Whirlpool coordinator is onion-only, so
*some* Tor (embedded or external) must be up before mixing can work. Electrum over clearnet
works without Tor.

## 4. Exercising Whirlpool Safely

- **Never develop against mainnet with real funds.** Run `--network testnet` with a throwaway
  `--dir`; the `WhirlpoolServer.TESTNET` coordinator endpoints are baked in.
- Coordinator availability is external — if the testnet coordinator is down, mixing simply
  won't form rounds; that's not a client bug.
- For fully offline protocol work there is `WhirlpoolServer.LOCAL_TESTNET`
  (`127.0.0.1:8080`), which expects a locally run `whirlpool-server` instance (not in this
  repo).
- The full manual flow to verify: create wallet → Receive (Deposit) → fund from a testnet
  faucet → select UTXO → Mix Selected (Tx0 dialog: pick pool, fee target, broadcast) → Premix
  tab → Start Mixing → watch `ashigaru.log` and the Mixes column.
- Known gap: `TESTNET4` is listed in `Whirlpool.WHIRLPOOL_NETWORKS` but has no
  `WhirlpoolServer` entry, so whirlpool startup on testnet4 will throw. Use `testnet` (v3) for
  mix testing.

## 5. Where to Make Changes (Cookbook)

**Adding a UI feature** — follow the existing pattern (the receive dialog is the cleanest
template):
1. FXML in `src/main/resources/com/sparrowwallet/sparrow/gui/` (see `ashigaru-receive.fxml`).
2. Controller in `src/main/java/com/sparrowwallet/sparrow/gui/`
   (see `AshigaruReceiveController.java` — static `show()` entry point, loads its own FXML).
3. Wire into `AshigaruWalletController` / `AshigaruMainController` via an `@FXML` handler.
4. If the feature reacts to wallet/mix state: register with `EventManager.get().register(this)`
   and subscribe with `@Subscribe` methods; unregister on close.

**Adding an app setting:** field + getter/setter in `io/Config.java` (Gson-serialized
automatically), surface it in the settings UI. Wallet-scoped settings go in drongo's
`MixConfig`/`WalletConfig` instead — those persist inside the encrypted wallet file.

**Adding an event:** new class in `event/`, post via `EventManager.get().post(...)`. Keep
events immutable carriers; heavy work belongs in the subscriber's background service, not the
bus thread.

**Touching the Whirlpool bridge:** the seam is `sparrow/whirlpool/dataSource/*` and
`dataPersister/*` — implementations of darkjar interfaces. Prefer changing the Sparrow side;
treat darkjar as vendored upstream (below).

## 6. Invariants — Do Not Break

1. **Derivation paths are consensus with mobile.** The Whirlpool account indices
   (`2147483644'`–`2147483646'`, `StandardAccount.java:40-42`), forced P2WPKH, and postmix
   index handling make the same seed portable between Ashigaru mobile and desktop. Any change
   here silently strands funds from the other device's view.
2. **The dual-identity mix design.** Input and output registration must remain on separate
   network identities (separate HTTP clients, Tor circuits, and the distinct output-reg onion).
   Nothing may "optimize" these into a shared connection — that is the entire privacy model.
3. **Wallet file compatibility.** `wallets/*.mv.db` files are Argon2/ECIES-encrypted H2
   databases with Flyway-managed schemas. Schema changes need a Flyway migration, never an
   in-place format change.
4. **darkjar is vendored, not owned.** Its `com.samourai.*` code and pinned (old) dependencies
   come from Samourai upstream via Sparrow's nightjar. Don't reformat, restructure, or bump
   its dependencies casually — diffability against upstream is the only audit trail. Local
   fixes should be minimal and commented.
5. **Coordinator protocol version** (`WhirlpoolProtocol.PROTOCOL_VERSION = "0.23"`) must match
   the live coordinator; it is not a value to touch client-side.

## 7. Current State (as of 2026-08, v1.4.5)

- Version `1.4.5` (`build.gradle`), developed on branch `v1.4.5`. 1.4.1 is the newest
  *published* release. See the root `RELEASE_NOTES.md` for what changed, rather than
  duplicating it here.
- All four original PLAN.md features are implemented (`receiveBtn`, `badbankInfoLabel`,
  `txnTable` toggle, post-Tx0 navigation all present in `AshigaruWalletController`); PLAN.md
  itself is historical (kept at `docs/PLAN.md`).
- See the root `README.md` "Future Features" section for the current roadmap (not duplicated
  here to avoid this doc drifting out of sync with it).
- Known code TODOs: zeroleak change-index revert and BIP69 output sorting remain unimplemented
  in the vendored darkjar Tx0 construction path (inherited from upstream Samourai code, not
  fixable in-tree without diverging from the vendored source); the `TESTNET4` enum mismatch
  (§4) is still present as of this release.
- Docs: `docs/ARCHITECTURE.md` (system breakdown), this file,
  `docs/ReproducibleBuilds.md`, `docs/SeedEntropyAnalysis.md`, the historical `docs/PLAN.md`,
  and the release checklist at `docs/TESTING.txt`.

## 8. Using the Whirlpool Client in Other Applications

The darkjar Whirlpool client is embeddable: it is a plain `java-library` module with no
Sparrow dependencies (dependency direction is `sparrow → darkjar`, never back). Everything
below is verifiable against the vendored source in `darkjar/` — no upstream repo access
needed (see §8.5).

### 8.1 Getting the library

Build the jar (`./gradlew :darkjar:jar`) and depend on it, or copy the module into your own
build. Its Maven dependencies are ordinary Maven Central artifacts (`darkjar/build.gradle`) —
none of the Whirlpool code itself is fetched from anywhere; it is all in-tree source.

### 8.2 What's provided vs what you implement

**Provided, reusable as-is** (in `com.sparrowwallet.nightjar.*`):

- `JavaHttpClientService` — Jetty-based `IHttpClientService` with per-usage client pooling
  and SOCKS proxy support. Construct with your Tor proxy `HostAndPort`.
- `JavaStompClientService` — STOMP-over-WebSocket for the mix session.
- `WhirlpoolTorClientService` — no-op Tor identity hooks; subclass to wire `changeIdentity()`
  to your Tor controller's NEWNYM (see Sparrow's `SparrowTorClientService` for the pattern —
  ~20 lines). **Do this**: without circuit rotation the dual-identity privacy model degrades.

**You implement** (this is the real integration work):

- `DataSource` (`darkjar/.../data/dataSource/DataSource.java`) — supplies UTXOs, chain
  height, fees, and `pushTx()` broadcast. Don't implement it raw: extend
  `WalletResponseDataSource` and implement one method, `fetchWalletResponse()`, returning a
  `WalletResponse` (balances, UTXOs per account, address indices, chain status) from your
  backend (Electrum, Core RPC, your indexer). Sparrow's `SparrowDataSource` is the reference.
- `DataPersister` — or skip it: the built-in `FileDataPersisterFactory` persists mix state to
  JSON files, which is fine for standalone apps.

### 8.3 Wiring (mirrors `sparrow/whirlpool/Whirlpool.java`)

```java
// 1. Transport + coordinator endpoints
WhirlpoolServer server = WhirlpoolServer.TESTNET;               // or MAINNET
JavaHttpClientService http = new JavaHttpClientService(torProxy, 60000);
JavaStompClientService stomp = new JavaStompClientService(http);
TorClientService tor = new MyTorClientService();                 // NEWNYM hook
ServerApi serverApi = new ServerApi(server.getServerUrlOnion(),
        server.getServerUrlOutputReg(), http);

// 2. Config
WhirlpoolWalletConfig config = new WhirlpoolWalletConfig(
        myDataSourceFactory, http, stomp, tor, serverApi,
        server.getParams(), /*mobile=*/false);
config.setDataPersisterFactory(new FileDataPersisterFactory());

// 3. Wallet from BIP39 seed (purpose 84 for native segwit)
byte[] seed = HD_WalletFactoryGeneric.getInstance().computeSeedFromWords(words);
WhirlpoolWallet wallet = new WhirlpoolWallet(config, seed, passphrase, walletId);
wallet = new WhirlpoolWalletService().openWallet(wallet);        // open() + suppliers

// 4. Operate
wallet.start();                                                  // orchestrators + auto-mix
Collection<Pool> pools = wallet.getPoolSupplier().getPools();
Tx0 tx0 = wallet.tx0(whirlpoolUtxos, pool, tx0Config);           // enter the pool
wallet.mix(whirlpoolUtxo);                                       // queue a premix utxo
```

Observe progress by registering on the client's own bus:
`WhirlpoolEventService.getInstance().register(listener)` with Guava `@Subscribe` methods for
`MixSuccessEvent`, `MixFailEvent`, `MixProgressChangeEvent`, `WalletStartEvent`, etc.

### 8.4 Integration cautions

- The client requires the derivation layout in ARCHITECTURE.md §5 — it derives
  premix/postmix/badbank from the seed itself; your app's wallet model must expect those
  accounts or funds will look "missing".
- `Tx0Config`'s change account should be `WhirlpoolAccount.BADBANK` unless you have a
  deliberate reason otherwise.
- Run everything through Tor; the coordinator endpoints are onion-only in practice.
- The library is RxJava2/`streamsupport`-era Java (Android heritage) — it runs fine on modern
  JVMs but expect Java-8 idioms at the API surface.

### 8.5 Upstream references (and why you don't need them)

The historical provenance: Samourai's `whirlpool-client` / `extlibj` / `soroban-client` lived
on Samourai's own GitLab (offline since the 2024 takedown); Sparrow's `nightjar` bundle lives
on the Sparrow Gitea (`code.sparrowwallet.com`). Neither is required: **the complete client
source is vendored in this repo** under `darkjar/src/main/java/` — the Gitea/Maven
repositories in the build files only serve prebuilt third-party artifacts for the *app*
module, not any Whirlpool code. Reading darkjar *is* reading the upstream client, pinned at
the exact revision this app runs. `whirlpool-client-cli` (Samourai's standalone CLI embedder)
was the canonical example of using this library outside a wallet GUI; if unreachable, the
Sparrow bridge classes in `sparrow/whirlpool/` serve the same illustrative purpose.

## 9. Glossary

| Term | Meaning |
|---|---|
| **Tx0** | Pool-entry transaction: splits a deposit UTXO into uniform premix outputs + coordinator fee + badbank change |
| **Premix / Postmix** | Accounts holding coins waiting to mix / already mixed |
| **Badbank** | Quarantine account for Tx0 change ("doxxic change") — linked to your deposit history, never merged with mixed coins |
| **Mustmix** | A premix input carrying fee headroom; pays the mix's miner fees |
| **Liquidity** | An already-mixed, denomination-exact input remixing for free |
| **Bordereau** | Random one-time token, blind-signed by the coordinator, that authorizes an output registration without linking it to an input |
| **SCODE** | Partner/promo code embedded in the Tx0 fee payload (can discount the coordinator fee) |
| **Doxxic change** | Change from Tx0 that remains linkable to the deposit — what Badbank holds |
| **Cahoots** | Two-party collaborative transactions (StonewallX2 = 2-person coinjoin spend, Stowaway = payjoin variant), negotiated over Soroban |
| **Soroban** | BIP47-encrypted RPC-over-Tor mailbox used as Cahoots transport (not part of the mix protocol) |
| **PayNym** | Directory/social layer for BIP47 payment codes |
| **Mix To** | Routing postmix outputs directly into a different (possibly offline) wallet after N mixes |
