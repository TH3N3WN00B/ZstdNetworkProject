package com.rigorberto.zstdnetworkproject.paper;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.HexDump;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdAsyncPools;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class ZstdNetworkProjectPaper extends JavaPlugin implements Listener, PluginMessageListener, CommandExecutor {

    private ZstdSettings settings = new ZstdSettings();

    /** Cached CraftPlayer#getHandle method; resolved once and reused for every join. */
    private volatile Method getHandleMethod;

    /**
     * Last compression level each player reported in their zstd capability response. Populated when
     * the play-phase plugin message arrives and used by /zstdinfo. Main-thread only.
     */
    private final Map<UUID, Integer> clientZstdLevels = new HashMap<>();

    /** False when the zstd native library could not load: the plugin then stays fully passive. */
    private boolean zstdUsable;

    @Override
    public void onEnable() {
        settings = loadConfig();
        zstdUsable = ZstdNative.isAvailable();
        HexDump.configure(getDataFolder().toPath().resolve("zstd-hexdump.log"), settings.isHexDump());
        if (HexDump.isEnabled()) {
            getLogger().info("hex-dump enabled: frames are being written to " + getDataFolder().toPath().resolve("zstd-hexdump.log"));
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ZstdNegotiation.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, ZstdNegotiation.CHANNEL, this);
        Objects.requireNonNull(getCommand("zstdinfo"), "zstdinfo is not registered in plugin.yml").setExecutor(this);
        StartupBanner.print();
        logEnvironment();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!zstdUsable
                || !ZstdNegotiation.CHANNEL.equals(channel)
                || !ZstdNegotiation.isSupportedResponse(message)) {
            return;
        }
        try {
            clientZstdLevels.put(player.getUniqueId(),
                    ZstdNegotiation.extractCompressionLevel(message, settings.effectiveCompressionLevel()));
            Channel nettyChannel = getNettyChannel(player);
            if (nettyChannel != null) {
                ZstdCapability.markZstdObserved(nettyChannel);
            }
            if (settings.isDebugMessage()) {
                player.sendMessage(Component.text(
                        "[Zstd] zstd packet compression enabled (server level " + settings.effectiveCompressionLevel() + ")",
                        NamedTextColor.GREEN));
            }
        } catch (Exception e) {
            getLogger().warning("Failed to enable zstd for player " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Logs the runtime environment at startup so container/shared-hosting operators can see what
     * was auto-detected (CPU count as seen by the JVM, native library availability, pool sizes)
     * without attaching a debugger.
     */
    private void logEnvironment() {
        String javaVersion = System.getProperty("java.version", "unknown");
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        if (ZstdNative.isAvailable()) {
            getLogger().info("compression-level=" + settings.effectiveCompressionLevel()
                    + ", compression-threshold=" + settings.getCompressionThreshold() + " bytes"
                    + ", async-workers=" + ZstdAsyncPools.workerCount()
                    + ", async-threshold=" + ZstdAsyncPools.ASYNC_THRESHOLD + " bytes"
                    + ", cpus=" + Runtime.getRuntime().availableProcessors()
                    + ", java=" + javaVersion + ", os=" + osName + " " + osArch
                    + ", native=" + ZstdNative.status());
        } else {
            getLogger().warning("zstd native library is not available on this platform (" + osName
                    + " " + osArch + "); players will use vanilla zlib compression.");
            if (osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
                getLogger().warning("On Alpine/musl-based Docker images the bundled zstd-jni natives "
                        + "(glibc) may fail to load: install the 'java-zstd-jni' system package or "
                        + "switch to a glibc-based image to enable zstd.");
            }
        }
    }

    private ZstdSettings loadConfig() {
        try {
            return ConfigLoader.load(getDataFolder().toPath().resolve("config.yml"));
        } catch (IOException e) {
            getLogger().warning("Failed to load config.yml, using defaults: " + e.getMessage());
            return new ZstdSettings();
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("ZstdNetworkProject Paper plugin disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!zstdUsable) {
            return; // No native library: never install handlers we cannot run, never probe.
        }
        Player player = event.getPlayer();
        try {
            replacePipeline(player);
        } catch (Exception e) {
            getLogger().warning("Failed to replace pipeline: " + e.getMessage());
            ErrorLogger.log(getDataFolder().toPath().resolve("zstd-errors.log"), "Failed to replace pipeline", e);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clientZstdLevels.remove(event.getPlayer().getUniqueId());
    }

    /**
     * /zstdinfo: reports how many connected players have zstd compression active, one row per zstd
     * player with the compression level they reported and their current ping. Never prints IPs.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> rows = new ArrayList<>();
        int zstdCount = 0;
        int onlineCount = 0;
        for (Player player : getServer().getOnlinePlayers()) {
            onlineCount++;
            boolean onZstd;
            try {
                Channel channel = getNettyChannel(player);
                onZstd = channel != null && ZstdCapability.remoteSpeaksZstd(channel);
            } catch (Exception e) {
                onZstd = false;
            }
            if (!onZstd) {
                continue;
            }
            zstdCount++;
            int level = clientZstdLevels.getOrDefault(player.getUniqueId(), settings.effectiveCompressionLevel());
            rows.add(" - " + player.getName() + " | nivel " + level + " | ping " + player.getPing() + "ms");
        }
        String nativeStatus = ZstdNative.isAvailable() ? "OK" : "NO";
        sender.sendMessage("[Zstd] " + zstdCount + " de " + onlineCount + " jugadores en zstd ("
                + (onlineCount - zstdCount) + " en zlib) | native=" + nativeStatus
                + " nivel-servidor=" + settings.effectiveCompressionLevel()
                + " umbral=" + settings.getCompressionThreshold() + " bytes");
        for (String row : rows) {
            sender.sendMessage("[Zstd]" + row);
        }
        return true;
    }

    private Channel getNettyChannel(Player player) throws Exception {
        Object handle;
        try {
            Method getHandle = this.getHandleMethod;
            if (getHandle == null) {
                getHandle = player.getClass().getMethod("getHandle");
                this.getHandleMethod = getHandle;
            }
            handle = getHandle.invoke(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access player handle via reflection", e);
        }

        // CraftPlayer -> ServerPlayer -> ServerGamePacketListenerImpl -> Connection -> Channel
        Object connection = ReflectionUtil.getFieldValue(handle, "connection");
        if (connection == null) {
            return null;
        }
        Object nettyConnection = ReflectionUtil.getFieldValue(connection, "connection");
        if (nettyConnection == null) {
            return null;
        }
        Object channelValue = ReflectionUtil.getFieldValue(nettyConnection, "channel");
        return channelValue instanceof Channel channel ? channel : null;
    }

    private void replacePipeline(Player player) throws Exception {
        Channel channel = getNettyChannel(player);
        if (channel == null) {
            return;
        }

        // Netty requires pipeline modifications from the channel's event loop; scheduling keeps
        // the main server thread responsive and avoids racing concurrent I/O events.
        // We inject clientEncoder=true (peerZstdRequired=true) so vanilla clients stay on zlib
        // until a modded client proves zstd support (via negotiation channel or zstd frame).
        channel.eventLoop().execute(() -> {
            try {
                boolean replaced = PipelineInjector.injectClient(channel, settings);
                if (HexDump.isEnabled()) {
                    HexDump.note("pipeline", "post-inject replaced=" + replaced
                            + " peer=" + channel.remoteAddress() + ": " + handlerList(channel));
                }
                if (!replaced) {
                    getLogger().warning("No compression handlers were found/replaced on "
                            + player.getName() + "'s pipeline (server fork may use different handler names)");
                    ErrorLogger.log(getDataFolder().toPath().resolve("zstd-errors.log"),
                            "Pipeline handlers after failed inject for " + player.getName(),
                            new IllegalStateException(handlerList(channel)));
                } else {
                    // Send capability probe to the player once their play-phase channel
                    // registration has arrived.
                    sendCapabilityProbe(player);
                }
            } catch (Throwable t) {
                getLogger().warning("Failed to replace pipeline for " + player.getName() + ": " + t);
                ErrorLogger.log(getDataFolder().toPath().resolve("zstd-errors.log"),
                        "Failed to replace pipeline for " + player.getName(), t);
            }
        });
    }

    /**
     * Sends the play-phase capability probe to a modded client. Paper only forwards plugin
     * messages for channels the client advertised through its play-phase {@code minecraft:register},
     * which a modded client sends the moment it enters play - potentially just after
     * {@link PlayerJoinEvent} fires. Each attempt before the channel is registered is a harmless
     * no-op, so keep re-sending until the client proves zstd support or the attempt budget runs out.
     */
    private void sendCapabilityProbe(Player player) {
        final AtomicInteger remaining = new AtomicInteger(40); // 40 probes on a 5-tick period: ~10s
        getServer().getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline()) {
                task.cancel();
                return;
            }
            try {
                Channel nettyChannel = getNettyChannel(player);
                if (nettyChannel != null && ZstdCapability.remoteSpeaksZstd(nettyChannel)) {
                    task.cancel();
                    return;
                }
                player.sendPluginMessage(this, ZstdNegotiation.CHANNEL,
                        ZstdNegotiation.queryPayload(settings.effectiveCompressionLevel()));
            } catch (Exception ignored) {
            }
            if (remaining.decrementAndGet() <= 0) {
                task.cancel();
            }
        }, 0L, 5L);
    }

    /** Names + classes of every Netty handler in the pipeline, for diagnosing custom forks. */
    private static String handlerList(Channel channel) {
        StringBuilder sb = new StringBuilder(256);
        try {
            channel.pipeline().forEach(entry -> sb.append(entry.getKey()).append('(')
                    .append(entry.getValue().getClass().getName()).append(") "));
        } catch (Exception e) {
            sb.append("<pipeline read failed: ").append(e).append('>');
        }
        return sb.toString().trim();
    }
}
