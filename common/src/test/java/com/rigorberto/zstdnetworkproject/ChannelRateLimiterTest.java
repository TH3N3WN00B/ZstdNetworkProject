package com.rigorberto.zstdnetworkproject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the opt-in per-channel inbound rate gate.
 *
 * <p>Verifies the token bucket semantics that {@code rate-limit-bytes-per-second} /
 * {@code rate-limit-burst-bytes} expose: disabled by default (no limiter), burst allowance,
 * sustained-rate refill and one-way tripping once the budget is overdrawn.
 */
class ChannelRateLimiterTest {

    @Test
    void disabledSettingsYieldNoLimiter() {
        ZstdSettings settings = new ZstdSettings(); // defaults: rate 0, burst 0
        assertNull(ChannelRateLimiter.of(settings));
    }

    @Test
    void burstAllowsASingleLargeChargeUpToCapacity() {
        // 1 MiB/s with default burst (2s = 2 MiB): one 1.5 MiB frame fits, a second one trips.
        ZstdSettings settings = new ZstdSettings();
        settings.setRateLimitBytesPerSecond(1024 * 1024);
        ChannelRateLimiter limiter = ChannelRateLimiter.of(settings);
        assertTrue(limiter.isEnabled());
        assertTrue(limiter.charge(3L * 1024 * 1024 / 2));
        assertFalse(limiter.charge(3L * 1024 * 1024 / 2));
    }

    @Test
    void sustainedRateRefillsBetweenCharges() {
        // 1000 bytes/s, explicit burst of 1000: two 900-byte charges 1.1 s apart both fit because
        // the bucket refills 1000 bytes while waiting.
        ZstdSettings settings = new ZstdSettings();
        settings.setRateLimitBytesPerSecond(1000);
        settings.setRateLimitBurstBytes(1000);
        ChannelRateLimiter limiter = ChannelRateLimiter.of(settings);
        assertTrue(limiter.charge(900));
        sleep(1100);
        assertTrue(limiter.charge(900));
    }

    @Test
    void tripIsOneWay() {
        ZstdSettings settings = new ZstdSettings();
        settings.setRateLimitBytesPerSecond(100);
        settings.setRateLimitBurstBytes(100);
        ChannelRateLimiter limiter = ChannelRateLimiter.of(settings);
        assertTrue(limiter.charge(50));
        assertFalse(limiter.charge(60), "Overdraw must trip the limiter");
        assertFalse(limiter.charge(1), "Tripped limiter must keep refusing");
        sleep(500);
        assertFalse(limiter.charge(1), "Refill must not un-trip the limiter");
    }

    @Test
    void exactCapacityChargeIsAllowed() {
        ZstdSettings settings = new ZstdSettings();
        settings.setRateLimitBytesPerSecond(500);
        settings.setRateLimitBurstBytes(500);
        ChannelRateLimiter limiter = ChannelRateLimiter.of(settings);
        assertTrue(limiter.charge(500), "A charge exactly equal to the bucket must be allowed");
        assertFalse(limiter.charge(1));
    }

    @Test
    void zeroOrNegativeRatesStayDisabled() {
        ZstdSettings negative = new ZstdSettings();
        negative.setRateLimitBytesPerSecond(-10);
        assertNull(ChannelRateLimiter.of(negative));
        ZstdSettings zero = new ZstdSettings();
        zero.setRateLimitBytesPerSecond(0);
        assertNull(ChannelRateLimiter.of(zero));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}