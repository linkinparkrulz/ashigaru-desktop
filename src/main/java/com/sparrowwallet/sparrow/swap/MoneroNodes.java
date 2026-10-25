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
