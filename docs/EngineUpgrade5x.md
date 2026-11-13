# Scope: moving the swap engine from eigenwallet 4.16.1 to 5.0.2

**Status: implemented on `xmr-swap` (option A, one password prompt at Review offer), not yet run against a
real maker.** The pin, toolchain, sixth patch, shim and the Java side are in. This note is kept as the
reasoning and the checklist for the real-swap verification.

Why: makers are moving to 5.x, and a 4.x taker cannot set up a swap with them ("no protocols could be
agreed upon"). Today Ashigaru hides those offers and says why. The longer this waits, the fewer offers
there are. This note scopes the upgrade. Nothing has been changed yet.

How this was checked: the pinned source (`be3a2f97`, 4.16.1) was compared with `cc1de7f3` (5.0.2) in a
scratch clone. The five patches in `swap-engine/patches` were applied in order to 5.0.2. Rust and the
Monero build cannot be fetched in the development sandbox (git dependencies are blocked), so **none of
this has been compiled**. Every claim below about the shim is from reading code, and the first real check
is the `Swap build` workflow.

## Size of the gap

- 9 upstream commits, 106 files, +9.4k / -1.4k lines. Almost all of it is one change: `9e43bac`
  "transaction pinning + input proofs". The rest is release housekeeping and Tor/orchestrator changes
  that do not touch the taker.
- Rust toolchain moves from 1.90 to 1.92. `swap-engine.yaml` pins `dtolnay/rust-toolchain@1.90` and must
  follow (a one-line change, but it also changes the build cache key).
- Database: one new migration, an index on swap states. Additive. Persisted swap states use
  `#[serde(default)]` for the new fields, so swaps stored by 4.x still load. Going back to 4.x after
  running 5.x is not something to promise.
- The event surface Ashigaru forwards (`swap/src/cli/api/tauri_bindings.rs`) is unchanged, as is the
  signature of `cancel_and_refund(swap_id, wallet, db)`. The shim's event forwarding, `TimelockChange`
  handling and `resume_all` need no change.

## Patches

All five apply to 5.0.2 textually in order (not yet compiled). They touch Electrum SOCKS5, the event
sink, Electrum client subscriptions, Monero witness nodes and the Monero RPC timeout. A sixth is needed:

6. **Expose the ownership challenge.** `utxo_ownership_proof::Challenge` wraps `[u8; 32]` with a private
   field and no accessor. The shim must hand those 32 bytes to Java to be signed. Add `as_bytes()` (about
   3 lines). Without a patch the only alternative is to rebuild the challenge in Java, which duplicates a
   protocol detail (`swap_id`, both public keys, lock txid, tagged hash) that must then be kept in step by
   hand.

## The shim (`swap-engine/ashigaru-swap`)

The `BitcoinWallet` trait changed. Both `QuoteOnlyWallet` and `HybridWallet` need:

| Change | Work |
|---|---|
| `send_to_address(..., max_inputs: Option<usize>)` and `send_to_address_dynamic_fee(..., max_inputs)` | pass the cap in the `fund_lock` request; Badbank selects at most that many inputs |
| `max_giveable(script_size, max_inputs)` | pass the cap in the `max_giveable` request |
| `swap_fee_rate() -> FeeRate` (new) | answer from Ashigaru's `fee_rate` request. It must be an uncapped rate, used for the presigned cancel, refund and redeem transactions |
| `prove_utxo_ownership(&Psbt, Challenge) -> Result<Vec<OwnershipProof>>` (new, **synchronous**) | see below |

Things that are not trait changes but need checking when it compiles: `bob::Swap::new` and `from_db`
(unchanged in the diff from what was read), `VerifyPsbtPrevouts` (new, used when the shim acts as a
maker; the taker side does not implement it), and `check_lock_psbt`, which must still match the
maker's `TxLock::from_psbt` checks (two outputs at most) plus the new input cap.

### Ownership proofs are the real design change

In 5.x the taker proves, during swap setup, that it controls the keys behind every lock input:

```
setup_swap -> State0::receive -> TxLock::new (asks for fund_lock)
                              -> wallet.prove_utxo_ownership(psbt, challenge)   <-- NEW, needs private keys
           -> SwapSetupCompleted -> sign_and_finalize (the "Prepare" step today)
                                  -> approval (the "Commit" step today)
```

The proof is an ECDSA signature per input over a 32-byte domain-separated challenge. It cannot be
used as a transaction signature, but it does need the Badbank private keys, and it happens **before**
today's signing prompt, right after the user picks an offer. Constraints:

- **P2WPKH inputs only.** Badbank is a Whirlpool account, so its coins are native segwit and this should
  hold, but `fund_lock` must refuse to select anything else rather than fail inside the engine.
- **Maximum 25 inputs** from the taker. `max_giveable` and `fund_lock` must honour the cap. A Badbank with
  many tiny coins gets a lower maximum swap amount than its balance.
- **The trait method is synchronous**, called from async code. The shim's bridge is async. It needs
  `tokio::task::block_in_place` with `Handle::block_on`, which works because the engine runs on a
  multi-thread runtime (`lib.rs`). The call must finish quickly: the maker is waiting inside the setup.
- **Java needs a new request type** (`prove`: psbt, 32-byte challenge, returns public key and signature per
  input). Drongo gives the keys through the wallet's keystore and derivation paths; ECDSA over a raw
  digest needs checking against what Drongo exposes (it signs message hashes and sighashes today).

### Decision for you: when to ask for the password

Because proofs come first, the wallet must be unlocked while the setup is running. Options:

- **A. Unlock once, up front (recommended).** The password is asked when the user presses "Review offer"
  (or "Continue"), the keys are held in memory only for the length of that one swap setup, then wiped. The
  proof is then instant and the later "Sign" step needs no second prompt. This is also better for the
  clock (below). Cost: the password dialog moves earlier, and the in-page review's Prepare stage becomes a
  confirmation of the already-built transaction, not the moment of signing. `SwapReview` and the dialog
  fallback change with it.
- **B. Ask when the proof is requested.** Smaller change to the review flow, but a password dialog appears
  by itself in the middle of a network negotiation, and the maker may time out while the user types.

### The clock gets tighter

- The pre-lock approval timeout drops from **180 s to 90 s** (`PRE_BTC_LOCK_APPROVAL_TIMEOUT_SECS`).
- The maker now also holds the setup open for at most **180 s** (`SWAP_SETUP_DECISION_TIMEOUT`). Signing
  happens inside that window, so password entry plus review must fit in it, and the approval itself in 90 s.
- Java's review countdown (170 s, and the "180 second" wording in `docs/SwapUiPlan.md`, `SwapCopy` and the
  dialog) must be re-derived. With option A the password is out of the window, leaving the review roughly
  80 to 85 s on screen. That is shorter than today and the review screen should say so.

## Java and wording changes

- `SwapTimelock.CANCEL_BLOCKS` 24 to **30** (about 5 hours, not 4). `SwapCopy.cancelAfter()` and every
  string derived from it, plus `SwapTimelockTest`, `SwapCopyTest`, `SwapDashboardTest`.
- Punish timelock is **144** in 5.x too, so "about 24 hours" stands. A new **remaining-refund timelock of
  2 blocks** (about 20 minutes) governs the partial-refund deposit window. Check against the existing
  "about 30 minutes" deposit wording before reusing it.
- `SwapEngine.Quote`: `MIN_MAKER_VERSION` becomes 5.0.0 and `FIRST_UNSUPPORTED_MAKER_VERSION` goes away
  (4.x makers become the incompatible ones). `isTooNew()` becomes unnecessary; `isOutdated()` carries the
  filter. Reword the footer, the empty state and the `stopReason` special case, and `QuoteTest`.
- `BadbankSwapHost`: new `prove` handler, `max_inputs` in `fund_lock` and `max_giveable`, `swap_fee_rate`
  via the existing `fee_rate` request, the P2WPKH and 25-input rules in coin selection.
- New cancel and refund transactions are TRUC (version 3). Confirm the Electrum servers Ashigaru's Tor
  path uses relay them; eigenwallet's own list is what the patches default to.
- Docs: `SwapUiPlan.md` ("Known limit" section is replaced), `SwapEngineSpike.md`, `RELEASE_NOTES.md`.
- Screenshots (`SwapScreenshotsTest`): countdown frames and the timelock strings change.

## Risks

1. **Nothing here has been compiled.** The trait impl, the patch rebase and the block_on bridge are
   untested until CI runs. Expect two or three CI rounds, each of which is a full multi-platform build.
2. **Password timing UX** (above) is a product decision with security weight: keys held in memory for the
   duration of a setup.
3. **Real-swap verification is mandatory and costs real BTC.** A mainnet swap against a 5.x maker is the
   only way to test proofs, the TRUC cancel and refund, the 90 s window and `TimelockChange` at 30 blocks.
4. **Hard cut-over with makers.** After the upgrade, 4.x makers cannot be used. If a meaningful share of
   makers is still on 4.x on the day, offers drop instead of rising. Worth counting the version split in
   the current quote list before choosing the date.
5. **Swaps in flight.** A swap started on 4.x and resumed by 5.x should load, but this is untested. Ship
   with advice to finish or refund swaps first, and test resuming a 4.x record on the new engine.
6. **Upstream is moving.** 5.0.1 and 5.0.2 were two days apart. Pin 5.0.2, not a branch, and check
   upstream issues for known taker bugs before the pin is final.

## Plan and effort

Estimates are rough and assume CI is available and a 5.x maker can be reached.

| # | Step | Estimate |
|---|---|---|
| 1 | Bump pin to `cc1de7f3`, toolchain to 1.92, rebase patches, add patch 6, get the Rust workspace compiling | 1 to 2 days (CI-bound) |
| 2 | Shim: new trait methods, `max_inputs`, `swap_fee_rate`, synchronous proof bridge, unit tests | 1 to 2 days |
| 3 | Java: `prove` handler and key signing, input rules in `fund_lock` / `max_giveable`, tests | 2 days |
| 4 | Java: unlock-up-front flow, `SwapReview` and fallback dialog changes, new countdown, screenshots | 2 to 3 days |
| 5 | Constants, version gate, wording, docs, release notes | 1 day |
| 6 | Mainnet swap with a 5.x maker, then cancel and refund path (needs a stalled swap) | 1 to 2 days elapsed |

About 1.5 to 2 weeks of work, most of the uncertainty in steps 1 and 6. Steps 1 and 2 can be done and
built before any UI decision; step 4 depends on the password decision.

## Recommended order

1. Merge `xmr-swap` as it is. It is honest about the 4.x limit and is what exists to test.
2. Do the upgrade on its own branch from `xmr-swap`, steps 1 and 2 first so CI starts as early as possible.
3. Decide A or B for the password before step 4.
4. Release the upgraded engine only after step 6 passes. Until then keep 4.x makers and the 5.x notice.
