package com.sparrowwallet.sparrow.swap;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.io.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Monero nodes swaps use. A swap only releases its side once the maker's XMR is seen locked, so
 * the node that reports it is trusted with the swap: public nodes are used in agreement (two or more,
 * all of which must see the lock), while a node the user added is their own and trusted on its own.
 */
public final class MoneroNodes {
    /** At least this many public nodes must agree */
    public static final int MIN_PUBLIC_NODES = 2;

    public record Node(String name, String address) {}

    //Cake Wallet's public node, and nodes from Feather Wallet's curated list (src/assets/nodes.json)
    public static final List<Node> PUBLIC_NODES = List.of(
            new Node("Cake Wallet", "cakexmrkbd7ptshw7vfzhzqgg77xgeavdrxm6zbu57lppfkj3fczbrqd.onion:18081"),
            new Node("plowsof", "plowsof3t5hogddwabaeiyrno25efmzfxyro2vligremt7sxpsclfaid.onion:18089"),
            new Node("rucknium", "rucknium757bokwv3ss35ftgc3gzb7hgbvvglbg3hisp7tsj2fkd2nyd.onion:18081"),
            new Node("ravfxmr", "ravfxmrqgd4j2o2gdc2yaij2wcmkcflu2rdnzhng5htk5hgnh6gmyeid.onion:18081"),
            new Node("moneroxmr", "moneroxmrxw44lku6qniyarpwgznpcwml4drq7vb24ppatlcg4kmxpqd.onion:18089")
    );

    /** Ticked until the user chooses */
    static final List<String> DEFAULT_SELECTION = PUBLIC_NODES.subList(0, 3).stream().map(Node::address).toList();

    private MoneroNodes() {}

    public static List<String> getSelected() {
        List<String> selected = Config.get().getSwapMoneroNodes();
        return selected == null ? DEFAULT_SELECTION : selected;
    }

    public static boolean isPublic(String address) {
        return PUBLIC_NODES.stream().anyMatch(node -> node.address().equalsIgnoreCase(address));
    }

    /**
     * The nodes to give the engine, the first being its own node and the rest witnesses, or an empty
     * list when the selection is not enough to swap with.
     */
    public static List<String> forEngine(List<String> selected) {
        List<String> custom = selected.stream().filter(address -> !isPublic(address)).toList();
        if(!custom.isEmpty()) {
            //The user's own node is trusted alone; further own nodes act as witnesses
            return custom;
        }
        List<String> publicNodes = new ArrayList<>(selected);
        return publicNodes.size() >= MIN_PUBLIC_NODES ? publicNodes : List.of();
    }

    /** True when a node of the user's own is ticked, which makes it the only one used */
    public static boolean hasOwn(List<String> selected) {
        return selected.stream().anyMatch(address -> !isPublic(address));
    }

    /**
     * How many ticked public nodes are not used because the user's own node is ticked as well. Ticking a node of their
     * own means they trust it alone, so the public ones are dropped (see {@link #forEngine}); this counts them so the
     * screens can say so instead of showing four ticks and one node.
     */
    public static int ignoredPublic(List<String> selected) {
        return hasOwn(selected) ? (int)selected.stream().filter(MoneroNodes::isPublic).count() : 0;
    }

    /**
     * What swaps run on, as a phrase to follow "Using": the engine's nodes and, when ticked nodes are left out, which.
     * Null when the selection cannot be used.
     */
    public static String describe(List<String> selected) {
        List<String> used = forEngine(selected);
        if(used.isEmpty()) {
            return null;
        }
        int ignored = ignoredPublic(selected);
        String left = ignored == 0 ? "" : "the " + ignored + " public node" + (ignored == 1 ? " you ticked is" : "s you ticked are") + " not used";
        if(hasOwn(selected)) {
            if(used.size() == 1) {
                return ignored == 0 ? "your own Monero node, trusted on its own" : "your own Monero node only; " + left;
            }
            return used.size() + " of your own Monero nodes, which must agree before a swap releases your BTC" + (ignored == 0 ? "" : "; " + left);
        }
        return used.size() + " public Monero nodes, which must agree before a swap releases your BTC";
    }

    /** A note for the Settings screen while the nodes are being chosen, or null when there is nothing to point out */
    public static String note(List<String> selected) {
        int ignored = ignoredPublic(selected);
        if(ignored == 0) {
            return null;
        }
        return "Your own node is ticked, so only your own node is used. The " + ignored + " public node" + (ignored == 1 ? " you ticked is" : "s you ticked are") + " ignored.";
    }

    /** Why the selection cannot be used, or null */
    public static String problem(List<String> selected) {
        if(forEngine(selected).isEmpty()) {
            return "Tick at least " + MIN_PUBLIC_NODES + " public Monero nodes, or add your own, in Settings → Swaps.";
        }
        return null;
    }

    public static boolean isOnionHostAndPort(String value) {
        try {
            HostAndPort node = HostAndPort.fromString(value.trim());
            return node.hasPort() && node.getHost().endsWith(".onion");
        } catch(IllegalArgumentException e) {
            return false;
        }
    }
}
