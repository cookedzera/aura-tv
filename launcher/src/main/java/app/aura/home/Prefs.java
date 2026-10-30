package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aura's own choices, all in one place with their defaults. */
final class Prefs {
    static final String GLASS_TILES = "set_glass_tiles", NAMES_ALWAYS = "set_names_always", STACK_AUTO = "set_stack_auto",
            MOMENTS = "set_moments", PICKS = "set_picks", WEATHER = "set_weather", DAILY = "set_daily", HIDDEN = "set_hidden",
            MINE_ONLY = "set_mine_only", BLACK = "set_black", TOP_SHELF = "set_top_shelf", PARALLAX = "set_parallax",
            FLYOVERS = "set_flyovers", INPUT_PREVIEW = "set_input_preview";

    private Prefs() { }

    /** The settings of the profile in use (see {@link Profiles}). */
    static SharedPreferences of(Context c) {
        return c.getSharedPreferences(Profiles.file(c), Context.MODE_PRIVATE);
    }

    /** Settings shared by everyone on this TV: updates, Block ads, the weather's location. */
    static SharedPreferences device(Context c) {
        return c.getSharedPreferences("aura", Context.MODE_PRIVATE);
    }

    static boolean on(Context c, String key) {
        // everything starts on, except always-visible app names, only your own photos and pure black;
        // flyovers download video, so they wait until asked for; and live pictures of inputs, which
        // some TVs draw over the whole screen (a soundbar on HDMI: black, with sound)
        return of(c).getBoolean(key, !NAMES_ALWAYS.equals(key) && !MINE_ONLY.equals(key) && !BLACK.equals(key)
                && !FLYOVERS.equals(key) && !INPUT_PREVIEW.equals(key));
    }

    static void set(Context c, String key, boolean v) {
        of(c).edit().putBoolean(key, v).apply();
    }

    static final String TILE_STYLE = "set_tile_style";
    static final String[] TILE_STYLES = {"glass", "vivid", "midnight", "original"};
    static final String[] TILE_NAMES = {"Glass", "Vivid", "Midnight", "Original banners"};

    /** How app tiles look; from before there were four, the old glass switch decides. */
    static String tileStyle(Context c) {
        String s = of(c).getString(TILE_STYLE, null);
        return s != null ? s : on(c, GLASS_TILES) ? "glass" : "original";
    }

    /** The screensaver's scene; Auto chooses by the light and the weather (see Ambient). */
    static final String SAVER = "set_saver";
    static final String[] SAVERS = {"auto", "museum", "wind", "sky", "space", "mosaic", "photos"};
    static final String[] SAVER_NAMES = {"Auto", "Museum", "Live wind", "Tonight's sky", "Earth from space", "Photo mosaic",
            "Photographs"};

    static String saver(Context c) {
        String s = of(c).getString(SAVER, "auto");
        return java.util.Arrays.asList(SAVERS).contains(s) ? s : "auto";
    }

    static final String WIDGET_ORDER = "set_widget_order", WIDGETS_HIDDEN = "set_widgets_hidden";

    /** The widgets' order, as indexes into their natural order (sky, stack, today). */
    static List<Integer> widgetOrder(Context c) {
        List<Integer> out = new ArrayList<>();
        for (String s : of(c).getString(WIDGET_ORDER, "0,1,2").split(",")) {
            try {
                int i = Integer.parseInt(s.trim());
                if (i >= 0 && i < 3 && !out.contains(i)) out.add(i);
            } catch (NumberFormatException ignored) {
            }
        }
        for (int i = 0; i < 3; i++) if (!out.contains(i)) out.add(i);
        return out;
    }

    static void setWidgetOrder(Context c, List<Integer> order) {
        StringBuilder b = new StringBuilder();
        for (int i : order) b.append(b.length() > 0 ? "," : "").append(i);
        of(c).edit().putString(WIDGET_ORDER, b.toString()).apply();
    }

    static Set<String> hiddenWidgets(Context c) {
        return new HashSet<>(of(c).getStringSet(WIDGETS_HIDDEN, new HashSet<>()));
    }

    static void hideWidget(Context c, String id, boolean hide) {
        Set<String> s = hiddenWidgets(c);
        if (hide) s.add(id);
        else s.remove(id);
        of(c).edit().putStringSet(WIDGETS_HIDDEN, s).apply();
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
