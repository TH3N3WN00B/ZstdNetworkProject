package com.rigorberto.zstdnetworkproject;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection record of whether the remote end has been observed sending zstd frames.
 *
 * <p>Client encoders only switch to zstd once the remote side has proven it can decode it (by
 * sending zstd itself), so that a client with the mod never breaks a vanilla server that expects
 * zlib. All decoders sniff the frame magic, so they can receive either format regardless of this
 * flag.
 */
public final class ZstdCapability {

    private static final AttributeKey<AtomicBoolean> REMOTE_SPEAKS_ZSTD =
            AttributeKey.valueOf("zstdnetworkproject:remote-zstd");

    private ZstdCapability() {
    }

    public static boolean remoteSpeaksZstd(Channel channel) {
        if (channel == null) {
            return false;
        }
        AtomicBoolean flag = channel.attr(REMOTE_SPEAKS_ZSTD).get();
        return flag != null && flag.get();
    }

    public static void markZstdObserved(Channel channel) {
        if (channel == null) {
            return;
        }
        AtomicBoolean flag = channel.attr(REMOTE_SPEAKS_ZSTD).get();
        if (flag == null) {
            flag = new AtomicBoolean();
            AtomicBoolean existing = channel.attr(REMOTE_SPEAKS_ZSTD).setIfAbsent(flag);
            if (existing != null) {
                flag = existing;
            }
        }
        flag.set(true);
        ZstdOverlayStats.noteZstdObserved();
    }
}
