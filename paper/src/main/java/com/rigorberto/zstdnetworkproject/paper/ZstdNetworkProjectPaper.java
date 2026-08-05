package com.rigorberto.zstdnetworkproject.paper;

import com.rigorberto.zstdnetworkproject.ZstdEncoder;
import com.rigorberto.zstdnetworkproject.ZstdDecoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ZstdNetworkProjectPaper extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ZstdNetworkProject Paper plugin enabled!");
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
        }
    }

    private void replacePipeline(org.bukkit.entity.Player player) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Field connectionField = handle.getClass().getDeclaredField("connection");
        connectionField.setAccessible(true);
        Object connection = connectionField.get(handle);
        
        Field channelField = connection.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        Channel channel = (Channel) channelField.get(connection);
        
        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get("compress") != null) {
            pipeline.replace("compress", "compress", new ZstdEncoder());
        }
        if (pipeline.get("decompress") != null) {
            pipeline.replace("decompress", "decompress", new ZstdDecoder());
        }
    }
}