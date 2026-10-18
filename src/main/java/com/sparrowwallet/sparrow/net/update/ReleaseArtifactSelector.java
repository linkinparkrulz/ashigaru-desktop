package com.sparrowwallet.sparrow.net.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the release asset for this platform.
 *
 * <p>Each release ships one package per platform: the Windows .exe installer, a macOS .dmg per
 * architecture, and the Linux AppImage. The UI still presents every installable candidate with the
 * match preselected, so a release carrying anything extra stays a visible choice, not a wrong download.
 */
public class ReleaseArtifactSelector {
    /** A downloadable asset from the release, and whether it is the inferred match. */
    public record Candidate(String name, String url, long size, boolean suggested) {}

    public enum Family { WINDOWS, MACOS, LINUX, UNKNOWN }

    /**
     * Filters {@code assets} to those installable on this platform and marks one as suggested.
     * Attestation files are excluded - they are downloaded separately, not offered as the payload.
     */
    public static List<Candidate> select(List<ReleaseAsset> assets) {
        return select(assets, currentFamily(), System.getProperty("os.arch"));
    }

    static List<Candidate> select(List<ReleaseAsset> assets, Family family, String osArch) {
        List<ReleaseAsset> installable = new ArrayList<>();
        for(ReleaseAsset asset : assets) {
            if(!isAttestation(asset.name()) && matchesFamily(asset.name(), family)) {
                installable.add(asset);
            }
        }

        String suggested = suggestName(installable, family, osArch);
        List<Candidate> candidates = new ArrayList<>();
        for(ReleaseAsset asset : installable) {
            candidates.add(new Candidate(asset.name(), asset.url(), asset.size(), asset.name().equals(suggested)));
        }

        return candidates;
    }

    private static boolean isAttestation(String name) {
        return name.equals(ReleaseTrust.SUMS_ASSET)
                || name.equals(ReleaseTrust.MESSAGE_ASSET)
                || name.equals(ReleaseTrust.SIGNATURE_ASSET)
                || name.startsWith("RELEASE-");
    }

    private static boolean matchesFamily(String name, Family family) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch(family) {
            case WINDOWS -> lower.endsWith(".exe");
            case MACOS -> lower.endsWith(".dmg");
            case LINUX -> lower.endsWith(".appimage");
            case UNKNOWN -> true;
        };
    }

    private static String suggestName(List<ReleaseAsset> assets, Family family, String osArch) {
        return switch(family) {
            case WINDOWS -> firstEndingWith(assets, ".exe");
            case MACOS -> firstEndingWith(assets, macArchSuffix(osArch) + ".dmg");
            case LINUX -> firstEndingWith(assets, ".AppImage");
            case UNKNOWN -> null;
        };
    }

    /** Apple Silicon builds are published as -aarch64, Intel as -x86_64. */
    private static String macArchSuffix(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "-aarch64" : "-x86_64";
    }

    private static String firstEndingWith(List<ReleaseAsset> assets, String suffix) {
        String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
        for(ReleaseAsset asset : assets) {
            if(asset.name().toLowerCase(Locale.ROOT).endsWith(lowerSuffix)) {
                return asset.name();
            }
        }
        return null;
    }

    public static Family currentFamily() {
        String os = System.getProperty("os.name");
        if(os == null) {
            return Family.UNKNOWN;
        }

        String lower = os.toLowerCase(Locale.ROOT);
        if(lower.startsWith("windows")) {
            return Family.WINDOWS;
        }
        if(lower.startsWith("mac") || lower.contains("darwin")) {
            return Family.MACOS;
        }
        if(lower.contains("linux") || lower.contains("unix")) {
            return Family.LINUX;
        }
        return Family.UNKNOWN;
    }

    private ReleaseArtifactSelector() {}
}
