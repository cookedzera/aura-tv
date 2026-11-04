package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Profiles, as on Apple TV: each person keeps their own app order, hidden apps, folders, tile
 * style, widgets, wallpaper and switches. The first profile uses Aura's original settings, so
 * nothing changes for anyone who never adds one. TV-wide things (Block ads, weather location,
 * updates) are shared by everyone.
 *
 * A profile can be for kids: Settings, the TV's own settings and an app's Hide and Uninstall then
 * ask for the parents' PIN, and hidden apps can't be found by typing either.
 */
final class Profiles {
    static final class Profile {
        final String id, name;
        final int color;
        boolean kids;

        Profile(String id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    static final String MAIN = "main";
    static final int MAX = 5;
    /** Each new profile takes the next of these colours for its badge. */
    private static final int[] COLORS = {0xFF6E8BFF, 0xFFFF8A65, 0xFF4CD9A0, 0xFFE57BD8, 0xFFFFC857};
    private static volatile String current;
    private static volatile List<Profile> cachedAll;

    private Profiles() { }

    private static SharedPreferences store(Context c) {
        return c.getSharedPreferences("aura_profiles", Context.MODE_PRIVATE);
    }

    /** Read afresh next time (a backup was just restored). */
    static void forget() {
        current = null;
        cachedAll = null;
    }

    static String current(Context c) {
        String id = current;
        if (id == null) id = current = store(c).getString("current", MAIN);
        return id;
    }

    /** The settings file of the profile in use: the main one keeps Aura's original file. */
    static String file(Context c) {
        String id = current(c);
        return MAIN.equals(id) ? "aura" : "aura_" + id;
    }

    static List<Profile> all(Context c) {
        List<Profile> known = cachedAll;
        if (known != null) {
            List<Profile> copy = new ArrayList<>(known.size());
            for (Profile p : known) {
                Profile cp = new Profile(p.id, p.name, p.color);
                cp.kids = p.kids;
                copy.add(cp);
            }
            return copy;
        }
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(store(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Profile p = new Profile(o.getString("id"), o.getString("name"), o.optInt("color", COLORS[0]));
                p.kids = o.optBoolean("kids", false);
                out.add(p);
            }
        } catch (JSONException ignored) {
        }
        if (out.isEmpty()) out.add(new Profile(MAIN, c.getString(R.string.me), COLORS[0]));
        List<Profile> cache = new ArrayList<>(out.size());
        for (Profile p : out) {
            Profile cp = new Profile(p.id, p.name, p.color);
            cp.kids = p.kids;
            cache.add(cp);
        }
        cachedAll = java.util.Collections.unmodifiableList(cache);
        return out;
    }

    static String name(Context c) {
        List<Profile> list = cachedAll != null ? cachedAll : all(c);
        String cur = current(c);
        for (Profile p : list) if (p.id.equals(cur)) return p.name;
        return c.getString(R.string.me);
    }

    private static void save(Context c, List<Profile> list) {
        JSONArray a = new JSONArray();
        List<Profile> cache = new ArrayList<>(list.size());
        try {
            for (Profile p : list) {
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("kids", p.kids));
                Profile cp = new Profile(p.id, p.name, p.color);
                cp.kids = p.kids;
                cache.add(cp);
            }
        } catch (JSONException ignored) {
        }
        cachedAll = java.util.Collections.unmodifiableList(cache);
        store(c).edit().putString("list", a.toString()).apply();
    }

    /** A new profile named {@code name}; returns it, or null when there are already {@link #MAX}. */
    static Profile add(Context c, String name) {
        List<Profile> list = all(c);
        if (list.size() >= MAX) return null;
        Profile p = new Profile(Long.toString(System.currentTimeMillis(), 36), name, COLORS[list.size() % COLORS.length]);
        list.add(p);
        save(c, list);
        return p;
    }

    static void use(Context c, String id) {
        current = id;
        store(c).edit().putString("current", id).apply();
    }

    /** The profile in use is a kids' one: grown-up things ask for the PIN. */
    static boolean kids(Context c) {
        List<Profile> list = cachedAll != null ? cachedAll : all(c);
        String cur = current(c);
        for (Profile p : list) if (p.id.equals(cur)) return p.kids;
        return false;
    }

    static void setKids(Context c, String id, boolean on) {
        List<Profile> list = all(c);
        for (Profile p : list) if (p.id.equals(id)) p.kids = on;
        save(c, list);
    }

    // ---- the parents' PIN, kept only as a hash ---------------------------------------------------

    static boolean hasPin(Context c) {
        return !c.getSharedPreferences("aura", Context.MODE_PRIVATE).getString("parent_pin", "").isEmpty();
    }

    static void setPin(Context c, String pin) {
        c.getSharedPreferences("aura", Context.MODE_PRIVATE).edit().putString("parent_pin", hash(pin)).apply();
    }

    static boolean pinOk(Context c, String pin) {
        return hash(pin).equals(c.getSharedPreferences("aura", Context.MODE_PRIVATE).getString("parent_pin", "-"));
    }

    private static String hash(String pin) {
        StringBuilder s = new StringBuilder();
        for (byte b : Spake2.sha(("aura-pin:" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8), "SHA-256")) s.append(String.format("%02x", b));
        return s.toString();
    }

    /** Removes a profile and its settings; the main one stays. */
    static void remove(Context c, String id) {
        if (MAIN.equals(id)) return;
        List<Profile> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list);
        c.getSharedPreferences("aura_" + id, Context.MODE_PRIVATE).edit().clear().apply();
        if (id.equals(current(c))) use(c, MAIN);
    }
}
