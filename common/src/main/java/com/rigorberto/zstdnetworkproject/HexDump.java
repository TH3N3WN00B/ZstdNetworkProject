package com.rigorberto.zstdnetworkproject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in full-frame hex dumps for protocol debugging: when {@code hex-dump: true} is set in the
 * config, every frame crossing the zstd encoder/decoder is appended to a log file with sizes,
 * direction, peer address and a classic offset/hex/ASCII block. This makes both ends of a broken
 * connection comparable byte for byte (e.g. to prove a server batches several packets into one
 * frame).
 *
 * <p>Disabled by default and hard-capped ({@link #MAX_FRAME_BYTES} per frame, {@link #MAX_TOTAL_BYTES}
 * per file) so an accidentally left-on setting cannot fill the disk or stall the event loop for long.
 */
public final class HexDump {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Largest frame payload dumped in full; longer frames are truncated at this mark. */
    private static final int MAX_FRAME_BYTES = 8192;

    /** Total bytes appended before dumping auto-disables itself. */
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    private static volatile Path file;
    private static volatile boolean enabled;
    private static final AtomicLong written = new AtomicLong();
    private static final Object LOCK = new Object();

    private HexDump() {
    }

    /** Configures the dump target once at startup; later calls are ignored so runtime code cannot redirect it. */
    public static void configure(Path logFile, boolean on) {
        if (file == null) {
            file = logFile.toAbsolutePath();
            enabled = on && logFile != null;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Best-effort remote address of the channel owning {@code ctx}, for correlating both ends. */
    public static String peerOf(ChannelHandlerContext ctx) {
        try {
            return String.valueOf(ctx.channel().remoteAddress());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Dumps the readable bytes of {@code buf} without moving its reader index. */
    public static void dump(String tag, String header, ByteBuf buf) {
        if (!enabled || !buf.isReadable()) {
            return;
        }
        int length = buf.readableBytes();
        int captured = Math.min(length, MAX_FRAME_BYTES);
        byte[] data = new byte[captured];
        try {
            buf.getBytes(buf.readerIndex(), data);
        } catch (RuntimeException e) {
            return; // unreadable buffer: never crash the pipeline for a debug aid
        }
        write(tag, header + " frameBytes=" + length, data, length > captured);
    }

    public static void dump(String tag, String header, byte[] data) {
        if (!enabled || data.length == 0) {
            return;
        }
        int captured = Math.min(data.length, MAX_FRAME_BYTES);
        byte[] slice = captured == data.length ? data : java.util.Arrays.copyOf(data, captured);
        write(tag, header + " frameBytes=" + data.length, slice, data.length > captured);
    }

    /** Text-only entry (no hex), e.g. pipeline listings or lifecycle notes. */
    public static void note(String tag, String line) {
        if (!enabled) {
            return;
        }
        writeRaw(tag, line);
    }

    private static void write(String tag, String header, byte[] data, boolean truncated) {
        StringBuilder sb = new StringBuilder(128 + data.length * 4);
        sb.append(header);
        if (truncated) {
            sb.append(" TRUNCATED_AT=").append(data.length);
        }
        sb.append(System.lineSeparator()).append(hexBlock(data));
        writeRaw(tag, sb.toString());
    }

    private static void writeRaw(String tag, String body) {
        if (written.get() >= MAX_TOTAL_BYTES) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(TIMESTAMP) + "] [" + tag + "] " + body + System.lineSeparator();
        synchronized (LOCK) {
            if (written.get() >= MAX_TOTAL_BYTES) {
                return;
            }
            try {
                Path path = file;
                if (path == null) {
                    return;
                }
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                written.addAndGet(line.length());
                if (written.get() >= MAX_TOTAL_BYTES) {
                    Files.writeString(path, "[" + LocalDateTime.now().format(TIMESTAMP)
                            + "] hex-dump limit reached (" + MAX_TOTAL_BYTES + " bytes), stopping"
                            + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                }
            } catch (IOException | RuntimeException e) {
                // tracing must never crash the game or server
            }
        }
    }

    /** Classic 16-bytes-per-row dump: offset, hex pairs and printable ASCII gutter. */
    private static String hexBlock(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 4 + 64);
        for (int row = 0; row < data.length; row += 16) {
            sb.append(String.format("%08x  ", row));
            for (int i = 0; i < 16; i++) {
                if (row + i < data.length) {
                    int b = data[row + i] & 0xFF;
                    if (b < 0x10) {
                        sb.append('0');
                    }
                    sb.append(Integer.toHexString(b)).append(i == 7 ? '-' : ' ');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ');
            for (int i = 0; i < 16 && row + i < data.length; i++) {
                int b = data[row + i] & 0xFF;
                sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
