package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

/**
 * Reusable zstd compression/decompression contexts and scratch buffers, kept per thread so they
 * are never shared concurrently. Context creation is by far the most expensive part of a zstd
 * round trip on small packets, so reusing them across calls (event-loop threads and worker-pool
 * threads each get their own) is the biggest single win available in Java.
 */
final class ZstdCodecCtx {

    private static final ThreadLocal<ZstdCompressCtx> COMPRESS =
            ThreadLocal.withInitial(ZstdCompressCtx::new);
    private static final ThreadLocal<ZstdDecompressCtx> DECOMPRESS =
            ThreadLocal.withInitial(ZstdDecompressCtx::new);
    private static final ThreadLocal<byte[]> SCRATCH =
            ThreadLocal.withInitial(() -> new byte[16 * 1024]);

    private ZstdCodecCtx() {
    }

    static ZstdCompressCtx compress(int level, int workers) {
        ZstdCompressCtx ctx = COMPRESS.get();
        ctx.setLevel(level);
        try {
            ctx.setWorkers(workers);
        } catch (Throwable t) {
            // zstd-jni built without multithreading support: fall back to single-threaded.
            ctx.setWorkers(0);
        }
        return ctx;
    }

    static ZstdDecompressCtx decompress() {
        return DECOMPRESS.get();
    }

    static int compressBound(int size) {
        return (int) Zstd.compressBound(size);
    }

    static byte[] scratch(int needed) {
        byte[] buf = SCRATCH.get();
        if (buf.length < needed) {
            buf = new byte[needed];
            SCRATCH.set(buf);
        }
        return buf;
    }
}
