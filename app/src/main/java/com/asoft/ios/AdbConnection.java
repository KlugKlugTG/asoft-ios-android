package com.asoft.ios;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

/**
 * An adb connection to Wireless debugging's port, as much of adb's protocol as running a shell command takes:
 * CNXN, the phone's STLS answer, TLS with the paired key (AdbTls), the phone's CNXN, then one stream per
 * command ("shell:…": OPEN, OKAY, the output in WRTE packets each answered with OKAY, CLSE).
 *
 * Every packet: six little-endian words (command, two arguments, the payload's length, the sum of its bytes,
 * the command inverted), then the payload.
 */
final class AdbConnection implements Closeable {
    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257, AUTH = 0x48545541, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001, STLS_VERSION = 0x01000000, MAX_PAYLOAD = 256 * 1024;

    /** The phone did not take the key: it was never paired, or its pairing was removed in Settings. */
    static final class NotPaired extends IOException {
        NotPaired(String s, Exception how) {
            super(s, how);
        }
    }

    private static final class Packet {
        int command, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private SSLSocket tls;
    private DataInputStream in;
    private OutputStream out;
    private int streams;
    /** What the phone says of itself ("device::ro.product.name=…"). */
    String banner = "";

    private AdbConnection(Socket socket) {
        this.socket = socket;
    }

    static AdbConnection open(AdbKey key, String host, int port, int timeoutMs) throws IOException {
        Socket plain = new Socket();
        AdbConnection c = new AdbConnection(plain);
        try {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            plain.setTcpNoDelay(true);
            c.in = new DataInputStream(plain.getInputStream());
            c.out = plain.getOutputStream();
            c.write(CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".getBytes(StandardCharsets.US_ASCII));
            Packet p = c.read();
            if (p.command == AUTH) {
                throw new IOException("port " + port + " asks for adb's older sign-in: not Wireless debugging's port");
            }
            if (p.command != STLS) {
                throw new IOException("port " + port + " did not answer as Wireless debugging does (0x" + Integer.toHexString(p.command) + ")");
            }
            c.write(STLS, STLS_VERSION, 0, new byte[0]);
            try {
                c.tls = AdbTls.client(plain, key);
                c.tls.setSoTimeout(timeoutMs);
                c.in = new DataInputStream(c.tls.getInputStream());
                c.out = c.tls.getOutputStream();
                // TLS 1.3 ends the client's handshake before the phone has looked at the key: only its
                // first packet says that it took it. Without one, it hung up
                p = c.read();
            } catch (java.net.SocketTimeoutException e) {
                throw e;
            } catch (IOException e) {
                throw new NotPaired("the phone did not accept this app's adb key", e);
            }
            if (p.command != CNXN) {
                throw new IOException("no CNXN after TLS (0x" + Integer.toHexString(p.command) + ")");
            }
            c.banner = new String(p.data, StandardCharsets.UTF_8).replace("\u0000", "");
            return c;
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /**
     * Runs @command in adb's shell (uid 2000) and returns what it printed, stdout and stderr together, once it
     * has ended. One command at a time.
     */
    synchronized String shell(String command, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        tls.setSoTimeout(timeoutMs);
        int local = ++streams, remote = 0;
        write(OPEN, local, 0, ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Packet p = read();
            if (p.arg1 != local) {
                continue;       // an older stream's last word
            }
            if (p.command == OKAY) {
                remote = p.arg0;
            } else if (p.command == WRTE) {
                remote = p.arg0;
                if (output.size() < 4 * MAX_PAYLOAD) {
                    output.write(p.data, 0, p.data.length);
                }
                write(OKAY, local, remote, new byte[0]);
            } else if (p.command == CLSE) {
                if (remote != 0) {
                    write(CLSE, local, remote, new byte[0]);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private void write(int command, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) {
            sum += b & 0xff;
        }
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).put(data);
        out.write(b.array());
        out.flush();
    }

    private Packet read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.command = b.getInt();
        p.arg0 = b.getInt();
        p.arg1 = b.getInt();
        int size = b.getInt();
        b.getInt();
        if (b.getInt() != ~p.command || size < 0 || size > 4 * MAX_PAYLOAD) {
            throw new IOException("not an adb packet");
        }
        p.data = new byte[size];
        in.readFully(p.data);
        return p;
    }

    boolean closed() {
        return socket.isClosed();
    }

    @Override
    public void close() {
        try {
            if (tls != null) {
                tls.close();
            }
        } catch (IOException ignored) {
            // closing anyway
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
    }
}
