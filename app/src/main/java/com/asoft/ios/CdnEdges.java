package com.asoft.ios;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Which of a CDN's servers to fetch from. Apple's updates.cdn-apple.com answers each resolver with
 * a server near it: on 2026-11-08 the phone's own DNS (and Google's with the client's subnet) gave
 * an ISP-side cache at 75-98 KB/s per connection, while Cloudflare's and Google's subnet-less answers
 * (Apple's own 17.253.x.x) gave ~6 MB/s. So the addresses come from the system's resolver and from
 * two DNS-over-HTTPS answers, each is tried with a 1 MB range, and the connections go to the fast ones.
 * Requests then go over our own TLS socket to that address (SNI and certificate check for the host
 * name), HTTP/1.1 with keep-alive: one connection per worker, reused chunk after chunk.
 */
final class CdnEdges {
    static final class Edge {
        final InetAddress ip;
        final String from;
        volatile double mbps = -1;      // the probe's speed, -1 = not measured or failed
        final AtomicInteger fails = new AtomicInteger();

        Edge(InetAddress ip, String from) {
            this.ip = ip;
            this.from = from;
        }

        @Override
        public String toString() {
            return ip.getHostAddress() + " (" + from + (mbps >= 0 ? String.format(Locale.ROOT, ", %.1f MB/s", mbps) : "") + ")";
        }
    }

    final String host;
    final int port;
    final String path;
    final boolean tls;
    private final List<Edge> edges = new ArrayList<>();
    private final AtomicInteger next = new AtomicInteger();

    CdnEdges(String url) throws IOException {
        URL u = new URL(url);
        host = u.getHost();
        tls = u.getProtocol().equals("https");
        port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        path = u.getFile().isEmpty() ? "/" : u.getFile();
    }

    /** The addresses from the system's resolver and two DNS-over-HTTPS answers (IPv4). */
    List<Edge> discover(IpswFetch.Listener log) {
        Map<String, Edge> seen = new LinkedHashMap<>();
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) {
                    seen.putIfAbsent(a.getHostAddress(), new Edge(a, "system DNS"));
                }
            }
        } catch (IOException e) {
            log.log("system DNS: " + e.getMessage());
        }
        doh(seen, "https://cloudflare-dns.com/dns-query?name=" + host + "&type=A", "Cloudflare DNS", log);
        doh(seen, "https://dns.google/resolve?name=" + host + "&type=A&edns_client_subnet=0.0.0.0/0", "Google DNS", log);
        synchronized (edges) {
            edges.clear();
            edges.addAll(seen.values());
        }
        return new ArrayList<>(seen.values());
    }

    private static final Pattern A = Pattern.compile("\"type\"\\s*:\\s*1\\s*,\\s*\"TTL\"\\s*:\\s*\\d+\\s*,\\s*\"data\"\\s*:\\s*\"([0-9.]+)\"");

    private static void doh(Map<String, Edge> seen, String url, String from, IpswFetch.Listener log) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("accept", "application/dns-json");
            try (InputStream in = c.getInputStream()) {
                String body = new String(Io.readAll(in), StandardCharsets.UTF_8);
                Matcher m = A.matcher(body);
                while (m.find()) {
                    seen.putIfAbsent(m.group(1), new Edge(InetAddress.getByName(m.group(1)), from));
                }
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            log.log(from + ": " + e.getMessage());
        }
    }

    /** Fetches [from, from + 1 MB) from every edge at once; sorts them fastest first. */
    void probe(long from, IpswFetch.Listener log) {
        List<Edge> all;
        synchronized (edges) {
            all = new ArrayList<>(edges);
        }
        List<Thread> ts = new ArrayList<>();
        for (Edge e : all) {
            Thread t = new Thread(() -> {
                long t0 = System.nanoTime();
                try (Conn c = connect(e, 8_000); InputStream in = c.get(from, 1 << 20)) {
                    byte[] b = new byte[1 << 16];
                    long n = 0;
                    int r;
                    while ((r = in.read(b)) > 0) {
                        n += r;
                    }
                    double s = (System.nanoTime() - t0) / 1e9;
                    e.mbps = n / 1e6 / Math.max(s, 1e-3);
                } catch (IOException ex) {
                    e.mbps = -1;
                    e.fails.incrementAndGet();
                }
            }, "edge-probe");
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            try {
                t.join(12_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (edges) {
            Collections.sort(edges, (a, b) -> Double.compare(b.mbps, a.mbps));
            log.log("CDN servers: " + edges);
        }
    }

    /**
     * The edge the next connection should use: round robin over those at least half as fast as the
     * best one (the best alone when it is far ahead), skipping one that keeps failing. Null when
     * none answered (then the caller uses the plain URL).
     */
    Edge pick() {
        synchronized (edges) {
            List<Edge> good = new ArrayList<>();
            double best = edges.isEmpty() ? -1 : edges.get(0).mbps;
            for (Edge e : edges) {
                if (e.mbps > 0 && e.mbps >= best / 2 && e.fails.get() < 3) {
                    good.add(e);
                }
            }
            if (good.isEmpty()) {
                for (Edge e : edges) {
                    if (e.mbps > 0 && e.fails.get() < 6) {
                        good.add(e);
                    }
                }
            }
            if (good.isEmpty()) {
                Edge least = null;          // every one failed lately: the one that failed least
                for (Edge e : edges) {
                    if (e.mbps > 0 && (least == null || e.fails.get() < least.fails.get())) {
                        least = e;
                    }
                }
                return least;
            }
            return good.get(Math.floorMod(next.getAndIncrement(), good.size()));
        }
    }

    /** For the screen: "3 Apple servers (best 6.1 MB/s)", or "" before the probe. */
    String summary() {
        synchronized (edges) {
            int n = 0;
            double best = 0;
            for (Edge e : edges) {
                if (e.mbps > 0) {
                    n++;
                    best = Math.max(best, e.mbps);
                }
            }
            return n == 0 ? "" : String.format(Locale.ROOT, "%d %s (best %.1f MB/s when tried)", n, n == 1 ? "server" : "servers", best);
        }
    }

    /** More than one server answered: a slow connection can move to another one. */
    boolean others() {
        synchronized (edges) {
            int n = 0;
            for (Edge e : edges) {
                if (e.mbps > 0) {
                    n++;
                }
            }
            return n > 1;
        }
    }

    boolean any() {
        synchronized (edges) {
            for (Edge e : edges) {
                if (e.mbps > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    // -- one connection: TLS to the chosen address, HTTP/1.1 with keep-alive -----------------------

    private static final boolean ANDROID = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT).contains("android");

    Conn connect(Edge e, int timeoutMs) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(e.ip, port), timeoutMs);
            raw.setSoTimeout(30_000);
            raw.setTcpNoDelay(true);
            raw.setReceiveBufferSize(4 << 20);
            if (!tls) {
                return new Conn(this, e, raw);
            }
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters p = s.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            if (!ANDROID) {
                p.setEndpointIdentificationAlgorithm("HTTPS");      // the PC JVM checks the name in the handshake
            }
            s.setSSLParameters(p);
            s.startHandshake();
            if (ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                s.close();
                throw new SSLPeerUnverifiedException(e.ip.getHostAddress() + " is not " + host);
            }
            return new Conn(this, e, s);
        } catch (IOException ex) {
            raw.close();
            throw ex;
        }
    }

    /** A kept-alive connection; get() opens one range, read to its end before the next get(). */
    static final class Conn implements Closeable {
        final CdnEdges cdn;
        final Edge edge;
        private final Socket s;
        private final InputStream in;
        private final OutputStream out;
        private boolean reusable = true;

        Conn(CdnEdges cdn, Edge edge, Socket s) throws IOException {
            this.cdn = cdn;
            this.edge = edge;
            this.s = s;
            this.in = new BufferedInputStream(s.getInputStream(), 1 << 16);
            this.out = s.getOutputStream();
        }

        boolean reusable() {
            return reusable && !s.isClosed();
        }

        InputStream get(long from, long len) throws IOException {
            String req = "GET " + cdn.path + " HTTP/1.1\r\nHost: " + cdn.host + "\r\nRange: bytes=" + from + "-" + (from + len - 1)
                    + "\r\nUser-Agent: ASoftware-iOS-setup\r\nAccept-Encoding: identity\r\nConnection: keep-alive\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = line();
            String[] parts = status.split(" ", 3);
            int code = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            long length = -1;
            String range = null;
            String h;
            while (!(h = line()).isEmpty()) {
                int c = h.indexOf(':');
                if (c < 0) {
                    continue;
                }
                String k = h.substring(0, c).trim().toLowerCase(Locale.ROOT), v = h.substring(c + 1).trim();
                switch (k) {
                    case "content-length":
                        length = Long.parseLong(v);
                        break;
                    case "content-range":
                        range = v;
                        break;
                    case "connection":
                        if (v.equalsIgnoreCase("close")) {
                            reusable = false;
                        }
                        break;
                    case "transfer-encoding":
                        reusable = false;
                        throw new IOException("unexpected Transfer-Encoding " + v);
                    default:
                        break;
                }
            }
            if (code != 206) {
                reusable = false;
                throw new IpswFetch.HttpError(code, code == 200 ? "the server ignored the byte range" : "HTTP " + code + " from " + edge.ip.getHostAddress());
            }
            if (range == null || !range.startsWith("bytes " + from + "-" + (from + len - 1) + "/") || length != len) {
                reusable = false;
                throw new IOException("unexpected range reply " + range + ", " + length + " bytes");
            }
            final long total = length;
            return new InputStream() {
                long left = total;

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
                    int r = in.read(b, o, (int) Math.min(n, left));
                    if (r < 0) {
                        reusable = false;
                        throw new EOFException("the server closed the connection early");
                    }
                    left -= r;
                    return r;
                }

                @Override
                public void close() {
                    if (left > 0) {
                        reusable = false;       // the rest of the body is still on the wire
                    }
                }
            };
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    int n = sb.length();
                    if (n > 0 && sb.charAt(n - 1) == '\r') {
                        sb.setLength(n - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) c);
                if (sb.length() > 16384) {
                    throw new IOException("header line too long");
                }
            }
            throw new EOFException("the server closed the connection");
        }

        @Override
        public void close() throws IOException {
            s.close();
        }
    }
}
