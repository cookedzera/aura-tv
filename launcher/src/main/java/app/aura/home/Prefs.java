package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Aura's own choices, all in one place with their defaults. */
final class Prefs {
    static final String GLASS_TILES = "set_glass_tiles", NAMES_ALWAYS = "set_names_always", STACK_AUTO = "set_stack_auto",
            MOMENTS = "set_moments", PICKS = "set_picks", WEATHER = "set_weather", DAILY = "set_daily", HIDDEN = "set_hidden";

    private Prefs() { }

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("aura", Context.MODE_PRIVATE);
    }

    static boolean on(Context c, String key) {
        // everything starts on, except always-visible app names
        return of(c).getBoolean(key, !NAMES_ALWAYS.equals(key));
    }

    static void set(Context c, String key, boolean v) {
        of(c).edit().putBoolean(key, v).apply();
    }

    static Set<String> hidden(Context c) {
        return new HashSet<>(of(c).getStringSet(HIDDEN, new HashSet<>()));
    }

    static void hide(Context c, String pkg, boolean hide) {
        Set<String> s = hidden(c);
        if (hide) s.add(pkg);
        else s.remove(pkg);
        of(c).edit().putStringSet(HIDDEN, s).apply();
    }
}
