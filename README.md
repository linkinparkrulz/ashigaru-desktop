# Ashigaru Desktop

A graphical Bitcoin wallet and desktop GUI front-end for [Ashigaru Terminal](https://ashigaru.rs) hardware, providing a full Whirlpool coinjoin experience.

---

### Features

- **Whirlpool coinjoin** — Tx0, zeroleak coinjoin, Premix/Postmix/Badbank account management
- **Tor** — built-in Tor support for network privacy
- **Mix To** — Set to mix to an offline or different wallet
- **Receive Only** — Decreases attack surface (spend is done on mobile)

### Future Features

- **Eigenwallet Integration** — Post bad bank funds for atomic swap into XMR
- **AmIExposed or BitHypha UTXO analysis** — Do analysis on your own UTXO's 
- **Dojo Integration** — Get all the benefits of Dojo such as Nextblock estimates, personal electrum server detail and more

---

### Download

Pre-built binaries for every platform are published on the [Releases](../../releases) page.

| Platform | Package |
|---|---|
| Windows | `.exe` installer, `.msi` |
| macOS (Apple Silicon) | `Ashigaru-X.Y.Z-aarch64.dmg` |
| macOS (Intel) | `Ashigaru-X.Y.Z-x86_64.dmg` |
| Linux (desktop) | `.deb`, `.rpm`, `.tar.gz` |
| Linux (headless / server) | `ashigaru-server` `.deb`, `.rpm` |

Each release also includes `SHA256SUMS`, `MESSAGE.txt`, and `RELEASE-BIP47-SIGNATURE.txt` for verification.

**macOS installation**

Releases are not yet notarized. On macOS Ventura and later, Gatekeeper is stricter for quarantined, non-notarized apps downloaded from the internet — ad-hoc signing alone does not satisfy trust requirements, and you may see "damaged and can't be opened" or a blocked launch. This can also indicate a bad signature or packaging issue, so verify the file hash first (see *Verifying a release* below).

If the hash checks out, two options:

**Option A — Remove quarantine (recommended):**

1. Mount the DMG
2. Open Terminal and run:

```sh
xattr -rd com.apple.quarantine /Volumes/Ashigaru/Ashigaru.app
```

3. Drag `Ashigaru.app` to `/Applications`, then open normally

If you already copied it to Applications:

```sh
xattr -rd com.apple.quarantine /Applications/Ashigaru.app
```

**Option B — Open Anyway via System Settings:**

After a blocked launch attempt, go to **System Settings → Privacy & Security** and click **Open Anyway** next to the Ashigaru entry.

> **Stable releases** will ship Developer ID signed and notarized, which removes this friction entirely.

---

### Verifying a release

Every release is signed by the developer using a BIP47 notification key. Verification is a three-step process with no special tooling required.

**Notification address:** `<NOTIFICATION_ADDRESS>`

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

Open `RELEASE-BIP47-SIGNATURE.txt`. The file contains the signed statement and a base64 signature. Verify using any Bitcoin message verifier (Ashigaru, https://paymentcode.io/lab, https://pajasevi.github.io/bip47-verifier/) with:

- **Payment Code**: the payment code that is the supposed identity
- **Message**: the content of `MESSAGE.txt` (verbatim, no trailing newline changes)
- **Signature**: the base64 value from `RELEASE-BIP47-SIGNATURE.txt`

---

### Build from source

Requires JDK 21 (Temurin recommended).

```bash
git clone --recursive https://github.com/linkinparkrulz/ashigaru-desktop.git
cd ashigaru-desktop
./gradlew jpackage
```

The packaged application is written to `build/jpackage/`.

For proving reproducibility, see docs [here](docs/ReproducibleBuilds.md).

___

### Software license:

Ashigaru Terminal is released under the Free and Open Source license [GNU GPLv3](LICENSE).

**Declaration as per Apache v2.0, section 4:** Ashigaru Terminal has been forked from/build upon [Sparrow wallet Source Code](https://web.archive.org/web/20250525130614/https://github.com/sparrowwallet/sparrow/releases/tag/1.8.4), v1.8.4 released 7th March 2024. Additionally the Sparrow wallet Source Code for [Nightjar library](https://web.archive.org/web/20250528121847/https://github.com/sparrowwallet/nightjar), including commits up to and including 14th Feburary 2024, has been imported as a module directly into this Ashigaru Terminal code repository. Original source code was released under license Apache v2.0, and changes/modifications under this Ashigaru Open Source Project code repository are done so under the GNU GPLv3 license.

<br>
