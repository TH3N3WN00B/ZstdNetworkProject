package com.rigorberto.zstdnetworkproject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {

    public static final String KEY_CONFIG_VERSION = "config-version";
    public static final String KEY_COMPRESSION_LEVEL = "compression-level";
    public static final String KEY_FAST = "fast";
    public static final String KEY_FAST_LEVEL = "fast-level";
    public static final String KEY_DEBUG_MESSAGE = "debug-message";
    public static final String KEY_HARDWARE_ACCELERATION = "hardware-acceleration";
    public static final String KEY_HARDWARE_ACCELERATION_THREADS = "hardware-acceleration-threads";
    public static final String KEY_COMPRESSION_THRESHOLD = "compression-threshold";
    public static final String KEY_COMPRESS_IF_BENEFICIAL = "compress-if-beneficial";
    public static final String KEY_DEBUG_OVERLAY = "debug-overlay";
    public static final String KEY_DISABLED_SERVERS = "disabled-servers";
    public static final String KEY_AUTO_DISABLE_MODS = "auto-disable-mods";
    public static final String KEY_HEX_DUMP = "hex-dump";

    public static final int DEFAULT_COMPRESSION_LEVEL = 3;
    public static final int MAX_COMPRESSION_LEVEL = 22;
    public static final int DEFAULT_FAST_LEVEL = 1;
    public static final int MAX_FAST_LEVEL = 99;
    public static final int MAX_HARDWARE_ACCELERATION_THREADS = 64;
    public static final int MIN_COMPRESSION_THRESHOLD = 256;

    /**
     * Current config schema version. Bump this and append a new block to {@link #DEFAULT_BLOCKS}
     * whenever a new setting is added: existing config.yml files are then auto-updated, appending
     * the new setting at the bottom of the file.
     */
    public static final int CONFIG_VERSION = 8;

    /**
     * Each block is the comment lines plus the {@code key: value} line for one setting. The first
     * non-comment, non-empty line of each block defines the setting's key.
     */
    private static final List<String> DEFAULT_BLOCKS = List.of(
            "# Compression level used for zstd packet compression (see https://github.com/facebook/zstd).\n" +
            "# The default level (3) is the level recommended by Zstandard.\n" +
            "# Valid range: 1 - 22. Higher levels compress better but use more CPU.\n" +
            "compression-level: 3",
            "# Fast mode, equivalent to the zstd CLI '--fast=#' flag.\n" +
            "# When enabled, zstd uses negative (fast) compression levels, trading\n" +
            "# some compression ratio for much higher speed.\n" +
            "# Disabled by default.\n" +
            "fast: false",
            "# The '#' value used by --fast when fast mode is enabled.\n" +
            "# Higher values are faster (and compress less). Default: 1.\n" +
            "fast-level: 1",
            "# Send an in-game chat message to the player when zstd compression is enabled.\n" +
            "# Enabled by default.\n" +
            "debug-message: true",
            "# CPU hardware acceleration: uses zstd's multithreaded mode to compress large\n" +
            "# packets (>= 512 KiB uncompressed) on multiple CPU cores.\n" +
            "# Only affects packet compression; zstd decompression is inherently single-threaded.\n" +
            "# Enabled by default.\n" +
            "hardware-acceleration: true",
            "# How many CPU worker threads zstd may use per large packet.\n" +
            "# 0 = auto (half of the available processors, capped at 4).\n" +
            "# A larger value can speed up huge packets (e.g. chunks) at the cost of CPU usage.\n" +
            "hardware-acceleration-threads: 0",
            "# Packets smaller than this (uncompressed bytes) are sent uncompressed.\n" +
            "# Must be at least 256, so that every packet this encoder compresses is decoded\n" +
            "# as zstd rather than zlib by peers.\n" +
            "compression-threshold: 256",
            "# Send a packet uncompressed when compression would not actually shrink it.\n" +
            "# Incompressible data (e.g. already-compressed textures or chunk section data)\n" +
            "# can otherwise end up LARGER after zstd than before. Enabled by default.\n" +
            "compress-if-beneficial: true",
            "# Show zstd statistics (status, packets, compression ratio) in the client\n" +
            "# debug screen bandwidth view (F3 + 3). Client-side only.\n" +
            "# Disabled by default.\n" +
            "debug-overlay: false",
            "# Servers where this mod stays completely passive (pure vanilla behavior).\n" +
            "# Some servers use custom network protocol patchers that break when their\n" +
            "# compression handlers are replaced. Entries are comma-separated substrings\n" +
            "# matched against the server address (host or host:port), e.g.\n" +
            "# \"disabled-servers\": \"play.example.com, 10.0.0.5:25565\"\n" +
            "disabled-servers: ",
            "# Mods whose presence makes this mod stay completely passive (pure vanilla\n" +
            "# behavior). Entries are mod ids, comma-separated (empty by default).\n" +
            "auto-disable-mods: ",
            "# Dump every frame crossing the compression encoder/decoder to zstd-hexdump.log\n" +
            "# (sizes, direction, peer address and full hex). Only for diagnosing protocol\n" +
            "# problems with custom servers; adds I/O overhead and grows the log fast.\n" +
            "# Disabled by default.\n" +
            "hex-dump: false"
    );

    private static final String DEFAULT_CONFIG =
            "# ZstdNetworkProject configuration\n" +
            "# Config files are auto-updated: new settings are appended at the bottom\n" +
            "# as the plugin evolves, so this file stays in sync with the latest version.\n" +
            KEY_CONFIG_VERSION + ": " + CONFIG_VERSION + "\n\n" +
            String.join("\n\n", DEFAULT_BLOCKS) + "\n";

    private ConfigLoader() {
    }

    public static ZstdSettings load(Path configFile) throws IOException {
        if (!Files.isRegularFile(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
        } else {
            updateConfig(configFile);
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
        settings.setHardwareAcceleration(parseBoolean(values.get(KEY_HARDWARE_ACCELERATION), true));
        settings.setHardwareAccelerationThreads(clamp(parseInt(values.get(KEY_HARDWARE_ACCELERATION_THREADS), 0), 0, MAX_HARDWARE_ACCELERATION_THREADS));
        settings.setCompressionThreshold(Math.max(MIN_COMPRESSION_THRESHOLD,
                parseInt(values.get(KEY_COMPRESSION_THRESHOLD), ZstdSettings.MIN_COMPRESSION_THRESHOLD)));
        settings.setCompressIfBeneficial(parseBoolean(values.get(KEY_COMPRESS_IF_BENEFICIAL), true));
        settings.setDebugOverlay(parseBoolean(values.get(KEY_DEBUG_OVERLAY), false));
        settings.setDisabledServers(parseList(values.get(KEY_DISABLED_SERVERS)));
        String autoDisableMods = values.get(KEY_AUTO_DISABLE_MODS);
        settings.setAutoDisableMods(autoDisableMods == null
                ? ZstdSettings.DEFAULT_AUTO_DISABLE_MODS
                : parseList(autoDisableMods));
        settings.setHexDump(parseBoolean(values.get(KEY_HEX_DUMP), false));
        return settings;
    }

    /** Parses a comma-separated setting value into a list of trimmed, non-empty entries. */
    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    /**
     * Auto-updates an existing config.yml: appends any newly introduced settings at the bottom and
     * writes the current {@link #CONFIG_VERSION} at the top. The file is only rewritten when
     * something actually changed.
     */
    private static void updateConfig(Path configFile) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8));
        Map<String, String> values = parseValues(lines);
        boolean changed = false;

        int fileVersion = parseInt(values.get(KEY_CONFIG_VERSION), 0);
        if (fileVersion < CONFIG_VERSION) {
            int versionLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String key = keyOfLine(lines.get(i));
                if (KEY_CONFIG_VERSION.equals(key)) {
                    versionLine = i;
                    break;
                }
            }
            if (versionLine >= 0) {
                lines.set(versionLine, KEY_CONFIG_VERSION + ": " + CONFIG_VERSION);
            } else {
                lines.add(0, KEY_CONFIG_VERSION + ": " + CONFIG_VERSION);
            }

            for (String block : DEFAULT_BLOCKS) {
                String key = keyOfBlock(block);
                if (!values.containsKey(key)) {
                    if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                        lines.add("");
                    }
                    for (String line : block.split("\n")) {
                        lines.add(line);
                    }
                }
            }
            changed = true;
        }

        if (changed) {
            Files.writeString(configFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseValues(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            String key = keyOfLine(line);
            if (key == null) {
                continue;
            }
            int colon = line.indexOf(':');
            values.put(key, line.substring(colon + 1).trim());
        }
        return values;
    }

    private static String keyOfLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return trimmed.substring(0, colon).trim();
    }

    private static String keyOfBlock(String block) {
        for (String line : block.split("\n")) {
            String key = keyOfLine(line);
            if (key != null) {
                return key;
            }
        }
        throw new IllegalStateException("Config block has no key: " + block);
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
