package com.rigorberto.zstdnetworkproject;

import io.netty.channel.Channel;
import java.util.concurrent.TimeUnit;

public final class ClientPipelineInjector {

    @FunctionalInterface
    public interface Injector {
        boolean apply(Object connection, ZstdSettings settings) throws Exception;
    }

    @FunctionalInterface
    public interface ChannelInjector {
        boolean apply(Channel channel, ZstdSettings settings) throws Exception;
    }

    private ClientPipelineInjector() {
    }

    /**
     * Installs both Zstd handlers (encoder + decoder) on a client connection. The encoder only
     * switches to zstd once the remote end has been observed sending zstd, so a modded client
     * stays fully compatible with vanilla servers.
     */
    public static boolean inject(Object connection, ZstdSettings settings) throws Exception {
        return runOnEventLoop(connection, settings, PipelineInjector::injectClient);
    }

    /**
     * Installs only the Zstd decoder on a client connection. Intended for the configuration phase,
     * where the client must decode Zstd from a proxy but still send Zlib until the remote side
     * switches to Zstd at the play phase.
     */
    public static boolean injectDecoder(Object connection, ZstdSettings settings) throws Exception {
        return runOnEventLoop(connection, settings, PipelineInjector::injectDecoder);
    }

    /**
     * Installs both Zstd handlers on a channel. Uses a pre-resolved {@link Channel} so callers on
     * obfuscated builds can obtain it via compile-time references (which the remapper rewrites)
     * instead of string-based reflection.
     */
    public static boolean inject(Channel channel, ZstdSettings settings) throws Exception {
        return runOnEventLoop(channel, settings, PipelineInjector::injectClient);
    }

    /**
     * Installs only the Zstd decoder on a channel. See {@link #inject(Channel, ZstdSettings)}.
     */
    public static boolean injectDecoder(Channel channel, ZstdSettings settings) throws Exception {
        return runOnEventLoop(channel, settings, PipelineInjector::injectDecoder);
    }

    /**
     * Best-effort {@code host:port} string for the remote peer of a client channel, used for
     * matching against the {@code disabled-servers} config. Falls back to the raw address text.
     */
    public static String remoteAddress(Channel channel) {
        try {
            java.net.SocketAddress address = channel.remoteAddress();
            if (address instanceof java.net.InetSocketAddress inet && inet.getAddress() != null) {
                String host = inet.getHostString();
                int port = inet.getPort();
                boolean needsBrackets = host.indexOf(':') >= 0;
                return needsBrackets ? "[" + host + "]:" + port : host + ":" + port;
            }
            if (address != null) {
                return address.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean runOnEventLoop(Object connection, ZstdSettings settings,
                                          ChannelInjector injection) throws Exception {
        return runOnEventLoop(getChannel(connection), settings, injection);
    }

    private static boolean runOnEventLoop(Channel channel, ZstdSettings settings,
                                          ChannelInjector injection) throws Exception {
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

    /**
     * Resolves the underlying {@link Channel} from a client connection object, preferring the
     * public {@code getChannel()} method and falling back to the {@code channel} field.
     */
    public static Channel getChannel(Object connection) {
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
