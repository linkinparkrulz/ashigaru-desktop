package com.sparrowwallet.sparrow.net.dojo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.HttpClientService;
import com.sparrowwallet.sparrow.paynym.Bip47Signing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Dojo Bay directory: the community list of public Dojos, served as an onion service. Read the way Sentinel's Dojo Bay
 * edition reads it (tetragrammalol/sentinel-android-dojobay, ui/dojo/DojoBay*.kt):
 *
 * <ol>
 * <li>The directory is trusted only when its {@code data/operator.json} is signed by the operator's BIP47 key, names the address
 * it was fetched from, and that key is the pinned {@link #DEFAULT_OPERATOR_CODE}. Otherwise nothing from it is shown.</li>
 * <li>Each listing is checked on its own: its {@code signed} block must be a listing, signed by the BIP47 code inside it (and the
 * one the directory gives for it), and the pairing and explorer the directory lists must be the ones that were signed, apart from
 * {@code pairing.version}. Unverified listings are shown, dimmed, with the reason; they cannot be chosen.</li>
 * </ol>
 *
 * Ashigaru connects to a Dojo's Electrum server ({@code indexer_url}), not its Dojo API. That address comes from the directory
 * alone unless the operator's signed message happens to name its host: {@link Listing#addressSigned()} says which, and the
 * confirmation step says so once. Nothing is fetched from a Dojo until the user chooses Connect.
 */
public class DojoBayDirectory {
    private static final Logger log = LoggerFactory.getLogger(DojoBayDirectory.class);

    public static final String BASE_URL = "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/";
    public static final String DIRECTORY_URL = BASE_URL + "data/dojos.json";
    public static final String OPERATOR_URL = BASE_URL + "data/operator.json";

    /**
     * The Dojo Bay operator's payment code, pinned as Sentinel pins it (DojoBayConstants.DEFAULT_OPERATOR_CODE; Dojo Bay
     * data/operator.json at a3125f5, notification address 1HmVAPcz3hyETMnu4UzgJTw1mmrNcJKVB).
     */
    public static final String DEFAULT_OPERATOR_CODE =
            "PM8TJfHaHuh5xgKoEbrkWaBtytb8qrRNYdmHzxiFcvacD6HpyyxvSV3VLKYsr6UvMxB4jvJP4xxNvCp2pRY3cJPNmLB2L8nYEttaFVszXSBjXNMy8cD9";

    /** Sizes past which a reply is refused, as Sentinel caps them */
    static final int MAX_DIRECTORY_CHARS = 2 * 1024 * 1024;
    static final int MAX_OPERATOR_CHARS = 16 * 1024;
    static final int MAX_LISTINGS = 256;
    static final int MAX_SIGNED_CHARS = 4 * 1024;

    private static final Pattern PAYMENT_CODE = Pattern.compile("PM8T[1-9A-HJ-NP-Za-km-z]+");
    /** The signed pairing, then the signer's code on a line of its own: "{...}\nBIP47: PM8T..." */
    private static final Pattern LISTING_TAIL = Pattern.compile("^([\\s\\S]*?)\\n\\s*BIP47:\\s*\\n?(\\S+)\\s*$");

    /** Why a directory or listing is not trusted */
    public enum Problem {
        OPERATOR_MISSING("Dojo Bay did not say who runs it (no signed operator file)."),
        OPERATOR_UNVERIFIED("Dojo Bay's operator file is not signed correctly."),
        WRONG_ADDRESS("Dojo Bay's operator file names a different address than the one it was fetched from."),
        OPERATOR_CODE_MISMATCH("Dojo Bay is signed by someone other than its known operator."),
        TOO_LARGE("Dojo Bay sent more than a directory should be."),
        MISSING_SIGNATURE("Not signed by its operator."),
        UNREADABLE_BLOCK("Its signature block cannot be read."),
        NOT_A_LISTING("What it is signed with is not a Dojo listing."),
        BAD_SIGNATURE("Its signature does not verify."),
        SIGNER_NOT_CODE("It is not signed by the payment code it gives."),
        PAYLOAD_MISMATCH("Its listed details are not the ones its operator signed."),
        NOT_A_PAIRING("What its operator signed is not a Dojo pairing.");

        private final String text;

        Problem(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /**
     * One Dojo in the directory. {@code signerCode} is the payment code the signature proves (the directory's own name and code
     * are not signed). {@code problem} is null for a verified listing. {@code explorerUrl} is taken from the signed text.
     */
    public record Listing(String id, String name, String paynym, String country, String jurisdiction, String version, boolean online,
                          String signerCode, String indexerUrl, String explorerUrl, boolean addressSigned, Problem problem) {
        public boolean verified() {
            return problem == null;
        }

        /** The name the server is saved and shown under: the Dojo's name, and its PayNym when it gives one */
        public String displayName() {
            boolean hasName = name != null && !name.isBlank();
            boolean hasPaynym = paynym != null && !paynym.isBlank();
            if(hasName && hasPaynym) {
                return name + " (" + paynym + ")";
            }
            return hasName ? name : hasPaynym ? paynym : id;
        }

        public Server server() {
            return new Server(indexerUrl, displayName());
        }

        public Server explorer() {
            return explorerUrl == null ? null : new Server(explorerUrl, displayName());
        }
    }

    /** The directory as read: its listings for this network, or the reason it was refused (then there are none) */
    public record Result(List<Listing> listings, Problem refused, Instant fetchedAt) {
        public boolean trusted() {
            return refused == null;
        }
    }

    //The last directory read, for this network, shown at once the next time the list is opened in this session
    private static volatile Result lastResult;
    private static volatile Network lastNetwork;

    /** The last list read this session for the current network, or null */
    public static Result lastResult() {
        return lastNetwork == Network.get() ? lastResult : null;
    }

    /**
     * Fetches the operator file and then the directory, over Tor, with one retry each. Blocking: call off the UI thread.
     * Refused directories return a result with no listings and the reason.
     */
    public static Result fetch(HttpClientService http) throws Exception {
        String operatorJson = fetchText(http, OPERATOR_URL);
        if(operatorJson.length() > MAX_OPERATOR_CHARS) {
            return remember(new Result(List.of(), Problem.TOO_LARGE, Instant.now()));
        }
        Problem operatorProblem = checkOperator(operatorJson, BASE_URL, DEFAULT_OPERATOR_CODE);
        if(operatorProblem != null) {
            log.warn("Dojo Bay directory refused: " + operatorProblem);
            return remember(new Result(List.of(), operatorProblem, Instant.now()));
        }

        String directoryJson = fetchText(http, DIRECTORY_URL);
        if(directoryJson.length() > MAX_DIRECTORY_CHARS) {
            return remember(new Result(List.of(), Problem.TOO_LARGE, Instant.now()));
        }
        return remember(new Result(readListings(directoryJson, LocalDate.now(java.time.ZoneOffset.UTC)), null, Instant.now()));
    }

    private static Result remember(Result result) {
        lastResult = result;
        lastNetwork = Network.get();
        return result;
    }

    private static String fetchText(HttpClientService http, String url) throws Exception {
        try {
            return http.requestJson(url, String.class, null);
        } catch(Exception e) {
            log.info("Retrying " + url + " after: " + e.getMessage());
            return http.requestJson(url, String.class, null);
        }
    }

    /**
     * Whether {@code data/operator.json} binds the directory to its operator: {@code verifySigned} is a signed message whose first
     * line is the directory's own address ({@code onion}, which must be where it was fetched from), naming {@code paymentCode},
     * signed by that code's notification key, and that code is the pinned one. Null when it does; otherwise why not.
     */
    static Problem checkOperator(String operatorJson, String fetchedFrom, String pinnedCode) {
        JsonObject doc;
        try {
            doc = JsonParser.parseString(operatorJson).getAsJsonObject();
        } catch(Exception e) {
            return Problem.OPERATOR_MISSING;
        }
        String onion = optString(doc, "onion");
        String paymentCode = optString(doc, "paymentCode");
        String signed = optString(doc, "verifySigned");
        if(isBlank(onion) || isBlank(paymentCode) || isBlank(signed)) {
            return Problem.OPERATOR_MISSING;
        }
        if(signed.length() > MAX_SIGNED_CHARS) {
            return Problem.OPERATOR_UNVERIFIED;
        }
        SignedMessageVerifier.ParsedBlock block;
        try {
            block = SignedMessageVerifier.parse(signed.replace("\r\n", "\n"));
        } catch(Exception e) {
            return Problem.OPERATOR_UNVERIFIED;
        }
        String firstLine = block.message().split("\n", 2)[0];
        if(!normUrl(firstLine).equals(normUrl(onion)) || !normUrl(onion).equals(normUrl(fetchedFrom))) {
            return Problem.WRONG_ADDRESS;
        }
        Matcher code = Pattern.compile("BIP47:\\s*(PM8T[1-9A-HJ-NP-Za-km-z]+)").matcher(block.message());
        if(!code.find() || !code.group(1).equals(paymentCode.trim())) {
            return Problem.OPERATOR_UNVERIFIED;
        }
        if(!SignedMessageVerifier.verify(signed, null, paymentCode.trim())) {
            return Problem.OPERATOR_UNVERIFIED;
        }
        if(!paymentCode.trim().equals(pinnedCode)) {
            return Problem.OPERATOR_CODE_MISMATCH;
        }
        return null;
    }

    /** Every listing for the current network that gives an Electrum server, checked; at most {@link #MAX_LISTINGS} are read */
    static List<Listing> readListings(String directoryJson, LocalDate today) {
        List<Listing> listings = new ArrayList<>();
        JsonObject directory = JsonParser.parseString(directoryJson).getAsJsonObject();
        if(!directory.has("nodes") || !directory.get("nodes").isJsonArray()) {
            return listings;
        }
        List<String> seenHosts = new ArrayList<>();
        int read = 0;
        for(JsonElement element : directory.getAsJsonArray("nodes")) {
            if(++read > MAX_LISTINGS) {
                break;
            }
            try {
                JsonObject node = element.getAsJsonObject();
                if(!matchesNetwork(optString(node, "network"))) {
                    continue;
                }
                String indexerUrl = optString(node, "indexer_url");
                if(isBlank(indexerUrl)) {
                    //Nothing Ashigaru can connect to
                    continue;
                }
                Listing listing = checkListing(node, today);
                if(!seenHosts.contains(listing.server().getHost())) {
                    seenHosts.add(listing.server().getHost());
                    listings.add(listing);
                }
            } catch(Exception e) {
                log.warn("Skipping malformed Dojo Bay listing: " + e.getMessage());
            }
        }
        //Verified first, then online, then by name
        listings.sort(java.util.Comparator.comparing((Listing l) -> !l.verified()).thenComparing(l -> !l.online())
                .thenComparing(l -> l.displayName().toLowerCase(Locale.ROOT)));
        return listings;
    }

    /** One listing, checked as Sentinel checks it (DojoBaySignatures.checkNode), plus Ashigaru's listing tag */
    static Listing checkListing(JsonObject node, LocalDate today) {
        String indexerUrl = optString(node, "indexer_url");
        String signed = optString(node, "signed");
        String listedCode = optString(node, "paymentCode");
        Problem problem = null;
        String signerCode = null;
        String explorerUrl = null;
        boolean addressSigned = false;

        if(isBlank(signed)) {
            problem = Problem.MISSING_SIGNATURE;
        } else if(signed.length() > MAX_SIGNED_CHARS) {
            problem = Problem.UNREADABLE_BLOCK;
        } else {
            SignedMessageVerifier.ParsedBlock block = null;
            try {
                block = SignedMessageVerifier.parse(signed.replace("\r\n", "\n"));
            } catch(Exception e) {
                problem = Problem.UNREADABLE_BLOCK;
            }
            if(block != null) {
                String message = block.message();
                if(!isListing(message, today)) {
                    problem = Problem.NOT_A_LISTING;
                } else {
                    String body = message.startsWith(Bip47Signing.DOJO_LISTING_TAG) ? message.substring(Bip47Signing.DOJO_LISTING_TAG.length()).strip() : message;
                    Matcher tail = LISTING_TAIL.matcher(body);
                    String pairingText = tail.matches() ? tail.group(1).strip() : body;
                    signerCode = tail.matches() ? tail.group(2) : listedCode;
                    if(isBlank(signerCode) || !PAYMENT_CODE.matcher(signerCode).matches()) {
                        problem = Problem.SIGNER_NOT_CODE;
                    } else if(!SignedMessageVerifier.verify(signed, null, signerCode)) {
                        problem = SignedMessageVerifier.verify(signed, null) ? Problem.SIGNER_NOT_CODE : Problem.BAD_SIGNATURE;
                    } else if(!isBlank(listedCode) && !SignedMessageVerifier.verify(signed, null, listedCode)) {
                        //The directory's own code for this Dojo must be the signer too, or it could show one operator's name over another's listing
                        problem = Problem.SIGNER_NOT_CODE;
                    } else {
                        JsonObject signedPayload = parseObject(pairingText);
                        JsonObject signedPairing = signedPayload == null || !signedPayload.has("pairing") || !signedPayload.get("pairing").isJsonObject()
                                ? null : signedPayload.getAsJsonObject("pairing");
                        if(signedPairing == null || isBlank(optString(signedPairing, "url"))) {
                            problem = Problem.NOT_A_PAIRING;
                        } else if(!sameAsSigned(signedPayload, node.get("payload"))) {
                            problem = Problem.PAYLOAD_MISMATCH;
                        } else {
                            explorerUrl = explorerUrl(signedPayload);
                            String indexerHost = hostOf(indexerUrl);
                            addressSigned = indexerHost != null && message.contains(indexerHost);
                        }
                    }
                }
            }
        }

        return new Listing(optString(node, "id"), optString(node, "name"), optString(node, "paynym"), country(optString(node, "country")),
                optString(node, "jurisdiction"), optString(node, "version"), "active".equalsIgnoreCase(optString(node, "status")),
                problem == null ? signerCode : null, indexerUrl, explorerUrl, addressSigned, problem);
    }

    /**
     * Whether the pairing and explorer the directory lists are the ones that were signed. Only those two keys are compared, and a
     * different {@code pairing.version} alone is allowed (a Dojo updated since it signed), as Sentinel allows it.
     */
    static boolean sameAsSigned(JsonObject signedPayload, JsonElement listed) {
        if(listed == null || !listed.isJsonObject()) {
            return false;
        }
        JsonObject signedCore = core(signedPayload);
        JsonObject listedCore = core(listed.getAsJsonObject());
        if(signedCore.equals(listedCore)) {
            return true;
        }
        if(isVersionString(signedCore) && isVersionString(listedCore)) {
            JsonObject a = signedCore.deepCopy();
            JsonObject b = listedCore.deepCopy();
            a.getAsJsonObject("pairing").remove("version");
            b.getAsJsonObject("pairing").remove("version");
            return a.equals(b);
        }
        return false;
    }

    private static JsonObject core(JsonObject payload) {
        JsonObject core = new JsonObject();
        if(payload.has("pairing")) {
            core.add("pairing", payload.get("pairing"));
        }
        if(payload.has("explorer")) {
            core.add("explorer", payload.get("explorer"));
        }
        return core;
    }

    private static boolean isVersionString(JsonObject core) {
        return core.has("pairing") && core.get("pairing").isJsonObject() && core.getAsJsonObject("pairing").has("version")
                && core.getAsJsonObject("pairing").get("version").isJsonPrimitive() && core.getAsJsonObject("pairing").getAsJsonPrimitive("version").isString();
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch(Exception e) {
            return null;
        }
    }

    /** The block explorer in the signed payload, skipping the {@code explorer.null} placeholder; null when there is none */
    private static String explorerUrl(JsonObject payload) {
        if(!payload.has("explorer") || !payload.get("explorer").isJsonObject()) {
            return null;
        }
        JsonObject explorer = payload.getAsJsonObject("explorer");
        String url = optString(explorer, "url");
        String type = optString(explorer, "type");
        if(isBlank(url) || (type != null && type.toLowerCase(Locale.ROOT).contains("null"))) {
            return null;
        }
        try {
            new Server(url);
            return url;
        } catch(Exception e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return new Server(url).getHost();
        } catch(Exception e) {
            return null;
        }
    }

    /** A two-letter country code, upper case, or null */
    static String country(String code) {
        if(code == null) {
            return null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.matches("[A-Z]{2}") ? c : null;
    }

    /** A payment code shortened to its first and last 8 characters, as the list shows the signer */
    public static String shortCode(String code) {
        if(code == null) {
            return "";
        }
        return code.length() <= 20 ? code : code.substring(0, 8) + "…" + code.substring(code.length() - 8);
    }

    private static String normUrl(String url) {
        String u = url == null ? "" : url.strip();
        while(u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * The last day an untagged listing is accepted. Listings made before the tag existed carry none; operators have until then to sign theirs again
     * with the tag as their first line. After it, only tagged listings are read.
     */
    static final LocalDate UNTAGGED_LISTINGS_UNTIL = LocalDate.of(2027, 6, 30);

    /**
     * Whether a signed message is a Dojo directory listing. A tagged one: the first line is exactly the tag, there is more after it, and the tag
     * appears nowhere else. An untagged one only until {@link #UNTAGGED_LISTINGS_UNTIL}, and never one that is a Tools -> Sign message or an
     * Auth47 login challenge.
     */
    static boolean isListing(String message, LocalDate today) {
        if(message == null) {
            return false;
        }
        String tag = Bip47Signing.DOJO_LISTING_TAG;
        if(message.startsWith(tag)) {
            String rest = message.substring(tag.length());
            return rest.startsWith("\n") && !rest.isBlank() && !rest.contains(tag);
        }
        if(message.contains(tag) || Bip47Signing.isSignMessageOrLogin(message)) {
            return false;
        }
        return !today.isAfter(UNTAGGED_LISTINGS_UNTIL);
    }

    /** Matches a Dojo's network field ("mainnet"/"testnet") against the current network; all test networks count as not mainnet */
    static boolean matchesNetwork(String network) {
        if(network == null) {
            return false;
        }
        return "mainnet".equalsIgnoreCase(network.trim()) == (Network.get() == Network.MAINNET);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull() || !el.isJsonPrimitive()) ? null : el.getAsString();
    }
}
