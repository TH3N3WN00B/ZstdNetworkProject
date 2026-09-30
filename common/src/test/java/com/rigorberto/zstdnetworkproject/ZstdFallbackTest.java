package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the zlib fallback behavior described in AUDITORÍA_ZLIB_FALLBACK.md.
 *
 * <p>The audit proposed implementing an auto-detect fallback in {@link ZstdEncoder}; that behavior
 * already exists and this test locks it in:
 * <ul>
 *   <li>A client encoder ({@code peerZstdRequired=true}) emits vanilla-compatible zlib frames
 *       (never zstd) while the peer has not proven it speaks zstd.</li>
 *   <li>Once the peer proves zstd (via {@link ZstdCapability#markZstdObserved}), the same encoder
 *       switches to real zstd frames opening with the zstd magic {@code 28 B5 2F FD}.</li>
 *   <li>The decoder sniffs the frame magic: a zstd frame is decompressed and marks the peer
 *       capable, a zlib frame is decompressed without marking it.</li>
 * </ul>
 */
class ZstdFallbackTest {

    /** First four bytes of every zstd frame (matches {@link ZstdDecoder#ZSTD_MAGIC}). */
    private static final byte[] ZSTD_MAGIC = {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD};

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int bytes = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << (bytes * 7);
            if (++bytes > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    private static byte[] varInt(int value) {
        ByteBuf buf = Unpooled.buffer(5);
        try {
            while ((value & ~0x7F) != 0) {
                buf.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buf.writeByte(value);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, off, part.length);
            off += part.length;
        }
        return out;
    }

    /** Compressible patterned payload so zlib/zstd actually shrink it. */
    private static byte[] patterned(int length, long seed) {
        byte[] data = new byte[length];
        Random rnd = new Random(seed);
        rnd.nextBytes(data);
        for (int i = 8; i < data.length - 24; i += 64) {
            byte[] tag = "0a08 datetime text".getBytes();
            System.arraycopy(tag, 0, data, i, Math.min(tag.length, data.length - i));
        }
        return data;
    }

    private static byte[] zlib(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] out = new byte[data.length + 64];
        int n = deflater.deflate(out);
        deflater.end();
        byte[] z = new byte[n];
        System.arraycopy(out, 0, z, 0, n);
        return z;
    }

    private static byte[] zstd(byte[] data) {
        return Zstd.compress(data);
    }

    private static byte[] inflateOrThrow(byte[] data) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] out = new byte[Math.max(64, data.length * 8)];
        int written = 0;
        while (!inflater.finished()) {
            if (written == out.length) {
                out = java.util.Arrays.copyOf(out, out.length * 2);
            }
            int n = inflater.inflate(out, written, out.length - written);
            if (n == 0 && !inflater.finished() && inflater.needsInput()) {
                inflater.end();
                throw new IllegalStateException("truncated zlib stream");
            }
            written += n;
        }
        inflater.end();
        return java.util.Arrays.copyOf(out, written);
    }

    private static byte[] bytesOf(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    private static boolean holdsZstdMagic(byte[] payload) {
        if (payload.length < ZSTD_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZSTD_MAGIC.length; i++) {
            if (payload[i] != ZSTD_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A client encoder with {@code peerZstdRequired=true} must never emit zstd while the peer is
     * silent: the frame is {@code varint(uncompressedSize)} + a real zlib stream (header CMF 0x78)
     * that inflates back to the packet.
     */
    @Test
    void clientEncoderEmitsZlibWhilePeerIsSilent() throws Exception {
        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionThreshold(16); // packets >= 16 bytes must be compressed
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdEncoder(settings, true));
        try {
            byte[] packet = patterned(400, 7);
            assertFalse(ZstdCapability.remoteSpeaksZstd(channel),
                    "fresh channel must not be zstd-capable");
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(packet)),
                    "the encoder must emit an outbound frame");

            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame, "the zlib fallback must produce a frame");
            try {
                int declared = readVarInt(frame);
                assertEquals(packet.length, declared,
                        "varint(uncompressedSize) must be the first bytes of the frame");
                byte[] payload = new byte[frame.readableBytes()];
                frame.readBytes(payload);
                assertFalse(holdsZstdMagic(payload),
                        "a silent peer must never receive a zstd frame");
                assertEquals(0x78, payload[0] & 0xFF,
                        "zlib streams open with the CMF byte 0x78");
                assertArrayEquals(packet, inflateOrThrow(payload),
                        "the zlib payload must inflate back to the original packet");
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * After the peer proves zstd (the capability handshake), the same encoder instance must switch
     * to zstd frames opening with the real magic {@code 28 B5 2F FD}.
     */
    @Test
    void clientEncoderSwitchesToZstdOncePeerProvesCapability() {
        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionThreshold(16);
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdEncoder(settings, true));
        try {
            byte[] packet = patterned(400, 7);

            // First packet while the peer is silent: zlib.
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(packet)));
            ByteBuf first = channel.readOutbound();
            assertNotNull(first);
            byte[] firstPayload = new byte[first.readableBytes()];
            first.getBytes(first.readerIndex(), firstPayload);
            assertFalse(holdsZstdMagic(firstPayload), "silent peer must get zlib first");
            first.release();

            // Peer proves zstd support.
            ZstdCapability.markZstdObserved(channel);
            assertTrue(ZstdCapability.remoteSpeaksZstd(channel));

            // Next packet: a real zstd frame.
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(packet)));
            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame, "a zstd frame should be produced");
            try {
                int declared = readVarInt(frame);
                assertEquals(packet.length, declared, "uncompressed size varint");
                byte[] payload = new byte[frame.readableBytes()];
                frame.getBytes(frame.readerIndex(), payload);
                assertTrue(holdsZstdMagic(payload),
                        "payload must start with the zstd magic 28 B5 2F FD");

                byte[] out = new byte[declared];
                long n = Zstd.decompress(out, payload);
                assertTrue(!Zstd.isError(n), "decompression must succeed: "
                        + Zstd.getErrorName(n));
                assertEquals(packet.length, n);
                assertArrayEquals(packet, out, "round-trip must match the input");
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** A zstd frame fed to the decoder is decompressed and marks the peer zstd-capable. */
    @Test
    void decoderSniffsZstdFrameAndMarksPeerCapable() {
        byte[] packet = patterned(400, 11);
        byte[] frame = concat(varInt(packet.length), zstd(packet));

        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder());
        try {
            long packetsBefore = ZstdDecoder.ZSTD_PACKETS.sum();
            assertFalse(ZstdCapability.remoteSpeaksZstd(channel));
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame)),
                    "one decoded frame (packet) expected");

            ByteBuf out = channel.readInbound();
            assertNotNull(out, "decoder must produce the decompressed packet");
            try {
                assertArrayEquals(packet, bytesOf(out), "zstd frame must round-trip");
            } finally {
                out.release();
            }
            assertTrue(ZstdCapability.remoteSpeaksZstd(channel),
                    "a zstd frame must mark the peer capable");
            assertEquals(packetsBefore + 1, ZstdDecoder.ZSTD_PACKETS.sum(),
                    "zstd packet counter must advance");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** A vanilla zlib frame is decompressed by the decoder without marking the peer zstd-capable. */
    @Test
    void decoderTreatsZlibFrameWithoutMarkingZstd() {
        byte[] packet = patterned(400, 13);
        byte[] frame = concat(varInt(packet.length), zlib(packet));

        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder());
        try {
            long packetsBefore = ZstdDecoder.ZLIB_PACKETS.sum();
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame)),
                    "one decoded frame (packet) expected");

            ByteBuf out = channel.readInbound();
            assertNotNull(out, "decoder must produce the decompressed packet");
            try {
                assertArrayEquals(packet, bytesOf(out), "zlib frame must round-trip");
            } finally {
                out.release();
            }
            assertFalse(ZstdCapability.remoteSpeaksZstd(channel),
                    "a zlib frame alone must not mark the peer zstd-capable");
            assertEquals(packetsBefore + 1, ZstdDecoder.ZLIB_PACKETS.sum(),
                    "zlib packet counter must advance");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}