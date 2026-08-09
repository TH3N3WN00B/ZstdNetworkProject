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
 * with a per-channel FIFO preserving packet order.
 *
 * <p>When the input {@link ByteBuf} is direct, the synchronous path compresses straight from the
 * buffer's memory into a pooled direct buffer, avoiding intermediate {@code byte[]} copies. When
 * {@link ZstdSettings#isCompressIfBeneficial()} is enabled, a packet whose compressed form would
 * not actually be smaller is sent uncompressed instead.
 */
public class ZstdFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

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
            processor.add(ctx, new RawWork(ctx, in, uncompressed, promise));
            return;
        }

        processor.add(ctx, new CompressWork(ctx, processor, in, uncompressed, compressionLevel,
                settings.effectiveWorkers(uncompressed), settings, promise));
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
            int sizeVarIntLength = varIntLength(uncompressed + 1);
            ByteBuf out = ctx.alloc().buffer(sizeVarIntLength + 1 + uncompressed);
            ZstdEncoder.writeVarInt(out, uncompressed + 1);
            out.writeByte(0);
            out.writeBytes(msg);
            OUTPUT_BYTES.add(out.readableBytes());
            msg.release();
            ctx.write(out, promise);
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
            try {
                ByteBuf out = compressDirectOrCopy();
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
         * Compresses the input synchronously into a pooled buffer. Returns null when the packet
         * was sent raw instead (never-expand fallback).
         */
        private ByteBuf compressDirectOrCopy() {
            ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
            int bound = ZstdCodecCtx.compressBound(uncompressed);
            int sizeVarIntLength = varIntLength(uncompressed);

            if (in.isDirect()) {
                ByteBuf payload = ctx.alloc().directBuffer(bound);
                ByteBuffer dst = payload.nioBuffer(0, bound);
                ByteBuffer src = in.nioBuffer();
                int size = zctx.compress(dst, src);
                if (size < 0) {
                    payload.release();
                    throw new IllegalStateException("zstd compression failed: " + size);
                }
                payload.writerIndex(size);
                try {
                    return finishFrame(payload, size);
                } finally {
                    payload.release();
                }
            }

            byte[] input = new byte[uncompressed];
            in.getBytes(in.readerIndex(), input);
            byte[] dstArr = ZstdCodecCtx.scratch(bound);
            int size = zctx.compress(dstArr, input);
            if (size < 0) {
                throw new IllegalStateException("zstd compression failed: " + size);
            }
            if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, size)) {
                ByteBuf out = rawAlloc();
                out.writeByte(0);
                out.writeBytes(input);
                OUTPUT_BYTES.add(out.readableBytes());
                return out;
            }
            ByteBuf out = allocCompressed(size);
            out.writeBytes(dstArr, 0, size);
            PACKETS_COMPRESSED.increment();
            OUTPUT_BYTES.add(out.readableBytes());
            return out;
        }

        /** Copies the already-compressed payload into a final frame (length varint + size varint). */
        private ByteBuf finishFrame(ByteBuf payload, int size) {
            if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, size)) {
                ByteBuf out = ctx.alloc().buffer(varIntLength(uncompressed + 1) + 1 + uncompressed);
                ZstdEncoder.writeVarInt(out, uncompressed + 1);
                out.writeByte(0);
                out.writeBytes(in, in.readerIndex(), uncompressed);
                OUTPUT_BYTES.add(out.readableBytes());
                return out;
            }
            ByteBuf out = allocCompressed(size);
            out.writeBytes(payload, 0, size);
            PACKETS_COMPRESSED.increment();
            OUTPUT_BYTES.add(out.readableBytes());
            return out;
        }

        private ByteBuf rawAlloc() {
            int sizeVarIntLength = varIntLength(uncompressed + 1);
            ByteBuf out = ctx.alloc().buffer(sizeVarIntLength + 1 + uncompressed);
            ZstdEncoder.writeVarInt(out, uncompressed + 1);
            return out;
        }

        private ByteBuf allocCompressed(int compressedSize) {
            int sizeVarIntLength = varIntLength(uncompressed);
            int frameLength = sizeVarIntLength + compressedSize;
            ByteBuf out = ctx.alloc().buffer(varIntLength(frameLength) + frameLength);
            ZstdEncoder.writeVarInt(out, frameLength);
            ZstdEncoder.writeVarInt(out, uncompressed);
            return out;
        }

        @Override
        public void submitAsync() {
            byte[] input = new byte[uncompressed];
            in.getBytes(in.readerIndex(), input);
            in.release();
            ZstdAsyncPools.executor().execute(() -> {
                byte[] result;
                try {
                    ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
                    byte[] dst = new byte[ZstdCodecCtx.compressBound(uncompressed)];
                    int size = zctx.compress(dst, input);
                    if (size < 0) {
                        throw new IllegalStateException("zstd compression failed: " + size);
                    }
                    result = java.util.Arrays.copyOf(dst, size);
                } catch (Throwable t) {
                    ctx.executor().execute(() -> {
                        promise.tryFailure(t);
                        processor.onAsyncComplete(ctx);
                    });
                    return;
                }
                ctx.executor().execute(() -> complete(input, result));
            });
        }

        private void complete(byte[] input, byte[] result) {
            if (!ctx.channel().isActive()) {
                promise.tryFailure(new ClosedChannelException());
                processor.onAsyncComplete(ctx);
                return;
            }
            ByteBuf out;
            if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, result.length)) {
                out = ctx.alloc().buffer(varIntLength(uncompressed + 1) + 1 + uncompressed);
                ZstdEncoder.writeVarInt(out, uncompressed + 1);
                out.writeByte(0);
                out.writeBytes(input);
            } else {
                out = allocCompressed(result.length);
                out.writeBytes(result);
                PACKETS_COMPRESSED.increment();
            }
            OUTPUT_BYTES.add(out.readableBytes());
            ctx.writeAndFlush(out, promise);
            processor.onAsyncComplete(ctx);
        }

        @Override
        public void discard() {
            in.release();
            promise.tryFailure(new ClosedChannelException());
        }
    }
}
