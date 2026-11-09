package com.asoft.ios;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Where this phone's Wireless debugging listens: adbd picks a new port at every switch-on, and another for
 * each pairing dialog, and says so only over mDNS on the Wi-Fi ("_adb-tls-connect._tcp", "_adb-tls-pairing._tcp").
 * Another phone on the same Wi-Fi may announce the same: only this phone's own addresses count.
 */
final class AdbMdns {
    static final String CONNECT = "_adb-tls-connect._tcp", PAIRING = "_adb-tls-pairing._tcp";

    interface Listener {
        /** This phone announces the service at @port; -1: it took the announcement back. */
        void port(int port);
    }

    private final NsdManager nsd;
    private final String type;
    private final Listener listener;
    private volatile boolean running;
    /** The announcement we passed on, to know its withdrawal. */
    private volatile String found;

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override
        public void onStartDiscoveryFailed(String serviceType, int error) {
            running = false;
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int error) {
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            resolve(info, 3);
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
            if (running && info.getServiceName() != null && info.getServiceName().equals(found)) {
                found = null;
                listener.port(-1);
            }
        }
    };

    AdbMdns(Context c, String type, Listener listener) {
        nsd = c.getSystemService(NsdManager.class);
        this.type = type;
        this.listener = listener;
    }

    void start() {
        if (!running) {
            running = true;
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery);
        }
    }

    void stop() {
        if (running) {
            running = false;
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (IllegalArgumentException ignored) {
                // it never started
            }
        }
    }

    /** Android before 14 resolves one name at a time and fails the others: a few more tries. */
    private void resolve(NsdServiceInfo info, int triesLeft) {
        if (!running) {
            return;
        }
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo i, int error) {
                if (triesLeft > 1) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        resolve(info, triesLeft - 1);
                    }, "adb-mdns-retry").start();
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo i) {
                if (running && own(i.getHost()) && i.getPort() > 0) {
                    found = i.getServiceName();
                    listener.port(i.getPort());
                }
            }
        });
    }

    private static boolean own(InetAddress a) {
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress()) {
            return true;
        }
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress mine : Collections.list(n.getInetAddresses())) {
                    if (mine.equals(a)) {
                        return true;
                    }
                }
            }
        } catch (SocketException | NullPointerException ignored) {
            // no interfaces to ask: not ours
        }
        return false;
    }

    /** The port this phone announces for @type, waiting up to @timeoutMs for it; -1: none. Not on the main thread. */
    static int find(Context c, String type, long timeoutMs) {
        CountDownLatch done = new CountDownLatch(1);
        int[] port = {-1};
        AdbMdns m = new AdbMdns(c, type, p -> {
            if (p > 0) {
                port[0] = p;
                done.countDown();
            }
        });
        m.start();
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            m.stop();
        }
        return port[0];
    }
}
