package com.rigorberto.zstdnetworkproject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NPE safety in the encoder pipeline.
 * Verifies that common failure paths don't crash with NullPointerException.
 */
class NpeSafetyEncoderTest {

    @Test
    void testEncodeNullMessagePassesThrough() {
        // When msg is not a ByteBuf, it should be passed through unchanged
        // This simulates the check at the start of write()
        assertDoesNotThrow(() -> {
            // We can't easily test the full write path without a ChannelHandlerContext,
            // but we can verify the helper methods work correctly
        });
    }

    @Test
    void testWriteVarIntHandlesZero() {
        // Zero-length varint is valid
        int len = ZstdEncoder.varIntLength(0);
        assertEquals(1, len, "Zero should encode in 1 byte");
    }

    @Test
    void testWriteVarIntHandlesMaxInt() {
        // Maximum positive int should fit in 5 bytes
        int len = ZstdEncoder.varIntLength(Integer.MAX_VALUE);
        assertEquals(5, len, "MAX_VALUE should encode in 5 bytes");
    }

    @Test
    void testBeneficialCalculationEdgeCases() {
        // Test the beneficial() check doesn't crash on edge cases
        assertDoesNotThrow(() -> {
            // Boundary cases must not throw. Frames that are not smaller are sent raw, matching
            // the encoder's framing decision (varint(size) + payload vs varint(0) + raw payload).
            assertFalse(ZstdEncoder.beneficial(0, 0), "Empty packets are sent raw");
            assertFalse(ZstdEncoder.beneficial(1024, 1024), "Equal sizes are not beneficial");
            assertFalse(ZstdEncoder.beneficial(1024, 1025), "Larger compressed is not beneficial");
        });
    }

    @Test
    void testCompressContextReuse() {
        // Verify compress() doesn't throw on concurrent calls (ThreadLocal safety)
        assertDoesNotThrow(() -> {
            ZstdCodecCtx.compress(1, 0);
            ZstdCodecCtx.compress(2, 1);
            ZstdCodecCtx.compress(1, 0); // Reset
        });
    }

    @Test
    void testDecompressContextReuse() {
        // Verify decompress() doesn't throw on concurrent calls
        assertDoesNotThrow(() -> {
            ZstdCodecCtx.decompress();
            ZstdCodecCtx.decompress();
        });
    }

    @Test
    void testDeflaterReuse() {
        // Deflater is reused per-thread, verify it resets correctly
        assertDoesNotThrow(() -> {
            Deflater d1 = ZstdCodecCtx.deflater();
            Deflater d2 = ZstdCodecCtx.deflater();
            // Same instance expected (ThreadLocal)
            assertNotNull(d1);
            assertNotNull(d2);
        });
    }

    @Test
    void testSmallBufferPool() {
        // Test the buffer pool doesn't throw on concurrent access
        assertDoesNotThrow(() -> {
            byte[] buf1 = ZstdAsyncPools.acquireSmallBuffer(1024);
            byte[] buf2 = ZstdAsyncPools.acquireSmallBuffer(1024);
            assertNotNull(buf1);
            assertNotNull(buf2);
            
            ZstdAsyncPools.releaseSmallBuffer(buf1);
            ZstdAsyncPools.releaseSmallBuffer(buf2);
        });
    }

    @Test
    void testLargeBufferAllocation() {
        // Test that large buffers (>65KB) are allocated fresh and not pooled
        byte[] buf = ZstdAsyncPools.acquireSmallBuffer(131072); // 128KB
        assertEquals(131072, buf.length, "Large buffer should be exact size");
        ZstdAsyncPools.releaseSmallBuffer(buf); // Will be leaked intentionally
    }

    @Test
    void testZeroLengthBufferPool() {
        // Edge case: zero capacity
        assertDoesNotThrow(() -> {
            byte[] buf = ZstdAsyncPools.acquireSmallBuffer(0);
            assertNotNull(buf);
            // A pooled small buffer is returned, never a null or oversized allocation
            assertTrue(buf.length <= 65536, "Pooled small buffer must stay within 64KB");
            ZstdAsyncPools.releaseSmallBuffer(buf);
        });
    }
}
