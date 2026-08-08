package com.rigorberto.zstdnetworkproject;

/**
 * Prints the colored ZSTD startup banner to the console, in the same style as the well-known
 * nLogin plugin banner. The logo is rendered in blue and the footer line in white using ANSI
 * escape codes, which the game/proxy consoles render when they support colors.
 */
public final class StartupBanner {

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

    private static final String FOOTER = "alpha experimental builds v0.1 by Rigorberto";

    private StartupBanner() {
    }

    public static void print() {
        for (String line : ART) {
            System.out.println(BLUE + line + RESET);
        }
        System.out.println();
        System.out.println(WHITE + FOOTER + RESET);
    }
}
