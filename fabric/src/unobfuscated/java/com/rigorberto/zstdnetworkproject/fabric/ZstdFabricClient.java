package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.nio.file.Path;

public class ZstdFabricClient implements ClientModInitializer {

    private static ZstdSettings settings = new ZstdSettings();

    @Override
    public void onInitializeClient() {
        settings = loadConfig();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject");
        StatsLogger.start(configDir.resolve("zstd-stats.log"), settings.effectiveCompressionLevel());
        ClientConfigurationConnectionEvents.INIT.register(ZstdFabricClient::onConfigurationInit);
        ClientPlayConnectionEvents.JOIN.register(ZstdFabricClient::onJoin);
    }

    private static void onConfigurationInit(ClientConfigurationPacketListenerImpl handler, Minecraft client) {
        tryInject(handler, ClientPipelineInjector::injectDecoder);
    }

    private static void onJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        tryInject(handler, ClientPipelineInjector::inject);
    }

    private static void tryInject(Object handler, ClientPipelineInjector.Injector injection) {
        try {
            Object connection = ClientPipelineInjector.getConnection(handler);
            if (connection != null && injection.apply(connection, settings)) {
                return;
            }
            ZstdNetworkProjectFabric.LOGGER.debug("No compression handlers replaced on client connection");
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to inject Zstd handlers on client", e);
            ErrorLogger.log(FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers on client", e);
        }
    }

    private static ZstdSettings loadConfig() {
        try {
            return ConfigLoader.load(FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject").resolve("config.yml"));
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.warn("Failed to load config.yml, using defaults: {}", e.getMessage());
            return new ZstdSettings();
        }
    }
}
