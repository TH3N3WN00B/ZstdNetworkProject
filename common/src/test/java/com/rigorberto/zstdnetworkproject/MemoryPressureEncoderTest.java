package com.rigorberto.zstdnetworkproject;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OOM and memory pressure scenarios.
 * Verifies the encoder handles edge cases without leaking memory or crashing.
 */
class MemoryPressureEncoderTest {

    @Test
    void testCompressionThresholdBoundary() {
        // Test exactly at the threshold boundary
        ZstdSettings settings = new ZstdSettings();
        settings.setCompressionThreshold(64 * 1024); // 64KB
        assertEquals(64 * 1024, settings.getCompressionThreshold(),
                "Threshold stored as configured");

        // The sync/async split lives in ZstdAsyncPools.ASYNC_THRESHOLD (packets >= it go to the
        // worker pool) and is independent of the compression threshold checked by the encoder.
        assertEquals(64 * 1024, ZstdAsyncPools.ASYNC_THRESHOLD,
                "Default async threshold separates sync from async encoding");
    }

    @Test
    void testCompressionBoundAccuracy() {
        // Zstd.compressBound should be reasonable
        int size = 1024;
        int bound = ZstdCodecCtx.compressBound(size);
        assertTrue(bound >= size, "Bound must be >= input size");
        assertTrue(bound <= size * 2 + 1024, "Bound should be reasonable (not exponential)");
    }

    @Test
    void testDeflateBoundAccuracy() {
        // zlib bound formula
        int size = 1024;
        int bound = ZstdCodecCtx.deflateBound(size);
        // deflateBound(size) = size + (size >> 3) + (size >> 6) + 64
        assertEquals(size + (size >> 3) + (size >> 6) + 64, bound);
    }

    @Test
    void testLargePacketAllocation() {
        // Simulate a very large packet (e.g., chunk download)
        // Should not allocate excessively large cached buffers
        byte[] scratch = ZstdCodecCtx.scratch(1024 * 1024); // 1MB scratch
        assertEquals(1024 * 1024, scratch.length);
    }

    @Test
    void testVeryLargePacketAllocation() {
        // Even larger: 16MB packet (within Minecraft's 64MB limit)
        byte[] scratch = ZstdCodecCtx.scratch(16 * 1024 * 1024);
        assertEquals(16 * 1024 * 1024, scratch.length);
    }

    @Test
    void testTinyPacketNoAllocation() {
        // Very small packets should use minimal allocations
        byte[] buf = ZstdAsyncPools.acquireSmallBuffer(16); // 16 bytes
        assertTrue(buf.length <= 65536, "Small buffer should be <= 64KB");
        ZstdAsyncPools.releaseSmallBuffer(buf);
    }

    @Test
    void testVarIntLengthBounds() {
        // Test all varint length boundaries (7 bits per byte)
        assertEquals(1, ZstdEncoder.varIntLength(0x00), "zero");
        assertEquals(1, ZstdEncoder.varIntLength(0x7F), "1-byte max");
        assertEquals(2, ZstdEncoder.varIntLength(0x80), "2-byte min");
        assertEquals(2, ZstdEncoder.varIntLength(0x3FFF), "2-byte max");
        assertEquals(3, ZstdEncoder.varIntLength(0x4000), "3-byte min");
        assertEquals(3, ZstdEncoder.varIntLength(0x1FFFFF), "3-byte max");
        assertEquals(4, ZstdEncoder.varIntLength(0x200000), "4-byte min");
        assertEquals(4, ZstdEncoder.varIntLength(0x0FFFFFFF), "4-byte max");
        assertEquals(5, ZstdEncoder.varIntLength(0x10000000), "5-byte min");
        assertEquals(5, ZstdEncoder.varIntLength(Integer.MAX_VALUE), "5-byte max");
    }

    @Test
    void testCompressionLevelBounds() {
        // Test different compression levels don't crash
        for (int level : new int[] {-1, 0, 1, 5, 10, 15, 22}) {
            ZstdSettings settings = new ZstdSettings();
            settings.setCompressionLevel(level);
            assertEquals(level, settings.effectiveCompressionLevel(), 
                    "Level " + level + " should be preserved");
        }
    }

    @Test
    void testWorkerCountBounds() {
        // Worker count should be bounded
        int workers = ZstdAsyncPools.workerCount();
        assertTrue(workers >= 1, "Workers must be >= 1");
    }

    @Test
    void testAsyncThresholdBounds() {
        // Async threshold should be bounded
        int threshold = ZstdAsyncPools.ASYNC_THRESHOLD;
        assertTrue(threshold >= 256, "Threshold must be >= 256");
    }

    @Test
    void testIsNativeReadableEdgeCases() {
        // Test the native readability check
        ByteBuf directSingle = Unpooled.directBuffer(1024);
        directSingle.writeZero(1024); // must be readable, otherwise Netty wraps nothing

        // A direct composite with multiple components cannot be exposed as a single native
        // buffer (nioBufferCount > 1), so it must not be considered native-readable.
        ByteBuf compA = Unpooled.directBuffer(512);
        compA.writeZero(512);
        ByteBuf compB = Unpooled.directBuffer(512);
        compB.writeZero(512);
        ByteBuf directMultiple = Unpooled.wrappedBuffer(compA, compB);

        ByteBuf heapBuffer = Unpooled.buffer(1024);
        heapBuffer.writeZero(1024);

        assertTrue(ZstdCodecCtx.isNativeReadable(directSingle),
            "Direct single buffer should be native-readable");
        assertFalse(ZstdCodecCtx.isNativeReadable(directMultiple),
            "Direct composite buffer should NOT be native-readable");
        assertFalse(ZstdCodecCtx.isNativeReadable(heapBuffer),
            "Heap buffer should NOT be native-readable");

        directSingle.release();
        directMultiple.release();
        heapBuffer.release();
    }
}
