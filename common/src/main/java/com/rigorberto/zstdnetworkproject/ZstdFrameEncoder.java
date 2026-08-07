package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Encoder that writes the Minecraft VarInt frame length, the uncompressed-size VarInt and then
 * the zstd-compressed payload. This replaces merged compression+length handlers (such as
 * Velocity's {@code MinecraftCompressorAndLengthEncoder}) on proxies, where the client's
 * frame decoder has already been removed from the outbound path.
 */
public class ZstdFrameEncoder extends MessageToByteEncoder<ByteBuf> {
    private static final int COMPRESSION_THRESHOLD = 256;

    public static final LongAdder PACKETS_COMPRESSED = new LongAdder();
    public static final LongAdder INPUT_BYTES = new LongAdder();
    public static final LongAdder OUTPUT_BYTES = new LongAdder();

    private final int compressionLevel;

    public ZstdFrameEncoder(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int uncompressed = msg.readableBytes();
        INPUT_BYTES.add(uncompressed);

        if (uncompressed < COMPRESSION_THRESHOLD) {
            ZstdEncoder.writeVarInt(out, uncompressed + 1);
            out.writeByte(0);
            out.writeBytes(msg);
            OUTPUT_BYTES.add(out.readableBytes());
            return;
        }

        PACKETS_COMPRESSED.increment();

        byte[] input = new byte[uncompressed];
        msg.getBytes(msg.readerIndex(), input);

        byte[] compressed = Zstd.compress(input, compressionLevel);
        int compressedSize = compressed.length;

        int sizeVarIntLength = varIntLength(uncompressed);
        int frameLength = sizeVarIntLength + compressedSize;

        ZstdEncoder.writeVarInt(out, frameLength);
        ZstdEncoder.writeVarInt(out, uncompressed);
        out.writeBytes(compressed, 0, compressedSize);

        OUTPUT_BYTES.add(frameLength);
    }

    private static int varIntLength(int value) {
        int length = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            length++;
        }
        return length;
    }
}
