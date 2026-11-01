package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * An endless wall of photographs, none shown twice, from sources that need no account or key:
 * Windows Spotlight's 4K lock-screen photographs (a fresh few every run), the archive of every Bing
 * photograph of the day since 2010 (thousands, kept at 4K by the open Bing Wallpaper Archive), and
 * Bing's own photograph of the day should the archive be out of reach. A handful arrive on every
 * background run and the oldest leave, so the wall keeps changing without filling the TV; every
 * photograph shown is remembered, and only once all of them have been can one come round again.
 */
final class Gallery {
    private static final String DIR = "gallery", INDEX = "index.json", ARCHIVE_LIST = "archive.tsv";
    private static final String UA = "AuraTV/7.6 (open-source Android TV home screen; wallpaper gallery)";
    private static final String ARCHIVE = "https://bing.npanuhin.me/US/en.json";
    private static final String SPOTLIGHT = "https://fd.api.iris.microsoft.com/v4/api/selection?placement=88000820&bcnt=4&fmt=json&country=US&locale=en-US";
    private static final String[] MARKETS = {"en-US", "en-GB", "en-IN", "de-DE", "fr-FR", "ja-JP", "en-AU", "it-IT"};
    private static final int W = 1920, H = 1080, KEEP = 48, PER_RUN = 6, SEEN_MAX = 9000;
    private static final long ARCHIVE_MS = 30 * 86_400_000L, GAP_MS = 1_500;
    private static final Object LOCK = new Object();
    private static final Random RND = new Random();

    private Gallery() { }

    // ---- reading (for the backdrop) ----------------------------------------------------------

    static long stamp(Context c) {
        return new File(dir(c), INDEX).lastModified();
    }

    /** Fewer than a dozen photographs on the TV: worth a run soon. */
    static boolean hungry(Context c) {
        return readIndex(c).length() < 12;
    }

    static List<Backdrop.Wallpaper> walls(Context c) {
        List<Backdrop.Wallpaper> out = new ArrayList<>();
        JSONArray index = readIndex(c);
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            if (o != null && new File(o.optString("path")).isFile()) out.add(new Backdrop.Wallpaper(o));
        }
        return out;
    }

    // ---- importing (on the job's thread) -----------------------------------------------------

    /** A few new photographs; false if the network failed part-way, so the job can retry. */
    static boolean run(Context c) {
        synchronized (LOCK) {
            if (!Prefs.on(c, Prefs.DAILY)) return true;
            File dir = dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) return true;
            // a source switched off: what it brought leaves the wall
            JSONArray index = retire(c, readIndex(c));
            Set<String> seen = seen(c);
            boolean ok = true;
            int added = 0, want = index.length() < 12 ? PER_RUN * 2 : PER_RUN;
            List<String> sources = sources(c);
            // shared out between the sources that are on, in turn, so each has its place on the wall
            int each = sources.isEmpty() ? 0 : Math.max(1, (want + sources.size() - 1) / sources.size());
            for (String src : sources) {
                if (added >= want) break;
                int n = Math.min(each, want - added);
                try {
                    List<JSONObject> got;
                    switch (src) {
                        case "spotlight": got = spotlight(c, dir, seen, n); break;
                        case "wikimedia": got = wikimedia(c, dir, seen, n); break;
                        case "wallhaven": got = wallhaven(c, dir, seen, n); break;
                        case "unsplash": got = unsplash(c, dir, seen, n); break;
                        default:
                            got = archive(c, dir, seen, n);
                            // the archive out of reach: Bing's own photograph of the day still comes
                            if (got.isEmpty()) got = bing(c, dir, seen, Math.min(2, n));
                    }
                    for (JSONObject e : got) {
                        index.put(e);
                        write(c, index = prune(index));
                        added++;
                    }
                } catch (InterruptedException e) {
                    ok = false;
                    break;
                } catch (IOException | JSONException | RuntimeException e) {
                    // one source down doesn't keep the others from the wall
                    ok = false;
                }
            }
            try {
                if (added == 0) write(c, index);
            } catch (IOException e) {
                ok = false;
            }
            saveSeen(c, seen);
            sweep(dir, readIndex(c));
            return ok;
        }
    }

    /** The sources switched on, in the order they take turns; Unsplash only once it has a key. */
    static List<String> sources(Context c) {
        List<String> out = new ArrayList<>();
        if (Prefs.on(c, Prefs.SRC_SPOTLIGHT)) out.add("spotlight");
        if (Prefs.on(c, Prefs.SRC_BING)) out.add("bing");
        if (Prefs.on(c, Prefs.SRC_WIKIMEDIA)) out.add("wikimedia");
        if (Prefs.on(c, Prefs.SRC_WALLHAVEN)) out.add("wallhaven");
        if (Prefs.on(c, Prefs.SRC_UNSPLASH) && !unsplashKey(c).isEmpty()) out.add("unsplash");
        return out;
    }

    static String unsplashKey(Context c) {
        return Prefs.device(c).getString(Prefs.UNSPLASH_KEY, "");
    }

    /** The index without the photographs of sources now switched off (by their ids' prefix), their files deleted. */
    private static JSONArray retire(Context c, JSONArray index) {
        List<String> on = sources(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), src = id.startsWith("s:") ? "spotlight" : id.startsWith("c:") ? "wikimedia"
                    : id.startsWith("w:") ? "wallhaven" : id.startsWith("u:") ? "unsplash" : "bing";
            if (!on.contains(src)) //noinspection ResultOfMethodCallIgnored
                new File(o.optString("path")).delete();
            else out.put(o);
        }
        return out;
    }

    /** Wikimedia Commons' Featured Pictures of landscapes, mountains, lakes, forests and coasts, from a random moment in their history. */
    private static List<JSONObject> wikimedia(Context c, File dir, Set<String> seen, int max)
            throws IOException, JSONException, InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        String[] cats = {"landscapes", "mountains", "lakes", "forests", "coasts"};
        // a random day since 2008: Commons orders a category by when each picture joined it
        long from = 1_199_145_600_000L + (long) (RND.nextDouble() * (System.currentTimeMillis() - 1_199_145_600_000L));
        String start = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).format(new java.util.Date(from));
        byte[] body = get("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo"
                + "&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=" + W + "&generator=categorymembers&gcmtype=file&gcmlimit=40"
                + "&gcmsort=timestamp&gcmstart=" + start + "&gcmtitle=Category:Featured_pictures_of_" + cats[RND.nextInt(cats.length)]);
        JSONObject q = body == null ? null : new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONObject("query");
        JSONObject pages = q == null ? null : q.optJSONObject("pages");
        if (pages == null) return out;
        java.util.Iterator<String> keys = pages.keys();
        while (keys.hasNext() && out.size() < max) {
            JSONObject page = pages.optJSONObject(keys.next());
            JSONArray infos = page == null ? null : page.optJSONArray("imageinfo");
            JSONObject info = infos == null ? null : infos.optJSONObject(0);
            if (info == null) continue;
            String id = "c:" + page.optString("title");
            int ow = info.optInt("width"), oh = info.optInt("height");
            // TV-shaped, large photographs only
            if (seen.contains(id) || ow < W || ow < oh * 1.3f || !"image/jpeg".equals(info.optString("mime"))) continue;
            seen.add(id);
            Thread.sleep(GAP_MS);
            byte[] data = get(info.optString("thumburl", info.optString("url")));
            if (data == null) continue;
            JSONObject meta = info.optJSONObject("extmetadata");
            String who = credit(meta == null || meta.optJSONObject("Artist") == null ? ""
                    : meta.optJSONObject("Artist").optString("value").replaceAll("<[^>]*>", " "));
            String lic = meta == null || meta.optJSONObject("LicenseShortName") == null ? "" : meta.optJSONObject("LicenseShortName").optString("value");
            String title = page.optString("title");
            String about = meta == null || meta.optJSONObject("ImageDescription") == null ? ""
                    : meta.optJSONObject("ImageDescription").optString("value").replaceAll("<[^>]*>", " ").replaceAll("&[a-z]+;", " ");
            JSONObject e = file(c, dir, id, data, Daily.place(title, about), story(about), who, lic, "Wikimedia Commons");
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Wallhaven: nature and landscape wallpapers only, safe for work, 16:9 and at least full HD, in a random order. */
    private static List<JSONObject> wallhaven(Context c, File dir, Set<String> seen, int max)
            throws IOException, JSONException, InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        String seed = Integer.toString(RND.nextInt(Integer.MAX_VALUE), 36);
        byte[] body = get("https://wallhaven.cc/api/v1/search?q=nature%20landscape&categories=100&purity=100"
                + "&atleast=1920x1080&ratios=16x9&sorting=random&seed=" + seed);
        JSONArray data = body == null ? null : new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("data");
        for (int i = 0; data != null && i < data.length() && out.size() < max; i++) {
            JSONObject o = data.getJSONObject(i);
            String id = "w:" + o.optString("id"), path = o.optString("path");
            if (seen.contains(id) || !path.startsWith("https://") || !"sfw".equals(o.optString("purity"))) continue;
            seen.add(id);
            // Wallhaven asks for no more than 45 requests a minute
            Thread.sleep(GAP_MS);
            byte[] img = get(path);
            if (img == null) continue;
            JSONObject e = file(c, dir, id, img, "A wallpaper from Wallhaven", "Chosen by the Wallhaven community: nature and landscapes only.", "", "", "Wallhaven");
            if (e != null) out.add(e);
        }
        return out;
    }

    /**
     * Unsplash, with your own free key: nature and landscape photographs, filtered strictly for
     * content, each credited to its photographer, and each download reported back as Unsplash asks.
     */
    private static List<JSONObject> unsplash(Context c, File dir, Set<String> seen, int max)
            throws IOException, JSONException, InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        String key = unsplashKey(c);
        byte[] body = get("https://api.unsplash.com/photos/random?count=10&orientation=landscape&content_filter=high&query=nature%20landscape", key);
        JSONArray a = body == null ? null : new JSONArray(new String(body, StandardCharsets.UTF_8));
        for (int i = 0; a != null && i < a.length() && out.size() < max; i++) {
            JSONObject o = a.getJSONObject(i);
            String id = "u:" + o.optString("id");
            JSONObject urls = o.optJSONObject("urls");
            String raw = urls == null ? "" : urls.optString("raw");
            if (seen.contains(id) || !raw.startsWith("https://")) continue;
            seen.add(id);
            Thread.sleep(GAP_MS);
            byte[] img = get(raw + (raw.contains("?") ? "&" : "?") + "w=" + W + "&h=" + H + "&fit=crop&q=85&fm=jpg");
            if (img == null) continue;
            JSONObject links = o.optJSONObject("links");
            if (links != null && links.optString("download_location").startsWith("https://")) get(links.optString("download_location"), key);
            JSONObject loc = o.optJSONObject("location"), user = o.optJSONObject("user");
            String where = loc == null ? "" : loc.optString("name", "");
            if (where.isEmpty() || where.equals("null")) where = o.optString("alt_description", "");
            if (where.equals("null")) where = "";
            if (!where.isEmpty()) where = Character.toUpperCase(where.charAt(0)) + where.substring(1);
            String who = user == null ? "" : credit(user.optString("name"));
            String about = o.optString("description", "");
            if (about.isEmpty() || about.equals("null")) about = o.optString("alt_description", "");
            if (about.equals("null")) about = "";
            JSONObject e = file(c, dir, id, img, cut(where, 60), story(about), who, "", "Unsplash");
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Whether Unsplash takes {@code key}. Off the main thread. */
    static boolean checkUnsplash(String key) {
        try {
            return get("https://api.unsplash.com/photos/random?count=1", key) != null;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Files an interrupted run left behind, not in the index: deleted. */
    private static void sweep(File dir, JSONArray index) {
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < index.length(); i++) if (index.optJSONObject(i) != null) keep.add(index.optJSONObject(i).optString("file"));
        File[] fs = dir.listFiles((d, n) -> n.startsWith("g") && (n.endsWith(".jpg") || n.endsWith(".tmp")));
        for (int i = 0; fs != null && i < fs.length; i++) if (!keep.contains(fs[i].getName())) //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
    }

    /** Windows Spotlight: a few 4K photographs, a different few on every call. */
    private static List<JSONObject> spotlight(Context c, File dir, Set<String> seen, int max)
            throws IOException, JSONException, InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        byte[] body = get(SPOTLIGHT);
        if (body == null) return out;
        JSONObject rsp = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONObject("batchrsp");
        JSONArray items = rsp == null ? null : rsp.optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && out.size() < max; i++) {
            JSONObject ad = new JSONObject(items.getJSONObject(i).optString("item", "{}")).optJSONObject("ad");
            JSONObject img = ad == null ? null : ad.optJSONObject("landscapeImage");
            String url = img == null ? "" : img.optString("asset");
            if (!url.startsWith("https://")) continue;
            // the file name without its per-request prefix names the photograph
            String id = "s:" + url.replaceAll(".*/", "").replaceAll("^[0-9a-f]{8}-[0-9a-f-]+_", "");
            if (seen.contains(id)) continue;
            seen.add(id);
            Thread.sleep(GAP_MS);
            byte[] data = get(url);
            if (data == null) continue;
            String where = ad.optString("iconHoverText").split("\r?\n")[0].trim();
            JSONObject e = file(c, dir, id, data, where.isEmpty() ? ad.optString("title") : where, story(ad.optString("description")),
                    credit(ad.optString("copyright")), "", "Windows Spotlight");
            if (e != null) out.add(e);
        }
        return out;
    }

    /** The Bing archive: photographs from any day since 2010 not shown yet, chosen at random. */
    private static List<JSONObject> archive(Context c, File dir, Set<String> seen, int max)
            throws IOException, JSONException, InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        if (max <= 0) return out;
        List<String[]> all = archiveList(c);
        List<String[]> fresh = new ArrayList<>();
        for (String[] a : all) if (!seen.contains(archiveId(a))) fresh.add(a);
        // every one of them seen: the archive starts over, so it never runs dry
        if (fresh.isEmpty() && !all.isEmpty()) {
            for (String[] a : all) seen.remove(archiveId(a));
            fresh.addAll(all);
        }
        Collections.shuffle(fresh, RND);
        for (int i = 0; i < fresh.size() && out.size() < max && i < max * 3; i++) {
            String[] a = fresh.get(i);
            seen.add(archiveId(a));
            Thread.sleep(GAP_MS);
            byte[] data = get(a[3]);
            if (data == null) continue;
            JSONObject e = file(c, dir, archiveId(a), data, placeIn(a[1]), a[1], credit(a[2]), "",
                    "Bing  ·  " + (a[0].length() >= 4 ? a[0].substring(0, 4) : ""));
            if (e != null) out.add(e);
        }
        return out;
    }

    /** The same photograph as Bing's own feed names it, so the two sources never show it twice. */
    private static String archiveId(String[] a) {
        String ohr = a[4].replaceAll(".*OHR\\.([^_]+)_.*", "$1");
        return a[4].contains("OHR.") ? "bing:" + ohr : "a:" + a[0];
    }

    /**
     * The archive's list (date, title, credit, picture, Bing's own address), fetched once a month and
     * kept as plain lines: the list runs to megabytes, so it's read as a stream, never held whole.
     */
    private static List<String[]> archiveList(Context c) throws IOException {
        File f = new File(dir(c), ARCHIVE_LIST);
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > ARCHIVE_MS) {
            File tmp = new File(dir(c), ARCHIVE_LIST + ".tmp");
            HttpURLConnection con = (HttpURLConnection) new URL(ARCHIVE).openConnection();
            con.setRequestProperty("User-Agent", UA);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(60_000);
            try {
                if (con.getResponseCode() == 200) {
                    try (JsonReader r = new JsonReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                         Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                        r.beginArray();
                        while (r.hasNext()) {
                            String[] a = {"", "", "", "", ""};
                            r.beginObject();
                            while (r.hasNext()) {
                                String k = r.nextName();
                                if (r.peek() != JsonToken.STRING) {
                                    r.skipValue();
                                    continue;
                                }
                                String v = r.nextString().replace('\t', ' ').replace('\n', ' ');
                                switch (k) {
                                    case "date": a[0] = v; break;
                                    case "title": a[1] = v; break;
                                    case "copyright": a[2] = v; break;
                                    case "url": a[3] = v; break;
                                    case "bing_url": a[4] = v; break;
                                    default: break;
                                }
                            }
                            r.endObject();
                            if (a[3].startsWith("https://") && !a[1].isEmpty()) w.write(android.text.TextUtils.join("\t", a) + "\n");
                        }
                        r.endArray();
                    }
                    if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                }
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                // out of reach this time: last month's list still serves
                if (!f.isFile()) throw e instanceof IOException ? (IOException) e : new IOException(e);
            } finally {
                con.disconnect();
            }
        }
        List<String[]> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] a = line.split("\t", -1);
                if (a.length == 5) out.add(a);
            }
        }
        return out;
    }

    /** Bing's photograph of the day, from one market a run, in turn. */
    private static List<JSONObject> bing(Context c, File dir, Set<String> seen, int max)
            throws IOException, JSONException, InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        SharedPreferences p = prefs(c);
        int turn = p.getInt("gallery_market", 0);
        p.edit().putInt("gallery_market", turn + 1).apply();
        String mkt = MARKETS[Compat.mod(turn, MARKETS.length)];
        byte[] body = get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + mkt);
        if (body == null) return out;
        JSONArray imgs = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("images");
        for (int i = 0; imgs != null && i < imgs.length() && out.size() < max; i++) {
            JSONObject o = imgs.getJSONObject(i);
            String base = o.optString("urlbase");
            // the same photograph runs in many countries under different ids: its name says which
            String id = "bing:" + base.replaceAll(".*OHR\\.([^_]+)_.*", "$1");
            if (base.isEmpty() || seen.contains(id)) continue;
            Thread.sleep(1_000);
            byte[] data = get("https://www.bing.com" + base + "_UHD.jpg&w=" + W + "&h=" + H + "&rs=1&c=4");
            seen.add(id);
            if (data == null) continue;
            String copy = o.optString("copyright");
            int paren = copy.lastIndexOf(" (");
            String where = paren > 0 ? copy.substring(0, paren).trim() : copy;
            String who = paren > 0 ? credit(copy.substring(paren + 2).replace(")", "")) : "";
            JSONObject e = file(c, dir, id, data, Daily.place(where, where), where, who, "", "Bing");
            if (e != null) out.add(e);
        }
        return out;
    }

    /** A few whole sentences, at most about 220 characters: something to read while the picture is up. */
    private static String story(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (s.length() <= 220) return s;
        int end = -1;
        for (int i = s.indexOf(". "); i > 0 && i < 220; i = s.indexOf(". ", i + 1)) end = i + 1;
        return end > 40 ? s.substring(0, end) : cut(s, 220);
    }

    /** Where a Bing title says the photograph was taken ("… in the Rio Grande Valley of Texas"), else the title itself. */
    private static String placeIn(String title) {
        int comma = title.lastIndexOf(", "), in = title.lastIndexOf(" in "), prev = comma > 0 ? title.lastIndexOf(", ", comma - 1) : -1;
        String p = in > 0 ? title.substring(in + 4) : comma > 0 ? title.substring(prev < 0 ? 0 : prev + 2) : title;
        p = p.replaceFirst("^the ", "The ").trim();
        return cut(p.isEmpty() ? title : Character.toUpperCase(p.charAt(0)) + p.substring(1), 60);
    }

    /** A photographer's credit without the copyright sign. */
    private static String credit(String s) {
        return cut(s.replace("©", "").replaceAll("\\s+", " ").trim(), 40);
    }

    /** Crops the photograph to the TV, files it, and returns its index entry; null if it won't decode. */
    private static JSONObject file(Context c, File dir, String id, byte[] data, String place, String line, String author,
                                   String license, String region) throws IOException, JSONException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= W && o.outHeight / (o.inSampleSize * 2) >= H) o.inSampleSize *= 2;
        Bitmap b, fit;
        try {
            b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            fit = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            // the job shares the home screen's process: skip this one rather than take Aura down
            return null;
        }
        float s = Math.max(W / (float) b.getWidth(), H / (float) b.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((W - b.getWidth() * s) / 2f, (H - b.getHeight() * s) / 2f);
        new Canvas(fit).drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        b.recycle();
        String name = "g" + Integer.toHexString(id.hashCode()) + "_" + System.currentTimeMillis() + ".jpg";
        File out = new File(dir, name), tmp = new File(dir, name + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fit.compress(Bitmap.CompressFormat.JPEG, 92, fo);
        }
        if (!tmp.renameTo(out)) {
            fit.recycle();
            throw new IOException("couldn't file " + out);
        }
        JSONObject e = new JSONObject();
        e.put("id", id);
        e.put("file", name);
        e.put("path", out.getAbsolutePath());
        e.put("day", "");
        e.put("added", System.currentTimeMillis());
        e.put("place", place);
        e.put("region", region);
        e.put("line", line);
        e.put("author", author);
        e.put("license", license);
        e.put("mood", Daily.mood(fit));
        e.put("accent", String.format(Locale.ROOT, "#%06X", AppRepo.colorOf(new BitmapDrawable(c.getResources(), fit)) & 0xFFFFFF));
        fit.recycle();
        return e;
    }

    // ---- network and text ----------------------------------------------------------------------

    private static byte[] get(String url) throws IOException, InterruptedException {
        return get(url, null);
    }

    /** With {@code unsplash}, the request carries that Unsplash key, as its API asks. */
    private static byte[] get(String url, String unsplash) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestProperty("User-Agent", UA);
            if (unsplash != null) {
                con.setRequestProperty("Authorization", "Client-ID " + unsplash);
                con.setRequestProperty("Accept-Version", "v1");
            }
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            try {
                int code = con.getResponseCode();
                if (code == 429 || code == 503) {
                    if (attempt >= 2) throw new IOException("rate limited");
                    Thread.sleep(20_000L * (attempt + 1));
                    continue;
                }
                if (code == 404 || code == 400) return null;
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        if (Thread.interrupted()) throw new InterruptedIOException();
                        bo.write(buf, 0, n);
                    }
                    return bo.toByteArray();
                }
            } finally {
                con.disconnect();
            }
        }
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int sp = s.lastIndexOf(' ', max - 1);
        return s.substring(0, sp > max / 2 ? sp : max - 1).trim() + "…";
    }

    // ---- index ---------------------------------------------------------------------------------

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    /**
     * Its own file: the seen list runs to thousands of names, and the shared "aura" file is rewritten
     * whole on every write (each Home press's timing among them).
     */
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gallery", Context.MODE_PRIVATE);
    }

    private static Set<String> seen(Context c) {
        return new HashSet<>(prefs(c).getStringSet("gallery_seen", new HashSet<>()));
    }

    private static void saveSeen(Context c, Set<String> seen) {
        if (seen.size() > SEEN_MAX) {
            List<String> l = new ArrayList<>(seen);
            Collections.shuffle(l, RND);
            seen = new HashSet<>(l.subList(0, SEEN_MAX));
        }
        prefs(c).edit().putStringSet("gallery_seen", seen).apply();
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONArray readIndex(Context c) {
        File f = new File(dir(c), INDEX);
        if (!f.isFile()) return new JSONArray();
        try {
            return new JSONArray(read(new FileInputStream(f)));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    /** Newest first, at most {@link #KEEP}; photographs that fall off the end are deleted. */
    private static JSONArray prune(JSONArray index) {
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < index.length(); i++) if (index.optJSONObject(i) != null) all.add(index.optJSONObject(i));
        Collections.sort(all, (a, b) -> Long.compare(b.optLong("added"), a.optLong("added")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.size(); i++) {
            if (i < KEEP) out.put(all.get(i));
            else //noinspection ResultOfMethodCallIgnored
                new File(all.get(i).optString("path")).delete();
        }
        return out;
    }

    private static void write(Context c, JSONArray index) throws IOException {
        File f = new File(dir(c), INDEX), tmp = new File(dir(c), INDEX + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("couldn't write the index");
    }
}
