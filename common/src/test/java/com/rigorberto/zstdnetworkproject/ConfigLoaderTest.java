package com.rigorberto.zstdnetworkproject;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config schema tests: a fresh config file must be generated at the current {@link
 * ConfigLoader#CONFIG_VERSION} including the new opt-in rate-limit settings, which parse into the
 * channel rate limiter wiring without changing any existing default behavior.
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void freshConfigContainsRateLimitKeysAtCurrentVersion() throws Exception {
        Path config = tempDir.resolve("config.yml");
        ZstdSettings settings = ConfigLoader.load(config);

        String text = Files.readString(config);
        assertTrue(text.contains("config-version: " + ConfigLoader.CONFIG_VERSION),
                "Fresh config must be written at the current schema version");
        assertTrue(text.contains("rate-limit-bytes-per-second: 0"),
                "Fresh config must document the per-channel rate limit, off by default");
        assertTrue(text.contains("rate-limit-burst-bytes: 0"),
                "Fresh config must document the burst capacity setting");

        // Defaults: everything off, nothing changes behavior.
        assertEquals(0, settings.getRateLimitBytesPerSecond());
        assertEquals(0, settings.getRateLimitBurstBytes());
        assertTrue(settings.isCompressIfBeneficial(), "defaults must remain unchanged");
        assertTrue(settings.isUseVirtualThreads());
    }

    @Test
    void parsesRateLimitValues() throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config,
                "config-version: " + ConfigLoader.CONFIG_VERSION + "\n"
                        + "rate-limit-bytes-per-second: 5242880\n"
                        + "rate-limit-burst-bytes: 10485760\n");
        ZstdSettings settings = ConfigLoader.load(config);
        assertEquals(5_242_880L, settings.getRateLimitBytesPerSecond());
        assertEquals(10_485_760L, settings.getRateLimitBurstBytes());
    }
}