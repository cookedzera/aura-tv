package app.aura.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Plain http from a phone or computer on the home network, for {@link Cast}. Android refuses Aura
 * plain http (rightly: nothing from the internet should arrive unencrypted), yet the DLNA apps on
 * phones and computers only serve plain http. So a cast from an address on the home network is
 * fetched through this relay on the TV's own loopback, the one place the network rules let plain
 * http through (res/xml/network_security_config.xml). It reaches that one address and nothing
 * else, and ends with the cast.
 */
final class LanRelay {
    private final InetAddress to;
    private final int port;
    private final String host;
    private final ServerSocket server;
    private final String url;

    /** A relay for {@code url} when it's plain http to the home network, else null. Off the main thread: a name may need looking up. */
    static LanRelay of(String url) {
        try {
            URL u = new URL(url);
            if (!"http".equalsIgnoreCase(u.getProtocol())) return null;
            InetAddress at = InetAddress.getByName(u.getHost());
            return home(at) ? new LanRelay(u, at) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 10.x, 172.16-31.x and 192.168.x, and IPv6's private range: the home network. */
    static boolean home(InetAddress at) {
        byte[] b = at.getAddress();
        return at.isSiteLocalAddress() || b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    LanRelay(URL u, InetAddress at) throws IOException {
        to = at;
        port = u.getPort() > 0 ? u.getPort() : 80;
        host = u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        url = "http://127.0.0.1:" + server.getLocalPort() + (u.getFile().isEmpty() ? "/" : u.getFile());
        new SafeThread(this::accept, "cast-relay").start();
    }

    /** Where the player asks instead. */
    String url() {
        return url;
    }

    void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket in = server.accept();
                new SafeThread(() -> relay(in), "cast-relay-one").start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /**
     * One request: its head passed on as the sender's own (its Host, and one request a connection,
     * so every request is seen here), then the bytes both ways until the answer ends.
     */
    private void relay(Socket in) {
        try (Socket player = in; Socket sender = new Socket()) {
            byte[] head = head(player.getInputStream());
            if (head == null) return;
            sender.connect(new InetSocketAddress(to, port), 8000);
            OutputStream o = sender.getOutputStream();
            o.write(rewrite(new String(head, StandardCharsets.ISO_8859_1)).getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            Thread up = new SafeThread(() -> pump(player, sender), "cast-relay-up");
            up.start();
            pump(sender, player);
            // the player reads the answer to its end and hangs up first: closing under it cut the
            // answer short ("unexpected end of stream"), a photo failing one time in two
            up.join(10_000);
        } catch (IOException | InterruptedException ignored) {
            // the sender gone, or the player stopped: that request ends
        }
    }

    /** The request's head, up to its blank line; null when it doesn't come (or is far too long to be one). */
    private static byte[] head(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int matched = 0;
        for (int c; (c = in.read()) >= 0; ) {
            b.write(c);
            matched = c == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : c == '\r' ? 1 : 0;
            if (matched == 4) return b.toByteArray();
            if (b.size() > 16 * 1024) return null;
        }
        return null;
    }

    private String rewrite(String head) {
        StringBuilder out = new StringBuilder();
        for (String line : head.split("\r\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("host:") || l.startsWith("connection:") || l.startsWith("keep-alive:")) continue;
            out.append(line).append("\r\n");
            if (out.length() == line.length() + 2) out.append("Host: ").append(host).append("\r\nConnection: close\r\n");
        }
        return out.append("\r\n").toString();
    }

    private static void pump(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[32 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            to.shutdownOutput();
        } catch (IOException ignored) {
        }
    }
}
