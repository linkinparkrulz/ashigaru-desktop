# Ashigaru Desktop

A graphical Bitcoin wallet and desktop GUI front-end for [Ashigaru Terminal](https://ashigaru.rs), providing a full Whirlpool coinjoin experience.

---

<img width="1092" height="829" alt="2026-07-25_12-10" src="https://github.com/user-attachments/assets/31386ed2-2dc7-49b4-97fc-f12de9f46dfc" />

---

### Features

- **Whirlpool coinjoin** — Tx0, zeroleak coinjoin, Premix/Postmix/Badbank account management
- **Tor** — built-in Tor support for network privacy
- **Mix To** — Set to mix to an offline or different wallet
- **Receive Only** — Decreases attack surface (spend is done on mobile)
- **Import/Export Labels** — Transport your labels to any BIP-329 adhering wallet software
- **BIP47 Message Verifier** — Verify that a message was signed by a specific entity
- **PepeHash PayNym Integration** — BIP47 based transactions include the corresponding pepehash avatar in their transaction card for easy identification
- **Whirlpoolstats.xyz Tool** — Want to know the latest Tx0? Pool totals? Unspent Capacity? It's all available as a tool now h/t to Black Coffee.
- **AmIExposed or BitHypha UTXO analysis** — Do analysis on your own UTXO's
- **Dojo Bay Integration** — Connect to geographically dispersed electrum servers with BIP47 Verified reputations. 
- **Logs** — See under the hood and share application logs with developers, with addresses, keys and wallet names redacted by default
- **Verified In-App Updates** — Ashigaru finds new releases and checks them against the release signing key before you install, so you never have to go looking for a download again

### Future Features

- **Eigenwallet Integration** — Post bad bank funds for atomic swap into XMR
- **Dojo Integration** — Get all the benefits of Dojo such as Nextblock estimates, personal electrum server detail and more

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
