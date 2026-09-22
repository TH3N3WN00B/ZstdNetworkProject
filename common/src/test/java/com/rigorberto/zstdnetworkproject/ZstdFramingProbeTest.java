package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the zstd frame layout emitted by both encoders end-to-end against the real zstd-jni
 * (1.5.7-16), which regressed on 26.2 servers as {@code 00 b5 2f fd ...} (magic {@code 28 b5 2f fd}
 * with its first byte zeroed), crashing vanilla peers.
 *
 * <p>Root cause: {@code ZstdCompressCtx.compress(ByteBuffer dst, ByteBuffer src)} writes at the
 * buffer's absolute base and ignores a pre-set position, so compressing into a Netty view offset
 * past the varint slots placed the stream at the frame's start and the fill-in size VarInt then
 * clobbered/misplaced it. Both encoders now compress into a dedicated buffer at position 0 and
 * assemble the prefix with forward writes.
 */
class ZstdFramingProbeTest {

    private static final byte[] ZSTD_MAGIC = new byte[] {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD};

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private static byte[] patterned(int length, long seed) {
        byte[] data = new byte[length];
        Random rnd = new Random(seed);
        rnd.nextBytes(data);
        // Mix realistic NBT-ish plaintext so zstd picks its raw vs compressed blocks like the
        // real MSPT/TPS tick chat packet does.
        for (int i = 8; i < data.length - 24; i += 64) {
            byte[] tag = "0a08 datetime text".getBytes();
            System.arraycopy(tag, 0, data, i, Math.min(tag.length, data.length - i));
        }
        return data;
    }

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

    private static boolean holdsMagic(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (buf.getByte(buf.readerIndex() + i) != ZSTD_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@link ZstdEncoder} frame (used on Fabric/Paper/Velocity compressors) must open with
     * {@code varint(uncompressedSize)} followed by a payload that starts with the zstd magic — never
     * {@code 00 b5 2f fd}.
     */
    @Test
    void zstdEncoderEmitsValidMagicFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdEncoder(new ZstdSettings()));
        channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        try {
            byte[] packet = patterned(553, 42);
            ByteBuf in = PooledByteBufAllocator.DEFAULT.directBuffer(packet.length).writeBytes(packet);
            assertTrue(channel.writeOutbound(in), "packet should be written");

            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame, "a compressed frame should be produced");
            try {
                int declared = readVarInt(frame);
                assertEquals(packet.length, declared, "uncompressed size varint");
                assertTrue(holdsMagic(frame), "payload must start with zstd magic, got "
                        + hex(firstBytes(frame, 8)));

                byte[] out = new byte[declared];
                byte[] payload = new byte[frame.readableBytes()];
                frame.getBytes(frame.readerIndex(), payload);
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

    /**
     * The {@link ZstdFrameEncoder} proxy frame (merged length+compression, Velocity style) must open
     * with {@code varint(frameLength), varint(uncompressedSize)} followed by the zstd magic.
     */
    @Test
    void zstdFrameEncoderEmitsValidMagicFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdFrameEncoder(new ZstdSettings()));
        channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        try {
            byte[] packet = patterned(553, 42);
            ByteBuf in = PooledByteBufAllocator.DEFAULT.directBuffer(packet.length).writeBytes(packet);
            assertTrue(channel.writeOutbound(in), "packet should be written");

            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame, "a compressed frame should be produced");
            try {
                int frameLength = readVarInt(frame);
                int declared = readVarInt(frame);
                assertEquals(packet.length, declared, "uncompressed size varint");
                assertEquals(frameLength - ZstdEncoder.varIntLength(declared), frame.readableBytes(),
                        "frame length must cover size varint + payload");
                assertTrue(holdsMagic(frame), "payload must start with zstd magic, got "
                        + hex(firstBytes(frame, 8)));

                byte[] out = new byte[declared];
                byte[] payload = new byte[frame.readableBytes()];
                frame.getBytes(frame.readerIndex(), payload);
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

    private static byte[] firstBytes(ByteBuf buf, int count) {
        byte[] data = new byte[Math.min(count, buf.readableBytes())];
        buf.getBytes(buf.readerIndex(), data);
        return data;
    }

    /**
     * Documentation probe: feeding the exact payload of the crash frame that killed the 26.2 test
     * sessions ({@code 00 b5 2f fd ...}) to zstd confirms it is a valid 553-byte frame whose magic's
     * first byte was zeroed — the wire corruption my encoder probe reproduced.
     */
    @Test
    void probeObservedCrashPayloadDecodesWithMagicRestored() {
        byte[] observed = new byte[] {
                // clang-format off
                (byte) 0x00, (byte) 0xb5, (byte) 0x2f, (byte) 0xfd, (byte) 0x60, (byte) 0x29,
                (byte) 0x01, (byte) 0xc5, (byte) 0x06, (byte) 0x00, (byte) 0x24, (byte) 0x09,
                (byte) 0x09, (byte) 0x31, (byte) 0x68, (byte) 0x40, (byte) 0x44, (byte) 0x47,
                (byte) 0xb2, (byte) 0x46, (byte) 0x10, (byte) 0x81, (byte) 0x6f, (byte) 0x8f,
                (byte) 0x8f, (byte) 0xe4, (byte) 0xc6, (byte) 0x18, (byte) 0xd6, (byte) 0x03,
                (byte) 0x0a, (byte) 0x08, (byte) 0x00, (byte) 0x05, (byte) 0x63, (byte) 0x6f,
                (byte) 0x6c, (byte) 0x6f, (byte) 0x72, (byte) 0x00, (byte) 0x04, (byte) 0x67,
                (byte) 0x72, (byte) 0x61, (byte) 0x79, (byte) 0x09, (byte) 0x00, (byte) 0x05,
                (byte) 0x65, (byte) 0x78, (byte) 0x74, (byte) 0x72, (byte) 0x61, (byte) 0x0a,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x09, (byte) 0x06, (byte) 0x79,
                (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f, (byte) 0x77, (byte) 0x08,
                (byte) 0x00, (byte) 0x04, (byte) 0x74, (byte) 0x65, (byte) 0x78, (byte) 0x74,
                (byte) 0x00, (byte) 0x01, (byte) 0x3a, (byte) 0x00, (byte) 0x05, (byte) 0x05,
                (byte) 0x67, (byte) 0x72, (byte) 0x65, (byte) 0x65, (byte) 0x6e, (byte) 0x32,
                (byte) 0x00, (byte) 0x07, (byte) 0x23, (byte) 0x34, (byte) 0x30, (byte) 0x45,
                (byte) 0x41, (byte) 0x34, (byte) 0x30, (byte) 0x30, (byte) 0x32, (byte) 0x42,
                (byte) 0x44, (byte) 0x35, (byte) 0x32, (byte) 0x42, (byte) 0x2e, (byte) 0x31,
                (byte) 0x35, (byte) 0x42, (byte) 0x46, (byte) 0x31, (byte) 0x35, (byte) 0x30,
                (byte) 0x0a, (byte) 0x64, (byte) 0x61, (byte) 0x72, (byte) 0x6b, (byte) 0x5f,
                (byte) 0x20, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x05, (byte) 0x20, (byte) 0x4d, (byte) 0x53, (byte) 0x50, (byte) 0x54,
                (byte) 0x04, (byte) 0x33, (byte) 0x33, (byte) 0x39, (byte) 0x45, (byte) 0x33,
                (byte) 0x33, (byte) 0x39, (byte) 0x2e, (byte) 0x31, (byte) 0x43, (byte) 0x43,
                (byte) 0x36, (byte) 0x31, (byte) 0x43, (byte) 0x36, (byte) 0x39, (byte) 0x50,
                (byte) 0x69, (byte) 0x6e, (byte) 0x67, (byte) 0x02, (byte) 0x37, (byte) 0x32,
                (byte) 0x02, (byte) 0x6d, (byte) 0x73, (byte) 0x03, (byte) 0x54, (byte) 0x50,
                (byte) 0x53, (byte) 0x00, (byte) 0x1e, (byte) 0x00, (byte) 0x3c, (byte) 0xab,
                (byte) 0x18, (byte) 0x40, (byte) 0x4e, (byte) 0x0f, (byte) 0x34, (byte) 0x00,
                (byte) 0x7c, (byte) 0x00, (byte) 0x64, (byte) 0x41, (byte) 0x99, (byte) 0xe0,
                (byte) 0xb1, (byte) 0xcc, (byte) 0x50, (byte) 0x03, (byte) 0x90, (byte) 0x01,
                (byte) 0x0d, (byte) 0x02, (byte) 0x2e, (byte) 0x40, (byte) 0x51, (byte) 0x80,
                (byte) 0x3b, (byte) 0x00, (byte) 0xd9, (byte) 0x69, (byte) 0xc2, (byte) 0x32,
                (byte) 0x0c, (byte) 0x66, (byte) 0x19, (byte) 0x1c, (byte) 0x84, (byte) 0xae,
                (byte) 0xc0, (byte) 0x0e, (byte) 0xb2, (byte) 0x00, (byte) 0xbc, (byte) 0x40,
                (byte) 0x2b, (byte) 0xc0, (byte) 0x2f, (byte) 0x28, (byte) 0x0a, (byte) 0xf0,
                (byte) 0xfb, (byte) 0x53, (byte) 0x78, (byte) 0x81, (byte) 0x0b, (byte) 0x56,
                (byte) 0xaa, (byte) 0x14, (byte) 0x28, (byte) 0xae, (byte) 0xa5, (byte) 0x78,
                (byte) 0xb0, (byte) 0x17, (byte) 0x53, (byte) 0x0f
                // clang-format on
        };
        assertEquals(226, observed.length);

        // As observed (magic first byte zeroed): not decodable.
        String a = tryDecompress(observed, 553);
        assertTrue(a.startsWith("THROW") || a.startsWith("ERROR"), "corrupt payload must fail, got " + a);

        // Restore the magic's first byte (0x28): the full 553-byte packet comes back.
        byte[] restored = observed.clone();
        restored[0] = ZSTD_MAGIC[0];
        String b = tryDecompress(restored, 553);
        assertTrue(b.startsWith("OK bytes=553"), "magic-restored payload must decode to 553 bytes, got " + b);
        System.out.println("REPRO cracked: as-observed -> " + a + " | magic-restored -> " + b
                + " payload head " + hex(restored));
    }

    private static String tryDecompress(byte[] payload, int expected) {
        try {
            byte[] out = new byte[expected];
            long size = Zstd.decompress(out, payload);
            if (Zstd.isError(size)) {
                return "ERROR " + Zstd.getErrorName(size);
            }
            return "OK bytes=" + size + " head=" + hex(out);
        } catch (Throwable t) {
            return "THROW " + t;
        }
    }
}