package com.rigorberto.zstdnetworkproject.forge;

import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the play-phase capability payload on a Forge payload channel whose name matches the
 * {@code zstdnetworkproject:capable} channel. Registering it as a real custom payload makes the
 * Forge client advertise the channel over {@code minecraft:register} in the play phase, so a
 * vanilla Paper server can probe it as it does for Fabric clients.
 *
 * <p>This is best-effort: Forge's channel machinery is designed for Forge↔Forge negotiation, so
 * interop with a vanilla peer is not guaranteed. Any failure here only disables the capability
 * handshake; the core Netty injection still works.
 */
public final class ZstdForgeNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");
    private static Channel<CustomPacketPayload> channel;

    private ZstdForgeNetwork() {
    }

    static void register() {
        if (channel != null) {
            return;
        }
        try {
            channel = ChannelBuilder.named(ZstdNegotiation.CHANNEL)
                    .networkProtocolVersion(ZstdNegotiation.PROTOCOL_VERSION)
                    .optional()
                    .payloadChannel()
                    .play()
                    .bidirectional()
                    .add(ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC, ZstdForgeNetwork::handle)
                    .build();
        } catch (Throwable t) {
            LOGGER.warn("Failed to register zstd capability channel (capability handshake disabled): {}", t.toString());
        }
    }

    private static void handle(ZstdCapablePayload payload, CustomPayloadEvent.Context context) {
        context.setPacketHandled(true);
        if (context.isClientSide()) {
            ZstdForgeClient.onPlayQuery(payload.data(), context.getConnection());
        }
    }

    static void sendToServer(Connection connection, ZstdCapablePayload payload) {
        Channel<CustomPacketPayload> ch = channel;
        if (ch == null) {
            return;
        }
        try {
            ch.send(payload, connection);
        } catch (Throwable t) {
            LOGGER.debug("Failed to send zstd capability response", t);
        }
    }
}
