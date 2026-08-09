package com.rigorberto.zstdnetworkproject;

public final class ZstdSettings {

    /**
     * Packets at least this large (uncompressed bytes) may be compressed with zstd's multithreaded
     * mode, which splits the input into jobs processed on multiple CPU cores. Smaller packets are
     * not worth the threading overhead.
     */
    public static final int HARDWARE_ACCEL_MIN_SIZE = 512 * 1024;

    /**
     * Packets smaller than this (uncompressed bytes) are never compressed. Must be at least 256:
     * the decoder decides zstd vs zlib by whether the declared uncompressed size is at least 256,
     * so anything this encoder compresses must be >= 256.
     */
    public static final int MIN_COMPRESSION_THRESHOLD = 256;

    private int compressionLevel = 3;
    private boolean fast;
    private int fastLevel = 1;
    private boolean debugMessage = true;
    private boolean hardwareAcceleration = true;
    private int hardwareAccelerationThreads;
    private int compressionThreshold = MIN_COMPRESSION_THRESHOLD;
    private boolean compressIfBeneficial = true;

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
        return autoWorkers();
    }

    private static int autoWorkers() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores / 2));
    }
}
