package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.zip.Inflater;

public class ZstdDecoder extends ByteToMessageDecoder {

    private static final ThreadLocal<Inflater> INFLATER_THREAD_LOCAL = ThreadLocal.withInitial(() -> new Inflater(true));

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) return;

        int readerIndex = in.readerIndex();
        int uncompressedSize = readVarInt(in);

        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("Invalid uncompressed size: " + uncompressedSize);
        }

        if (uncompressedSize == 0) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        if (in.readableBytes() == 0) {
            in.readerIndex(readerIndex);
            return;
        }

        ByteBuf decompressed = ctx.alloc().directBuffer(uncompressedSize);
        try {
            int decompressedSize;
            boolean zstdSuccess = false;

            // Try zstd decompression first
            try {
                if (in.hasArray() && decompressed.hasArray()) {
                    decompressedSize = (int) Zstd.decompressByteArray(
                            in.array(), in.arrayOffset() + in.readerIndex(), in.readableBytes(),
                            decompressed.array(), decompressed.arrayOffset() + decompressed.writerIndex(), uncompressedSize
                    );
                } else {
                    byte[] input = new byte[in.readableBytes()];
                    in.getBytes(in.readerIndex(), input);

                    byte[] output = new byte[uncompressedSize];
                    decompressedSize = (int) Zstd.decompressByteArray(input, 0, input.length, output, 0, uncompressedSize);
                    decompressed.writeBytes(output, 0, decompressedSize);
                }

                if (decompressedSize == uncompressedSize) {
                    zstdSuccess = true;
                }
            } catch (Exception e) {
                // zstd failed, will try zlib fallback
            }

            // Fallback to zlib (vanilla) decompression if zstd failed
            if (!zstdSuccess) {
                in.readerIndex(readerIndex);
                uncompressedSize = readVarInt(in); // Re-read varint

                if (uncompressedSize == 0) {
                    out.add(in.readRetainedSlice(in.readableBytes()));
                    return;
                }

                Inflater inflater = INFLATER_THREAD_LOCAL.get();
                inflater.reset();

                byte[] input = new byte[in.readableBytes()];
                in.getBytes(in.readerIndex(), input);

                inflater.setInput(input);
                byte[] output = new byte[uncompressedSize];
                try {
                    decompressedSize = inflater.inflate(output);
                    if (decompressedSize != uncompressedSize) {
                        throw new IllegalStateException("Zlib decompressed size mismatch: expected " + uncompressedSize + ", got " + decompressedSize);
                    }
                    decompressed.writeBytes(output, 0, decompressedSize);
                } catch (Exception e) {
                    throw new IllegalStateException("Both zstd and zlib decompression failed", e);
                }
            }

            out.add(decompressed);
            in.skipBytes(in.readableBytes());
        } catch (Exception e) {
            decompressed.release();
            throw e;
        }
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