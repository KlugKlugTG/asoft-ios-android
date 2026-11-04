package com.asoft.ios;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QMP over QEMU's UNIX socket (loopback TCP is blocked for apps in a restricted standby
 * bucket). QEMU serves one QMP client at a time, so the app keeps one connection for the
 * guest's whole life (Guest owns it). Nothing here may run on the UI thread: connect()
 * waits for QEMU's greeting.
 */
final class Qmp {
    private final LocalSocket sock = new LocalSocket();
    private OutputStream out;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    /** Blocks until QEMU greets us, at most @timeoutMs. */
    void connect(String path, int timeoutMs) throws IOException {
        sock.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
        sock.setSoTimeout(timeoutMs);
        out = sock.getOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        if (in.readLine() == null) {                // the greeting
            throw new IOException("QEMU closed the QMP socket");
        }
        sock.setSoTimeout(0);
        write("{\"execute\":\"qmp_capabilities\"}");
        Thread drain = new Thread(() -> {
            try {
                while (in.readLine() != null) {
                    // replies and events: nothing to do with them yet
                }
            } catch (IOException ignored) {
                // QEMU went away
            }
        }, "qmp-read");
        drain.setDaemon(true);
        drain.start();
    }

    private void write(String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Queues one command; a failure only means QEMU is gone. */
    void send(String json) {
        sender.execute(() -> {
            try {
                write(json);
            } catch (IOException ignored) {
                // QEMU went away
            }
        });
    }

    private static String abs(float x, float y) {
        int ax = Math.max(0, Math.min(32767, Math.round(x * 32767)));
        int ay = Math.max(0, Math.min(32767, Math.round(y * 32767)));
        return "{\"type\":\"abs\",\"data\":{\"axis\":\"x\",\"value\":" + ax + "}},"
                + "{\"type\":\"abs\",\"data\":{\"axis\":\"y\",\"value\":" + ay + "}}";
    }

    /** Touch at x, y in 0..1 of the guest screen; down/up with button = true/false, null = move only. */
    void touch(float x, float y, Boolean button) {
        String ev = abs(x, y);
        if (button != null) {
            ev += ",{\"type\":\"btn\",\"data\":{\"down\":" + button + ",\"button\":\"left\"}}";
        }
        send("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[" + ev + "]}}");
    }

    /** A finger from the left edge to 70 % across at mid height: iOS's back gesture. */
    void edgeSwipeBack() {
        sender.execute(() -> {
            try {
                write("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[" + abs(0.002f, 0.5f)
                        + ",{\"type\":\"btn\",\"data\":{\"down\":true,\"button\":\"left\"}}]}}");
                for (int i = 1; i <= 12; i++) {
                    Thread.sleep(16);
                    write("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":["
                            + abs(0.002f + 0.7f * i / 12, 0.5f) + "]}}");
                }
                write("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":["
                        + "{\"type\":\"btn\",\"data\":{\"down\":false,\"button\":\"left\"}}]}}");
            } catch (IOException | InterruptedException ignored) {
                // QEMU went away
            }
        });
    }

    /** iOS 16 maps F1 = Home, F2 = side button, F3/F4 = volume down/up (bsd/dev/sweet_vinput.c). */
    void key(String qcode) {
        send("{\"execute\":\"send-key\",\"arguments\":{\"keys\":[{\"type\":\"qcode\",\"data\":\"" + qcode + "\"}]}}");
    }

    /** One edge of a key, for a button the user holds. */
    void keyEvent(String qcode, boolean down) {
        send("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[{\"type\":\"key\",\"data\":{\"down\":"
                + down + ",\"key\":{\"type\":\"qcode\",\"data\":\"" + qcode + "\"}}}]}}");
    }

    void close() {
        sender.shutdownNow();
        try {
            sock.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
