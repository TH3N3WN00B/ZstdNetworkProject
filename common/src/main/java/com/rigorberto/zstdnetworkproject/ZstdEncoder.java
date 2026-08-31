package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.Deflater;

/**
 * Minecraft compression encoder: writes VarInt(uncompressedSize) followed by the zstd-compressed
 * payload, or VarInt(0) followed by the raw payload for packets under the compression threshold.
 *
 * <p>Small packets are compressed inline on the event loop using a reused per-thread context.
 * Packets at least {@link ZstdAsyncPools#ASYNC_THRESHOLD} bytes are compressed on a shared worker
 * pool, with a per-channel FIFO preserving packet order.
 *
 * <p>When the input {@link ByteBuf} is direct, compression runs straight from the buffer's memory
 * into a pooled direct buffer on whichever thread does the work, avoiding intermediate
 * {@code byte[]} copies. When {@link ZstdSettings#isCompressIfBeneficial()} is enabled, a packet
 * whose compressed form would not actually be smaller is sent uncompressed instead.
 *
 * <p>While the worker pool is idle the packet is encoded directly in {@link #write} without
 * allocating a work object at all; the FIFO only kicks in once an asynchronous packet is in flight,
 * so the common case is zero-allocation.
 */
public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

    private final int compressionLevel;
    private final ZstdSettings settings;
    private final boolean peerZstdRequired;
    private final OrderedAsyncProcessor processor = new OrderedAsyncProcessor(OrderedAsyncProcessor.Direction.OUTBOUND);
    private boolean closeCleanupAttached;

    public ZstdEncoder() {
        this(new ZstdSettings());
    }

    public ZstdEncoder(int compressionLevel) {
        this.settings = new ZstdSettings();
        this.compressionLevel = compressionLevel;
        this.peerZstdRequired = false;
    }

    public ZstdEncoder(ZstdSettings settings) {
        this(settings, false);
    }

    /**
     * @param peerZstdRequired when true (clients), packets above the compression threshold are
     *                         zstd-compressed only once the remote end has been observed sending
     *                         zstd; before that they are sent as vanilla-compatible zlib so a
     *                         client with the mod never breaks a vanilla server
     */
    public ZstdEncoder(ZstdSettings settings, boolean peerZstdRequired) {
        this.settings = settings;
        this.compressionLevel = settings.effectiveCompressionLevel();
        this.peerZstdRequired = peerZstdRequired;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }
        ensureCloseCleanup(ctx);
        ByteBuf in = (ByteBuf) msg;
        int readable = in.readableBytes();
        INPUT_BYTES.add(readable);
        if (HexDump.isEnabled()) {
            HexDump.dump("frame-out", "OUT packet threshold=" + settings.getCompressionThreshold()
                    + " peer=" + HexDump.peerOf(ctx), in);
        }

        if (readable < settings.getCompressionThreshold()) {
            if (processor.isIdle()) {
                writeRaw(ctx, in, readable, promise);
            } else {
                processor.add(ctx, new RawWork(ctx, in, readable, promise));
            }
            return;
        }

        if (peerZstdRequired && !ZstdCapability.remoteSpeaksZstd(ctx.channel())) {
            if (processor.isIdle()) {
                writeZlib(ctx, in, readable, promise);
            } else {
                processor.add(ctx, new ZlibWork(ctx, in, readable, promise));
            }
            return;
        }

        if (processor.isIdle() && readable < ZstdAsyncPools.ASYNC_THRESHOLD) {
            compressSync(ctx, in, readable, compressionLevel, settings.effectiveWorkers(readable),
                    settings, promise);
        } else {
            processor.add(ctx, new CompressWork(ctx, processor, in, readable, compressionLevel,
                    settings.effectiveWorkers(readable), settings, promise));
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        throw new UnsupportedOperationException("encode is not used; write() is overridden");
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        try {
            super.handlerRemoved(ctx);
        } finally {
            processor.discardAll();
        }
    }

    private void ensureCloseCleanup(ChannelHandlerContext ctx) {
        if (closeCleanupAttached) {
            return;
        }
        closeCleanupAttached = true;
        ctx.channel().closeFuture().addListener(future -> processor.discardAll());
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static void writeVarIntAt(ByteBuf buf, int index, int value) {
        while ((value & ~0x7F) != 0) {
            buf.setByte(index++, (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.setByte(index, value);
    }

    public static int varIntLength(int value) {
        if ((value & (0xFFFFFFFF << 7)) == 0) {
            return 1;
        }
        if ((value & (0xFFFFFFFF << 14)) == 0) {
            return 2;
        }
        if ((value & (0xFFFFFFFF << 21)) == 0) {
            return 3;
        }
        if ((value & (0xFFFFFFFF << 28)) == 0) {
            return 4;
        }
        return 5;
    }

    /**
     * Whether the compressed frame (varint + compressed payload) is smaller than sending the
     * packet raw (one zero varint byte + the payload).
     */
    private static boolean beneficial(int readable, int compressedSize) {
        return varIntLength(readable) + compressedSize < 1 + readable;
    }

    /** Encodes a sub-threshold packet as {@code varint(0) + raw}. Releases {@code msg}. */
    private static void writeRaw(ChannelHandlerContext ctx, ByteBuf msg, int readable, ChannelPromise promise) {
        ByteBuf out = ctx.alloc().directBuffer(1 + readable);
        writeVarInt(out, 0);
        out.writeBytes(msg);
        OUTPUT_BYTES.add(out.readableBytes());
        msg.release();
        ctx.write(out, promise);
    }

    /** Vanilla-compatible zlib fallback: {@code varint(uncompressedSize) + zlib stream}. Releases {@code in}. */
    private static void writeZlib(ChannelHandlerContext ctx, ByteBuf in, int readable, ChannelPromise promise) {
        ByteBuf out = null;
        try {
            Deflater deflater = ZstdCodecCtx.deflater();
            deflater.reset();
            if (in.hasArray()) {
                deflater.setInput(in.array(), in.arrayOffset() + in.readerIndex(), readable);
            } else {
                byte[] input = new byte[readable];
                in.getBytes(in.readerIndex(), input);
                deflater.setInput(input);
            }
            deflater.finish();
            int varIntLen = varIntLength(readable);
            out = ctx.alloc().directBuffer(varIntLen + ZstdCodecCtx.deflateBound(readable));
            writeVarInt(out, readable);
            // deflate() fills at most dst.length bytes per call and gives no direct signal that it
            // had more to write, so the loop must run until finished(): taking a single call's
            // result would silently truncate the stream (and produce a frame whose size prefix no
            // longer matches its payload) whenever deflateBound under-estimates.
            byte[] dst = ZstdCodecCtx.scratch(8192);
            while (!deflater.finished()) {
                int size = deflater.deflate(dst);
                if (size <= 0) {
                    if (deflater.finished()) {
                        break;
                    }
                    throw new IllegalStateException("zlib compression stalled: deflate() produced "
                            + size + " bytes and is not finished (declaredSize=" + readable + ")");
                }
                out.writeBytes(dst, 0, size);
            }
            if (out.readableBytes() <= varIntLen) {
                throw new IllegalStateException("zlib compression produced an empty stream for "
                        + readable + " input bytes");
            }
            PACKETS_COMPRESSED.increment();
            OUTPUT_BYTES.add(out.readableBytes());
            ByteBuf frame = out;
            out = null;
            ctx.write(frame, promise);
        } catch (Throwable t) {
            if (out != null) {
                out.release();
            }
            promise.tryFailure(t);
        } finally {
            in.release();
        }
    }

    /** Encodes a zstd packet synchronously. Releases {@code in}. */
    private static void compressSync(ChannelHandlerContext ctx, ByteBuf in, int readable, int level,
                                     int workers, ZstdSettings settings, ChannelPromise promise) {
        try {
            ctx.write(compressDirectOrCopy(ctx, in, readable, level, workers, settings), promise);
        } catch (Throwable t) {
            promise.tryFailure(t);
        } finally {
            in.release();
        }
    }

    /**
     * Compresses the input synchronously into a pooled buffer holding the complete
     * {@code varint(uncompressedSize) + payload} frame (or {@code varint(0) + raw} when
     * {@code compress-if-beneficial} rejects the compressed form).
     */
    private static ByteBuf compressDirectOrCopy(ChannelHandlerContext ctx, ByteBuf in, int readable,
                                                int level, int workers, ZstdSettings settings) {
        ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
        int bound = ZstdCodecCtx.compressBound(readable);
        int varIntLength = varIntLength(readable);

        ByteBuf out = ctx.alloc().directBuffer(varIntLength + bound);
        // zstd-jni only accepts direct NIO buffers, so anything else (heap allocator, a composite
        // whose nioBuffer() would merge into a heap buffer) is staged through a direct copy first.
        ByteBuf staged = ZstdCodecCtx.isNativeReadable(in) ? null : ctx.alloc().directBuffer(readable);
        try {
            ByteBuffer dst = out.nioBuffer(varIntLength, bound);
            ByteBuffer src;
            if (staged == null) {
                src = in.nioBuffer();
            } else {
                staged.writeBytes(in, in.readerIndex(), readable);
                src = staged.nioBuffer();
            }

            int size = zctx.compress(dst, src);
            if (size < 0) {
                throw new IllegalStateException("zstd compression failed: " + size);
            }

            out.writerIndex(varIntLength + size);
            writeVarIntAt(out, 0, readable);
            if (settings.isCompressIfBeneficial() && !beneficial(readable, size)) {
                out.clear();
                writeVarInt(out, 0);
                out.writeBytes(in, in.readerIndex(), readable);
            } else {
                PACKETS_COMPRESSED.increment();
            }
            OUTPUT_BYTES.add(out.readableBytes());
            ByteBuf frame = out;
            out = null;
            return frame;
        } finally {
            if (staged != null) {
                staged.release();
            }
            if (out != null) {
                out.release();
            }
        }
    }

    private static final class RawWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final ByteBuf msg;
        private final int readable;
        private final ChannelPromise promise;

        RawWork(ChannelHandlerContext ctx, ByteBuf msg, int readable, ChannelPromise promise) {
            this.ctx = ctx;
            this.msg = msg;
            this.readable = readable;
            this.promise = promise;
        }

        @Override
        public int queuedBytes() {
            return readable;
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            writeRaw(ctx, msg, readable, promise);
        }

        @Override
        public void submitAsync() {
        }

        @Override
        public void discard() {
            msg.release();
            promise.tryFailure(new ClosedChannelException());
        }
    }

    private static final class ZlibWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final ByteBuf in;
        private final int readable;
        private final ChannelPromise promise;

        ZlibWork(ChannelHandlerContext ctx, ByteBuf in, int readable, ChannelPromise promise) {
            this.ctx = ctx;
            this.in = in;
            this.readable = readable;
            this.promise = promise;
        }

        @Override
        public int queuedBytes() {
            return readable;
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            writeZlib(ctx, in, readable, promise);
        }

        @Override
        public void submitAsync() {
        }

        @Override
        public void discard() {
            in.release();
            promise.tryFailure(new ClosedChannelException());
        }
    }

    private static final class CompressWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final OrderedAsyncProcessor processor;
        private final ByteBuf in;
        private final int readable;
        private final int level;
        private final int workers;
        private final ZstdSettings settings;
        private final ChannelPromise promise;

        CompressWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, ByteBuf in,
                     int readable, int level, int workers, ZstdSettings settings, ChannelPromise promise) {
            this.ctx = ctx;
            this.processor = processor;
            this.in = in;
            this.readable = readable;
            this.level = level;
            this.workers = workers;
            this.settings = settings;
            this.promise = promise;
        }

        @Override
        public int queuedBytes() {
            return readable;
        }

        @Override
        public boolean isAsync() {
            return readable >= ZstdAsyncPools.ASYNC_THRESHOLD;
        }

        @Override
        public void processSync() {
            compressSync(ctx, in, readable, level, workers, settings, promise);
        }

        /**
         * Hands the input to the worker thread, which compresses straight out of its memory. After
         * {@code submitAsync} the event loop no longer touches {@code in} (the work owns it), so
         * the worker has exclusive access and {@link #complete} releases the single reference it
         * took ownership of.
         */
        @Override
        public void submitAsync() {
            ZstdAsyncPools.executor().execute(() -> {
                ByteBuf out;
                try {
                    out = compressOnWorker();
                } catch (Throwable t) {
                    in.release();
                    ctx.executor().execute(() -> {
                        promise.tryFailure(t);
                        processor.onAsyncComplete(ctx);
                    });
                    return;
                }
                ctx.executor().execute(() -> complete(out));
            });
        }

        private ByteBuf compressOnWorker() {
            return compressDirectOrCopy(ctx, in, readable, level, workers, settings);
        }

        private void complete(ByteBuf out) {
            try {
                if (!ctx.channel().isActive()) {
                    out.release();
                    promise.tryFailure(new ClosedChannelException());
                } else {
                    ctx.writeAndFlush(out, promise);
                }
            } finally {
                in.release();
                processor.onAsyncComplete(ctx);
            }
        }

        @Override
        public void discard() {
            in.release();
            promise.tryFailure(new ClosedChannelException());
        }
    }
}
