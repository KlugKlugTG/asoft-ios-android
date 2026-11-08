package com.asoft.ios;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The guest's root shell (com.asoft.bash on the serial console) for the setup's boots: ios26/scripts/
 * guest-shell.py in Java, over the console's UNIX socket (Guest.startSetup). Each command goes as
 * echo @@TAG"S"@@; CMD; echo @@TAG"E"@@, so its output is told from the console's echo and the kernel's
 * messages; one that gets no answer is sent again with a new tag (idempotent commands only).
 */
final class GuestConsole implements AutoCloseable {
    private final File path;
    private LocalSocket s;
    private InputStream in;
    private OutputStream out;
    private final StringBuilder buf = new StringBuilder();
    private int n;

    GuestConsole(File path) {
        this.path = path;
    }

    private boolean connect() {
        if (s != null) {
            return true;
        }
        if (!path.exists()) {
            return false;
        }
        try {
            LocalSocket l = new LocalSocket();
            l.connect(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            l.setSoTimeout(500);
            s = l;
            in = l.getInputStream();
            out = l.getOutputStream();
            out.write('\r');
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The command's output, or null when it got none within @waitMs (sent again every @retryMs). */
    String run(String cmd, long waitMs, long retryMs) {
        long until = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < until) {
            if (!connect()) {
                sleep(1000);
                continue;
            }
            n++;
            String tag = "G" + (System.currentTimeMillis() % 1_000_000_000L) + n + "G";
            String start = "@@" + tag + "S@@", end = "@@" + tag + "E@@";
            try {
                out.write(("echo @@" + tag + "\"S\"@@; " + cmd + "; echo @@" + tag + "\"E\"@@\r").getBytes(StandardCharsets.UTF_8));
                out.flush();
                long again = Math.min(until, System.currentTimeMillis() + retryMs);
                byte[] b = new byte[65536];
                while (System.currentTimeMillis() < again) {
                    int r;
                    try {
                        r = in.read(b);
                    } catch (java.net.SocketTimeoutException e) {
                        r = 0;
                    }
                    if (r < 0) {
                        throw new IOException("the console closed");
                    }
                    if (r > 0) {
                        buf.append(new String(b, 0, r, StandardCharsets.ISO_8859_1));
                        if (buf.length() > (4 << 20)) {
                            buf.delete(0, buf.length() - (1 << 20));
                        }
                    }
                    int i = buf.indexOf(start);
                    int j = i >= 0 ? buf.indexOf(end, i + 1) : -1;
                    if (j > i && i >= 0) {
                        String o = buf.substring(i + start.length(), j);
                        buf.delete(0, j + end.length());
                        return new String(o.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).replaceAll("^[\r\n]+|[\r\n]+$", "");
                    }
                }
            } catch (IOException e) {
                close();
                sleep(1000);
            }
        }
        return null;
    }

    /** True when @cmd printed a line that is exactly @word (the command line's own echo has the word too). */
    boolean says(String cmd, String word, long waitMs) {
        String o = run(cmd, waitMs, 30_000);
        if (o == null) {
            return false;
        }
        for (String l : o.split("\r?\n")) {
            if (l.trim().equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // gone
            }
        }
        s = null;
        in = null;
        out = null;
    }
}
