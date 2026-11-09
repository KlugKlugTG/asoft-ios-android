package com.asoft.ios;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.net.ssl.SSLSocket;

/**
 * The PC's test of the app's adb code (test-wadb-pc.sh runs it, on the PC's Java with Conscrypt's desktop jar):
 *
 *   PairTest points             M and N as numbers, to compare with BoringSSL's source
 *   PairTest key DIR            makes the key in DIR and prints its adb line; the script reads the certificate
 *   PairTest serve DIR CODE     stands in for the phone's pairing dialog on a free port, printed first: the real
 *                               "adb pair" is run against it, and the key it hands over is printed
 *   PairTest self DIR           this app's pairing against that stand-in, right code and wrong code, then a
 *                               connection and a shell command against a stand-in for adbd
 */
public final class PairTest {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "points":
                System.out.println(Spake2.points());
                break;
            case "key":
                System.out.println(AdbKey.of(new File(a[1])).publicLine("test@pc"));
                break;
            case "serve": {
                AdbKey key = AdbKey.of(new File(a[1]));
                try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                    System.out.println("port " + s.getLocalPort());
                    System.out.flush();
                    System.out.println("peer " + servePairing(s, key, a[2]));
                }
                break;
            }
            case "self":
                self(new File(a[1]));
                break;
            default:
                throw new IllegalArgumentException(a[0]);
        }
    }

    /** One pairing as the phone's side: returns what the client handed over. */
    private static String servePairing(ServerSocket s, AdbKey key, String code) throws Exception {
        s.setSoTimeout(30_000);
        try (Socket plain = s.accept(); SSLSocket tls = server(plain, key)) {
            plain.setSoTimeout(30_000);
            return AdbPairing.exchange(new DataInputStream(tls.getInputStream()), tls.getOutputStream(), false,
                    AdbPairing.password(code, AdbTls.exported(tls)), AdbPairing.info(AdbPairing.DEVICE_GUID, "adb-TEST-guid"));
        }
    }

    private static SSLSocket server(Socket plain, AdbKey key) throws Exception {
        SSLSocket tls = (SSLSocket) AdbTls.context(key).getSocketFactory().createSocket(plain, "127.0.0.1", plain.getPort(), true);
        tls.setUseClientMode(false);
        tls.setNeedClientAuth(true);
        tls.startHandshake();
        return tls;
    }

    private static void self(File dir) throws Exception {
        AdbKey phone = AdbKey.of(new File(dir, "phone")), app = AdbKey.of(new File(dir, "app"));
        String line = app.publicLine("ASoftware-iOS");

        for (String typed : new String[] {"123456", "654321"}) {
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                String[] got = new String[1];
                Thread t = new Thread(() -> {
                    try {
                        got[0] = servePairing(s, phone, "123456");
                    } catch (Exception e) {
                        got[0] = "server: " + e;
                    }
                });
                t.start();
                String result;
                try {
                    result = "paired, phone " + AdbPairing.pair(app, "ASoftware-iOS", "127.0.0.1", s.getLocalPort(), typed);
                } catch (AdbPairing.WrongCode e) {
                    result = "wrong code";
                }
                t.join(10_000);
                boolean right = typed.equals("123456");
                boolean ok = right ? result.equals("paired, phone adb-TEST-guid") && line.equals(got[0]) : result.equals("wrong code");
                System.out.println("pair with " + (right ? "the right" : "a wrong") + " code: " + result + (ok ? "  OK" : "  FAILED (server: " + got[0] + ")"));
                if (!ok) {
                    System.exit(1);
                }
            }
        }

        // a stand-in for adbd on Wireless debugging's port: STLS, TLS, CNXN, one shell stream
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String[] seen = new String[1];
            s.setSoTimeout(30_000);
            Thread t = new Thread(() -> {
                try (Socket plain = s.accept()) {
                    plain.setSoTimeout(30_000);
                    DataInputStream in = new DataInputStream(plain.getInputStream());
                    OutputStream out = plain.getOutputStream();
                    int[] p = packet(in);
                    send(out, 0x534c5453, 0x01000000, 0, new byte[0]);
                    p = packet(in);
                    try (SSLSocket tls = server(plain, phone)) {
                        boolean known = java.util.Arrays.equals(tls.getSession().getPeerCertificates()[0].getPublicKey().getEncoded(),
                                app.cert.getPublicKey().getEncoded());
                        in = new DataInputStream(tls.getInputStream());
                        out = tls.getOutputStream();
                        send(out, 0x4e584e43, 0x01000001, 1024 * 1024, "device::ro.product.name=test;features=shell_v2".getBytes(StandardCharsets.US_ASCII));
                        p = packet(in);                                     // OPEN
                        seen[0] = "key " + (known ? "known" : "unknown") + ", " + new String(last, StandardCharsets.UTF_8).replace("\u0000", "");
                        int local = p[1];
                        send(out, 0x59414b4f, 7, local, new byte[0]);
                        send(out, 0x45545257, 7, local, "first\n".getBytes(StandardCharsets.US_ASCII));
                        packet(in);                                         // OKAY
                        send(out, 0x45545257, 7, local, "second\n".getBytes(StandardCharsets.US_ASCII));
                        packet(in);
                        send(out, 0x45534c43, 7, local, new byte[0]);
                        packet(in);                                         // CLSE
                    }
                } catch (Exception e) {
                    seen[0] = "server: " + e;
                }
            });
            t.start();
            String got;
            try (AdbConnection c = AdbConnection.open(app, "127.0.0.1", s.getLocalPort(), 10_000)) {
                got = c.banner + " | " + c.shell("am kill-all", 10_000).replace("\n", "\\n");
            }
            t.join(10_000);
            boolean ok = got.equals("device::ro.product.name=test;features=shell_v2 | first\\nsecond\\n")
                    && "key known, shell:am kill-all".equals(seen[0]);
            System.out.println("connect and shell: " + got + " (" + seen[0] + ")" + (ok ? "  OK" : "  FAILED"));
            if (!ok) {
                System.exit(1);
            }
        }
        Files.walk(dir.toPath()).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    private static byte[] last;

    private static int[] packet(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int[] p = {b.getInt(), b.getInt(), b.getInt(), b.getInt()};
        last = new byte[p[3]];
        in.readFully(last);
        return p;
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(0).putInt(~command).put(data);
        out.write(b.array());
        out.flush();
    }
}
