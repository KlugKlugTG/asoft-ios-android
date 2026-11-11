package com.asoft.ios;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Apple's IPSWs, member by member: the PC installer's ios26/scripts/ipsw-get.py ported. The zip's
 * central directory (zip64) comes from the end of the file, then only the members a step needs: in
 * 32 MB chunks over 8 HTTP Range connections, each chunk written at its own offset, the finished ones
 * listed in a ".done" file next to the output (as ipsw-get.py's OUT.done), so an app kill, a reboot
 * or a lost network costs only the chunks in flight. CRC-32 is checked at the end. A deflated member
 * up to 256 MB comes in one request and is inflated on the way; a bigger one (iOS 16's SystemOS,
 * 1.1 GB in the zip) is fetched in chunks as it is and inflated from that copy afterwards.
 *
 * Two kinds of output:
 *  - {@link Kind#FILE}: the member's own bytes, inflated if the zip deflated them (ipsw-get.py's OUT);
 *  - {@link Kind#SPARSE}: the member's local header and data at their own offsets in a sparse copy of
 *    the IPSW, which also holds the zip's tail (central directory, end records). The helper VM's jobs
 *    read that copy as the IPSW itself (Python's zipfile, dd at a member's offset), without the bytes of
 *    the members nobody needs.
 * No Android classes: the setup service drives it, and it runs on a PC JVM for tests.
 */
final class IpswFetch {
    static final int CHUNK = 32 << 20;
    // Apple's CDN throttles per connection (on 2026-11-08 the aggregate was ~3.7 MB/s over 8, while
    // single connections reached 6 MB/s): more connections raise the aggregate until a per-IP cap.
    static final int CONNECTIONS = 16;
    /**
     * A deflated member up to this size comes whole in one request and is inflated in memory (its bytes and
     * the inflated ones on the heap at once); a bigger one goes in parts and is inflated as a stream. 256 MB
     * was too much for an app's heap (256 MB growth limit on the S25): cloudOS's 233 MB ramdisk ran it out
     * of memory (2026-11-08).
     */
    static final long ONE_REQUEST = 16L << 20;
    private static final int TRIES = 10;

    enum Kind { FILE, SPARSE }

    /** A member of the zip, as its central directory has it. */
    static final class Entry {
        String name;
        int method;
        long csize, usize, lho;
        long crc;
        /** Where its data starts: after the local header with its own name and extra field (read lazily). */
        long data = -1;

        long end() {
            return data + csize;
        }
    }

    /** The zip's directory and tail. */
    static final class Index {
        long size;
        long cdOff;
        byte[] tail;            // [cdOff, size): central directory and end records
        final Map<String, Entry> entries = new LinkedHashMap<>();
    }

    /** What the screen shows; called from the worker threads. */
    interface Listener {
        void progress(Item item);
        void log(String line);
    }

    /** One member to fetch, and its state for the screen. */
    static final class Item {
        final String member;
        final File out;
        final Kind kind;
        /** Expected bytes (the size the installer pins), or 0: then the zip's own. */
        final long expect;
        Entry entry;
        final AtomicLong done = new AtomicLong();      // bytes on disk that count (chunks finished + this session's)
        volatile long total;
        volatile String state = "waiting";            // waiting, fetching, verifying, ok, failed
        volatile String error;

        Item(String member, File out, Kind kind, long expect) {
            this.member = member;
            this.out = out;
            this.kind = kind;
            this.expect = expect;
        }
    }

    /** The source: an http(s) URL, or the path of an IPSW on this device. */
    private final String url;
    private final File local;
    final AtomicInteger active = new AtomicInteger();          // connections transferring now
    final AtomicLong received = new AtomicLong();              // bytes received in this session
    private volatile boolean stop;
    private final Listener listener;
    private final int connections, chunk;
    /** The CDN's servers to use (http sources), null for a file on this device or before index(). */
    private CdnEdges cdn;
    /** Each worker's kept-alive connection to its server. */
    private final ThreadLocal<CdnEdges.Conn> conn = new ThreadLocal<>();

    IpswFetch(String url, Listener listener) {
        this(url, listener, CONNECTIONS, CHUNK);
    }

    IpswFetch(String url, Listener listener, int connections, int chunk) {
        this.url = url;
        File f = new File(url);
        this.local = url.startsWith("http://") || url.startsWith("https://") ? null : f;
        this.listener = listener;
        this.connections = connections;
        this.chunk = chunk;
    }

    /** Ends the transfers in flight (the chunks they had stay to do); run() returns with an IOException. */
    void stop() {
        stop = true;
    }

    boolean stopped() {
        return stop;
    }

    // -- ranges --------------------------------------------------------------------------------

    /** The CDN servers in use, for the screen ("3 servers, best 6.1 MB/s"); null for a local source. */
    CdnEdges cdn() {
        return cdn;
    }

    /** Closes this thread's kept-alive connection (a worker's last act). */
    private void dropConn() {
        CdnEdges.Conn c = conn.get();
        conn.remove();
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // gone already
            }
        }
    }

    /** Opens [@from, @from + @len) of the source; the caller reads exactly @len bytes, then closes it. */
    private InputStream open(long from, long len) throws IOException {
        if (local == null && cdn != null && cdn.any()) {
            CdnEdges.Conn c = conn.get();
            if (c == null || !c.reusable()) {
                dropConn();
                CdnEdges.Edge e = cdn.pick();
                if (e != null) {
                    c = cdn.connect(e, 15_000);
                    conn.set(c);
                }
            }
            if (c != null) {
                try {
                    return c.get(from, len);
                } catch (IOException ex) {
                    c.edge.fails.incrementAndGet();
                    dropConn();
                    throw ex;
                }
            }
        }
        if (local != null) {
            RandomAccessFile f = new RandomAccessFile(local, "r");
            f.seek(from);
            return new InputStream() {
                long left = len;

                @Override
                public int read() throws IOException {
                    byte[] b = new byte[1];
                    return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int o, int n) throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int r = f.read(b, o, (int) Math.min(n, left));
                    if (r > 0) {
                        left -= r;
                    }
                    return r;
                }

                @Override
                public void close() throws IOException {
                    f.close();
                }
            };
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("Range", "bytes=" + from + "-" + (from + len - 1));
        c.setRequestProperty("User-Agent", "ASoftware-iOS-setup");
        int code = c.getResponseCode();
        if (code != 206) {
            c.disconnect();
            // a server ignoring Range would send the whole multi-GB file
            throw new HttpError(code, code == 200 ? "the server ignored the byte range" : "HTTP " + code);
        }
        String cr = c.getHeaderField("Content-Range");
        if (cr == null || !cr.startsWith("bytes " + from + "-" + (from + len - 1) + "/")) {
            c.disconnect();
            throw new IOException("unexpected Content-Range " + cr);
        }
        InputStream in = c.getInputStream();
        return new InputStream() {
            @Override
            public int read() throws IOException {
                return in.read();
            }

            @Override
            public int read(byte[] b, int o, int n) throws IOException {
                return in.read(b, o, n);
            }

            @Override
            public void close() {
                c.disconnect();
            }
        };
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String m) {
            super(m);
            this.code = code;
        }
    }

    private byte[] read(long from, int len) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < TRIES && !stop; attempt++) {
            try (InputStream in = open(from, len)) {
                byte[] b = new byte[len];
                readFully(in, b, 0, len);
                return b;
            } catch (HttpError e) {
                dropConn();
                if (e.code == 404 || e.code == 403 || e.code == 410 || e.code == 200) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                dropConn();
                last = e;
            }
            pause(attempt);
        }
        throw stop ? new IOException("stopped") : last;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(Math.min(30_000, 2_000L * (attempt + 1)));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readFully(InputStream in, byte[] b, int o, int n) throws IOException {
        while (n > 0) {
            int r = in.read(b, o, n);
            if (r < 0) {
                throw new EOFException("the connection ended early");
            }
            o += r;
            n -= r;
        }
    }

    long size() throws IOException {
        if (local != null) {
            return local.length();
        }
        IOException last = null;
        for (int attempt = 0; attempt < TRIES && !stop; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(20_000);
                c.setReadTimeout(60_000);
                c.setRequestProperty("Range", "bytes=0-0");
                c.setRequestProperty("User-Agent", "ASoftware-iOS-setup");
                int code = c.getResponseCode();
                String cr = c.getHeaderField("Content-Range");
                if (code == 206 && cr != null && cr.contains("/")) {
                    return Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
                }
                if (code == 404 || code == 403 || code == 410) {
                    throw new HttpError(code, "HTTP " + code);
                }
                last = new IOException("no size from the server (HTTP " + code + ")");
            } catch (HttpError e) {
                throw e;
            } catch (IOException e) {
                last = e;
            } finally {
                c.disconnect();
            }
            pause(attempt);
        }
        throw stop ? new IOException("stopped") : last;
    }

    // -- the directory ---------------------------------------------------------------------------

    private static long u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int o) {
        return u16(b, o) | u16(b, o + 2) << 16;
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | u32(b, o + 4) << 32;
    }

    private static int lastIndexOf(byte[] b, byte[] what) {
        for (int i = b.length - what.length; i >= 0; i--) {
            boolean ok = true;
            for (int j = 0; j < what.length && ok; j++) {
                ok = b[i + j] == what[j];
            }
            if (ok) {
                return i;
            }
        }
        return -1;
    }

    Index index() throws IOException {
        Index ix = new Index();
        ix.size = size();
        if (local == null && cdn == null) {
            CdnEdges c = new CdnEdges(url);
            c.discover(listener);
            c.probe(ix.size / 3, listener);
            cdn = c;
        }
        int tailLen = (int) Math.min(ix.size, 65536 + 22 + 56 + 20);
        byte[] tail = read(ix.size - tailLen, tailLen);
        int i = lastIndexOf(tail, new byte[] {'P', 'K', 6, 6});
        long cdSize, cdOff;
        if (i >= 0) {
            cdSize = u64(tail, i + 40);
            cdOff = u64(tail, i + 48);
        } else {
            i = lastIndexOf(tail, new byte[] {'P', 'K', 5, 6});
            if (i < 0) {
                throw new IOException("not a zip file (no end record)");
            }
            cdSize = u32(tail, i + 12);
            cdOff = u32(tail, i + 16);
        }
        if (cdOff + cdSize > ix.size || ix.size - cdOff > (64 << 20)) {
            throw new IOException("bad central directory");
        }
        ix.cdOff = cdOff;
        ix.tail = read(cdOff, (int) (ix.size - cdOff));
        byte[] cd = ix.tail;
        int o = 0;
        while (o + 46 <= cdSize && u32(cd, o) == 0x02014b50L) {
            Entry e = new Entry();
            e.method = (int) u16(cd, o + 10);
            e.crc = u32(cd, o + 16);
            e.csize = u32(cd, o + 20);
            e.usize = u32(cd, o + 24);
            int nlen = (int) u16(cd, o + 28), xlen = (int) u16(cd, o + 30), clen = (int) u16(cd, o + 32);
            e.lho = u32(cd, o + 42);
            e.name = new String(cd, o + 46, nlen, StandardCharsets.UTF_8);
            int x = o + 46 + nlen, xend = x + xlen;
            while (x + 4 <= xend) {
                int id = (int) u16(cd, x), len = (int) u16(cd, x + 2);
                if (id == 1) {          // zip64: the fields that are 0xffffffff, in this order
                    int v = x + 4;
                    if (e.usize == 0xffffffffL) {
                        e.usize = u64(cd, v);
                        v += 8;
                    }
                    if (e.csize == 0xffffffffL) {
                        e.csize = u64(cd, v);
                        v += 8;
                    }
                    if (e.lho == 0xffffffffL) {
                        e.lho = u64(cd, v);
                    }
                }
                x += 4 + len;
            }
            ix.entries.put(e.name, e);
            o = xend + clen;
        }
        if (ix.entries.isEmpty()) {
            throw new IOException("empty central directory");
        }
        return ix;
    }

    /** Reads @e's local header: where its data starts, and the header's own bytes (for a sparse copy). */
    private byte[] localHeader(Entry e) throws IOException {
        byte[] h = read(e.lho, 30);
        if (u32(h, 0) != 0x04034b50L) {
            throw new IOException(e.name + ": bad local header");
        }
        int n = (int) (30 + u16(h, 26) + u16(h, 28));
        byte[] all = read(e.lho, n);
        e.data = e.lho + n;
        return all;
    }

    // -- fetching -------------------------------------------------------------------------------

    /** The ".done" list of @item: one line per finished chunk index. */
    static File doneFile(Item item) {
        return item.kind == Kind.FILE ? new File(item.out.getPath() + ".done")
                : new File(item.out.getPath() + "." + item.member.replaceAll("[^A-Za-z0-9._-]", "_") + ".done");
    }

    /** The marker of a member that passed its CRC check. */
    static File okFile(Item item) {
        return item.kind == Kind.FILE ? new File(item.out.getPath() + ".ok")
                : new File(item.out.getPath() + "." + item.member.replaceAll("[^A-Za-z0-9._-]", "_") + ".ok");
    }

    private static Set<Integer> readDone(File f) {
        Set<Integer> s = new HashSet<>();
        if (f.isFile()) {
            try {
                for (String l : Files.readAllLines(f.toPath())) {
                    l = l.trim();
                    if (!l.isEmpty()) {
                        s.add(Integer.parseInt(l));
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // a torn last line: that chunk comes again
            }
        }
        return s;
    }

    /** Bytes still to come for @items (after their own .done lists), for the free-space check. */
    static long remaining(List<Item> items, int chunk) {
        long left = 0;
        for (Item it : items) {
            if (okFile(it).isFile() || it.entry == null) {
                continue;
            }
            long len = it.entry.csize;
            int n = (int) ((len + chunk - 1) / chunk);
            Set<Integer> done = readDone(doneFile(it));
            for (int i = 0; i < n; i++) {
                if (!done.contains(i)) {
                    left += Math.min(chunk, len - (long) i * chunk);
                }
            }
            if (it.kind == Kind.FILE && it.entry.method == 8) {
                left += it.entry.usize;         // inflated beside the compressed copy
            }
        }
        return left;
    }

    /**
     * Looks up every item's member in @ix (and its local header), sets the sizes the screen shows,
     * and writes the sparse copy's skeleton (its full length, the zip tail) once.
     */
    void prepare(Index ix, List<Item> items) throws IOException {
        for (Item it : items) {
            Entry e = ix.entries.get(it.member);
            if (e == null) {
                throw new IOException(it.member + ": not in the IPSW");
            }
            it.entry = e;
            boolean ok = okFile(it).isFile();
            if (it.expect > 0 && it.expect != e.usize) {
                throw new IOException(it.member + ": " + e.usize + " bytes in the IPSW, " + it.expect + " expected");
            }
            byte[] lh = localHeader(e);
            it.total = e.csize;
            it.done.set(ok ? e.csize : doneBytes(it));
            it.state = ok ? "ok" : "waiting";
            if (it.kind == Kind.SPARSE) {
                skeleton(it.out, ix);
                try (FileChannel ch = FileChannel.open(it.out.toPath(), StandardOpenOption.WRITE)) {
                    ch.write(ByteBuffer.wrap(lh), e.lho);
                }
            }
        }
    }

    private long doneBytes(Item it) {
        long b = 0;
        for (int i : readDone(doneFile(it))) {
            b += Math.min(chunk, it.entry.csize - (long) i * chunk);
        }
        return b;
    }

    /** The sparse copy: the IPSW's length (no blocks behind it) and its tail at its own place. */
    static void skeleton(File out, Index ix) throws IOException {
        File mark = new File(out.getPath() + ".tail");
        if (out.isFile() && out.length() == ix.size && mark.isFile()) {
            return;
        }
        out.getParentFile().mkdirs();
        // SPARSE: on a PC's NTFS a file extended without it takes its whole length on the disk
        // (Android's f2fs and ext4 leave holes anyway); the tail ends at the IPSW's last byte
        try (FileChannel ch = FileChannel.open(out.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            if (ch.size() > ix.size) {
                ch.truncate(ix.size);
            }
            ByteBuffer b = ByteBuffer.wrap(ix.tail);
            long at = ix.cdOff;
            while (b.hasRemaining()) {
                at += ch.write(b, at);
            }
            ch.force(true);
        }
        try (FileOutputStream o = new FileOutputStream(mark)) {
            o.write(String.valueOf(ix.size).getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** Fetches what @items still lack, then checks each one's CRC-32. Throws when stopped or failed. */
    void run(List<Item> items) throws IOException {
        for (Item it : items) {
            if (stop) {
                throw new IOException("stopped");
            }
            if (okFile(it).isFile()) {
                it.state = "ok";
                listener.progress(it);
                continue;
            }
            try {
                fetch(it);
                it.state = "verifying";
                listener.progress(it);
                verify(it);
                it.state = "ok";
                it.error = null;
                listener.progress(it);
            } catch (IOException e) {
                it.state = stop ? "waiting" : "failed";
                it.error = e.getMessage();
                listener.progress(it);
                throw e;
            }
        }
    }

    private void fetch(Item it) throws IOException {
        Entry e = it.entry;
        if (it.kind == Kind.FILE && e.method == 8 && e.csize <= ONE_REQUEST) {
            it.state = "fetching";
            listener.progress(it);
            active.incrementAndGet();
            byte[] z;
            try {
                z = read(e.data, (int) e.csize);
            } finally {
                active.decrementAndGet();
            }
            received.addAndGet(z.length);
            it.done.set(e.csize);
            byte[] raw = inflate(z, e.usize);
            CRC32 crc = new CRC32();
            crc.update(raw);
            if (crc.getValue() != e.crc) {
                throw new IOException(it.member + ": damaged in transfer (CRC-32 " + Long.toHexString(crc.getValue())
                        + ", expected " + Long.toHexString(e.crc) + ")");
            }
            File part = new File(it.out.getPath() + ".part");
            it.out.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(part)) {
                o.write(raw);
                o.getFD().sync();
            }
            if (!part.renameTo(it.out)) {
                throw new IOException("cannot rename " + part);
            }
            return;
        }
        if (e.method != 0 && e.method != 8) {
            throw new IOException(it.member + ": compressed with method " + e.method + ", not supported");
        }
        // the zip's bytes as they are, chunk by chunk: into the sparse copy at their own offsets, or
        // (a member of its own) the stored bytes at 0, or a big deflated member's raw bytes into OUT.z
        File target = it.kind == Kind.SPARSE ? it.out : e.method == 0 ? it.out : new File(it.out.getPath() + ".z");
        long base = it.kind == Kind.SPARSE ? e.data : 0;
        target.getParentFile().mkdirs();
        try (RandomAccessFile f = new RandomAccessFile(target, "rw")) {
            if (it.kind != Kind.SPARSE && f.length() != e.csize) {
                f.setLength(e.csize);
            }
        }
        File donePath = doneFile(it);
        Set<Integer> done = readDone(donePath);
        int n = (int) ((e.csize + chunk - 1) / chunk);
        ConcurrentLinkedQueue<Integer> todo = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < n; i++) {
            if (!done.contains(i)) {
                todo.add(i);
            }
        }
        it.done.set(doneBytes(it));
        it.state = "fetching";
        listener.progress(it);
        listener.log(it.member + ": " + e.csize + " bytes at " + e.data + ", " + todo.size() + "/" + n + " chunks to fetch");
        IOException[] failure = new IOException[1];
        try (FileChannel ch = FileChannel.open(target.toPath(), StandardOpenOption.WRITE);
             FileOutputStream doneOut = new FileOutputStream(donePath, true)) {
            List<Thread> workers = new ArrayList<>();
            for (int w = 0; w < Math.min(connections, Math.max(1, todo.size())); w++) {
                Thread t = new Thread(() -> {
                    Integer idx;
                    while (!stop && failure[0] == null && (idx = todo.poll()) != null) {
                        try {
                            fetchChunk(it, ch, base, idx);
                            synchronized (doneOut) {
                                doneOut.write((idx + "\n").getBytes(StandardCharsets.US_ASCII));
                                doneOut.getFD().sync();
                            }
                            listener.progress(it);
                        } catch (IOException ex) {
                            synchronized (failure) {
                                if (failure[0] == null) {
                                    failure[0] = ex;
                                }
                            }
                        }
                    }
                    dropConn();
                }, "ipsw-fetch-" + w);
                workers.add(t);
                t.start();
            }
            for (Thread t : workers) {
                try {
                    t.join();
                } catch (InterruptedException ex) {
                    stop = true;
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (stop) {
            throw new IOException("stopped");
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /** Bytes per second of every connection together since @sinceNs, and per transferring connection. */
    private final long startedNs = System.nanoTime();

    private double perConnection() {
        double s = (System.nanoTime() - startedNs) / 1e9;
        return s < 5 ? 0 : received.get() / s / Math.max(1, active.get());
    }

    /**
     * One chunk: [idx * chunk, +chunk) of the member's data, written as it arrives. A retry goes on
     * from the bytes already written (only the chunk's end in .done counts across an app kill). A
     * connection far slower than the others (an overloaded cache: 75-98 KB/s on 2026-11-08) gives
     * its chunk's rest to another server, when there is another one.
     */
    private void fetchChunk(Item it, FileChannel ch, long base, int idx) throws IOException {
        Entry e = it.entry;
        long from = (long) idx * chunk;
        int len = (int) Math.min(chunk, e.csize - from);
        IOException last = null;
        byte[] buf = new byte[1 << 20];
        long got = 0;
        int slow = 0;
        try {
            for (int attempt = 0; attempt < TRIES + slow && !stop; attempt++) {
                long t0 = System.nanoTime(), start = got;
                active.incrementAndGet();
                try (InputStream in = open(e.data + from + got, len - got)) {
                    while (got < len) {
                        if (stop) {
                            throw new IOException("stopped");
                        }
                        double s = (System.nanoTime() - t0) / 1e9, rate = (got - start) / Math.max(s, 1e-3);
                        if (s > 30 && rate < Math.max(64e3, perConnection() / 4) && slow < 20 && cdn != null && cdn.others()) {
                            CdnEdges.Conn c = conn.get();
                            if (c != null) {
                                c.edge.fails.incrementAndGet();
                            }
                            dropConn();
                            slow++;
                            throw new IOException(String.format(java.util.Locale.ROOT, "slow server (%.0f KB/s, the others %.0f KB/s)",
                                    rate / 1e3, perConnection() / 1e3));
                        }
                        int r = in.read(buf, 0, (int) Math.min(buf.length, len - got));
                        if (r < 0) {
                            throw new EOFException("the connection ended early");
                        }
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, r);
                        long at = base + from + got;
                        while (bb.hasRemaining()) {
                            at += ch.write(bb, at);
                        }
                        got += r;
                        it.done.addAndGet(r);
                        received.addAndGet(r);
                    }
                    ch.force(false);
                    got = -1;           // complete: nothing to take back
                    CdnEdges.Conn c = conn.get();
                    if (c != null) {
                        c.edge.fails.set(0);
                    }
                    return;
                } catch (HttpError ex) {
                    dropConn();
                    if (ex.code == 404 || ex.code == 403 || ex.code == 410 || ex.code == 200) {
                        throw ex;
                    }
                    last = ex;
                } catch (IOException ex) {
                    dropConn();
                    if (stop) {
                        throw ex;
                    }
                    last = ex;
                    listener.log(it.member + ": chunk " + idx + " at " + (got >> 20) + " MB, retry " + attempt + ": " + ex.getMessage());
                } finally {
                    active.decrementAndGet();
                }
                if (!(last != null && last.getMessage() != null && last.getMessage().startsWith("slow server"))) {
                    pause(attempt);
                }
            }
            throw stop ? new IOException("stopped") : last;
        } finally {
            if (got > 0) {
                it.done.addAndGet(-got);    // not in .done: it comes again
            }
        }
    }

    private static byte[] inflate(byte[] z, long usize) throws IOException {
        if (usize > Integer.MAX_VALUE - 16) {
            throw new IOException("too big to inflate in memory");
        }
        Inflater inf = new Inflater(true);
        inf.setInput(z);
        byte[] out = new byte[(int) usize];
        try {
            int n = 0;
            while (n < out.length) {
                int r = inf.inflate(out, n, out.length - n);
                if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += r;
            }
            if (n != out.length) {
                throw new IOException("inflated to " + n + " bytes, " + usize + " expected");
            }
        } catch (DataFormatException ex) {
            throw new IOException("damaged deflate data: " + ex.getMessage());
        } finally {
            inf.end();
        }
        return out;
    }

    /**
     * The CRC-32 check (and, for a big deflated member of its own, the inflation into OUT). The
     * .done list goes once it passes, and an .ok marker says so; a mismatch drops the .done list,
     * so every chunk comes again.
     */
    private void verify(Item it) throws IOException {
        Entry e = it.entry;
        if (it.kind == Kind.FILE && e.method == 8 && e.csize <= ONE_REQUEST) {
            mark(it);                   // checked while it was inflated
            return;
        }
        CRC32 crc = new CRC32();
        long n;
        File src = it.kind == Kind.SPARSE ? it.out : e.method == 0 ? it.out : new File(it.out.getPath() + ".z");
        long base = it.kind == Kind.SPARSE ? e.data : 0;
        try (FileChannel ch = FileChannel.open(src.toPath(), StandardOpenOption.READ)) {
            if (e.method == 0) {
                n = crcRange(ch, base, e.csize, crc);
            } else {
                File part = it.kind == Kind.FILE ? new File(it.out.getPath() + ".part") : null;
                n = inflateRange(ch, base, e.csize, crc, part);
                if (part != null && !stop) {
                    if (crc.getValue() == e.crc && n == e.usize && !part.renameTo(it.out)) {
                        throw new IOException("cannot rename " + part);
                    }
                }
            }
        }
        if (stop) {
            throw new IOException("stopped");
        }
        if (n != e.usize || crc.getValue() != e.crc) {
            doneFile(it).delete();
            throw new IOException(it.member + ": damaged in transfer (CRC-32 " + Long.toHexString(crc.getValue())
                    + ", expected " + Long.toHexString(e.crc) + "); it will be fetched again");
        }
        if (it.kind == Kind.FILE && e.method == 8) {
            new File(it.out.getPath() + ".z").delete();
        }
        mark(it);
    }

    private void mark(Item it) throws IOException {
        try (FileOutputStream o = new FileOutputStream(okFile(it))) {
            o.write(String.format("%s %d %08x\n", it.member, it.entry.usize, it.entry.crc).getBytes(StandardCharsets.US_ASCII));
            o.getFD().sync();
        }
        doneFile(it).delete();
    }

    private long crcRange(FileChannel ch, long at, long len, CRC32 crc) throws IOException {
        ByteBuffer b = ByteBuffer.allocateDirect(4 << 20);
        long n = 0;
        while (n < len && !stop) {
            b.clear();
            b.limit((int) Math.min(b.capacity(), len - n));
            int r = ch.read(b, at + n);
            if (r < 0) {
                break;
            }
            b.flip();
            crc.update(b);
            n += r;
        }
        return n;
    }

    /** Inflates [at, at + len) of @ch, CRC over the output; the output also goes to @to when given. */
    private long inflateRange(FileChannel ch, long at, long len, CRC32 crc, File to) throws IOException {
        Inflater inf = new Inflater(true);
        byte[] in = new byte[4 << 20], out = new byte[4 << 20];
        long n = 0, made = 0;
        FileOutputStream o = to == null ? null : new FileOutputStream(to);
        try {
            while (!stop && !inf.finished()) {
                if (inf.needsInput()) {
                    if (n >= len) {
                        break;
                    }
                    ByteBuffer b = ByteBuffer.wrap(in, 0, (int) Math.min(in.length, len - n));
                    int r = ch.read(b, at + n);
                    if (r <= 0) {
                        break;
                    }
                    n += r;
                    inf.setInput(in, 0, r);
                }
                int r = inf.inflate(out);
                if (r > 0) {
                    crc.update(out, 0, r);
                    made += r;
                    if (o != null) {
                        o.write(out, 0, r);
                    }
                } else if (inf.needsDictionary()) {
                    break;
                }
            }
            if (o != null) {
                o.getFD().sync();
            }
        } catch (DataFormatException ex) {
            throw new IOException("damaged deflate data: " + ex.getMessage());
        } finally {
            inf.end();
            if (o != null) {
                o.close();
            }
        }
        return made;
    }

    /** Little-endian helpers for tests that build zips. */
    static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }
}
