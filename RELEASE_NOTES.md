# Ashigaru Desktop 1.5.1

*Released 2026-10-29*

A fix for Tor, which stopped connecting for everyone on 2026-10-28.

## Tor

- **Tor connects again.** Ashigaru bundled Tor 0.4.7. On 2026-10-28 the Tor network began
  requiring the `Desc=3` and `Microdesc=3` protocols, which Tor releases before 0.4.9 lack. They
  read the network's consensus, find a protocol they cannot speak and shut down, so Ashigaru
  could not connect at all. The bundled Tor is now 0.4.9.5. The Tor binaries Ashigaru unpacked
  before are deleted the first time the new version starts.
- **Linux ARM64 uses the same Tor as every other platform.** It used to need a separately
  built Tor binary.

# Ashigaru Desktop 1.5.0

*Released 2026-10-23*

Fixes for the window on Linux desktops, restoring wallets made with a short passphrase or none, a
way to restart Tor without restarting the app, and a much shorter list of downloads.

## Window and layout

- **The window can be maximized and tiled again.** Its minimum height was 760 pixels. On screens
  where a panel takes up part of that height, window managers greyed out maximize, refused to tile
  the window and let the panel cover its bottom edge. The window's minimum and starting sizes now
  never exceed the usable screen area. The sidebar scrolls instead of holding the window open.
- **Narrow windows scroll instead of cutting content off.** Tools, the wallet view and Settings
  used to lose whatever lay past the right edge. They now scroll sideways.
- **No more "…" buttons.** Buttons, toggles and checkboxes keep their full labels. The Logs toolbar
  wraps onto more rows when the window is narrow, and wrapped text no longer ends in an ellipsis.
- **No white flash while resizing.** The area a resize uncovers was painted white before the app
  redrew it. It is now the app's background colour.

## Restoring a wallet

- **Any passphrase is accepted when restoring, including none.** Typing in an existing seed went
  through the steps meant for a new wallet. You were told your passphrase was too short, and an
  empty one was refused outright, so a wallet created with no passphrase could not be restored.
  A seed you type in is now treated as a restore and takes its passphrase as it is. A seed
  generated in the app still gets the dice-first passphrase steps. The terminal interface behaves
  the same way.

## Tor

- **Restart Tor and New Tor Identity.** Right-click the connection status in the bottom right, or
  use the new **Restart Tor** button in **Settings → Server**. New Tor Identity asks Tor for fresh
  circuits. Restart Tor shuts the bundled Tor down, starts a fresh one and reconnects. Until now,
  Tor only stopped when the app quit.
- **A stuck Tor no longer leaves a second Tor running.** If Tor had not finished starting when a
  retry or restart happened, a new Tor was launched while the first kept running. The first one
  still held Tor's data folder, so the new one failed. The unfinished one is now shut down first.

## Downloads

- **One package per platform.** Releases now contain the Windows `.exe` installer, a macOS `.dmg`
  for each chip type (Apple Silicon and Intel), and the Linux `.AppImage`. The `.msi`, the zips,
  the `.deb`, the `.rpm`, the tarballs and the headless server builds are gone.
- **Upgrading from an old package.** If you installed 1.4.x from a `.deb` or `.rpm`, the updater
  offers the AppImage. If you used the `.msi`, it offers the `.exe`. Once 1.5.0 is running, you can
  uninstall the old package. Your wallets in `~/.ashigaru` (or `%APPDATA%\Ashigaru`) are not
  affected.

## Cleanup

- **"Sparrow" no longer appears in Ashigaru's own text.** Messages, the Whirlpool text, the
  `--version` output, the name the app reports to Electrum servers and the help links now say
  Ashigaru or point at this repository. Internal names that existing wallets and setups depend on
  are unchanged.
- **Restarting from the terminal interface works.** It looked for a launcher called `sparrow`,
  which the build never creates.

---

# Ashigaru Desktop 1.4.5

*Released 2026-08-30*

Everything since 1.1.2. Versions 1.3.0, 1.4.0 and 1.4.1 exist in the repository but were never
announced, so this covers the whole span in one document — a guided tour and dice-based
passphrases, hardening of how Dojo nodes are verified, a Logs tool, in-app update checking, and a
typeface of its own.

**You will need to download this one by hand.** In-app updating did not exist before 1.3.0, so if
you are on 1.1.2 there is nothing to check for it. And 1.3.0's updater cannot reach GitHub at all —
see the first item below — so it will not find this release either. From 1.4.5 onward, in-app
updates work.

## Connectivity

- **Published builds could not make an HTTPS connection.** Anything reaching a normal `https://`
  address failed with `Received fatal alert: handshake_failure` — the update check most visibly, but
  the cause was general. The packaged runtime is assembled with `jlink`, and it was being built
  without `jdk.crypto.ec`, the module providing the SunEC security provider. SunEC is a *service*
  provider, so nothing in the module graph `requires` it and `jlink` had no reason to include it.
  Without it the runtime cannot complete the elliptic-curve key exchange that essentially every
  modern server needs.

  Two things hid it. Onion addresses were unaffected, since onion HTTP is not TLS, so mixing, fee
  rates and server connections all kept working. And running from source was unaffected, because that
  uses the full JDK — the fault only ever existed in the builds published here.

## Updates

- **Ashigaru checks for new releases.** A new **Settings → Update** screen shows the version you
  are running and the latest published release, and can check on demand or once a day. Checking is
  off until you say yes: you are asked once, on first run, and the request goes through your proxy
  when one is configured. It reveals only that someone asked for a version number, never anything
  about your wallets.
- **Downloads are verified before you are offered them.** Choosing an update fetches it and then
  checks four things in order, showing each as it passes: the release message was signed by the
  Ashigaru release key, the published checksums match what that signed message commits to, the
  release is the one being offered and is newer than the one you run, and the file you downloaded
  matches its published checksum. These are the same checks the README asks you to perform by hand.
- **A failed check deletes the download.** If any link in that chain does not hold, the file is
  removed rather than left somewhere it could be opened by accident, and the screen says which
  check failed. A release published without its signature is refused outright rather than trusted.
- **Then you install it.** Ashigaru does not update itself. On Windows and macOS it hands the
  verified file to the system installer and steps aside; on Linux it shows you the file, since
  package installs need root and the right format depends on your distribution. The package
  matching how you installed is preselected, with the rest available if the guess is wrong.
- **The portable Windows build is offered.** The updater only ever offered `.exe` and `.msi`, so
  anyone running the portable `.zip` was shown an installer instead of the build they actually use.

## Getting started

- **Guided tour.** A new in-app tour walks you through the app by pointing at the real controls —
  the wallet selector, opening and creating wallets, your balance and accounts, receiving, mixing,
  and the Settings screens. It appears on first launch and is available any time from
  **Tools → Guided Tour**, so you can replay it whenever you like.
- **The tour waits for you.** Start it before you have a wallet and it covers what it can, then
  picks up automatically with the wallet-specific steps as soon as you open your first wallet.
- **Every step looks the same, and Back works.** The tour bubbles took on the styling of whatever
  control they happened to point at, so the "Your balance" step rendered oversized and the Settings
  steps came out a different colour from the rest. Each bubble is now styled in its own right,
  independent of where it is anchored, and stepping back through the tour behaves.

## Creating a wallet

- **Dice-first passphrase creation.** When adding a passphrase to a new wallet, Ashigaru can now
  walk you through generating one with physical dice, using the EFF large wordlist. Rolling real
  dice takes the entropy out of the computer's hands entirely — useful if you would rather not
  trust software randomness for the one secret that is never written to your wallet file.
- **Weak-passphrase warning.** The passphrase step now tells you when what you have typed is weak,
  at the point where you can still do something about it.
- **Dice passphrases are joined with dashes.** A rolled passphrase reads
  `abandon-ability-able-…` rather than using spaces. The passphrase has to be copied off the screen by
  hand and typed back in later, possibly into another wallet, and a space is the one separator you
  cannot verify you reproduced: one space or two look identical on paper, and a trailing one is
  invisible. The advisory now says the dashes are part of the passphrase, and explains that the
  master fingerprint is how you check you typed it back correctly.

  Existing wallets are unaffected — you type your own passphrase when restoring, and nothing
  re-derives it.
- **The setup dialogs are the right size again.** Every step of **+ New / Restore** had grown far
  taller than its content, in some cases running off the bottom of the screen with the second option
  out of reach. They size to their content properly now.

## Dojo discovery and security

- **Signatures are verified before Ashigaru authenticates.** Discovery previously authenticated to
  every reachable Dojo — spending its apikey and building an onion circuit — and only checked the
  signed block afterwards. Verification is pure local cryptography with no network I/O, so it now
  happens first: only listings whose signature verifies are contacted at all.
- **Signed identity is bound to the advertised payment code.** A valid signature on its own only
  proves a block is self-consistent. Without binding, a hostile directory could display an honest
  operator's identity while embedding a block validly signed by the attacker's own payment code.
  Verification now requires the advertised payment code to resolve to the same notification address
  as the embedded one.
- **Simpler, quieter discovery.** Per-Dojo `/auth/login` and `/support/services` authentication is
  gone, along with the thread pool, per-node timeouts, version gate and reachability probe. The only
  network request is the single directory fetch; the indexer endpoint is taken from the directory
  listing directly.
- **Discovered servers are ephemeral.** Dojo Electrum servers and explorers are now cleared at
  shutdown and again at startup, and repopulated by a fresh, verified discovery once you connect.
  Stale or removed nodes no longer linger, and their onion addresses are not left on disk while the
  app is closed.

## Mixing

- **Deleting a wallet stops its mixing.** The Whirlpool engine for a deleted wallet was left running
  indefinitely, against a wallet file that no longer existed, with no way to see it or stop it again
  — and without unlinking it from any other wallet that had it configured as a **Mix To**
  destination. Deleting a wallet now shuts its engine down and clears those links. Locking a wallet
  is deliberately unchanged: remixing continues while a wallet is locked, so the anonymity set keeps
  building.
- **The mixing button no longer sticks.** Start/Stop Mixing could sit on "Starting…" or "Stopping…"
  indefinitely — most easily by starting a mix with nothing currently eligible, or after a start that
  failed. It now follows the mixing state directly rather than waiting for an event that may never
  arrive, and recovers on its own.
- **Fewer Tor circuit rotations.** A sustained coordinator outage used to force a new Tor circuit on
  every single failed connection. Ashigaru now changes identity on the first failure and then every
  third, resetting once a connection succeeds.
- **Accurate retry logging.** During a coordinator outage, mix sessions reported their retry delay
  in milliseconds while labelling it seconds — "retrying in 90000s". The delay itself was always
  correct; now the log says so too.

## Startup

- **A second launch no longer starts a second Ashigaru.** Opening the app while it was already
  running started a whole second process. Both then pointed Tor at the same data directory, which Tor
  refuses — `It looks like another Tor process is running with the same data directory` — and the
  second window died during startup. Launching again now brings the running window to the front and
  exits, which is what it always appeared to do. Opening a `.psbt` or a `bitcoin:` link while
  Ashigaru is running is unchanged.
- **The window opens where you can reach it.** The title bar could land above the top of the screen,
  leaving no way to click minimise or maximise without dragging the window down first. It is now
  pulled inside the usable area of whichever screen it opens on.
- **The splash reports real progress.** It used to show a bar that spun regardless and one of four
  fixed messages. It now follows the startup as it happens — Tor, then the server connection, then
  wallet loading — marking each step as it completes, and moving with Tor's own reported percentage
  during bootstrap. A Tor bootstrap that fails or a server that cannot be reached is marked failed
  with the reason kept on screen, rather than leaving a bar spinning until something times out.

## Tools

- **Logs.** A new **Tools → Logs** screen shows recent application activity without leaving the
  app. Filter to warnings or errors, or search the text; either way stack traces stay attached to
  the entry they belong to rather than being cut in half.
- **Safe to share.** Bitcoin addresses, extended public keys, BIP47 payment codes, transaction ids,
  onion hostnames, derivation paths and the names of your open wallets are replaced with
  placeholders before anything is shown. That is the default, so the text you are looking at is
  already the text that is safe to send. A **Show raw log** toggle turns redaction off and tells
  you what it exposes.
- **Built for bug reports.** **Copy** and **Save** emit exactly what is on screen, with a short
  header carrying the version, operating system, Java version, network and whether a proxy is
  active — the things a developer asks for first, and nothing about your wallets. **Open Folder**
  takes you to the log directory if you would rather handle the file yourself.
- **The BIP47 verifier takes a PayNym handle.** Enter `+linkinparkrulz` and press **Look up** to
  fill in the payment code, instead of pasting 116 characters from somewhere. The lookup goes over
  Tor when it is configured, runs only when you press the button, and shows the name it resolved
  rather than changing the field silently. For checking an Ashigaru release, **Use Release Signing
  Code** remains the button to press — it uses the code compiled into the app and depends on nothing
  external. The payment code field is a single line rather than a three-row box.
- The sidebar reads Guided Tour, Verifier, Statistics and Logs, so the four entries scan as a list.

## Settings

- **The settings file can no longer be left half written.** `config` was saved by truncating it and
  writing over the top, so a crash — or a second process writing at the same moment — could leave it
  cut off mid-value. Ashigaru would then fail to read it at the next start, fall back to defaults, and
  overwrite it, losing every setting. It is now written alongside the real file and moved into place
  in one step.
- **An unreadable settings file is kept** as `config.corrupt-<date>` rather than silently
  overwritten, with the log naming the path, so the settings can still be recovered by hand.

## Appearance

- **A typeface of its own.** Ashigaru is set in Nikkyou Sans, a display face drawn from wartime
  propaganda poster lettering. Anything that has to line up or be read character by character —
  amounts, addresses, transaction ids, the log view, typed input — stays monospaced.
- **The icons are drawn.** None of the Font Awesome icons in the app were rendering: the fonts ship
  with the application but were never registered as providers of the glyph service, so every icon
  that asked for one came out as empty space. Separately, the Statistics icon had been drawn from an
  older icon set whose character position is empty in the font actually loaded. Both are fixed, so
  the sidebars, buttons and status indicators show their icons.
- **Text wraps instead of disappearing.** Wizard subtitles, body copy and hints, the public-server
  warning in Server Settings, and the help text behind every **?** were clipping to an ellipsis or
  running off the side of the screen rather than wrapping. They now show in full at any window size.
- **Bundled fonts are attributed.** Roboto Mono and the two Font Awesome faces had shipped inside
  every release without appearing in `THIRD_PARTY_NOTICES.md`, though both licences ask for their
  notices to travel with the binaries. They are recorded now, along with the new typeface.

## Packages

- **`.rpm` packages are published.** The download table has listed `.rpm` for Linux since well before
  this release and it was never actually built — Fedora, RHEL and openSUSE users were quietly left
  with the tarball. Both the desktop and headless builds now produce one.
- **`.msi` installers are published.** Same story on Windows: advertised, never built. The `.exe`
  installer remains the recommended route, with the `.msi` alongside it.
- **Both Linux tarballs ship.** The desktop and headless Linux builds were producing tarballs with
  the same filename, so only one of the two ever reached the release page. The headless build is
  now published as `Ashigaru-server-…`, matching the naming already used for its `.deb` and `.rpm`
  packages.
- **The Linux launcher shows its icon.** The desktop entry asked for the icon by bare name, which
  only resolves if the icon has been installed into the system icon theme — it is not, so app
  menus, docks and launchers showed a placeholder or nothing at all. It now points at the file
  where the package actually puts it. Thanks to 91xTx93x3 for the fix.

## Under the hood

- **The log file no longer grows forever.** `ashigaru.log` was written to indefinitely and could
  reach any size given enough time. It now rolls at 10 MB, keeping three files and 40 MB at most.
- **The heap is bounded.** Without an explicit limit the JVM sizes its maximum heap from the
  machine's RAM — a quarter of it — which read as heavy memory use in system monitors regardless of
  what Ashigaru was actually holding. Both the packaged launcher and a run from source now cap it.

## Project

- **Published security policy.** The repository carries a `SECURITY.md` setting out coordinated
  disclosure: report vulnerabilities privately by email or through GitHub's Security Advisory form —
  **not** through public issues or pull requests, since this is Bitcoin wallet software and early
  disclosure puts funds and privacy at risk. It also documents which versions receive security
  updates, what to include in a report, what response to expect, and how to verify releases.
- **The build is pinned and tested.** The Java toolchain is fixed to a single version rather than
  whatever the machine happens to have, and the test suite runs on every push and pull request.

## Verifying releases

- **`SHA256SUMS` now lists the release files.** Every release up to and including 1.1.2 carried a
  `SHA256SUMS` containing exactly one line: a hash of nothing, naming the file itself. Running the
  `sha256sum -c SHA256SUMS` check documented in the README could therefore never confirm a
  download. It now lists every published file under the name you download it as, so the documented
  check works as written. Thanks to the user who reported it.

To check a download:

```bash
sha256sum -c SHA256SUMS --ignore-missing
sha256sum SHA256SUMS   # compare with SHA256(SHA256SUMS) in MESSAGE.txt
```

The signature in `RELEASE-BIP47-SIGNATURE.txt` must recover to `1K8CDoBYWBuaeAhejLAk5hiACAgbbPnDCJ`,
the notification address of the release signing payment code published in the README.
**Tools → Verifier** does this for you, and from this release **Settings → Update** checks the
whole chain automatically.
