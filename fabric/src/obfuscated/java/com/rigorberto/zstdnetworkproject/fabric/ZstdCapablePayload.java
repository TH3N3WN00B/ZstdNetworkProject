package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketDecoder;
import net.minecraft.network.codec.ValueFirstEncoder;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Play-phase capability payload. Paper servers reach the client in the play phase via plugin
 * messages, but Paper only forwards a plugin message for channels the client advertised through
 * its play-phase {@code minecraft:register}. Registering this payload as a real C2S/S2C custom
 * payload makes the client advertise the channel and lets it answer the server's capability
 * probe. The wire format is raw: the payload writes its bytes with no length prefix, mirroring
 * how the server encodes plugin messages ({@code DiscardedPayload(id, byte[])} writes the bytes
 * verbatim).
 */
public record ZstdCapablePayload(byte[] data) implements CustomPayload {

    public static final CustomPayload.Id<ZstdCapablePayload> TYPE =
            new CustomPayload.Id<>(Identifier.tryParse(ZstdNegotiation.CHANNEL));

    public static final PacketCodec<ByteBuf, ZstdCapablePayload> CODEC = PacketCodec.of(
            (ValueFirstEncoder<ByteBuf, ZstdCapablePayload>) (payload, buf) -> buf.writeBytes(payload.data),
            (PacketDecoder<ByteBuf, ZstdCapablePayload>) buf -> {
                byte[] raw = new byte[buf.readableBytes()];
                buf.readBytes(raw);
                return new ZstdCapablePayload(raw);
            });

    @Override
    public CustomPayload.Id<ZstdCapablePayload> getId() {
        return TYPE;
    }
}