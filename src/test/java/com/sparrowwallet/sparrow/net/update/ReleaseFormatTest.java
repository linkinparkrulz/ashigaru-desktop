package com.sparrowwallet.sparrow.net.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Other tools verify our releases (the in-app updater, and packagers that rebuild an image around the AppImage), so the file names and the
 * MESSAGE.txt fields in release.yaml are a contract, written down in docs/ReleaseFormat.md. This reads the workflow and fails when they drift.
 */
class ReleaseFormatTest {
    private static String text(String file) throws IOException {
        Path path = Path.of(file);
        assertTrue(Files.isRegularFile(path), "run the tests from the project directory: " + file);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void theAssetNamesAreTheOnesTheUpdaterAndPackagersExpect() throws IOException {
        String workflow = text(".github/workflows/release.yaml");
        for(String name : new String[] {ReleaseTrust.SUMS_ASSET, ReleaseTrust.MESSAGE_ASSET, ReleaseTrust.SIGNATURE_ASSET}) {
            assertTrue(workflow.contains(name), name + " is not mentioned in release.yaml");
        }
        assertEquals("SHA256SUMS", ReleaseTrust.SUMS_ASSET);
        assertEquals("MESSAGE.txt", ReleaseTrust.MESSAGE_ASSET);
        assertEquals("RELEASE-BIP47-SIGNATURE.txt", ReleaseTrust.SIGNATURE_ASSET);
    }

    @Test
    void messageTxtHasTheDocumentedLinesInTheDocumentedOrder() throws IOException {
        String workflow = text(".github/workflows/release.yaml");
        String[] lines = {
                "\"Ashigaru release attestation\"",
                "\"Repo: github.com/linkinparkrulz/ashigaru-desktop\"",
                "\"Version: ${VERSION}\"",
                "\"Tag: ${GITHUB_REF_NAME}\"",
                "\"Commit: ${GITHUB_SHA}\"",
                "\"SHA256(SHA256SUMS): ${SUMS_HASH}\""};
        int at = workflow.indexOf("Generate MESSAGE.txt");
        assertTrue(at > 0);
        for(String line : lines) {
            int found = workflow.indexOf(line, at);
            assertTrue(found > 0, "MESSAGE.txt line missing or out of order: " + line);
            at = found;
        }
    }

    @Test
    void theSumsFileIsSortedByNameWithBareFileNames() throws IOException {
        String workflow = text(".github/workflows/release.yaml");
        assertTrue(workflow.contains("sort -k2 \"$tmp\" > SHA256SUMS"));
        assertTrue(workflow.contains("printf '%s  %s\\n'"), "two spaces between the hash and the name, as sha256sum writes it");
        assertTrue(workflow.contains("name=${file##*/}"), "names carry no path");
    }

    @Test
    void everyReleaseFileIsAttestedByAPinnedAction() throws IOException {
        String workflow = text(".github/workflows/release.yaml");
        assertTrue(workflow.contains("id-token: write") && workflow.contains("attestations: write"));
        assertTrue(workflow.matches("(?s).*actions/attest-build-provenance@[0-9a-f]{40}.*"), "pinned by commit, like every other action");
        assertTrue(workflow.contains("subject-path: release-assets/**"));
        //The attestation is made before the release is created, from the same files
        assertTrue(workflow.indexOf("name: Attest build provenance") < workflow.indexOf("name: Create draft GitHub release"));
    }

    @Test
    void theSigningIdentityIsTheSameEverywhere() throws IOException {
        String readme = text("README.md");
        String format = text("docs/ReleaseFormat.md");
        String workflow = text(".github/workflows/release.yaml");
        for(String where : new String[] {readme, format, workflow}) {
            assertTrue(where.contains("1K8CDoBYWBuaeAhejLAk5hiACAgbbPnDCJ"));
        }
        assertTrue(readme.contains(ReleaseTrust.RELEASE_SIGNING_PAYMENT_CODE));
        assertTrue(workflow.contains(ReleaseTrust.RELEASE_SIGNING_PAYMENT_CODE));
    }

    @Test
    void theFormatPageNamesTheSameFilesAndFields() throws IOException {
        String format = text("docs/ReleaseFormat.md");
        for(String needle : new String[] {"SHA256SUMS", "MESSAGE.txt", "RELEASE-BIP47-SIGNATURE.txt", "Ashigaru release attestation",
                "Repo: github.com/linkinparkrulz/ashigaru-desktop", "Version:", "Tag: v", "Commit:", "SHA256(SHA256SUMS):", "gh attestation verify"}) {
            assertTrue(format.contains(needle), needle);
        }
    }

    @Test
    void theReleaseBuildsBundlesAndAttestsTheSwapEngine() throws IOException {
        String workflow = text(".github/workflows/release.yaml");
        //The engine is built by the release itself, and the packages wait for it
        assertTrue(workflow.contains("uses: ./.github/workflows/swap-engine.yaml"));
        assertTrue(workflow.matches("(?s).*build:\\s+needs: engine.*"), "the packages need the engine");
        for(String target : new String[] {"x86_64-pc-windows-gnu", "x86_64-unknown-linux-gnu", "aarch64-apple-darwin", "x86_64-apple-darwin"}) {
            assertTrue(workflow.contains("target: " + target), target);
            assertTrue(workflow.contains("swap-engine-") , "engine artifacts are named by target");
        }
        //It is bundled where the app looks, checked inside the app, and its files are release assets like the packages
        assertTrue(workflow.contains("src/main/resources/${{ matrix.prefix }}"));
        assertTrue(workflow.contains("Check the engine is in the app"));
        assertTrue(workflow.contains("engine-assets/*"));
        assertTrue(workflow.contains("swap-engine-${{ matrix.target }}-"), "named by platform, since both Mac libraries share a file name");
        //The release job takes only the release artifacts, so the raw engine artifacts do not collide as assets
        assertTrue(workflow.contains("pattern: release-*"));
    }

    @Test
    void theReleaseOnlyBuildsTheEngineWithLockedDependencies() throws IOException {
        String release = text(".github/workflows/release.yaml");
        String engine = text(".github/workflows/swap-engine.yaml");
        String fetch = text("swap-engine/fetch.sh");
        assertTrue(release.contains("require_lock: true"));
        assertTrue(engine.contains("require_lock:") && engine.contains("Cargo.lock.overlay"));
        assertTrue(engine.contains("steps.lock.outputs.flag"), "the test and build commands carry --locked when the overlay is there");
        assertTrue(engine.contains("name: swap-engine-cargo-lock"), "the resolved lock is kept for review");
        assertTrue(fetch.contains("Cargo.lock.overlay"), "fetch.sh puts the overlay in place");
    }

    @Test
    void everyActionInEveryWorkflowIsPinnedToACommit() throws IOException {
        try(var files = Files.list(Path.of(".github/workflows"))) {
            for(Path file : files.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                for(String line : Files.readAllLines(file)) {
                    String trimmed = line.trim().replaceFirst("^- ", "");
                    if(trimmed.startsWith("uses: ") && !trimmed.startsWith("uses: ./")) {
                        assertTrue(trimmed.matches("uses: [\\w./-]+@[0-9a-f]{40}( #.*)?"), file.getFileName() + ": " + trimmed + " is not pinned to a commit");
                    }
                }
            }
        }
    }

    @Test
    void theReleaseEngineBuildDoesNotUseCaches() throws IOException {
        String engine = text(".github/workflows/swap-engine.yaml");
        //Each step that restores or saves a cache is skipped when the build is a release (require_lock), so a cache cannot change what a release contains
        int caches = 0;
        for(String step : engine.split("\n      - name: ")) {
            if(step.contains("uses: actions/cache")) {
                caches++;
                assertTrue(step.contains("!inputs.require_lock"), "this cache step is used in releases: " + step.split("\n")[0]);
            }
        }
        assertEquals(3, caches);
    }
}
