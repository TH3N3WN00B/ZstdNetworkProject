package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Encoder that writes the Minecraft VarInt frame length, the uncompressed-size VarInt and then the
 * zstd-compressed payload (or {@code length+1, 0x00, raw} for packets under the threshold). This
 * replaces merged compression+length handlers (such as Velocity's
 * {@code MinecraftCompressorAndLengthEncoder}) on proxies, where the client's frame decoder has
 * already been removed from the outbound path.
 *
 * <p>Small packets are processed inline on the event loop with a reused per-thread context; packets
 * at least {@link ZstdAsyncPools#ASYNC_THRESHOLD} bytes are compressed on a shared worker pool,
 * with a per-channel FIFO preserving packet order. While the pool is idle a packet is encoded
 * directly in {@link #write} without allocating a work object.
 *
 * <p>When the input {@link ByteBuf} is direct, the payload is compressed straight into the final
 * frame buffer (a fixed five-byte slot is reserved at the front and the frame-length VarInt is
 * written at the computed offset afterwards), so there is no intermediate payload buffer and no
 * copy. When {@link ZstdSettings#isCompressIfBeneficial()} is enabled, a packet whose compressed
 * form would not actually be smaller is sent uncompressed instead.
 */
public class ZstdFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

    /** Maximum length of a VarInt frame-length prefix; the payload is always written after this. */
    private static final int FRAME_LENGTH_SLOT = 5;

    private final int compressionLevel;
    private final ZstdSettings settings;
    private final OrderedAsyncProcessor processor = new OrderedAsyncProcessor();
    private boolean closeCleanupAttached;

    public ZstdFrameEncoder(int compressionLevel) {
        this.settings = new ZstdSettings();
        this.compressionLevel = compressionLevel;
    }

    public ZstdFrameEncoder(ZstdSettings settings) {
        this.settings = settings;
        this.compressionLevel = settings.effectiveCompressionLevel();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }
        ensureCloseCleanup(ctx);
        ByteBuf in = (ByteBuf) msg;
        int uncompressed = in.readableBytes();
        INPUT_BYTES.add(uncompressed);

        if (uncompressed < settings.getCompressionThreshold()) {
            if (processor.isIdle()) {
                writeRaw(ctx, in, uncompressed, promise);
            } else {
                processor.add(ctx, new RawWork(ctx, in, uncompressed, promise));
            }
            return;
        }

        if (processor.isIdle() && uncompressed < ZstdAsyncPools.ASYNC_THRESHOLD) {
            compressSync(ctx, in, uncompressed, compressionLevel, settings.effectiveWorkers(uncompressed),
                    settings, promise);
        } else {
            processor.add(ctx, new CompressWork(ctx, processor, in, uncompressed, compressionLevel,
                    settings.effectiveWorkers(uncompressed), settings, promise));
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        throw new UnsupportedOperationException("encode is not used; write() is overridden");
    }

    private void ensureCloseCleanup(ChannelHandlerContext ctx) {
        if (closeCleanupAttached) {
            return;
        }
        closeCleanupAttached = true;
        ctx.channel().closeFuture().addListener(future -> processor.discardAll());
    }

    private static int varIntLength(int value) {
        int length = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            length++;
        }
        return length;
    }

    /**
     * Whether the compressed frame (length varint + uncompressed-size varint + compressed payload)
     * is smaller than the raw frame ({@code length+1, 0x00, payload}).
     */
    private static boolean beneficial(int uncompressed, int compressedSize) {
        int frameLength = varIntLength(uncompressed) + compressedSize;
        int rawFrameLength = varIntLength(uncompressed + 1) + 1 + uncompressed;
        return varIntLength(frameLength) + frameLength < varIntLength(rawFrameLength) + rawFrameLength;
    }

    /** Encodes a sub-threshold packet as {@code varint(len+1), 0x00, raw}. Releases {@code msg}. */
    private static void writeRaw(ChannelHandlerContext ctx, ByteBuf msg, int uncompressed, ChannelPromise promise) {
        int sizeVarIntLength = varIntLength(uncompressed + 1);
        ByteBuf out = ctx.alloc().buffer(sizeVarIntLength + 1 + uncompressed);
        ZstdEncoder.writeVarInt(out, uncompressed + 1);
        out.writeByte(0);
        out.writeBytes(msg);
        OUTPUT_BYTES.add(out.readableBytes());
        msg.release();
        ctx.write(out, promise);
    }

    /** Encodes a zstd packet synchronously. Releases {@code in}. */
    private static void compressSync(ChannelHandlerContext ctx, ByteBuf in, int uncompressed, int level,
                                     int workers, ZstdSettings settings, ChannelPromise promise) {
        try {
            ByteBuf out = compressDirectOrCopy(ctx, in, uncompressed, level, workers, settings);
            if (out != null) {
                ctx.write(out, promise);
            }
        } catch (Throwable t) {
            promise.tryFailure(t);
        } finally {
            in.release();
        }
    }

    /**
     * Compresses the input synchronously into a pooled buffer. Returns null when the packet was
     * sent raw instead (never-expand fallback).
     */
    private static ByteBuf compressDirectOrCopy(ChannelHandlerContext ctx, ByteBuf in, int uncompressed,
                                                int level, int workers, ZstdSettings settings) {
        ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
        int bound = ZstdCodecCtx.compressBound(uncompressed);
        int sizeVarIntLength = varIntLength(uncompressed);

        if (in.isDirect()) {
            ByteBuf out = ctx.alloc().directBuffer(FRAME_LENGTH_SLOT + sizeVarIntLength + bound);
            ByteBuffer dst = out.nioBuffer(FRAME_LENGTH_SLOT + sizeVarIntLength, bound);
            int size = zctx.compress(dst, in.nioBuffer());
            if (size < 0) {
                out.release();
                throw new IllegalStateException("zstd compression failed: " + size);
            }
            return finishFrame(out, size, sizeVarIntLength, ctx, in, uncompressed, settings);
        }

        byte[] input = new byte[uncompressed];
        in.getBytes(in.readerIndex(), input);
        byte[] dstArr = ZstdCodecCtx.scratch(bound);
        int size = zctx.compress(dstArr, input);
        if (size < 0) {
            throw new IllegalStateException("zstd compression failed: " + size);
        }
        if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, size)) {
            ByteBuf out = rawAlloc(ctx, uncompressed);
            out.writeByte(0);
            out.writeBytes(input);
            OUTPUT_BYTES.add(out.readableBytes());
            return out;
        }
        ByteBuf out = allocCompressed(ctx, uncompressed, size);
        out.writeBytes(dstArr, 0, size);
        PACKETS_COMPRESSED.increment();
        OUTPUT_BYTES.add(out.readableBytes());
        return out;
    }

    /**
     * Writes the size VarInt and the frame-length VarInt around an already-compressed payload that
     * was written into {@code out} starting at {@code FRAME_LENGTH_SLOT + sizeVarIntLength}. The
     * frame-length VarInt only needs as many bytes as its value takes, so the frame starts at
     * {@code FRAME_LENGTH_SLOT - frameLengthVarIntLength} and the reader index is set accordingly;
     * no copy is performed.
     */
    private static ByteBuf finishFrame(ByteBuf out, int size, int sizeVarIntLength,
                                       ChannelHandlerContext ctx, ByteBuf in, int uncompressed,
                                       ZstdSettings settings) {
        if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, size)) {
            out.release();
            ByteBuf raw = rawAlloc(ctx, uncompressed);
            raw.writeByte(0);
            raw.writeBytes(in, in.readerIndex(), uncompressed);
            OUTPUT_BYTES.add(raw.readableBytes());
            return raw;
        }
        int frameLength = sizeVarIntLength + size;
        int frameLengthVarIntLength = varIntLength(frameLength);
        int frameStart = FRAME_LENGTH_SLOT - frameLengthVarIntLength;
        out.writerIndex(FRAME_LENGTH_SLOT + sizeVarIntLength + size);
        ZstdEncoder.writeVarIntAt(out, FRAME_LENGTH_SLOT, uncompressed);
        ZstdEncoder.writeVarIntAt(out, frameStart, frameLength);
        out.readerIndex(frameStart);
        PACKETS_COMPRESSED.increment();
        OUTPUT_BYTES.add(out.readableBytes());
        dumpFrame("proxy-frame", out, uncompressed, size, frameLength);
        return out;
    }

    private static void dumpFrame(String tag, ByteBuf out, int uncompressed, int size, int frameLength) {
        try {
            int readable = out.readableBytes();
            int idx = out.readerIndex();
            int head = Math.min(readable, 24);
            int tail = Math.min(readable - head, 8);
            StringBuilder sb = new StringBuilder(256);
            sb.append("uncompressed=").append(uncompressed)
                    .append(" size=").append(size)
                    .append(" frameLength=").append(frameLength)
                    .append(" varIntLen=").append(varIntLength(frameLength))
                    .append(" readable=").append(readable)
                    .append(" head=");
            for (int i = 0; i < head; i++) {
                int b = out.getUnsignedByte(idx + i);
                if (b < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(b));
            }
            if (tail > 0) {
                sb.append(" tail=");
                for (int i = 0; i < tail; i++) {
                    int b = out.getUnsignedByte(idx + readable - tail + i);
                    if (b < 0x10) {
                        sb.append('0');
                    }
                    sb.append(Integer.toHexString(b));
                }
            }
            TraceDump.dump(tag, sb.toString());
        } catch (RuntimeException e) {
            // tracing must never crash the connection
        }
    }

    private static ByteBuf rawAlloc(ChannelHandlerContext ctx, int uncompressed) {
        int sizeVarIntLength = varIntLength(uncompressed + 1);
        ByteBuf out = ctx.alloc().buffer(sizeVarIntLength + 1 + uncompressed);
        ZstdEncoder.writeVarInt(out, uncompressed + 1);
        return out;
    }

    private static ByteBuf allocCompressed(ChannelHandlerContext ctx, int uncompressed, int compressedSize) {
        int sizeVarIntLength = varIntLength(uncompressed);
        int frameLength = sizeVarIntLength + compressedSize;
        ByteBuf out = ctx.alloc().buffer(varIntLength(frameLength) + frameLength);
        ZstdEncoder.writeVarInt(out, frameLength);
        ZstdEncoder.writeVarInt(out, uncompressed);
        return out;
    }

    private static final class RawWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final ByteBuf msg;
        private final int uncompressed;
        private final ChannelPromise promise;

        RawWork(ChannelHandlerContext ctx, ByteBuf msg, int uncompressed, ChannelPromise promise) {
            this.ctx = ctx;
            this.msg = msg;
            this.uncompressed = uncompressed;
            this.promise = promise;
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            writeRaw(ctx, msg, uncompressed, promise);
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

    private static final class CompressWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final OrderedAsyncProcessor processor;
        private final ByteBuf in;
        private final int uncompressed;
        private final int level;
        private final int workers;
        private final ZstdSettings settings;
        private final ChannelPromise promise;

        CompressWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, ByteBuf in,
                     int uncompressed, int level, int workers, ZstdSettings settings,
                     ChannelPromise promise) {
            this.ctx = ctx;
            this.processor = processor;
            this.in = in;
            this.uncompressed = uncompressed;
            this.level = level;
            this.workers = workers;
            this.settings = settings;
            this.promise = promise;
        }

        @Override
        public boolean isAsync() {
            return uncompressed >= ZstdAsyncPools.ASYNC_THRESHOLD;
        }

        @Override
        public void processSync() {
            compressSync(ctx, in, uncompressed, level, workers, settings, promise);
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
            ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
            int bound = ZstdCodecCtx.compressBound(uncompressed);
            int sizeVarIntLength = varIntLength(uncompressed);

            if (in.isDirect()) {
                ByteBuf out = ctx.alloc().directBuffer(FRAME_LENGTH_SLOT + sizeVarIntLength + bound);
                ByteBuffer dst = out.nioBuffer(FRAME_LENGTH_SLOT + sizeVarIntLength, bound);
                int size = zctx.compress(dst, in.nioBuffer());
                if (size < 0) {
                    out.release();
                    throw new IllegalStateException("zstd compression failed: " + size);
                }
                return finishFrame(out, size, sizeVarIntLength, ctx, in, uncompressed, settings);
            }

            byte[] src = new byte[uncompressed];
            in.getBytes(in.readerIndex(), src);
            byte[] dstArr = ZstdCodecCtx.scratch(bound);
            int size = zctx.compress(dstArr, src);
            if (size < 0) {
                throw new IllegalStateException("zstd compression failed: " + size);
            }
            if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, size)) {
                ByteBuf out = rawAlloc(ctx, uncompressed);
                out.writeByte(0);
                out.writeBytes(src);
                OUTPUT_BYTES.add(out.readableBytes());
                return out;
            }
            ByteBuf out = allocCompressed(ctx, uncompressed, size);
            out.writeBytes(dstArr, 0, size);
            PACKETS_COMPRESSED.increment();
            OUTPUT_BYTES.add(out.readableBytes());
            return out;
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
