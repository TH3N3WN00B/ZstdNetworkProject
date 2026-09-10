package com.rigorberto.zstdnetworkproject;

/**
 * Prints the colored ZSTD startup banner to the console, in the same style as the well-known
 * nLogin plugin banner. The logo is rendered in blue and the footer line in white using ANSI
 * escape codes, which the game/proxy consoles render when they support colors.
 *
 * <p>The footer version is read from the jar manifest ({@code Implementation-Version}), so it
 * always matches the built artifact without manual edits.
 */
public final class StartupBanner {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("zstdnetworkproject");

    private static final String BLUE = "\u001B[34m";
    private static final String WHITE = "\u001B[37m";
    private static final String RESET = "\u001B[0m";

    private static final String[] ART = {
            " _____  _______________ ",
            "/__  / / ___/_  __/ __ \\",
            "  / /  \\__ \\ / / / / / /",
            " / /_____/ // / / /_/ / ",
            "/____/____//_/ /_____/  "
    };

    private static final String FOOTER = resolveVersion() + " by Rigorberto";

    private StartupBanner() {
    }

    /** Version from the containing jar's manifest, or {@code beta-0.2.4} when unavailable (IDE runs). */
    private static String resolveVersion() {
        try {
            Package pkg = StartupBanner.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            return version != null ? version : "ZstdNetworkProject beta-0.2.4";
        } catch (Throwable t) {
            return "ZstdNetworkProject beta-0.2.4";
        }
    }

    public static void print() {
        StringBuilder sb = new StringBuilder();
        for (String line : ART) {
            sb.append(BLUE).append(line).append(RESET).append(System.lineSeparator());
        }
        sb.append(System.lineSeparator()).append(WHITE).append(FOOTER).append(RESET);
        LOGGER.info(sb.toString());
    }
}
