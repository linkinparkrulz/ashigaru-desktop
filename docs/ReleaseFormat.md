# Release file format

What a release publishes, for tools that verify it (the in-app updater, and downstream packagers). These names and fields are a contract: a change
here breaks verifiers, so `ReleaseFormatTest` fails if `release.yaml` stops matching this page.

## Assets

| File | What it is |
|---|---|
| the binaries (`Ashigaru-<version>-<arch>.AppImage`, `.dmg`, `.exe`, `.deb`...) | the payload |
| `SHA256SUMS` | `sha256sum` lines, `<64 hex>  <file name>`, sorted by file name, one per payload file, file names without a path |
| `MESSAGE.txt` | the text that is signed (below) |
| `RELEASE-BIP47-SIGNATURE.txt` | a Bitcoin signed-message block over the exact bytes of `MESSAGE.txt` |
| `RELEASE-SIGNING-INSTRUCTIONS.txt` | how the maintainer produces the signature |

Every payload file also has a GitHub build attestation (`gh attestation verify <file> --repo linkinparkrulz/ashigaru-desktop`).

## MESSAGE.txt

Exactly these lines, in this order, one per line:

```
Ashigaru release attestation
Repo: github.com/linkinparkrulz/ashigaru-desktop
Version: <version, without the leading v>
Tag: v<version>
Commit: <full commit hash>
SHA256(SHA256SUMS): <hash of the SHA256SUMS file>
```

## The signature

- The signed message is the content of `MESSAGE.txt`, byte for byte.
- The signer is the notification address (BIP47 child 0, P2PKH) of the release-signing payment code in the README: `1K8CDoBYWBuaeAhejLAk5hiACAgbbPnDCJ`.
- A verifier should require the signed text to equal `MESSAGE.txt`, the recovered address to equal that address, `Tag:` to name the version it expects, and
  `SHA256(SHA256SUMS)` to match the `SHA256SUMS` it downloaded, then check each payload file against `SHA256SUMS`.
