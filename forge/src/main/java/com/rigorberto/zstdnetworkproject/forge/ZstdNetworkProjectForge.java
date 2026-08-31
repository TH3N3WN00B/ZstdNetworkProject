package com.rigorberto.zstdnetworkproject.forge;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("zstdnetworkproject")
public class ZstdNetworkProjectForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");
    private static ZstdSettings settings;

    public ZstdNetworkProjectForge(FMLJavaModLoadingContext context) {
        settings = loadConfig();
        StartupBanner.print();
        // Advertise the play-phase capability channel on both sides so a zstd peer (Forge or a
        // vanilla Paper server probing plugins) can detect this side's support.
        ZstdForgeNetwork.register();
        if (isClientDist()) {
            new ZstdForgeClient();
        }
        // Install Zstd handlers on each new player connection (dedicated and integrated server).
        PlayerLoggedInEvent.BUS.addListener(ZstdNetworkProjectForge::onPlayerLoggedIn);
    }

    private static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        try {
            Object connection = ReflectionUtil.getFieldValue(serverPlayer, "connection");
            if (connection == null) {
                return;
            }
            Object nettyConnection = ReflectionUtil.getFieldValue(connection, "connection");
            if (nettyConnection == null) {
                return;
            }
            Object channelValue = ReflectionUtil.getFieldValue(nettyConnection, "channel");
            if (channelValue instanceof Channel channel) {
                PipelineInjector.injectClient(channel, settings);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inject Zstd handlers", e);
            ErrorLogger.log(configDir().resolve("zstd-errors.log"), "Failed to inject Zstd handlers", e);
        }
    }

    static ZstdSettings settings() {
        return settings;
    }

    /**
     * Detects the distribution without linking to a loader API that changed across eras.
     */
    private static boolean isClientDist() {
        try {
            Class<?> envHolder = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment");
            Object dist;
            try {
                dist = envHolder.getField("dist").get(null);
            } catch (NoSuchFieldException e) {
                dist = envHolder.getMethod("getDist").invoke(null);
            }
            return (Boolean) dist.getClass().getMethod("isClient").invoke(dist);
        } catch (Exception e) {
            LOGGER.warn("Failed to detect Forge distribution, assuming dedicated server", e);
            return false;
        }
    }

    static java.nio.file.Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject");
    }

    private static ZstdSettings loadConfig() {
        try {
            return ConfigLoader.load(configDir().resolve("config.yml"));
        } catch (Exception e) {
            LOGGER.warn("Failed to load config.yml, using defaults: {}", e.getMessage());
            return new ZstdSettings();
        }
    }
}
