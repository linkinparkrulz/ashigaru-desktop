# Ashigaru Desktop

A privacy-first Bitcoin wallet for the desktop, built around Whirlpool coinjoin. It is the graphical companion to [Ashigaru Terminal](https://ashigaru.rs) and Ashigaru Mobile: receive here, mix through Premix and Postmix, and spend from mobile. Everything goes over Tor by default.

<p align="center">
  <img src="docs/images/main.png" alt="Ashigaru Desktop with a wallet open on its Deposit account" width="880">
</p>

---

### Features

**Mixing**
- **Whirlpool coinjoin.** Tx0, zeroleak coinjoin, and Premix / Postmix / Badbank account management.
- **Mix To.** Send mixed outputs on to an offline or different wallet.
- **Whirlpoolstats.xyz tool.** See the latest Tx0s, pool totals and unspent capacity (h/t Black Coffee).

**Wallets**
- **Receive only.** Spending happens on mobile, which keeps the desktop's attack surface small.
- **Dice-rolled passphrases.** Creating a wallet walks you through rolling a diceware BIP39 passphrase with physical dice. Restoring a wallet accepts the passphrase it already has, or none.
- **Import / export labels.** Move your labels to and from any BIP-329 wallet.
- **PayNym avatars.** BIP47 transactions show the counterparty's PepeHash avatar on the transaction card.
- **UTXO analysis.** Check your own UTXOs with AmIExposed or BitHypha.

**Privacy and network**
- **Built-in Tor.** Every connection goes through the bundled Tor. If it gets stuck, right-click the connection status (or open **Settings → Server**) to request a new Tor identity or restart Tor.
- **Dojo Bay integration.** Connect to geographically dispersed Electrum servers whose operators have signed their pairing with BIP47.

**Tools**
- **Guided tour.** A short walkthrough of wallets, accounts, receiving and mixing, shown on first run and available from Tools at any time.
- **BIP47 message verifier.** Check that a message was signed by the holder of a payment code, including Ashigaru's own release signatures.
- **Logs.** Look under the hood and share application logs with developers. Addresses, keys and wallet names are redacted by default.
- **Verified in-app updates.** Ashigaru finds new releases and checks them against the release signing key before you install, so you never have to go hunting for a download.

### Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/images/create-wallet-passphrase.png" alt="Choosing a passphrase when creating a wallet"><br><sub>Creating a wallet: a passphrase, rolled with dice</sub></td>
    <td width="50%"><img src="docs/images/create-wallet-diceware.png" alt="Reviewing a six-word diceware passphrase and its master fingerprint"><br><sub>Reviewing the rolled passphrase and master fingerprint (example only)</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/tools-verifier.png" alt="The BIP47 message verifier with the release signing code filled in"><br><sub>Tools → Verifier, checking a release signature</sub></td>
    <td width="50%"><img src="docs/images/settings-server.png" alt="Server settings with Test Connection and Restart Tor"><br><sub>Settings → Server, with Test Connection and Restart Tor</sub></td>
  </tr>
</table>

### Future Features

- **Eigenwallet integration.** Post Badbank funds for an atomic swap into XMR.
- **Dojo integration.** Get all the benefits of Dojo, such as Nextblock fee estimates, personal Electrum server details and more.

---

### Download

Pre-built binaries for every platform are published on the [Releases](../../releases) page.

| Platform | Package | Min OS | Notarized |
|---|---|---|---|
| Windows | `Ashigaru-X.Y.Z.exe` installer | Windows 10 | — |
| macOS (Apple Silicon) | `Ashigaru-X.Y.Z-aarch64.dmg` | macOS 11.0 | No, ad-hoc signed |
| macOS (Intel) | `Ashigaru-X.Y.Z-x86_64.dmg` | macOS 11.0 | No, ad-hoc signed |
| Linux | `Ashigaru-X.Y.Z-x86_64.AppImage` | — | — |

Each release also includes `SHA256SUMS`, `MESSAGE.txt`, and `RELEASE-BIP47-SIGNATURE.txt` for verification.

**Upgrading from 1.4.x.** Until 1.4.5, Linux releases also shipped `.deb`, `.rpm` and `.tar.gz` packages, and Windows releases shipped an `.msi`. From 1.5.0 there is one package per platform. The in-app updater offers the AppImage on Linux and the `.exe` on Windows. Once the new version is running you can uninstall the old package; your wallets live in `~/.ashigaru` (or `%APPDATA%\Ashigaru`) and are not touched.

**macOS installation**

Requires macOS 11.0 (Big Sur) or later. macOS builds are ad-hoc signed but not Developer ID signed or notarized. On macOS Ventura and later, Gatekeeper is stricter for quarantined, non-notarized apps downloaded from the internet — ad-hoc signing alone does not satisfy trust requirements, and you may see "damaged and can't be opened" or a blocked launch. This can also indicate a bad signature or packaging issue, so verify the file hash first (see *Verifying a release* below).

If the hash checks out, two options:

**Option A — Remove quarantine (recommended for nightly builds):**

1. Mount the DMG
2. Copy `Ashigaru.app` to `/Applications`
3. Open Terminal and run:

```sh
xattr -rd com.apple.quarantine /Applications/Ashigaru.app
```

4. Open normally

If you already copied it to Applications:

```sh
xattr -rd com.apple.quarantine /Applications/Ashigaru.app
```

**Option B — Open Anyway via System Settings:**

After a blocked launch attempt, go to **System Settings → Privacy & Security** and click **Open Anyway** next to the Ashigaru entry.

> Developer ID signing and notarization require an Apple Developer Program account. Current macOS packages are ad-hoc signed only, which is common for open-source desktop applications distributed without a paid Apple license.

---

### Verifying a release

Every release is signed by the maintainer using the private key for the notification address derived from the Ashigaru release-signing BIP47 Payment Code. This lets users verify that the release message was signed by the owner of the payment code, without exposing private keys.

From 1.4.5, **Settings → Update** performs every check below for you and shows each one as it passes, refusing and deleting anything that fails. (1.3.0 shipped the updater but cannot reach GitHub — see the 1.4.5 release notes — so it has to be replaced by hand.) The manual steps remain here for anyone verifying a download obtained outside the app, or checking the app's own work.

**Release signing identity**

- **PayNym:** https://paynym.rs/+linkinparkrulz
- **BIP47 Payment Code:**

```text
PM8TJM51x2mDd85CzEgVc2y7vdyB3eBj93JVjVtCt6PZtmfzhFzYPMXYBXh28zthWhVKGjVQZPT1MKxGxEtfenLYEkuc5GhoWtMzQCF8c8mrckYFM7r1
```

- **Notification address:** `1K8CDoBYWBuaeAhejLAk5hiACAgbbPnDCJ`

That address is derived from the payment code above, and is what every release signature must
recover to. It is published here so you can confirm the recovered signer without deriving it
yourself.

**Step 1 — Verify the file hash**

```bash
sha256sum -c SHA256SUMS --ignore-missing
```

Confirm the downloaded binary appears as `OK`.

**Step 2 — Verify the hash commitment in MESSAGE.txt**

```bash
sha256sum SHA256SUMS
```

Compare the output against the `SHA256(SHA256SUMS): ...` line inside `MESSAGE.txt`. They must match.

**Step 3 — Verify the Bitcoin message signature**

Open `RELEASE-BIP47-SIGNATURE.txt`. It is a standard Bitcoin signed message block: the contents of `MESSAGE.txt` between `-----BEGIN BITCOIN SIGNED MESSAGE-----` and `-----BEGIN BITCOIN SIGNATURE-----`, then the signing address and a base64 signature.

The simplest check is in Ashigaru itself — **Tools → Verifier**, use the release signing code, paste the whole file into the signed message block, then parse and verify. You can also use Ashigaru Mobile or a BIP47 message verifier such as https://paymentcode.io/lab with:

- **Payment Code**: the release signing payment code above
- **Message**: the exact contents of `MESSAGE.txt`
- **Signature**: the base64 value from `RELEASE-BIP47-SIGNATURE.txt`

The verifier derives the notification address from the payment code and checks the signature against it. Confirm the recovered signer is the notification address published above — that is what ties the release to the maintainer's key.

---

### Build from source

Requires JDK 21 (Temurin recommended).

```bash
git clone https://github.com/linkinparkrulz/ashigaru-desktop.git
cd ashigaru-desktop
./gradlew jpackage
```

The packaged application is written to `build/jpackage/`.

On Linux, `./gradlew jpackage` builds a `.deb`; the AppImage that releases ship is built with:

```bash
./gradlew packageAppImage
```

The AppImage task downloads `appimagetool` from the official AppImageKit continuous release and writes the package to `build/distributions/`.

For proving reproducibility, see docs [here](docs/ReproducibleBuilds.md).

___

### Documentation

- [Architecture](docs/ARCHITECTURE.md) — module layout, UI structure, Whirlpool deep dive
- [Development](docs/DEVELOPMENT.md) — build, run and test workflow for contributors
- [Reproducible Builds](docs/ReproducibleBuilds.md) — independently verify a release binary
- [Seed Entropy Analysis](docs/SeedEntropyAnalysis.md)
- [Release Testing Checklist](docs/TESTING.txt)

---

### Software license:

Ashigaru Desktop is released under the Free and Open Source license [GNU GPLv3](LICENSE).

**Declaration as per Apache v2.0, section 4:** Ashigaru Desktop has been forked from/built upon [Sparrow wallet Source Code](https://web.archive.org/web/20250525130614/https://github.com/sparrowwallet/sparrow/releases/tag/1.8.4), v1.8.4 released 7th March 2024. Additionally the Sparrow wallet Source Code for [Nightjar library](https://web.archive.org/web/20250528121847/https://github.com/sparrowwallet/nightjar), including commits up to and including 14th February 2024, has been imported as a module directly into this Ashigaru Desktop code repository. Original source code was released under license Apache v2.0, and changes/modifications under this Ashigaru Open Source Project code repository are done so under the GNU GPLv3 license.

<br>
