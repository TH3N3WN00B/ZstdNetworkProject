package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ReflectionUtil;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.reflect.Method;

/**
 * Client-to-server payload send. NeoForge moved the entry point twice, and both moves fall inside
 * the versions this build targets, so it is resolved reflectively instead of being compiled against
 * one fixed class:
 * <ul>
 *   <li>1.21.4–1.21.6: {@code PacketDistributor.sendToServer}</li>
 *   <li>1.21.7+ (and 26.x): {@code ClientPacketDistributor.sendToServer}</li>
 * </ul>
 * The 26.x build keeps a direct (non-reflective) variant in {@code src/unobfuscated}. This is not a
 * hot path — it runs once per login in {@code announceCapability} — so reflection is acceptable.
 */
final class ZstdNeoForgeSender {

    private static final Method SEND = resolveSend();

    private static Method resolveSend() {
        try {
            Class<?> cls = ReflectionUtil.classExists("net.neoforged.neoforge.client.network.ClientPacketDistributor")
                    ? Class.forName("net.neoforged.neoforge.client.network.ClientPacketDistributor")
                    : Class.forName("net.neoforged.neoforge.network.PacketDistributor");
            return findSendToServer(cls);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Both {@code PacketDistributor} (1.21.4–1.21.6) and {@code ClientPacketDistributor} (1.21.7+)
     * declare {@code sendToServer} as a varargs method:
     * {@code sendToServer(CustomPacketPayload payload, CustomPacketPayload... payloads)}. Because the
     * extra varargs parameter changes the erased signature, a plain
     * {@code getMethod("sendToServer", CustomPacketPayload.class)} lookup throws
     * {@code NoSuchMethodException}; instead match by name and first parameter type so the call
     * keeps working across eras regardless of the exact signature.
     */
    private static Method findSendToServer(Class<?> cls) throws NoSuchMethodException {
        for (Method method : cls.getMethods()) {
            if ("sendToServer".equals(method.getName())
                    && method.getParameterCount() >= 1
                    && method.getParameterTypes()[0] == CustomPacketPayload.class) {
                return method;
            }
        }
        throw new NoSuchMethodException(cls.getName() + ".sendToServer(CustomPacketPayload)");
    }

    private ZstdNeoForgeSender() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        try {
            Object[] args = SEND.getParameterCount() >= 2
                    ? new Object[]{payload, new CustomPacketPayload[0]}
                    : new Object[]{payload};
            SEND.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to send zstd capability payload", e);
        }
    }
}
