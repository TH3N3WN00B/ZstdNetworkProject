package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;

public final class ZstdFabricJoinHook {

    private static ZstdSettings settings = new ZstdSettings();

    private ZstdFabricJoinHook() {
    }

    public static void register(ZstdSettings settings) {
        ZstdFabricJoinHook.settings = settings;
        ServerPlayConnectionEvents.JOIN.register(ZstdFabricJoinHook::onJoin);
    }

    private static void onJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
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
