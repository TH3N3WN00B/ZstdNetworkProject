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

    /**
     * Largest declared uncompressed frame size accepted from a peer. Matches vanilla
     * {@code CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH}: the decompression buffer is allocated
     * from this declared value <em>before</em> the frame is validated, so a larger bound would let
     * a peer commit that much direct memory per frame at almost no bandwidth cost.
     */
    public static final long MAX_UNCOMPRESSED_SIZE = 8L * 1024 * 1024;

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

    private final OrderedAsyncProcessor processor = new OrderedAsyncProcessor(OrderedAsyncProcessor.Direction.INBOUND);
    private boolean warnedRawFrame;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        int frameStart = in.readerIndex();
        int uncompressedSize = readVarInt(in);

        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("Invalid uncompressed size: " + uncompressedSize);
        }

        if (uncompressedSize == 0) {
            RAW_PACKETS.increment();
            RAW_BYTES.add(in.readableBytes());
            if (HexDump.isEnabled()) {
                HexDump.dump("frame-in", "IN raw declared=0 peer=" + HexDump.peerOf(ctx), in);
            }
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
            if (TraceDump.isEnabled()) {
                int frameBytes = in.readableBytes();
                TraceDump.dump("client-frame",
                        "size=" + uncompressedSize + " frameBytes=" + frameBytes
                                + " head=" + hex(in, in.readerIndex(), Math.min(frameBytes, 16)));
            }
            if (HexDump.isEnabled()) {
                HexDump.dump("frame-in", "IN zstd declared=" + uncompressedSize
                        + " peer=" + HexDump.peerOf(ctx), in);
            }
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
        if (HexDump.isEnabled()) {
            HexDump.dump("frame-in", "IN zlib declared=" + uncompressedSize
                    + " peer=" + HexDump.peerOf(ctx), in);
        }
        int headerLen = in.readerIndex() - frameStart;
        byte[] header = new byte[headerLen];
        in.getBytes(frameStart, header);
        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);
        if (processor.isIdle()) {
            out.add(inflateOrPassThrough(ctx, header, input, uncompressedSize));
        } else {
            processor.add(ctx, new ZlibWork(this, ctx, header, input, uncompressedSize));
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
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        try {
            super.handlerRemoved0(ctx);
        } finally {
            processor.discardAll();
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
            // isDirect() alone is not enough: nioBuffer() on a multi-component direct composite
            // returns a merged HEAP buffer, which zstd-jni rejects.
            if (ZstdCodecCtx.isNativeReadable(in)) {
                n = zctx.decompress(dst, in.nioBuffer());
            } else if (in.hasArray()) {
                n = zctx.decompressByteArrayToDirectByteBuffer(
                        dst, 0, size, in.array(), in.arrayOffset() + in.readerIndex(), in.readableBytes());
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

    /**
     * Inflates a zlib frame, falling back to passing the payload through unchanged when it turns
     * out not to be zlib at all. Some third-party servers and proxies write frames that omit the
     * {@code varint(size)} compression header entirely (a protocol violation vanilla never
     * produces); in that case the bytes we consumed as the size prefix are really the first bytes
     * of the packet (usually its packet id), so they are reassembled with the payload before it is
     * forwarded — dropping them would shift every subsequent read and desync the decoder. Custom
     * client patchers normally tolerate such peers; disconnecting on the first bad frame would
     * make this mod unusable there. Logged once per connection.
     */
    private ByteBuf inflateOrPassThrough(
            ChannelHandlerContext ctx, byte[] header, byte[] input, int size) {
        try {
            return inflateSync(ctx, input, size);
        } catch (NotZlibException e) {
            if (!warnedRawFrame) {
                warnedRawFrame = true;
                System.err.println("[zstdnetworkproject] Peer sent an uncompressed frame without "
                        + "the compression size prefix (non-vanilla server or proxy); restoring the "
                        + "misread " + header.length + " prefix byte(s) so the packet stays intact. "
                        + e.getMessage());
            }
            if (HexDump.isEnabled()) {
                HexDump.note("frame-in", "IN pass-through repaired (payload is not zlib, restored "
                        + "prefix): declared=" + size + " headerBytes=" + header.length
                        + " bytes=" + input.length + " peer=" + HexDump.peerOf(ctx));
            }
            ByteBuf passthrough = ctx.alloc().directBuffer(header.length + input.length);
            passthrough.writeBytes(header).writeBytes(input);
            return passthrough;
        }
    }

    /** Marks frames whose payload is not a zlib stream at all (non-vanilla raw-frame peers). */
    private static final class NotZlibException extends IllegalStateException {
        NotZlibException(String detail, DataFormatException cause) {
            super(detail, cause);
        }
    }

    private static ByteBuf inflateSync(ChannelHandlerContext ctx, byte[] input, int size) {
        byte[] dst = ZstdCodecCtx.scratch(size);
        Inflater inflater = INFLATER_THREAD_LOCAL.get();
        int n;
        try {
            inflater.reset();
            inflater.setInput(input);
            n = inflater.inflate(dst);
        } catch (DataFormatException e) {
            String detail = zlibFailureDetail(input, size, e.toString());
            TraceDump.dump("client-decode", detail);
            throw new NotZlibException(detail, e);
        }
        if (n != size) {
            String detail = zlibFailureDetail(input, size,
                    "produced " + n + " bytes, expected " + size);
            TraceDump.dump("client-decode", detail);
            throw new IllegalStateException(detail);
        }
        return ctx.alloc().directBuffer(size).writeBytes(dst, 0, size);
    }

    /**
     * Full context for a zlib failure: declared size, actual frame length, hex head/tail of the
     * received payload and the running frame-type totals, so a malformed stream can be identified
     * from the disconnect report alone (peer misbehaving vs. mid-frame desync).
     */
    private static String zlibFailureDetail(byte[] input, int size, String reason) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("zlib decompression failed. declaredSize=").append(size)
                .append(" frameBytes=").append(input.length)
                .append(" reason=").append(reason)
                .append(" totals: zstd=").append(ZSTD_PACKETS.sum())
                .append(" zlib=").append(ZLIB_PACKETS.sum())
                .append(" raw=").append(RAW_PACKETS.sum());
        if (input.length > 0) {
            int head = Math.min(input.length, 64);
            sb.append(" head=").append(hex(input, 0, head));
            int tailStart = input.length - Math.min(input.length - head, 32);
            if (tailStart > head) {
                sb.append(" tail=").append(hex(input, tailStart, input.length - tailStart));
            }
        }
        return sb.toString();
    }

    private static String hex(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int b = bytes[offset + i] & 0xFF;
            if (b < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
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
        private final int queuedBytes;

        RawWork(ChannelHandlerContext ctx, ByteBuf raw) {
            this.ctx = ctx;
            this.raw = raw;
            this.queuedBytes = raw.readableBytes();
        }

        @Override
        public int queuedBytes() {
            return queuedBytes;
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
        private final ZstdDecoder decoder;
        private final ChannelHandlerContext ctx;
        private final byte[] header;
        private final byte[] input;
        private final int size;

        ZlibWork(ZstdDecoder decoder, ChannelHandlerContext ctx, byte[] header, byte[] input,
                int size) {
            this.decoder = decoder;
            this.ctx = ctx;
            this.header = header;
            this.input = input;
            this.size = size;
        }

        @Override
        public int queuedBytes() {
            return input.length;
        }

        @Override
        public boolean isAsync() {
            return false;
        }

        @Override
        public void processSync() {
            try {
                ctx.fireChannelRead(decoder.inflateOrPassThrough(ctx, header, input, size));
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
        private final int queuedBytes;

        ZstdWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, ByteBuf in, int size) {
            this.ctx = ctx;
            this.processor = processor;
            this.in = in;
            this.size = size;
            // Charge the queue for what the decompressed frame will cost, not for the compressed
            // bytes we are holding: that is the memory a peer can actually make us commit.
            this.queuedBytes = Math.max(in.readableBytes(), size);
        }

        @Override
        public int queuedBytes() {
            return queuedBytes;
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
