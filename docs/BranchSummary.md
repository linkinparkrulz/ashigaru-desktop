# Branch Summary: packaging-work

This document records everything accomplished on the `claude/packaging-work-30IfY` branch and
explains the reasoning behind each decision.

---

## Goal

Turn Ashigaru Desktop into a properly packaged, cross-platform application that:

- Builds reproducible installers for Linux, macOS, and Windows via CI
- Shares the same wallet database format as Sparrow Desktop and Ashigaru Terminal so `.mv.db`
  wallet files are interchangeable between all three apps
- Has a polished UI matching Ashigaru's brand identity (replacing Sparrow's defaults)
- Is stable enough for real-world use (no silent crashes, correct error messages)

---

## Changes by area

### 1. Java / JavaFX platform upgrade

**Commits:** `7923024`, `d176c3f`, `5189794`, `879e30b`

- Upgraded JDK target from 18.0.1 → **21.0.7 (LTS)**
- Upgraded JavaFX from 18 → **21**
- Upgraded `badass-jlink-plugin` from 2.26.0 → 3.2.1 (required for Java 21 jlink support)
- Added missing jlink modules (`addExtraDependencies`) so the modular runtime image builds cleanly
- Dropped monocle platform detection — monocle JARs are not published for JavaFX 21

Java 21 LTS is the correct long-term target for a jpackage-based desktop app.

---

### 2. CI: release workflow and macOS packaging

**Commits:** `b46340f`, `470e798`, `37ba610`, `236854b`, `7d9f01d`

- Added a GitHub Actions **release workflow** that builds and publishes installers for all three
  platforms on each push to the branch
- macOS ad-hoc signing: fixed the Gradle task ordering (`jpackageImage.doLast` instead of a
  separate task) so `codesign` runs after jpackage produces the `.app` bundle
- Fixed macOS ZIP distribution: the archive was missing the `.app` bundle due to a path error
- Added architecture to the macOS ZIP filename to avoid collisions between arm64 and x86_64 builds
- Added a CI job that validates the macOS bundle structure (checks `Info.plist`, entitlements,
  signing) so packaging regressions are caught automatically
- Fixed `Info.plist` bundle metadata: correct `CFBundleIdentifier`, `CFBundleName`, version strings

---

### 3. H2 database upgrade and cross-app wallet compatibility

**Commits:** `a2ce0d9`, `c613d2f`, `7c838ff`, `35c2412`, `30f88b8`, `ff40559`

This was the most involved change.

#### Background

Ashigaru Desktop originally used **H2 1.4.200** while Sparrow Desktop and Ashigaru Terminal use
**H2 2.1.214**. H2 changed its on-disk MVStore format between major versions:

| H2 version | Write format |
|------------|-------------|
| 1.4.200    | 1           |
| 2.0.x / 2.1.x | 2      |
| 2.2.x      | 3           |

H2 2.x refuses to open format-1 files in write mode (error `90048`). This meant Ashigaru Desktop
wallets could not be opened in Sparrow and vice versa.

Flyway 7.15.0 (the original version) was also incompatible with H2 2.x because `VALUE` became a
reserved SQL keyword in H2 2.0 and Flyway 7 queries `INFORMATION_SCHEMA.SETTINGS.VALUE` without
quoting it, causing a `[42001]` syntax error on every wallet open.

#### What was done

1. **Upgraded H2 1.4.200 → 2.1.214** — matches Sparrow Desktop and Ashigaru Terminal exactly,
   so wallet `.mv.db` files are fully interchangeable between all three apps.

2. **Upgraded Flyway 7.15.0 → 8.5.13** — Flyway 8 handles the H2 2.x `VALUE` keyword correctly.
   Added `.ignoreMissingMigrations(true)` so Ashigaru Desktop can open wallets from newer Sparrow
   versions that have additional migration scripts (Flyway logs a warning but does not fail).

3. **Auto-migration for legacy wallet files** (`DbPersistence.migrateLegacyH2Format`) — when any
   `.mv.db` file was written by an older H2 version (format 1 or 2), opening it with H2 2.1.214
   raises error `90048`. Ashigaru Desktop now handles this automatically:
   - Detects the `90048` error in `createDataSource()`
   - Opens the old file in read-only mode via `ACCESS_MODE_DATA=r` in the JDBC URL (H2 2.x
     bypasses the write-format check in read-only mode, so it can read format-1 and format-2 files)
   - Exports all schema and data to a temp SQL file using `SCRIPT TO '<path>'`
   - Creates a new H2 2.1.214 database and imports with `RUNSCRIPT FROM '<path>'`
   - Renames the original to `.mv.db.bak` and moves the new file into its place
   - Retries the connection, which now succeeds
   - Works for both unencrypted and `CIPHER=AES` encrypted wallets

   This is transparent to the user — existing Sparrow wallets open without any manual step.

#### Why not H2 2.2.224?

H2 2.2.224 writes format-3 files. Sparrow Desktop and Ashigaru Terminal use H2 2.1.214 and cannot
open format-3. Using 2.1.214 keeps all three apps on the same write format (2).

---

### 4. UI / brand identity

**Commits:** `ee47464`, `c10ce4f`, `e1effe9`, `a84712a`, `4f23ea5`, `7e4c610`, `291cf61`, `8e50e7c`

- Replaced Sparrow's copy-button glyphs with plain Unicode characters (✂ / ✓) — the glyph font
  was not bundled, causing invisible buttons on clean installs
- Removed hardcoded address and TXID abbreviation from wallet tables — full strings are now shown
- Added UTXO checkbox selection so users can select multiple UTXOs without holding Ctrl
- Mandatory passphrase confirmation on wallet creation (confirm field required)
- Added "View Seed Words" button in wallet settings
- Replaced Blockstream Esplora server reference with Mempool.space
- Removed fiat currency preferences (not applicable for Ashigaru's use case)
- Embedded preferences inside the main window (no floating dialog)
- Replaced the floating "Back" button in preferences with a proper page-header row
- Fixed QR overlay logo: switched to the square Ashigaru logo, muted background

---

### 5. macOS Dock icon

**Commits:** `c613d2f`, `a2ce0d9`

The macOS Dock requires `.icns` files to contain at minimum 512×512 (`ic09`) and 1024×1024
(`ic10`) sub-images. The original `ashigaru.icns` only contained sizes up to 256×256, so the Dock
showed a blank/generic icon for the packaged app.

- Regenerated `ashigaru.icns` from `Ashigaru_Terminal_Logo_Square.png` with all 7 required sizes:
  16, 32, 64, 128, 256, 512, 1024
- Added `Taskbar.setIconImage()` in `AshigaruGui.java` for non-bundled (development) runs where
  the `.icns` is not involved

---

### 6. Bug fixes

**Commits:** `4a79342`, `9dc1f83`, `4ac3ea7`, `7c838ff`, `b7358ef`

| Bug | Fix |
|-----|-----|
| Wallet auto-load on startup prompted passphrase for every wallet | Fixed: passphrase is only prompted when the user explicitly opens a wallet |
| Delete wallet confirmation used wallet name instead of BIP39 passphrase | Fixed: confirmation now requires typing the wallet's BIP39 passphrase |
| UTXO table "Mixes" column visible for wrong account types | Fixed: column shown only for Premix and Postmix accounts |
| Receive dialog NPE in child wallets | Fixed: null-check on wallet before accessing receive node |
| `WhirlpoolServices.walletOpened()` NPE | Fixed: `AppServices.getWallet()` can return null if the wallet is not yet registered at event time; added null guards before calling `.getMasterMixConfig()` |
| Wallet history not loaded on first open | Fixed: history fetch triggered correctly after wallet registration |
| Wallet position not restored from preferences | Fixed: snap-to-preference applied after stage show |
| Preferences back button overlapped content | Fixed: replaced with a header row using standard layout |

---

### 7. README updates

**Commits:** `7a40029`, `b46340f`, `7d9f01d`

- Renamed project references from Sparrow to Ashigaru throughout
- Added build instructions, release download links, macOS Gatekeeper workaround notes
- Documented the reproducible build process

---

## Current state

The branch is CI-green. The following work:

- Creating a new wallet (H2 2.1.214, Flyway 8.5.13)
- Opening an existing Sparrow Desktop or Ashigaru Terminal wallet directly
- Auto-migrating H2 1.4.x or H2 2.0.x legacy wallet files on first open
- macOS Dock icon displays correctly in packaged builds
- Linux `.deb` and `.rpm` installers build via CI

## Known limitations / future work

- The "newer migration" Flyway warning (schema version 9 > latest migration 8) is harmless but
  indicates Sparrow has added a migration since the Ashigaru fork was taken. If Ashigaru Desktop
  needs to add wallet schema changes in the future, it should increment from version 9.
- Flyway 8.5.13 warns that H2 2.1.214 is slightly newer than its tested version (2.1.210). This
  is a cosmetic warning; functionality is unaffected.
