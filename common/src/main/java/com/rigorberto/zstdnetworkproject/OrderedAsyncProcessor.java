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
 */
final class OrderedAsyncProcessor {

    /** A unit of work. All methods except {@code submitAsync} run on the event loop. */
    interface Work {
        /** Whether this unit should be processed on the shared worker pool. */
        boolean isAsync();

        /** Process synchronously on the event loop. */
        void processSync();

        /** Kick off asynchronous processing; completion must return via {@code onAsyncComplete}. */
        void submitAsync();

        /** Called on channel close for units that never got processed (release/fail). */
        void discard();
    }

    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private boolean inFlight;

    boolean isIdle() {
        return queue.isEmpty() && !inFlight;
    }

    void add(ChannelHandlerContext ctx, Work work) {
        queue.addLast(work);
        drain(ctx);
    }

    private void drain(ChannelHandlerContext ctx) {
        while (!queue.isEmpty() && !inFlight) {
            Work work = queue.peekFirst();
            if (!work.isAsync()) {
                queue.pollFirst();
                work.processSync();
            } else {
                inFlight = true;
                queue.pollFirst();
                work.submitAsync();
                return;
            }
        }
    }

    /** Must be called on the event loop after an asynchronous unit completes. */
    void onAsyncComplete(ChannelHandlerContext ctx) {
        inFlight = false;
        drain(ctx);
    }

    void discardAll() {
        while (!queue.isEmpty()) {
            queue.pollFirst().discard();
        }
    }
}
