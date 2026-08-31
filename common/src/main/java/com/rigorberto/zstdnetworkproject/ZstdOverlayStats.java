package com.rigorberto.zstdnetworkproject;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Client-side snapshot of compression statistics for the F3+3 bandwidth overlay.
 *
 * <p>All values are read from the static {@link LongAdder} counters maintained by
 * {@link ZstdEncoder} and {@link ZstdDecoder}, so the overlay adds no per-packet cost of its own.
 * This class is intentionally free of Minecraft imports so it can live in the shared module and be
 * consumed by every client-side loader (Fabric, NeoForge).
 */
public final class ZstdOverlayStats {

    private static volatile boolean enabled;
    private static volatile boolean zstdObserved;
    private static volatile int clientCompressionLevel = 3;
    private static volatile int serverCompressionLevel = -1;

    private ZstdOverlayStats() {
    }

    /** Whether the user turned the F3+3 zstd lines on in config.yml (default off). */
    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void setClientCompressionLevel(int level) {
        clientCompressionLevel = level;
    }

    public static int getClientCompressionLevel() {
        return clientCompressionLevel;
    }

    public static void setServerCompressionLevel(int level) {
        serverCompressionLevel = level;
    }

    public static int getServerCompressionLevel() {
        return serverCompressionLevel;
    }

    /**
     * True once at least one zstd frame has been observed on the current connection, which is the
     * same signal encoders use to start compressing ({@link ZstdCapability}).
     */
    public static boolean isZstdActive() {
        return zstdObserved;
    }

    /** Called by {@link ZstdCapability#markZstdObserved} when a zstd frame is seen. */
    public static void noteZstdObserved() {
        zstdObserved = true;
    }

    /**
     * Baselines captured at {@link #resetConnection()}. The underlying counters are process-wide
     * {@link LongAdder}s that never go backwards (a server has many connections sharing them), so
     * per-connection figures are produced by subtracting the value they held when this connection
     * started rather than by zeroing them.
     */
    private static volatile long basePackets;
    private static volatile long baseInputBytes;
    private static volatile long baseOutputBytes;
    private static volatile long baseZstdPackets;
    private static volatile long baseZlibPackets;
    private static volatile long baseRawPackets;

    /** Called when a new login starts, so a server without zstd never shows stale state. */
    public static void resetConnection() {
        zstdObserved = false;
        serverCompressionLevel = -1;
        basePackets = totalSentPackets();
        baseInputBytes = totalSentUncompressedBytes();
        baseOutputBytes = totalSentCompressedBytes();
        baseZstdPackets = ZstdDecoder.ZSTD_PACKETS.sum();
        baseZlibPackets = ZstdDecoder.ZLIB_PACKETS.sum();
        baseRawPackets = ZstdDecoder.RAW_PACKETS.sum();
    }

    // Both encoders are summed everywhere: proxies install ZstdFrameEncoder and never ZstdEncoder,
    // so reading only the latter reports zero traffic on Velocity.
    private static long totalSentPackets() {
        return ZstdEncoder.PACKETS_COMPRESSED.sum() + ZstdFrameEncoder.PACKETS_COMPRESSED.sum();
    }

    private static long totalSentUncompressedBytes() {
        return ZstdEncoder.INPUT_BYTES.sum() + ZstdFrameEncoder.INPUT_BYTES.sum();
    }

    private static long totalSentCompressedBytes() {
        return ZstdEncoder.OUTPUT_BYTES.sum() + ZstdFrameEncoder.OUTPUT_BYTES.sum();
    }

    public static long sentPackets() {
        return totalSentPackets() - basePackets;
    }

    public static long sentUncompressedBytes() {
        return totalSentUncompressedBytes() - baseInputBytes;
    }

    public static long sentCompressedBytes() {
        return totalSentCompressedBytes() - baseOutputBytes;
    }

    public static long receivedZstdPackets() {
        return ZstdDecoder.ZSTD_PACKETS.sum() - baseZstdPackets;
    }

    public static long receivedZlibPackets() {
        return ZstdDecoder.ZLIB_PACKETS.sum() - baseZlibPackets;
    }

    public static long receivedRawPackets() {
        return ZstdDecoder.RAW_PACKETS.sum() - baseRawPackets;
    }

    public static long receivedTotalPackets() {
        return receivedZstdPackets() + receivedZlibPackets() + receivedRawPackets();
    }

    /**
     * Average compression ratio achieved on sent packets (uncompressed / compressed), or 0 when
     * nothing has been compressed yet.
     */
    public static double compressionRatio() {
        long out = sentCompressedBytes();
        if (out <= 0) {
            return 0.0;
        }
        return (double) sentUncompressedBytes() / out;
    }

    /** Percentage of bytes saved on sent packets, 0..100 (0 when nothing compressed yet). */
    public static double savingsPercent() {
        long in = sentUncompressedBytes();
        if (in <= 0) {
            return 0.0;
        }
        return 100.0 * (in - sentCompressedBytes()) / in;
    }

    /** Human-readable byte count using binary units, e.g. {@code 1.5 MiB}. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : new String[] {"KiB", "MiB", "GiB"}) {
            value /= 1024.0;
            if (value < 1024.0) {
                return String.format(Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return String.format(Locale.ROOT, "%.1f TiB", value / 1024.0);
    }

    /**
     * The text lines drawn by the F3+3 overlay, styled after the vanilla debug charts' min/avg/max
     * labels. One line while zstd has not been observed yet, three once it is active.
     */
    public static String[] overlayLines() {
        if (!zstdObserved) {
            return new String[] {"Zstd: inactive (vanilla zlib)"};
        }
        double ratio = compressionRatio();
        String ratioText = ratio > 0 ? String.format(Locale.ROOT, "%.2fx", ratio) : "n/a";
        String serverLevelStr = serverCompressionLevel >= 0 ? "lvl " + serverCompressionLevel : "active";
        return new String[] {
                String.format(Locale.ROOT, "Zstd: active (Client: lvl %d | Server: %s), ratio %s (%.0f%% saved)",
                        clientCompressionLevel, serverLevelStr, ratioText, savingsPercent()),
                "Sent: " + sentPackets() + " pkts, "
                        + formatBytes(sentUncompressedBytes()) + " -> " + formatBytes(sentCompressedBytes()),
                "Recv: " + receivedTotalPackets() + " pkts (zstd " + receivedZstdPackets()
                        + ", zlib " + receivedZlibPackets() + ", raw " + receivedRawPackets() + ")"
        };
    }
}
