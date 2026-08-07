package com.rigorberto.zstdnetworkproject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class StatsLogger {

    private static final long INTERVAL_MS = 60_000L;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile Thread thread;

    private StatsLogger() {
    }

    public static synchronized void start(Path logFile, int compressionLevel) {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(() -> run(logFile, compressionLevel), "zstd-stats-logger");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(Path logFile, int compressionLevel) {
        long encCompressed = 0, encIn = 0, encOut = 0;
        long zstd = 0, zstdBytes = 0, zlib = 0, raw = 0;

        while (true) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }

            try {
                long curEncCompressed = ZstdEncoder.PACKETS_COMPRESSED.sum();
                long curEncIn = ZstdEncoder.INPUT_BYTES.sum();
                long curEncOut = ZstdEncoder.OUTPUT_BYTES.sum();
                long curZstd = ZstdDecoder.ZSTD_PACKETS.sum();
                long curZstdBytes = ZstdDecoder.ZSTD_BYTES.sum();
                long curZlib = ZstdDecoder.ZLIB_PACKETS.sum();
                long curRaw = ZstdDecoder.RAW_PACKETS.sum();

                String line = String.format(
                        "%s encode: +%d pkts (+%s -> +%s) | decode zstd: +%d pkts (+%s), zlib fallback: +%d, raw: +%d | level %d | zstd active: %s",
                        LocalDateTime.now().format(TIMESTAMP),
                        curEncCompressed - encCompressed,
                        formatBytes(curEncIn - encIn),
                        formatBytes(curEncOut - encOut),
                        curZstd - zstd,
                        formatBytes(curZstdBytes - zstdBytes),
                        curZlib - zlib,
                        curRaw - raw,
                        compressionLevel,
                        curZstd > 0 ? "YES" : "NO");

                encCompressed = curEncCompressed;
                encIn = curEncIn;
                encOut = curEncOut;
                zstd = curZstd;
                zstdBytes = curZstdBytes;
                zlib = curZlib;
                raw = curRaw;

                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                // logging must never crash the game
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
