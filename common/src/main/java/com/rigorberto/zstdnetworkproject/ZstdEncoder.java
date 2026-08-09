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
 * Minecraft compression encoder: writes VarInt(uncompressedSize) followed by the zstd-compressed
 * payload, or VarInt(0) followed by the raw payload for packets under the compression threshold.
 *
 * <p>Small packets are compressed inline on the event loop using a reused per-thread context.
 * Packets at least {@link ZstdAsyncPools#ASYNC_THRESHOLD} bytes are compressed on a shared worker
 * pool, with a per-channel FIFO preserving packet order.
 *
 * <p>When the input {@link ByteBuf} is direct, the synchronous path compresses straight from the
 * buffer's memory into a pooled direct buffer, avoiding the intermediate {@code byte[]} copies.
 * When {@link ZstdSettings#isCompressIfBeneficial()} is enabled, a packet whose compressed form
 * would not actually be smaller is sent uncompressed instead.
 */
public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

    private final int compressionLevel;
    private final ZstdSettings settings;
    private final OrderedAsyncProcessor processor = new OrderedAsyncProcessor();
    private boolean closeCleanupAttached;

    public ZstdEncoder() {
        this(new ZstdSettings());
    }

    public ZstdEncoder(int compressionLevel) {
        this.settings = new ZstdSettings();
        this.compressionLevel = compressionLevel;
    }

    public ZstdEncoder(ZstdSettings settings) {
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
        int readable = in.readableBytes();
        INPUT_BYTES.add(readable);

        if (readable < settings.getCompressionThreshold()) {
            processor.add(ctx, new RawWork(ctx, in, readable, promise));
            return;
        }

        processor.add(ctx, new CompressWork(ctx, processor, in, readable, compressionLevel,
                settings.effectiveWorkers(readable), settings, promise));
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

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static void writeVarIntAt(ByteBuf buf, int index, int value) {
        while ((value & ~0x7F) != 0) {
            buf.setByte(index++, (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.setByte(index, value);
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
     * Whether the compressed frame (varint + compressed payload) is smaller than sending the
     * packet raw (one zero varint byte + the payload).
     */
    private static boolean beneficial(int readable, int compressedSize) {
        return varIntLength(readable) + compressedSize < 1 + readable;
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
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            ByteBuf out = ctx.alloc().buffer(readable + 5);
            writeVarInt(out, 0);
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
        public boolean isAsync() {
            return readable >= ZstdAsyncPools.ASYNC_THRESHOLD;
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
            int bound = ZstdCodecCtx.compressBound(readable);
            int varIntLength = varIntLength(readable);

            if (in.isDirect()) {
                ByteBuf out = ctx.alloc().directBuffer(varIntLength + bound);
                ByteBuffer dst = out.nioBuffer(varIntLength, bound);
                ByteBuffer src = in.nioBuffer();
                int size = zctx.compress(dst, src);
                if (size < 0) {
                    out.release();
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
                return out;
            }

            byte[] input = new byte[readable];
            in.getBytes(in.readerIndex(), input);
            byte[] dstArr = ZstdCodecCtx.scratch(bound);
            int size = zctx.compress(dstArr, input);
            if (size < 0) {
                throw new IllegalStateException("zstd compression failed: " + size);
            }
            if (settings.isCompressIfBeneficial() && !beneficial(readable, size)) {
                ByteBuf out = ctx.alloc().buffer(1 + readable);
                writeVarInt(out, 0);
                out.writeBytes(input);
                OUTPUT_BYTES.add(out.readableBytes());
                return out;
            }
            ByteBuf out = ctx.alloc().buffer(varIntLength + size);
            writeVarInt(out, readable);
            out.writeBytes(dstArr, 0, size);
            PACKETS_COMPRESSED.increment();
            OUTPUT_BYTES.add(out.readableBytes());
            return out;
        }

        @Override
        public void submitAsync() {
            byte[] input = new byte[readable];
            in.getBytes(in.readerIndex(), input);
            in.release();
            ZstdAsyncPools.executor().execute(() -> {
                byte[] result;
                try {
                    result = compressCopy(input, level, workers);
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
            if (settings.isCompressIfBeneficial() && !beneficial(readable, result.length)) {
                out = ctx.alloc().buffer(1 + readable);
                writeVarInt(out, 0);
                out.writeBytes(input);
            } else {
                int varIntLength = varIntLength(readable);
                out = ctx.alloc().buffer(varIntLength + result.length);
                writeVarInt(out, readable);
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

    private static byte[] compressCopy(byte[] input, int level, int workers) {
        ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
        byte[] dst = new byte[ZstdCodecCtx.compressBound(input.length)];
        int size = zctx.compress(dst, input);
        if (size < 0) {
            throw new IllegalStateException("zstd compression failed: " + size);
        }
        return java.util.Arrays.copyOf(dst, size);
    }
}
