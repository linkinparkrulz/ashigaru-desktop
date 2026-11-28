# Copy: how Ashigaru talks

One voice, everywhere: the sidebar, the wizard, the swap screens, the dialogs, the docs. These rules are short
on purpose. `CopyConsistencyTest` enforces the mechanical ones, so if a build fails on copy, this is the file it
is pointing at.

## 1. Sentence case

Labels, buttons, menu items, dialog titles and card titles are sentence case.

| Yes | No |
|---|---|
| `New / Restore…` | `New / Restore` (as a button) |
| `Mix To…` | `Mix To...` |
| `Hot wallet`, `Watch-only` | `Hot Wallet`, `Watch Only` |
| `Manage aliases…` | `Manage Aliases...` |

ALL CAPS belongs to section eyebrows only — the small label at the top of a panel, like `PREPARE COINS FOR
MIXING` or `NEW / RESTORE WALLET`. Nothing else.

## 2. Ellipsis

Use the single character `…`, never three full stops. It means "this opens something" — a dialog, a file
picker, a menu — and it means nothing else. A message that is waiting on something does not get one either:
`Calculating…` is a status, so it stays as words, not as an affordance.

## 3. Errors: what failed, then what to do

Three parts, always in this order.

1. **Title** — the thing that failed, sentence case, no "Error".
   `Password not verified`, `Tx0 preview not calculated`, `Transaction Zero not broadcast`.
2. **Body** — `Could not <verb> <object>.` followed by one sentence saying what to do next. No "please" in an
   error; "please" asks, and an error tells.
3. **Details** — the exception, logged. Never pasted into the body. The body ends with
   `See Tools → Logs for the details.` when there is something to read there.

Never `Failed to…`, never `Error fetching…`, never a raw `e.getMessage()` in a dialog.

## 4. One name per thing

| Thing | Name | Never |
|---|---|---|
| The change account | `Badbank` | `Bad Bank`, `Bad bank` |
| A wallet with no keys | `Watch-only` | `Watch Only`, `watch only` |
| Splitting coins | `Tx0` | `TX0`, `tx0` |
| The mixing accounts | `Deposit`, `Premix`, `Postmix`, `Badbank` | lowercase, or `Pre-mix` |
| Starting the engine | `Start Mixing` | `Start Mix` |
| The offer's price term | `premium` / `discount` | `price delta` |

`Bad Bank` survives in exactly one place: `SentinelPairing`'s key names, because Sentinel shows those names on
its own screen and the two apps have to agree. That is interop, not wording.

## 5. Don't say please, and don't say sorry

`Please enter a name` → `Enter a name for the wallet.` An instruction is already polite.

## 6. Say where to look

Point at a real place, with the path a person can follow: `Tools → Logs`, `Settings → Swaps`. Never "the log
file" or "the Help menu" on their own.
