package app.aura.home;

import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.Assert.*;

/** Memory and time bounds: Top Shelf's per-app cache, the wall's downloads, the phone page's slow requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class MediaBoundsFixesTest {
    /** Passing along a long dock keeps only the last few apps' cards; resting on one again keeps it among them. */
    @Test
    public void topShelfKeepsOnlyTheLastFewApps() throws Exception {
        TopShelf.clearCache();
        Field f = TopShelf.class.getDeclaredField("CACHE");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object[]> cache = (Map<String, Object[]>) f.get(null);
        long now = SystemClock.elapsedRealtime();
        synchronized (cache) {
            for (int i = 0; i < 10; i++) cache.put("app" + i, new Object[]{now, new ArrayList<Continue.Item>()});
        }
        assertEquals(4, cache.size());
        assertTrue(TopShelf.cached("app9"));
        assertFalse(TopShelf.cached("app0"));
        assertTrue(TopShelf.cached("app6"));
        synchronized (cache) {
            cache.put("app10", new Object[]{now, new ArrayList<Continue.Item>()});
        }
        assertTrue("rested on a moment ago", TopShelf.cached("app6"));
        assertFalse("the least recent goes", TopShelf.cached("app7"));
        TopShelf.clearCache();
    }

    /** A picture far bigger than the wall needs is passed over like one that's gone; one in bounds arrives whole. */
    @Test
    public void galleryPassesOverAnOversizedPicture() throws Exception {
        Method get = Gallery.class.getDeclaredMethod("get", String.class);
        get.setAccessible(true);
        assertNull(get.invoke(null, once(9 << 20)));
        byte[] fine = (byte[]) get.invoke(null, once(300 << 10));
        assertNotNull(fine);
        assertEquals(300 << 10, fine.length);
    }

    /** A connection trickling its request in is cut at its deadline, not each read's own wait; a file on its way in has none. */
    @Test
    public void phoneLinkReadsStopAtTheDeadline() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
             Socket server = ss.accept()) {
            Class<?> timed = Class.forName("app.aura.home.Drop$Timed");
            Constructor<?> make = timed.getDeclaredConstructor(Socket.class, int.class, long.class);
            make.setAccessible(true);
            long now = SystemClock.uptimeMillis();
            InputStream late = (InputStream) make.newInstance(server, 10_000, now - 1);
            try {
                late.read();
                fail("past its deadline, a read doesn't wait");
            } catch (SocketTimeoutException expected) {
                // what's being tested
            }
            InputStream soon = (InputStream) make.newInstance(server, 10_000, now + 300);
            long start = System.nanoTime();
            try {
                soon.read();
                fail("nothing was sent");
            } catch (SocketTimeoutException expected) {
                assertTrue("gave up at the deadline, not after 10 s", (System.nanoTime() - start) / 1_000_000 < 5_000);
            }
            Field deadline = timed.getDeclaredField("deadline");
            deadline.setAccessible(true);
            deadline.setLong(soon, Long.MAX_VALUE);
            client.getOutputStream().write(42);
            client.getOutputStream().flush();
            assertEquals(42, soon.read());
        }
    }

    /** One HTTP response of {@code size} bytes from a loopback port, for one request; returns its address. */
    private static String once(int size) throws IOException {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try (ServerSocket s = ss; Socket c = s.accept()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
                for (String l; (l = r.readLine()) != null && !l.isEmpty(); ) {
                    // the request's headers
                }
                OutputStream o = c.getOutputStream();
                o.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + size + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                byte[] chunk = new byte[64 << 10];
                for (int sent = 0; sent < size; sent += chunk.length) o.write(chunk, 0, Math.min(chunk.length, size - sent));
                o.flush();
            } catch (IOException ignored) {
                // the client stopped reading part-way
            }
        });
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + ss.getLocalPort() + "/p.jpg";
    }
}
