package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdOverlayStats;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

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
        ZstdOverlayStats.setEnabled(settings.isDebugOverlay());
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject");
        StatsLogger.start(configDir.resolve("zstd-stats.log"), settings.effectiveCompressionLevel());
        ClientLoginConnectionEvents.INIT.register(ZstdFabricClient::onLoginInit);
        ClientLoginNetworking.registerGlobalReceiver(
                Identifier.tryParse(ZstdNegotiation.CHANNEL), ZstdFabricClient::onLoginQuery);
        ClientConfigurationConnectionEvents.INIT.register(ZstdFabricClient::onConfigurationInit);
        ClientPlayConnectionEvents.JOIN.register(ZstdFabricClient::onJoin);
        if (settings.isDebugOverlay()) {
            HudElementRegistry.addLast(Identifier.tryParse("zstdnetworkproject:debug_overlay"),
                    ZstdFabricClient::renderOverlay);
        }
    }

    private static void onLoginInit(ClientHandshakePacketListenerImpl handler, Minecraft client) {
        negotiated = false;
        ZstdOverlayStats.resetConnection();
    }

    /**
     * Answers the proxy's capability probe. Our proxy switches to zstd only after receiving this
     * response, so answering means the server-side peer will be speaking zstd.
     */
    private static CompletableFuture<FriendlyByteBuf> onLoginQuery(Minecraft client,
                                                                   ClientHandshakePacketListenerImpl handler,
                                                                   FriendlyByteBuf buf,
                                                                   Consumer<ChannelFutureListener> sender) {
        if (!ZstdNative.isAvailable()) {
            return null; // NAK: no zstd native on this platform, keep vanilla zlib.
        }
        negotiated = true;
        return CompletableFuture.completedFuture(
                new FriendlyByteBuf(Unpooled.wrappedBuffer(ZstdNegotiation.queryPayload())));
    }

    private static void onConfigurationInit(ClientConfigurationPacketListenerImpl handler, Minecraft client) {
        tryInject(handler, ClientPipelineInjector::injectDecoder);
    }

    private static void onJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        if (negotiated) {
            try {
                Object connection = ClientPipelineInjector.getConnection(handler);
                ZstdCapability.markZstdObserved(ClientPipelineInjector.getChannel(connection));
            } catch (Exception ignored) {
            }
        }
        tryInject(handler, ClientPipelineInjector::inject);
    }

    private static void tryInject(Object handler, ClientPipelineInjector.Injector injection) {
        if (!ZstdNative.isAvailable()) {
            return;
        }
        try {
            Object connection = ClientPipelineInjector.getConnection(handler);
            if (connection != null && injection.apply(connection, settings)) {
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

    /**
     * Draws zstd statistics right above the vanilla F3+3 bandwidth chart (bottom-left, same spot
     * where the chart stacks its min/avg/max labels). Mirrors the vanilla visibility rules: only
     * while the network charts are shown and only for remote servers.
     */
    private static void renderOverlay(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null || client.isLocalServer()) {
            return;
        }
        if (client.getDebugOverlay() == null || !client.getDebugOverlay().showNetworkCharts()) {
            return;
        }
        Font font = client.font;
        int y = graphics.guiHeight() - 87;
        for (String line : ZstdOverlayStats.overlayLines()) {
            int width = font.width(line);
            graphics.fill(1, y - 1, width + 3, y + 8, 0x90202020);
            graphics.text(font, line, 2, y, 0xFFE0E0E0, false);
            y -= 9;
        }
    }
}
