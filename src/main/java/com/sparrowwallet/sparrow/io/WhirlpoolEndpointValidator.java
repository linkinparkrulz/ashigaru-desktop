package com.sparrowwallet.sparrow.io;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a Whirlpool server entered in Settings before it is saved, so a typo shows up as a message there
 * rather than as a mix that fails to start.
 */
public final class WhirlpoolEndpointValidator {
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private WhirlpoolEndpointValidator() {
    }

    /**
     * @return a message describing the first problem, or empty if the entry can be saved. Leaving every field
     * blank is valid, and means "use the built-in server".
     */
    public static Optional<String> validate(String coordinatorUrl, String outputRegUrl, String blindSignatureKeyPath) {
        boolean noCoordinator = isBlank(coordinatorUrl);
        if(noCoordinator && isBlank(outputRegUrl) && isBlank(blindSignatureKeyPath)) {
            return Optional.empty();
        }

        if(noCoordinator) {
            return Optional.of("Enter a coordinator URL, or clear every field to use the built-in server.");
        }

        Optional<String> coordinatorError = validateUrl("coordinator URL", coordinatorUrl);
        if(coordinatorError.isPresent()) {
            return coordinatorError;
        }

        if(!isBlank(outputRegUrl)) {
            Optional<String> outputRegError = validateUrl("output registration URL", outputRegUrl);
            if(outputRegError.isPresent()) {
                return outputRegError;
            }
        }

        if(!isBlank(blindSignatureKeyPath)) {
            return validateKey(blindSignatureKeyPath.trim());
        }

        return Optional.empty();
    }

    static Optional<String> validateUrl(String what, String value) {
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch(Exception e) {
            return Optional.of("The " + what + " is not a valid address.");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if(host == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            return Optional.of("The " + what + " must start with http:// or https:// and include a host.");
        }

        //Plain http is only acceptable where nothing crosses the open internet unencrypted
        if(scheme.equals("http") && !isOnionOrLocal(host)) {
            return Optional.of("The " + what + " must use https:// unless it is an .onion address or on your own network.");
        }

        return Optional.empty();
    }

    static Optional<String> validateKey(String path) {
        File file = new File(path);
        if(!file.isFile() || !file.canRead()) {
            return Optional.of("The blind signature key file cannot be read: " + path);
        }

        //The same format the mixing code loads: a PEM "BEGIN PUBLIC KEY" block holding an RSA SubjectPublicKeyInfo
        try {
            String pem = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String body = pem.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            if(!pem.contains("-----BEGIN PUBLIC KEY-----")) {
                throw new IllegalArgumentException("not a PEM public key");
            }

            byte[] der = Base64.getDecoder().decode(body);
            if(!(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)) instanceof RSAPublicKey)) {
                throw new IllegalArgumentException("not an RSA key");
            }
        } catch(Exception e) {
            return Optional.of("The blind signature key file is not a PEM-encoded RSA public key.");
        }

        return Optional.empty();
    }

    static boolean isOnionOrLocal(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".onion") || lower.equals("localhost")) {
            return true;
        }

        //Only literal addresses are examined, so this never makes a DNS lookup
        if(IPV4_LITERAL.matcher(lower).matches()) {
            try {
                InetAddress address = InetAddress.getByName(lower);
                return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
            } catch(Exception e) {
                return false;
            }
        }

        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
