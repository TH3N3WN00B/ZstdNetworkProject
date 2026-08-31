package com.rigorberto.zstdnetworkproject;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

public final class PipelineInjector {

    public static final String VANILLA_ENCODER = "compress";
    public static final String VANILLA_DECODER = "decompress";
    public static final String VELOCITY_ENCODER = "compression-encoder";
    public static final String VELOCITY_DECODER = "compression-decoder";

    private PipelineInjector() {
    }

    public static boolean injectDecoder(Channel channel, ZstdSettings settings) {
        return inject(channel, settings, null, VANILLA_DECODER, false, false);
    }

    /**
     * Injects the client-side encoder, which only switches to zstd once the server has been
     * observed sending zstd (so a modded client never breaks a vanilla server).
     */
    public static boolean injectClient(Channel channel, ZstdSettings settings) {
        return inject(channel, settings, VANILLA_ENCODER, VANILLA_DECODER, false, true);
    }

    /**
     * Replaces the compression handlers in the pipeline. Idempotent: handlers that are already
     * Zstd-based are left untouched, and a {@code null} handler name leaves that side alone.
     *
     * @param channel       the channel whose pipeline should be modified (must be invoked on the
     *                      channel's event loop)
     * @param settings      the compression settings
     * @param encoderName   the name of the compression encoder handler, or null to skip it
     * @param decoderName   the name of the compression decoder handler, or null to skip it
     * @param framedEncoder whether the encoder must write the VarInt frame length itself (true for
     *                      merged length+compression handlers such as Velocity's, false when a
     *                      separate frame-length encoder remains in the pipeline)
     * @return true if at least one handler was replaced
     */
    public static boolean inject(Channel channel, ZstdSettings settings, String encoderName,
                                 String decoderName, boolean framedEncoder) {
        return inject(channel, settings, encoderName, decoderName, framedEncoder, false);
    }

    /**
     * Replaces the compression handlers in the pipeline. Idempotent: handlers that are already
     * Zstd-based are left untouched, and a {@code null} handler name leaves that side alone.
     *
     * @param channel         the channel whose pipeline should be modified (must be invoked on the
     *                        channel's event loop)
     * @param settings        the compression settings
     * @param encoderName     the name of the compression encoder handler, or null to skip it
     * @param decoderName     the name of the compression decoder handler, or null to skip it
     * @param framedEncoder   whether the encoder must write the VarInt frame length itself (true
     *                        for merged length+compression handlers such as Velocity's, false when
     *                        a separate frame-length encoder remains in the pipeline)
     * @param clientEncoder   whether this is the client-side encoder that must only use zstd after
     *                        observing zstd from the remote end (see {@link ZstdCapability})
     * @return true if at least one handler was replaced
     */
    public static boolean inject(Channel channel, ZstdSettings settings, String encoderName,
                                 String decoderName, boolean framedEncoder, boolean clientEncoder) {
        if (channel == null) {
            return false;
        }
        boolean replaced = false;
        ChannelPipeline pipeline = channel.pipeline();
        if (encoderName != null) {
            ChannelHandler encoder = pipeline.get(encoderName);
            Class<? extends ChannelHandler> expected = framedEncoder ? ZstdFrameEncoder.class : ZstdEncoder.class;
            if (encoder != null && !expected.isInstance(encoder)) {
                pipeline.replace(encoderName, encoderName,
                        framedEncoder
                                ? new ZstdFrameEncoder(settings)
                                : new ZstdEncoder(settings, clientEncoder));
                replaced = true;
            }
        }
        if (decoderName != null) {
            ChannelHandler decoder = pipeline.get(decoderName);
            if (decoder != null && !(decoder instanceof ZstdDecoder)) {
                pipeline.replace(decoderName, decoderName, new ZstdDecoder());
                replaced = true;
            }
        }
        return replaced;
    }
}
