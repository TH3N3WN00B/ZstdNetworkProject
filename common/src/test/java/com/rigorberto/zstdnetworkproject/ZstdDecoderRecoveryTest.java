package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of {@link ZstdDecoder} framing driven through an {@link EmbeddedChannel}.
 *
 * <p>Covers the vanilla frame layouts plus the "size-prefix-less" variants some non-vanilla
 * servers and proxies produce (see {@code ZstdDecoder.inflateOrPassThrough}): a zlib stream with
 * no {@code varint(uncompressedSize)} prefix must be decompressed and forwarded as the packet
 * (the crash observed against such a peer was a raw zlib stream handed to the vanilla decoder,
 * which parsed {@code 78 9C ...} as packet id 120 {@code store_cookie} with a garbage key),
 * while a completely uncompressed frame without the prefix keeps the existing raw pass-through.
 */
class ZstdDecoderRecoveryTest {

    /** Builds an opaque "packet" whose first byte reads as a small positive VarInt (size > 0). */
    private static byte[] packet(int len) {
        byte[] p = new byte[len];
        p[0] = 0x05;
        for (int i = 1; i < len; i++) {
            p[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return p;
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

    private static byte[] varInt(int value) {
        ByteBuf b = Unpooled.buffer(5);
        while ((value & ~0x7F) != 0) {
            b.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        b.writeByte(value);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
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

    private static byte[] decode(byte[] frame) {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder());
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame)),
                    "One decoded frame (packet) expected");
            ByteBuf out = channel.readInbound();
            assertNotNull(out, "Decoder must produce a downstream frame");
            try {
                byte[] result = new byte[out.readableBytes()];
                out.getBytes(out.readerIndex(), result);
                return result;
            } finally {
                out.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void vanillaZlibFrameDecodes() {
        byte[] packet = packet(300);
        byte[] frame = concat(varInt(packet.length), zlib(packet));
        assertArrayEquals(packet, decode(frame));
    }

    @Test
    void vanillaRawFramePassesThrough() {
        byte[] packet = packet(300);
        byte[] frame = concat(varInt(0), packet);
        assertArrayEquals(packet, decode(frame));
    }

    @Test
    void sizePrefixLessZlibFrameIsDecompressed() {
        // The whole envelope is a zlib stream (starts 78 9C ...); the decoder must recover the
        // packet instead of forwarding the raw compressed bytes to the downstream parser.
        byte[] packet = packet(300);
        byte[] frame = zlib(packet); // no varint(0) raw frame with size prefix, no varint(size)
        assertArrayEquals(packet, decode(frame));
    }

    @Test
    void sizePrefixLessRawFramePassesThroughUnchanged() {
        // A completely uncompressed frame without the compression prefix: the consumed size
        // prefix byte is really the start of the packet, so it must be restored byte-for-byte.
        byte[] packet = packet(300);
        assertArrayEquals(packet, decode(packet));
    }

    @Test
    void garbageFrameThatIsNotZlibPassesThrough() {
        // Neither the payload nor the whole envelope is a valid zlib stream: the raw pass-through
        // must reproduce the original frame exactly (and must not throw).
        byte[] garbage = new byte[64];
        garbage[0] = 0x05; // small positive size varint so the decoder reaches the zlib branch
        for (int i = 1; i < garbage.length; i++) {
            garbage[i] = (byte) (0x01 + (i * 13 & 0x7F)); // incompressible, never a zlib stream
        }
        assertArrayEquals(garbage, decode(garbage));
    }

    @Test
    void truncatedZlibStreamWithoutThePrefixDoesNotRecover() {
        // A zlib header followed by too few bytes: the whole-envelope inflate must reject it and
        // fall back to the raw pass-through (no crash, no partial packet forwarded).
        byte[] full = zlib(packet(300));
        byte[] truncated = new byte[8];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertArrayEquals(truncated, decode(truncated));
    }

    @Test
    void emptyAndNullSafeChecks() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder());
        try {
            assertNull(channel.readInbound(), "No output from an empty feed");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rateLimitTripsOnRapidLargeDeclarations() {
        // 400 bytes/s with burst 400: two zlib frames declaring 300 bytes each in quick succession
        // overdraw the bucket and the second one must land the connection.
        ZstdSettings settings = new ZstdSettings();
        settings.setRateLimitBytesPerSecond(400);
        settings.setRateLimitBurstBytes(400);
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder(settings));
        try {
            byte[] packet = packet(256);
            byte[] frame = concat(varInt(packet.length), zlib(packet));
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame)),
                    "First frame fits the burst budget");
            DecoderException ex = assertThrows(DecoderException.class,
                    () -> channel.writeInbound(Unpooled.wrappedBuffer(frame)),
                    "Second frame overdraws the bucket and must trip the gate");
            assertTrue(ex.getCause() instanceof IllegalStateException,
                    "Decoder must surface the rate-limit trip as IllegalStateException, was: "
                            + ex.getCause());
        } finally {
            try {
                channel.finishAndReleaseAll();
            } catch (DecoderException expected) {
                // EmbeddedChannel rethrows the recorded trip at teardown; already asserted above.
            }
        }
    }

    @Test
    void rateLimitDisabledByDefaultDoesNotInterfere() {
        // The default settings (rate 0) must behave exactly like the no-arg constructor.
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder(new ZstdSettings()));
        try {
            byte[] packet = packet(300);
            byte[] frame = concat(varInt(packet.length), zlib(packet));
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame)));
            ByteBuf out = channel.readInbound();
            assertNotNull(out);
            try {
                byte[] result = new byte[out.readableBytes()];
                out.getBytes(out.readerIndex(), result);
                assertArrayEquals(packet, result);
            } finally {
                out.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void zeroedMagicZstdFrameIsRepairedDecoded() {
        // The encoder bug (pre-0.3.1, zstd-jni writing at a buffer's absolute base) produced
        // frames opening `00 b5 2f fd ...`: magic `28 b5 2f fd` with its first byte zeroed. A peer
        // running that build must still be understood: restore the byte and decompress.
        byte[] packet = packet(553);
        byte[] compressed = Zstd.compress(packet);
        assertTrue(compressed.length > 4, "compressed payload must exist");
        assertTrue((compressed[0] & 0xFF) == 0x28, "sanity: zstd magic first byte");
        compressed[0] = 0x00; // simulate the corruption
        byte[] frame = concat(varInt(packet.length), compressed);
        assertArrayEquals(packet, decode(frame));
    }

    @Test
    void zeroedMagicTailGarbagePassesThroughNotMisclassified() {
        // A payload that merely *looks* like a zeroed magic (`00 b5 2f fd ...`) but does not decode
        // as zstd even with the byte restored must not be guessed: the frame keeps the raw
        // pass-through exactly as the zlib recovery does.
        byte[] garbage = new byte[80];
        garbage[0] = 0x00;
        garbage[1] = (byte) 0xB5;
        garbage[2] = 0x2F;
        garbage[3] = (byte) 0xFD;
        for (int i = 4; i < garbage.length; i++) {
            garbage[i] = (byte) (0x03 + (i * 7 & 0x7F)); // incompressible, valid zstd by chance ~never
        }
        int declared = 200;
        byte[] frame = concat(varInt(declared), garbage);
        assertArrayEquals(frame, decode(frame));
    }

    @Test
    void implausibleRepairRatioNeverProbes() {
        // The repair probe allocates a byte[uncompressedSize] heap array to trial-decompress the
        // restored magic, so an implausible declared-size-to-frame-bytes ratio must never reach it:
        // a tiny garbage frame declaring e.g. 8 MiB would otherwise force an 8 MiB heap allocation
        // per frame at almost no bandwidth cost. Same thresholds as the zlib guard: >= 1 MiB
        // declared with more than a 10x ratio is skipped (and the frame then hits the zlib guard).
        assertFalse(ZstdDecoder.isPlausibleRepairCandidate(64, 8 * 1024 * 1024),
                "64-byte frame declaring 8 MiB must skip the probe");
        assertFalse(ZstdDecoder.isPlausibleRepairCandidate(4096, 2 * 1024 * 1024),
                "4 KiB frame declaring 2 MiB (>10x at >=1 MiB) must skip the probe");
        assertTrue(ZstdDecoder.isPlausibleRepairCandidate(226, 553),
                "the observed legacy repaired frame (226 bytes -> 553) is still probed");
        assertTrue(ZstdDecoder.isPlausibleRepairCandidate(1024 * 1024, 4 * 1024 * 1024),
                "a 4x ratio at a large size is legitimate and still probed");
        assertTrue(ZstdDecoder.isPlausibleRepairCandidate(4, 1023),
                "below 1 MiB declared the allocation is bounded anyway, so it always probes");
    }

    @Test
    void implausibleRepairRatioFrameIsRejectedLikeZlib() {
        // Zeroed-magic garbage declaring 8 MiB in 64 frame bytes: the plausibility gate skips the
        // probe, the frame falls through to the zlib path and is rejected by the zlib ratio guard
        // (identical 1 MiB / 10x thresholds) with an error instead of being guessed at. Never an
        // 8 MiB heap probe, never a silent pass-through of a hostile declared size.
        byte[] garbage = new byte[64];
        garbage[0] = 0x00;
        garbage[1] = (byte) 0xB5;
        garbage[2] = 0x2F;
        garbage[3] = (byte) 0xFD;
        for (int i = 4; i < garbage.length; i++) {
            garbage[i] = (byte) (i & 0xFF);
        }
        byte[] frame = concat(varInt(8 * 1024 * 1024), garbage);
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdDecoder());
        try {
            assertThrows(RuntimeException.class,
                    () -> channel.writeInbound(Unpooled.wrappedBuffer(frame)),
                    "implausible ratio must be rejected, not decoded nor passed through");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}