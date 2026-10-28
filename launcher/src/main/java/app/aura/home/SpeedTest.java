package app.aura.home;

import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internet speed against Cloudflare's public speed test (no account or key): latency, then
 * download, then upload, each for a few seconds. Data streams through one small buffer per
 * connection and is thrown away, so the test costs almost no memory and stores nothing.
 */
final class SpeedTest {
    interface Listener {
        /** Called from the test's threads, several times a second. */
        void onUpdate(String phase, long pingMs, double downMbps, double upMbps, boolean done);
    }

    private static final String DOWN = "https://speed.cloudflare.com/__down?bytes=", UP = "https://speed.cloudflare.com/__up";
    private static final long PHASE_MS = 7_000, WARM_MS = 1_200;
    private static final int STREAMS = 3;

    private final AtomicBoolean stop = new AtomicBoolean();

    void cancel() {
        stop.set(true);
    }

    /** Runs the whole test; call off the main thread. */
    void run(Listener l) {
        long ping = ping();
        l.onUpdate("ping", ping, 0, 0, false);
        double down = transfer(false, ping, 0, l);
        double up = stop.get() ? 0 : transfer(true, ping, down, l);
        l.onUpdate("done", ping, down, up, true);
    }

    /** The median time for an empty request over a warm connection. */
    private long ping() {
        long[] t = new long[6];
        for (int i = 0; i < t.length && !stop.get(); i++) {
            long a = SystemClock.elapsedRealtime();
            try {
                HttpURLConnection c = open(DOWN + 0);
                try (InputStream in = c.getInputStream()) {
                    while (in.read() >= 0) { }
                }
            } catch (IOException e) {
                return -1;
            }
            t[i] = SystemClock.elapsedRealtime() - a;
        }
        // the first request also pays for the TLS handshake: leave it out
        long[] warm = Arrays.copyOfRange(t, 1, t.length);
        Arrays.sort(warm);
        return warm[warm.length / 2];
    }

    /**
     * A few parallel streams for {@link #PHASE_MS}; the first moments are left out while TCP
     * ramps up. Returns megabits per second.
     */
    private double transfer(boolean upload, long ping, double down, Listener l) {
        AtomicLong bytes = new AtomicLong();
        long start = SystemClock.elapsedRealtime(), end = start + PHASE_MS;
        Thread[] th = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            th[i] = new Thread(() -> {
                byte[] buf = new byte[32 * 1024];
                while (!stop.get() && SystemClock.elapsedRealtime() < end) {
                    try {
                        if (upload) send(buf, bytes, end);
                        else receive(buf, bytes, end);
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "speed");
            th[i].start();
        }
        long warmBytes = -1, warmAt = 0;
        double mbps = 0;
        while (SystemClock.elapsedRealtime() < end && !stop.get()) {
            SystemClock.sleep(250);
            long now = SystemClock.elapsedRealtime();
            if (warmBytes < 0 && now - start >= WARM_MS) {
                warmBytes = bytes.get();
                warmAt = now;
            }
            if (warmBytes >= 0 && now > warmAt) mbps = (bytes.get() - warmBytes) * 8 / 1000.0 / (now - warmAt);
            else mbps = bytes.get() * 8 / 1000.0 / Math.max(1, now - start);
            l.onUpdate(upload ? "up" : "down", ping, upload ? down : mbps, upload ? mbps : 0, false);
        }
        for (Thread t : th) {
            try {
                t.join(2_000);
            } catch (InterruptedException ignored) {
            }
        }
        return mbps;
    }

    private static void receive(byte[] buf, AtomicLong bytes, long end) throws IOException {
        HttpURLConnection c = open(DOWN + 50_000_000);
        try (InputStream in = c.getInputStream()) {
            for (int n; SystemClock.elapsedRealtime() < end && (n = in.read(buf)) > 0; ) bytes.addAndGet(n);
        } finally {
            c.disconnect();
        }
    }

    private static void send(byte[] buf, AtomicLong bytes, long end) throws IOException {
        int size = 8 * 1024 * 1024;
        HttpURLConnection c = open(UP);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setFixedLengthStreamingMode(size);
        try (OutputStream out = c.getOutputStream()) {
            for (int sent = 0; sent < size && SystemClock.elapsedRealtime() < end; sent += buf.length) {
                int n = Math.min(buf.length, size - sent);
                out.write(buf, 0, n);
                bytes.addAndGet(n);
            }
        } catch (IOException e) {
            // cut short at the end of the phase: what was sent still counts
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "AuraTV-speedtest");
        return c;
    }
}
