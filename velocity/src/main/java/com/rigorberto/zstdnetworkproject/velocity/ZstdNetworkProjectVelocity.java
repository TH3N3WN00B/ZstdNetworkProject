package com.rigorberto.zstdnetworkproject.velocity;

import com.google.inject.Inject;
import com.rigorberto.zstdnetworkproject.ZstdEncoder;
import com.rigorberto.zstdnetworkproject.ZstdDecoder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

@Plugin(
    id = "zstdnetworkproject",
    name = "ZstdNetworkProject",
    version = "1.0-SNAPSHOT",
    description = "Zstd packet compressor for Velocity proxy (compatible with Krypton & PacketFixer)",
    authors = {"Rigorberto"}
)
public class ZstdNetworkProjectVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    // Reflection fields/methods for accessing internal Velocity classes
    private static Field connectionField;
    private static Method getChannelMethod;

    static {
        try {
            // Try to find the internal ConnectedPlayer class
            Class<?> connectedPlayerClass = Class.forName("com.velocitypowered.proxy.connection.client.ConnectedPlayer");
            connectionField = connectedPlayerClass.getDeclaredField("connection");
            connectionField.setAccessible(true);

            Class<?> minecraftConnectionClass = Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection");
            getChannelMethod = minecraftConnectionClass.getDeclaredMethod("getChannel");
            getChannelMethod.setAccessible(true);
        } catch (Exception e) {
            // Reflection setup failed - will fallback to no-op
        }
    }

    @Inject
    public ZstdNetworkProjectVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("ZstdNetworkProject Velocity plugin initialized! (Compatible with Krypton & PacketFixer)");
    }

    @Subscribe
    public void onPlayerPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        try {
            replacePipeline(player);
        } catch (Exception e) {
            logger.debug("Failed to replace pipeline for player {}: {}", player.getUsername(), e.getMessage());
        }
    }

    private void replacePipeline(Player player) throws Exception {
        if (connectionField == null || getChannelMethod == null) {
            return; // Reflection not available
        }

        Object connection = connectionField.get(player);
        if (connection == null) {
            return;
        }

        Channel channel = (Channel) getChannelMethod.invoke(connection);
        if (channel == null) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();

        // Check if compression is already handled by Krypton or PacketFixer
        // Krypton uses "compress" and "decompress" handler names
        // PacketFixer might also use similar names
        if (pipeline.get("compress") != null) {
            pipeline.replace("compress", "compress", new ZstdEncoder());
            logger.debug("Replaced 'compress' handler with ZstdEncoder for {}", player.getUsername());
        }
        if (pipeline.get("decompress") != null) {
            pipeline.replace("decompress", "decompress", new ZstdDecoder());
            logger.debug("Replaced 'decompress' handler with ZstdDecoder for {}", player.getUsername());
        }
    }
}