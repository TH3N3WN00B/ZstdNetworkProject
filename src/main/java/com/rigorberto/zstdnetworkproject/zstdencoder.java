package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {
    private final int compressionLevel = 3;

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int readable = msg.readableBytes();
        // Lógica de umbral (threshold) de compresión de Minecraft
        if (readable < 256) { // Ejemplo de threshold
            writeVarInt(out, 0);
            out.writeBytes(msg);
            return;
        }

        long maxCompressedLength = Zstd.compressBound(readable);
        out.ensureWritable((int) maxCompressedLength + 5); // +5 para el VarInt

        writeVarInt(out, readable);

        int compressedSize = (int) Zstd.compressByteArray(
                out.array(), out.arrayOffset() + out.writerIndex(),
                msg.array(), msg.arrayOffset() + msg.readerIndex(), readable, compressionLevel
        );

        out.writerIndex(out.writerIndex() + compressedSize);
    }
}