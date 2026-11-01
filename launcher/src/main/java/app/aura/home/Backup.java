package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Your setup in one small file, saved to your phone and brought back after a reinstall or onto
 * another TV: profiles, app order, folders, hidden apps, tiles, widgets, remote buttons, the
 * screensaver's scene, Tonight's pick and the weather's city. Android's own backup isn't used (it
 * would carry this TV's debugging key too), and photos stay on the phone they came from.
 *
 * Kept as {"aura": 1, "files": {name: {key: [type, value]}}}, each setting with its type.
 */
final class Backup {
    private static final int VERSION = 1;
    /** This TV's own state, never carried to another: its debugging link, crash history, caches and sign-ins. */
    private static final Set<String> LOCAL = new HashSet<>(Arrays.asList(
            "adb_ok", "adb_open", "keys_lag", "keys_healing", "crashes", "safe_at", "setup_note", "welcome_resume",
            "update_ver", "update_seen", "update_told", "listings_asked", "tmdb_session", "tmdb_account", "tmdb_user",
            "pick_made_for", "pick_count", "pick_at", "geo_at", "wx_at", "wx", "saver_prev", "sleepAt", "sleepVol", "sleepSet",
            "sleepEnd", "known_pkgs", "new_pkgs", "welcomed", "keys_wanted", "adb_paired", "adb_tls_port", "set_light", "parent_pin"));

    private Backup() { }

    /** Every settings file Aura keeps: the shared one (also the first profile's), the profile list, each other profile. */
    private static List<String> files(Context c) {
        List<String> out = new java.util.ArrayList<>(Arrays.asList("aura", "aura_profiles"));
        for (Profiles.Profile p : Profiles.all(c)) if (!Profiles.MAIN.equals(p.id)) out.add("aura_" + p.id);
        return out;
    }

    static String export(Context c) {
        JSONObject files = new JSONObject();
        try {
            for (String name : files(c)) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : c.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                    if (LOCAL.contains(e.getKey())) continue;
                    Object v = e.getValue();
                    String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                            : v instanceof Float ? "f" : v instanceof Set ? "S" : "s";
                    o.put(e.getKey(), new JSONArray().put(t).put(v instanceof Set ? new JSONArray((Set<?>) v) : v));
                }
                files.put(name, o);
            }
            return new JSONObject().put("aura", VERSION).put("made", System.currentTimeMillis()).put("files", files).toString(1);
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * Brings a saved setup back; the number of settings restored. Profiles are replaced whole; the
     * shared file keeps this TV's own state and takes everything else from the copy.
     */
    static int restore(Context c, String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("aura", 0) < 1) throw new JSONException("not an Aura backup");
        JSONObject files = root.getJSONObject("files");
        // every entry read first: a file broken halfway must not leave a profile already emptied
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            JSONObject o = files.getJSONObject(it.next());
            for (Iterator<String> ks = o.keys(); ks.hasNext(); ) {
                JSONArray tv = o.getJSONArray(ks.next());
                if (tv.length() != 2) throw new JSONException("not a setting");
                tv.getString(0);
                if (tv.getString(0).equals("S")) tv.getJSONArray(1);
            }
        }
        int n = 0;
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            String name = it.next();
            if (!name.equals("aura") && !name.startsWith("aura_") || name.equals("aura_usage")) continue;
            JSONObject o = files.getJSONObject(name);
            SharedPreferences.Editor ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
            if (!name.equals("aura")) ed.clear();
            for (Iterator<String> ks = o.keys(); ks.hasNext(); ) {
                String k = ks.next();
                if (LOCAL.contains(k)) continue;
                JSONArray tv = o.getJSONArray(k);
                switch (tv.getString(0)) {
                    case "b":
                        ed.putBoolean(k, tv.getBoolean(1));
                        break;
                    case "i":
                        ed.putInt(k, tv.getInt(1));
                        break;
                    case "l":
                        ed.putLong(k, tv.getLong(1));
                        break;
                    case "f":
                        ed.putFloat(k, (float) tv.getDouble(1));
                        break;
                    case "S":
                        Set<String> set = new HashSet<>();
                        JSONArray a = tv.getJSONArray(1);
                        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                        ed.putStringSet(k, set);
                        break;
                    default:
                        ed.putString(k, tv.getString(1));
                }
                n++;
            }
            ed.commit();
        }
        // the profile in use and the remote buttons are read once and kept: read afresh
        Profiles.forget();
        HomeKeys.forgetKeys();
        return n;
    }
}
