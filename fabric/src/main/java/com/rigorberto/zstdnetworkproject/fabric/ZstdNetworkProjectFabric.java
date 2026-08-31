package com.rigorberto.zstdnetworkproject.fabric;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ZstdNetworkProjectFabric implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");

    /**
     * Parsed once here and shared with the client entrypoint. Fabric runs {@code main} entrypoints
     * before {@code client} ones, so {@link ZstdFabricClient} can read this instead of parsing
     * config.yml a second time into a separate object that could disagree with this one.
     */
    private static ZstdSettings settings = new ZstdSettings();

    public static ZstdSettings settings() {
        return settings;
    }

    @Override
    public void onInitialize() {
        settings = loadConfig();
        // Before any passive-mode guard: the client entrypoint registers a receiver for
        // this payload type and Fabric rejects one whose type was never registered.
        ZstdFabricJoinHook.registerPayloads();
        String blockingMod = settings.findLoadedAutoDisableMod(FabricLoader.getInstance()::isModLoaded);
        if (blockingMod != null) {
            LOGGER.info("ZstdNetworkProject stays passive because '{}' is installed (auto-disable-mods config)",
                    blockingMod);
            return;
        }
        if (!ZstdNative.isAvailable()) {
            LOGGER.warn("zstd native library is not available on this platform ({} {}); "
                            + "staying on vanilla zlib compression.",
                    System.getProperty("os.name", "unknown"), System.getProperty("os.arch", "unknown"));
            return;
        }
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
