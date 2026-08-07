package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StatsLogger;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
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

    public ZstdNeoForgeClient() {
        settings = loadConfig();
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject");
        StatsLogger.start(configDir.resolve("zstd-stats.log"), settings.effectiveCompressionLevel());
        NeoForge.EVENT_BUS.register(this);
    }

    private static ZstdSettings loadConfig() {
        try {
            return ConfigLoader.load(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("config.yml"));
        } catch (Exception e) {
            LOGGER.warn("Failed to load config.yml, using defaults: {}", e.getMessage());
            return new ZstdSettings();
        }
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            Connection connection = event.getConnection();
            Object channelValue = ReflectionUtil.getFieldValue(connection, "channel");
            if (channelValue instanceof Channel channel) {
                channel.eventLoop().execute(() -> PipelineInjector.inject(channel, settings));
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inject Zstd handlers on client", e);
            ErrorLogger.log(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers on client", e);
        }
    }
}
