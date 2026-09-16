package com.rigorberto.zstdnetworkproject;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.function.Supplier;

/**
 * Shared helpers for per-connection flags carried as Netty channel attributes. Both
 * {@link ZstdCapability} (a boolean) and {@link ZstdPeerLevel} (a level) follow the same lazy
 * {@code attr().get()} + {@code setIfAbsent()} pattern; this consolidates it so the duplicated
 * lookups and the double-read hazard disappear.
 */
final class ChannelAttrs {

    private ChannelAttrs() {
    }

    /**
     * Returns the current value held under {@code key} on {@code channel}, creating and storing a
     * new one from {@code supplier} the first time it is touched. Never returns {@code null}.
     */
    static <T> T getOrCreate(Channel channel, AttributeKey<T> key, Supplier<? extends T> supplier) {
        T value = channel.attr(key).get();
        if (value != null) {
            return value;
        }
        T created = supplier.get();
        T existing = channel.attr(key).setIfAbsent(created);
        return existing != null ? existing : created;
    }
}