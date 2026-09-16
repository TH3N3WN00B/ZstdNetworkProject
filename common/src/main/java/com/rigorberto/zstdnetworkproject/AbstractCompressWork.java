package com.rigorberto.zstdnetworkproject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.nio.channels.ClosedChannelException;

/**
 * FIFO work item that zstd-compresses a packet, either synchronously on the event loop (small
 * packets) or on the shared worker pool (large packets), while keeping per-channel packet order.
 * Shared by both encoders ({@link ZstdEncoder} and {@link ZstdFrameEncoder}); the resulting frame
 * shape differs between the normal and the frame encoder and is supplied by the owning encoder.
 *
 * <p>{@link #submitAsync} hands the input to the worker thread, which compresses straight out of
 * its memory. After {@code submitAsync} the event loop no longer touches {@code in} (the work owns
 * it), so the worker has exclusive access and {@link #complete} releases the single reference it
 * took ownership of.
 */
abstract class AbstractCompressWork implements OrderedAsyncProcessor.Work {

    protected final ChannelHandlerContext ctx;
    protected final OrderedAsyncProcessor processor;
    protected final ByteBuf in;
    protected final int size;
    protected final int level;
    protected final int workers;
    protected final ZstdSettings settings;
    protected final ChannelPromise promise;

    AbstractCompressWork(ChannelHandlerContext ctx, OrderedAsyncProcessor processor, ByteBuf in,
                         int size, int level, int workers, ZstdSettings settings,
                         ChannelPromise promise) {
        this.ctx = ctx;
        this.processor = processor;
        this.in = in;
        this.size = size;
        this.level = level;
        this.workers = workers;
        this.settings = settings;
        this.promise = promise;
    }

    /**
     * Compresses {@code in} into a pooled buffer holding the complete frame (or the raw form when
     * {@code compress-if-beneficial} rejects the compressed one). The caller still owns the single
     * reference to {@code in} until {@link #processSync} or {@link #complete} releases it.
     */
    protected abstract ByteBuf compressDirectOrCopy();

    @Override
    public int queuedBytes() {
        return size;
    }

    @Override
    public boolean isAsync() {
        return size >= ZstdAsyncPools.ASYNC_THRESHOLD;
    }

    @Override
    public void processSync() {
        try {
            ctx.write(compressDirectOrCopy(), promise);
        } catch (Throwable t) {
            promise.tryFailure(t);
        } finally {
            in.release();
        }
    }

    @Override
    public void submitAsync() {
        ZstdAsyncPools.executor().execute(() -> {
            ByteBuf out;
            try {
                out = compressDirectOrCopy();
            } catch (Throwable t) {
                in.release();
                ctx.executor().execute(() -> {
                    promise.tryFailure(t);
                    processor.onAsyncComplete(ctx);
                });
                return;
            }
            ctx.executor().execute(() -> complete(out));
        });
    }

    private void complete(ByteBuf out) {
        try {
            if (!ctx.channel().isActive()) {
                out.release();
                promise.tryFailure(new ClosedChannelException());
            } else {
                ctx.writeAndFlush(out, promise);
            }
        } finally {
            in.release();
            processor.onAsyncComplete(ctx);
        }
    }

    @Override
    public void discard() {
        in.release();
        promise.tryFailure(new ClosedChannelException());
    }
}