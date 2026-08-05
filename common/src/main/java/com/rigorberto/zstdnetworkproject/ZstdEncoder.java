package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {
    private static final int COMPRESSION_LEVEL = 3;
    private static final int COMPRESSION_THRESHOLD = 256;

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int readable = msg.readableBytes();

        if (readable < COMPRESSION_THRESHOLD) {
            writeVarInt(out, 0);
            out.writeBytes(msg);
            return;
        }

        long maxCompressedLength = Zstd.compressBound(readable);
        out.ensureWritable((int) maxCompressedLength + 5);

        int varIntStartIndex = out.writerIndex();
        writeVarInt(out, readable);
        int varIntLength = out.writerIndex() - varIntStartIndex;

        int compressedSize;
        if (msg.hasArray() && out.hasArray()) {
            compressedSize = (int) Zstd.compressByteArray(
                    msg.array(), msg.arrayOffset() + msg.readerIndex(), readable,
                    out.array(), out.arrayOffset() + out.writerIndex(), (int) maxCompressedLength, COMPRESSION_LEVEL
            );
        } else {
            byte[] input = new byte[readable];
            msg.getBytes(msg.readerIndex(), input);

            byte[] compressed = new byte[(int) maxCompressedLength];
            compressedSize = (int) Zstd.compressByteArray(input, 0, readable, compressed, 0, (int) maxCompressedLength, COMPRESSION_LEVEL);

            out.writeBytes(compressed, 0, compressedSize);
        }

        out.writerIndex(out.writerIndex() + compressedSize);
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}