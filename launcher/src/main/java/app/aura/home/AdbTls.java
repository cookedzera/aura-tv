package app.aura.home;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Wireless debugging (Android 11+), for TVs where plain network debugging isn't offered: Aura
 * pairs with the TV's own debugging service using the 6-digit code from Developer options, exactly
 * as `adb pair` does (TLS 1.3, then SPAKE2 over the code and the TLS session, then each side's key
 * exchanged under AES-GCM), and later connects over TLS with the same key. Its ports change each
 * time wireless debugging is switched on, so they're found the way adb finds them, over mDNS.
 */
@TargetApi(30)
final class AdbTls {
    static final String PAIRING = "_adb-tls-pairing._tcp", CONNECT = "_adb-tls-connect._tcp";
    private static final byte[] CLIENT = "adb pair client\0".getBytes(StandardCharsets.US_ASCII),
            SERVER = "adb pair server\0".getBytes(StandardCharsets.US_ASCII);
    private static final int SPAKE2_MSG = 0, PEER_INFO = 1, PEER_INFO_SIZE = 8192;

    private AdbTls() { }

    // ---- finding the TV's own services -------------------------------------------------------

    /** The address the TV advertises a service of {@code type} on, or null within {@code ms}. */
    static InetSocketAddress find(Context c, String type, long ms) {
        NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        final AtomicReference<InetSocketAddress> found = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        // Android 11 allows only one active resolveService at a time (FAILURE_ALREADY_ACTIVE)
        final java.util.ArrayDeque<NsdServiceInfo> queue = new java.util.ArrayDeque<>();
        final boolean[] busy = {false};
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (queue) {
                    queue.add(info);
                    if (busy[0]) return;
                    busy[0] = true;
                }
                resolveNext(nsd, queue, busy, found, done);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStarted(String t) {
            }

            @Override
            public void onDiscoveryStopped(String t) {
            }

            @Override
            public void onStartDiscoveryFailed(String t, int error) {
                done.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String t, int error) {
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
            done.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | RuntimeException ignored) {
        } finally {
            synchronized (queue) {
                queue.clear();
            }
            try {
                nsd.stopServiceDiscovery(l);
            } catch (RuntimeException ignored) {
            }
        }
        return found.get();
    }

    private static void resolveNext(NsdManager nsd, java.util.ArrayDeque<NsdServiceInfo> queue, boolean[] busy,
                                    AtomicReference<InetSocketAddress> found, CountDownLatch done) {
        final NsdServiceInfo next;
        synchronized (queue) {
            if (found.get() != null || queue.isEmpty()) {
                busy[0] = false;
                return;
            }
            busy[0] = true;
            next = queue.poll();
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int error) {
                    resolveNext(nsd, queue, busy, found, done);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    if (ownHost(i.getHost())) {
                        found.compareAndSet(null, new InetSocketAddress(i.getHost(), i.getPort()));
                        done.countDown();
                    }
                    resolveNext(nsd, queue, busy, found, done);
                }
            });
        } catch (RuntimeException e) {
            resolveNext(nsd, queue, busy, found, done);
        }
    }

    /** True only when {@code host} is one of this TV's own network addresses (never another device on Wi-Fi). */
    private static boolean ownHost(java.net.InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            return java.net.NetworkInterface.getByInetAddress(host) != null;
        } catch (java.net.SocketException e) {
            return false;
        }
    }

    // ---- TLS with Aura's own key ---------------------------------------------------------------

    /** A TLS 1.3 client presenting Aura's debugging key (as a self-signed certificate) and trusting the TV's. */
    static SSLSocket wrap(Socket plain, InetSocketAddress at, KeyPair key) throws IOException {
        try {
            final X509Certificate cert = certificate(key);
            final PrivateKey priv = key.getPrivate();
            KeyManager km = new X509ExtendedKeyManager() {
                @Override
                public String[] getClientAliases(String type, Principal[] issuers) {
                    return new String[]{"aura"};
                }

                @Override
                public String chooseClientAlias(String[] type, Principal[] issuers, Socket s) {
                    return "aura";
                }

                @Override
                public String chooseEngineClientAlias(String[] type, Principal[] issuers, SSLEngine e) {
                    return "aura";
                }

                @Override
                public String[] getServerAliases(String type, Principal[] issuers) {
                    return null;
                }

                @Override
                public String chooseServerAlias(String type, Principal[] issuers, Socket s) {
                    return null;
                }

                @Override
                public X509Certificate[] getCertificateChain(String alias) {
                    return new X509Certificate[]{cert};
                }

                @Override
                public PrivateKey getPrivateKey(String alias) {
                    return priv;
                }
            };
            // the TV's certificate is its own, self-signed: who it is was settled by the pairing code
            TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain, at.getHostString(), at.getPort(), true);
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.setUseClientMode(true);
            s.startHandshake();
            return s;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** A plain self-signed X.509 certificate for Aura's key, written out by hand (no library for it on Android). */
    static X509Certificate certificate(KeyPair key) throws java.security.GeneralSecurityException {
        byte[] sha256Rsa = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
        byte[] alg = der(0x30, sha256Rsa, new byte[]{0x05, 0x00});
        byte[] cn = {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] name = der(0x30, der(0x31, der(0x30, cn, der(0x0c, "Aura".getBytes(StandardCharsets.UTF_8)))));
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, der(0x17, utc.format(new Date(now - 86_400_000L)).getBytes(StandardCharsets.US_ASCII)),
                der(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = der(0x30, der(0xa0, der(0x02, new byte[]{2})), der(0x02, BigInteger.valueOf(now).toByteArray()),
                alg, name, validity, name, key.getPublic().getEncoded());
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key.getPrivate());
        sig.update(tbs);
        byte[] s = sig.sign(), bits = new byte[s.length + 1];
        System.arraycopy(s, 0, bits, 1, s.length);
        byte[] cert = der(0x30, tbs, alg, der(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    /** One DER element: tag, length, then the parts as its content. */
    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xff);
        }
        out.write(body.toByteArray(), 0, n);
        return out.toByteArray();
    }

    // ---- pairing -------------------------------------------------------------------------------

    /**
     * Pairs with the TV's wireless debugging at {@code at}, using the code it shows. Afterwards the
     * TV knows Aura's key, as after "Always allow". Throws when the code is wrong or the TV went away.
     */
    static void pair(InetSocketAddress at, String code, KeyPair key, String name) throws IOException {
        Socket plain = new Socket();
        plain.connect(at, 5000);
        plain.setSoTimeout(15_000);
        try (SSLSocket s = wrap(plain, at, key)) {
            // the password is the code, bound to this very TLS session
            byte[] ekm = android.net.ssl.SSLSockets.exportKeyingMaterial(s, "adb-label\0", null, 64);
            byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII), pw = Arrays.copyOf(codeBytes, codeBytes.length + ekm.length);
            System.arraycopy(ekm, 0, pw, codeBytes.length, ekm.length);
            Spake2 spake = new Spake2(true, CLIENT, SERVER, pw, new SecureRandom());
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            packet(out, SPAKE2_MSG, spake.message());
            byte[] theirs = read(in, SPAKE2_MSG);
            byte[] secret = spake.finish(theirs);
            if (secret == null) throw new IOException("not a point");
            byte[] aesKey = hkdf(secret, "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII), 16);
            // what Aura is: its public key in adb's own layout, as "Always allow" would have kept it
            byte[] info = new byte[PEER_INFO_SIZE];
            info[0] = 0; // ADB_RSA_PUB_KEY
            byte[] pub = (Adb.base64(Adb.publicKey((RSAPublicKey) key.getPublic())) + " " + name).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(pub, 0, info, 1, Math.min(pub.length, PEER_INFO_SIZE - 2));
            packet(out, PEER_INFO, gcm(aesKey, true, info));
            // a wrong code shows up here: the TV's answer won't decrypt
            byte[] back = gcm(aesKey, false, read(in, PEER_INFO));
            if (back.length != PEER_INFO_SIZE) throw new IOException("peer info size " + back.length);
        } catch (java.security.GeneralSecurityException e) {
            throw new Adb.Denied("pairing code not accepted");
        }
    }

    /** Header: version 1, type, payload size (big-endian), then the payload. */
    private static void packet(OutputStream out, int type, byte[] payload) throws IOException {
        byte[] h = {1, (byte) type, (byte) (payload.length >>> 24), (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length};
        out.write(h);
        out.write(payload);
        out.flush();
    }

    private static byte[] read(DataInputStream in, int type) throws IOException {
        byte[] h = new byte[6];
        in.readFully(h);
        int n = (h[2] & 0xff) << 24 | (h[3] & 0xff) << 16 | (h[4] & 0xff) << 8 | (h[5] & 0xff);
        if (h[0] != 1 || h[1] != type || n <= 0 || n > PEER_INFO_SIZE * 2) throw new IOException("bad pairing packet");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    /** AES-128-GCM with adb's nonce: the message's number (the first, 0, for each direction here). */
    private static byte[] gcm(byte[] key, boolean seal, byte[] data) throws java.security.GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(seal ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
        return c.doFinal(data);
    }

    /** HKDF-SHA256 with no salt (RFC 5869: a block of zeros), one round is enough for 16 bytes. */
    static byte[] hkdf(byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            return Arrays.copyOf(mac.doFinal(), length);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
