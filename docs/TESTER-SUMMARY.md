# What to test in this build

Branch `claude/funny-cannon-itky0u`. Everything below came from tester reports or the eigenwallet 5.0.4 update.
Items marked **(swaps)** need a build that includes the swap engine (the Package workflow's `swap_engine` option);
the first build with 5.0.4 has not been compiled or run against a real maker yet.

**Before you update:** finish or refund any swap that is still running. The engine changed underneath it.
**Never enable eigenwallet's "on-chain backup" (Apollo)** if you run eigenwallet separately: after it has run,
older versions cannot open the swap database.

## 1. Swaps on engine 5.0.4 (swaps)

Start with a small coin. Report the maker you used and what the screen said.

- [ ] A normal swap with a 5.0.4 maker goes through. Makers on 5.0.4 should no longer fail with
      "Anti-spam deposit too small".
- [ ] **Price warning.** If the maker's final terms give you more than 2% less XMR than the offer you clicked,
      a "Price changed" window shows both XMR amounts and the difference.
  - [ ] "Reject swap" is the red default button. Enter, Esc and the window's X all reject.
  - [ ] It counts down and closes as a rejection when the approval time runs out.
  - [ ] "Accept the worse price" leads to the normal review, which says "You accepted a price X% below the offer".
- [ ] **Refusal.** More than 5% less XMR, or a changed refund policy: no window, the swap stops, and the dashboard
      says why. Your coin is still in Badbank.
- [ ] Nothing is locked until you press Approve on the final review, in every case above.
- [ ] A maker that refuses is left out of the offers for that coin and the stopped swap says why.
- [ ] Swaps say which Monero nodes they use. With your own node ticked, only it is used (the status says so).
- [ ] The Monero node settings are greyed out while swaps are on, like the Bitcoin server. "Edit nodes" turns
      swaps off so you can change them (unless a swap is running).

## 2. Start-up and Tor

- [ ] **Linux Mint:** Ashigaru starts and stays open even while Sparrow Wallet is running, or after a crash left
      a process behind. It used to quit about a second after launch with no window.
- [ ] **Mac:** Settings → Logs → copy the diagnostics block. Look for the Tor lines. `CompiledProofOfWorkHash`
      should read `0 (set)`. Send the whole block if it does not, or if Tor does not connect.
- [ ] The swap engine's log shows why it did not load, if it did not (older Macs).

## 3. Verifier (Tools → Verifier)

- [ ] Paste a whole signed message into the single box: it is read as you paste, with no Parse button.
- [ ] The result card sits beside the inputs. Verify and the result are visible at the smallest window size.
- [ ] Pasting a second block replaces the first. Changing anything blanks the result.
- [ ] Ctrl+Enter verifies; Tab moves between boxes; Paste and Clear work.
- [ ] A message naming a different BIP47 payment code than the one you are verifying is refused.

## 3b. Sign (Tools → Sign), new

- [ ] A **Sign** tab sits beside Verifier. With a seed wallet open it is already chosen and shows the payment code
      and notification address. Watch-only and hardware wallets are not listed.
- [ ] Enter a message, press **Sign Message** (Ctrl+Enter), and copy the signed block.
- [ ] Paste the block into **Verifier** with the same payment code: "Signature verified" and "The message names
      this payment code".
- [ ] The note under the message says what line is added; untick "Add my payment code" and it is signed as typed.
- [ ] If you can, check the block in another BIP47 wallet's verifier and tell us which one.

## 4. Smaller fixes from your reports

- [ ] The "?" help marks (passphrase and elsewhere) show their text on a click, and close on a second click.
- [ ] Hovering the dots on a coin explains them (completed mixes, not bits of entropy).
- [ ] A coin waiting for Whirlpool says "awaiting a round".
- [ ] The "Leaving Tor" dialog closes with the window's X and has a Cancel button.
- [ ] The amber note on Badbank is no longer cut off.
- [ ] Tx0: Start Mixing is the red button, Mix To... and Preview Tx0 are neutral, and the three are the same
      width. Preview Tx0 turns green once a Deposit coin is ticked.
- [ ] Tx0: every amount has eight decimals, so the Anti-Sybil fee reads 0.00125000. The caption is not cut off.
- [ ] The wallet's "..." button does not run over the sidebar divider when the wallet has a picture.
- [ ] Statistics pool badges are pills.

## What to send back

For anything odd: the screen, the steps, and Settings → Logs → the saved report (it now says whether swaps are on,
whether the engine loaded and what the Swaps screen reports). For a swap: the maker, the amount and the swap id.

## Known limits

- The 2% and 5% limits follow eigenwallet's 5.0.4 release notes. The wording of the dialog is Ashigaru's own.
- The price check cannot be tried without a maker that moves its price, or a test build that fakes the terms.
