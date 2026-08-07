package com.rigorberto.zstdnetworkproject;

import io.netty.channel.Channel;
import java.util.concurrent.TimeUnit;

public final class ClientPipelineInjector {

    @FunctionalInterface
    public interface Injector {
        boolean apply(Object connection, ZstdSettings settings) throws Exception;
    }

    @FunctionalInterface
    private interface ChannelInjector {
        boolean apply(Channel channel, ZstdSettings settings) throws Exception;
    }

    private ClientPipelineInjector() {
    }

    /**
     * Installs both Zstd handlers (encoder + decoder) on a client connection. Intended for the
     * play phase, once the remote side is also sending Zstd.
     */
    public static boolean inject(Object connection, ZstdSettings settings) throws Exception {
        return runOnEventLoop(connection, settings, PipelineInjector::inject);
    }

    /**
     * Installs only the Zstd decoder on a client connection. Intended for the configuration phase,
     * where the client must decode Zstd from a proxy but still send Zlib until the remote side
     * switches to Zstd at the play phase.
     */
    public static boolean injectDecoder(Object connection, ZstdSettings settings) throws Exception {
        return runOnEventLoop(connection, settings, PipelineInjector::injectDecoder);
    }

    private static boolean runOnEventLoop(Object connection, ZstdSettings settings,
                                          ChannelInjector injection) throws Exception {
        Channel channel = getChannel(connection);
        if (channel == null) {
            return false;
        }
        if (channel.eventLoop().inEventLoop()) {
            return injection.apply(channel, settings);
        }
        return channel.eventLoop().submit(() -> injection.apply(channel, settings)).get(2, TimeUnit.SECONDS);
    }

    /**
     * Resolves the underlying connection from a client packet listener, preferring the public
     * {@code getConnection()} method and falling back to the {@code connection} field.
     */
    public static Object getConnection(Object packetListener) throws Exception {
        try {
            Object value = packetListener.getClass().getMethod("getConnection").invoke(packetListener);
            if (value != null) {
                return value;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return ReflectionUtil.getFieldValue(packetListener, "connection");
    }

    private static Channel getChannel(Object connection) {
        try {
            Object value = connection.getClass().getMethod("getChannel").invoke(connection);
            if (value instanceof Channel channel) {
                return channel;
            }
        } catch (Exception ignored) {
        }
        try {
            Object value = ReflectionUtil.getFieldValue(connection, "channel");
            if (value instanceof Channel channel) {
                return channel;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
