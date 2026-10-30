package app.aura.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App folders, as on Apple TV: a few apps gathered into one tile. Kept in Aura's settings as
 * {id: {name, apps: [package…]}}; the grid holds a folder by its tag "folder:id".
 */
final class Folders {
    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String tag() {
            return "folder:" + id;
        }
    }

    /** Names offered for a new folder, so making one needs no keyboard. */
    static final String[] NAMES = {"Movies & Shows", "Music", "Kids", "Sports", "News", "Games", "Tools", "Favourites"};
    private static final String KEY = "folders";

    private Folders() { }

    static Map<String, Folder> all(Context c) {
        Map<String, Folder> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(Prefs.of(c).getString(KEY, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject f = o.getJSONObject(id);
                Folder folder = new Folder(id, f.optString("name", "Folder"));
                JSONArray apps = f.optJSONArray("apps");
                for (int i = 0; apps != null && i < apps.length(); i++) folder.apps.add(apps.getString(i));
                out.put(id, folder);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void save(Context c, Map<String, Folder> all) {
        JSONObject o = new JSONObject();
        try {
            for (Folder f : all.values()) {
                if (f.apps.isEmpty()) continue; // an empty folder simply goes away
                o.put(f.id, new JSONObject().put("name", f.name).put("apps", new JSONArray(f.apps)));
            }
        } catch (JSONException ignored) {
        }
        Prefs.of(c).edit().putString(KEY, o.toString()).apply();
    }

    /** The folder holding {@code pkg}, or null. */
    static Folder of(Context c, String pkg) {
        for (Folder f : all(c).values()) if (f.apps.contains(pkg)) return f;
        return null;
    }

    /** A new folder with the first unused name, holding {@code pkg}; returns it. */
    static Folder create(Context c, String pkg) {
        Map<String, Folder> all = all(c);
        String name = NAMES[0];
        for (String n : NAMES) {
            boolean used = false;
            for (Folder f : all.values()) if (f.name.equals(n)) used = true;
            if (!used) {
                name = n;
                break;
            }
        }
        Folder f = new Folder(Long.toString(System.currentTimeMillis(), 36), name);
        removeEverywhere(all, pkg);
        f.apps.add(pkg);
        all.put(f.id, f);
        save(c, all);
        return f;
    }

    static void add(Context c, String id, String pkg) {
        Map<String, Folder> all = all(c);
        Folder f = all.get(id);
        if (f == null) return;
        removeEverywhere(all, pkg);
        f.apps.add(pkg);
        save(c, all);
    }

    static void remove(Context c, String pkg) {
        Map<String, Folder> all = all(c);
        removeEverywhere(all, pkg);
        save(c, all);
    }

    /** The next of the preset names, so OK on Rename steps through them. */
    static void rename(Context c, String id) {
        Map<String, Folder> all = all(c);
        Folder f = all.get(id);
        if (f == null) return;
        int at = java.util.Arrays.asList(NAMES).indexOf(f.name);
        f.name = NAMES[(at + 1) % NAMES.length];
        save(c, all);
    }

    /** Every app back on the grid. */
    static void dissolve(Context c, String id) {
        Map<String, Folder> all = all(c);
        all.remove(id);
        save(c, all);
    }

    private static void removeEverywhere(Map<String, Folder> all, String pkg) {
        for (Folder f : all.values()) f.apps.remove(pkg);
    }
}
