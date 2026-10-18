# Reproducible Builds

The purpose of this guide is to aid you in confirming that the official Ashigaru Desktop release
binaries can be independently reproduced — that the Ashigaru Open Source Project released binaries
built **only** from the source code in this repository.

Every release publishes `SHA256SUMS`, `MESSAGE.txt`, and `RELEASE-BIP47-SIGNATURE.txt` alongside
the binaries (see **Verifying a release** in the [README](../README.md) for the signature side).
This guide covers rebuilding a binary from source and comparing its hash.

## Environment

The release binaries are built with Temurin JDK **21.0.7** (see `java-version` in
`.github/workflows/release.yaml`) and the Gradle wrapper checked into this repository (Gradle 8.5).
Building with a different JDK vendor/version or Gradle version will likely yield different hashes:
jlink/jpackage embed toolchain paths and class file versions into the output.

The examples below build the Linux x86_64 tar distribution inside a clean container so the
filesystem timestamps/order are the only remaining variables. The Gradle build normalizes archive
timestamps and file order (`reproducibleFileOrder` in `build.gradle`).

## Build inside a clean container

```bash
sudo docker pull eclipse-temurin:21.0.7_6-jdk
sudo docker run -it --name ashigaru-desktop eclipse-temurin:21.0.7_6-jdk bash
```

Inside the container (or copy a checked-out source tree in with `docker cp`):

```bash
apt update && apt install -y git
git clone --recursive https://github.com/linkinparkrulz/ashigaru-desktop.git
cd ashigaru-desktop
git checkout v1.4.5   # the tag you are reproducing

./gradlew clean packageAppImage
```

Note that the build downloads its dependencies from Maven Central and several other configured
repositories at build time; network access is required and the resolved artifacts must match those
used for the official build (see `build.gradle` `repositories`).

## Compare against the release

The AppImage wraps the application in a squashfs image whose metadata is not byte-for-byte
reproducible, so compare the application it contains rather than the AppImage file itself. Download
the official `Ashigaru-1.4.5-x86_64.AppImage`, check it against `SHA256SUMS`, then:

```bash
chmod +x Ashigaru-1.4.5-x86_64.AppImage
./Ashigaru-1.4.5-x86_64.AppImage --appimage-extract   # unpacks to squashfs-root/
diff -r squashfs-root/usr build/jpackage/Ashigaru && echo "Reproduced"
```

`diff` must report no differences.

## Notes

- The `.exe` (Windows) and `.dmg` (macOS) installers embed platform-packager metadata; reproduce those on the matching OS using the same workflow commands
  shown in `.github/workflows/release.yaml`.
- The AppImage build downloads `appimagetool` at build time. Pin its checksum with
  `-PappImageToolSha256=...` (recorded per release) so the tool itself is verified.
- If a build does not reproduce, double-check the JDK build number first — it is the most common
  source of drift.
