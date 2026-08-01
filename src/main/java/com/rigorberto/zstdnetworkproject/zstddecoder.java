package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public class ZstdDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) return;

        // Leer el tamaño del paquete descomprimido (VarInt)
        // Lógica estándar de Minecraft para leer VarInt aquí...
        int uncompressedSize = readVarInt(in);

        if (uncompressedSize == 0) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        ByteBuf decompressed = ctx.alloc().directBuffer(uncompressedSize);
        try {
            // Descomprimir usando Zstd de forma nativa
            Zstd.decompressByteArray(
                    decompressed.array(), decompressed.arrayOffset() + decompressed.readerIndex(), uncompressedSize,
                    in.array(), in.arrayOffset() + in.readerIndex(), in.readableBytes()
            );
            decompressed.writerIndex(uncompressedSize);
            out.add(decompressed);
            in.skipBytes(in.readableBytes());
        } catch (Exception e) {
            decompressed.release();
            throw e;
        }
    }

    // Implementación de readVarInt omitida por brevedad
}