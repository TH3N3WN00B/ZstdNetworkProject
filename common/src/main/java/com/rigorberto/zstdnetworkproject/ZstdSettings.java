package com.rigorberto.zstdnetworkproject;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class ZstdSettings {

    /**
     * Packets at least this large (uncompressed bytes) may be compressed with zstd's multithreaded
     * mode, which splits the input into jobs processed on multiple CPU cores. Smaller packets are
     * not worth the threading overhead.
     */
    public static final int HARDWARE_ACCEL_MIN_SIZE = 512 * 1024;

    /**
     * Optional mod ids whose presence makes the mod stay completely passive.
     * Empty by default so Krypton / FNP Patcher can use Zstd without being auto-disabled.
     */
    public static final List<String> DEFAULT_AUTO_DISABLE_MODS = List.of();

    /**
     * Packets smaller than this (uncompressed bytes) are never compressed. Floored at 256 because
     * below that the frame header plus zstd's own frame overhead routinely costs more than the
     * compression saves. (The decoder does not depend on this value: it picks zstd or zlib by
     * sniffing the frame magic, so it decodes any threshold a peer chooses.)
     */
    public static final int MIN_COMPRESSION_THRESHOLD = 256;

    /** Auto-detected hardware-acceleration worker count, computed once (half the cores, capped). */
    private static final int AUTO_WORKERS =
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private int compressionLevel = 3;
    private boolean fast;
    private int fastLevel = 1;
    private boolean debugMessage = true;
    private boolean hardwareAcceleration = true;
    private int hardwareAccelerationThreads;
    private int compressionThreshold = MIN_COMPRESSION_THRESHOLD;
    private boolean compressIfBeneficial = true;
    private boolean debugOverlay;
    private List<String> disabledServers = List.of();
    private List<String> autoDisableMods = DEFAULT_AUTO_DISABLE_MODS;
    private boolean hexDump;

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public void setCompressionLevel(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    public boolean isFast() {
        return fast;
    }

    public void setFast(boolean fast) {
        this.fast = fast;
    }

    public int getFastLevel() {
        return fastLevel;
    }

    public void setFastLevel(int fastLevel) {
        this.fastLevel = fastLevel;
    }

    public int effectiveCompressionLevel() {
        return fast ? -fastLevel : compressionLevel;
    }

    public boolean isDebugMessage() {
        return debugMessage;
    }

    public void setDebugMessage(boolean debugMessage) {
        this.debugMessage = debugMessage;
    }

    public boolean isHardwareAcceleration() {
        return hardwareAcceleration;
    }

    public void setHardwareAcceleration(boolean hardwareAcceleration) {
        this.hardwareAcceleration = hardwareAcceleration;
    }

    public int getHardwareAccelerationThreads() {
        return hardwareAccelerationThreads;
    }

    public void setHardwareAccelerationThreads(int hardwareAccelerationThreads) {
        this.hardwareAccelerationThreads = hardwareAccelerationThreads;
    }

    public int getCompressionThreshold() {
        return compressionThreshold;
    }

    public void setCompressionThreshold(int compressionThreshold) {
        this.compressionThreshold = Math.max(MIN_COMPRESSION_THRESHOLD, compressionThreshold);
    }

    public boolean isCompressIfBeneficial() {
        return compressIfBeneficial;
    }

    public void setCompressIfBeneficial(boolean compressIfBeneficial) {
        this.compressIfBeneficial = compressIfBeneficial;
    }

    /** Whether the client draws zstd statistics in the F3+3 bandwidth view (default off). */
    public boolean isDebugOverlay() {
        return debugOverlay;
    }

    public void setDebugOverlay(boolean debugOverlay) {
        this.debugOverlay = debugOverlay;
    }

    /** Server address substrings (host or host:port) where the mod stays completely passive. */
    public List<String> getDisabledServers() {
        return disabledServers;
    }

    public void setDisabledServers(List<String> disabledServers) {
        this.disabledServers = disabledServers == null ? List.of() : List.copyOf(disabledServers);
    }

    /**
     * True when the given remote address (best-effort {@code host:port} string) matches one of the
     * configured disabled-server entries, meaning this connection must not be touched at all:
     * servers with custom protocol patchers break when their compression handlers are replaced.
     */
    public boolean isServerDisabled(String remoteAddress) {
        if (remoteAddress == null || disabledServers.isEmpty()) {
            return false;
        }
        String address = remoteAddress.toLowerCase(Locale.ROOT);
        for (String entry : disabledServers) {
            if (!entry.isEmpty() && address.contains(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Mod ids whose presence makes the mod stay completely passive (see {@link #DEFAULT_AUTO_DISABLE_MODS}). */
    public List<String> getAutoDisableMods() {
        return autoDisableMods;
    }

    public void setAutoDisableMods(List<String> autoDisableMods) {
        this.autoDisableMods = autoDisableMods == null ? List.of() : List.copyOf(autoDisableMods);
    }

    /**
     * Returns the first configured mod id that is currently loaded, checked through the supplied
     * platform predicate ({@code FabricLoader::isModLoaded} or {@code ModList::isLoaded}), or
     * {@code null} when none of them is installed.
     */
    public String findLoadedAutoDisableMod(Predicate<String> isModLoaded) {
        for (String modId : autoDisableMods) {
            if (!modId.isEmpty() && isModLoaded.test(modId)) {
                return modId;
            }
        }
        return null;
    }

    /**
     * Whether every frame crossing the encoder/decoder is hex-dumped to
     * {@code zstd-hexdump.log} for protocol debugging. Off by default.
     */
    public boolean isHexDump() {
        return hexDump;
    }

    public void setHexDump(boolean hexDump) {
        this.hexDump = hexDump;
    }

    /**
     * Number of CPU worker threads zstd may spawn for a single compression job. Returns 0 (no
     * multithreading) when hardware acceleration is disabled or the packet is too small, the
     * configured thread count when one is set, or an auto-detected value based on the available
     * processors otherwise.
     */
    public int effectiveWorkers(int uncompressedSize) {
        if (!hardwareAcceleration || uncompressedSize < HARDWARE_ACCEL_MIN_SIZE) {
            return 0;
        }
        if (hardwareAccelerationThreads > 0) {
            return hardwareAccelerationThreads;
        }
        return AUTO_WORKERS;
    }
}
