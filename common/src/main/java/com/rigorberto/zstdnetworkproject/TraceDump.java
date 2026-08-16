package com.rigorberto.zstdnetworkproject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Debugging aid: appends frame diagnostics (sizes and hex dumps) to a file so a corrupted transfer
 * can be compared on both ends. Tracing is DISABLED unless the {@code zstdnetworkproject.trace-file}
 * system property is set explicitly, so production servers pay no cost and get no stray log file.
 * When enabled the path comes from that property and defaults to {@code zstd-trace.log}.
 */
public final class TraceDump {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String TRACE_PROPERTY = "zstdnetworkproject.trace-file";
    private static final boolean ENABLED;
    private static final String TRACE_PATH;

    static {
        String configured = System.getProperty(TRACE_PROPERTY);
        ENABLED = configured != null;
        TRACE_PATH = configured != null ? configured : "zstd-trace.log";
    }

    private TraceDump() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void dump(String tag, String line) {
        if (!ENABLED) {
            return;
        }
        try {
            Path path = Path.of(TRACE_PATH);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path,
                    "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + tag + ": " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // tracing must never crash the game
        }
    }
}
