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
 * raw, otherwise the rest is a zstd or zlib frame. The compressor is chosen by sniffing the frame
 * magic: frames starting with the zstd magic {@code 28 B5 2F FD} are decompressed with zstd and
 * everything else with zlib, so the decoder transparently supports vanilla servers (zlib), zstd
 * peers and raw frames in any combination.
 *
 * <p>Frames are decompressed inline on the event loop using a reused per-thread context, except for
 * frames at least {@link ZstdAsyncPools#ASYNC_THRESHOLD} bytes which are decompressed on a shared
 * worker pool. A per-channel FIFO keeps frames in order when asynchronous decompression is in
 * flight.
 *
 * <p>When the incoming frame is a direct {@link ByteBuf} it is decompressed straight from the
 * buffer's memory into a pooled direct buffer, avoiding intermediate {@code byte[]} copies.
 */
public class ZstdDecoder extends ByteToMessageDecoder {

    public static final long MAX_UNCOMPRESSED_SIZE = 64L * 1024 * 1024;

    /** First four bytes of every zstd frame. */
    private static final byte[] ZSTD_MAGIC = {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD};

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

        if (in.readableBytes() >= 4 && isZstdMagic(in)) {
            ZSTD_PACKETS.increment();
            ZSTD_BYTES.add(uncompressedSize);
            ZstdCapability.markZstdObserved(ctx.channel());
            int frameBytes = in.readableBytes();
            TraceDump.dump("client-frame",
                    "size=" + uncompressedSize + " frameBytes=" + frameBytes
                            + " head=" + hex(in, in.readerIndex(), Math.min(frameBytes, 16)));
            ByteBuf payload = in.readRetainedSlice(in.readableBytes());
            if (processor.isIdle() && uncompressedSize < ZstdAsyncPools.ASYNC_THRESHOLD) {
                try {
                    out.add(decompressSync(ctx, payload, uncompressedSize));
                } finally {
                    payload.release();
                }
            } else {
                processor.add(ctx, new ZstdWork(ctx, processor, payload, uncompressedSize));
            }
            return;
        }

        ZLIB_PACKETS.increment();
        ZLIB_BYTES.add(uncompressedSize);
        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);
        if (processor.isIdle()) {
            out.add(inflateSync(ctx, input, uncompressedSize));
        } else {
            processor.add(ctx, new ZlibWork(ctx, input, uncompressedSize));
        }
    }

    private static boolean isZstdMagic(ByteBuf in) {
        int idx = in.readerIndex();
        return in.getByte(idx) == ZSTD_MAGIC[0]
                && in.getByte(idx + 1) == ZSTD_MAGIC[1]
                && in.getByte(idx + 2) == ZSTD_MAGIC[2]
                && in.getByte(idx + 3) == ZSTD_MAGIC[3];
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            super.channelInactive(ctx);
        } finally {
            processor.discardAll();
        }
    }

    /**
     * Decompresses a zstd frame held by {@code in} into a pooled direct buffer. May run on any
     * thread; the caller owns releasing {@code in}.
     */
    private static ByteBuf decompressSync(ChannelHandlerContext ctx, ByteBuf in, int size) {
        ZstdDecompressCtx zctx = ZstdCodecCtx.decompress();
        ByteBuf out = ctx.alloc().directBuffer(size);
        ByteBuffer dst = out.nioBuffer(0, size);
        try {
            int n;
            if (in.isDirect()) {
                n = zctx.decompress(dst, in.nioBuffer());
            } else {
                int len = in.readableBytes();
                byte[] src = ZstdCodecCtx.scratch(len);
                in.getBytes(in.readerIndex(), src, 0, len);
                n = zctx.decompressByteArrayToDirectByteBuffer(dst, 0, size, src, 0, len);
            }
            if (n < 0) {
                String detail = describeFailure(in, size, "zstd decompression failed: " + n);
                TraceDump.dump("client-decode", detail);
                throw new IllegalStateException(detail);
            }
            out.writerIndex(n);
            return out;
        } catch (RuntimeException e) {
            out.release();
            String detail = describeFailure(in, size, e.toString());
            TraceDump.dump("client-decode", detail);
            throw new IllegalStateException(detail, e);
        }
    }

    private static String describeFailure(ByteBuf in, int size, String reason) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("zstd decompress failed. declaredSize=").append(size)
                .append(" readableBytes=").append(in.readableBytes())
                .append(" isDirect=").append(in.isDirect())
                .append(" clazz=").append(in.getClass().getSimpleName())
                .append(" reason=").append(reason);
        int len = in.readableBytes();
        if (len > 0) {
            int idx = in.readerIndex();
            int head = Math.min(len, 64);
            int tail = Math.min(len - head, 32);
            sb.append(" head=").append(hex(in, idx, head));
            if (tail > 0) {
                sb.append(" tail=").append(hex(in, idx + len - tail, tail));
            }
            sb.append(" fullLen=").append(len);
            try {
                sb.append(" fullHex=").append(hex(in, idx, len));
            } catch (RuntimeException ex) {
                sb.append(" fullHex=unavailable(").append(ex).append(')');
            }
        }
        return sb.toString();
    }

    private static String hex(ByteBuf buf, int index, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int b = buf.getUnsignedByte(index + i);
            if (b < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
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
        private final ByteBuf in;
        private final int size;

        ZstdWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, ByteBuf in, int size) {
            this.ctx = ctx;
            this.processor = processor;
            this.in = in;
            this.size = size;
        }

        @Override
        public boolean isAsync() {
            return size >= ZstdAsyncPools.ASYNC_THRESHOLD;
        }

        @Override
        public void processSync() {
            try {
                ctx.fireChannelRead(decompressSync(ctx, in, size));
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            } finally {
                in.release();
            }
        }

        @Override
        public void submitAsync() {
            ZstdAsyncPools.executor().execute(() -> {
                ByteBuf result;
                try {
                    result = decompressSync(ctx, in, size);
                } catch (Throwable t) {
                    in.release();
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
            try {
                if (ctx.channel().isActive()) {
                    ctx.fireChannelRead(result);
                } else {
                    result.release();
                }
            } finally {
                in.release();
                processor.onAsyncComplete(ctx);
            }
        }

        @Override
        public void discard() {
            in.release();
        }
    }
}
