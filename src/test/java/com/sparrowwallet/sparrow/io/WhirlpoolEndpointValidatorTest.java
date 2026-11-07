package com.sparrowwallet.sparrow.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class WhirlpoolEndpointValidatorTest {
    private static File bundledKey(File dir) throws Exception {
        File key = new File(dir, "key.pem");
        try(InputStream in = WhirlpoolEndpointValidatorTest.class.getClassLoader().getResourceAsStream("cipher/testnet/blind_signature_public_key.pem")) {
            assertNotNull(in, "the bundled testnet key should be on the classpath");
            Files.write(key.toPath(), in.readAllBytes());
        }
        return key;
    }

    @Test
    void everyFieldBlankMeansUseTheBuiltInServer() {
        assertTrue(WhirlpoolEndpointValidator.validate(null, null, null).isEmpty());
        assertTrue(WhirlpoolEndpointValidator.validate("  ", "", " ").isEmpty());
    }

    @Test
    void otherFieldsNeedACoordinator() {
        assertTrue(WhirlpoolEndpointValidator.validate("", "https://out.example.com", "").isPresent());
        assertTrue(WhirlpoolEndpointValidator.validate("", "", "/some/key.pem").isPresent());
    }

    @Test
    void acceptsHttpsOnionAndLocalHttp() {
        assertTrue(WhirlpoolEndpointValidator.validate("https://pool.example.com:8081", "", "").isEmpty());
        assertTrue(WhirlpoolEndpointValidator.validate("http://abcdefghijklmnop.onion", "", "").isEmpty());
        assertTrue(WhirlpoolEndpointValidator.validate("http://127.0.0.1:8080", "", "").isEmpty());
        assertTrue(WhirlpoolEndpointValidator.validate("http://localhost:8080", "", "").isEmpty());
        assertTrue(WhirlpoolEndpointValidator.validate("http://192.168.1.20:8080", "", "").isEmpty());
        assertTrue(WhirlpoolEndpointValidator.validate("http://10.0.0.5:8080", "", "").isEmpty());
    }

    @Test
    void rejectsPlainHttpToTheOpenInternet() {
        assertTrue(WhirlpoolEndpointValidator.validate("http://pool.example.com", "", "").isPresent());
        assertTrue(WhirlpoolEndpointValidator.validate("http://8.8.8.8:8080", "", "").isPresent());
        assertTrue(WhirlpoolEndpointValidator.validate("https://pool.example.com", "http://out.example.com", "").isPresent());
    }

    @Test
    void rejectsMalformedAddresses() {
        assertTrue(WhirlpoolEndpointValidator.validate("pool.example.com", "", "").isPresent());
        assertTrue(WhirlpoolEndpointValidator.validate("ftp://pool.example.com", "", "").isPresent());
        assertTrue(WhirlpoolEndpointValidator.validate("https://", "", "").isPresent());
        assertTrue(WhirlpoolEndpointValidator.validate("https://bad host", "", "").isPresent());
    }

    @Test
    void checksTheBlindSignatureKeyFile(@TempDir File dir) throws Exception {
        File key = bundledKey(dir);
        assertTrue(WhirlpoolEndpointValidator.validate("https://pool.example.com", "", key.getAbsolutePath()).isEmpty());

        assertTrue(WhirlpoolEndpointValidator.validate("https://pool.example.com", "", new File(dir, "missing.pem").getAbsolutePath()).isPresent(),
                "a file that does not exist is rejected");

        File notAKey = new File(dir, "notes.txt");
        Files.writeString(notAKey.toPath(), "this is not a key");
        assertTrue(WhirlpoolEndpointValidator.validate("https://pool.example.com", "", notAKey.getAbsolutePath()).isPresent(),
                "a file that is not a PEM public key is rejected");

        assertTrue(WhirlpoolEndpointValidator.validate("https://pool.example.com", "", dir.getAbsolutePath()).isPresent(),
                "a directory is rejected");
    }
}
