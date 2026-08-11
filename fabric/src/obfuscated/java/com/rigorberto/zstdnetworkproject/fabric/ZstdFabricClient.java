package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ZstdFabricClient implements ClientModInitializer {

    private static ZstdSettings settings = new ZstdSettings();

    /**
     * Set when a server sends us the zstd capability login query during this login session, which
     * only our proxy does. Reset whenever a new login starts.
     */
    private static volatile boolean negotiated;

    @Override
    public void onInitializeClient() {
        settings = loadConfig();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject");
        StatsLogger.start(configDir.resolve("zstd-stats.log"), settings.effectiveCompressionLevel());
        ClientLoginConnectionEvents.INIT.register(ZstdFabricClient::onLoginInit);
        ClientLoginNetworking.registerGlobalReceiver(
                Identifier.tryParse(ZstdNegotiation.CHANNEL), ZstdFabricClient::onLoginQuery);
        ClientConfigurationConnectionEvents.INIT.register(ZstdFabricClient::onConfigurationInit);
        ClientPlayConnectionEvents.JOIN.register(ZstdFabricClient::onJoin);
    }

    private static void onLoginInit(ClientLoginNetworkHandler handler, MinecraftClient client) {
        negotiated = false;
    }

    /**
     * Answers the proxy's capability probe. Our proxy switches to zstd only after receiving this
     * response, so answering means the server-side peer will be speaking zstd.
     */
    private static CompletableFuture<PacketByteBuf> onLoginQuery(MinecraftClient client,
                                                                 ClientLoginNetworkHandler handler,
                                                                 PacketByteBuf buf,
                                                                 Consumer<?> sender) {
        if (!ZstdNative.isAvailable()) {
            return null; // NAK: no zstd native on this platform, keep vanilla zlib.
        }
        negotiated = true;
        return CompletableFuture.completedFuture(
                new PacketByteBuf(Unpooled.wrappedBuffer(ZstdNegotiation.queryPayload())));
    }

    private static void onConfigurationInit(ClientConfigurationNetworkHandler handler, MinecraftClient client) {
        tryInject(handler, ClientPipelineInjector::injectDecoder);
    }

    private static void onJoin(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client) {
        if (negotiated && handler.connection != null) {
            ZstdCapability.markZstdObserved(handler.connection.channel);
        }
        tryInject(handler, ClientPipelineInjector::inject);
    }

    private static void tryInject(ClientCommonNetworkHandler handler, ClientPipelineInjector.ChannelInjector injection) {
        if (!ZstdNative.isAvailable()) {
            return;
        }
        try {
            ClientConnection connection = handler.connection;
            Channel channel = connection != null ? connection.channel : null;
            if (channel != null && injection.apply(channel, settings)) {
                return;
            }
            ZstdNetworkProjectFabric.LOGGER.debug("No compression handlers replaced on client connection");
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to inject Zstd handlers on client", e);
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
