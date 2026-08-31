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
            return cls.getMethod("sendToServer", CustomPacketPayload.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ZstdNeoForgeSender() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        try {
            SEND.invoke(null, payload);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to send zstd capability payload", e);
        }
    }
}
