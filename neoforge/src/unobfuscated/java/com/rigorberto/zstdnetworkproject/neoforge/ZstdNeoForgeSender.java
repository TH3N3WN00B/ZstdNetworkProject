package com.rigorberto.zstdnetworkproject.neoforge;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client-to-server payload send, isolated because the entry point moved between eras:
 * {@code PacketDistributor.sendToServer} in 21.x, {@code ClientPacketDistributor.sendToServer} in
 * 26.x. Each build compiles only the variant from its own era source directory.
 */
final class ZstdNeoForgeSender {

    private ZstdNeoForgeSender() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}
