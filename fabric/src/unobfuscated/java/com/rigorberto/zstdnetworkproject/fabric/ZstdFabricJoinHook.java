package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public final class ZstdFabricJoinHook {

    private static ZstdSettings settings = new ZstdSettings();

    private ZstdFabricJoinHook() {
    }

    public static void register(ZstdSettings settings) {
        ZstdFabricJoinHook.settings = settings;
        ServerPlayConnectionEvents.JOIN.register(ZstdFabricJoinHook::onJoin);
    }

    private static void onJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        try {
            Object connection = ReflectionUtil.getFieldValue(handler, "connection");
            if (connection == null) {
                return;
            }
            Object channelValue = ReflectionUtil.getFieldValue(connection, "channel");
            if (channelValue instanceof Channel channel) {
                PipelineInjector.injectClient(channel, settings);
            }
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to inject Zstd handlers", e);
            ErrorLogger.log(FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers", e);
        }
    }
}
