package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLLoader;
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
        LOGGER.info("Enabling alpha zstd packet compression for NeoForge");
        if (FMLLoader.getCurrent().getDist() == Dist.CLIENT) {
            NeoForge.EVENT_BUS.register(new ZstdNeoForgeClient());
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
