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
 * can be compared on both ends. The path comes from {@code zstdnetworkproject.trace-file} and
 * defaults to {@code zstd-trace.log} in the working directory.
 */
public final class TraceDump {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TraceDump() {
    }

    public static void dump(String tag, String line) {
        try {
            Path path = Path.of(System.getProperty("zstdnetworkproject.trace-file", "zstd-trace.log"));
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
