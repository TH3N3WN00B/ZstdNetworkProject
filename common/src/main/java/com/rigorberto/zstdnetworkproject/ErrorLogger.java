package com.rigorberto.zstdnetworkproject;

import java.io.IOException;
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

            Files.createDirectories(errorFile.getParent());
            Files.writeString(errorFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // logging must never crash the game
        }
    }
}
