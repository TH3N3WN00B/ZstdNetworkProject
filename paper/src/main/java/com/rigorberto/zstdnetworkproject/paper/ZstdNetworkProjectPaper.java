package com.rigorberto.zstdnetworkproject.paper;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.HexDump;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public class ZstdNetworkProjectPaper extends JavaPlugin implements Listener {

    private ZstdSettings settings = new ZstdSettings();

    @Override
    public void onEnable() {
        settings = loadConfig();
        HexDump.configure(getDataFolder().toPath().resolve("zstd-hexdump.log"), settings.isHexDump());
        if (HexDump.isEnabled()) {
            getLogger().info("hex-dump enabled: frames are being written to " + getDataFolder().toPath().resolve("zstd-hexdump.log"));
        }
        getServer().getPluginManager().registerEvents(this, this);
        StartupBanner.print();
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
        try {
            replacePipeline(event.getPlayer());
        } catch (Exception e) {
            getLogger().warning("Failed to replace pipeline: " + e.getMessage());
            ErrorLogger.log(getDataFolder().toPath().resolve("zstd-errors.log"), "Failed to replace pipeline", e);
        }
    }

    private void replacePipeline(Player player) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);

        // CraftPlayer -> ServerPlayer -> ServerGamePacketListenerImpl -> Connection -> Channel
        Object connection = ReflectionUtil.getFieldValue(handle, "connection");
        if (connection == null) {
            return;
        }
        Object nettyConnection = ReflectionUtil.getFieldValue(connection, "connection");
        if (nettyConnection == null) {
            return;
        }
        Object channelValue = ReflectionUtil.getFieldValue(nettyConnection, "channel");
        if (channelValue instanceof Channel channel) {
            boolean replaced = PipelineInjector.inject(channel, settings);
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
            }
        }
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
