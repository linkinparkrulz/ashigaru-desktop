package com.sparrowwallet.sparrow.net.dojo;

import com.sparrowwallet.sparrow.paynym.Bip47Signing;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** AD-25: a Dojo listing is a message made as one, with its own first line, not anything else the same kind of key signed */
class DojoListingTagTest {
    private static final String TAG = Bip47Signing.DOJO_LISTING_TAG;
    private static final String BODY = "{\"pairing\":{\"url\":\"http://x.onion/pairing\"}}\n\nBIP47: PM8T";
    private static final LocalDate DURING = DojoNodeDiscovery.UNTAGGED_LISTINGS_UNTIL;
    private static final LocalDate AFTER = DojoNodeDiscovery.UNTAGGED_LISTINGS_UNTIL.plusDays(1);

    @Test
    void aTaggedListingIsAlwaysOne() {
        assertTrue(DojoNodeDiscovery.isListing(TAG + "\n" + BODY, AFTER));
        assertFalse(DojoNodeDiscovery.isListing(TAG + " " + BODY, AFTER), "the tag is a line of its own");
        assertFalse(DojoNodeDiscovery.isListing(TAG + "\n", AFTER), "and something follows it");
        assertFalse(DojoNodeDiscovery.isListing(TAG + "\n" + TAG + "\n" + BODY, AFTER));
        assertFalse(DojoNodeDiscovery.isListing(null, DURING));
    }

    @Test
    void anUntaggedListingOnlyDuringTheTransition() {
        assertTrue(DojoNodeDiscovery.isListing(BODY, DURING));
        assertFalse(DojoNodeDiscovery.isListing(BODY, AFTER));
        assertFalse(DojoNodeDiscovery.isListing("x\n" + TAG + "\n" + BODY, DURING), "the tag only counts as the first line");
    }

    @Test
    void signMessagesAndLoginsAreNeverListings() {
        assertFalse(DojoNodeDiscovery.isListing(Bip47Signing.MESSAGE_PREFIX + BODY, DURING));
        assertFalse(DojoNodeDiscovery.isListing("auth47://nonce?r=http://x.onion/pairing", DURING));
    }
}
