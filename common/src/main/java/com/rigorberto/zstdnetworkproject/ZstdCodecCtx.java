package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import java.util.zip.Deflater;

/**
 * Reusable zstd compression/decompression contexts and scratch buffers, kept per thread so they
 * are never shared concurrently. Context creation is by far the most expensive part of a zstd
 * round trip on small packets, so reusing them across calls (event-loop threads and worker-pool
 * threads each get their own) is the biggest single win available in Java.
 */
final class ZstdCodecCtx {

    private static final class CompressHolder {
        final ZstdCompressCtx ctx = new ZstdCompressCtx();
        int lastLevel = Integer.MIN_VALUE;
        int lastWorkers = Integer.MIN_VALUE;

        ZstdCompressCtx get(int level, int workers) {
            if (lastLevel != level) {
                ctx.setLevel(level);
                lastLevel = level;
            }
            if (lastWorkers != workers) {
                try {
                    ctx.setWorkers(workers);
                    lastWorkers = workers;
                } catch (Throwable t) {
                    ctx.setWorkers(0);
                    lastWorkers = 0;
                }
            }
            return ctx;
        }
    }

    private static final ThreadLocal<CompressHolder> COMPRESS =
            ThreadLocal.withInitial(CompressHolder::new);
    private static final ThreadLocal<ZstdDecompressCtx> DECOMPRESS =
            ThreadLocal.withInitial(ZstdDecompressCtx::new);
    private static final ThreadLocal<byte[]> SCRATCH =
            ThreadLocal.withInitial(() -> new byte[16 * 1024]);

    /**
     * Cached scratch buffers stop growing beyond this size: larger requests get a transient array
     * instead of permanently enlarging the thread-local. Without the cap a single huge frame (the
     * decoder accepts declared sizes up to 64 MiB) would pin an equally huge array on every thread
     * that touched it for the rest of its life, which is easy memory pressure in small containers.
     */
    private static final int MAX_CACHED_SCRATCH = 4 * 1024 * 1024;
    private static final ThreadLocal<Deflater> DEFLATER =
            ThreadLocal.withInitial(Deflater::new);

    private ZstdCodecCtx() {
    }

    static ZstdCompressCtx compress(int level, int workers) {
        return COMPRESS.get().get(level, workers);
    }

    static ZstdDecompressCtx decompress() {
        return DECOMPRESS.get();
    }

    static int compressBound(int size) {
        return (int) Zstd.compressBound(size);
    }

    static byte[] scratch(int needed) {
        byte[] buf = SCRATCH.get();
        if (buf.length >= needed) {
            return buf;
        }
        if (needed > MAX_CACHED_SCRATCH) {
            // Transient buffer: garbage-collected right after this frame instead of being
            // retained forever in the thread-local.
            return new byte[needed];
        }
        buf = new byte[needed];
        SCRATCH.set(buf);
        return buf;
    }

    /**
     * Reusable zlib deflater for the vanilla-compatible fallback encoder. Deliberately left at the
     * JDK default level: vanilla Minecraft's {@code CompressionEncoder} also uses a plain
     * {@code new Deflater()}, and the configured zstd level (1-22, or negative in fast mode) has no
     * meaningful translation into zlib's 0-9 scale.
     */
    static Deflater deflater() {
        return DEFLATER.get();
    }

    /**
     * Whether {@code buf}'s memory can be handed straight to zstd-jni. Its {@code ByteBuffer} entry
     * points reject non-direct buffers ({@code IllegalArgumentException: srcBuff must be a direct
     * buffer}), and {@link io.netty.buffer.ByteBuf#nioBuffer()} on a direct <em>composite</em> with
     * more than one component returns a merged <em>heap</em> buffer, so checking
     * {@link io.netty.buffer.ByteBuf#isDirect()} alone is not enough.
     */
    static boolean isNativeReadable(io.netty.buffer.ByteBuf buf) {
        return buf.isDirect() && buf.nioBufferCount() == 1;
    }

    /** Upper bound on the size of a zlib stream for {@code size} input bytes. */
    static int deflateBound(int size) {
        return size + (size >> 3) + (size >> 6) + 64;
    }
}
