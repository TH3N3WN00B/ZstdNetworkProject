package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.HexDump;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("zstdnetworkproject")
public class ZstdNetworkProjectNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");
    private final ZstdSettings settings;

    public ZstdNetworkProjectNeoForge() {
        NeoForge.EVENT_BUS.register(this);
        settings = loadConfig();
        HexDump.configure(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("zstd-hexdump.log"),
                settings.isHexDump());
        StartupBanner.print();
        if (isClientDist()) {
            NeoForge.EVENT_BUS.register(new ZstdNeoForgeClient());
        }
    }

    /**
     * Detects the distribution without linking to a loader API that changed across eras
     * ({@code FMLEnvironment.dist} field in 1.21.4 vs {@code FMLEnvironment.getDist()} in 26.2).
     */
    private static boolean isClientDist() {
        try {
            Class<?> envClass = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist;
            try {
                dist = envClass.getField("dist").get(null);
            } catch (NoSuchFieldException e) {
                dist = envClass.getMethod("getDist").invoke(null);
            }
            return (Boolean) dist.getClass().getMethod("isClient").invoke(dist);
        } catch (Exception e) {
            LOGGER.warn("Failed to detect NeoForge distribution, assuming dedicated server", e);
            return false;
        }
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
    public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
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
            if (channelValue instanceof Channel) {
                PipelineInjector.inject((Channel) channelValue, settings);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inject Zstd handlers", e);
            ErrorLogger.log(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers", e);
        }
    }
}
