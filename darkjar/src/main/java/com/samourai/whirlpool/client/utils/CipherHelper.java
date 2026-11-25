package com.samourai.whirlpool.client.utils;

import org.bouncycastle.crypto.params.RSAKeyParameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class CipherHelper {

    public static RSAKeyParameters loadRSAPublicKey(final InputStream pubKey) throws IOException {
        String pem = new String(pubKey.readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            return new RSAKeyParameters(false, publicKey.getModulus(), publicKey.getPublicExponent());
        } catch (Exception e) {
            throw new IOException("Failed to load RSA public key", e);
        }
    }
}
