package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Tonight's pick: highly rated shows from TVmaze's public catalogue (free, no account or key; data
 * CC BY-SA, credited on screen). A pool of about thirty is refreshed weekly in the background with
 * small posters, so Control Center only ever reads from disk.
 */
final class Picks {
    static final class Pick {
        final String name, line, about, poster, query;

        Pick(JSONObject o) {
            name = o.optString("name");
            line = o.optString("line");
            about = o.optString("about");
            poster = o.optString("poster");
            query = o.optString("query");
        }
    }

    private static final String DIR = "picks", INDEX = "picks.json";
    private static final String API = "https://api.tvmaze.com/shows?page=";
    private static final String UA = "AuraTV/6 (open-source Android TV home screen)";
    private static final long WEEK = 7L * 24 * 3_600_000;
    private static final int POOL = 30;
    private static final double MIN_RATING = 8.2;

    private Picks() { }

    static boolean stale(Context c) {
        File f = new File(new File(c.getFilesDir(), DIR), INDEX);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > WEEK;
    }

    static List<Pick> load(Context c) {
        List<Pick> out = new ArrayList<>();
        File f = new File(new File(c.getFilesDir(), DIR), INDEX);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Pick p = new Pick(a.getJSONObject(i));
                if (new File(p.poster).isFile()) out.add(p);
            }
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    static Bitmap poster(Pick p) {
        return BitmapFactory.decodeFile(p.poster);
    }

    /** Weekly: a few random catalogue pages, the best-rated shows with posters, shuffled. */
    static void refresh(Context c) throws IOException, InterruptedException {
        if (!stale(c)) return;
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        Random rnd = new Random();
        List<JSONObject> found = new ArrayList<>();
        int[] pages = {rnd.nextInt(12), 12 + rnd.nextInt(12), 24 + rnd.nextInt(12)};
        try {
            for (int page : pages) {
                JSONArray shows = new JSONArray(new String(get(API + page), StandardCharsets.UTF_8));
                for (int i = 0; i < shows.length(); i++) {
                    JSONObject s = shows.getJSONObject(i);
                    JSONObject rating = s.optJSONObject("rating"), image = s.optJSONObject("image");
                    if (rating == null || image == null || rating.optDouble("average", 0) < MIN_RATING) continue;
                    found.add(s);
                }
                Thread.sleep(1500); // TVmaze asks for gentle request rates
            }
        } catch (JSONException e) {
            return;
        }
        Collections.shuffle(found, rnd);
        JSONArray out = new JSONArray();
        for (JSONObject s : found) {
            if (out.length() >= POOL) break;
            try {
                File img = new File(dir, "p" + s.optInt("id") + ".jpg");
                if (!img.isFile()) {
                    byte[] data = get(s.getJSONObject("image").optString("medium"));
                    try (FileOutputStream fo = new FileOutputStream(img)) {
                        fo.write(data);
                    }
                }
                out.put(entry(s, img));
            } catch (IOException | JSONException ignored) {
            }
        }
        if (out.length() == 0) return;
        // drop posters of shows no longer in the pool
        String keep = out.toString();
        File[] old = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File tmp = new File(dir, INDEX + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(keep.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, INDEX));
    }

    private static JSONObject entry(JSONObject s, File img) throws JSONException {
        JSONArray g = s.optJSONArray("genres");
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "★ %.1f", s.getJSONObject("rating").optDouble("average")));
        for (int i = 0; g != null && i < Math.min(2, g.length()); i++) line.append("  ·  ").append(g.optString(i));
        String premiered = s.optString("premiered", "");
        if (premiered.length() >= 4) line.append("  ·  ").append(premiered, 0, 4);
        String about = s.optString("summary", "").replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        int dot = about.indexOf(". ");
        if (dot > 30) about = about.substring(0, dot + 1);
        if (about.length() > 140) about = about.substring(0, 137).trim() + "…";
        JSONObject e = new JSONObject();
        e.put("name", s.optString("name"));
        e.put("line", line.toString());
        e.put("about", about);
        e.put("poster", img.getAbsolutePath());
        e.put("query", s.optString("name") + " trailer");
        return e;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                return readAll(in);
            }
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
