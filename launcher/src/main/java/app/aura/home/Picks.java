package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Tonight's pick: a film or show worth the evening, to your taste. With nothing set up, highly
 * rated shows from TVmaze's public catalogue (free, no account or key; data CC BY-SA, credited on
 * screen). With your own free TMDB key: films too, what's trending this week, only what's on the
 * streaming apps you have, or your own TMDB watchlist (this product uses the TMDB API but is not
 * endorsed or certified by TMDB). Genres, a lowest rating and the years narrow any of them.
 *
 * A pool of about thirty is refreshed weekly in the background, and at once whenever the taste
 * changes, with small posters, so the home screen and Control Center only ever read from disk.
 * The taste is the TV's, like the pool: everyone on it shares one.
 */
final class Picks {
    static final class Pick {
        final String name, line, about, poster, query, why, yt, src;

        Pick(JSONObject o) {
            name = o.optString("name");
            line = o.optString("line");
            about = o.optString("about");
            poster = o.optString("poster");
            query = o.optString("query");
            why = o.optString("why", "TOP RATED");
            yt = o.optString("yt");
            src = o.optString("src", "TVmaze");
        }
    }

    /** A genre as Settings offers it, with TVmaze's names for it (genres or show types) and TMDB's film and series ids (0: none). */
    static final class Genre {
        final String id;
        /** Its name on screen, a string resource. */
        final int name;
        final String[] maze;
        final int film, show;

        Genre(String id, int name, int film, int show, String... maze) {
            this.id = id;
            this.name = name;
            this.film = film;
            this.show = show;
            this.maze = maze;
        }
    }

    static final Genre[] GENRES = {
            new Genre("action", R.string.genre_action, 28, 10759, "Action"),
            new Genre("adventure", R.string.genre_adventure, 12, 10759, "Adventure"),
            new Genre("animation", R.string.genre_animation, 16, 16, "Anime", "Animation"),
            new Genre("comedy", R.string.genre_comedy, 35, 35, "Comedy"),
            new Genre("crime", R.string.genre_crime, 80, 80, "Crime", "Legal"),
            new Genre("documentary", R.string.genre_documentary, 99, 99, "Documentary"),
            new Genre("drama", R.string.genre_drama, 18, 18, "Drama", "Medical"),
            new Genre("family", R.string.genre_family, 10751, 10762, "Family", "Children"),
            new Genre("fantasy", R.string.genre_fantasy, 14, 10765, "Fantasy", "Supernatural"),
            new Genre("history", R.string.genre_history, 36, 10768, "History"),
            new Genre("horror", R.string.genre_horror, 27, 0, "Horror"),
            new Genre("music", R.string.genre_music, 10402, 0, "Music"),
            new Genre("mystery", R.string.genre_mystery, 9648, 9648, "Mystery"),
            new Genre("reality", R.string.genre_reality, 0, 10764, "Reality"),
            new Genre("romance", R.string.genre_romance, 10749, 0, "Romance"),
            new Genre("scifi", R.string.genre_scifi, 878, 10765, "Science-Fiction"),
            new Genre("thriller", R.string.genre_thriller, 53, 9648, "Thriller", "Espionage"),
            new Genre("war", R.string.genre_war, 10752, 10768, "War"),
            new Genre("western", R.string.genre_western, 37, 37, "Western"),
    };

    static final String KIND = "pick_kind", GENRE_LIST = "pick_genres", RATING = "pick_rating", ERA = "pick_era",
            SOURCE = "pick_source", MY_APPS = "pick_my_apps", KEY = "tmdb_key", SESSION = "tmdb_session",
            ACCOUNT = "tmdb_account", USER = "tmdb_user";
    private static final String MADE_FOR = "pick_made_for", COUNT = "pick_count";
    static final String[] KINDS = {"shows", "films", "both"};
    static final int[] KIND_NAMES = {R.string.kind_shows, R.string.kind_films, R.string.kind_both},
            KIND_WORDS = {R.string.kind_shows_word, R.string.kind_films_word, R.string.kind_both_word};
    static final String[] SOURCES = {"top", "trending", "watchlist"};
    static final int[] SOURCE_NAMES = {R.string.source_top, R.string.source_trending, R.string.your_tmdb_watchlist};
    static final float[] RATINGS = {6.5f, 7f, 7.5f, 8f, 8.5f};
    static final String[] ERAS = {"any", "2020", "2010", "2000", "classic"};
    static final int[] ERA_NAMES = {R.string.era_any, R.string.era_2020, R.string.era_2010, R.string.era_2000, R.string.era_classic};

    /** Streaming apps and their TMDB watch provider ids, for "only what's on my apps". */
    private static final String[][] PROVIDERS = {
            {"com.netflix.ninja", "8"}, {"com.amazon.amazonvideo.livingroom", "9|119"}, {"com.disney.disneyplus", "337"},
            {"com.hulu.livingroomplus", "15"}, {"com.wbd.stream", "1899"}, {"com.hbo.hbonow", "1899|384"},
            {"com.apple.atve.androidtv.appletv", "350"}, {"com.cbs.ott", "531"}, {"com.peacocktv.peacockandroid", "386|387"},
            {"in.startv.hotstar", "122|2336"}, {"com.jio.media.ondemand", "220"}, {"com.graymatrix.did", "232"},
            {"com.sonyliv", "237"}, {"com.crunchyroll.crunchyroid", "283"}, {"com.mubi", "11"},
            {"com.amazon.avod.thirdpartyclient", "9|119"}, {"com.netflix.mediaclient", "8"}, {"com.amazon.avod", "9|119"},
    };

    // renamed (with the posters' "q" prefix) when the posters grew from 185 px, so the pool is made again
    private static final String DIR = "picks", INDEX = "picks2.json";
    private static final String MAZE = "https://api.tvmaze.com/shows?page=", TMDB = "https://api.themoviedb.org/3",
            POSTER = "https://image.tmdb.org/t/p/w342";
    private static final String UA = "AuraTV/7 (open-source Android TV home screen)";
    private static final long WEEK = 7L * 24 * 3_600_000;
    private static final int POOL = 30;
    private static final Object LOCK = new Object();

    private Picks() { }

    // ---- taste ---------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return Prefs.device(c);
    }

    static String key(Context c) {
        return prefs(c).getString(KEY, "");
    }

    /** Your own TMDB key is set: films, trending and your watchlist become possible. */
    static boolean tmdb(Context c) {
        return !key(c).isEmpty();
    }

    static boolean signedIn(Context c) {
        return tmdb(c) && !prefs(c).getString(SESSION, "").isEmpty();
    }

    static String user(Context c) {
        return prefs(c).getString(USER, "");
    }

    /** Shows, films or both; TVmaze has shows only, so without TMDB it's always shows. */
    static String kind(Context c) {
        if (!tmdb(c)) return "shows";
        String k = prefs(c).getString(KIND, "both");
        return Arrays.asList(KINDS).contains(k) ? k : "both";
    }

    /** Top rated, trending (TMDB) or your watchlist (signed in to TMDB). */
    static String source(Context c) {
        String s = prefs(c).getString(SOURCE, "top");
        if (!tmdb(c) || s.equals("watchlist") && !signedIn(c) || !Arrays.asList(SOURCES).contains(s)) return "top";
        return s;
    }

    static float rating(Context c) {
        return prefs(c).getFloat(RATING, 8f);
    }

    static String era(Context c) {
        String e = prefs(c).getString(ERA, "any");
        return Arrays.asList(ERAS).contains(e) ? e : "any";
    }

    static boolean myApps(Context c) {
        return tmdb(c) && prefs(c).getBoolean(MY_APPS, false);
    }

    static Set<String> genres(Context c) {
        Set<String> out = new LinkedHashSet<>();
        for (String g : prefs(c).getString(GENRE_LIST, "").split(",")) if (!g.trim().isEmpty()) out.add(g.trim());
        return out;
    }

    static void setGenres(Context c, Set<String> g) {
        StringBuilder b = new StringBuilder();
        for (Genre x : GENRES) if (g.contains(x.id)) b.append(b.length() > 0 ? "," : "").append(x.id);
        prefs(c).edit().putString(GENRE_LIST, b.toString()).apply();
    }

    /** Why a pick is on the card ("TOP RATED"), kept in English in the pool, in the TV's language. */
    static String why(Context c, String why) {
        switch (why) {
            case "ON YOUR APPS":
                return c.getString(R.string.why_on_your_apps);
            case "TRENDING THIS WEEK":
                return c.getString(R.string.why_trending);
            case "ON YOUR WATCHLIST":
                return c.getString(R.string.why_watchlist);
            default:
                return c.getString(R.string.why_top_rated);
        }
    }

    /** "Any", or the chosen genres by name: "Drama, Crime +2". */
    static String genreSummary(Context c) {
        List<String> names = new ArrayList<>();
        Set<String> g = genres(c);
        for (Genre x : GENRES) if (g.contains(x.id)) names.add(c.getString(x.name));
        if (names.isEmpty()) return c.getString(R.string.any_genre_short);
        return names.size() <= 2 ? android.text.TextUtils.join(", ", names) : c.getString(R.string.genres_more, names.get(0), names.get(1), names.size() - 2);
    }

    /** Everything the pool depends on, in one line: a pool made for another taste is made again. */
    private static String taste(Context c) {
        List<String> g = new ArrayList<>(genres(c));
        Collections.sort(g);
        return kind(c) + "|" + source(c) + "|" + g + "|" + rating(c) + "|" + era(c) + "|" + myApps(c) + "|" + tmdb(c)
                + "|" + prefs(c).getString(ACCOUNT, "")
                // the cards are written in the TV's language: another language makes them again
                + "|" + Locale.getDefault().getLanguage();
    }

    /** How many picks the pool holds now (-1 before the first is made). */
    static int count(Context c) {
        return prefs(c).getInt(COUNT, -1);
    }

    static boolean stale(Context c) {
        File f = new File(new File(c.getFilesDir(), DIR), INDEX);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > WEEK || !taste(c).equals(prefs(c).getString(MADE_FOR, ""));
    }

    // ---- reading -------------------------------------------------------------------------------

    /** When the pool was last written: anything made from it is made again once this changes. */
    static long stamp(Context c) {
        return new File(new File(c.getFilesDir(), DIR), INDEX).lastModified();
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

    /** The poster, halved only while it stays at least 600 px wide, in 16-bit colour. */
    static Bitmap poster(Pick p) {
        return Continue.picture(p.poster, 600);
    }

    /** Its trailer: straight to the video on YouTube when TMDB named one, else a search there, else the TV's own search. */
    static Intent watch(Context c, Pick p) {
        PackageManager pm = c.getPackageManager();
        if (!p.yt.isEmpty()) {
            Uri video = Uri.parse("https://www.youtube.com/watch?v=" + p.yt);
            Intent v = new Intent(Intent.ACTION_VIEW, video).setPackage("com.google.android.youtube.tv");
            if (v.resolveActivity(pm) != null) return v;
            // no Android TV YouTube (Fire TV has its own, under another name): whatever app here opens
            // YouTube's links, as long as it isn't only a browser
            Intent any = new Intent(Intent.ACTION_VIEW, video);
            android.content.pm.ResolveInfo ri = pm.resolveActivity(any, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)
                    && ri.activityInfo.packageName.contains("youtube")) return any;
        }
        Intent yt = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube.tv")
                .putExtra(android.app.SearchManager.QUERY, p.query);
        if (yt.resolveActivity(pm) != null) return yt;
        Intent global = new Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, p.query);
        if (global.resolveActivity(pm) != null) return global;
        // nothing that searches: YouTube's page for the trailer, in whatever opens links
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(p.query + " trailer")));
    }

    // ---- making the pool -----------------------------------------------------------------------

    /**
     * Makes the pool again when it's a week old or the taste changed ({@code force}: regardless).
     * Off the main thread. False when nothing could be fetched; an empty pool (nothing matches)
     * still counts as done, so a narrow taste isn't asked for again and again.
     */
    static boolean refresh(Context c, boolean force) throws IOException, InterruptedException {
        synchronized (LOCK) {
            if (!force && !stale(c)) return true;
            File dir = new File(c.getFilesDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            String taste = taste(c);
            List<JSONObject> found;
            try {
                found = tmdb(c) ? fromTmdb(c) : fromMaze(c);
            } catch (JSONException e) {
                return false;
            }
            JSONArray out = new JSONArray();
            for (JSONObject s : found) {
                if (out.length() >= POOL) break;
                try {
                    File img = new File(dir, "q" + s.getString("id") + ".jpg");
                    if (!img.isFile()) {
                        byte[] data = get(s.getString("art"), null);
                        try (FileOutputStream fo = new FileOutputStream(img)) {
                            fo.write(data);
                        }
                    }
                    s.put("poster", img.getAbsolutePath());
                    s.remove("art");
                    out.put(s);
                } catch (IOException | JSONException ignored) {
                }
            }
            if (out.length() == 0 && !found.isEmpty()) return false;
            // posters of picks no longer in the pool go
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
            prefs(c).edit().putString(MADE_FOR, taste).putInt(COUNT, out.length()).apply();
            return true;
        }
    }

    /** The years asked for, as {from, to} (inclusive). */
    private static int[] years(Context c) {
        switch (era(c)) {
            case "2020":
                return new int[]{2020, 9999};
            case "2010":
                return new int[]{2010, 9999};
            case "2000":
                return new int[]{2000, 9999};
            case "classic":
                return new int[]{0, 1999};
            default:
                return new int[]{0, 9999};
        }
    }

    private static int year(String date) {
        try {
            return date != null && date.length() >= 4 ? Integer.parseInt(date.substring(0, 4)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** TVmaze: catalogue pages in a random order, the best-rated shows that fit, shuffled. */
    private static List<JSONObject> fromMaze(Context c) throws IOException, InterruptedException, JSONException {
        Set<String> want = genres(c);
        float min = rating(c);
        int[] yr = years(c);
        Random rnd = new Random();
        // the well-known shows sit in the first few dozen pages (ids are in order of adding)
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < 60; i++) pages.add(i);
        Collections.shuffle(pages, rnd);
        // a narrow taste reads more pages before it has enough
        int most = want.isEmpty() && yr[0] == 0 && yr[1] == 9999 ? 3 : 10;
        List<JSONObject> found = new ArrayList<>();
        for (int i = 0; i < most && found.size() < POOL * 2; i++) {
            JSONArray shows;
            try {
                shows = new JSONArray(new String(get(MAZE + pages.get(i), null), StandardCharsets.UTF_8));
            } catch (IOException e) {
                continue;
            }
            for (int k = 0; k < shows.length(); k++) {
                JSONObject s = shows.getJSONObject(k);
                JSONObject rating = s.optJSONObject("rating"), image = s.optJSONObject("image");
                if (rating == null || image == null || rating.optDouble("average", 0) < min) continue;
                int y = year(s.optString("premiered", null));
                if (y < 0 && (yr[0] > 0 || yr[1] < 9999) || y >= 0 && (y < yr[0] || y > yr[1])) continue;
                if (!want.isEmpty() && !mazeGenre(s, want)) continue;
                found.add(maze(s));
            }
            Thread.sleep(1500); // TVmaze asks for gentle request rates
        }
        Collections.shuffle(found, rnd);
        return found;
    }

    private static boolean mazeGenre(JSONObject s, Set<String> want) {
        Set<String> has = new HashSet<>();
        JSONArray g = s.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) has.add(g.optString(i));
        has.add(s.optString("type"));
        for (Genre x : GENRES) {
            if (!want.contains(x.id)) continue;
            for (String n : x.maze) if (has.contains(n)) return true;
        }
        return false;
    }

    private static JSONObject maze(JSONObject s) throws JSONException {
        JSONArray g = s.optJSONArray("genres");
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "★ %.1f", s.getJSONObject("rating").optDouble("average")));
        for (int i = 0; g != null && i < Math.min(2, g.length()); i++) line.append("  ·  ").append(g.optString(i));
        String premiered = s.optString("premiered", "");
        if (premiered.length() >= 4) line.append("  ·  ").append(premiered, 0, 4);
        JSONObject e = new JSONObject();
        e.put("id", "mz" + s.optInt("id"));
        e.put("name", s.optString("name"));
        e.put("line", line.toString());
        e.put("about", about(s.optString("summary", "")));
        // the full poster (about 680 px wide): "medium" is a 210 px thumbnail, a smear on a TV
        JSONObject image = s.getJSONObject("image");
        e.put("art", image.optString("original", image.optString("medium")));
        e.put("query", s.optString("name") + " trailer");
        e.put("why", "TOP RATED");
        e.put("src", "TVmaze");
        return e;
    }

    /** The first sentence or two, short enough for two lines on a card. */
    private static String about(String html) {
        String about = html.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        int dot = about.indexOf(". ");
        if (dot > 30) about = about.substring(0, dot + 1);
        if (about.length() > 140) about = about.substring(0, 137).trim() + "…";
        return about;
    }

    // ---- TMDB ----------------------------------------------------------------------------------

    /** TMDB: films, shows or both, from the source asked for, shuffled; each with its trailer when there is one. */
    private static List<JSONObject> fromTmdb(Context c) throws IOException, InterruptedException, JSONException {
        String source = source(c), kind = kind(c);
        String[] types = kind.equals("both") ? new String[]{"movie", "tv"} : kind.equals("films") ? new String[]{"movie"} : new String[]{"tv"};
        Random rnd = new Random();
        List<List<JSONObject>> each = new ArrayList<>();
        for (String type : types) {
            List<JSONObject> got = source.equals("watchlist") ? watchlist(c, type) : source.equals("trending") ? trending(c, type) : discover(c, type);
            Collections.shuffle(got, rnd);
            each.add(got);
        }
        // films and shows taken turn about, so both make the pool
        List<JSONObject> found = new ArrayList<>();
        for (int i = 0; found.size() < POOL; i++) {
            boolean any = false;
            for (List<JSONObject> l : each) {
                if (i < l.size()) {
                    found.add(l.get(i));
                    any = true;
                }
            }
            if (!any) break;
        }
        for (JSONObject p : found) {
            String yt = trailer(c, p.optString("type"), p.optString("tmdb"));
            if (yt != null) p.put("yt", yt);
        }
        return found;
    }

    /** Top rated, as TMDB's discover lists it: well-voted titles above your rating, in your genres, years and apps. */
    private static List<JSONObject> discover(Context c, String type) throws IOException, JSONException {
        boolean film = type.equals("movie");
        StringBuilder q = new StringBuilder(TMDB + "/discover/" + type + "?include_adult=false&sort_by=vote_average.desc");
        q.append("&vote_count.gte=").append(film ? 400 : 150);
        q.append("&vote_average.gte=").append(rating(c));
        q.append("&language=").append(language());
        String ids = genreIds(c, film);
        if (ids == null) return new ArrayList<>();
        if (!ids.isEmpty()) q.append("&with_genres=").append(enc(ids));
        int[] yr = years(c);
        String field = film ? "primary_release_date" : "first_air_date";
        if (yr[0] > 0) q.append('&').append(field).append(".gte=").append(yr[0]).append("-01-01");
        if (yr[1] < 9999) q.append('&').append(field).append(".lte=").append(yr[1]).append("-12-31");
        String providers = myApps(c) ? providers(c) : "";
        if (!providers.isEmpty()) {
            q.append("&with_watch_providers=").append(enc(providers)).append("&watch_region=").append(region())
                    .append("&with_watch_monetization_types=").append(enc("flatrate|free|ads"));
        }
        String why = providers.isEmpty() ? "TOP RATED" : "ON YOUR APPS";
        List<JSONObject> out = new ArrayList<>();
        JSONObject first = json(get(q + "&page=1", c));
        add(c, out, first, type, why, null, null);
        // a few more pages, picked at random, so the pool isn't the same top thirty every week
        int pages = Math.min(first.optInt("total_pages", 1), 15);
        List<Integer> more = new ArrayList<>();
        for (int p = 2; p <= pages; p++) more.add(p);
        Collections.shuffle(more);
        for (int i = 0; i < Math.min(2, more.size()); i++) add(c, out, json(get(q + "&page=" + more.get(i), c)), type, why, null, null);
        return out;
    }

    /** This week's most watched, in your genres and years (a rating would leave little that's new). */
    private static List<JSONObject> trending(Context c, String type) throws IOException, JSONException {
        boolean film = type.equals("movie");
        String ids = genreIds(c, film);
        if (ids == null) return new ArrayList<>();
        Set<Integer> want = new HashSet<>();
        for (String s : ids.split("\\|")) if (!s.isEmpty()) want.add(Integer.parseInt(s));
        List<JSONObject> out = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            add(c, out, json(get(TMDB + "/trending/" + type + "/week?language=" + language() + "&page=" + page, c)), type,
                    "TRENDING THIS WEEK", want, years(c));
        }
        return out;
    }

    /** Your own TMDB watchlist, as it is: what you said you'd watch needs no filtering. */
    private static List<JSONObject> watchlist(Context c, String type) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        String account = p.getString(ACCOUNT, ""), session = p.getString(SESSION, "");
        List<JSONObject> out = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            JSONObject r = json(get(TMDB + "/account/" + account + "/watchlist/" + (type.equals("movie") ? "movies" : "tv")
                    + "?session_id=" + enc(session) + "&language=" + language() + "&sort_by=created_at.desc&page=" + page, c));
            add(c, out, r, type, "ON YOUR WATCHLIST", null, null);
            if (page >= r.optInt("total_pages", 1)) break;
        }
        return out;
    }

    /** TMDB's results, each as a pick; with {@code want}/{@code yr}, only those in those genres and years. */
    private static void add(Context c, List<JSONObject> out, JSONObject page, String type, String why, Set<Integer> want, int[] yr) throws JSONException {
        JSONArray rs = page.optJSONArray("results");
        boolean film = type.equals("movie");
        for (int i = 0; rs != null && i < rs.length(); i++) {
            JSONObject r = rs.getJSONObject(i);
            String poster = r.optString("poster_path", "");
            if (poster.isEmpty() || poster.equals("null") || r.optBoolean("adult")) continue;
            int y = year(r.optString(film ? "release_date" : "first_air_date", null));
            if (yr != null && (y >= 0 ? y < yr[0] || y > yr[1] : yr[0] > 0 || yr[1] < 9999)) continue;
            JSONArray g = r.optJSONArray("genre_ids");
            if (want != null && !want.isEmpty()) {
                boolean hit = false;
                for (int k = 0; g != null && k < g.length(); k++) if (want.contains(g.optInt(k))) hit = true;
                if (!hit) continue;
            }
            String name = r.optString(film ? "title" : "name");
            StringBuilder line = new StringBuilder();
            line.append(c.getString(film ? R.string.pick_film : R.string.pick_series));
            double vote = r.optDouble("vote_average", 0);
            if (vote > 0) line.append(String.format(Locale.ROOT, "  ·  ★ %.1f", vote));
            int shown = 0;
            for (int k = 0; g != null && k < g.length() && shown < 2; k++) {
                String gn = genreName(c, g.optInt(k), film);
                if (gn != null) {
                    line.append("  ·  ").append(gn);
                    shown++;
                }
            }
            if (y > 0) line.append("  ·  ").append(y);
            JSONObject e = new JSONObject();
            e.put("id", (film ? "m" : "tv") + r.optInt("id"));
            e.put("tmdb", String.valueOf(r.optInt("id")));
            e.put("type", type);
            e.put("name", name);
            e.put("line", line.toString());
            e.put("about", about(r.optString("overview", "")));
            e.put("art", POSTER + poster);
            e.put("query", name + (y > 0 ? " " + y : "") + " trailer");
            e.put("why", why);
            e.put("src", "TMDB");
            out.add(e);
        }
    }

    /** The YouTube id of its trailer (an official one if there is one), or null. */
    private static String trailer(Context c, String type, String id) {
        if (id.isEmpty()) return null;
        try {
            JSONArray vs = json(get(TMDB + "/" + type + "/" + id + "/videos", c)).optJSONArray("results");
            String any = null;
            for (int i = 0; vs != null && i < vs.length(); i++) {
                JSONObject v = vs.getJSONObject(i);
                if (!"YouTube".equals(v.optString("site")) || !"Trailer".equals(v.optString("type"))) continue;
                if (v.optBoolean("official")) return v.optString("key");
                if (any == null) any = v.optString("key");
            }
            return any;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * The chosen genres as TMDB ids joined with "|" (any of them), "" for any genre, or null when
     * none of them exists for this kind (Horror has no series genre on TMDB): nothing to find.
     */
    private static String genreIds(Context c, boolean film) {
        Set<String> want = genres(c);
        if (want.isEmpty()) return "";
        Set<String> ids = new LinkedHashSet<>();
        for (Genre x : GENRES) {
            int id = film ? x.film : x.show;
            if (want.contains(x.id) && id != 0) ids.add(String.valueOf(id));
        }
        return ids.isEmpty() ? null : android.text.TextUtils.join("|", ids);
    }

    private static String genreName(Context c, int id, boolean film) {
        for (Genre x : GENRES) if ((film ? x.film : x.show) == id) return c.getString(x.name);
        return null;
    }

    /** The watch providers of the streaming apps installed here, joined with "|". */
    private static String providers(Context c) {
        Set<String> ids = new LinkedHashSet<>();
        PackageManager pm = c.getPackageManager();
        for (String[] p : PROVIDERS) {
            try {
                pm.getApplicationInfo(p[0], 0);
                ids.addAll(Arrays.asList(p[1].split("\\|")));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return android.text.TextUtils.join("|", ids);
    }

    private static String region() {
        String r = Locale.getDefault().getCountry();
        return r == null || r.length() != 2 ? "US" : r.toUpperCase(Locale.ROOT);
    }

    private static String language() {
        Locale l = Locale.getDefault();
        return l.getLanguage() + (l.getCountry().isEmpty() ? "" : "-" + l.getCountry());
    }

    // ---- your TMDB key and account -------------------------------------------------------------

    /** Whether TMDB accepts {@code key} (a v3 API key, or a v4 read access token). Off the main thread. */
    static boolean check(String key) {
        try {
            HttpURLConnection con = open(TMDB + "/configuration", key);
            try {
                return con.getResponseCode() == 200;
            } finally {
                con.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    static void setKey(Context c, String key) {
        prefs(c).edit().putString(KEY, key.trim()).remove(SESSION).remove(ACCOUNT).remove(USER).apply();
    }

    /** Step one of signing in: a token you approve on TMDB's own site (on your phone, from a QR code). */
    static String requestToken(Context c) throws IOException, JSONException {
        return json(get(TMDB + "/authentication/token/new", c)).getString("request_token");
    }

    static String approveUrl(String token) {
        return "https://www.themoviedb.org/authenticate/" + token;
    }

    /**
     * Step two, asked every few seconds: once the token is approved, a session and your account,
     * kept on the TV. False while it's still waiting.
     */
    static boolean finishSignIn(Context c, String token) throws JSONException {
        try {
            JSONObject s = json(post(TMDB + "/authentication/session/new", c, new JSONObject().put("request_token", token).toString()));
            String session = s.optString("session_id", "");
            if (session.isEmpty()) return false;
            JSONObject a = json(get(TMDB + "/account?session_id=" + enc(session), c));
            prefs(c).edit().putString(SESSION, session).putString(ACCOUNT, String.valueOf(a.optInt("id")))
                    .putString(USER, a.optString("username")).putString(SOURCE, "watchlist").apply();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void signOut(Context c) {
        SharedPreferences p = prefs(c);
        if (source(c).equals("watchlist")) p.edit().putString(SOURCE, "top").apply();
        p.edit().remove(SESSION).remove(ACCOUNT).remove(USER).apply();
    }

    // ---- network -------------------------------------------------------------------------------

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static JSONObject json(byte[] b) throws JSONException {
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    /** A TMDB call carries your key: as a bearer token (v4 read token) or the api_key parameter (v3 key). */
    private static HttpURLConnection open(String url, String key) throws IOException {
        boolean bearer = key != null && key.startsWith("eyJ");
        String u = key == null || bearer ? url : url + (url.contains("?") ? "&" : "?") + "api_key=" + enc(key);
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("Accept", "application/json");
        if (bearer) con.setRequestProperty("Authorization", "Bearer " + key);
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        return con;
    }

    /** GET; with {@code c}, a TMDB call signed with your key. */
    private static byte[] get(String url, Context c) throws IOException {
        HttpURLConnection con = open(url, c == null ? null : key(c));
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                return readAll(in);
            }
        } finally {
            con.disconnect();
        }
    }

    private static byte[] post(String url, Context c, String body) throws IOException {
        HttpURLConnection con = open(url, key(c));
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            try (OutputStream o = con.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
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
