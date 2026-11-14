package app.aura.home;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a phone casts over plain http from the home network reaches the player through the relay on
 * the TV's loopback, as the sender sent it; nothing else is relayed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class LanRelayTest {

    @Test
    public void onlyTheHomeNetworkIsRelayed() throws Exception {
        for (String home : new String[]{"192.168.31.20", "10.0.0.5", "172.20.1.9", "fd12::1"}) {
            Assert.assertTrue(home, LanRelay.home(InetAddress.getByName(home)));
        }
        for (String not : new String[]{"8.8.8.8", "127.0.0.1", "169.254.1.1", "172.32.0.1", "2001:4860::8888"}) {
            Assert.assertFalse(not, LanRelay.home(InetAddress.getByName(not)));
        }
        // https needs no relay, and the internet never gets one
        Assert.assertNull(LanRelay.of("https://192.168.1.2/a.mp4"));
        Assert.assertNull(LanRelay.of("http://8.8.8.8/a.mp4"));
        Assert.assertNull(LanRelay.of("http://127.0.0.1:8080/a.mp4"));
        LanRelay r = LanRelay.of("http://192.168.1.2:8200/MediaItems/22.mp4?x=1");
        Assert.assertNotNull(r);
        Assert.assertTrue(r.url(), r.url().matches("http://127\\.0\\.0\\.1:\\d+/MediaItems/22\\.mp4\\?x=1"));
        r.close();
    }

    @Test
    public void aRequestReachesTheSenderAsItsOwnAndTheAnswerComesBack() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        try (ServerSocket sender = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread t = new Thread(() -> {
                try (Socket s = sender.accept()) {
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    while (!b.toString("ISO-8859-1").endsWith("\r\n\r\n")) b.write(in.read());
                    seen.set(b.toString("ISO-8859-1"));
                    OutputStream o = s.getOutputStream();
                    o.write("HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.ISO_8859_1));
                    o.flush();
                } catch (Exception ignored) {
                }
            });
            t.start();
            // the phone's address, here answered on this machine's loopback
            LanRelay r = new LanRelay(new URL("http://phone.lan:" + sender.getLocalPort() + "/v.mp4"), InetAddress.getByName("127.0.0.1"));
            URL via = new URL(r.url());
            String answer;
            try (Socket p = new Socket(via.getHost(), via.getPort())) {
                p.getOutputStream().write(("GET /v.mp4 HTTP/1.1\r\nHost: " + via.getHost() + ":" + via.getPort()
                        + "\r\nRange: bytes=100-\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                InputStream in = p.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                for (int c; (c = in.read()) >= 0; ) b.write(c);
                answer = b.toString("ISO-8859-1");
            }
            t.join(5000);
            r.close();
            String head = seen.get();
            Assert.assertNotNull(head);
            Assert.assertTrue(head, head.startsWith("GET /v.mp4 HTTP/1.1\r\n"));
            Assert.assertTrue("the sender's own name, not the relay's: " + head, head.contains("Host: phone.lan:" + sender.getLocalPort() + "\r\n"));
            Assert.assertTrue("seeking still works", head.contains("Range: bytes=100-\r\n"));
            Assert.assertTrue("one request a connection: " + head, head.contains("Connection: close\r\n") && !head.contains("keep-alive"));
            Assert.assertTrue(answer, answer.startsWith("HTTP/1.1 206") && answer.endsWith("hello"));
        }
    }
}
