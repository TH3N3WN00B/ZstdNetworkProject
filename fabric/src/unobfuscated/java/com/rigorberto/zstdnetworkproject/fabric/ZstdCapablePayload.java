package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Play-phase capability payload. Paper servers reach the client in the play phase via plugin
 * messages, but Paper only forwards a plugin message for channels the client advertised through
 * its play-phase {@code minecraft:register}. Registering this payload as a real C2S/S2C custom
 * payload makes the client advertise the channel and lets it answer the server's capability
 * probe. The wire format is raw: the payload writes its bytes with no length prefix, mirroring
 * how the server encodes plugin messages ({@code DiscardedPayload(id, byte[])} writes the bytes
 * verbatim).
 */
public record ZstdCapablePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZstdCapablePayload> TYPE =
            new CustomPacketPayload.Type<>(channelId());

    public static final StreamCodec<ByteBuf, ZstdCapablePayload> CODEC = CustomPacketPayload.codec(
            (ZstdCapablePayload payload, ByteBuf buf) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] raw = new byte[buf.readableBytes()];
                buf.readBytes(raw);
                return new ZstdCapablePayload(raw);
            });

    /** Fails loudly at class-init instead of letting a null id surface as an NPE much later. */
    private static Identifier channelId() {
        return java.util.Objects.requireNonNull(Identifier.tryParse(ZstdNegotiation.CHANNEL),
                "invalid zstd capability channel id: " + ZstdNegotiation.CHANNEL);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}