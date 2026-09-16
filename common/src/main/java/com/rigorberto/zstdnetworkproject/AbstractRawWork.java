package com.rigorberto.zstdnetworkproject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.nio.channels.ClosedChannelException;

/**
 * FIFO work item that encodes a sub-threshold packet verbatim, keeping per-channel packet order
 * while an asynchronous packet is in flight. Shared by both encoders ({@link ZstdEncoder} and
 * {@link ZstdFrameEncoder}); the exact frame shape (the size-prefix differs between the normal and
 * the frame encoder) is supplied by the owning encoder.
 */
abstract class AbstractRawWork implements OrderedAsyncProcessor.Work {

    protected final ChannelHandlerContext ctx;
    protected final ByteBuf msg;
    protected final int size;
    protected final ChannelPromise promise;

    AbstractRawWork(ChannelHandlerContext ctx, ByteBuf msg, int size, ChannelPromise promise) {
        this.ctx = ctx;
        this.msg = msg;
        this.size = size;
        this.promise = promise;
    }

    /** Encodes the sub-threshold packet as a raw frame without copying. Releases {@code msg}. */
    protected abstract void writeRaw(ChannelHandlerContext ctx, ByteBuf msg, int size,
                                     ChannelPromise promise);

    @Override
    public int queuedBytes() {
        return size;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void processSync() {
        writeRaw(ctx, msg, size, promise);
    }

    @Override
    public void submitAsync() {
    }

    @Override
    public void discard() {
        msg.release();
        promise.tryFailure(new ClosedChannelException());
    }
}