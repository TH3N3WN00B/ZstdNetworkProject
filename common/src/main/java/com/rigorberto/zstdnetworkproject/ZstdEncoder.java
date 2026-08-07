package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.concurrent.atomic.LongAdder;

public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {
    private static final int COMPRESSION_THRESHOLD = 256;

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

    private final int compressionLevel;

    public ZstdEncoder() {
        this(3);
    }

    public ZstdEncoder(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int readable = msg.readableBytes();
        INPUT_BYTES.add(readable);

        if (readable < COMPRESSION_THRESHOLD) {
            writeVarInt(out, 0);
            out.writeBytes(msg);
            OUTPUT_BYTES.add(out.readableBytes());
            return;
        }

        PACKETS_COMPRESSED.increment();

        byte[] input = new byte[readable];
        msg.getBytes(msg.readerIndex(), input);

        byte[] compressed = Zstd.compress(input, compressionLevel);
        int compressedSize = compressed.length;

        int varIntStartIndex = out.writerIndex();
        writeVarInt(out, readable);
        int varIntLength = out.writerIndex() - varIntStartIndex;

        out.writeBytes(compressed);

        OUTPUT_BYTES.add(varIntLength + compressedSize);
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
