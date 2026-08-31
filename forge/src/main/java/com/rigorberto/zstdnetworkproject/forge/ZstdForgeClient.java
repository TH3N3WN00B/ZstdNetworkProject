package com.rigorberto.zstdnetworkproject.forge;

import com.rigorberto.zstdnetworkproject.ClientPipelineInjector;
import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side handler: installs the Zstd pipeline on every connection (staying passive until the
 * remote end is observed sending Zstd, so modded clients keep working with vanilla servers) and
 * registers the play-phase capability payload so a vanilla Paper server can detect zstd support.
 */
public class ZstdForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");
    private static final ZstdSettings SETTINGS = loadConfig();

    public ZstdForgeClient() {
        String blockingMod = SETTINGS.findLoadedAutoDisableMod(ModList::isLoaded);
        if (blockingMod != null) {
            LOGGER.info("ZstdNetworkProject stays passive because '{}' is installed (auto-disable-mods config)", blockingMod);
            return;
        }
        ZstdForgeNetwork.register();
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(ZstdForgeClient::onLoggingIn);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ZstdNative.isAvailable()) {
            return; // Native library missing on this platform: never inject zstd handlers.
        }
        try {
            Connection connection = event.getConnection();
            Object channelValue = ReflectionUtil.getFieldValue(connection, "channel");
            if (channelValue instanceof Channel channel) {
                if (SETTINGS.isServerDisabled(ClientPipelineInjector.remoteAddress(channel))) {
                    LOGGER.info("ZstdNetworkProject is disabled for this server (disabled-servers config)");
                    return;
                }
                channel.eventLoop().execute(() -> PipelineInjector.injectClient(channel, SETTINGS));
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inject Zstd handlers on client", e);
            ErrorLogger.log(ZstdNetworkProjectForge.configDir().resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers on client", e);
        }
    }

    /**
     * Called from {@link ZstdForgeNetwork} when the remote end probes our zstd capability. If this
     * is a client connection, answer the probe so a vanilla Paper server enables zstd for us.
     */
    static void onPlayQuery(byte[] data, Connection connection) {
        if (!ZstdNative.isAvailable()) {
            return; // NAK: no zstd native.
        }
        try {
            Channel channel = ClientPipelineInjector.getChannel(connection);
            if (channel == null || SETTINGS.isServerDisabled(ClientPipelineInjector.remoteAddress(channel))) {
                return;
            }
            int serverLevel = ZstdNegotiation.extractCompressionLevel(data, -1);
            if (serverLevel >= 0) {
                // mark the remote as zstd-capable so the encoder may switch over.
                ZstdCapability.markZstdObserved(channel);
            }
            ZstdForgeNetwork.sendToServer(connection, new ZstdCapablePayload(
                    ZstdNegotiation.responsePayload(SETTINGS.effectiveCompressionLevel())));
        } catch (Exception e) {
            LOGGER.debug("Failed to answer zstd capability probe", e);
        }
    }

    private static ZstdSettings loadConfig() {
        try {
            return ConfigLoader.load(ZstdNetworkProjectForge.configDir().resolve("config.yml"));
        } catch (Exception e) {
            LOGGER.warn("Failed to load config.yml, using defaults: {}", e.getMessage());
            return new ZstdSettings();
        }
    }
}
