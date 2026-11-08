package com.asoft.ios;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    /** One per command sent, in order: QEMU answers a client's commands in the order they came. */
    private final ConcurrentLinkedQueue<CompletableFuture<String>> replies = new ConcurrentLinkedQueue<>();
    /** A command's write and its place in the replies, as one step: the sender and the finger's thread both write. */
    private final Object writing = new Object();
    private volatile boolean closed;

    /**
     * The running guest is paused because a disk write found the phone's storage full: QEMU's default for a
     * full disk (-drive werror=enospc) stops the guest and says so in a BLOCK_IO_ERROR event with "nospace";
     * nothing is lost, and "cont" goes on once there is room. The screen says so (it froze silently before).
     */
    static volatile boolean noSpace;
    private static final java.util.regex.Pattern NOSPACE = java.util.regex.Pattern.compile("\"nospace\":\\s*true");

    /** Blocks until QEMU greets us, at most @timeoutMs. */
    void connect(String path, int timeoutMs) throws IOException {
        noSpace = false;            // a new QEMU
        sock.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
        sock.setSoTimeout(timeoutMs);
        out = sock.getOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        if (in.readLine() == null) {                // the greeting
            throw new IOException("QEMU closed the QMP socket");
        }
        sock.setSoTimeout(0);
        if (!writeCounted(new CompletableFuture<>(), "{\"execute\":\"qmp_capabilities\"}")) {
            throw new IOException("QEMU closed the QMP socket");
        }
        Thread drain = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    // events carry "event" and answer nothing; anything else answers the oldest command
                    if (!line.startsWith("{\"timestamp\"") && !line.contains("\"event\":")) {
                        CompletableFuture<String> f = replies.poll();
                        if (f != null) {
                            f.complete(line);
                        }
                    } else if (line.contains("\"BLOCK_IO_ERROR\"") && NOSPACE.matcher(line).find()) {
                        noSpace = true;     // a disk write found the phone's storage full: QEMU paused the guest
                    } else if (line.contains("\"RESUME\"")) {
                        noSpace = false;
                    }
                }
            } catch (IOException ignored) {
                // QEMU went away
            }
            for (CompletableFuture<String> f; (f = replies.poll()) != null; ) {
                f.complete(null);
            }
        }, "qmp-read");
        drain.setDaemon(true);
        drain.start();
        Thread finger = new Thread(this::fingerLoop, "qmp-finger");
        finger.setDaemon(true);
        finger.start();
    }

    private void write(String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Writes @json with @f as its answer's place; on a failed write @f completes with null. */
    private boolean writeCounted(CompletableFuture<String> f, String json) {
        synchronized (writing) {
            replies.add(f);
            try {
                write(json);
                return true;
            } catch (IOException e) {
                replies.remove(f);
                f.complete(null);       // QEMU went away
                return false;
            }
        }
    }

    /** Queues one command; its answer, or null once QEMU is gone, completes the future. */
    CompletableFuture<String> send(String json) {
        CompletableFuture<String> f = new CompletableFuture<>();
        sender.execute(() -> writeCounted(f, json));
        return f;
    }

    /**
     * Runs one command and waits at most @timeoutMs for its answer: the reply line, or null when
     * QEMU went away or took longer. Never on the UI thread.
     */
    String call(String json, long timeoutMs) {
        try {
            return send(json).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** An HMP command through QMP (savevm has no plain QMP form that waits). */
    String hmp(String line, long timeoutMs) {
        return call("{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\""
                + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}", timeoutMs);
    }

    private static String abs(float x, float y) {
        int ax = Math.max(0, Math.min(32767, Math.round(x * 32767)));
        int ay = Math.max(0, Math.min(32767, Math.round(y * 32767)));
        return "{\"type\":\"abs\",\"data\":{\"axis\":\"x\",\"value\":" + ax + "}},"
                + "{\"type\":\"abs\",\"data\":{\"axis\":\"y\",\"value\":" + ay + "}}";
    }

    // -- the finger: its own thread with one command in flight. A move not yet sent gives way to the
    // newest one (QEMU's tablet keeps only the newest position until the guest reads it anyway), a
    // down or an up is never merged or dropped, and everything goes in the order it came. Without the
    // wait a busy QEMU (a save holds its monitor for seconds) let the moves pile up in the socket.
    //
    // Moves go at iOS's own pace: the next one once a new iOS frame reached the screen (Render.uploads)
    // and MIN_GAP_MS passed, else MAX_GAP_MS after the last. Every display frame's move (~120 a second)
    // drowned iOS 26 under TCG: in a 3 s drag in Settings it drew 1-3 frames and took the moves in bursts
    // afterwards (a list that froze, then a flick taken for the home gesture); at ~14 moves a second it
    // drew 43-49 frames during the same drag (APK-PLAN §6.22). A move with a down or an up queued behind
    // it is dropped: the edge carries the newest position.
    private static final long MIN_GAP_MS = 16, MAX_GAP_MS = 66;

    private static final class Touch {
        final boolean move;
        String json;

        Touch(boolean move, String json) {
            this.move = move;
            this.json = json;
        }
    }

    private final ArrayDeque<Touch> touches = new ArrayDeque<>();

    /** Touch at x, y in 0..1 of the guest screen; down/up with button = true/false, null = move only. */
    void touch(float x, float y, Boolean button) {
        String ev = abs(x, y);
        if (button != null) {
            ev += ",{\"type\":\"btn\",\"data\":{\"down\":" + button + ",\"button\":\"left\"}}";
        }
        String json = "{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[" + ev + "]}}";
        synchronized (touches) {
            Touch last = touches.peekLast();
            if (button == null && last != null && last.move) {
                last.json = json;
            } else {
                touches.addLast(new Touch(button == null, json));
            }
            touches.notifyAll();
        }
    }

    private void fingerLoop() {
        long lastMove = 0, framesAtMove = -1;
        while (!closed) {
            String json = null;
            synchronized (touches) {
                while (json == null) {
                    if (closed) {
                        return;
                    }
                    Touch head = touches.peekFirst();
                    long wait = 0;
                    if (head != null && !head.move) {
                        json = touches.pollFirst().json;
                    } else if (head != null && touches.size() > 1) {
                        touches.pollFirst();            // an edge behind it has a newer position
                        continue;
                    } else if (head != null) {
                        long now = android.os.SystemClock.uptimeMillis();
                        long since = now - lastMove;
                        if (since >= MAX_GAP_MS || since >= MIN_GAP_MS && Render.uploads() != framesAtMove) {
                            json = touches.pollFirst().json;
                            lastMove = now;
                            framesAtMove = Render.uploads();
                        } else {
                            wait = Math.max(1, Math.min(4, MAX_GAP_MS - since));
                        }
                    }
                    if (json == null) {
                        try {
                            touches.wait(wait);         // 0: until a touch comes
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
            CompletableFuture<String> f = new CompletableFuture<>();
            if (!writeCounted(f, json)) {
                return;                 // QEMU went away
            }
            try {
                f.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // a busy monitor (a save): go on; moves merge in the queue meanwhile
            } catch (Exception e) {
                return;
            }
        }
    }

    /** A finger from the left edge to 70 % across at mid height: iOS's back gesture. */
    void edgeSwipeBack() {
        Thread t = new Thread(() -> {
            try {
                touch(0.002f, 0.5f, true);
                for (int i = 1; i <= 12; i++) {
                    Thread.sleep(16);
                    touch(0.002f + 0.7f * i / 12, 0.5f, null);
                }
                touch(0.702f, 0.5f, false);
            } catch (InterruptedException ignored) {
                // leaving
            }
        }, "qmp-back");
        t.setDaemon(true);
        t.start();
    }

    /** iOS 16 maps F1 = Home, F2 = side button, F3/F4 = volume down/up (bsd/dev/sweet_vinput.c). */
    void key(String qcode) {
        send("{\"execute\":\"send-key\",\"arguments\":{\"keys\":[{\"type\":\"qcode\",\"data\":\"" + qcode + "\"}]}}");
    }

    /**
     * iOS 26: the usb-consumer device's @button (home, power, volup, voldown): "press" (down, then up
     * 100 ms after the guest read it), "down" or "up" (held until the up: iOS repeats volume steps).
     */
    void consumer(String how, String button) {
        send("{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"" + how
                + "\",\"value\":\"" + button + "\"}}");
    }

    /** iOS 26: one press of the usb-consumer device's @button (home, power, volup, voldown). */
    void press(String button) {
        consumer("press", button);
    }

    /** watchOS: turns QEMU's usb-crown by @units (positive: a list moves on down, as the PC's wheel down). */
    void crown(int units) {
        send("{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucrown\",\"property\":\"rotate\","
                + "\"value\":" + units + "}}");
    }

    /** One edge of a key, for a button the user holds. */
    void keyEvent(String qcode, boolean down) {
        send("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[{\"type\":\"key\",\"data\":{\"down\":"
                + down + ",\"key\":{\"type\":\"qcode\",\"data\":\"" + qcode + "\"}}}]}}");
    }

    void close() {
        closed = true;
        synchronized (touches) {
            touches.notifyAll();
        }
        sender.shutdownNow();
        try {
            sock.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
