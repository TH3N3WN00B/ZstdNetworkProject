package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ZstdNegotiation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Play-phase capability payload, the NeoForge counterpart of the Fabric one. A client sends it to
 * announce that it can decode zstd; the server then allows its encoder to switch away from vanilla
 * zlib for that connection. The wire format is raw bytes with no length prefix, matching how plugin
 * messages are encoded on the Paper side.
 */
public record ZstdCapablePayload(byte[] data) implements CustomPacketPayload {

    /**
     * The resource-identifier class was renamed inside the obfuscated era: Mojang renamed
     * {@code ResourceLocation} to {@code Identifier} (same {@code net.minecraft.resources} package)
     * by NeoForge 1.21.11, so which one this build links against is resolved reflectively instead of
     * being hardcoded against a single version.
     */
    public static final CustomPacketPayload.Type<ZstdCapablePayload> TYPE = makeType();

    public static final StreamCodec<ByteBuf, ZstdCapablePayload> CODEC = CustomPacketPayload.codec(
            (ZstdCapablePayload payload, ByteBuf buf) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] raw = new byte[buf.readableBytes()];
                buf.readBytes(raw);
                return new ZstdCapablePayload(raw);
            });

    private static Object channelId() {
        try {
            Class<?> cls = Id.load();
            Method tryParse = cls.getMethod("tryParse", String.class);
            return Objects.requireNonNull(tryParse.invoke(null, ZstdNegotiation.CHANNEL),
                    "invalid zstd capability channel id: " + ZstdNegotiation.CHANNEL);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static CustomPacketPayload.Type<ZstdCapablePayload> makeType() {
        try {
            Object id = channelId();
            Constructor<?> ctor = CustomPacketPayload.Type.class.getDeclaredConstructor(id.getClass());
            return (CustomPacketPayload.Type<ZstdCapablePayload>) ctor.newInstance(id);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Resolves the name of the (renamed) Minecraft resource-identifier class for the current era. */
    private interface Id {
        static Class<?> load() throws ClassNotFoundException {
            return com.rigorberto.zstdnetworkproject.ReflectionUtil.classExists("net.minecraft.resources.Identifier")
                    ? Class.forName("net.minecraft.resources.Identifier")
                    : Class.forName("net.minecraft.resources.ResourceLocation");
        }
    }
}
