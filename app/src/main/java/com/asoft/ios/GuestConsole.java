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
 *
 * While connected, a reader thread drains the console all the time. QEMU's socket chardev writes the
 * guest's console output with the main lock held and waits when the socket is full: a client that stops
 * reading froze the whole watch 30 s after its last command (2026-11-08, the kernel's AppleSEPKeyStore
 * lines filled the socket). guest-shell.py on the PC disconnects after each call, so it never met this.
 */
final class GuestConsole implements AutoCloseable {
    private final File path;
    private LocalSocket s;
    private OutputStream out;
    private Thread reader;
    private final StringBuilder buf = new StringBuilder();
    private int n;

    GuestConsole(File path) {
        this.path = path;
    }

    private synchronized boolean connect() {
        if (s != null) {
            return true;
        }
        if (!path.exists()) {
            return false;
        }
        try {
            LocalSocket l = new LocalSocket();
            l.connect(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            s = l;
            out = l.getOutputStream();
            InputStream in = l.getInputStream();
            reader = new Thread(() -> drain(l, in), "guest-console");
            reader.setDaemon(true);
            reader.start();
            out.write('\r');
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void drain(LocalSocket l, InputStream in) {
        byte[] b = new byte[65536];
        try {
            int r;
            while ((r = in.read(b)) >= 0) {
                synchronized (buf) {
                    buf.append(new String(b, 0, r, StandardCharsets.ISO_8859_1));
                    if (buf.length() > (4 << 20)) {
                        buf.delete(0, buf.length() - (1 << 20));
                    }
                }
            }
        } catch (IOException ignored) {
            // closed
        }
        synchronized (this) {
            if (s == l) {
                s = null;
                out = null;
            }
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
                OutputStream o;
                synchronized (this) {
                    o = out;
                }
                if (o == null) {
                    continue;
                }
                o.write(("echo @@" + tag + "\"S\"@@; " + cmd + "; echo @@" + tag + "\"E\"@@\r").getBytes(StandardCharsets.UTF_8));
                o.flush();
                long again = Math.min(until, System.currentTimeMillis() + retryMs);
                while (System.currentTimeMillis() < again) {
                    synchronized (buf) {
                        int i = buf.indexOf(start);
                        int j = i >= 0 ? buf.indexOf(end, i + 1) : -1;
                        if (j > i && i >= 0) {
                            String got = buf.substring(i + start.length(), j);
                            buf.delete(0, j + end.length());
                            return new String(got.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
                                    .replaceAll("^[\r\n]+|[\r\n]+$", "");
                        }
                    }
                    sleep(200);
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
    public synchronized void close() {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // gone
            }
        }
        s = null;
        out = null;
    }
}
