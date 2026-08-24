package com.rigorberto.zstdnetworkproject.velocity;

import com.google.inject.Inject;
import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdAsyncPools;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
    id = "zstdnetworkproject",
    name = "ZstdNetworkProject",
    version = "beta-0.8",
    description = "Zstd packet compressor for Velocity proxy (compatible with Krypton & PacketFixer)",
    authors = {"Rigorberto"}
)
public class ZstdNetworkProjectVelocity {

    private static final String CONNECTED_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";

    private static final ChannelIdentifier CAPABILITY_CHANNEL =
            MinecraftChannelIdentifier.from(ZstdNegotiation.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private ZstdSettings settings = new ZstdSettings();

    /**
     * Username -> whether the client confirmed zstd support during the login phase. Populated by the
     * login plugin message callback (velocity guarantees the client answers before login completes)
     * and read by {@link #onPlayerPostLogin}.
     */
    private final Map<String, Boolean> zstdCapable = new ConcurrentHashMap<>();

    // Reflection targets for accessing the player's Netty channel.
    private static Method getConnectionMethod;
    private static Field connectionField;
    private static Method getChannelMethod;

    static {
        try {
            Class<?> connectedPlayerClass = Class.forName(CONNECTED_PLAYER_CLASS);
            try {
                getConnectionMethod = connectedPlayerClass.getMethod("getConnection");
            } catch (NoSuchMethodException e) {
                connectionField = connectedPlayerClass.getDeclaredField("connection");
                connectionField.setAccessible(true);
            }

            Class<?> minecraftConnectionClass = Class.forName(MINECRAFT_CONNECTION_CLASS);
            try {
                getChannelMethod = minecraftConnectionClass.getMethod("getChannel");
            } catch (NoSuchMethodException e) {
                getChannelMethod = minecraftConnectionClass.getDeclaredMethod("getChannel");
                getChannelMethod.setAccessible(true);
            }
        } catch (Exception e) {
            // Reflection setup failed - will fallback to no-op.
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
        settings = loadConfig();
        StartupBanner.print();
        if (!ZstdNative.isAvailable()) {
            String osName = System.getProperty("os.name", "unknown");
            logger.warn("zstd native library is not available on this platform ({} {}); "
                            + "zstd compression is disabled, all players will use vanilla zlib.",
                    osName, System.getProperty("os.arch"));
            if (osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
                logger.warn("On Alpine/musl-based Docker images the bundled zstd-jni natives (glibc) "
                        + "may fail to load: install the 'java-zstd-jni' system package or switch to "
                        + "a glibc-based image to enable zstd.");
            }
            return;
        }
        logger.info("zstd compression enabled: {}, compression-level={}, compression-threshold={} bytes, "
                        + "async-workers={}, async-threshold={} bytes, cpus={}",
                ZstdNative.status(), settings.effectiveCompressionLevel(), settings.getCompressionThreshold(),
                ZstdAsyncPools.workerCount(), ZstdAsyncPools.ASYNC_THRESHOLD,
                Runtime.getRuntime().availableProcessors());
    }

    private ZstdSettings loadConfig() {
        try {
            return ConfigLoader.load(dataDirectory.resolve("config.yml"));
        } catch (IOException e) {
            logger.warn("Failed to load config.yml, using defaults: {}", e.getMessage());
            return new ZstdSettings();
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!ZstdNative.isAvailable()) {
            return; // Native library missing: never advertise zstd, clients stay on zlib.
        }
        InboundConnection connection = event.getConnection();
        if (!(connection instanceof LoginPhaseConnection login)) {
            return;
        }
        String username = event.getUsername();
        login.sendLoginPluginMessage(CAPABILITY_CHANNEL, ZstdNegotiation.queryPayload(),
                response -> zstdCapable.put(username, ZstdNegotiation.isSupportedResponse(response)));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        zstdCapable.remove(event.getPlayer().getUsername());
    }

    @Subscribe
    public void onPlayerPostLogin(PostLoginEvent event) {
        if (!ZstdNative.isAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        Boolean supported = zstdCapable.get(player.getUsername());
        if (supported == null || !supported) {
            return; // Client did not confirm zstd support (vanilla client); keep vanilla zlib.
        }
        try {
            boolean injected = replacePipeline(player);
            if (injected && settings.isDebugMessage()) {
                player.sendMessage(Component.text(
                        "[Zstd] zstd packet compression enabled (proxy level " + settings.effectiveCompressionLevel() + ")",
                        NamedTextColor.GREEN));
            }
        } catch (Exception e) {
            logger.warn("Failed to replace pipeline for player {}: {}", player.getUsername(), e.getMessage());
            ErrorLogger.log(dataDirectory.resolve("zstd-errors.log"),
                    "Failed to enable zstd compression for player " + player.getUsername(), e);
        }
    }

    private boolean replacePipeline(Player player) throws Exception {
        if ((getConnectionMethod == null && connectionField == null) || getChannelMethod == null) {
            return false; // Reflection not available
        }

        Object connection = getConnectionMethod != null
                ? getConnectionMethod.invoke(player)
                : connectionField.get(player);
        if (connection == null) {
            return false;
        }

        Channel channel = (Channel) getChannelMethod.invoke(connection);
        if (channel == null) {
            return false;
        }

        if (channel.eventLoop().inEventLoop()) {
            return inject(channel);
        }
        return channel.eventLoop().submit(() -> inject(channel)).get(2, TimeUnit.SECONDS);
    }

    private boolean inject(Channel channel) {
        return PipelineInjector.inject(channel, settings,
                PipelineInjector.VELOCITY_ENCODER, PipelineInjector.VELOCITY_DECODER, true);
    }
}
