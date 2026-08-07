package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.Inflater;

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

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        int startIndex = in.readerIndex();
        int uncompressedSize = readVarInt(in);

        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("Invalid uncompressed size: " + uncompressedSize);
        }

        if (uncompressedSize == 0) {
            RAW_PACKETS.increment();
            RAW_BYTES.add(in.readableBytes());
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        if (uncompressedSize > MAX_UNCOMPRESSED_SIZE) {
            throw new IllegalArgumentException("Uncompressed size exceeds limit: " + uncompressedSize);
        }

        if (in.readableBytes() == 0) {
            in.readerIndex(startIndex);
            return;
        }

        byte[] input = new byte[in.readableBytes()];
        in.getBytes(in.readerIndex(), input);

        ByteBuf decompressed = ctx.alloc().directBuffer(uncompressedSize);
        try {
            try {
                byte[] output = Zstd.decompress(input, uncompressedSize);
                if (output.length != uncompressedSize) {
                    throw new IllegalStateException("Zstd decompressed size mismatch: expected "
                            + uncompressedSize + ", got " + output.length);
                }
                decompressed.writeBytes(output);
                ZSTD_PACKETS.increment();
                ZSTD_BYTES.add(uncompressedSize);
            } catch (Exception zstdError) {
                byte[] output = new byte[uncompressedSize];
                try {
                    Inflater inflater = INFLATER_THREAD_LOCAL.get();
                    inflater.reset();
                    inflater.setInput(input);
                    int result = inflater.inflate(output);
                    if (result != uncompressedSize) {
                        throw new IllegalStateException("Zlib decompressed size mismatch: expected "
                                + uncompressedSize + ", got " + result);
                    }
                    decompressed.writeBytes(output, 0, result);
                    ZLIB_PACKETS.increment();
                    ZLIB_BYTES.add(uncompressedSize);
                } catch (Exception zlibError) {
                    throw new IllegalStateException("Both zstd and zlib decompression failed"
                            + " [sizeVarInt=" + uncompressedSize
                            + ", frameBytes=" + in.readableBytes()
                            + ", first32=" + hexDump(in, startIndex, Math.min(32, in.readableBytes())) + "]",
                            zlibError);
                }
            }

            out.add(decompressed);
            in.skipBytes(input.length);
        } catch (Exception e) {
            decompressed.release();
            throw e;
        }
    }

    private static String hexDump(ByteBuf buf, int index, int length) {
        StringBuilder sb = new StringBuilder(length * 3);
        for (int i = 0; i < length && index + i < buf.readableBytes(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", buf.getByte(buf.readerIndex() + index + i)));
        }
        return sb.toString();
    }

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (buf.isReadable()) {
            currentByte = buf.readByte();
            value |= (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) {
                return value;
            }

            position += 7;
            if (position >= 32) {
                throw new IllegalArgumentException("VarInt too big");
            }
        }

        throw new IllegalArgumentException("VarInt not complete");
    }
}
