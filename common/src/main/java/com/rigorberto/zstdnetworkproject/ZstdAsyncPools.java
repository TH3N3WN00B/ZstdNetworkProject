package com.rigorberto.zstdnetworkproject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared daemon worker pool used to move large-packet (de)compression off the Netty event loops.
 * The pool is shared across all channels in the JVM and sized to a fraction of the available
 * cores so the game/proxy threads are not starved.
 */
final class ZstdAsyncPools {

    /** Packets at least this large (uncompressed bytes) are processed on the worker pool. */
    static final int ASYNC_THRESHOLD = 64 * 1024;

    private static final ExecutorService EXECUTOR;

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(2, Math.min(8, cores / 2));
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "zstd-codec-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        EXECUTOR = Executors.newFixedThreadPool(threads, factory);
    }

    private ZstdAsyncPools() {
    }

    static ExecutorService executor() {
        return EXECUTOR;
    }
}
