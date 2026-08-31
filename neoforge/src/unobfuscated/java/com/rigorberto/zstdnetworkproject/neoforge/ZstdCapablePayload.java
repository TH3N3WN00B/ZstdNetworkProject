package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Play-phase capability payload, the NeoForge counterpart of the Fabric one. A client sends it to
 * announce that it can decode zstd; the server then allows its encoder to switch away from vanilla
 * zlib for that connection. The wire format is raw bytes with no length prefix, matching how plugin
 * messages are encoded on the Paper side.
 */
public record ZstdCapablePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZstdCapablePayload> TYPE =
            new CustomPacketPayload.Type<>(Objects.requireNonNull(
                    Identifier.tryParse(ZstdNegotiation.CHANNEL),
                    "invalid zstd capability channel id: " + ZstdNegotiation.CHANNEL));

    public static final StreamCodec<ByteBuf, ZstdCapablePayload> CODEC = CustomPacketPayload.codec(
            (ZstdCapablePayload payload, ByteBuf buf) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] raw = new byte[buf.readableBytes()];
                buf.readBytes(raw);
                return new ZstdCapablePayload(raw);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
