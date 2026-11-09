package com.asoft.ios;

import org.conscrypt.Conscrypt;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 1.3 as adb speaks it on Wireless debugging's two ports, with this app's adb key as the client
 * certificate. adbd's own certificate is made up at every start and signed by itself, so there is nothing in
 * it to check, and adb's own client does not: what the two sides know of each other is the pairing code (bound
 * to this very TLS session through its exported key) and, from then on, this app's key.
 *
 * The TLS comes from the Conscrypt the app brings (Apache-2.0): pairing needs the session's exported keying
 * material (RFC 5705), which the phone's own Conscrypt has only behind a hidden API.
 */
final class AdbTls {
    private static Provider provider;

    private AdbTls() {}

    private static synchronized Provider provider() {
        if (provider == null) {
            provider = Conscrypt.newProvider();
        }
        return provider;
    }

    /** The handshake over a connected @plain socket; closing the result closes @plain. */
    static SSLSocket client(Socket plain, AdbKey key) throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3", provider());
            ctx.init(new KeyManager[] {new OneKey(key)}, new TrustManager[] {new AnyPeer()}, null);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain,
                    plain.getInetAddress().getHostAddress(), plain.getPort(), true);
            s.setUseClientMode(true);
            s.setEnabledProtocols(new String[] {"TLSv1.3"});
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The 64 bytes both ends of this session can derive and nobody between them (adb's label ends in a zero byte). */
    static byte[] exported(SSLSocket s) throws IOException {
        return Conscrypt.exportKeyingMaterial(s, "adb-label\u0000", null, 64);
    }

    /** (The server's side of a session, for the PC test of the pairing: PairTest.) */
    static SSLContext context(AdbKey key) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3", provider());
        ctx.init(new KeyManager[] {new OneKey(key)}, new TrustManager[] {new AnyPeer()}, null);
        return ctx;
    }

    private static final class OneKey extends X509ExtendedKeyManager {
        private final AdbKey key;

        OneKey(AdbKey key) {
            this.key = key;
        }

        @Override
        public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
            return "adb";
        }

        @Override
        public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
            return "adb";
        }

        @Override
        public String[] getClientAliases(String type, Principal[] issuers) {
            return new String[] {"adb"};
        }

        @Override
        public String[] getServerAliases(String type, Principal[] issuers) {
            return new String[] {"adb"};
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[] {key.cert};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key.key;
        }
    }

    /** See the class comment: adb's certificates carry a key and nothing to verify. */
    private static final class AnyPeer implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String type) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String type) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
