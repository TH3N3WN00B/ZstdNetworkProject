package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.HexDump;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdOverlayStats;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
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
import java.util.List;
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
        settings = ZstdNetworkProjectFabric.settings();
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
                ZstdCapablePayload.TYPE.id(), ZstdFabricClient::onLoginQuery);
        ClientPlayNetworking.registerGlobalReceiver(ZstdCapablePayload.TYPE, ZstdFabricClient::onPlayQuery);
        ClientConfigurationConnectionEvents.INIT.register(ZstdFabricClient::onConfigurationInit);
        ClientPlayConnectionEvents.JOIN.register(ZstdFabricClient::onJoin);
        ServerboundPlayChannelEvents.REGISTER.register(ZstdFabricClient::onServerChannelsRegistered);
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
        if (!ZstdNative.isAvailable() || isDisabled(handler)) {
            // NAK. Must be a completed future holding null, never a bare null: Fabric API calls
            // thenAccept() on whatever this returns and would NPE on the network thread.
            return CompletableFuture.completedFuture(null);
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
                new FriendlyByteBuf(Unpooled.wrappedBuffer(ZstdNegotiation.responsePayload(settings.effectiveCompressionLevel()))));
    }

    /**
     * Answers the Paper server's play-phase capability probe. Unlike the login probe (which only
     * our proxy sends), this also works against a Paper server without a proxy, which reaches
     * modded clients through the play-phase plugin message channel. Answering keeps the connection
     * on vanilla compression until the server switches to zstd; our frame-sniffing encoder flips
     * automatically once the first zstd frame arrives from the server.
     */
    private static void onPlayQuery(ZstdCapablePayload payload, ClientPlayNetworking.Context context) {
        if (!ZstdNative.isAvailable() || isDisabled(context.client().getConnection())) {
            return; // NAK: no zstd native, or this server must stay vanilla.
        }
        int serverLevel = ZstdNegotiation.extractCompressionLevel(payload.data(), -1);
        if (serverLevel >= 0) {
            ZstdOverlayStats.setServerCompressionLevel(serverLevel);
        }
        ClientPlayNetworking.send(new ZstdCapablePayload(
                ZstdNegotiation.responsePayload(settings.effectiveCompressionLevel())));
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

    private static void onConfigurationInit(ClientConfigurationPacketListenerImpl handler, Minecraft client) {
        if (isDisabled(handler)) {
            return;
        }
        tryInject(handler, ClientPipelineInjector::injectDecoder);
    }

    private static void onJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        if (isDisabled(handler)) {
            return;
        }
        if (negotiated) {
            try {
                Object connection = ClientPipelineInjector.getConnection(handler);
                ZstdCapability.markZstdObserved(ClientPipelineInjector.getChannel(connection));
            } catch (Exception ignored) {
            }
        }
        tryInject(handler, ClientPipelineInjector::inject);
        announceCapability(handler);
    }

    /**
     * The server just told us which plugin-message channels it accepts. If ours is among them the
     * peer runs this mod, so announce zstd support now rather than waiting to be asked.
     */
    private static void onServerChannelsRegistered(ClientPacketListener handler, PacketSender sender,
                                                   Minecraft client, List<Identifier> channels) {
        if (channels.contains(ZstdCapablePayload.TYPE.id())) {
            announceCapability(handler);
        }
    }

    /**
     * Tells the server this client can decode zstd. Both encoders refuse to send zstd until their
     * peer is known to speak it, so without this announcement a modded client and a modded
     * server (Fabric, NeoForge or Paper, with no proxy in between) would each sit on vanilla zlib
     * waiting for the other to go first.
     */
    private static void announceCapability(Object handler) {
        if (!ZstdNative.isAvailable() || isDisabled(handler)) {
            return;
        }
        try {
            if (!ClientPlayNetworking.canSend(ZstdCapablePayload.TYPE)) {
                return; // Vanilla server (or one without the mod): stay on zlib, say nothing.
            }
            ClientPlayNetworking.send(new ZstdCapablePayload(
                    ZstdNegotiation.responsePayload(settings.effectiveCompressionLevel())));
        } catch (Exception e) {
            ZstdNetworkProjectFabric.LOGGER.debug("Failed to announce zstd support", e);
        }
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
