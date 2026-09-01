package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ConfigLoader;
import com.rigorberto.zstdnetworkproject.ErrorLogger;
import com.rigorberto.zstdnetworkproject.HexDump;
import com.rigorberto.zstdnetworkproject.PipelineInjector;
import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import com.rigorberto.zstdnetworkproject.StartupBanner;
import com.rigorberto.zstdnetworkproject.ZstdCapability;
import com.rigorberto.zstdnetworkproject.ZstdNative;
import com.rigorberto.zstdnetworkproject.ZstdSettings;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

@Mod("zstdnetworkproject")
public class ZstdNetworkProjectNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstdnetworkproject");
    private final ZstdSettings settings;

    public ZstdNetworkProjectNeoForge(IEventBus modEventBus) {
        settings = loadConfig();
        String blockingMod = settings.findLoadedAutoDisableMod(id -> ModList.get().isLoaded(id));
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
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::registerPayloads);
        HexDump.configure(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("zstd-hexdump.log"),
                settings.isHexDump());
        StartupBanner.print();
        if (isClientDist()) {
            // ZstdNeoForgeClient subscribes itself to the event bus. Registering it here as well
            // would deliver every client event twice and, worse, would subscribe it even when its
            // constructor bailed out early.
            new ZstdNeoForgeClient(settings);
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

    /**
     * Registers the capability payload in both directions. Marked {@code optional()} so a client
     * carrying this mod can still join a server without it (and vice versa) instead of being
     * rejected over a missing channel.
     *
     * <p>Registered once via {@code playBidirectional}. Since the single-handler overload only
     * registers the server side from NeoForge 1.21.6 (causing the "Some clientbound payloads are
     * missing client-side handlers" startup failure) and the two-handler overload did not exist
     * before 1.21.6, the two-handler overload is invoked reflectively when available and falls
     * back to the single-handler overload on older versions.
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        IPayloadHandler<ZstdCapablePayload> handler =
                (payload, context) -> onCapabilityAnnounced(context);
        try {
            Method twoHandlers = registrar.getClass().getMethod(
                    "playBidirectional",
                    CustomPacketPayload.Type.class, StreamCodec.class,
                    IPayloadHandler.class, IPayloadHandler.class);
            twoHandlers.invoke(registrar,
                    ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC, handler, handler);
        } catch (NoSuchMethodException e) {
            registrar.playBidirectional(
                    ZstdCapablePayload.TYPE, ZstdCapablePayload.CODEC, handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to register zstd capability payload", e);
        }
    }

    /**
     * The peer told us it can decode zstd, so this connection may switch to it.
     *
     * <p>Without this the connection deadlocks: both encoders are installed with
     * {@code peerZstdRequired}, so a modded server and a modded client would each keep sending
     * vanilla zlib while waiting for the other to send zstd first.
     */
    private static void onCapabilityAnnounced(IPayloadContext context) {
        try {
            Connection connection = context.connection();
            Object channelValue = ReflectionUtil.getFieldValue(connection, "channel");
            if (channelValue instanceof Channel channel) {
                ZstdCapability.markZstdObserved(channel);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to mark peer as zstd-capable", e);
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
            if (channelValue instanceof Channel channel) {
                PipelineInjector.injectClient(channel, settings);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inject Zstd handlers", e);
            ErrorLogger.log(FMLPaths.CONFIGDIR.get().resolve("zstdnetworkproject").resolve("zstd-errors.log"),
                    "Failed to inject Zstd handlers", e);
        }
    }
}
