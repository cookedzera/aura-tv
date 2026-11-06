package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Category rows: home as labelled rows (Favorites, Movies &amp; TV, Music, Games, News, All apps)
 * instead of one grid. The smart rows fill themselves from what each app tells Android it is
 * (its app category, Android 8 and later) and, where an app says nothing, from a short list of
 * well-known TV apps; anything can be added to or taken out of any row by hand. A row with nothing
 * in it isn't shown. Kept per profile, like folders.
 */
final class Categories {
    /** Home shows category rows (on by default; Settings, Home screen switches back to the grid). */
    static final String ROWS = "set_rows", DIM = "set_rows_dim";
    /** Asked once, in the welcome or (updating from 7.7) on home. */
    static final String ASKED = "rows_asked";
    private static final String KEY = "categories";
    static final String FAV = "fav", ALL = "all";
    static final String[] SORTS = {"custom", "az", "most", "recent"};

    static final class Cat {
        final String id;
        /** Its own name, or null for the built-in one in the TV's language. */
        String name;
        /** What fills it by itself: video, music, games, news; null for a row filled by hand. */
        final String auto;
        final List<String> add = new ArrayList<>();
        final Set<String> remove = new HashSet<>();
        String sort = "custom";
        boolean hidden;

        Cat(String id, String name, String auto) {
            this.id = id;
            this.name = name;
            this.auto = auto;
        }
    }

    private Categories() { }

    static boolean on(Context c) {
        return Prefs.of(c).getBoolean(ROWS, true);
    }

    static boolean dim(Context c) {
        return Prefs.of(c).getBoolean(DIM, true);
    }

    /** The rows in home's order; made the first time with the smart set. */
    static List<Cat> all(Context c) {
        String saved = Prefs.of(c).getString(KEY, null);
        if (saved != null) {
            try {
                return parse(new JSONArray(saved));
            } catch (JSONException ignored) {
                // unreadable: the smart set again, rather than no rows at all
            }
        }
        return defaults();
    }

    static List<Cat> defaults() {
        List<Cat> out = new ArrayList<>();
        out.add(new Cat(FAV, null, null));
        out.add(new Cat("video", null, "video"));
        out.add(new Cat("music", null, "music"));
        out.add(new Cat("games", null, "games"));
        out.add(new Cat("news", null, "news"));
        out.add(new Cat(ALL, null, null));
        return out;
    }

    static void save(Context c, List<Cat> cats) {
        JSONArray a = new JSONArray();
        try {
            for (Cat k : cats) {
                JSONObject o = new JSONObject().put("id", k.id).put("sort", k.sort).put("hidden", k.hidden);
                if (k.name != null) o.put("name", k.name);
                if (k.auto != null) o.put("auto", k.auto);
                o.put("add", new JSONArray(k.add));
                o.put("remove", new JSONArray(new ArrayList<>(k.remove)));
                a.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        Prefs.of(c).edit().putString(KEY, a.toString()).apply();
    }

    static List<Cat> parse(JSONArray a) throws JSONException {
        List<Cat> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Cat k = new Cat(o.getString("id"), o.has("name") ? o.getString("name") : null, o.has("auto") ? o.getString("auto") : null);
            k.sort = java.util.Arrays.asList(SORTS).contains(o.optString("sort")) ? o.optString("sort") : "custom";
            k.hidden = o.optBoolean("hidden", false);
            JSONArray add = o.optJSONArray("add"), rm = o.optJSONArray("remove");
            for (int j = 0; add != null && j < add.length(); j++) k.add.add(add.getString(j));
            for (int j = 0; rm != null && j < rm.length(); j++) k.remove.add(rm.getString(j));
            out.add(k);
        }
        // All apps is always there, last unless moved
        boolean all = false;
        for (Cat k : out) all |= k.id.equals(ALL);
        if (!all) out.add(new Cat(ALL, null, null));
        return out;
    }

    static Cat find(List<Cat> cats, String id) {
        for (Cat k : cats) if (k.id.equals(id)) return k;
        return null;
    }

    /** Its name as shown: your own, or the built-in one. */
    static String name(Context c, Cat k) {
        if (k.name != null && !k.name.isEmpty()) return k.name;
        switch (k.id) {
            case FAV: return c.getString(R.string.cat_favorites);
            case "video": return c.getString(R.string.cat_video);
            case "music": return c.getString(R.string.cat_music);
            case "games": return c.getString(R.string.cat_games);
            case "news": return c.getString(R.string.cat_news);
            case ALL: return c.getString(R.string.cat_all_apps);
            default: return c.getString(R.string.cat_new);
        }
    }

    /**
     * The apps in a row, in its order: those it fills by itself, those you added, none you took
     * out; All apps holds every app in your own order. {@code order} is home's saved order, {@code
     * installed} the apps on the TV (hidden ones already left out).
     */
    static List<String> members(Context c, Cat k, List<String> order, List<String> installed) {
        Set<String> there = new HashSet<>(installed);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (k.id.equals(ALL)) {
            for (String p : order) if (there.contains(p)) out.add(p);
            out.addAll(installed);
        } else {
            for (String p : k.add) if (there.contains(p)) out.add(p);
            if (k.auto != null) {
                // in your own order, as in All apps
                for (String p : order) if (there.contains(p) && k.auto.equals(kind(c, p))) out.add(p);
                for (String p : installed) if (k.auto.equals(kind(c, p))) out.add(p);
            }
            out.removeAll(k.remove);
        }
        List<String> list = new ArrayList<>(out);
        sort(c, k.sort, list);
        return list;
    }

    static void sort(Context c, String how, List<String> list) {
        if (how.equals("az")) {
            final PackageManager pm = c.getPackageManager();
            final Map<String, String> names = new HashMap<>();
            for (String p : list) names.put(p, label(pm, p));
            Collections.sort(list, (a, b) -> names.get(a).compareToIgnoreCase(names.get(b)));
        } else if (how.equals("most")) {
            final Map<String, Integer> n = counts(c);
            Collections.sort(list, (a, b) -> Integer.compare(n.containsKey(b) ? n.get(b) : 0, n.containsKey(a) ? n.get(a) : 0));
        } else if (how.equals("recent")) {
            final Map<String, Long> at = Habits.lastOpened(c);
            Collections.sort(list, (a, b) -> Long.compare(at.containsKey(b) ? at.get(b) : 0L, at.containsKey(a) ? at.get(a) : 0L));
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---- what an app is ------------------------------------------------------------------------

    /** TV apps that don't say what they are, by what they are. */
    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        for (String p : new String[]{"com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.amazon.avod",
                "com.disney.disneyplus", "com.google.android.youtube.tv", "com.wbd.stream", "com.hbo.hbonow",
                "com.apple.atve.androidtv.appletv", "in.startv.hotstar", "com.jio.media.ondemand", "com.sonyliv",
                "com.graymatrix.did", "com.plexapp.android", "org.xbmc.kodi", "org.videolan.vlc", "com.mxtech.videoplayer.ad",
                "com.mxtech.videoplayer.pro", "com.google.android.videos", "io.gh.reisxd.tizentube.cobalt", "app.netmirror.newtv",
                "com.peacocktv.peacockandroid", "com.cbs.ott", "com.hulu.livingroomplus", "tv.pluto.android", "com.tubitv",
                "com.zee5.androidtv", "com.mubi", "com.crunchyroll.crunchyroid", "com.google.android.youtube.tvkids"}) {
            KNOWN.put(p, "video");
        }
        for (String p : new String[]{"com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.amazon.music.tv",
                "com.apple.android.music", "com.jio.media.jiobeats", "com.gaana", "deezer.android.tv", "com.aspiro.tidal",
                "com.soundcloud.android", "com.pandora.android.atv", "tunein.player", "com.wynk.music"}) {
            KNOWN.put(p, "music");
        }
        for (String p : new String[]{"com.nvidia.geforcenow", "com.valvesoftware.steamlink", "com.gamepass",
                "com.microsoft.xcloud", "com.netflix.NGP.games", "com.nvidia.tegrazone3"}) {
            KNOWN.put(p, "games");
        }
    }

    private static final Map<String, String> kinds = new HashMap<>();

    /** video, music, games, news, or "" when the app says nothing and isn't a known one. */
    static synchronized String kind(Context c, String pkg) {
        String k = kinds.get(pkg);
        if (k != null) return k;
        k = KNOWN.containsKey(pkg) ? KNOWN.get(pkg) : "";
        if (k.isEmpty()) {
            try {
                ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
                if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) k = "games";
                else if (Build.VERSION.SDK_INT >= 26) {
                    switch (ai.category) {
                        case ApplicationInfo.CATEGORY_GAME: k = "games"; break;
                        case ApplicationInfo.CATEGORY_VIDEO: k = "video"; break;
                        case ApplicationInfo.CATEGORY_AUDIO: k = "music"; break;
                        case ApplicationInfo.CATEGORY_NEWS: k = "news"; break;
                        default: break;
                    }
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        kinds.put(pkg, k);
        return k;
    }

    /** An app was installed, updated or removed: what it is gets read again. */
    static synchronized void forget(String pkg) {
        kinds.remove(pkg);
    }

    // ---- by hand ------------------------------------------------------------------------------

    static void addTo(Context c, String id, String pkg) {
        List<Cat> cats = all(c);
        Cat k = find(cats, id);
        if (k == null) return;
        k.remove.remove(pkg);
        if (!k.add.contains(pkg)) k.add.add(pkg);
        save(c, cats);
    }

    static void removeFrom(Context c, String id, String pkg) {
        List<Cat> cats = all(c);
        Cat k = find(cats, id);
        if (k == null || k.id.equals(ALL)) return;
        k.add.remove(pkg);
        k.remove.add(pkg);
        save(c, cats);
    }

    /** A row of your own, named from the folder names or typed; its id is returned. */
    static String create(Context c, String name) {
        List<Cat> cats = all(c);
        String id = "c" + Long.toString(System.currentTimeMillis(), 36);
        Cat k = new Cat(id, name, null);
        // new rows go just above All apps
        int at = cats.size();
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).id.equals(ALL)) at = i;
        cats.add(at, k);
        save(c, cats);
        return id;
    }

    /** A change to one row, saved; nothing happens for a row that's gone. */
    interface Edit {
        void on(List<Cat> cats, Cat k);
    }

    static void edit(Context c, String id, Edit e) {
        List<Cat> cats = all(c);
        Cat k = find(cats, id);
        if (k == null) return;
        e.on(cats, k);
        save(c, cats);
    }

    static void rename(Context c, String id, String name) {
        edit(c, id, (cats, k) -> k.name = name);
    }

    static void setHidden(Context c, String id, boolean hidden) {
        edit(c, id, (cats, k) -> k.hidden = hidden);
    }

    /** OK steps through the orders: yours, A to Z, most used, recently used. */
    static void nextSort(Context c, String id) {
        edit(c, id, (cats, k) -> k.sort = SORTS[(java.util.Arrays.asList(SORTS).indexOf(k.sort) + 1) % SORTS.length]);
    }

    /** One place up (-1) or down (1) on home. */
    static void move(Context c, String id, int d) {
        edit(c, id, (cats, k) -> {
            int i = cats.indexOf(k), j = i + d;
            if (j >= 0 && j < cats.size()) cats.add(j, cats.remove(i));
        });
    }

    /** A row of your own deleted; the built-in ones can only be hidden. */
    static void delete(Context c, String id) {
        edit(c, id, (cats, k) -> {
            if (!builtIn(k)) cats.remove(k);
        });
    }

    static boolean builtIn(Cat k) {
        return k.id.equals(FAV) || k.id.equals(ALL) || k.auto != null;
    }

    static int sortName(String how) {
        switch (how) {
            case "az": return R.string.sort_az;
            case "most": return R.string.sort_most;
            case "recent": return R.string.sort_recent;
            default: return R.string.sort_custom;
        }
    }

    /** Your own order inside a row (sorted by hand): the row's apps as now laid out. */
    static void reorder(Context c, String id, List<String> pkgs) {
        List<Cat> cats = all(c);
        Cat k = find(cats, id);
        if (k == null || k.id.equals(ALL)) return;
        k.sort = "custom";
        k.add.clear();
        k.add.addAll(pkgs);
        save(c, cats);
    }

    // ---- launches, for Most used ---------------------------------------------------------------

    private static SharedPreferences usage(Context c) {
        return c.getSharedPreferences("aura_launches", Context.MODE_PRIVATE);
    }

    static void launched(Context c, String pkg) {
        if (pkg == null) return;
        SharedPreferences p = usage(c);
        p.edit().putInt(pkg, p.getInt(pkg, 0) + 1).apply();
    }

    static Map<String, Integer> counts(Context c) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> e : usage(c).getAll().entrySet()) {
            if (e.getValue() instanceof Integer) out.put(e.getKey(), (Integer) e.getValue());
        }
        return out;
    }
}
