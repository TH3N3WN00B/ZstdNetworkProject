package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;

/**
 * Detects whether the bundled zstd-jni native library can be loaded on the current platform and
 * JVM. The zstd-jni jar ships natives for a fixed set of OS/arch combinations; on any other
 * combination loading the library fails and every zstd call throws an error.
 *
 * <p>Callers use {@link #isAvailable()} to fail soft: the proxy keeps vanilla zlib compression and
 * clients answer the capability probe with no (or no response at all), so neither side ever crashes
 * just because the native library is missing.
 */
public final class ZstdNative {

    /** Non-null when the native library loaded; null otherwise. */
    private static final String STATUS = probe();

    private ZstdNative() {
    }

    private static String probe() {
        try {
            int maxLevel = Zstd.maxCompressionLevel();
            if (maxLevel > 0) {
                return "zstd native (max level " + maxLevel + ")";
            }
            return "zstd native (unknown version)";
        } catch (Throwable t) {
            // native library not bundled, not extractable, or unsupported on this platform
            return null;
        }
    }

    public static boolean isAvailable() {
        return STATUS != null;
    }

    /** Human-readable native status, or null when the native library is unavailable. */
    public static String status() {
        return STATUS;
    }
}
