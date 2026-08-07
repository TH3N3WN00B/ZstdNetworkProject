package com.rigorberto.zstdnetworkproject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {

    public static final String KEY_COMPRESSION_LEVEL = "compression-level";
    public static final String KEY_FAST = "fast";
    public static final String KEY_FAST_LEVEL = "fast-level";
    public static final String KEY_DEBUG_MESSAGE = "debug-message";

    public static final int DEFAULT_COMPRESSION_LEVEL = 3;
    public static final int MAX_COMPRESSION_LEVEL = 22;
    public static final int DEFAULT_FAST_LEVEL = 1;
    public static final int MAX_FAST_LEVEL = 99;

    private static final String DEFAULT_CONFIG =
            "# ZstdNetworkProject configuration\n" +
            "# Compression level used for zstd packet compression (see https://github.com/facebook/zstd).\n" +
            "# The default level (3) is the level recommended by Zstandard.\n" +
            "# Valid range: 1 - 22. Higher levels compress better but use more CPU.\n" +
            "compression-level: 3\n" +
            "\n" +
            "# Fast mode, equivalent to the zstd CLI '--fast=#' flag.\n" +
            "# When enabled, zstd uses negative (fast) compression levels, trading\n" +
            "# some compression ratio for much higher speed.\n" +
            "# Disabled by default.\n" +
            "fast: false\n" +
            "\n" +
            "# The '#' value used by --fast when fast mode is enabled.\n" +
            "# Higher values are faster (and compress less). Default: 1.\n" +
            "fast-level: 1\n" +
            "\n" +
            "# Send an in-game chat message to the player when zstd compression is enabled.\n" +
            "# Enabled by default.\n" +
            "debug-message: true\n";

    private ConfigLoader() {
    }

    public static ZstdSettings load(Path configFile) throws IOException {
        if (!Files.isRegularFile(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
        }

        Map<String, String> values = new HashMap<>();
        List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            values.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }

        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionLevel(clamp(parseInt(values.get(KEY_COMPRESSION_LEVEL), DEFAULT_COMPRESSION_LEVEL), 1, MAX_COMPRESSION_LEVEL));
        settings.setFast(parseBoolean(values.get(KEY_FAST), false));
        settings.setFastLevel(clamp(parseInt(values.get(KEY_FAST_LEVEL), DEFAULT_FAST_LEVEL), 1, MAX_FAST_LEVEL));
        settings.setDebugMessage(parseBoolean(values.get(KEY_DEBUG_MESSAGE), true));
        return settings;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return "true".equalsIgnoreCase(trimmed) || "yes".equalsIgnoreCase(trimmed);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
