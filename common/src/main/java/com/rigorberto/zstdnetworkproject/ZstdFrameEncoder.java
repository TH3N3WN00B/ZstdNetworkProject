package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.ByteBuffer;
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
 * <p>The payload is compressed into a dedicated direct buffer at position 0 (zstd-jni's buffer
 * entry points write at the absolute base of a buffer and ignore pre-set positions, so the payload
 * must not be compressed into a view offset past the varint slots) and the frame is then assembled
 * with forward writes as {@code varint(frameLength), varint(uncompressedSize), payload}. When
 * {@link ZstdSettings#isCompressIfBeneficial()} is enabled, a packet whose compressed form would
 * not actually be smaller is sent uncompressed instead.
 */
public class ZstdFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

    private final int compressionLevel;
    private final ZstdSettings settings;
    private final OrderedAsyncProcessor processor = new OrderedAsyncProcessor(OrderedAsyncProcessor.Direction.OUTBOUND);
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

    private static int varIntLength(int value) {
        return ZstdEncoder.varIntLength(value);
    }

    /** Writes {@code value} as a VarInt at the current writer index of {@code buf}. */
    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * Whether the compressed frame (length varint + uncompressed-size varint + compressed payload)
     * is smaller than the raw frame ({@code length+1, 0x00, payload}).
     */
    private static boolean beneficial(int uncompressed, int compressedSize) {
        // Both sides are measured the same way: the frame body, plus the VarInt that prefixes it.
        int compressedBody = varIntLength(uncompressed) + compressedSize;
        int rawBody = 1 + uncompressed;
        return varIntLength(compressedBody) + compressedBody < varIntLength(rawBody) + rawBody;
    }

    /** Encodes a sub-threshold packet as {@code varint(len+1), 0x00, raw}. Releases {@code msg}. */
    private static void writeRaw(ChannelHandlerContext ctx, ByteBuf msg, int uncompressed, ChannelPromise promise) {
        int sizeVarIntLength = varIntLength(uncompressed + 1);
        ByteBuf out = ctx.alloc().directBuffer(sizeVarIntLength + 1 + uncompressed);
        writeVarInt(out, uncompressed + 1);
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
            ctx.write(compressDirectOrCopy(ctx, in, uncompressed, level, workers, settings), promise);
        } catch (Throwable t) {
            promise.tryFailure(t);
        } finally {
            in.release();
        }
    }

    /**
     * Compresses the input synchronously into a pooled buffer holding the complete length-prefixed
     * frame (or the {@code length+1, 0x00, raw} form when {@code compress-if-beneficial} rejects the
     * compressed one).
     */
    private static ByteBuf compressDirectOrCopy(ChannelHandlerContext ctx, ByteBuf in, int uncompressed,
                                                int level, int workers, ZstdSettings settings) {
        ZstdCompressCtx zctx = ZstdCodecCtx.compress(level, workers);
        int bound = ZstdCodecCtx.compressBound(uncompressed);
        int sizeVarIntLength = varIntLength(uncompressed);

        // zstd-jni's compress(ByteBuffer, ByteBuffer) entry points write at the buffer's absolute
        // base and ignore a pre-set position, so the payload is compressed into a dedicated buffer
        // at position 0 and the frame is assembled afterwards (same corruption that produced
        // `00 b5 2f fd ...` magic frames from {@link ZstdEncoder} before its fix).
        ByteBuf payload = ctx.alloc().directBuffer(bound);
        // zstd-jni only accepts direct NIO buffers, so anything else (heap allocator, a composite
        // whose nioBuffer() would merge into a heap buffer) is staged through a direct copy first.
        ByteBuf staged = ZstdCodecCtx.isNativeReadable(in) ? null : ctx.alloc().directBuffer(uncompressed);
        try {
            ByteBuffer dst = payload.nioBuffer(0, bound);
            ByteBuffer src;
            if (staged == null) {
                src = in.nioBuffer();
            } else {
                staged.writeBytes(in, in.readerIndex(), uncompressed);
                src = staged.nioBuffer();
            }

            int size = zctx.compress(dst, src);
            if (size < 0) {
                throw new IllegalStateException("zstd compression failed: " + size);
            }
            payload.writerIndex(size);

            if (settings.isCompressIfBeneficial() && !beneficial(uncompressed, size)) {
                ByteBuf raw = rawAlloc(ctx, uncompressed);
                raw.writeByte(0);
                raw.writeBytes(in, in.readerIndex(), uncompressed);
                OUTPUT_BYTES.add(raw.readableBytes());
                return raw;
            }

            // Assemble varint(frameLength), varint(uncompressedSize), payload with forward writes.
            int frameLength = sizeVarIntLength + size;
            ByteBuf out = ctx.alloc().directBuffer(varIntLength(frameLength) + sizeVarIntLength + size);
            writeVarInt(out, frameLength);
            writeVarInt(out, uncompressed);
            out.writeBytes(payload, 0, size);
            PACKETS_COMPRESSED.increment();
            OUTPUT_BYTES.add(out.readableBytes());
            dumpFrame("proxy-frame", out, uncompressed, size, frameLength);
            return out;
        } finally {
            if (staged != null) {
                staged.release();
            }
            payload.release();
        }
    }

    private static void dumpFrame(String tag, ByteBuf out, int uncompressed, int size, int frameLength) {
        if (!TraceDump.isEnabled()) {
            return;
        }
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
        ByteBuf out = ctx.alloc().directBuffer(sizeVarIntLength + 1 + uncompressed);
        writeVarInt(out, uncompressed + 1);
        return out;
    }

    private static final class RawWork extends AbstractRawWork {
        RawWork(ChannelHandlerContext ctx, ByteBuf msg, int size, ChannelPromise promise) {
            super(ctx, msg, size, promise);
        }

        @Override
        protected void writeRaw(ChannelHandlerContext ctx, ByteBuf msg, int size, ChannelPromise promise) {
            ZstdFrameEncoder.writeRaw(ctx, msg, size, promise);
        }
    }

    private static final class CompressWork extends AbstractCompressWork {
        CompressWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, ByteBuf in, int size,
                     int level, int workers, ZstdSettings settings, ChannelPromise promise) {
            super(ctx, processor, in, size, level, workers, settings, promise);
        }

        @Override
        protected ByteBuf compressDirectOrCopy() {
            return ZstdFrameEncoder.compressDirectOrCopy(ctx, in, size, level, workers, settings);
        }
    }
}
