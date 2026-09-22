package com.rigorberto.zstdnetworkproject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared daemon worker pool used to move large-packet (de)compression off the Netty event loops.
 * The pool is shared across all channels in the JVM.
 *
 * <p>On Java 21+ the default is to use virtual threads ({@code Executors.newVirtualThreadPerTaskExecutor()}):
 * a zstd JNI call blocks the current thread while the native library does the heavy lifting, and
 * virtual threads do that without pinning a platform (carrier) thread, so the pool is effectively
 * unbounded and the game/proxy threads are never starved. The opt-in {@code use-virtual-threads}
 * config (or a pre-existing {@code -Dzstdnetworkproject.workers} system property on Java &lt; 21)
 * falls back to a fixed pool of platform threads sized to a fraction of the available cores.
 *
 * <p>Both the pool size and the async threshold can be tuned for the runtime environment without a
 * rebuild, which is especially useful inside containers where {@code availableProcessors()} may
 * report the whole host instead of the container's CPU quota:
 * <ul>
 *   <li>pool size: {@code -Dzstdnetworkproject.workers=N} or env {@code ZSTDNETWORKPROJECT_WORKERS}
 *       (only used on the fixed-pool fallback)</li>
 *   <li>threshold: {@code -Dzstdnetworkproject.async-threshold=N} or env
 *       {@code ZSTDNETWORKPROJECT_ASYNC_THRESHOLD}</li>
 *   <li>per-channel queue limit: {@code -Dzstdnetworkproject.max-queued-bytes=N} or env
 *       {@code ZSTDNETWORKPROJECT_MAX_QUEUED_BYTES} (see {@link OrderedAsyncProcessor})</li>
 * </ul>
 */
public final class ZstdAsyncPools {

    /**
     * Packets at least this large (uncompressed bytes) are processed on the worker pool. Can be
     * overridden via {@code zstdnetworkproject.async-threshold} or
     * {@code ZSTDNETWORKPROJECT_ASYNC_THRESHOLD}.
     */
    public static final int ASYNC_THRESHOLD = intValue(
            "zstdnetworkproject.async-threshold", "ZSTDNETWORKPROJECT_ASYNC_THRESHOLD", 64 * 1024, 256);

    private static final int WORKER_COUNT;
    private static final ThreadFactory PLATFORM_FACTORY;

    /**
     * Whether the worker pool uses virtual threads (Java 21+) or a fixed platform-thread pool.
     * Defaults to virtual threads when the runtime supports them; {@code ConfigLoader} flips it
     * from the {@code use-virtual-threads} setting once the config is parsed.
     */
    private static volatile boolean virtualThreads = Runtime.version().feature() >= 21;

    /** Created on first use, so the config-driven toggle has already been applied by then. */
    private static volatile ExecutorService executor;

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int defaultWorkers = Math.max(2, Math.min(8, cores / 2));
        WORKER_COUNT = intValue("zstdnetworkproject.workers", "ZSTDNETWORKPROJECT_WORKERS", defaultWorkers, 1, 64);
        AtomicInteger counter = new AtomicInteger();
        PLATFORM_FACTORY = runnable -> {
            Thread thread = new Thread(runnable, "zstd-codec-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            // Below-normal priority: Windows actually honors Java thread priorities (they map to
            // Win32 priority classes, unlike Linux/macOS which mostly ignore them), so this keeps
            // large-packet (de)compression from stealing CPU from the game/render threads on the
            // typical 4-8 core desktop. Compression latency impact is negligible next to the
            // smoothness gain.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    private ZstdAsyncPools() {
    }

    /** Overrides the pool strategy from the parsed {@code use-virtual-threads} config setting. */
    static void setUseVirtualThreads(boolean enabled) {
        virtualThreads = enabled;
    }

    public static int workerCount() {
        return WORKER_COUNT;
    }

    static ExecutorService executor() {
        ExecutorService ex = executor;
        if (ex == null) {
            synchronized (ZstdAsyncPools.class) {
                ex = executor;
                if (ex == null) {
                    ex = createExecutor();
                    executor = ex;
                }
            }
        }
        return ex;
    }

    private static ExecutorService createExecutor() {
        if (virtualThreads && Runtime.version().feature() >= 21) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        return Executors.newFixedThreadPool(WORKER_COUNT, PLATFORM_FACTORY);
    }

    private static int intValue(String sysProp, String env, int fallback, int min) {
        return Math.max(min, intValue(sysProp, env, fallback));
    }

    private static int intValue(String sysProp, String env, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, intValue(sysProp, env, fallback)));
    }

    private static int intValue(String sysProp, String env, int fallback) {
        String raw = rawValue(sysProp, env);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Same lookup as {@link #intValue}, for settings whose sensible range exceeds an int. */
    static long longValue(String sysProp, String env, long fallback, long min) {
        String raw = rawValue(sysProp, env);
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(min, Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Small direct buffer pool to reduce allocations for common packet sizes.
     * Reused safely via atomic swap and null-check guards.
     */
    private static final java.util.concurrent.atomic.AtomicReference<byte[]> SMALL_BUFFER_POOL =
            new java.util.concurrent.atomic.AtomicReference<>(new byte[64 * 1024]);

    /**
     * Returns a small reusable buffer (<= 64KB) or allocates a new one if needed.
     * The returned buffer is zeroed and ready to write.
     */
    static byte[] acquireSmallBuffer(int maxCapacity) {
        // Defensive clamp: a non-positive request is treated as an empty buffer request and can
        // never reach the pooled path with a negative capacity.
        if (maxCapacity < 0) {
            maxCapacity = 0;
        }
        if (maxCapacity > 65536) {
            return new byte[maxCapacity];
        }

        byte[] pool = SMALL_BUFFER_POOL.get();
        if (pool == null || pool.length < maxCapacity) {
            byte[] newPool = new byte[Math.min(256 * 1024, maxCapacity)];
            if (!SMALL_BUFFER_POOL.compareAndSet(pool, newPool)) {
                return SMALL_BUFFER_POOL.get();
            }
            return newPool;
        }

        byte[] reused = pool;
        java.util.Arrays.fill(reused, 0, reused.length, (byte) 0);
        return reused;
    }

    /**
     * Releases a small buffer back to the pool (if within capacity).
     * If the buffer is too large, it's leaked intentionally (will be GC'd).
     */
    static void releaseSmallBuffer(byte[] buffer) {
        if (buffer == null || buffer.length > 65536) {
            return;
        }

        byte[] currentPool = SMALL_BUFFER_POOL.get();
        if (currentPool != null && currentPool.length >= buffer.length) {
            if (SMALL_BUFFER_POOL.compareAndSet(currentPool, buffer)) {
                return;
            }
        }
    }

    private static String rawValue(String sysProp, String env) {
        String raw = System.getProperty(sysProp);
        if (raw == null) {
            raw = System.getenv(env);
        }
        return raw == null ? null : raw.trim();
    }
}
