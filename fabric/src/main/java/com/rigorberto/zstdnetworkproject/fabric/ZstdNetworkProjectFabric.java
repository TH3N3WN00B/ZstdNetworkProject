package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ZstdNetworkProjectFabric implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");

    @Override
    public void onInitialize() {
        ZstdSettings settings = loadConfig();
        ZstdFabricJoinHook.register(settings);
        StartupBanner.print();
    }

    private static ZstdSettings loadConfig() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            return ConfigLoader.load(configDir.resolve("zstdnetworkproject").resolve("config.yml"));
        } catch (Exception e) {
            LOGGER.warn("Failed to load config.yml, using defaults: {}", e.getMessage());
            return new ZstdSettings();
        }
    }
}
