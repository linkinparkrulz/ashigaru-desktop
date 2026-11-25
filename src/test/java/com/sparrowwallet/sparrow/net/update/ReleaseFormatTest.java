package com.sparrowwallet.sparrow.net.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void assetNamesAreLimitedToTheCharactersGitHubKeepsBeforeTheSumsAreTaken() throws IOException {
        String workflow = text(".github/workflows/release.yaml");
        String rule = "sed 's/[^A-Za-z0-9._-]/_/g'";
        //The engine files are named safely when collected (libstdc++-6.dll would otherwise be renamed by GitHub and miss its checksum line)
        int collect = workflow.indexOf("Collect the engine files as release assets");
        assertTrue(collect >= 0 && workflow.indexOf(rule, collect) > collect);
        //and every asset is checked again before SHA256SUMS is written
        int sums = workflow.indexOf("- name: Generate SHA256SUMS");
        int rename = workflow.indexOf(rule, sums);
        assertTrue(sums >= 0 && rename > sums && rename < workflow.indexOf("sha256sum \"$file\"", sums), "renamed before the sums are taken");
        assertEquals("swap-engine-x86_64-pc-windows-gnu-libstdc__-6.dll", "swap-engine-x86_64-pc-windows-gnu-libstdc++-6.dll".replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    @Test
    void everyJobThatTakesARunnerHasATimeout() throws IOException {
        for(Path file : workflows()) {
            String workflow = text(file.toString());
            int runners = workflow.split("\n    runs-on:", -1).length - 1;
            int timeouts = workflow.split("\n    timeout-minutes: \\d+", -1).length - 1;
            assertEquals(runners, timeouts, file + ": a job without timeout-minutes can hold a runner for six hours");
        }
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
        List<Path> workflows = workflows();
        for(Path file : workflows) {
            for(String line : Files.readAllLines(file)) {
                String trimmed = line.trim().replaceFirst("^- ", "");
                if(trimmed.startsWith("uses: ") && !trimmed.startsWith("uses: ./")) {
                    assertTrue(trimmed.matches("uses: [\\w./-]+@[0-9a-f]{40}( #.*)?"), file.getFileName() + ": " + trimmed + " is not pinned to a commit");
                }
            }
        }
    }

    @Test
    void runnersAndToolsAreFixed() throws IOException {
        for(Path file : workflows()) {
            String workflow = text(file.toString());
            assertFalse(WorkflowRunners.namesLatestRunner(workflow), file + ": a -latest runner changes without notice");
            for(String line : workflow.split("\n")) {
                if(line.contains("cargo install ")) {
                    assertTrue(line.contains("--version ") && line.contains("--locked"), file + ": " + line.trim() + " is not pinned");
                }
            }
        }
    }

    @Test
    void theLatestRunnerCheckSeesEveryWayOfWritingIt() {
        String[] latest = {
                "jobs:\n  a:\n    runs-on: macos-latest\n",
                "jobs:\n  a:\n    runs-on: 'ubuntu-latest'\n",
                "jobs:\n  a:\n    runs-on: \"Windows-Latest\" # mixed case\n",
                "jobs:\n  a:\n    runs-on: [ubuntu-latest]\n",
                "jobs:\n  a:\n    runs-on: [self-hosted, \"ubuntu-latest\"]\n",
                "jobs:\n  a:\n    runs-on:\n      - self-hosted\n      - ubuntu-latest\n",
                "jobs:\n  a:\n    runs-on:\n      group: big\n      labels: ubuntu-latest-xlarge\n",
                "jobs:\n  a:\n    runs-on:\n      labels: [x64, macos-latest]\n",
                "jobs:\n  a:\n    runs-on:\n      labels:\n        - macos-latest-large\n",
                "jobs:\n  a:\n    runs-on: ${{ matrix.platform }}\n    strategy:\n      matrix:\n        platform: [macos-15, ubuntu-latest]\n",
                "jobs:\n  a:\n    runs-on: ${{ matrix.runner }}\n    strategy:\n      matrix:\n        include:\n          - runner: windows-2022\n          - runner: windows-latest\n",
                "jobs:\n  a:\n    runs-on: ${{ matrix.image }}\n    strategy:\n      matrix:\n        image:\n          - macos-15\n          - macos-latest\n",
                "jobs:\n  a:\n    runs-on: [\n      self-hosted,\n      ubuntu-latest\n    ]\n",
                //An alias to an anchor elsewhere, as a scalar and as a block list
                "x-runner: &runner ubuntu-latest\njobs:\n  a:\n    runs-on: *runner\n",
                "x-runners: &runners\n  - self-hosted\n  - macos-latest-xlarge\njobs:\n  a:\n    runs-on: *runners\n",
                //An input to a reusable workflow, which may run on it
                "jobs:\n  a:\n    uses: ./.github/workflows/build.yaml\n    with:\n      os: ubuntu-latest\n",
        };
        for(String workflow : latest) {
            assertTrue(WorkflowRunners.namesLatestRunner(workflow), workflow);
        }
        String[] fixed = {
                "jobs:\n  a:\n    runs-on: macos-15\n",
                "jobs:\n  a:\n    runs-on: [ubuntu-22.04]\n",
                "jobs:\n  a:\n    runs-on: macos-15 # not macos-latest\n",
                "jobs:\n  a:\n    runs-on: my-ubuntu-latest-mirror\n",
                //os: under env: is not a runner, and neither is text in a script
                "env:\n  os: ubuntu-latest\njobs:\n  a:\n    runs-on: ubuntu-22.04\n    env:\n      os: macos-latest\n    steps:\n      - run: |\n          echo runs-on: ubuntu-latest\n      - name: it's fine\n        with:\n          os: windows-latest\n",
                //A matrix value runs-on does not use is not a runner
                "jobs:\n  a:\n    runs-on: ubuntu-22.04\n    strategy:\n      matrix:\n        image: [ubuntu-latest]\n",
                "jobs:\n  a:\n    runs-on: ${{ matrix.os }}\n    strategy:\n      matrix:\n        os: [ubuntu-22.04]\n        tool: [macos-latest]\n",
        };
        for(String workflow : fixed) {
            assertFalse(WorkflowRunners.namesLatestRunner(workflow), workflow);
        }
    }

    @Test
    void theWorkflowReaderFindsTheRunnersOfTheRealWorkflows() throws IOException {
        //Every job of the real workflows names its runner somewhere the reader looks: if it found none, the check above would pass for nothing
        for(Path file : workflows()) {
            String workflow = text(file.toString());
            if(workflow.contains("\n    runs-on:")) {
                assertFalse(WorkflowRunners.runnerValues(workflow).isEmpty(), file.toString());
            }
        }
        assertTrue(WorkflowRunners.runnerValues(text(".github/workflows/release.yaml")).containsAll(List.of("windows-2022", "ubuntu-22.04", "macos-15", "macos-15-intel")));
    }

    @Test
    void theEngineCacheKeyChangesWithTheLockAndTheWorkflow() throws IOException {
        String engine = text(".github/workflows/swap-engine.yaml");
        String key = engine.lines().filter(line -> line.trim().startsWith("key: swap-engine-")).findFirst().orElseThrow();
        assertTrue(key.contains("'swap-engine/Cargo.lock.overlay'") && key.contains("'.github/workflows/swap-engine.yaml'"), key);
    }

    @Test
    void theEngineCacheHoldsOnlyTheMoneroBuild() throws IOException {
        String engine = text(".github/workflows/swap-engine.yaml");
        String step = engine.substring(engine.indexOf("- name: Cache the Monero build"));
        step = step.substring(0, step.indexOf("restore-keys:"));
        assertTrue(step.contains("target/debug/monero-depends") && step.contains("release/build/monero-sys-*"), step);
        assertFalse(step.contains("~/.cargo/registry") || step.matches("(?s).*eigenwallet/target\\s*\\n.*"), "not the whole target folder or the registry: " + step);
    }

    @Test
    void engineAndReleaseUploadsTryTwice() throws IOException {
        for(String file : new String[] {".github/workflows/swap-engine.yaml", ".github/workflows/release.yaml"}) {
            String workflow = text(file);
            assertTrue(workflow.contains("continue-on-error: true") && workflow.contains("if: steps.upload.outcome == 'failure'")
                    && workflow.contains("overwrite: true"), file);
        }
    }

    /** The commits the release tags name, from git ls-remote (none is an annotated tag) */
    private static final java.util.Map<String, String> ACTION_PINS = java.util.Map.of(
            "actions/checkout", "3d3c42e5aac5ba805825da76410c181273ba90b1",       //v7.0.1
            "actions/cache", "caa296126883cff596d87d8935842f9db880ef25",          //v5.1.0, also cache/restore and cache/save
            "actions/setup-java", "d1374f8ac74474200d16ef8ecee22c7ae7a51880");    //v6.3.0
    private static final String TEMURIN = "'21.0.13+100000002.0.LTS'";

    @Test
    void theActionsAreTheNewReleasesAtTheirTagsCommits() throws IOException {
        java.util.regex.Pattern uses = java.util.regex.Pattern.compile("uses:\\s*(actions/(?:checkout|cache|setup-java))(?:/[a-z]+)?@([0-9a-f]{40})");
        int found = 0;
        for(Path file : workflows()) {
            java.util.regex.Matcher matcher = uses.matcher(text(file.toString()));
            while(matcher.find()) {
                found++;
                assertEquals(ACTION_PINS.get(matcher.group(1)), matcher.group(2), file + ": " + matcher.group());
            }
        }
        assertTrue(found >= 12, "every checkout, cache and setup-java step was seen: " + found);
    }

    @Test
    void everyJdkIsTemurin210131Plus2WithoutCheckLatest() throws IOException {
        int found = 0;
        for(Path file : workflows()) {
            String workflow = text(file.toString());
            for(String step : workflow.split("\n      - ")) {
                if(step.contains("actions/setup-java@")) {
                    found++;
                    assertTrue(step.contains("java-version: " + TEMURIN), file + ": " + step);
                    assertTrue(step.contains("check-latest: false") && !step.contains("check-latest: true"), file + ": " + step);
                }
            }
        }
        assertEquals(4, found, "release, package, swap-package and test");
    }

    @Test
    void theEngineIsBuiltWithExactlyRust1920() throws IOException {
        String engine = text(".github/workflows/swap-engine.yaml");
        assertTrue(engine.contains("rustup toolchain install 1.92.0 "));
        assertTrue(engine.contains("grep -q '^rustc 1\\.92\\.0 '"), "and the build checks that it is the one used");
        assertFalse(engine.contains("toolchain: 1.92"), "an input the action does not have is ignored");
    }

    @Test
    void theEngineBuildKeepsCheckoutPathsOutOfTheCAndCppToo() throws IOException {
        String engine = text(".github/workflows/swap-engine.yaml");
        assertTrue(engine.contains("echo \"CFLAGS_${target}=$cmaps\"") && engine.contains("echo \"CXXFLAGS_${target}=$cmaps\""));
        assertTrue(engine.contains("-ffile-prefix-map=$GITHUB_WORKSPACE=/build"));
        assertFalse(engine.matches("(?s).*\\n\\s*(C|CXX)FLAGS=.*"), "plain CFLAGS would also change how Monero's depends are built");
    }

    @Test
    void theReleaseRunsTheseChecks() throws IOException {
        assertTrue(text(".github/workflows/release.yaml").contains("./gradlew :test --tests 'com.sparrowwallet.sparrow.net.update.ReleaseFormatTest'"));
    }

    /** Every workflow file, .yaml and .yml, and at least one: a check that scanned nothing would pass for nothing */
    private static List<Path> workflows() throws IOException {
        try(var files = Files.list(Path.of(".github/workflows"))) {
            List<Path> found = files.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml")).sorted().toList();
            assertFalse(found.isEmpty(), "no workflow files found: run the tests from the project directory");
            return found;
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
