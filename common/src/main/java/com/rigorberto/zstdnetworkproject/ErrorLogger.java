package com.rigorberto.zstdnetworkproject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ErrorLogger {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ErrorLogger() {
    }

    public static void log(Path errorFile, String context, Throwable error) {
        if (error == null) {
            return;
        }
        try {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));

            String entry = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + context + System.lineSeparator()
                    + sw + System.lineSeparator();

            Path parent = errorFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(errorFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Logging must never crash the game: every call site is already handling an earlier
            // failure, so a secondary IOException, SecurityException or NPE here must not replace
            // (or escape past) the diagnostic the caller was trying to record.
        }
    }
}
