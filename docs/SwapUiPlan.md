# Swap UI refinement plan

How the swap screens are being reworked ahead of merging `xmr-swap` into `main`. The aim is not more
features. It is making the swap easier to read, harder to misunderstand, and honest about recovery.

## Decisions

- **Final review is a proper screen.** Signing ("Prepare") and approving ("Commit") are two stages of one
  in-page panel with a pinned action bar. Only the wallet password stays a focused dialog. If the Swaps
  screen is not visible when a request arrives, it falls back to a modal so a request cannot expire
  unseen.
- **The dashboard answers five questions:** where are my funds, do I need to act, can I close Ashigaru,
  what if it stalls, when does recovery open. Five named milestones replace the continuous bar:
  Review and authorize, Lock BTC, Verify the maker's XMR, Claim XMR, Complete. Protocol steps stay in a
  collapsed section.
- **Offers are compared by what you would receive.** A four-column table: Estimated XMR, If cancelled,
  Maker and rate, action. Sorting is explicit. Badges sit beside the metric they describe. The market
  rate is shown as a reference, not an offer.
- **Unfinished swaps come first.** An attention notice above the offers links to the recovery screen.
  "Cancel and refund" lives only on that screen.
- **Typography.** Nikkyou Sans for titles and key headings only, Roboto for interface text (14px for
  anything a user must understand, 13px for supporting detail, nothing smaller), Roboto Mono for amounts,
  addresses and ids. This applies across the app, not only to swaps.
- **Window size.** Minimum 1200 and default 1280 (always clamped to the screen). Swaps sits inside Tools,
  which adds a 190px menu beside the 236px sidebar, so at today's 1100px window Swaps gets about 613px
  with the menu open. The menu starts collapsed (58px) and most users keep it so, which gives about 745px
  at 1100 and 925px at 1280. The offers table needs about 700px.

## Engine facts (mainnet, pinned `EIGENWALLET_REV`)

- Cancel timelock 24 blocks (about 4 hours). Punish timelock 144 blocks (about 24 hours), counted from
  the cancel confirming. (`docs/SwapEngineSpike.md` says 72; the code says 144.)
- The 180 second approval timeout starts when the final approval is requested, after signing.
- A **running** swap cancels and refunds by itself once the timelock allows. The engine refuses a manual
  cancel while the swap runs, and before the cancel timelock expires.
- Every unfinished swap resumes automatically whenever the engine starts.
- `cancel_and_refund` is one call that publishes the cancel and the refund and blocks until done. It is
  for swaps that are **not** running.
- The engine emits `TimelockChange` events (`None{blocks_left}`, `Cancel{blocks_left}`,
  `WaitingForRemainingRefund{blocks_left}`, `RemainingRefund`, `Punish`). Ashigaru forwards every event
  but ignored this one.
- The anti-spam deposit: after a partial refund the maker may withhold it for a short window (about 30
  minutes). Otherwise the taker claims it back.
- The completion event carries only the XMR transaction id, so there is no confirmation count to show.

## Wording rules (one source of truth)

- Refund is an **estimate**: "up to", "minus network fees". Never "you get back" as a final figure.
- Recovery depends on whether the swap is running:
  - **Running:** Ashigaru cancels and refunds by itself. The user needs to do nothing except keep it
    running.
  - **Stopped, cancel open:** the user presses Cancel and refund.
  - **Stopped, cancel not open yet:** the button is disabled and says when it opens.
- Times are always "about", derived from blocks left (about 10 minutes per block). Never an exact time.
- The deposit is stated as "the maker may keep X%" with the BTC amount, in its own callout.
- "Estimated XMR" until the maker confirms terms. "You receive" only where the amount is final.
- A completed swap says "XMR sent". Spendability is general Monero guidance, not a live count.
- "Priced below market" is not user wording. Hidden offers are "offering more XMR than the market pays".

## Status

Built, in this order: the engine-backed data and wording, the shell (window size and typography), the
in-page final review, the dashboard and the offers screen. Each is its own commit. Every state of every
screen is rendered and checked by `SwapScreenshotsTest` (see `docs/DEVELOPMENT.md`).

## Build order

1. Engine-backed data and wording: read timelock events, shared copy, fix the confirmed dialog text.
2. Shell: window size, bundled Roboto, default face, sizes.
3. In-page final review.
4. Dashboard: milestones, summary, three recovery variants, completion receipt.
5. Offers: table, notice, empty states, address field.
6. Tests, release notes, merge cleanup.

## To check on a real swap

These need the swap engine, Tor and live makers, which the automated tests cannot reach:

- Sign and approve in the page, the password dialog opening over it, and the countdown reaching the
  engine's own 180 second timeout.
- Leaving the Swaps screen mid-review: the same request must come up as a dialog, with only the
  remaining time.
- A swap that stops after the lock: Ashigaru restarted, the swap found again and shown, the notice above
  the offers, and Cancel and refund working once the cancel timelock has expired. The engine's call blocks
  until the refund is published.
- A swap running through the cancel timelock: Ashigaru cancels and refunds by itself, with no button.
- The countdown against real blocks, and that the `TimelockChange` events arrive.

## Known limit: the engine is on eigenwallet 4.16

eigenwallet 5.0.1 (2026-10-30) made breaking protocol changes: takers prove they own the lock's inputs, the
cancel and refund transactions were changed against pinning, and **both peers must upgrade to start a new
swap**. Makers are moving to 5.x. Ashigaru's engine (`swap-engine/EIGENWALLET_REV`, with five patches) is
still on a 4.16 revision, so it cannot set up a swap with a 5.x maker: the setup fails with "no protocols
could be agreed upon" after about two minutes of retries. Offers from makers on 5.x or later are therefore
left out of the table and counted in the "not shown" line, and a setup that fails this way says why.

As more makers upgrade, fewer offers remain. **The engine needs updating to 5.x**, which means rebasing the
patches and checking the shim against the new API. The work, risks and an estimate are in
`docs/EngineUpgrade5x.md`. When it does:

- The mainnet cancel timelock becomes 30 blocks, not 24. `SwapTimelock.CANCEL_BLOCKS` and the "about 4
  hours" wording that comes from it (`SwapCopy.cancelAfter()`) must change, and `FIRST_UNSUPPORTED_MAKER_VERSION`
  and the version check in `SwapEngine.Quote` must move with the engine.
- Re-read the timelock values (including the punish timelock) from the pinned source before trusting the
  "about 24 hours" refund window.

## Still to verify

- Exactly how the deposit is released after a partial refund (wording is deliberately "may keep").
- What happens if Ashigaru closes between the cancel and the refund, and whether the maker publishing
  the cancel starts the refund window without Ashigaru's action.
- Whether the displayed XMR estimate also nets Monero's claim fee.
- Whether a stopped, unresolved swap should block starting a new one (today only a running swap does).
