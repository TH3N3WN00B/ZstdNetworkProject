package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdOverlayStats;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ZstdNeoForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");
    private final ZstdSettings settings;

    /**
     * @param settings the instance the mod container already parsed; re-reading config.yml here
     *                 would parse the same file a second time into an object that can disagree with
     *                 the server-side one (the loader rewrites the file when its version is stale)
     */
    public ZstdNeoForgeClient(ZstdSettings settings) {
        this.settings = settings;
        ZstdOverlayStats.setEnabled(settings.isDebugOverlay());
        ZstdOverlayStats.setClientCompressionLevel(settings.effectiveCompressionLevel());
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject");
        StatsLogger.start(configDir.resolve("zstd-stats.log"), settings.effectiveCompressionLevel());
        NeoForge.EVENT_BUS.register(this);
        if (settings.isDebugOverlay()) {
            registerOverlayListener();
        }
    }

    /**
     * The F3+3 overlay must call render APIs that differ between Minecraft eras (GuiGraphics vs
     * GuiGraphicsExtractor), so each build ships exactly one implementation in an era-specific
     * source directory and it is loaded reflectively here.
     */
    private static void registerOverlayListener() {
        boolean modern = ReflectionUtil.classExists("net.minecraft.client.gui.GuiGraphicsExtractor");
        String className = modern
                ? "com.rigorberto.zstdnetworkproject.neoforge.ZstdNeoForgeOverlayModern"
                : "com.rigorberto.zstdnetworkproject.neoforge.ZstdNeoForgeOverlayLegacy";
        try {
            NeoForge.EVENT_BUS.register(Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to register zstd debug overlay listener", e);
        }
    }

    /**
     * Tells the server this client can decode zstd. Both encoders refuse to send zstd until their
     * peer is known to speak it, so without this announcement a modded client and a modded server
     * would each sit on vanilla zlib waiting for the other to go first. NeoForge negotiates
     * channels during the configuration phase, so by the time we are logging in {@code hasChannel}
     * already knows whether the server carries this mod.
     */
    private void announceCapability() {
        try {
            Minecraft client = Minecraft.getInstance();
            ClientPacketListener listener = client.getConnection();
            if (listener == null || !listener.hasChannel(ZstdCapablePayload.TYPE)) {
                return; // Vanilla server (or one without the mod): stay on zlib, say nothing.
            }
            ZstdNeoForgeSender.sendToServer(new ZstdCapablePayload(
                    ZstdNegotiation.responsePayload(settings.effectiveCompressionLevel())));
        } catch (Exception e) {
            LOGGER.debug("Failed to announce zstd support", e);
        }
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ZstdOverlayStats.resetConnection();
        if (!ZstdNative.isAvailable()) {
            return; // Native library missing on this platform: never inject zstd handlers.
        }
        try {
            Connection connection = event.getConnection();
            Object channelValue = ReflectionUtil.getFieldValue(connection, "channel");
            if (channelValue instanceof Channel channel) {
                if (settings.isServerDisabled(ClientPipelineInjector.remoteAddress(channel))) {
                    LOGGER.info("ZstdNetworkProject is disabled for this server (disabled-servers config)");
                    return;
                }
                channel.eventLoop().execute(() -> PipelineInjector.injectClient(channel, settings));
                announceCapability();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inject Zstd handlers on client", e);
            ErrorLogger.log(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers on client", e);
        }
    }
}
