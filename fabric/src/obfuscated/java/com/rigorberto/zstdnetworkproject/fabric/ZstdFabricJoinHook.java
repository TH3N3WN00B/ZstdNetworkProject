package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;

public final class ZstdFabricJoinHook {

    private static ZstdSettings settings = new ZstdSettings();

    private ZstdFabricJoinHook() {
    }

    /**
     * Registers the capability payload codecs. Called unconditionally from the shared entrypoint,
     * before any of the passive-mode guards, because the client entrypoint registers a receiver for
     * this type and Fabric rejects a receiver whose payload type is unknown.
     */
    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC);
    }

    public static void register(ZstdSettings settings) {
        ZstdFabricJoinHook.settings = settings;
        ServerPlayNetworking.registerGlobalReceiver(ZstdCapablePayload.TYPE,
                ZstdFabricJoinHook::onCapabilityAnnounced);
        ServerPlayConnectionEvents.JOIN.register(ZstdFabricJoinHook::onJoin);
    }

    /**
     * A modded client told us it can decode zstd, so this connection may switch to it.
     *
     * <p>Without this the connection deadlocks: both encoders are installed with
     * {@code peerZstdRequired}, so a modded server and a modded client would each keep sending
     * vanilla zlib while waiting for the other to send zstd first.
     */
    private static void onCapabilityAnnounced(ZstdCapablePayload payload,
                                              ServerPlayNetworking.Context context) {
        if (!ZstdNative.isAvailable()) {
            return; // No native library here: we could not produce zstd frames anyway.
        }
        try {
            ZstdCapability.markZstdObserved(context.player().networkHandler.connection.channel);
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to mark client as zstd-capable", e);
        }
    }

    private static void onJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        if (!ZstdNative.isAvailable()) {
            return; // Never install handlers we cannot actually run; peers stay on vanilla zlib.
        }
        try {
            // ServerPlayNetworkHandler.connection (inherited from ServerCommonNetworkHandler) -> ClientConnection.channel
            PipelineInjector.injectClient(handler.connection.channel, settings);
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to inject Zstd handlers", e);
            ErrorLogger.log(FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers", e);
        }
    }
}
