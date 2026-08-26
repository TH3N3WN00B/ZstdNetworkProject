package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.HexDump;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdOverlayStats;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.RenderTickCounter;
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
        String blockingMod = settings.findLoadedAutoDisableMod(FabricLoader.getInstance()::isModLoaded);
        if (blockingMod != null) {
            ZstdNetworkProjectFabric.LOGGER.info(
                    "ZstdNetworkProject stays passive because '{}' is installed (auto-disable-mods config)", blockingMod);
            return;
        }
        ZstdOverlayStats.setEnabled(settings.isDebugOverlay());
        ZstdOverlayStats.setClientCompressionLevel(settings.effectiveCompressionLevel());
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("zstdnetworkproject");
        HexDump.configure(configDir.resolve("zstd-hexdump.log"), settings.isHexDump());
        StatsLogger.start(configDir.resolve("zstd-stats.log"), settings.effectiveCompressionLevel());
        ClientLoginConnectionEvents.INIT.register(ZstdFabricClient::onLoginInit);
        ClientLoginNetworking.registerGlobalReceiver(
                Identifier.tryParse(ZstdNegotiation.CHANNEL), ZstdFabricClient::onLoginQuery);
        ClientConfigurationConnectionEvents.INIT.register(ZstdFabricClient::onConfigurationInit);
        ClientPlayConnectionEvents.JOIN.register(ZstdFabricClient::onJoin);
        if (settings.isDebugOverlay()) {
            HudRenderCallback.EVENT.register(ZstdFabricClient::renderOverlay);
        }
    }

    private static void onLoginInit(ClientLoginNetworkHandler handler, MinecraftClient client) {
        negotiated = false;
        ZstdOverlayStats.resetConnection();
    }

    /**
     * Answers the proxy's capability probe. Our proxy switches to zstd only after receiving this
     * response, so answering means the server-side peer will be speaking zstd.
     */
    private static CompletableFuture<PacketByteBuf> onLoginQuery(MinecraftClient client,
                                                                  ClientLoginNetworkHandler handler,
                                                                  PacketByteBuf buf,
                                                                  Consumer<?> sender) {
        if (!ZstdNative.isAvailable() || isDisabled(handler)) {
            return null; // NAK: no zstd native on this platform, or this server must stay vanilla.
        }
        negotiated = true;
        byte[] payload = null;
        if (buf != null && buf.isReadable()) {
            payload = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), payload);
        }
        int serverLevel = ZstdNegotiation.extractCompressionLevel(payload, -1);
        if (serverLevel >= 0) {
            ZstdOverlayStats.setServerCompressionLevel(serverLevel);
        }
        return CompletableFuture.completedFuture(
                new PacketByteBuf(Unpooled.wrappedBuffer(ZstdNegotiation.responsePayload(settings.effectiveCompressionLevel()))));
    }

    /** True when the remote address matches the disabled-servers config: stay fully passive. */
    private static boolean isDisabled(Object handler) {
        try {
            Channel channel = ClientPipelineInjector.getChannel(ClientPipelineInjector.getConnection(handler));
            if (channel == null) {
                return false;
            }
            boolean disabled = settings.isServerDisabled(ClientPipelineInjector.remoteAddress(channel));
            if (disabled) {
                ZstdNetworkProjectFabric.LOGGER.info("ZstdNetworkProject is disabled for this server (disabled-servers config)");
            }
            return disabled;
        } catch (Exception e) {
            return false;
        }
    }

    private static void onConfigurationInit(ClientConfigurationNetworkHandler handler, MinecraftClient client) {
        if (isDisabled(handler)) {
            return;
        }
        tryInject(handler, ClientPipelineInjector::injectDecoder);
    }

    private static void onJoin(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client) {
        if (isDisabled(handler)) {
            return;
        }
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

    /**
     * Draws zstd statistics right above the vanilla F3+3 packet-size chart (bottom-left, same spot
     * where the chart stacks its min/avg/max labels). Mirrors the vanilla visibility rules: only
     * while the packet size and ping charts are shown and only for remote servers.
     */
    private static void renderOverlay(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.isInSingleplayer()) {
            return;
        }
        if (!client.inGameHud.getDebugHud().shouldShowPacketSizeAndPingCharts()) {
            return;
        }
        TextRenderer font = client.textRenderer;
        int y = client.getWindow().getScaledHeight() - 87;
        for (String line : ZstdOverlayStats.overlayLines()) {
            int width = font.getWidth(line);
            context.fill(1, y - 1, width + 3, y + 8, 0x90202020);
            context.drawText(font, line, 2, y, 0xFFE0E0E0, false);
            y -= 9;
        }
    }
}
