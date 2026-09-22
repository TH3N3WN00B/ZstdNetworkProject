package com.rigorberto.zstdnetworkproject;

/**
 * Per-channel inbound decompression rate gate (token bucket), used to cap how many uncompressed
 * bytes a single connection can make the decoder commit per second. Zstd's wire format and every
 * existing per-frame guard ({@link ZstdDecoder#MAX_UNCOMPRESSED_SIZE}, the zlib ratio guard, the
 * repair-probe gate) stay untouched; this is an opt-in traffic-shaping layer on top of them.
 *
 * <p>Abuse scenario it closes: a peer emits a stream of frames that each declare a large
 * uncompressed size (e.g. repeated near-{@link ZstdDecoder#MAX_UNCOMPRESSED_SIZE} declarations).
 * Per frame the existing guards are satisfied, but the aggregate decompression work and direct
 * memory churn across the connection can still overwhelm a server. The rate limiter charges every
 * inbound frame (its declared size, or the raw payload length for raw frames) against a per-channel
 * token bucket that refills at {@code limitBytesPerSecond} up to {@code burstBytes}; the first
 * frame that would overdraw the bucket lands the connection (logged once per channel), exactly like
 * {@link OrderedAsyncProcessor#MAX_QUEUED_BYTES}.
 *
 * <p>Single-threaded by design: {@code charge} is only invoked from the channel's event loop inside
 * {@code ZstdDecoder.decode}, so no locking is needed. A disabled limiter is never constructed
 * ({@code of} returns null), keeping the common path allocation-free.
 */
final class ChannelRateLimiter {

    /** Bucket capacity: how much burst a connection is allowed. Never below one second's rate. */
    private final long burstBytes;

    /** Token refill rate, in bytes per second. */
    private final long limitBytesPerSecond;

    /** Current tokens. Doubles as the deficit book for the trip decision. */
    private double tokens;

    /** nanosecond timestamp of the last refill, for drift-free elapsed-time computation. */
    private long lastRefillNanos;

    /** Whether the limit was already exceeded (connection is closing; keep refusing). */
    private boolean tripped;

    private ChannelRateLimiter(long limitBytesPerSecond, long burstBytes) {
        this.limitBytesPerSecond = limitBytesPerSecond;
        this.burstBytes = burstBytes;
        this.tokens = burstBytes;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Builds the limiter for {@code settings}, or returns {@code null} when rate limiting is
     * disabled (the default: {@code rate-limit-bytes-per-second: 0}).
     */
    static ChannelRateLimiter of(ZstdSettings settings) {
        long rate = settings.getRateLimitBytesPerSecond();
        if (rate <= 0) {
            return null;
        }
        long burst = settings.getRateLimitBurstBytes();
        if (burst <= 0) {
            // Default burst: two seconds' worth of the configured rate, so a legit chunk burst is
            // never the thing that trips the gate.
            burst = Math.max(rate, rate * 2);
        }
        return new ChannelRateLimiter(rate, burst);
    }

    boolean isEnabled() {
        return limitBytesPerSecond > 0;
    }

    /**
     * Charges {@code bytes} against the connection's budget, refilling tokens from the elapsed
     * time first. Returns false (and permanently trips the limiter) the first time the bucket
     * would be overdrawn; the caller then logs once and closes the channel.
     */
    boolean charge(long bytes) {
        if (tripped) {
            return false;
        }
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1e9;
        if (elapsedSeconds > 0) {
            tokens = Math.min(burstBytes, tokens + elapsedSeconds * limitBytesPerSecond);
            lastRefillNanos = now;
        }
        if (tokens < bytes) {
            tripped = true;
            return false;
        }
        tokens -= bytes;
        return true;
    }
}