package com.rigorberto.zstdnetworkproject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the vanilla-compatible zlib fallback frame layout of
 * {@link ZstdEncoder} (the path used for packets at/above the compression threshold while the
 * peer has not yet proven it speaks zstd).
 *
 * <p>Historically {@code ZstdEncoder.writeZlib} wrote the {@code varint(uncompressedSize)} with
 * {@code setByte} (which does not move the writer index) and then appended the zlib payload,
 * which overwrote the varint: the emitted frame was a bare zlib stream (e.g. {@code 78 9C ...}).
 * Peers then read the CMF byte {@code 0x78} as the declared size (120), failed to inflate the
 * remainder and disconnected with a protocol desync. This test locks the correct layout:
 * {@code varint(uncompressedSize) + valid zlib stream} that inflates back to the packet.
 */
class ZstdEncoderWriteZlibTest {

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

    private static byte[] inflate(byte[] data) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] out = new byte[Math.max(64, data.length * 8)];
        int written = 0;
        while (!inflater.finished()) {
            if (written == out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            int n = inflater.inflate(out, written, out.length - written);
            if (n == 0 && !inflater.finished() && inflater.needsInput()) {
                inflater.end();
                throw new IllegalStateException("truncated zlib stream");
            }
            written += n;
        }
        inflater.end();
        return Arrays.copyOf(out, written);
    }

    @Test
    void zlibFallbackFrameOpensWithTheUncompressedSizeVarint() throws Exception {
        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionThreshold(16); // packets >= 16 bytes must be compressed
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdEncoder(settings, true));
        try {
            byte[] packet = new byte[400];
            for (int i = 0; i < packet.length; i++) {
                packet[i] = (byte) (i * 7 + 3); // patterned so zlib compresses it
            }
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(packet)),
                    "The encoder must emit an outbound frame");
            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame, "The zlib fallback must produce a frame");
            try {
                int declared = readVarInt(frame);
                assertEquals(packet.length, declared,
                        "varint(uncompressedSize) must be the first bytes of the frame");
                byte[] payload = new byte[frame.readableBytes()];
                frame.readBytes(payload);
                assertArrayEquals(packet, inflate(payload),
                        "the zlib payload must inflate back to the original packet");
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void smallPacketsStillGoRawWithAZeroSizePrefix() {
        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionThreshold(16);
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdEncoder(settings, true));
        try {
            byte[] packet = new byte[8]; // below the threshold -> raw
            for (int i = 0; i < packet.length; i++) {
                packet[i] = (byte) i;
            }
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(packet)));
            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame);
            try {
                assertEquals(0, readVarInt(frame), "raw frames must open with varint(0)");
                byte[] payload = new byte[frame.readableBytes()];
                frame.readBytes(payload);
                assertArrayEquals(packet, payload, "raw payload must stay byte-for-byte identical");
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void monotonicGrowthPacketDeflatesToTheDeclaredSize() throws Exception {
        // A packet whose zlib form is only slightly smaller must still carry the exact
        // uncompressed size so the peer's declared-vs-produced check matches.
        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionThreshold(16);
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdEncoder(settings, true));
        try {
            byte[] packet = new byte[256];
            for (int i = 0; i < packet.length; i++) {
                packet[i] = (byte) i; // incompressible-ish pattern
            }
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(packet)));
            ByteBuf frame = channel.readOutbound();
            assertNotNull(frame);
            try {
                int declared = readVarInt(frame);
                byte[] payload = new byte[frame.readableBytes()];
                frame.readBytes(payload);
                assertArrayEquals(packet, inflate(payload));
                assertEquals(packet.length, declared);
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void varIntLengthMatchesWrittenBytesForBoundaryValues() {
        // varIntLength() must agree with the number of bytes the encoder writes.
        for (int value : new int[] {0, 1, 0x7F, 0x80, 0x3FFF, 0x4000, 0x1FFFFF, 0x200000, 0xFFFFFFF, Integer.MAX_VALUE}) {
            assertEquals(ZstdEncoder.varIntLength(value), encodedLength(value),
                    "varIntLength mismatch for " + value);
        }
    }

    private static int encodedLength(int value) {
        ByteBuf buf = Unpooled.buffer(5);
        int temp = value;
        while ((temp & ~0x7F) != 0) {
            buf.writeByte((temp & 0x7F) | 0x80);
            temp >>>= 7;
        }
        buf.writeByte(temp);
        int len = buf.readableBytes();
        buf.release();
        return len;
    }
}