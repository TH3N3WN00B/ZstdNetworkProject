package com.rigorberto.zstdnetworkproject;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-connection record of the highest zstd compression level the remote server announced during
 * the capability handshake (the login query sent by Velocity, the play query sent by Paper).
 *
 * <p>With the opt-in {@code match-server-level} setting, the client encoder may raise its own
 * level up to the server's whenever the server advertises a higher one, so uploading benefits from
 * the same compression settings the server picked for downloads. Only the client uses this:
 * servers never call {@link #adopt}.
 */
public final class ZstdPeerLevel {

    private static final AttributeKey<AtomicInteger> PEER_LEVEL =
            AttributeKey.valueOf("zstdnetworkproject:peer-level");

    private ZstdPeerLevel() {
    }

    /**
     * The level to use for outgoing compression, or {@code localLevel} unchanged. Matching never
     * lowers the client below its configured level, never touches fast mode (negative levels, where
     * the user explicitly preferred speed over ratio), and only applies once a server level has
     * been received.
     */
    public static int effectiveLevel(Channel channel, int localLevel) {
        if (channel == null || localLevel < 0) {
            return localLevel;
        }
        AtomicInteger ref = channel.attr(PEER_LEVEL).get();
        int peer = ref == null ? 0 : ref.get();
        return Math.max(localLevel, peer);
    }

    /**
     * Records the compression level a server announced on this connection. Only ever raises the
     * stored value, so a lower level arriving later (or a second probe) never downgrades the
     * client. Levels outside zstd's valid range are ignored, which also caps a malicious server
     * that claims a huge level to burn client CPU.
     */
    public static void adopt(Channel channel, int serverLevel) {
        if (channel == null || serverLevel < 1 || serverLevel > ConfigLoader.MAX_COMPRESSION_LEVEL) {
            return;
        }
        ChannelAttrs.getOrCreate(channel, PEER_LEVEL, AtomicInteger::new)
                .updateAndGet(current -> Math.max(current, serverLevel));
    }
}