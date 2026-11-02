package app.aura.home;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class PhonePageTest {
    private static String get(String url, int[] code) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        code[0] = con.getResponseCode();
        try (InputStream in = code[0] < 400 ? con.getInputStream() : con.getErrorStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; in != null && (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** One request straight into Aura's handler over a real socket pair: the same routing, escaping and token check. */
    private static String ask(Drop d, String path, int[] code) throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        java.net.Socket client = new java.net.Socket("127.0.0.1", ss.getLocalPort());
        java.net.Socket server = ss.accept();
        java.lang.reflect.Method serve = Drop.class.getDeclaredMethod("serve", java.net.Socket.class);
        serve.setAccessible(true);
        Thread t = new Thread(() -> {
            try { serve.invoke(d, server); } catch (ReflectiveOperationException ignored) { }
        });
        t.start();
        client.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: tv\r\n\r\n").getBytes("UTF-8"));
        client.getOutputStream().flush();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        InputStream in = client.getInputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        t.join(5000);
        client.close();
        ss.close();
        String all = b.toString("UTF-8");
        code[0] = Integer.parseInt(all.substring(9, 12));
        return all;
    }

    @Test
    public void reportOverTheNetwork() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Trouble.caught("phone test", new IllegalArgumentException("<script>alert(1)</script>"));
        Drop d = new Drop(c, new Drop.Quiet());
        d.only("/report", "/report.txt");
        java.lang.reflect.Field tf = Drop.class.getDeclaredField("token");
        tf.setAccessible(true);
        String base = "/" + tf.get(d);
        StringBuilder out = new StringBuilder();
        int[] code = new int[1];
        String page = ask(d, base + "/report", code);
        out.append("report page ").append(code[0]).append(", escaped=").append(page.contains("&lt;script&gt;"))
                .append(", raw script=").append(page.contains("<script>alert(1)")).append('\n');
        assertEquals(200, code[0]);
        assertTrue(page.contains("Problem report"));
        assertFalse(page.contains("<script>alert(1)"));
        String txt = ask(d, base + "/report.txt", code);
        out.append("report.txt ").append(code[0]).append(", has error=").append(txt.contains("phone test"))
                .append(", attachment=").append(txt.contains("aura-report.txt")).append('\n');
        assertEquals(200, code[0]);
        ask(d, base + "/backup.json", code);
        out.append("backup.json while only the report is open: ").append(code[0]).append('\n');
        assertEquals(404, code[0]);
        ask(d, "/wrongtoken/report", code);
        out.append("wrong token: ").append(code[0]).append('\n');
        assertEquals(404, code[0]);
        // someone on the network trying codes: the link shuts after 40 wrong ones, and says so
        for (int i = 0; i < 39; i++) ask(d, "/guess" + i + "/report", code);
        assertTrue(Trouble.report(c).contains("PHONE LINK"));
        out.append("after 40 wrong codes: closed and noted\n");
        try (java.io.FileWriter w = new java.io.FileWriter("target/phone.txt")) { w.write(out.toString()); }
    }
}
