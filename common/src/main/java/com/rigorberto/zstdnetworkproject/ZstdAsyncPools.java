package com.rigorberto.zstdnetworkproject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared daemon worker pool used to move large-packet (de)compression off the Netty event loops.
 * The pool is shared across all channels in the JVM and sized to a fraction of the available
 * cores so the game/proxy threads are not starved.
 *
 * <p>Both the pool size and the async threshold can be tuned for the runtime environment without a
 * rebuild, which is especially useful inside containers where {@code availableProcessors()} may
 * report the whole host instead of the container's CPU quota:
 * <ul>
 *   <li>pool size: {@code -Dzstdnetworkproject.workers=N} or env {@code ZSTDNETWORKPROJECT_WORKERS}</li>
 *   <li>threshold: {@code -Dzstdnetworkproject.async-threshold=N} or env
 *       {@code ZSTDNETWORKPROJECT_ASYNC_THRESHOLD}</li>
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
    private static final ExecutorService EXECUTOR;

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int defaultWorkers = Math.max(2, Math.min(8, cores / 2));
        WORKER_COUNT = intValue("zstdnetworkproject.workers", "ZSTDNETWORKPROJECT_WORKERS", defaultWorkers, 1, 64);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
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
        EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT, factory);
    }

    private ZstdAsyncPools() {
    }

    public static int workerCount() {
        return WORKER_COUNT;
    }

    static ExecutorService executor() {
        return EXECUTOR;
    }

    private static int intValue(String sysProp, String env, int fallback, int min) {
        return Math.max(min, intValue(sysProp, env, fallback));
    }

    private static int intValue(String sysProp, String env, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, intValue(sysProp, env, fallback)));
    }

    private static int intValue(String sysProp, String env, int fallback) {
        String raw = System.getProperty(sysProp);
        if (raw == null) {
            raw = System.getenv(env);
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
