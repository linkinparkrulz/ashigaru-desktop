# Privacy score powered by am-i.exposed (shelved)

**Status: shelved. Nothing here is built, and no code depends on it.** This file records what was
learned and decided so the work can be picked up cold. File and line references are to the tree at the
commit that added this file; lines drift, so search by name if one has moved.

## 1. Goal

Replace the guesswork behind the wallet's "privacy wheel" (the donut, legend and sidebar numbers on the
wallet screen) with a real transaction analysis from [am-i.exposed](https://github.com/Copexit/am-i-exposed).

Decisions made so far:

- The user runs **their own am-i.exposed instance**, set in General settings, backed by **their own
  local mempool.space**.
- **am-i.exposed does the Whirlpool detection** (Tx0, premix, mixed coins), not Ashigaru.
- A zpub for an account can be opened directly in that instance:
  `https://<instance>/#xpub=<zpub>`.

## 2. How the score works today

It is not an analysis. It never looks at a transaction.

- A coin counts as **private** if it is a Whirlpool Postmix UTXO on the **receive** chain
  (`WalletPrivacy.isMixedCoin`, `gui/WalletPrivacy.java:25`). Postmix coins on the change chain count as
  unmixed change. The mix count does not enter the percentage: a Postmix receive coin with one mix is
  fully "private".
- `WalletPrivacy.of(WalletForm)` (`:34`) reads `Wallet.getWalletUtxos()` for the Deposit (master) wallet
  and for the Premix, Postmix and Badbank child wallets, and sums sats and UTXO counts per account.
- Derived values: `total()`, `privateSats()` (Postmix minus change), `unmixedSats()` (Deposit plus
  Postmix change), `privateShare()` = `privateSats / total`.
- The percent shown is `Math.round(privateShare() * 100)`
  (`AshigaruWalletController.java:163`). There is no per-account percent and no grade.
- Mix counts come from `UtxoEntry.MixStatus.getMixesDone()`, which prefers stored `UtxoMixData`, then a
  recount through the Whirlpool instance, then a hard-coded 1 for Postmix. The "private enough" mix
  threshold is `Whirlpool.DEFAULT_MIXTO_MIN_MIXES = 3` or the wallet's `MixConfig`. It drives the green
  pips and the Whirlpool view rings only, not the donut.
- It runs entirely from local wallet state. Before the wallet history has synced it shows whatever was
  loaded last, and 0% when the total is 0.

Where it is displayed:

| Place | Code |
|---|---|
| Hero panel "WALLET PRIVACY": donut, percent, legend (PRIVATE / MIXING / UNMIXED / BADBANK) | `ashigaru-wallet.fxml:42-57`, `AshigaruWalletController.showPrivacy` (`:160`), `PrivacyDonut.show` |
| Sidebar account rows (balance, "N mixed · M change") | `AshigaruMainController.buildPipeline` (`:207`), `refreshPipeline` (`:239`) |
| Per-coin pips, tooltip and badge | `AshigaruUtxoCell` |
| Whirlpool tab rings | `WhirlpoolView` |
| Mix-selected button tooltips | `AshigaruWalletController` |

`refreshPipeline()` is the single point that builds the score; it runs when a wallet opens and when
history finishes. **No test covers `WalletPrivacy`.** `WalletPrivacy.of` takes a `WalletForm`, which is
hard to construct, so a test would need wallets and nodes built by hand, or a refactor to plain data.

## 3. What am-i.exposed is

Facts below come from the project's own README, LICENSE, CLI README and `docs/privacy-engine.md`. Where
a point rests on a model summary of a long page, it is marked.

- **License:** MIT, copyright 2026 Copexit. Redistribution is allowed with the notice.
- **Stack:** Next.js static export (no server), TypeScript, Boltzmann entropy in Rust compiled to WASM.
  "No backend": the analysis runs in the browser.
- **Data source:** the **mempool.space API only**. The API base can be changed in the web app's
  settings, and the CLI takes `--api <url>`. It routes through the mempool.space onion address when run
  over Tor.
- **Heuristics:** the README says 34 heuristics (28 transaction, 6 address) and 10 chain-analysis
  modules; the engine doc says 5 wallet heuristics and 12 modules. The counts disagree, so treat them
  as roughly three dozen. Examples: address reuse, common-input ownership, change detection, round
  amounts, CoinJoin detection (Whirlpool, WabiSabi, JoinMarket, Stonewall, Wasabi 1.x), fingerprints,
  timing, entity matching, taint, Boltzmann entropy.
- **Score:** each finding adds a modifier to a base (70 for a transaction, 93 for an address), clamped to
  0-100. Grades: A+ 90-100, B 75-89, C 50-74, D 25-49, F 0-24. Deterministic links cap the grade at F.
  Only privacy-enhancing signals raise a score.
- **Whirlpool:** a mixed transaction has 5-10 outputs with 5 or more equal at 50,000, 100,000, 1,000,000,
  5,000,000 or 50,000,000 sats and at most one other. Tx0 premix: 1-3 inputs, 2+ denomination outputs
  (from the summary, not verbatim). Badbank handling is not stated.
- **Wallet scans:** xpub, descriptor, zpub (the CLI example uses a zpub), BIP44/49/84/86 derivation.
  Per the engine doc each unspent coin gets a class (for example "Mixed (CoinJoin)") and a
  `utxoOrigins` breakdown. It also says holding mixed and unmixed coins is "shown, not scored", so the
  engine may not produce a "percent private".
- **Entity labels:** 364 named entities and an index covering 30M+ addresses; a ~6 MB core index and an
  optional ~92 MB full index. The label data's own source licensing is not stated.
- **Maturity:** about 700 commits, two named authors, and described elsewhere as experimental and
  changing daily. The CLI's JSON for xpubs is not documented.
- **Interfaces:** the web app; a Node CLI (`npm i -g am-i-exposed`, Node 20+) with
  `scan tx|address|xpub|psbt --json`, `boltzmann`, `chain-trace`, `--api`, `--network`, `--gap-limit`,
  `--no-entities`, `--no-cache`; an MCP server (`scan_transaction`, `scan_address`, `scan_psbt`,
  `scan_wallet`, `compute_boltzmann`); a Bun standalone binary build; an Umbrel app.

## 4. Facts that shape the design

Verified:

- **The instance URL cannot be queried for a score.** It is a static site. The only machine-readable
  route is the CLI with `--json` (output has `score`, `grade`, `txType`, `findings[]`, `recommendation`).
- **`#xpub=` is a documented deep link.** A URL fragment is never sent to the web server; the page's
  JavaScript reads it and then asks the configured mempool API for the account's addresses. So the key
  reaches only that mempool server, which is safe only if the instance and its mempool are the user's own.
- **The result stays in the page.** The web app has no JSON export, postMessage or API stated, so
  Ashigaru cannot read a grade back from a deep link.
- **Ashigaru has no JavaScript runtime or WebView.** `javafx-web` is excluded (`build.gradle:111`) and the
  jlink image carries only base, graphics, controls, fxml and swing. Embedding the engine would add a
  runtime to every platform package.
- **The wallet cannot score from its own data alone.** It stores only transactions that touch its own
  scripthashes; foreign parents (the funders of a received coin, the other participants of a CoinJoin)
  are not stored, so input-side analysis needs a lookup. An Electrum server can supply them
  (`ElectrumServer.TransactionReferenceService`), at one request per transaction.

Not verified (check first when resuming):

- Whether the user's instance **starts scanning automatically** when opened with `#xpub=`. The user
  reports it does; upstream docs name `#xpub=` only as the form a wallet bookmark holds.
- Any deep link beyond `#xpub=` (the app's existing link uses `#tx=<txid>`).
- The CLI's JSON for an xpub, and whether it separates Premix, Postmix and Badbank or gives per-UTXO
  classes.
- Whether the web app can carry a custom mempool API in the link (it is a setting upstream).

## 5. Pieces already in Ashigaru to reuse

- **The am-i.exposed setting already exists.** `general.fxml:58` (`amIExposedServers`),
  `GeneralPreferencesController.java:129-183`, `Config.getAmIExposed()` (`Config.java:282`),
  `isAmIExposedDisabled()` (`:278`). It accepts a custom entry. It is used only for link-outs today.
- `AppServices.openAmIExposed(txid)` (`AppServices.java:1058`) builds `<instance>/#tx=<txid>` (or fills a
  `{0}` template) and sends it through `openExternalLink` (`:1032`), which the GUI routes to
  `ExposureDialogs.openOutsideTor` (`gui/ExposureDialogs.java:124`).
- `ExposureDialogs.showWhoLearnsWhat` (`:29`) lists who sees what. Any new third party needs an entry
  via `addParty`.
- `ExtendedKey.toString(Header)` (`drongo/.../ExtendedKey.java:40`) renders an account key as zpub.
- `WalletForm.getNestedWalletForms()` is how `WalletPrivacy.of` already walks the Whirlpool accounts.
- The background-service pattern in `swap/SwapService.java` for any process or HTTP work off the FX
  thread.
- **A problem to fix on the way:** the built-in default is plain `http://am-i.exposed/#tx={0}`
  (`net/AmIExposed.java:6`).

## 6. Options considered

| Option | What it is | Verdict |
|---|---|---|
| Port heuristics to Java | Re-implement in Java, credit upstream under MIT | Fully offline, but the largest job and it drifts from upstream |
| CLI sidecar | Run the Node CLI locally, read `--json` | The only way to get a grade back into Ashigaru; needs Node 20+ and a spike first. Kept as Phase 2 |
| Hosted or own instance over Tor | Call a hosted instance per transaction | A static site has no API; a public one would learn the txids and addresses |
| HTTP wrapper | A small JSON service around the CLI next to the instance | Works across machines, adds a service the user must run |
| Embed the JS engine | GraalJS or WebView | No runtime in the app today; heavy |
| **Deep link per account** | Open `<instance>/#xpub=<zpub>` | **Chosen for Phase 1.** Simple and does use the real engine; the grade stays in the browser |

## 7. The chosen design

### Phase 1: "Check privacy" per account

1. **Link builder** `AmIExposedLinks` (small, pure, unit-tested): from the configured instance URL take
   the base (drop any fragment and `{0}` template) and produce `<base>/#xpub=<zpub>`. Keep
   `openAmIExposed(txid)` for single transactions.
2. **Account keys:** for Deposit, Premix, Postmix and Badbank take the keystore's account extended public
   key and render it with `ExtendedKey.toString(Header)` as zpub. No private data is involved, so
   watch-only and hardware wallets work too.
3. **UI:** a "Check privacy" action on each account row in the sidebar and a "Check this wallet's
   privacy" button in the hero panel. Hidden when am-i.exposed is set to None. The tooltip says it opens
   the user's am-i.exposed.
4. **Exposure dialog, made accurate.** Today `openOutsideTor` says the *host* learns the request and the
   IP. For an xpub link the real exposure is the instance's mempool server learning **every address of
   the account**. Two cases:
   - **Own instance** (loopback, private address, `.local`, `.onion`): open, with a note that the instance
     will look up the account's addresses on its mempool server.
   - **Anything else, including the public default:** a strong warning that every address of the account
     is revealed, with Cancel as the default.
5. **"Who learns what"** entry describing exactly that.
6. **General settings text** beside the field: for account checks it should be the user's own instance.
   Change the built-in default to `https://`.
7. Release notes entry.

The existing basic score stays and is labelled as a basic count of mixed coins, so it is not mistaken for
the am-i.exposed grade.

### Phase 2 (optional): bring the grade into the app

Only after a spike. Run `am-i-exposed scan xpub <zpub> --json --api <local mempool>` on the four account
zpubs of a Whirlpool wallet and save the output as fixtures. If it returns per-UTXO classes and a grade
per account, add an optional runner:

- `privacy/AmIExposedEngine` (`ProcessBuilder` with an argument array, no shell; timeout; output cap;
  Gson parse tolerant of unknown fields), `AmIExposedReport`, and `PrivacyScoreService` (off the FX
  thread, cached by a fingerprint of the account's txids so it re-runs only when history changes).
- Settings: CLI path, mempool API URL, enable box, a Test button. Off by default; no process is started
  unless both are set.
- Show the grade beside the donut and as sidebar chips, and use the engine's mixed/unmixed classes for
  the donut when present. Fall back to the basic score, with a visible "engine unavailable" state, on any
  failure. Never show a stale grade.

## 8. Privacy rules to keep

1. Never default an account scan to the public mempool.space. An account scan sends every address.
2. If the mempool API host is not loopback, a private address or `.onion`, say so before anything is sent.
3. Pass account keys only to the user's own instance or a local process. Never log them and keep them out
   of the Logs diagnostics.
4. Describe the exposure honestly in the dialog and in "Who learns what".

## 9. Open questions for when this resumes

1. Does the user's instance auto-scan when opened with `#xpub=`, and does it accept a custom mempool API
   in the link or only in settings?
2. What does the CLI return for a Whirlpool account: per-UTXO classes, a grade per account, how Badbank
   is treated, and whether Premix and Postmix scan as separate accounts?
3. How do Whirlpool accounts' zpubs behave with the instance's derivation and gap limit?
4. Is Phase 2 wanted at all, or is the grade in the browser enough?
5. Later: bundle the Bun standalone binary so the CLI needs no Node? (MIT permits it. It would need a
   provenance entry in `docs/NativeBinaries.md` and a notice in `THIRD_PARTY_NOTICES.md`.)

## 10. Files to touch

- Modify: `AppServices.java`, `net/AmIExposed.java`, `gui/ExposureDialogs.java`,
  `gui/AshigaruMainController.java`, `gui/AshigaruWalletController.java`, `gui/ashigaru-wallet.fxml`,
  `preferences/general.fxml`, `GeneralPreferencesController.java`, `RELEASE_NOTES.md`.
- New: `net/AmIExposedLinks.java` and `AmIExposedLinksTest.java`. Phase 2 adds the `privacy` package and
  test fixtures.

## 11. Verification checklist

1. Unit tests: link builder for the default URL and for a custom instance with and without `{0}`,
   fragment and trailing slash; the local-versus-public classifier used by the dialog.
2. `./gradlew test` passes.
3. With the instance configured, "Check privacy" on each of the four accounts opens
   `<instance>/#xpub=<zpub>` and the page scans it. The zpub matches the one in the wallet's settings.
4. With the instance unset or None, the actions are hidden. With the public default, the strong warning
   appears and Cancel is the default.
5. Search the app log after a check; nothing about the key is logged.
6. Phase 2 only: no process starts when unconfigured; a public API URL is refused or confirmed; a missing
   CLI, a timeout or bad JSON falls back to the basic score.

## 12. Sources

- [am-i.exposed repository](https://github.com/Copexit/am-i-exposed) (README, LICENSE, `cli/README.md`,
  `docs/privacy-engine.md`)
- [Umbrel app listing](https://apps.umbrel.com/app/am-i-exposed)
- [Delving Bitcoin: privately running wallet privacy heuristics on your own UTXOs](https://delvingbitcoin.org/t/privately-running-wallet-privacy-heuristics-on-your-own-utxos-without-needing-to-run-your-own-node/2625)
