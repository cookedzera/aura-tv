package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aura's own choices, all in one place with their defaults. */
final class Prefs {
    /** The Smart Stack's optional cards: new releases from TVmaze's schedule, and suggested apps. */
    static final String STACK_NEW = "set_stack_new", STACK_APPS = "set_stack_apps";
    /** Phones and computers on the network may play on the TV over DLNA ({@link Renderer}). */
    static final String DLNA = "set_dlna";
    static final String GLASS_TILES = "set_glass_tiles", NAMES_ALWAYS = "set_names_always", STACK_AUTO = "set_stack_auto",
            MOMENTS = "set_moments", PICKS = "set_picks", WEATHER = "set_weather", DAILY = "set_daily", HIDDEN = "set_hidden",
            MINE_ONLY = "set_mine_only", BLACK = "set_black", TOP_SHELF = "set_top_shelf", PARALLAX = "set_parallax",
            FLYOVERS = "set_flyovers", INPUT_PREVIEW = "set_input_preview";
    /**
     * Music scenes: while a song plays and home rests, one of Apple's aerial videos streams behind
     * the player ({@link MusicScene}); beat glow, a light that breathes with the bass. Both wait to be
     * asked for: the videos use the network, the glow reads the TV's sound.
     */
    static final String SCENES = "set_music_scenes", BEAT = "set_beat_glow", SCENE_KIND = "set_scene_kind";

    private Prefs() { }

    /** The settings of the profile in use (see {@link Profiles}). */
    static SharedPreferences of(Context c) {
        return c.getSharedPreferences(Profiles.file(c), Context.MODE_PRIVATE);
    }

    /** Settings shared by everyone on this TV: updates, Block ads, the weather's location. */
    static SharedPreferences device(Context c) {
        return c.getSharedPreferences("aura", Context.MODE_PRIVATE);
    }

    /**
     * Where the wall's photographs come from, each on or off (Settings → Wallpaper → Photo sources).
     * Spotlight and Bing need nothing; Unsplash needs your own free key, sent from your phone.
     */
    static final String SRC_SPOTLIGHT = "src_spotlight", SRC_BING = "src_bing", SRC_WIKIMEDIA = "src_wikimedia",
            SRC_WALLHAVEN = "src_wallhaven", SRC_UNSPLASH = "src_unsplash", UNSPLASH_KEY = "unsplash_key";

    static boolean on(Context c, String key) {
        if (SRC_WIKIMEDIA.equals(key) || SRC_WALLHAVEN.equals(key) || SRC_UNSPLASH.equals(key)) return of(c).getBoolean(key, false);
        // everything starts on, except always-visible app names, only your own photos and pure black;
        // flyovers download video, so they wait until asked for; and live pictures of inputs, which
        // some TVs draw over the whole screen (a soundbar on HDMI: black, with sound)
        return of(c).getBoolean(key, !NAMES_ALWAYS.equals(key) && !MINE_ONLY.equals(key) && !BLACK.equals(key)
                && !FLYOVERS.equals(key) && !INPUT_PREVIEW.equals(key) && !SCENES.equals(key) && !BEAT.equals(key)
                && !AERIALS_AUTO.equals(key));
    }

    static void set(Context c, String key, boolean v) {
        of(c).edit().putBoolean(key, v).apply();
    }

    static final String TILE_STYLE = "set_tile_style";
    static final String[] TILE_STYLES = {"tvos", "glass", "vivid", "midnight", "original"};
    static final int[] TILE_NAMES = {R.string.tile_apple_tv, R.string.tile_glass, R.string.tile_vivid, R.string.tile_midnight, R.string.tile_original};
    /** The same, short enough for the welcome's small tiles. */
    static final int[] TILE_SHORT_NAMES = {R.string.tile_apple_tv, R.string.tile_glass, R.string.tile_vivid, R.string.tile_midnight, R.string.tile_original_short};

    /** Apps a row holds: large tiles, or more of your apps on screen at once, as on tvOS. */
    static final String GRID = "set_grid";
    /** Two rows of apps with the rest on pages to the side (on), or one long grid scrolling down. */
    static final String GRID_PAGES = "set_grid_pages";
    static final int[] GRID_COLS = {5, 6, 7};
    static final int[] GRID_NAMES = {R.string.grid_large, R.string.grid_compact, R.string.grid_dense};

    static int gridCols(Context c) {
        int n = of(c).getInt(GRID, 5);
        return n >= 5 && n <= 7 ? n : 5;
    }

    /** How app tiles look; from before there were four, the old glass switch decides. */
    static String tileStyle(Context c) {
        String s = of(c).getString(TILE_STYLE, null);
        return s != null ? s : tvos(c) ? "tvos" : on(c, GLASS_TILES) ? "glass" : "original";
    }

    /** The screensaver's scene: the photographs, famous quotes over them, or Auto, the two by turns (see Ambient). */
    static final String SAVER = "set_saver";
    static final String[] SAVERS = {"auto", "photos", "sayings", "aerials", "aerial_sayings"};
    static final int[] SAVER_NAMES = {R.string.saver_auto, R.string.photographs, R.string.saver_sayings,
            R.string.saver_aerials, R.string.saver_aerial_quotes};
    /** Apple's aerial videos join Auto's turns (photographs, aerials, quotes over aerials); off, as they stream. */
    static final String AERIALS_AUTO = "set_aerials_auto";

    /** Whether a screensaver mode streams aerials (Android 7+, where their safe connection works). */
    static boolean aerialSaver(Context c, String mode) {
        if (android.os.Build.VERSION.SDK_INT < 24) return false;
        return mode.startsWith("aerial") || mode.equals("auto") && on(c, AERIALS_AUTO);
    }

    static String saver(Context c) {
        String s = of(c).getString(SAVER, "auto");
        return java.util.Arrays.asList(SAVERS).contains(s) ? s : "auto";
    }

    /**
     * The home screen's style: "aura" (the sidebar, widgets, then apps) or "tvos" (apps alone on a
     * frosted dock low on the screen, the photograph above, as on Apple TV). With the tvOS style the
     * sidebar is hidden unless {@link #DOCK_SIDEBAR} brings it back, sliding in from the left.
     */
    static final String LAYOUT = "set_layout", DOCK_SIDEBAR = "set_dock_sidebar", DOCK_WIDGETS = "set_dock_widgets";

    static boolean tvos(Context c) {
        return "tvos".equals(of(c).getString(LAYOUT, "aura"));
    }

    static boolean dockSidebar(Context c) {
        return of(c).getBoolean(DOCK_SIDEBAR, false);
    }

    static boolean dockWidgets(Context c) {
        return of(c).getBoolean(DOCK_WIDGETS, false);
    }

    static final String NAME_MODE = "set_name_mode";
    static final String[] NAME_MODES = {"tvos", "focus", "always", "hidden"};
    static final int[] NAME_LABELS = {R.string.names_tvos, R.string.names_focus, R.string.names_always, R.string.hidden};

    static String nameMode(Context c) {
        String m = of(c).getString(NAME_MODE, null);
        if (m != null && java.util.Arrays.asList(NAME_MODES).contains(m)) return m;
        if (on(c, NAMES_ALWAYS)) return "always";
        return tvos(c) ? "tvos" : "focus";
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
