package app.aura.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * What the Smart Stack has learned from you, on this TV and for each profile: the titles you opened
 * from it, the cards you said you're not interested in, and the ones it kept showing that you
 * passed every time. Cards you open rise; cards you pass over again and again rest for a couple of
 * weeks; cards you turn down don't come back (Settings → Widgets brings them back). Titles are kept
 * as {@link Continue#key}s; apps as "app:" and their package. Nothing leaves the TV.
 */
final class Taste {
    private static final String KEY = "stack_taste";
    /** Visits to home with a card on show, never opened, before it rests. */
    private static final int TIRED_AFTER = 14;
    private static final long REST_MS = 14L * 86_400_000L, OPENED_KEEP = 120, SEEN_KEEP = 300;

    /** Bumped with every change that alters which cards are dealt: what's made from the old answers is made again. */
    static volatile int changes;

    private Taste() { }

    private static final class State {
        /** key → when opened, most recent last. */
        final JSONObject opened;
        /** key → [visits shown, first shown at]. */
        JSONObject seen;
        final Set<String> declined;

        State(JSONObject o) {
            JSONObject a = o.optJSONObject("opened"), b = o.optJSONObject("seen");
            opened = a != null ? a : new JSONObject();
            seen = b != null ? b : new JSONObject();
            declined = new HashSet<>();
            JSONArray d = o.optJSONArray("declined");
            for (int i = 0; d != null && i < d.length(); i++) declined.add(d.optString(i));
        }

        JSONObject json() throws JSONException {
            JSONArray d = new JSONArray();
            for (String k : declined) d.put(k);
            return new JSONObject().put("opened", opened).put("seen", seen).put("declined", d);
        }
    }

    private static State read(Context c) {
        try {
            return new State(new JSONObject(Prefs.of(c).getString(KEY, "{}")));
        } catch (JSONException e) {
            return new State(new JSONObject());
        }
    }

    private static void write(Context c, State s) {
        try {
            trim(s.opened, OPENED_KEEP);
            trim(s.seen, SEEN_KEEP);
            Prefs.of(c).edit().putString(KEY, s.json().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** The oldest entries go once there are more than {@code max} (JSONObject keeps insertion order on Android). */
    private static void trim(JSONObject o, long max) {
        int over = o.length() - (int) max;
        Iterator<String> it = o.keys();
        List<String> drop = new ArrayList<>();
        while (over-- > 0 && it.hasNext()) drop.add(it.next());
        for (String k : drop) o.remove(k);
    }

    static String app(String pkg) {
        return "app:" + pkg;
    }

    // ---- learning ----------------------------------------------------------------------------

    /** Opened from the stack: it counts as one of yours, and its tiredness goes. */
    static synchronized void opened(Context c, String key) {
        if (key == null || key.isEmpty()) return;
        State s = read(c);
        try {
            s.opened.remove(key);
            s.opened.put(key, System.currentTimeMillis());
            s.seen.remove(key);
        } catch (JSONException ignored) {
        }
        write(c, s);
    }

    /** These cards were on show during one visit to home: each counts once per visit. */
    static synchronized void shown(Context c, Set<String> keys) {
        if (keys.isEmpty()) return;
        State s = read(c);
        long now = System.currentTimeMillis();
        try {
            for (String k : keys) {
                JSONArray v = s.seen.optJSONArray(k);
                // a rest that's over starts the count again
                if (v == null || now - v.optLong(1, now) > REST_MS) v = new JSONArray().put(0).put(now);
                v.put(0, v.optInt(0) + 1);
                s.seen.remove(k);
                s.seen.put(k, v);
            }
        } catch (JSONException ignored) {
        }
        write(c, s);
    }

    /** "Not interested": this card won't be dealt again. */
    static synchronized void decline(Context c, String key) {
        if (key == null || key.isEmpty()) return;
        State s = read(c);
        s.declined.add(key);
        write(c, s);
        changes++;
    }

    /** Everything turned down comes back (Settings → Widgets). */
    static synchronized void forgive(Context c) {
        State s = read(c);
        s.declined.clear();
        s.seen = new JSONObject();
        write(c, s);
        changes++;
    }

    static synchronized int declinedCount(Context c) {
        return read(c).declined.size();
    }

    // ---- asking --------------------------------------------------------------------------------

    /** A snapshot for one round of choosing cards: read once, asked many times. */
    static final class View {
        private final State s;
        private final long now = System.currentTimeMillis();

        private View(State s) {
            this.s = s;
        }

        boolean declined(String key) {
            return key != null && s.declined.contains(key);
        }

        /** Shown on many visits, never opened, and still within its rest. */
        boolean tired(String key) {
            JSONArray v = key != null ? s.seen.optJSONArray(key) : null;
            return v != null && v.optInt(0) >= TIRED_AFTER && now - v.optLong(1, now) <= REST_MS;
        }

        /** Opened from the stack before: a title you watch. */
        boolean yours(String key) {
            return key != null && !key.isEmpty() && s.opened.has(key);
        }

        /** Left out: turned down, or passed over too often. */
        boolean skip(String key) {
            return declined(key) || tired(key);
        }

        /** The titles you've opened, newest first. */
        List<String> openedTitles() {
            List<String> out = new ArrayList<>();
            Iterator<String> it = s.opened.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!k.startsWith("app:")) out.add(k);
            }
            Collections.reverse(out);
            return out;
        }
    }

    static synchronized View view(Context c) {
        return new View(read(c));
    }

    /** True when {@code title} is, loosely, one of {@code keys} (a show's name inside an episode's title, or the other way). */
    static boolean matches(String title, List<String> keys) {
        String t = Continue.key(title);
        if (t.length() < 3) return false;
        for (String k : keys) if (k.length() >= 3 && (t.startsWith(k) || k.startsWith(t))) return true;
        return false;
    }
}
