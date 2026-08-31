package com.rigorberto.zstdnetworkproject;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayDeque;

/**
 * Per-channel FIFO that keeps packets in protocol order even when some of them are compressed or
 * decompressed on a shared worker pool. If a large packet is being processed asynchronously, every
 * packet that arrives afterwards is queued and released (or dispatched) only when the in-flight
 * packet completes.
 *
 * <p>All state here is confined to the owning channel's event loop: {@link #add} is invoked from
 * the handler's {@code write}/{@code decode} path, and async completions are marshalled back onto
 * the event loop before touching the queue. No locks are needed.
 *
 * <p>Queued work holds buffers that Netty's own {@code ChannelOutboundBuffer} knows nothing about,
 * so {@code Channel.isWritable()} would keep reporting the channel as writable while this queue
 * grows. {@link #MAX_QUEUED_BYTES} bounds that growth explicitly: a peer that stalls its socket (or
 * a saturated worker pool) trips the limit and the channel is closed instead of exhausting the
 * JVM's direct memory.
 */
final class OrderedAsyncProcessor {

    /** Whether drained work is dispatched inbound (decoder) or outbound (encoder). */
    enum Direction {
        /** Decoded packets were pushed with {@code fireChannelRead}: close the read cycle. */
        INBOUND,
        /** Encoded packets were pushed with {@code write}: flush them to the socket. */
        OUTBOUND
    }

    /**
     * Largest amount of not-yet-processed packet payload a single channel may hold in this queue.
     * Reaching it means the peer is not draining (or the worker pool is saturated) far beyond any
     * legitimate burst, so the connection is closed rather than allowed to grow without bound.
     * Override with {@code -Dzstdnetworkproject.max-queued-bytes=N} or the environment variable
     * {@code ZSTDNETWORKPROJECT_MAX_QUEUED_BYTES}.
     */
    static final long MAX_QUEUED_BYTES = ZstdAsyncPools.longValue(
            "zstdnetworkproject.max-queued-bytes", "ZSTDNETWORKPROJECT_MAX_QUEUED_BYTES",
            16L * 1024 * 1024, 64L * 1024);

    /** A unit of work. All methods except {@code submitAsync} run on the event loop. */
    interface Work {
        /** Whether this unit should be processed on the shared worker pool. */
        boolean isAsync();

        /** Payload bytes this unit keeps alive while it sits in the queue. */
        int queuedBytes();

        /** Process synchronously on the event loop. */
        void processSync();

        /** Kick off asynchronous processing; completion must return via {@code onAsyncComplete}. */
        void submitAsync();

        /** Called on channel close for units that never got processed (release/fail). */
        void discard();
    }

    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private final Direction direction;
    private long queuedBytes;
    private boolean inFlight;
    private boolean overflowed;

    OrderedAsyncProcessor(Direction direction) {
        this.direction = direction;
    }

    boolean isIdle() {
        return queue.isEmpty() && !inFlight;
    }

    void add(ChannelHandlerContext ctx, Work work) {
        if (queuedBytes + work.queuedBytes() > MAX_QUEUED_BYTES) {
            overflow(ctx, work);
            return;
        }
        queuedBytes += work.queuedBytes();
        queue.addLast(work);
        drain(ctx);
    }

    /**
     * Backpressure limit hit: drop everything still queued (failing its promises) and close the
     * channel. Logged once per channel so a misbehaving peer cannot flood the console.
     */
    private void overflow(ChannelHandlerContext ctx, Work rejected) {
        rejected.discard();
        if (!overflowed) {
            overflowed = true;
            System.err.println("[zstdnetworkproject] Closing connection to " + ctx.channel().remoteAddress()
                    + ": more than " + MAX_QUEUED_BYTES + " bytes of packets are waiting for "
                    + "(de)compression, which means the peer is not reading. Raise "
                    + "-Dzstdnetworkproject.max-queued-bytes if this is a legitimate workload.");
        }
        discardAll();
        ctx.close();
    }

    private boolean drain(ChannelHandlerContext ctx) {
        boolean processedSync = false;
        while (!queue.isEmpty() && !inFlight) {
            Work work = queue.peekFirst();
            if (!work.isAsync()) {
                pollFirst();
                work.processSync();
                processedSync = true;
            } else {
                inFlight = true;
                pollFirst();
                work.submitAsync();
                return processedSync;
            }
        }
        return processedSync;
    }

    private Work pollFirst() {
        Work work = queue.pollFirst();
        if (work != null) {
            queuedBytes -= work.queuedBytes();
        }
        return work;
    }

    /** Must be called on the event loop after an asynchronous unit completes. */
    void onAsyncComplete(ChannelHandlerContext ctx) {
        inFlight = false;
        boolean processedSync = drain(ctx);
        if (!processedSync || !ctx.channel().isActive()) {
            return;
        }
        // The packets just dispatched arrived outside the read/write cycle Netty set up for the
        // original event, so the matching end-of-cycle signal has to be raised here: downstream
        // handlers that batch on channelReadComplete (inbound) or only hit the socket on flush
        // (outbound) would otherwise sit on this work until unrelated traffic woke them up.
        if (direction == Direction.INBOUND) {
            ctx.fireChannelReadComplete();
        } else {
            ctx.flush();
        }
    }

    void discardAll() {
        while (!queue.isEmpty()) {
            pollFirst().discard();
        }
        queuedBytes = 0;
    }
}
