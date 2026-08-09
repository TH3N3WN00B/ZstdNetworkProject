package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minecraft compression decoder: reads VarInt(uncompressedSize); 0 means the rest of the frame is
 * raw, otherwise the rest is a zstd frame (for sizes at least 256) or a zlib frame (smaller, used
 * for compatibility with vanilla clients).
 *
 * <p>Frames are decompressed inline on the event loop using a reused per-thread context, except for
 * frames at least {@link ZstdAsyncPools#ASYNC_THRESHOLD} bytes which are decompressed on a shared
 * worker pool. A per-channel FIFO keeps frames in order when asynchronous decompression is in
 * flight.
 */
public class ZstdDecoder extends ByteToMessageDecoder {

    public static final long MAX_UNCOMPRESSED_SIZE = 64L * 1024 * 1024;

    public static final LongAdder ZSTD_PACKETS = new LongAdder();
    public static final LongAdder ZSTD_BYTES = new LongAdder();
    public static final LongAdder ZLIB_PACKETS = new LongAdder();
    public static final LongAdder ZLIB_BYTES = new LongAdder();
    public static final LongAdder RAW_PACKETS = new LongAdder();
    public static final LongAdder RAW_BYTES = new LongAdder();

    private static final ThreadLocal<Inflater> INFLATER_THREAD_LOCAL =
            ThreadLocal.withInitial(Inflater::new);

    private final OrderedAsyncProcessor processor = new OrderedAsyncProcessor();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        int uncompressedSize = readVarInt(in);

        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("Invalid uncompressed size: " + uncompressedSize);
        }

        if (uncompressedSize == 0) {
            RAW_PACKETS.increment();
            RAW_BYTES.add(in.readableBytes());
            ByteBuf raw = in.readRetainedSlice(in.readableBytes());
            if (processor.isIdle()) {
                out.add(raw);
            } else {
                processor.add(ctx, new RawWork(ctx, raw));
            }
            return;
        }

        if (uncompressedSize > MAX_UNCOMPRESSED_SIZE) {
            throw new IllegalArgumentException("Frame declares uncompressed size "
                    + uncompressedSize + " > max " + MAX_UNCOMPRESSED_SIZE);
        }

        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);

        if (uncompressedSize >= 256) {
            ZSTD_PACKETS.increment();
            ZSTD_BYTES.add(uncompressedSize);
            if (processor.isIdle() && uncompressedSize < ZstdAsyncPools.ASYNC_THRESHOLD) {
                out.add(decompressSync(ctx, input, uncompressedSize));
            } else {
                processor.add(ctx, new ZstdWork(ctx, processor, input, uncompressedSize));
            }
        } else {
            ZLIB_PACKETS.increment();
            ZLIB_BYTES.add(uncompressedSize);
            if (processor.isIdle()) {
                out.add(inflateSync(ctx, input, uncompressedSize));
            } else {
                processor.add(ctx, new ZlibWork(ctx, input, uncompressedSize));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            super.channelInactive(ctx);
        } finally {
            processor.discardAll();
        }
    }

    private static ByteBuf decompressSync(ChannelHandlerContext ctx, byte[] input, int size) {
        ZstdDecompressCtx zctx = ZstdCodecCtx.decompress();
        ByteBuf out = ctx.alloc().directBuffer(size);
        ByteBuffer dst = out.nioBuffer(0, size);
        int n = zctx.decompress(dst, input);
        if (n < 0) {
            out.release();
            throw new IllegalStateException("zstd decompression failed: " + n);
        }
        out.writerIndex(n);
        return out;
    }

    private static ByteBuf inflateSync(ChannelHandlerContext ctx, byte[] input, int size) {
        byte[] dst = ZstdCodecCtx.scratch(size);
        Inflater inflater = INFLATER_THREAD_LOCAL.get();
        try {
            synchronized (inflater) {
                inflater.reset();
                inflater.setInput(input);
                inflater.inflate(dst);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("zlib decompression failed", e);
        }
        return ctx.alloc().directBuffer(size).writeBytes(dst, 0, size);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int bytes = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << (bytes * 7);
            if (++bytes > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    private static final class RawWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final ByteBuf raw;

        RawWork(ChannelHandlerContext ctx, ByteBuf raw) {
            this.ctx = ctx;
            this.raw = raw;
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            ctx.fireChannelRead(raw);
        }

        @Override
        public void submitAsync() {
        }

        @Override
        public void discard() {
            raw.release();
        }
    }

    private static final class ZlibWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final byte[] input;
        private final int size;

        ZlibWork(ChannelHandlerContext ctx, byte[] input, int size) {
            this.ctx = ctx;
            this.input = input;
            this.size = size;
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            try {
                ctx.fireChannelRead(inflateSync(ctx, input, size));
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        }

        @Override
        public void submitAsync() {
        }

        @Override
        public void discard() {
        }
    }

    private static final class ZstdWork implements OrderedAsyncProcessor.Work {
        private final ChannelHandlerContext ctx;
        private final OrderedAsyncProcessor processor;
        private final byte[] input;
        private final int size;

        ZstdWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, byte[] input, int size) {
            this.ctx = ctx;
            this.processor = processor;
            this.input = input;
            this.size = size;
        }

        @Override
        public boolean isAsync() {
            return size >= ZstdAsyncPools.ASYNC_THRESHOLD;
        }

        @Override
        public void processSync() {
            try {
                ctx.fireChannelRead(decompressSync(ctx, input, size));
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        }

        @Override
        public void submitAsync() {
            ZstdAsyncPools.executor().execute(() -> {
                ByteBuf result;
                try {
                    ZstdDecompressCtx zctx = ZstdCodecCtx.decompress();
                    ByteBuf out = ctx.alloc().directBuffer(size);
                    ByteBuffer dst = out.nioBuffer(0, size);
                    int n = zctx.decompress(dst, input);
                    if (n < 0) {
                        out.release();
                        throw new IllegalStateException("zstd decompression failed: " + n);
                    }
                    out.writerIndex(n);
                    result = out;
                } catch (Throwable t) {
                    ctx.executor().execute(() -> {
                        ctx.fireExceptionCaught(t);
                        processor.onAsyncComplete(ctx);
                    });
                    return;
                }
                ctx.executor().execute(() -> complete(result));
            });
        }

        private void complete(ByteBuf result) {
            if (ctx.channel().isActive()) {
                ctx.fireChannelRead(result);
            } else {
                result.release();
            }
            processor.onAsyncComplete(ctx);
        }

        @Override
        public void discard() {
        }
    }
}
