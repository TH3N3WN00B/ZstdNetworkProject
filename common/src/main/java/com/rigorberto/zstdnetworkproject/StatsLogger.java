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
                // Both encoders must be summed: proxies install ZstdFrameEncoder and never
                // ZstdEncoder, so reading only the latter reports zero traffic on Velocity.
                long curEncCompressed = ZstdOverlayStats.sentPackets();
                long curEncIn = ZstdOverlayStats.sentUncompressedBytes();
                long curEncOut = ZstdOverlayStats.sentCompressedBytes();
                long curZstd = ZstdDecoder.ZSTD_PACKETS.sum();
                long curZstdBytes = ZstdDecoder.ZSTD_BYTES.sum();
                long curZlib = ZstdDecoder.ZLIB_PACKETS.sum();
                long curRaw = ZstdDecoder.RAW_PACKETS.sum();

                long deltaEncComp = curEncCompressed - encCompressed;
                long deltaEncIn = curEncIn - encIn;
                long deltaEncOut = curEncOut - encOut;
                long deltaZstd = curZstd - zstd;
                long deltaZstdBytes = curZstdBytes - zstdBytes;
                long deltaZlib = curZlib - zlib;
                long deltaRaw = curRaw - raw;

                if (deltaEncComp == 0 && deltaEncIn == 0 && deltaZstd == 0 && deltaZlib == 0 && deltaRaw == 0) {
                    continue;
                }

                String line = String.format(
                        "%s encode: +%d pkts (+%s -> +%s) | decode zstd: +%d pkts (+%s), zlib fallback: +%d, raw: +%d | level %d | zstd active: %s",
                        LocalDateTime.now().format(TIMESTAMP),
                        deltaEncComp,
                        formatBytes(deltaEncIn),
                        formatBytes(deltaEncOut),
                        deltaZstd,
                        formatBytes(deltaZstdBytes),
                        deltaZlib,
                        deltaRaw,
                        compressionLevel,
                        curZstd > 0 ? "YES" : "NO");

                encCompressed = curEncCompressed;
                encIn = curEncIn;
                encOut = curEncOut;
                zstd = curZstd;
                zstdBytes = curZstdBytes;
                zlib = curZlib;
                raw = curRaw;

                Path parent = logFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
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
