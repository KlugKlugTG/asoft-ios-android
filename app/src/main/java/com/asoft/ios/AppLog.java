package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The app's own log: what it decided and why (starts, saves, loads, qemu-img failures). Kept in
 * the app's private files and, readable over USB and adb even on a release build, in
 * Android/data/com.asoft.ios/files/log/: with QEMU's and the guest's last lines of the run
 * before. Nothing typed into iOS is logged.
 */
final class AppLog {
    private AppLog() {}

    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "log");
        d.mkdirs();
        return d;
    }

    static synchronized void note(Context c, String s) {
        String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + s;
        for (File f : new File[] {new File(Guest.runDir(c), "app.txt"), new File(dir(c), "app.txt")}) {
            try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
                w.println(line);
            } catch (IOException ignored) {
                // nowhere to say it
            }
        }
    }

    /** QEMU's and the guest's last lines, before a new start overwrites them. */
    static void keepRunLogs(Context c) {
        for (String n : new String[] {"qemu-stderr.txt", "serial.txt"}) {
            File from = new File(Guest.runDir(c), n);
            if (!from.isFile()) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(from.toPath(), StandardCharsets.ISO_8859_1);
                List<String> tail = lines.subList(Math.max(0, lines.size() - 400), lines.size());
                Files.write(new File(dir(c), "last-" + n).toPath(), tail, StandardCharsets.ISO_8859_1);
            } catch (IOException ignored) {
                // keep going without it
            }
        }
    }
}
