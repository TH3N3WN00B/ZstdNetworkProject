package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

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
        PayloadTypeRegistry.serverboundPlay().register(ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC);
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
            Channel channel = channelOf(context.player().connection);
            if (channel != null) {
                ZstdCapability.markZstdObserved(channel);
            }
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to mark client as zstd-capable", e);
        }
    }

    private static void onJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        if (!ZstdNative.isAvailable()) {
            return; // Never install handlers we cannot actually run; peers stay on vanilla zlib.
        }
        try {
            Channel channel = channelOf(handler);
            if (channel != null) {
                PipelineInjector.injectClient(channel, settings);
            }
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to inject Zstd handlers", e);
            ErrorLogger.log(FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers", e);
        }
    }

    /** ServerGamePacketListenerImpl.connection (from ServerCommonPacketListenerImpl) -> Connection.channel. */
    private static Channel channelOf(Object packetListener) throws Exception {
        Object connection = ReflectionUtil.getFieldValue(packetListener, "connection");
        if (connection == null) {
            return null;
        }
        Object channelValue = ReflectionUtil.getFieldValue(connection, "channel");
        return channelValue instanceof Channel channel ? channel : null;
    }
}
