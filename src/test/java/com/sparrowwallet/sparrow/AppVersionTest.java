package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The version is written in two places (build.gradle for the installer, AshigaruTerminal for the app) and the CI
 * Package workflow stamps both with the run number and commit. These keep the two places agreeing, and check the
 * stamping script on copies of the files.
 */
class AppVersionTest {
    private static final Path GRADLE = Path.of("build.gradle");
    private static final Path TERMINAL = Path.of("src/main/java/com/sparrowwallet/sparrow/AshigaruTerminal.java");
    private static final Path SCRIPT = Path.of(".github/scripts/set-build-version.sh");
    private static final Pattern GRADLE_VERSION = Pattern.compile("(?m)^def ashigaruTerminalVersion = '([^']+)'");

    @Test
    void theTwoPlacesAgreeAndLookLikeAVersion() throws IOException {
        assertTrue(Files.isRegularFile(GRADLE), "run the tests from the project directory");
        Matcher matcher = GRADLE_VERSION.matcher(Files.readString(GRADLE, StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "no ashigaruTerminalVersion in build.gradle");
        assertEquals(matcher.group(1), AshigaruTerminal.APP_VERSION, "build.gradle and AshigaruTerminal.APP_VERSION must be the same");
        //jpackage wants digits and dots, with a first number above zero
        assertTrue(AshigaruTerminal.APP_VERSION.matches("[1-9][0-9]*\\.[0-9]+\\.[0-9]+"), AshigaruTerminal.APP_VERSION);
        assertTrue(AshigaruTerminal.APP_VERSION_SUFFIX.isEmpty() || AshigaruTerminal.APP_VERSION_SUFFIX.matches("\\+[0-9a-f]{7}"),
                "the suffix is empty in the repository and +<commit> in CI: " + AshigaruTerminal.APP_VERSION_SUFFIX);
    }

    @Test
    void theScriptStampsTheRunNumberAndCommitOnACopy(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path root = copyOfTheFiles(dir);
        Result result = run(root, "81", "F6833E34A59B7872E15DBDF717596E4AE0228691");
        assertEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("1.5.81+f6833e3") || result.output.matches("(?s).*\\d+\\.\\d+\\.81\\+f6833e3.*"), result.output);

        String gradle = Files.readString(root.resolve(GRADLE), StandardCharsets.UTF_8);
        String terminal = Files.readString(root.resolve(TERMINAL), StandardCharsets.UTF_8);
        Matcher base = GRADLE_VERSION.matcher(Files.readString(GRADLE, StandardCharsets.UTF_8));
        assertTrue(base.find());
        String[] parts = base.group(1).split("\\.");
        String expected = parts[0] + "." + parts[1] + ".81";
        assertTrue(gradle.contains("def ashigaruTerminalVersion = '" + expected + "'"), gradle.lines().filter(l -> l.contains("ashigaruTerminalVersion")).findFirst().orElse(""));
        assertTrue(terminal.contains("APP_VERSION = \"" + expected + "\";"));
        assertTrue(terminal.contains("APP_VERSION_SUFFIX = \"+f6833e3\";"));

        //Only those lines changed, and the repository's own files were not touched
        assertEquals(1, lineDifferences(Files.readString(GRADLE, StandardCharsets.UTF_8), gradle));
        assertEquals(2, lineDifferences(Files.readString(TERMINAL, StandardCharsets.UTF_8), terminal));
    }

    @Test
    void aLaterRunGetsALaterNumber(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path root = copyOfTheFiles(dir);
        assertEquals(0, run(root, "81", "f6833e3").exit);
        assertEquals(0, run(root, "82", "0123456").exit);
        assertTrue(Files.readString(root.resolve(TERMINAL), StandardCharsets.UTF_8).contains("APP_VERSION_SUFFIX = \"+0123456\";"));
        assertTrue(Files.readString(root.resolve(GRADLE), StandardCharsets.UTF_8).matches("(?s).*def ashigaruTerminalVersion = '\\d+\\.\\d+\\.82'.*"));
    }

    @Test
    void theScriptRefusesBadInputAndMissingLines(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path root = copyOfTheFiles(dir);
        assertNotEquals(0, run(root, "x", "f6833e3").exit);
        assertNotEquals(0, run(root, "81", "not-a-sha").exit);

        //A line it has to change is gone: it must fail rather than build an unstamped package
        Path terminal = root.resolve(TERMINAL);
        Files.writeString(terminal, Files.readString(terminal, StandardCharsets.UTF_8).replaceAll("(?m)^.*APP_VERSION_SUFFIX.*$", ""), StandardCharsets.UTF_8);
        Result result = run(root, "81", "f6833e3");
        assertNotEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("APP_VERSION_SUFFIX"), result.output);
    }

    private static Path copyOfTheFiles(Path dir) throws IOException {
        assumeTrue(Files.isRegularFile(SCRIPT), "run the tests from the project directory");
        for(Path file : new Path[] {GRADLE, TERMINAL}) {
            Path target = dir.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return dir;
    }

    private record Result(int exit, String output) {}

    private static Result run(Path root, String runNumber, String sha) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("bash", SCRIPT.toAbsolutePath().toString(), runNumber, sha, root.toString()).redirectErrorStream(true).start();
        } catch(IOException e) {
            assumeTrue(false, "no bash on this machine: " + e.getMessage());
            throw e;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new Result(process.exitValue(), output);
    }

    private static int lineDifferences(String before, String after) {
        String[] a = before.split("\\R", -1);
        String[] b = after.split("\\R", -1);
        assertEquals(a.length, b.length, "the number of lines must not change");
        int different = 0;
        for(int i = 0; i < a.length; i++) {
            if(!a[i].equals(b[i])) {
                different++;
            }
        }
        return different;
    }
}
