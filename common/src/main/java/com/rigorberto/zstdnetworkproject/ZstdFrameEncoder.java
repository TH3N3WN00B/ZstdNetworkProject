package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
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
 */
public class ZstdFrameEncoder extends MessageToByteEncoder<ByteBuf> {
    private static final int COMPRESSION_THRESHOLD = 256;

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

        if (uncompressed < COMPRESSION_THRESHOLD) {
            processor.add(ctx, new RawWork(ctx, in, uncompressed, promise));
            return;
        }

        PACKETS_COMPRESSED.increment();
        byte[] input = new byte[uncompressed];
        in.getBytes(in.readerIndex(), input);
        in.release();
        processor.add(ctx, new CompressWork(ctx, processor, input, uncompressed, compressionLevel,
                settings.effectiveWorkers(uncompressed), promise));
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
        private final byte[] input;
        private final int uncompressed;
        private final int level;
        private final int workers;
        private final ChannelPromise promise;

        CompressWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, byte[] input,
                     int uncompressed, int level, int workers, ChannelPromise promise) {
            this.ctx = ctx;
            this.processor = processor;
            this.input = input;
            this.uncompressed = uncompressed;
            this.level = level;
            this.workers = workers;
            this.promise = promise;
        }

        @Override
        public boolean isAsync() {
            return uncompressed >= ZstdAsyncPools.ASYNC_THRESHOLD;
        }

        @Override
        public void processSync() {
            try {
                ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
                byte[] dst = ZstdCodecCtx.scratch(ZstdCodecCtx.compressBound(uncompressed));
                int size = zctx.compress(dst, input);
                if (size < 0) {
                    throw new IllegalStateException("zstd compression failed: " + size);
                }
                ByteBuf out = allocOut(size);
                out.writeBytes(dst, 0, size);
                ctx.write(out, promise);
            } catch (Throwable t) {
                promise.tryFailure(t);
            }
        }

        @Override
        public void submitAsync() {
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
                ctx.executor().execute(() -> complete(result));
            });
        }

        private void complete(byte[] result) {
            if (!ctx.channel().isActive()) {
                promise.tryFailure(new ClosedChannelException());
                processor.onAsyncComplete(ctx);
                return;
            }
            ByteBuf out = allocOut(result.length);
            out.writeBytes(result);
            ctx.writeAndFlush(out, promise);
            processor.onAsyncComplete(ctx);
        }

        private ByteBuf allocOut(int compressedSize) {
            int sizeVarIntLength = varIntLength(uncompressed);
            int frameLength = sizeVarIntLength + compressedSize;
            ByteBuf out = ctx.alloc().buffer(varIntLength(frameLength) + frameLength);
            ZstdEncoder.writeVarInt(out, frameLength);
            ZstdEncoder.writeVarInt(out, uncompressed);
            OUTPUT_BYTES.add(frameLength);
            return out;
        }

        @Override
        public void discard() {
            promise.tryFailure(new ClosedChannelException());
        }
    }
}
