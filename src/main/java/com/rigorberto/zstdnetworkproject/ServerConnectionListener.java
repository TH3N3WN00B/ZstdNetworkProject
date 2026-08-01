package com.rigorberto.zstdnetworkproject;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

public class ServerConnectionListener {
    @Mixin(ServerConnectionListener.class)
    public abstract class MixinServerConnectionListener {

        @Inject(method = "initChannel", at = @At("TAIL"))
        private void onInitChannel(Channel channel, CallbackInfo ci) {
            // Krypton y Minecraft vanilla agregan "compress" y "decompress"
            ChannelPipeline pipeline = channel.pipeline();

            // Esperamos a que se alcance el estado de juego para reemplazar
            // o reemplazamos directamente si el proxy (Velocity) maneja el handshake
            if (pipeline.get("compress") != null) {
                pipeline.replace("compress", "compress", new ZstdEncoder());
            }
            if (pipeline.get("decompress") != null) {
                pipeline.replace("decompress", "decompress", new ZstdDecoder());
            }
        }
    }
}
