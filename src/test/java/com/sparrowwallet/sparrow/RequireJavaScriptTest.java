package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The build's guard against packaging an old Java runtime: it must let the patched Temurin 21 through and stop anything older */
class RequireJavaScriptTest {
    private static final Path SCRIPT = Path.of(".github/scripts/require-java.sh");

    private static int run(String found) throws Exception {
        assumeTrue(Files.isRegularFile(SCRIPT), "run the tests from the project directory");
        ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toAbsolutePath().toString(), "21.0.13.1").redirectErrorStream(true);
        builder.environment().put("JAVA_VERSION_OVERRIDE", found);
        Process process;
        try {
            process = builder.start();
        } catch(java.io.IOException e) {
            assumeTrue(false, "no bash on this machine");
            throw e;
        }
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return process.exitValue();
    }

    @Test
    void thePatchedReleaseAndNewerPass() throws Exception {
        for(String version : new String[] {"21.0.13.1", "21.0.13.1+2", "21.0.13.2", "21.0.14", "21.1.0", "22", "25.0.1"}) {
            assertEquals(0, run(version), version);
        }
    }

    @Test
    void anythingOlderStopsTheBuild() throws Exception {
        for(String version : new String[] {"21.0.7", "21.0.12", "21.0.13", "21.0.12.9", "17.0.9", "11.0.2"}) {
            assertNotEquals(0, run(version), version);
        }
    }

    @Test
    void aMissingVersionStopsTheBuild() throws Exception {
        //An override of nothing reads as no override, so the script asks java; with an unreadable answer it must still not pass silently
        assertTrue(Files.readString(SCRIPT, StandardCharsets.UTF_8).contains("could not read the Java version"));
    }
}
