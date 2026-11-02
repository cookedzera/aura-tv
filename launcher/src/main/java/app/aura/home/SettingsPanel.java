package app.aura.home;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Scanner;

/**
 * Aura's settings on the same frosted veil as Control Center: the title set large on the left, a
 * list of choices on the right where OK cycles each value. Hidden apps open as a second page in
 * the same list; Back steps out of it, then closes.
 */
final class SettingsPanel {
    interface Host {
        Backdrop backdrop();

        List<AppRepo.App> apps();

        void applied(String what);

        void open(Intent intent, View from);

        boolean type(String text);

        void hud(int icon, String text);

        /** Who's watching, in place of Settings. */
        void whoIsWatching();
    }

    final FrameLayout panel;
    private final Activity act;
    private final Host host;
    private final LinearLayout head, rows;
    private final ScrollView scroll;
    private final TextView title, note, help;
    private final EditText city;
    /** The page on show: "" for the main list, else "apps", "widgets", "wallpaper", "drop" or "updates". */
    private String page = "";
    private boolean confirmClear;
    /** The photo whose removal waits for a second OK. */
    private String confirmPhoto;
    /** The profile whose removal waits for a second OK. */
    private String confirmProfile;
    /** Remove Aura waits for a second OK. */
    private boolean confirmRemove;
    /** "Checking…" while Block ads confirms the filter works; null otherwise. */
    private String shieldNote;
    /** What the text field is for: "city", "dns", "profile" or "tmdb". */
    private String inputFor = "city";
    /** Speeds measured by Find the fastest, by hostname; -1 when unreachable. */
    private final java.util.Map<String, Long> probed = new java.util.HashMap<>();
    private final java.util.Set<String> blocksAds = new java.util.HashSet<>();
    private boolean probing;
    private Updater.Release release;
    private String updateState = "", updateNote = "";
    // set up on this TV: the last connection's result, and how far setup got
    private String adbState = "", setupNote = "";
    private boolean setupRunning, confirmAdbOff;
    // remote buttons: waiting for a press; the button pressed, waiting for its app; a removal waiting for a second OK
    private boolean teachingKey;
    private int[] newKey;
    private String confirmKey;
    private Drop drop;
    private View dropCard;
    private TextView dropTitle, dropSub;
    private HomeActivity.Meter dropMeter;
    private LinearLayout dropThumbs;
    private final java.util.Map<String, android.graphics.Bitmap> thumbs = new java.util.HashMap<>();

    SettingsPanel(Activity a, Host h) {
        act = a;
        host = h;
        panel = HomeActivity.flat(a);
        GlassFrame veil = new GlassFrame(a, h.backdrop(), 0, 0.5f);
        veil.edgeLight = false;
        panel.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        head = new LinearLayout(a) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        head.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = Ui.caps(a, 8, 0x99FFFFFF, 0.3f);
        kicker.setText("AURA");
        head.addView(kicker);
        title = Ui.text(a, 52, Ui.heavy, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setLetterSpacing(-0.04f);
        head.addView(title, lp(0, 4));
        note = Ui.text(a, 12.5f, Ui.regular, 0xA6FFFFFF);
        note.setSingleLine(false);
        note.setMaxLines(4);
        note.setMaxWidth(Ui.dp(280));
        note.setLineSpacing(Ui.dpf(3), 1f);
        head.addView(note, lp(0, 12));
        // what the row in focus does, in a sentence: tvOS Settings' way of explaining itself
        help = Ui.text(a, 14, Ui.medium, 0xF2FFFFFF);
        help.setSingleLine(false);
        help.setMaxLines(5);
        help.setMaxWidth(Ui.dp(280));
        help.setLineSpacing(Ui.dpf(4), 1f);
        help.setMinLines(4);
        head.addView(help, lp(0, 26));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Ui.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        hl.leftMargin = Ui.dp(112);
        panel.addView(head, hl);

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        city = new EditText(a);
        city.setSingleLine(true);
        city.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        city.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        city.setHint("Type a city, then OK");
        city.setTextColor(0xFFFFFFFF);
        city.setHintTextColor(0x66FFFFFF);
        city.setTextSize(13);
        city.setTypeface(Ui.medium);
        city.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        android.graphics.drawable.GradientDrawable field = new android.graphics.drawable.GradientDrawable();
        field.setColor(0x1FFFFFFF);
        field.setCornerRadius(Ui.dpf(14));
        field.setStroke(Math.max(1, Ui.hair()), 0x40FFFFFF);
        city.setBackground(field);
        city.setVisibility(View.GONE);
        city.setOnEditorActionListener((v, id, e) -> {
            // the same field takes a city for the weather or a hostname for Block ads
            if ("dns".equals(inputFor)) setCustomDns(city.getText().toString().trim());
            else if ("profile".equals(inputFor)) addProfile(city.getText().toString().trim());
            else if ("tmdb".equals(inputFor)) setTmdbKey(city.getText().toString().trim());
            else if ("pin".equals(inputFor)) setPin(city.getText().toString().trim());
            else findCity(city.getText().toString().trim());
            return true;
        });
        right.addView(city, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40)));
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(24));
        scroll.setClipToPadding(false);
        // room on every side for a focused row to grow into, so it's never clipped
        scroll.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        scroll.setClipChildren(false);
        rows = new LinearLayout(a) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // tall enough that the whole main list fits without scrolling: no row is ever cut at an edge
        // 13 rows of 35 dp with 4 dp between, and room to grow, inside a 540 dp-tall TV screen
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(424), Ui.dp(532), Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = Ui.dp(78);
        panel.addView(right, rl);
        panel.setVisibility(View.GONE);
    }

    /** Fills and submits the panel's text field if it's currently open. */
    boolean typeIntoField(String text) {
        if (!isOpen() || city.getVisibility() != View.VISIBLE || text == null || text.isEmpty()) return false;
        city.setText(text);
        city.setSelection(city.length());
        city.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        return true;
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open() {
        page = "";
        entry = null;
        build();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setStartDelay(0).setDuration(260).setInterpolator(Ui.SOFT).start();
        head.setAlpha(0f);
        head.setTranslationX(-Ui.dp(20));
        head.animate().alpha(1f).translationX(0f).setStartDelay(40).setDuration(460).setInterpolator(Ui.EMPHASIZED).start();
        rows.setAlpha(0f);
        rows.setTranslationX(0f);
        rows.setTranslationY(Ui.dp(14));
        rows.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(380).setInterpolator(Ui.EMPHASIZED).start();
        focusFirst();
    }

    /** The first row that takes focus: section labels don't. */
    private void focusFirst() {
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).isFocusable() && rows.getChildAt(i).requestFocus()) return;
    }

    /**
     * Opened straight on {@code at} (a widget's "Choose cards" opens Widgets, the remote's profile
     * button Profiles): Back from that page leaves Settings, back to where you were, rather than
     * up to a main list you never saw. Pages further in still lead back to it first.
     */
    void open(String at) {
        open();
        go(at);
        entry = at;
    }

    /** The page Settings was opened straight on, or null. */
    private String entry;

    /** Straight to one page, as when Android's screensaver settings hand over to Aura. */
    void show(String p) {
        go(p);
    }

    /** Redraws the page on show, after something outside changed what it says. */
    void refresh() {
        if (isOpen()) build();
    }

    void close() {
        thumbs.clear();
        stopDrop();
        // closed while waiting for a remote button: the next press stays an ordinary one
        if (teachingKey || newKey != null) {
            teachingKey = false;
            newKey = null;
            HomeKeys.stopTeaching();
        }
        hideKeyboard();
        panel.animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(Ui.SOFT)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    /** Back leaves the city field, then the hidden-apps page; returns false when the panel should close. */
    boolean back() {
        if (city.getVisibility() == View.VISIBLE) {
            city.setVisibility(View.GONE);
            hideKeyboard();
            build();
            return true;
        }
        if (page.equals(entry) && !(page.equals("keys") && (newKey != null || teachingKey))) return false;
        if (page.equals("photos")) {
            // the photo list sits under From your phone, whose code stays on
            confirmPhoto = null;
            // the "Your photos" row is gone once the last one is removed: focus the first row instead
            go("drop");
            focusTag("photos");
            return true;
        }
        if (page.equals("widgets")) {
            go("home");
            focusTag("widgets");
            return true;
        }
        if (page.equals("sources")) {
            go("wallpaper");
            focusTag("sources");
            return true;
        }
        if (page.equals("unsplashphone")) {
            stopDrop();
            go("sources");
            focusTag("src:unsplash");
            return true;
        }
        if (page.equals("art")) {
            // opened from an app's tile: back to the home screen, not Settings
            stopDrop();
            return false;
        }
        if (page.equals("pair")) {
            stopDrop();
            go("tv");
            focusTag("adb:pair");
            return true;
        }
        if (page.equals("genres") || page.equals("tmdbphone") || page.equals("tmdbauth")) {
            String was = page;
            if (was.equals("tmdbphone")) stopDrop();
            authToken = null;
            go("picks");
            focusTag(was.equals("genres") ? "pk:genres" : was.equals("tmdbphone") ? "pk:phone" : "pk:auth");
            return true;
        }
        if (page.equals("keys")) {
            if (newKey != null || teachingKey) {
                newKey = null;
                teachingKey = false;
                HomeKeys.stopTeaching();
                build();
                focusTag("keys:add");
                return true;
            }
            go("home");
            focusTag("keys");
            return true;
        }
        if (!page.isEmpty()) {
            String was = page;
            if (was.equals("drop") || was.equals("backup") || was.equals("trouble")) stopDrop();
            page = "";
            build();
            // back on the main list, on the row that led to the page
            slidePage(true);
            focusTag(was.equals("apps") ? "hidden" : was);
            return true;
        }
        return false;
    }

    private void go(String p) {
        confirmRetire = false;
        boolean back = p.isEmpty() || page.equals("photos") && p.equals("drop")
                || (page.equals("widgets") || page.equals("keys")) && p.equals("home")
                || page.equals("pair") && p.equals("tv")
                || (page.equals("genres") || page.equals("tmdbphone") || page.equals("tmdbauth")) && p.equals("picks");
        page = p;
        build();
        slidePage(back);
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) {
                rows.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    // ---- pages -------------------------------------------------------------------------------

    private void slidePage(boolean back) {
        if (!isOpen()) return;
        rows.setAlpha(0f);
        rows.setTranslationY(0f);
        rows.setTranslationX(Ui.dp(back ? -18 : 18));
        rows.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(300).setInterpolator(Ui.EMPHASIZED).start();
    }

    private void build() {
        String keep = focusedTag();
        rows.removeAllViews();
        note.setMaxLines(page.equals("updates") ? 18 : 4);
        // the release notes have the whole column to themselves
        help.setVisibility(page.equals("updates") ? View.GONE : View.VISIBLE);
        help.setText("");
        if (page.equals("apps")) {
            Ui.fit(title, "Apps", Ui.dp(300), 52, 30);
            note.setText("Hidden apps leave your grid. They stay installed, and you can bring them back here.");
            // inputs hidden from home first (hold OK on an input's tile), by their name on this TV
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) act.getSystemService(android.content.Context.TV_INPUT_SERVICE);
            for (String h : Prefs.hidden(act)) {
                if (!h.startsWith("input:")) continue;
                android.media.tv.TvInputInfo info;
                try {
                    info = tim == null ? null : tim.getTvInputInfo(h.substring(6));
                } catch (RuntimeException e) {
                    info = null;
                }
                String name = info != null ? String.valueOf(info.loadLabel(act)) : "An input";
                add(name, "Hidden · OK shows it", h, () -> {
                    Prefs.hide(act, h, false);
                    host.applied("inputs");
                    build();
                });
            }
            for (AppRepo.App a : host.apps()) {
                boolean hidden = Prefs.hidden(act).contains(a.pkg);
                add(a.label, hidden ? "Hidden" : "Shown", "app:" + a.pkg, () -> {
                    Prefs.hide(act, a.pkg, !Prefs.hidden(act).contains(a.pkg));
                    host.applied("apps");
                    build();
                });
            }
        } else if (page.equals("home")) {
            Ui.fit(title, "Home screen", Ui.dp(300), 52, 30);
            note.setText("How your apps look and what moves on the home screen.");
            boolean tvos = Prefs.tvos(act);
            section("LOOK");
            // the two styles: Aura's own (sidebar, widgets, apps) or Apple TV's (apps alone on a dock)
            add("Style", tvos ? "tvOS · apps on a dock" : "Aura · widgets and sidebar", "layout", () -> {
                Prefs.of(act).edit().putString(Prefs.LAYOUT, Prefs.tvos(act) ? "aura" : "tvos").remove(Prefs.TILE_STYLE).remove(Prefs.NAME_MODE).apply();
                host.applied("layout");
                build();
                focusTag("layout");
            });
            if (tvos) add("Sidebar", Prefs.dockSidebar(act) ? "Slides in from the left" : "Hidden · Up for the top buttons", "dockside", () -> {
                Prefs.of(act).edit().putBoolean(Prefs.DOCK_SIDEBAR, !Prefs.dockSidebar(act)).apply();
                host.applied("layout");
                build();
                focusTag("dockside");
            });
            int si = java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act));
            add("Tiles", Prefs.TILE_NAMES[Math.max(0, si)], "tiles", () -> {
                // OK steps through the styles; the grid changes straight away behind the panel
                String next = Prefs.TILE_STYLES[(Math.max(0, si) + 1) % Prefs.TILE_STYLES.length];
                Prefs.of(act).edit().putString(Prefs.TILE_STYLE, next).apply();
                host.applied("tiles");
                build();
                focusTag("tiles");
            });
            int gi = Math.max(0, Prefs.gridCols(act) - 5);
            add("App size", Prefs.GRID_NAMES[gi], "grid", () -> {
                // OK steps through the sizes; the grid is laid out again behind the panel
                Prefs.of(act).edit().putInt(Prefs.GRID, Prefs.GRID_COLS[(gi + 1) % Prefs.GRID_COLS.length]).apply();
                host.applied("apps");
                build();
                focusTag("grid");
            });
            toggle("Apps beyond two rows", Prefs.GRID_PAGES, "Pages to the side", "Scroll down", "apps");
            int ni = Math.max(0, java.util.Arrays.asList(Prefs.NAME_MODES).indexOf(Prefs.nameMode(act)));
            add("App names", Prefs.NAME_LABELS[ni], "names", () -> {
                String next = Prefs.NAME_MODES[(ni + 1) % Prefs.NAME_MODES.length];
                Prefs.of(act).edit().putString(Prefs.NAME_MODE, next).putBoolean(Prefs.NAMES_ALWAYS, "always".equals(next)).apply();
                host.applied("names");
                build();
                focusTag("names");
            });
            toggle("Parallax icons", Prefs.PARALLAX, "On", "Off", "parallax");
            section("WHAT SHOWS");
            int hiddenW = Prefs.hiddenWidgets(act).size();
            boolean wShown = (!tvos || Prefs.dockWidgets(act)) && hiddenW < 3;
            // the plainest home: your apps and the wall, no widgets and no app cards (continue
            // watching, an app's rows); the two rows below still change each on its own
            boolean only = !wShown && !Prefs.on(act, Prefs.TOP_SHELF);
            add("Only apps", only ? "On · nothing but your apps" : "Off", "onlyapps", () -> {
                boolean on = !only;
                android.content.SharedPreferences.Editor e = Prefs.of(act).edit().putBoolean(Prefs.TOP_SHELF, !on);
                if (tvos) e.putBoolean(Prefs.DOCK_WIDGETS, !on);
                if (!tvos || !on) {
                    java.util.Set<String> s = new java.util.HashSet<>();
                    if (on) java.util.Collections.addAll(s, HomeActivity.WIDGET_IDS);
                    e.putStringSet(Prefs.WIDGETS_HIDDEN, s);
                }
                e.apply();
                host.applied("shelf");
                host.applied("widgets");
                build();
                focusTag("onlyapps");
            });
            add("Widgets", !wShown ? "Hidden · clean" : hiddenW == 0 ? "All shown" : (3 - hiddenW) + " of 3 shown", "widgets", () -> go("widgets"));
            // an app's own cards above it when it has focus (JioHotstar's rows, YouTube's videos); off for only apps
            toggle(tvos ? "App cards" : "Top Shelf", Prefs.TOP_SHELF, "On", "Hidden · clean", "shelf");
            toggle("Tonight's pick", Prefs.PICKS, "Shown", "Hidden · clean", "picks");
            toggle("Live picture on input tiles", Prefs.INPUT_PREVIEW, "On", "Off", "inputs");
            section("SPEED");
            // stillness for slow TVs; Auto decides from the TV's memory and chip
            String lm = Perf.mode(act);
            add("Light mode", lm.equals("auto") ? "Auto · " + (Perf.lowEnd(act) ? "on for this TV" : "off for this TV") : lm.equals("on") ? "On" : "Off", "light", () -> {
                String next = Perf.MODES[(java.util.Arrays.asList(Perf.MODES).indexOf(Perf.mode(act)) + 1) % Perf.MODES.length];
                Prefs.device(act).edit().putString(Perf.KEY, next).apply();
                host.applied("light");
                build();
                focusTag("light");
            });
            section("REMOTE");
            // switched by Aura where setup allowed it; elsewhere, Android's own Accessibility screen does it
            // how late the TV hands over remote releases while Aura watches the keys: some firmware adds a lot
            int lag = Prefs.device(act).getInt("keys_lag", 0);
            add("Home button in apps", HomeKeys.on(act) ? "Twice: switcher · hold: Control Center" + (lag >= 150 ? " · remote " + lag + " ms late" : "") : "Off", "homekeys", () -> {
                if (!HomeKeys.set(act, !HomeKeys.on(act))) {
                    host.open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), panel);
                    return;
                }
                build();
                focusTag("homekeys");
            });
            // where Android sends Home to another launcher, the service can send it to Aura instead
            if (HomeKeys.on(act) && !HomeRole.held(act)) {
                add("Home goes to", HomeKeys.takeover(act) ? "Aura" : HomeRole.other(act), "takeover", () -> {
                    HomeKeys.setTakeover(act, !HomeKeys.takeover(act));
                    build();
                    focusTag("takeover");
                });
            }
            // pictures need the service above, and Android 11
            if (android.os.Build.VERSION.SDK_INT >= 30 && HomeKeys.on(act)) {
                add("Pictures in the switcher", Previews.on(act) ? "Apps as you left them" : "Off", "previews", () -> {
                    Previews.set(act, !Previews.on(act));
                    host.hud(R.drawable.ic_check, Previews.on(act) ? "Kept only on this TV, two days at most" : "Pictures deleted");
                    build();
                    focusTag("previews");
                });
            }
            if (HomeKeys.on(act)) {
                int n = HomeKeys.keys(act).size();
                add("Remote buttons", n == 0 ? "Give a button an app" : n + (n == 1 ? " button with an app" : " buttons with apps"), "keys", () -> go("keys"));
            }
        } else if (page.equals("profiles")) {
            Ui.fit(title, "Profiles", Ui.dp(300), 52, 30);
            note.setText("Everyone gets their own app order, folders, tiles, widgets and wallpaper. OK switches to a profile.");
            String cur = Profiles.current(act);
            for (Profiles.Profile pr : Profiles.all(act)) {
                add(pr.name, pr.id.equals(cur) ? "In use" : "Switch", "pr:" + pr.id, () -> {
                    if (pr.id.equals(Profiles.current(act))) return;
                    Profiles.use(act, pr.id);
                    host.hud(R.drawable.ic_check, "Hello, " + pr.name);
                    host.applied("profile");
                });
            }
            if (Profiles.all(act).size() < Profiles.MAX) add("Add a profile", "Type a name", "pr:add", this::askProfile);
            // a profile for kids: grown-up things (Settings, hiding, uninstalling) ask for the parents' PIN
            for (Profiles.Profile pr : Profiles.all(act)) {
                if (Profiles.MAIN.equals(pr.id)) continue;
                add(pr.name + " is for kids", pr.kids ? "On · PIN for grown-up things" : "Off", "pr:kids:" + pr.id, () -> {
                    if (!pr.kids && !Profiles.hasPin(act)) {
                        pinFor = pr.id;
                        askPin();
                        return;
                    }
                    Profiles.setKids(act, pr.id, !pr.kids);
                    build();
                    focusTag("pr:kids:" + pr.id);
                });
            }
            if (Profiles.hasPin(act)) add("Parents' PIN", "Change", "pr:pin", () -> {
                pinFor = null;
                askPin();
            });
            for (Profiles.Profile pr : Profiles.all(act)) {
                if (Profiles.MAIN.equals(pr.id)) continue;
                add("Remove " + pr.name, ("pr:del:" + pr.id).equals(confirmProfile) ? "Press OK again" : "", "pr:del:" + pr.id, () -> {
                    String tag = "pr:del:" + pr.id;
                    if (!tag.equals(confirmProfile)) {
                        confirmProfile = tag;
                        build();
                        return;
                    }
                    confirmProfile = null;
                    boolean wasCurrent = pr.id.equals(Profiles.current(act));
                    Profiles.remove(act, pr.id);
                    host.hud(R.drawable.ic_check, pr.name + " removed");
                    if (wasCurrent) host.applied("profile");
                    else {
                        build();
                        focusTag("pr:" + Profiles.current(act));
                    }
                });
            }
        } else if (page.equals("widgets")) {
            Ui.fit(title, "Widgets", Ui.dp(300), 52, 30);
            note.setText("Hide the ones you don't use and the rest widen to fill the row. Holding OK on a widget moves or hides it too. "
                    + "The Smart Stack always deals what you're watching first; the cards below are extras.");
            boolean tvos = Prefs.tvos(act);
            java.util.Set<String> hidden = Prefs.hiddenWidgets(act);
            boolean anyOn = (!tvos || Prefs.dockWidgets(act)) && hidden.size() < HomeActivity.WIDGET_IDS.length;
            add("Show widgets", anyOn ? "On" : "Hidden · clean", "w:all", () -> {
                if (tvos) {
                    boolean turnOn = !anyOn;
                    Prefs.of(act).edit().putBoolean(Prefs.DOCK_WIDGETS, turnOn).apply();
                    if (turnOn && hidden.size() >= HomeActivity.WIDGET_IDS.length) {
                        Prefs.of(act).edit().putStringSet(Prefs.WIDGETS_HIDDEN, new java.util.HashSet<>()).apply();
                    }
                } else {
                    java.util.Set<String> s = new java.util.HashSet<>();
                    if (anyOn) java.util.Collections.addAll(s, HomeActivity.WIDGET_IDS);
                    Prefs.of(act).edit().putStringSet(Prefs.WIDGETS_HIDDEN, s).apply();
                }
                host.applied("widgets");
                build();
                focusTag("w:all");
            });
            for (int i : Prefs.widgetOrder(act)) {
                String id = HomeActivity.WIDGET_IDS[i];
                boolean shown = (!tvos || Prefs.dockWidgets(act)) && !hidden.contains(id);
                add(HomeActivity.WIDGET_NAMES[i], shown ? "Shown" : "Hidden", "w:" + id, () -> {
                    if (tvos && !Prefs.dockWidgets(act)) {
                        Prefs.of(act).edit().putBoolean(Prefs.DOCK_WIDGETS, true).apply();
                        Prefs.hideWidget(act, id, false);
                    } else {
                        Prefs.hideWidget(act, id, !Prefs.hiddenWidgets(act).contains(id));
                    }
                    host.applied("widgets");
                    build();
                    focusTag("w:" + id);
                });
            }
            toggle("Stack pages", Prefs.STACK_AUTO, "Turn on their own", "Stay put", "stack");
            // the stack's extras: today's releases come from the country's schedule, not your account
            toggle("New today on your apps", Prefs.STACK_NEW, "In the stack", "Left out", "cards");
            toggle("Suggested apps", Prefs.STACK_APPS, "In the stack", "Left out", "cards");
            // cards turned down from the stack (hold OK on a card): all back at once
            int declined = Taste.declinedCount(act);
            if (declined > 0) add("Cards you turned down", declined + " · OK brings them back", "w:forgive", () -> {
                Taste.forgive(act);
                host.applied("widgets");
                host.hud(R.drawable.ic_check, "They're back in the stack");
                build();
            });
            add("Original order", "", "w:reset", () -> {
                Prefs.setWidgetOrder(act, java.util.Arrays.asList(0, 1, 2));
                host.applied("widgets");
                build();
            });
        } else if (page.equals("shield")) {
            Ui.fit(title, "Block ads", Ui.dp(300), 52, 30);
            note.setText("Ad and tracker servers get no answer across apps and menus (YouTube's own video stream ads come from the video server itself). Pick a filter, or find the fastest one from this TV.");
            add("Block ads", shieldNote != null ? shieldNote : Shield.on(act) ? "On" : "Off", "shield:on", this::toggleShield);
            String current = Shield.host(act);
            boolean custom = true;
            for (String[] f : Shield.FILTERS) {
                if (f[0].equals(current)) custom = false;
                add(f[1], filterValue(f[0], f[2], current), "dns:" + f[0], () -> pickFilter(f[0], f[1]));
            }
            add("Your own DNS", custom ? filterValue(current, current, current) : "Type a hostname", "dns:custom", this::askDns);
            add("Find the fastest", probing ? "Measuring…" : "", "dns:probe", this::findFastest);
            add("Wi-Fi DNS settings", "If this TV ignores Private DNS · use 94.140.14.14", "dns:wifi",
                    () -> host.open(new Intent(Settings.ACTION_WIFI_SETTINGS), panel));
        } else if (page.equals("wallpaper")) {
            Ui.fit(title, "Wallpaper", Ui.dp(300), 52, 30);
            note.setText("Photographs that follow the sky, or pure black for OLED. The screensaver shows the photographs with a few lines about each, or famous quotes over them. Auto moves between the two.");
            toggle("Wallpaper", Prefs.BLACK, "Pure black", "Photographs", "photos");
            if (!Prefs.on(act, Prefs.BLACK)) {
                toggle("Fresh HD photographs", Prefs.DAILY, "On", "Off", "daily");
                if (Prefs.on(act, Prefs.DAILY)) {
                    List<String> on = Gallery.sources(act);
                    add("Photo sources", on.isEmpty() ? "None · Northern lights only" : on.size() + " on", "sources", () -> go("sources"));
                }
                toggle("Sky moments", Prefs.MOMENTS, "On", "Off", "moments");
            }
            // the screensaver's scene: the photographs, the quotes, or both by turns
            final List<String> modes = java.util.Arrays.asList(Prefs.SAVERS);
            String mode = Prefs.saver(act);
            add("Screensaver", Prefs.SAVER_NAMES[modes.indexOf(mode)]
                    + (mode.equals("auto") ? " · photographs and quotes by turns" : ""), Prefs.SAVER, () -> {
                String next = modes.get((modes.indexOf(Prefs.saver(act)) + 1) % modes.size());
                Prefs.of(act).edit().putString(Prefs.SAVER, next).apply();
                build();
                focusTag(Prefs.SAVER);
            });
            // the TV's own screensaver settings, here instead of three menus deep in Android's
            if (Ambient.canSet(act)) {
                boolean mine = Ambient.chosen(act);
                add("Aura as the screensaver", mine ? "On" : "Off · another one is chosen", "saver:use", () -> {
                    if (!Ambient.choose(act, !Ambient.chosen(act))) host.hud(R.drawable.ic_info, "The TV didn't allow that change");
                    build();
                    focusTag("saver:use");
                });
                if (mine) {
                    add("Starts after", Ambient.span(Ambient.startMs(act)), "saver:start", () -> {
                        Ambient.next(act, true, Ambient.startMs(act));
                        build();
                        focusTag("saver:start");
                    });
                }
                int off = Ambient.offMs(act);
                add("TV turns off after", Ambient.span(off) + (mine && off >= 0 && off < Ambient.startMs(act) ? " · before the screensaver" : " without use"), "saver:off", () -> {
                    Ambient.next(act, false, Ambient.offMs(act));
                    build();
                    focusTag("saver:off");
                });
            } else {
                add("Aura as the screensaver", Ambient.chosen(act) ? "On" : "Choose it in Android's settings", "saver:use", () -> {
                    try {
                        host.open(new Intent(Settings.ACTION_DREAM_SETTINGS), panel);
                    } catch (RuntimeException e) {
                        host.open(new Intent(Settings.ACTION_SETTINGS), panel);
                    }
                });
            }
            add("Preview the screensaver", "", "saver:preview", () -> {
                try {
                    act.startActivity(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    host.hud(R.drawable.ic_info, "This TV can't preview it; it starts after a while of rest");
                }
            });
        } else if (page.equals("updates")) {
            Ui.fit(title, "Updates", Ui.dp(300), 52, 30);
            String mine = Updater.installed(act);
            switch (updateState) {
                case "checking":
                    note.setText("Looking for a newer Aura on GitHub…");
                    break;
                case "available":
                case "downloading":
                    note.setText((installHint != null && !Updater.allowed(act) ? installHint + "\n\n" : "")
                            + "Aura " + release.version + " is out.\n\n" + release.notes);
                    break;
                case "error":
                    note.setText(updateNote);
                    break;
                default:
                    note.setText("You have the latest Aura. Updates come from the project's releases on GitHub.");
            }
            add("Installed", mine, "u:mine", () -> { });
            if (release != null && (updateState.equals("available") || updateState.equals("downloading"))) {
                add("Install " + release.version, updateState.equals("downloading") ? updateNote
                        : String.format(java.util.Locale.ROOT, "%.1f MB", release.size / 1048576f), "u:install", this::installUpdate);
            }
            if (!updateState.equals("checking") && !updateState.equals("downloading")) add("Check again", "", "u:check", this::checkUpdate);
            // the way round a TV whose installer won't take Aura's own updates (seen on Fire TV)
            final Intent downloader = updateState.equals("error")
                    ? act.getPackageManager().getLaunchIntentForPackage("com.esaba.downloader") : null;
            if (downloader != null) add("Open Downloader", "Type the code 4172997", "u:downloader",
                    () -> host.open(downloader.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), panel));
        } else if (page.equals("drop")) {
            Ui.fit(title, "From your phone", Ui.dp(300), 52, 30);
            note.setText("Point your phone's camera at the code to type on the TV, or send photos, GIFs and apps. Photos land on your wall; an app asks here before it installs.");
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200));
            cl.bottomMargin = Ui.dp(8);
            rows.addView(dropCard, cl);
            toggle("Show", Prefs.MINE_ONLY, "Only your photos", "Everything", "photos");
            int n = Mine.count(act);
            if (n > 0) add("Your photos", n + " on the TV", "photos", () -> {
                confirmPhoto = null;
                confirmClear = false;
                go("photos");
            });
        } else if (page.equals("photos")) {
            Ui.fit(title, "Your photos", Ui.dp(300), 52, 30);
            note.setText("Everything sent from your phone. OK on one removes it, after a second OK to be sure.");
            org.json.JSONArray list = Mine.list(act);
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.optJSONObject(i);
                final String id = o.optString("id");
                add(o.optString("title") + (o.optBoolean("gif") ? "  ·  GIF" : ""), id.equals(confirmPhoto) ? "Press OK again" : "Remove",
                        "p:" + id, photoThumb(id), () -> {
                            if (!id.equals(confirmPhoto)) {
                                confirmPhoto = id;
                                build();
                                return;
                            }
                            confirmPhoto = null;
                            thumbs.remove(id);
                            Mine.remove(act, id);
                            host.applied("photos");
                            build();
                            if (Mine.count(act) == 0) back();
                            else if (act.getCurrentFocus() == null || act.getCurrentFocus().getParent() != rows) go("photos");
                        });
            }
            if (list.length() > 1) add("Remove all", confirmClear ? "Press OK again" : list.length() + " photos", "clear", () -> {
                if (!confirmClear) {
                    confirmClear = true;
                    build();
                    return;
                }
                confirmClear = false;
                thumbs.clear();
                Mine.clear(act);
                host.applied("photos");
                back();
            });
        } else if (page.equals("picks")) {
            buildPicks();
        } else if (page.equals("genres")) {
            Ui.fit(title, "Genres", Ui.dp(300), 52, 30);
            note.setText("Picks come from any of the genres switched on here. With none on, any genre will do.");
            java.util.Set<String> g = Picks.genres(act);
            add("Any genre", g.isEmpty() ? "On" : "", "g:any", () -> {
                Picks.setGenres(act, new java.util.HashSet<>());
                tasteChanged();
            });
            for (Picks.Genre x : Picks.GENRES) {
                add(x.name, g.contains(x.id) ? "On" : "Off", "g:" + x.id, () -> {
                    java.util.Set<String> now = Picks.genres(act);
                    if (!now.remove(x.id)) now.add(x.id);
                    Picks.setGenres(act, now);
                    tasteChanged();
                });
            }
        } else if (page.equals("pair")) {
            Ui.fit(title, "Wireless debugging", Ui.dp(300), 52, 30);
            note.setText("In Developer options, switch on Wireless debugging and choose Pair device with pairing code. Leave it open, scan this with your phone, and type the six digits it shows.");
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
            add("Developer options", "", "pair:dev", () -> host.open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), panel));
        } else if (page.equals("backup")) {
            Ui.fit(title, "Your setup", Ui.dp(300), 52, 30);
            note.setText("Save your profiles, app order, folders, tiles and choices to your phone, and bring them back after a reinstall or on another TV. Photos stay on your phone.");
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("trouble")) {
            Ui.fit(title, "Problem reports", Ui.dp(300), 52, 30);
            note.setText("When something goes wrong, Aura keeps a note: the error, and what TV this is. Nothing is sent by itself. Scan the code to read it on your phone, then copy it into a GitHub issue or a message.");
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
            if (Trouble.count(act) > 0) add("Clear them", confirmClearTrouble ? "Press OK again" : "", "trouble:clear", () -> {
                if (!confirmClearTrouble) {
                    confirmClearTrouble = true;
                    build();
                    focusTag("trouble:clear");
                    return;
                }
                confirmClearTrouble = false;
                Trouble.clear(act);
                host.hud(R.drawable.ic_check, "Problem reports cleared");
                build();
            });
        } else if (page.equals("art")) {
            Ui.fit(title, artLabel, Ui.dp(300), 52, 30);
            note.setText("Your own picture for its tile: a photo, a poster, a banner. Scan the code and choose one on your phone; it's cropped to fit.");
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
            if (Art.has(act, artPkg)) add("Use the app's own", "", "art:reset", () -> {
                Art.remove(act, artPkg);
                host.hud(R.drawable.ic_check, artLabel + " has its own art again");
                host.applied("art");
                build();
            });
        } else if (page.equals("sources")) {
            Ui.fit(title, "Photo sources", Ui.dp(300), 52, 30);
            note.setText("Where the wall's photographs come from. Each one on takes its turn, and no photograph is shown twice. Unsplash needs your own free key: send it from your phone.");
            toggle("Windows Spotlight", Prefs.SRC_SPOTLIGHT, "On", "Off", "sources");
            toggle("Bing", Prefs.SRC_BING, "On", "Off", "sources");
            toggle("Wikimedia Commons", Prefs.SRC_WIKIMEDIA, "On", "Off", "sources");
            toggle("Wallhaven · nature", Prefs.SRC_WALLHAVEN, "On", "Off", "sources");
            if (Gallery.unsplashKey(act).isEmpty()) {
                add("Unsplash", "Needs a key · send it from your phone", "src:unsplash", this::openUnsplashDrop);
            } else {
                toggle("Unsplash", Prefs.SRC_UNSPLASH, "On", "Off", "sources");
                add("Unsplash key", "Saved · send a new one", "src:key", this::openUnsplashDrop);
            }
        } else if (page.equals("unsplashphone")) {
            Ui.fit(title, "Unsplash key", Ui.dp(300), 52, 30);
            note.setText("Scan the code, then paste the Access Key of an app you create at unsplash.com (Your apps). It's free with an Unsplash account.");
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("tmdbphone")) {
            Ui.fit(title, "TMDB key", Ui.dp(300), 52, 30);
            note.setText("Scan the code, then paste your key from themoviedb.org (Settings, API) into the page that opens. It's free with a TMDB account.");
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("tmdbauth")) {
            Ui.fit(title, "TMDB account", Ui.dp(300), 52, 30);
            note.setText("Scan the code, sign in to TMDB if it asks, and choose Approve. Your watchlist then becomes Tonight's pick. Aura keeps only the sign-in, on this TV.");
            if (authCard != null) rows.addView(authCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("tv")) {
            buildTv();
        } else if (page.equals("keys")) {
            buildKeys();
        } else {
            Ui.fit(title, "Settings", Ui.dp(300), 52, 30);
            Weather.Now w = Weather.now(act);
            note.setText("Changes apply at once. Everything here can be undone the same way.");
            // only while Android still sends Home to another launcher: Android asks, or (Google TV,
            // Fire TV, which keep Home whatever is chosen) the Home button service takes it over
            if (!HomeRole.held(act)) {
                boolean taken = HomeKeys.on(act) && HomeKeys.takeover(act);
                add("Home button", taken ? "Opens Aura" : "Opens " + HomeRole.other(act) + " · OK to change", "role", () -> {
                    // where only setup can take Home over, straight to the page that does it
                    if (HomeRole.needsSetup(act) && !HomeKeys.set(act, true) && !HomeRole.byHand(act)) {
                        go("tv");
                        return;
                    }
                    String m = HomeRole.make(act);
                    if (m != null) host.hud(m.startsWith("Home") ? R.drawable.ic_check : R.drawable.ic_info, m);
                    build();
                    focusTag("role");
                });
            }
            section("YOU");
            add("Profile", Profiles.name(act), "profiles", host::whoIsWatching);
            add("Wallpaper", Prefs.on(act, Prefs.BLACK) ? "Pure black" : "Photographs", "wallpaper", () -> go("wallpaper"));
            int mine = Mine.count(act);
            add("From your phone", mine > 0 ? mine + (mine == 1 ? " photo" : " photos") : "Photos, GIFs, apps", "drop", this::openDrop);
            section("HOME SCREEN");
            add("Home screen", Prefs.TILE_NAMES[Math.max(0, java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act)))]
                    + " tiles", "home", () -> go("home"));
            add("Hidden apps", String.valueOf(Prefs.hidden(act).size()), "hidden", () -> go("apps"));
            add("Tonight's pick", !Prefs.on(act, Prefs.PICKS) ? "Off" : Picks.tmdb(act) ? picksSummary() : "Shows · " + Picks.genreSummary(act),
                    "picks", () -> go("picks"));
            toggle("Weather", Prefs.WEATHER, "On", "Off", "weather");
            boolean manual = Prefs.device(act).getBoolean("geo_manual", false);
            String place = w != null && !w.city.isEmpty() ? w.city : "Not found yet";
            add("Weather city", (manual ? "" : "Auto · ") + place, "city", this::askCity);
            // Auto by the TV's country, or set: OK goes round the three
            String u = Weather.unit(act);
            add("Temperature", u.equals("c") ? "Celsius · °C" : u.equals("f") ? "Fahrenheit · °F"
                    : "Auto · " + (Weather.fahrenheit(act) ? "°F" : "°C"), "wx:unit", () -> {
                String next = Weather.UNITS[(java.util.Arrays.asList(Weather.UNITS).indexOf(Weather.unit(act)) + 1) % Weather.UNITS.length];
                Prefs.device(act).edit().putString(Weather.UNIT, next).apply();
                // fetched again in the new unit straight away (temperatures and wind alike)
                host.applied("weather");
                build();
                focusTag("wx:unit");
            });
            if (manual) add("Use my connection's city", "", "auto", () -> {
                Prefs.device(act).edit().putBoolean("geo_manual", false).putLong("geo_at", 0).putLong("wx_at", 0).apply();
                host.applied("weather");
                build();
            });
            section("THIS TV");
            // Private DNS arrived with Android 9: older TVs don't get the row at all
            if (android.os.Build.VERSION.SDK_INT >= 28) add("Block ads", Shield.on(act) ? "On · " + Shield.name(act) : "Off", "shield", () -> go("shield"));
            add("Animations", animLabel(), "anim", this::cycleAnim);
            add("Set up on this TV", Adb.on(act) ? Adb.unguarded(act) ? "Connected · open to your Wi-Fi" : "Connected" : "No computer needed", "tv", () -> go("tv"));
            add("Android settings", "", "android", () -> host.open(new Intent(Settings.ACTION_SETTINGS), panel));
            section("AURA");
            add("Your setup", "Save to your phone, or bring it back", "backup", this::openBackup);
            int problems = Trouble.count(act);
            add("Problem reports", problems == 0 ? "None lately" : problems + (problems == 1 ? " note" : " notes") + " · read on your phone",
                    "trouble", this::openTrouble);
            String waiting = Updater.pending(act);
            add("Updates", waiting != null ? "Aura " + waiting + " is ready" : "Version " + Updater.installed(act), "updates", () -> {
                // opening it counts as seen: the red dots go
                Updater.seen(act);
                host.applied("update");
                go("updates");
                checkUpdate();
            });
            if (Updater.unseen(act)) {
                View dot = HomeActivity.redDot(act);
                dot.setVisibility(View.VISIBLE);
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.dp(7), Ui.dp(7), Gravity.START | Gravity.CENTER_VERTICAL);
                dl.leftMargin = Ui.dp(5);
                ((FrameLayout) rows.getChildAt(rows.getChildCount() - 1)).addView(dot, dl);
            }
            add("Remove Aura", confirmRemove ? "Press OK again" : "Puts the TV back first", "remove", this::removeAura);
        }
        if (keep != null) {
            boolean kept = false;
            for (int i = 0; i < rows.getChildCount(); i++) if (keep.equals(rows.getChildAt(i).getTag())) kept = rows.getChildAt(i).requestFocus();
            if (!kept && isOpen()) focusFirst();
        }
    }

    // ---- tonight's pick -------------------------------------------------------------------------

    private boolean picksBusy, picksAgain, confirmSignOut;
    private View keyCard, authCard;
    private String authToken;
    private final Runnable picksLater = this::refreshPicks;

    /** "Trending this week · films and shows", "Your TMDB watchlist", "Top rated · shows". */
    private String picksSummary() {
        String src = Picks.source(act);
        if (src.equals("watchlist")) return "Your TMDB watchlist";
        String what = Picks.KIND_NAMES[java.util.Arrays.asList(Picks.KINDS).indexOf(Picks.kind(act))].toLowerCase(java.util.Locale.ROOT);
        return Picks.SOURCE_NAMES[java.util.Arrays.asList(Picks.SOURCES).indexOf(src)] + " · " + what;
    }

    /** Where picks come from, what kind, which genres, how good, how new: OK on each changes it. */
    private void buildPicks() {
        Ui.fit(title, "Tonight's pick", Ui.dp(300), 52, 30);
        boolean tmdb = Picks.tmdb(act), on = Prefs.on(act, Prefs.PICKS);
        note.setText(tmdb ? "Films and shows from TMDB with your own key, to your taste. This product uses the TMDB API but is not endorsed or certified by TMDB."
                : "Top-rated shows from TVmaze, to your taste. Add your own free TMDB key for films, what's trending, only what's on your apps, and your TMDB watchlist.");
        add("Tonight's pick", on ? "On" : "Off", Prefs.PICKS, () -> {
            Prefs.set(act, Prefs.PICKS, !Prefs.on(act, Prefs.PICKS));
            host.applied("picks");
            build();
        });
        if (on) {
            String src = Picks.source(act);
            if (tmdb) {
                add("From", Picks.SOURCE_NAMES[java.util.Arrays.asList(Picks.SOURCES).indexOf(src)], "pk:src", () -> {
                    List<String> can = new java.util.ArrayList<>(java.util.Arrays.asList("top", "trending"));
                    if (Picks.signedIn(act)) can.add("watchlist");
                    Prefs.device(act).edit().putString(Picks.SOURCE, can.get((can.indexOf(Picks.source(act)) + 1) % can.size())).apply();
                    tasteChanged();
                });
                int k = java.util.Arrays.asList(Picks.KINDS).indexOf(Picks.kind(act));
                add("Films or shows", Picks.KIND_NAMES[k], "pk:kind", () -> {
                    Prefs.device(act).edit().putString(Picks.KIND, Picks.KINDS[(k + 1) % Picks.KINDS.length]).apply();
                    tasteChanged();
                });
            }
            // your watchlist is yours as it is: no filters on it
            if (!src.equals("watchlist")) {
                add("Genres", Picks.genreSummary(act), "pk:genres", () -> go("genres"));
                // trending is what's popular this week, whatever it's rated
                if (src.equals("top")) {
                    float r = Picks.rating(act);
                    add("Rated at least", String.format(java.util.Locale.getDefault(), "★ %.1f", r), "pk:rating", () -> {
                        int at = 0;
                        for (int i = 0; i < Picks.RATINGS.length; i++) if (Math.abs(Picks.RATINGS[i] - r) < 0.01f) at = i;
                        Prefs.device(act).edit().putFloat(Picks.RATING, Picks.RATINGS[(at + 1) % Picks.RATINGS.length]).apply();
                        tasteChanged();
                    });
                }
                int e = java.util.Arrays.asList(Picks.ERAS).indexOf(Picks.era(act));
                add("Years", Picks.ERA_NAMES[e], "pk:era", () -> {
                    Prefs.device(act).edit().putString(Picks.ERA, Picks.ERAS[(e + 1) % Picks.ERAS.length]).apply();
                    tasteChanged();
                });
                if (tmdb && src.equals("top")) {
                    add("Only what's on my apps", Picks.myApps(act) ? "On" : "Off", "pk:apps", () -> {
                        Prefs.device(act).edit().putBoolean(Picks.MY_APPS, !Picks.myApps(act)).apply();
                        tasteChanged();
                    });
                }
            }
            int n = Picks.count(act);
            add("Find new picks", picksBusy ? "Finding…" : n == 0 ? "None match · try fewer limits" : n > 0 ? n + " ready" : "",
                    "pk:now", this::refreshPicks);
        }
        add("TMDB key", tmdb ? "Set · OK to change" : "Optional · free at themoviedb.org", "pk:key", this::askTmdb);
        add("Paste the key from your phone", "", "pk:phone", this::openKeyDrop);
        if (tmdb) {
            add("TMDB account", !Picks.signedIn(act) ? "Sign in for your watchlist"
                    : confirmSignOut ? "Press OK again to sign out" : "Signed in as " + Picks.user(act), "pk:auth", () -> {
                if (!Picks.signedIn(act)) {
                    signIn();
                    return;
                }
                if (!confirmSignOut) {
                    confirmSignOut = true;
                    build();
                    return;
                }
                confirmSignOut = false;
                Picks.signOut(act);
                host.hud(R.drawable.ic_check, "Signed out of TMDB");
                tasteChanged();
            });
        }
    }

    /** A change of taste: the pool is made again a moment after the last change, not after each press. */
    private void tasteChanged() {
        rows.removeCallbacks(picksLater);
        rows.postDelayed(picksLater, 1200);
        build();
    }

    /** The pool made again now, off the main thread; then the home screen and Control Center follow. */
    private void refreshPicks() {
        rows.removeCallbacks(picksLater);
        if (picksBusy) {
            picksAgain = true;
            return;
        }
        picksBusy = true;
        if (isOpen()) build();
        final android.content.Context app = act.getApplicationContext();
        new SafeThread(() -> {
            boolean ok;
            try {
                ok = Picks.refresh(app, true);
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            act.runOnUiThread(() -> {
                picksBusy = false;
                if (picksAgain) {
                    picksAgain = false;
                    refreshPicks();
                    return;
                }
                host.applied("picks");
                int n = Picks.count(act);
                host.hud(done && n > 0 ? R.drawable.ic_check : R.drawable.ic_info, !done
                        ? "Couldn't reach " + (Picks.tmdb(act) ? "TMDB" : "TVmaze") + " · try again in a moment"
                        : n == 0 ? "Nothing matches all that · try fewer genres or a lower rating" : n + " picks, to your taste");
                if (isOpen() && (page.equals("picks") || page.equals("genres"))) build();
            });
        }, "picks").start();
    }

    private void askTmdb() {
        inputFor = "tmdb";
        city.setHint("Your TMDB API key, then OK · empty removes it");
        city.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        city.setText(Picks.key(act));
        city.selectAll();
        city.setVisibility(View.VISIBLE);
        city.requestFocus();
        city.post(() -> Compat.svc(act, InputMethodManager.class).showSoftInput(city, InputMethodManager.SHOW_IMPLICIT));
    }

    /** A key typed here or pasted on the phone: checked with TMDB before it's kept. */
    private void setTmdbKey(String key) {
        hideKeyboard();
        city.setVisibility(View.GONE);
        String k = key.replaceAll("\\s+", "");
        if (k.isEmpty() || k.equals(Picks.key(act))) {
            if (k.isEmpty() && Picks.tmdb(act)) {
                Picks.setKey(act, "");
                host.hud(R.drawable.ic_check, "TMDB key removed · shows from TVmaze again");
                tasteChanged();
            }
            build();
            return;
        }
        host.hud(R.drawable.ic_info, "Checking the key with TMDB…");
        new SafeThread(() -> {
            boolean ok = Picks.check(k);
            act.runOnUiThread(() -> {
                if (!ok) {
                    host.hud(R.drawable.ic_info, "TMDB didn't accept that key · copy it again from Settings, API");
                    build();
                    return;
                }
                Picks.setKey(act, k);
                host.hud(R.drawable.ic_check, "TMDB key works · films, trending and more are on");
                if (page.equals("tmdbphone")) {
                    stopDrop();
                    go("picks");
                }
                tasteChanged();
                focusTag("pk:key");
            });
        }, "tmdb-check").start();
    }

    /** The phone's page for an Unsplash key, behind a QR code: the key goes straight to the TV, no typing. */
    private void openUnsplashDrop() {
        keepDrop = false;
        stopDrop();
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onKey(String text) {
                if (!text.isEmpty()) setUnsplashKey(text.trim());
            }
        });
        drop.unsplashKey = true;
        drop.only("/key");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/key", url != null ? "SCAN WITH YOUR PHONE" : "NOT CONNECTED",
                url != null ? "Paste your Unsplash key" : "No network", url != null ? "Same Wi-Fi, no app needed" : "Connect the TV to Wi-Fi first");
        go("unsplashphone");
    }

    /** Checks the key with Unsplash, then keeps it on this TV and switches Unsplash on. */
    private void setUnsplashKey(String k) {
        host.hud(R.drawable.ic_info, "Checking the key with Unsplash…");
        final android.content.Context app = act.getApplicationContext();
        new SafeThread(() -> {
            boolean ok = Gallery.checkUnsplash(k);
            act.runOnUiThread(() -> {
                if (!ok) {
                    host.hud(R.drawable.ic_info, "Unsplash didn't accept that key · copy the Access Key again");
                    return;
                }
                Prefs.device(app).edit().putString(Prefs.UNSPLASH_KEY, k).apply();
                Prefs.set(act, Prefs.SRC_UNSPLASH, true);
                host.hud(R.drawable.ic_check, "Unsplash key works · its photographs join the wall");
                host.applied("sources");
                if (page.equals("unsplashphone")) {
                    stopDrop();
                    go("sources");
                }
                focusTag(Prefs.SRC_UNSPLASH);
            });
        }, "unsplash-check").start();
    }

    /** The phone's page for the key, behind a QR code, open while this page is. */
    private void openKeyDrop() {
        keepDrop = false;
        stopDrop();
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onKey(String text) {
                if (!text.isEmpty()) setTmdbKey(text);
            }
        });
        drop.only("/key");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/key", url != null ? "SCAN WITH YOUR PHONE" : "NOT CONNECTED",
                url != null ? "Paste your TMDB key" : "No network", url != null ? "Same Wi-Fi, no app needed" : "Connect the TV to Wi-Fi first");
        go("tmdbphone");
    }

    /** Wireless debugging's code, typed on the phone while the TV's pairing dialog stays open. */
    private void openPair() {
        keepDrop = false;
        stopDrop();
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onPairCode(String code) {
                host.hud(R.drawable.ic_info, "Pairing with the TV…");
                new SafeThread(() -> {
                    String r = Adb.pair(act, code);
                    act.runOnUiThread(() -> {
                        adbState = r;
                        host.hud(r.equals(Adb.OK) || r.equals(Adb.OPEN_DOOR) ? R.drawable.ic_check : R.drawable.ic_info,
                                r.equals(Adb.OK) || r.equals(Adb.OPEN_DOOR) ? "Paired and connected" : r.equals(Adb.DENIED)
                                        ? "That code didn't match · send the new one" : "No pairing dialog found · open it and keep it open");
                        if (Adb.on(act) && page.equals("pair")) {
                            stopDrop();
                            go("tv");
                            focusTag("adb:setup");
                        }
                    });
                }, "pair").start();
            }
        });
        drop.only("/pair");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/pair", url != null ? "SCAN WITH YOUR PHONE" : "NOT CONNECTED",
                url != null ? "Type the pairing code" : "No network", url != null ? "Same Wi-Fi, no app needed" : "Connect the TV to Wi-Fi first");
        go("pair");
    }

    /** Save or bring back your setup, from the phone, behind a QR code. */
    private void openBackup() {
        keepDrop = false;
        stopDrop();
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onRestored(int count) {
                host.hud(R.drawable.ic_check, "Your setup is back · " + count + " settings");
                stopDrop();
                // every profile, order and choice read afresh
                host.applied("profile");
                if (isOpen()) go("");
            }
        });
        drop.only("/backup", "/backup.json", "/restore");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/backup", url != null ? "SCAN WITH YOUR PHONE" : "NOT CONNECTED",
                url != null ? "Save or bring back" : "No network", url != null ? "Same Wi-Fi, no app needed" : "Connect the TV to Wi-Fi first");
        go("backup");
    }

    private boolean confirmClearTrouble;

    /** What went wrong lately, read on the phone behind a QR code (see {@link Trouble}). */
    private void openTrouble() {
        keepDrop = false;
        stopDrop();
        confirmClearTrouble = false;
        drop = new Drop(act, new Drop.Quiet());
        drop.only("/report", "/report.txt");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/report", url != null ? "SCAN WITH YOUR PHONE" : "NOT CONNECTED",
                url != null ? "Read the report" : "No network", url != null ? "Same Wi-Fi, no app needed" : "Connect the TV to Wi-Fi first");
        go("trouble");
    }

    private String artPkg, artLabel = "";

    /** An app's own picture, from the phone: opened from its tile's menu. */
    void art(String pkg, String label) {
        keepDrop = false;
        stopDrop();
        artPkg = pkg;
        artLabel = label;
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onArt(String p) {
                host.hud(R.drawable.ic_check, "New picture for " + artLabel);
                host.applied("art");
                if (page.equals("art")) build();
            }
        });
        drop.artFor = pkg;
        drop.only("/art");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/art", url != null ? "SCAN WITH YOUR PHONE" : "NOT CONNECTED",
                url != null ? "Choose a picture" : "No network", url != null ? "Same Wi-Fi, no app needed" : "Connect the TV to Wi-Fi first");
        go("art");
    }

    /** Signing in to TMDB: a token, approved on the phone from a QR code, then a session kept on the TV. */
    private void signIn() {
        authToken = null;
        authCard = qrCard(null, "ONE MOMENT", "Asking TMDB…", "");
        go("tmdbauth");
        final android.content.Context app = act.getApplicationContext();
        new SafeThread(() -> {
            String t;
            try {
                t = Picks.requestToken(app);
            } catch (Exception e) {
                t = null;
            }
            final String token = t;
            act.runOnUiThread(() -> {
                if (!page.equals("tmdbauth")) return;
                if (token == null) {
                    host.hud(R.drawable.ic_info, "Couldn't reach TMDB · check the key and the connection");
                    back();
                    return;
                }
                authToken = token;
                authCard = qrCard(Picks.approveUrl(token), "SCAN WITH YOUR PHONE", "Approve Aura on TMDB", "Waiting for you to approve…");
                build();
                waitForApproval(token, 0);
            });
        }, "tmdb-token").start();
    }

    /** Asks every three seconds whether the token was approved, for five minutes at most. */
    private void waitForApproval(String token, int tries) {
        final android.content.Context app = act.getApplicationContext();
        rows.postDelayed(() -> {
            if (!token.equals(authToken) || !isOpen()) return;
            new SafeThread(() -> {
                boolean ok;
                try {
                    ok = Picks.finishSignIn(app, token);
                } catch (org.json.JSONException e) {
                    ok = false;
                }
                final boolean done = ok;
                act.runOnUiThread(() -> {
                    if (!token.equals(authToken)) return;
                    if (done) {
                        authToken = null;
                        host.hud(R.drawable.ic_check, "Signed in as " + Picks.user(act) + " · picks from your watchlist");
                        go("picks");
                        tasteChanged();
                        focusTag("pk:auth");
                    } else if (tries < 100) {
                        waitForApproval(token, tries + 1);
                    }
                });
            }, "tmdb-session").start();
        }, 3000);
    }

    /** A QR code with what it's for beside it. */
    private View qrCard(String url, String kicker, String head, String sub) {
        LinearLayout card = new LinearLayout(act);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(2), 0, 0, 0);
        Qr qr = url != null ? Qr.tryEncode(url) : null;
        View code = qr != null ? new Code(act, qr) : new View(act);
        card.addView(code, new LinearLayout.LayoutParams(Ui.dp(184), Ui.dp(184)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView k = Ui.caps(act, 7.5f, 0x99FFFFFF, 0.28f);
        k.setText(kicker);
        col.addView(k);
        TextView t = Ui.text(act, 15, Ui.semibold, 0xFFFFFFFF);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setText(head);
        col.addView(t, lp(0, 14));
        TextView s = Ui.text(act, 12, Ui.regular, 0xA6FFFFFF);
        s.setSingleLine(false);
        s.setMaxLines(3);
        s.setText(sub);
        col.addView(s, lp(0, 6));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = Ui.dp(20);
        card.addView(col, cl);
        return card;
    }


    /**
     * Everything Aura changed is handed back (Home, the screensaver, Block ads, its Home button and
     * standby admin), then Android's own uninstall asks. Without this, an app holding a device
     * admin can't be uninstalled at all: Android's settings refuse.
     */
    private void removeAura() {
        if (Safety.originalDisabled(act) && !Adb.on(act)) {
            confirmRemove = false;
            host.hud(R.drawable.ic_info, "Connect in Set up on this TV first so the TV's own home screen can be turned back on");
            go("tv");
            return;
        }
        if (!confirmRemove) {
            confirmRemove = true;
            build();
            return;
        }
        confirmRemove = false;
        final android.content.Context app = act.getApplicationContext();
        Power.setSleep(app, 0);
        if (Shield.allowed(app) && Shield.on(app)) Shield.set(app, false);
        if (Ambient.canSet(app) && Ambient.chosen(app)) Ambient.choose(app, false);
        HomeKeys.set(app, false);
        android.app.admin.DevicePolicyManager dpm = Compat.svc(app, android.app.admin.DevicePolicyManager.class);
        android.content.ComponentName admin = new android.content.ComponentName(app, SleepAdmin.class);
        if (dpm != null && dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin);
        host.hud(R.drawable.ic_info, "Handing the TV back…");
        final String home = Safety.original(app);
        new SafeThread(() -> {
            if (Adb.on(app)) Safety.restoreHome(app);
            // a moment for Android to let go of the admin before it's asked to uninstall
            android.os.SystemClock.sleep(800);
            act.runOnUiThread(() -> {
                try {
                    act.startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + act.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    host.hud(R.drawable.ic_info, "Uninstall Aura in Android's settings, Apps");
                }
            });
        }, "remove").start();
    }

    /** Remote buttons: press one, pick an app; the list of those given so far, each removable. */
    private void buildKeys() {
        Ui.fit(title, "Remote buttons", Ui.dp(300), 52, 30);
        if (newKey != null) {
            final String id = HomeKeys.id(newKey[0], newKey[1]);
            note.setText("Which app should " + HomeKeys.keyName(id) + " open?");
            for (AppRepo.App a : host.apps()) {
                add(a.label, "", "ka:" + a.pkg, () -> {
                    HomeKeys.give(act, newKey[0], newKey[1], a.pkg);
                    host.hud(R.drawable.ic_check, HomeKeys.keyName(id) + " opens " + a.label);
                    newKey = null;
                    build();
                    focusTag("key:" + id);
                });
            }
            return;
        }
        note.setText(teachingKey ? "Press the button on the remote now."
                : "Give any button on the remote an app: a shortcut button you never use, or a colour button. It works inside apps, and on this screen wherever Android lets the button through.");
        for (java.util.Map.Entry<String, String> k : HomeKeys.keys(act).entrySet()) {
            final String tag = "key:" + k.getKey(), id = k.getKey();
            add(HomeKeys.keyName(id), tag.equals(confirmKey) ? "Press OK again to remove" : appLabel(k.getValue()), tag, () -> {
                if (!tag.equals(confirmKey)) {
                    confirmKey = tag;
                    build();
                    return;
                }
                confirmKey = null;
                HomeKeys.forget(act, id);
                build();
                focusTag("keys:add");
            });
        }
        add("Add a button", teachingKey ? "Press it now…" : "", "keys:add", this::teachKey);
    }

    private String appLabel(String pkg) {
        for (AppRepo.App a : host.apps()) if (a.pkg.equals(pkg)) return a.label;
        return pkg;
    }

    /** Listens for the next button (Home button in apps does the listening), for thirty seconds. */
    private void teachKey() {
        if (teachingKey) return;
        boolean ok = HomeKeys.teach((code, scan) -> act.runOnUiThread(() -> {
            if (!teachingKey) return;
            teachingKey = false;
            newKey = new int[]{code, scan};
            if (page.equals("keys")) go("keys");
        }));
        if (!ok) {
            host.hud(R.drawable.ic_info, "Switch on Home button in apps first");
            return;
        }
        teachingKey = true;
        build();
        rows.postDelayed(() -> {
            if (!teachingKey) return;
            teachingKey = false;
            HomeKeys.stopTeaching();
            if (page.equals("keys")) build();
        }, 30_000);
    }

    /** Set up on this TV: connect to the TV's own debugging port, then grant what a PC would. */
    private void buildTv() {
        Ui.fit(title, "Set up on this TV", Ui.dp(300), 52, 30);
        note.setText(adbNote());
        add("Connect", adbState.equals("asking") ? "Waiting for you…" : Adb.on(act) ? "Connected" : "Not connected", "adb:connect", this::adbConnect);
        if (setupNote.isEmpty()) setupNote = Prefs.device(act).getString("setup_note", "");
        if (Adb.on(act)) add("Switch everything on", setupNote.isEmpty() ? Adb.setup(act).length + " extras" : setupNote, "adb:setup", this::adbSetup);
        if (Adb.on(act) && Safety.hasGoogleTvHome(act)) {
            if (Safety.googleTvHomeOn(act)) {
                add("Retire Google TV Home", confirmRetire ? "Press OK again · voice search & side panel stop" : "Voice search, side panel & accounts stop",
                        "adb:gtv", () -> retireGoogleHome(true));
            } else {
                add("Bring back Google TV Home", "Restores voice search, side panel & accounts", "adb:gtv", () -> retireGoogleHome(false));
            }
        }
        // newer TVs without plain network debugging: wireless debugging, paired with a code typed on the phone
        if (!Adb.on(act) && Adb.wireless()) add("Use wireless debugging", "Pair with a code", "adb:pair", this::openPair);
        // the open door shuts with a setting Aura may change once setup has granted it
        if (Adb.on(act) && Adb.unguarded(act) && Compat.granted(act, android.Manifest.permission.WRITE_SECURE_SETTINGS)) {
            add("Turn network debugging off", confirmAdbOff ? "Press OK again" : "Closes the open door", "adb:off", this::adbOff);
        }
        boolean dev = Settings.Global.getInt(act.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        add("Developer options", dev ? "USB debugging is here" : "Off · About, press Build 7 times", "adb:dev", () -> {
            try {
                // not switched on yet: the About page, where pressing Build seven times does it
                host.open(new Intent(dev ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS : Settings.ACTION_DEVICE_INFO_SETTINGS), panel);
            } catch (RuntimeException e) {
                host.open(new Intent(Settings.ACTION_SETTINGS), panel);
            }
        });
    }

    private void toggle(String label, String key, String onText, String offText, String what) {
        add(label, Prefs.on(act, key) ? onText : offText, key, () -> {
            Prefs.set(act, key, !Prefs.on(act, key));
            host.applied(what);
            build();
            // rebuilding the app grid (tiles, names) takes focus away: stay on the row you pressed
            focusTag(key);
        });
    }

    private void focusTag(String tag) {
        for (int i = 0; i < rows.getChildCount(); i++) if (tag.equals(rows.getChildAt(i).getTag())) rows.getChildAt(i).requestFocus();
    }

    // ---- from your phone ---------------------------------------------------------------------

    // ---- animation speed ------------------------------------------------------------------------

    private static final String[] ANIM_KEYS = {Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE};

    private float animScale() {
        return Settings.Global.getFloat(act.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    private String animLabel() {
        float s = animScale();
        return s <= 0f ? "Off" : s < 0.75f ? "Fast" : s > 1.25f ? "Slow" : "Normal";
    }

    /**
     * The whole TV's animation speed, the developer-options trick: Normal, Fast (half as long:
     * every app and menu feels quicker), or Off. Aura's own motion follows the same setting.
     */
    private void cycleAnim() {
        if (!Shield.allowed(act)) {
            host.hud(R.drawable.ic_info, "Connect in Set up on this TV once to let Aura change this");
            go("tv");
            return;
        }
        float s = animScale(), next = s > 0.75f ? 0.5f : s > 0f ? 0f : 1f;
        try {
            for (String k : ANIM_KEYS) Settings.Global.putFloat(act.getContentResolver(), k, next);
        } catch (SecurityException e) {
            host.hud(R.drawable.ic_info, "The TV didn't allow that change");
            return;
        }
        host.hud(R.drawable.ic_bolt, next == 0.5f ? "Everything moves twice as fast" : next == 0f ? "Animations off" : "Animations back to normal");
        build();
    }

    // ---- set up on this TV ----------------------------------------------------------------------

    private boolean confirmRetire;

    /** Switches Google TV's own launcher (and its setup recovery home) off or back on over ADB. */
    private void retireGoogleHome(boolean retire) {
        if (retire && !confirmRetire) {
            confirmRetire = true;
            build();
            focusTag("adb:gtv");
            return;
        }
        confirmRetire = false;
        final android.content.Context app = act.getApplicationContext();
        new SafeThread(() -> {
            Boolean took = Safety.retireGoogleHome(app, retire);
            act.runOnUiThread(() -> {
                if (took == null) {
                    host.hud(R.drawable.ic_info, "Couldn't reach the TV's debugging service · Connect first");
                } else if (!took) {
                    host.hud(R.drawable.ic_info, "Android didn't allow that change");
                } else if (retire) {
                    host.hud(R.drawable.ic_check, "Google TV Home retired · Bring back Google TV Home restores voice search & accounts");
                } else {
                    host.hud(R.drawable.ic_check, "Google TV Home is back · Voice search restored");
                }
                if (page.equals("tv")) {
                    build();
                    focusTag("adb:gtv");
                }
            });
        }, "gtv-home").start();
    }

    private String adbNote() {
        if (confirmRetire) {
            return "Voice search (Google Assistant in YouTube & apps), Google TV's side panel, profiles and some account screens stop working while Google TV Home is retired. Press OK again to retire it, or bring it back here any time.";
        }
        String s = adbState.isEmpty() && Adb.on(act) ? Adb.unguarded(act) ? Adb.OPEN_DOOR : Adb.OK : adbState;
        switch (s) {
            case "asking":
                return "If Android asks on the screen, choose Always allow from this computer, then OK.";
            case Adb.OK:
                return "Aura is connected to this TV. Switch everything on grants what the PC setup grants, and closing an app in the switcher now stops it fully.";
            case Adb.OPEN_DOOR:
                return "Careful: this TV let Aura in without asking. Anything on your Wi-Fi can control it the same way. Switch everything on, then turn network debugging off.";
            case Adb.OFF:
                return "Debugging is off. In Developer options switch on USB debugging (Network debugging on some TVs), then Connect again. No Developer options? Android settings, System, About: press OK on the build seven times.";
            case Adb.DENIED:
                return "Android didn't let Aura in in time. If you chose Always allow, Connect again: it goes straight in now. Otherwise Connect, and choose Always allow when it asks.";
            case Adb.FAILED:
                return "Couldn't reach the TV's debugging service. Try again in a moment.";
            default:
                return (HomeRole.stubborn(act) && !HomeRole.held(act) ? HomeRole.other(act) + " keeps the Home button for itself: setup hands it to Aura. " : "")
                        + "Aura switches on its extras from the TV itself, no computer needed, through USB debugging, the way a PC would. Android asks once: choose Always allow.";
        }
    }

    private void adbConnect() {
        if (adbState.equals("asking") || setupRunning) return;
        adbState = "asking";
        build();
        new SafeThread(() -> {
            String r = Adb.connect(act);
            act.runOnUiThread(() -> {
                adbState = r;
                if (r.equals(Adb.OK)) host.hud(R.drawable.ic_check, "Connected to this TV");
                else if (r.equals(Adb.OPEN_DOOR)) host.hud(R.drawable.ic_info, "Connected, but the TV never asked");
                if (page.equals("tv")) {
                    build();
                    focusTag(Adb.on(act) ? "adb:setup" : "adb:connect");
                }
            });
        }, "adb-connect").start();
    }

    /** Runs the setup steps one by one, the row counting along; a step that fails is named. */
    private void adbSetup() {
        if (setupRunning) return;
        setupRunning = true;
        setupNote = "0 of " + Adb.setup(act).length;
        build();
        Adb.runSetup(act, new Adb.Progress() {
            @Override
            public void step(int i, int n, String name) {
                final String now = (i + 1) + " of " + n + (name.equals("Speed") ? " · compiling, about a minute" : " · " + name);
                act.runOnUiThread(() -> {
                    setupNote = now;
                    if (page.equals("tv")) build();
                });
            }

            @Override
            public void restarting(int ok, int n, List<String> failed) {
                Prefs.device(act).edit().putString("setup_note", failed.isEmpty() ? "Done · all " + n
                        : ok + " of " + n + " · not " + TextUtils.join(", ", failed)).commit();
            }

            @Override
            public void done(int ok, int n, List<String> failed) {
                act.runOnUiThread(() -> {
                    setupRunning = false;
                    setupNote = ok == n ? "Done · all " + ok : ok + " of " + n + " · not " + TextUtils.join(", ", failed);
                    Prefs.device(act).edit().putString("setup_note", setupNote).apply();
                    host.hud(R.drawable.ic_check, ok == n ? "Everything is switched on" : ok + " of " + n + " switched on");
                    if (page.equals("tv")) {
                        build();
                        focusTag("adb:setup");
                    }
                });
            }
        });
    }

    /** Debugging off for the whole TV: nothing on the Wi-Fi gets in, Aura's own link included. */
    private void adbOff() {
        if (!confirmAdbOff) {
            confirmAdbOff = true;
            build();
            return;
        }
        confirmAdbOff = false;
        try {
            Settings.Global.putInt(act.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (SecurityException e) {
            host.hud(R.drawable.ic_info, "The TV didn't allow that change");
            return;
        }
        Prefs.device(act).edit().putBoolean("adb_ok", false).putBoolean("adb_open", false).apply();
        adbState = "";
        host.hud(R.drawable.ic_check, "Network debugging off · the door is shut");
        build();
        focusTag("adb:connect");
    }

    // ---- block ads ----------------------------------------------------------------------------

    /** "In use", else the speed measured here (and whether it blocks), else what it's for. */
    private String filterValue(String host, String about, String current) {
        Long ms = probed.get(host);
        String speed = ms == null ? null : ms < 0 ? "Unreachable" : ms + " ms" + (blocksAds.contains(host) ? "" : " · no blocking");
        if (host.equals(current)) return "In use" + (speed != null ? "  ·  " + speed : "");
        return speed != null ? speed : about;
    }

    private void pickFilter(String hostname, String name) {
        if (shieldNote != null) return;
        boolean was = Shield.on(act);
        Shield.choose(act, hostname);
        if (!was) {
            host.hud(R.drawable.ic_check, name + " chosen  ·  switch Block ads on to use it");
            build();
            return;
        }
        // already blocking: the TV moves to the new filter now, and it's checked like a fresh switch-on
        checkShield(name);
    }

    private void askDns() {
        inputFor = "dns";
        city.setHint("A DNS-over-TLS hostname, e.g. dns.example.com");
        city.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        city.setText("");
        city.setVisibility(View.VISIBLE);
        city.requestFocus();
        city.post(() -> Compat.svc(act, InputMethodManager.class).showSoftInput(city, InputMethodManager.SHOW_IMPLICIT));
    }

    /** A hostname you typed: tried from the TV first, so a typo can never cut the internet. */
    private void setCustomDns(String hostname) {
        if (hostname.isEmpty()) return;
        hideKeyboard();
        city.setVisibility(View.GONE);
        String h = hostname.replaceFirst("^(tls|https)://", "").replaceAll("/.*$", "").toLowerCase(java.util.Locale.ROOT);
        host.hud(R.drawable.ic_info, "Trying " + h + "…");
        new SafeThread(() -> {
            Shield.Probe p = Shield.probe(h);
            act.runOnUiThread(() -> {
                if (p == null) {
                    host.hud(R.drawable.ic_info, "Couldn't reach " + h + " for private DNS");
                    build();
                    return;
                }
                probed.put(h, p.ms);
                if (p.blocks) blocksAds.add(h);
                pickFilter(h, h);
                build();
            });
        }, "dns-custom").start();
    }

    /** Measures every filter from this TV and moves focus to the fastest one that blocks ads. */
    private void findFastest() {
        if (probing) return;
        probing = true;
        build();
        new SafeThread(() -> {
            String best = null;
            long bestMs = Long.MAX_VALUE;
            for (String[] f : Shield.FILTERS) {
                Shield.Probe p = Shield.probe(f[0]);
                final long ms = p == null ? -1 : p.ms;
                final boolean blocks = p != null && p.blocks;
                act.runOnUiThread(() -> {
                    probed.put(f[0], ms);
                    if (blocks) blocksAds.add(f[0]);
                    if (page.equals("shield")) build();
                });
                if (p != null && p.blocks && p.ms < bestMs) {
                    bestMs = p.ms;
                    best = f[1];
                }
            }
            final String b = best;
            final long bm = bestMs;
            act.runOnUiThread(() -> {
                probing = false;
                host.hud(R.drawable.ic_check, b != null ? "Fastest from this TV: " + b + "  ·  " + bm + " ms" : "None of the filters answered");
                if (page.equals("shield")) build();
                for (String[] f : Shield.FILTERS) if (f[1].equals(b)) focusTag("dns:" + f[0]);
            });
        }, "dns-probe").start();
    }

    private void toggleShield() {
        if (shieldNote != null) return;
        if (!Shield.allowed(act)) {
            host.hud(R.drawable.ic_info, "Connect in Set up on this TV once to let Aura block ads");
            go("tv");
            return;
        }
        if (Shield.on(act)) {
            Shield.set(act, false);
            host.hud(R.drawable.ic_check, "Ads are no longer blocked");
            build();
            return;
        }
        if (!Shield.set(act, true)) {
            host.hud(R.drawable.ic_info, "This TV didn't allow it: set 94.140.14.14 as DNS in its Wi-Fi settings instead");
            build();
            return;
        }
        checkShield(Shield.name(act));
    }

    /** Confirms the TV is online through the filter; if not, tries another reachable filter or switches back. */
    private void checkShield(String name) {
        shieldNote = "Checking…";
        build();
        new SafeThread(() -> {
            String used = name;
            String tried = Shield.host(act);
            boolean ok = Shield.verify(act), port853 = ok;
            if (!ok) {
                for (String[] f : Shield.FILTERS) {
                    if (f[0].equals(tried)) continue;
                    Shield.Probe p = Shield.probe(f[0]);
                    if (p != null && p.blocks) {
                        port853 = true;
                        Shield.choose(act, f[0]);
                        Shield.set(act, true);
                        used = f[1];
                        if (Shield.verify(act)) ok = true;
                        break;
                    }
                }
            }
            // never leave the TV without internet: switch straight back if no filter works
            if (!ok) {
                Shield.choose(act, tried);
                Shield.set(act, false);
            }
            final boolean done = ok, dot = port853;
            final String who = used;
            act.runOnUiThread(() -> {
                shieldNote = null;
                host.hud(done ? R.drawable.ic_check : R.drawable.ic_info, done ? "Blocking ads with " + who
                        : dot ? "This TV ignores Private DNS · set 94.140.14.14 in Wi-Fi settings instead"
                        : "Couldn't reach " + name + " (port 853 blocked), so blocking is off again");
                build();
            });
        }, "shield").start();
    }

    // ---- updates ------------------------------------------------------------------------------

    private void checkUpdate() {
        updateState = "checking";
        build();
        new SafeThread(() -> {
            Updater.Release r = null;
            String state;
            try {
                r = Updater.latest();
                state = r != null && Updater.newer(r.version, Updater.installed(act)) ? "available" : "latest";
            } catch (Exception e) {
                state = "error";
            }
            final Updater.Release found = r;
            final String s = state;
            // what the page just found counts too: home's dot and message stay in step
            if (found != null) Prefs.device(act).edit().putString("update_ver", found.version).apply();
            act.runOnUiThread(() -> {
                release = found;
                updateState = s;
                updateNote = "Couldn't reach GitHub. Check the connection, then try again.";
                if (page.equals("updates")) build();
            });
        }, "update-check").start();
    }

    private void installUpdate() {
        if (release == null || updateState.equals("downloading")) return;
        if (!Updater.allowed(act)) {
            // Android asks once whether Aura may install apps. Fire TV keeps the switch in its
            // Developer options, under a name of its own, and has no "Special app access" page
            boolean fire = Trouble.fireTv(act);
            // the update stays offered: back from allowing it, Install is right there
            installHint = fire
                    ? "Fire TV asks once whether Aura may install updates: Settings → My Fire TV → Developer options → Install unknown apps → Aura, then On. Then come back here and choose Install."
                    : "Android asks once whether Aura may install updates: allow it on the page that opens (Settings → Apps → Special app access → Install unknown apps), then come back here and choose Install.";
            build();
            boolean opened = false;
            for (Intent i : new Intent[]{
                    new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", android.net.Uri.parse("package:" + act.getPackageName())),
                    new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)}) {
                if (opened || i.resolveActivity(act.getPackageManager()) == null) continue;
                try {
                    HomeKeys.letTvHomePass();
                    act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    opened = true;
                } catch (RuntimeException ignored) {
                }
            }
            if (!opened) host.hud(R.drawable.ic_info, fire ? "My Fire TV → Developer options → Install unknown apps" : "Settings → Apps → Special app access");
            return;
        }
        final Updater.Release r = release;
        updateState = "downloading";
        updateNote = "Starting…";
        build();
        new SafeThread(() -> {
            try {
                Updater.install(act, r, (done, total) -> {
                    String t = total > 0 ? "Downloading " + Math.round(100f * done / total) + "%" : "Downloading…";
                    act.runOnUiThread(() -> {
                        if (t.equals(updateNote)) return;
                        updateNote = t;
                        if (page.equals("updates")) build();
                    });
                });
                act.runOnUiThread(() -> {
                    updateState = "available";
                    updateNote = "";
                    if (page.equals("updates")) build();
                    // the TV's installer should answer within seconds; one that never does (seen on
                    // Fire TV) or that refuses gets a way round it rather than a button that does nothing
                    rows.removeCallbacks(installerSilent);
                    rows.postDelayed(installerSilent, 25_000);
                });
            } catch (Exception e) {
                Trouble.caught("update", e);
                act.runOnUiThread(() -> {
                    updateState = "error";
                    updateNote = "The download stopped part-way. Check the connection, then try again.";
                    if (page.equals("updates")) build();
                });
            }
        }, "update").start();
    }

    /** How to let Aura install updates on this TV, shown above the notes once Android said no. */
    private String installHint;

    /** After an update was handed to the installer: no answer, or a refusal, and the Downloader way is offered. */
    private final Runnable installerSilent = () -> {
        String why = Updater.refused;
        if (Updater.answeredAt != 0 && why == null) return;
        updateState = "error";
        updateNote = (why != null ? "The TV wouldn't install it: " + why + "." : "The TV's installer didn't answer.")
                + "\n\nInstall it with the free Downloader app instead: type the code 4172997 there. Your settings stay as they are.";
        Trouble.note("UPDATE", why != null ? "refused: " + why : "the installer never answered");
        if (page.equals("updates")) build();
    };

    private boolean keepDrop;
    private final Runnable stopDropIdle = this::stopDropNow;

    private void openDrop() {
        page = "drop";
        confirmClear = false;
        rows.removeCallbacks(stopDropIdle);
        if (keepDrop && drop != null && dropCard != null) {
            if (dropCard.getParent() instanceof ViewGroup) ((ViewGroup) dropCard.getParent()).removeView(dropCard);
            build();
            focusTag(Prefs.MINE_ONLY);
            return;
        }
        stopDropNow();
        keepDrop = true;
        drop = new Drop(act, new Drop.Listener() {
            @Override
            public void onProgress(String name, long done, long total, int index, int count) {
                dropTitle.setText(count > 1 ? "Receiving " + (index + 1) + " of " + count : "Receiving " + shorten(name));
                dropSub.setText(Math.round(100f * done / total) + "%  ·  " + mb(done) + " of " + mb(total));
                dropMeter.setVisibility(View.VISIBLE);
                dropMeter.set(done / (float) total);
            }

            @Override
            public void onAdded(JSONObject e, int index, int count) {
                host.backdrop().show(e.optString("path"));
                dropTitle.setText("“" + e.optString("place") + "” is on your wall");
                int n = Mine.count(act);
                dropSub.setText(index + 1 < count ? "More on the way" : n + (n == 1 ? " photo" : " photos") + " from your phone");
                if (index + 1 >= count) dropMeter.setVisibility(View.INVISIBLE);
                thumb(e.optString("id"));
                build();
            }

            @Override
            public void onFailed(String name, String why) {
                dropTitle.setText("Couldn't add " + shorten(name));
                dropSub.setText(why);
                dropMeter.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onChanged() {
                host.applied("photos");
                // a photo removed from the phone leaves the card here too
                for (int i = dropThumbs.getChildCount() - 1; i >= 0; i--) {
                    Object id = dropThumbs.getChildAt(i).getTag();
                    if (id instanceof String && !Mine.thumb(act, (String) id).isFile()) dropThumbs.removeViewAt(i);
                }
                int n = Mine.count(act);
                dropTitle.setText(n == 0 ? "Waiting for your phone" : "Removed from your wall");
                dropSub.setText(n == 0 ? "Same Wi-Fi, no app needed" : n + (n == 1 ? " photo" : " photos") + " from your phone");
                build();
            }

            @Override
            public boolean onType(String text) {
                if (keepDrop) {
                    rows.removeCallbacks(stopDropIdle);
                    rows.postDelayed(stopDropIdle, 10 * 60_000L);
                }
                if (dropTitle != null && isOpen() && page.equals("drop")) {
                    dropTitle.setText("Typed from your phone");
                    dropSub.setText("“" + shorten(text) + "”");
                }
                return host.type(text);
            }

            @Override
            public void onApk(String name) {
                dropTitle.setText("Install " + shorten(name.replaceFirst("(?i)\\.apk$", "")) + "?");
                dropSub.setText("Confirm on the TV to install it");
                dropMeter.setVisibility(View.INVISIBLE);
            }
        });
        dropCard = dropCard(drop.start());
        build();
        focusTag(Prefs.MINE_ONLY);
    }

    private void stopDrop() {
        if (keepDrop && drop != null) {
            rows.removeCallbacks(stopDropIdle);
            rows.postDelayed(stopDropIdle, 10 * 60_000L);
            return;
        }
        stopDropNow();
    }

    /** Settings is going away for good (a profile switch, Aura closing): no phone page stays open. */
    void shutDrop() {
        stopDropNow();
    }

    private void stopDropNow() {
        keepDrop = false;
        rows.removeCallbacks(stopDropIdle);
        dropCard = null;
        if (drop != null) drop.stop();
        drop = null;
    }

    /** The code to scan beside what's happening: waiting, a progress bar, then what just arrived. */
    private View dropCard(String url) {
        LinearLayout card = new LinearLayout(act);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(2), 0, 0, 0);
        Qr qr = url != null ? Qr.tryEncode(url) : null;
        View code = qr != null ? new Code(act, qr) : new View(act);
        card.addView(code, new LinearLayout.LayoutParams(Ui.dp(184), Ui.dp(184)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = Ui.caps(act, 7.5f, 0x99FFFFFF, 0.28f);
        kicker.setText(url != null ? "SCAN WITH YOUR CAMERA" : "NOT CONNECTED");
        col.addView(kicker);
        TextView addr = Ui.text(act, 11.5f, Ui.medium, 0x8CFFFFFF);
        addr.setText(url != null ? "or open " + url.replaceFirst("^http://", "") : "Connect the TV to Wi-Fi first");
        addr.setSingleLine(false);
        addr.setMaxLines(2);
        col.addView(addr, lp(0, 6));
        dropTitle = Ui.text(act, 15, Ui.semibold, 0xFFFFFFFF);
        dropTitle.setEllipsize(TextUtils.TruncateAt.END);
        dropTitle.setText(url != null ? "Waiting for your phone" : "No network");
        col.addView(dropTitle, lp(0, 22));
        dropSub = Ui.text(act, 12, Ui.regular, 0xA6FFFFFF);
        dropSub.setEllipsize(TextUtils.TruncateAt.END);
        dropSub.setText(url != null ? "Same Wi-Fi, no app needed" : "");
        col.addView(dropSub, lp(0, 6));
        dropMeter = new HomeActivity.Meter(act);
        dropMeter.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4));
        ml.topMargin = Ui.dp(10);
        col.addView(dropMeter, ml);
        dropThumbs = new LinearLayout(act);
        col.addView(dropThumbs, lp(0, 12));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = Ui.dp(20);
        card.addView(col, cl);
        return card;
    }

    /** The newest arrivals, three at most, newest on the left. */
    private void thumb(String id) {
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(Mine.thumb(act, id).getPath());
        if (b == null) return;
        android.widget.ImageView iv = new android.widget.ImageView(act);
        iv.setTag(id);
        iv.setImageBitmap(b);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(8));
            }
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(36));
        l.rightMargin = Ui.dp(8);
        dropThumbs.addView(iv, 0, l);
        while (dropThumbs.getChildCount() > 3) dropThumbs.removeViewAt(3);
        iv.setAlpha(0f);
        iv.setTranslationY(Ui.dp(8));
        iv.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Ui.EMPHASIZED).start();
    }

    private static String shorten(String name) {
        String n = name == null || name.isEmpty() ? "a photo" : name;
        return n.length() > 24 ? n.substring(0, 21) + "…" : n;
    }

    private static String mb(long bytes) {
        return bytes < 1 << 20 ? Math.max(1, bytes >> 10) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576f);
    }

    /** A QR code drawn dark on a white card with its quiet zone, crisp at any size. */
    static final class Code extends View {
        private final Qr qr;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private int built = -1;

        Code(android.content.Context c, Qr q) {
            super(c);
            qr = q;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = Math.min(getWidth(), getHeight());
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(0, 0, w, w, Ui.dpf(18), Ui.dpf(18), p);
            if (built != (int) w) {
                built = (int) w;
                path.reset();
                // a quiet zone of three modules on each side, whole pixels per module so edges stay sharp
                int quiet = 3, cells = qr.size + quiet * 2;
                float m = (float) Math.floor(w / cells), off = (w - m * qr.size) / 2f;
                for (int y = 0; y < qr.size; y++) {
                    for (int x = 0; x < qr.size; x++) {
                        if (qr.at(x, y)) path.addRect(off + x * m, off + y * m, off + (x + 1) * m, off + (y + 1) * m,
                                android.graphics.Path.Direction.CW);
                    }
                }
            }
            p.setColor(0xFF0B0E16);
            p.setAntiAlias(false);
            c.drawPath(path, p);
            p.setAntiAlias(true);
        }
    }

    private String focusedTag() {
        View f = act.getCurrentFocus();
        return f != null && f.getParent() == rows ? (String) f.getTag() : null;
    }

    /** One choice: its name on the left, its value on the right; OK changes it. */
    private void add(String label, String value, String tag, Runnable run) {
        add(label, value, tag, null, run);
    }

    /** The same, led by a small picture (a photo's thumbnail). */
    private android.graphics.Bitmap photoThumb(String id) {
        android.graphics.Bitmap b = thumbs.get(id);
        if (b == null && (b = android.graphics.BitmapFactory.decodeFile(Mine.thumb(act, id).getPath())) != null) thumbs.put(id, b);
        return b;
    }

    private void add(String label, String value, String tag, android.graphics.Bitmap thumb, Runnable run) {
        Pad p = new Pad(act, null, 16, 0f);
        p.plainColor = 0x1AFFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.03f;
        p.setTag(tag);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(thumb != null ? 6 : 16), 0, Ui.dp(16), 0);
        if (thumb != null) {
            android.widget.ImageView iv = new android.widget.ImageView(act);
            iv.setImageBitmap(thumb);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(7));
                }
            });
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(28));
            tl.rightMargin = Ui.dp(12);
            r.addView(iv, tl);
        }
        TextView name = p.tint(Ui.text(act, 13.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        name.setText(label);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!value.isEmpty()) {
            TextView v = p.tint(Ui.text(act, 12, Ui.medium, 0xFFFFFFFF), 0.7f);
            v.setText(value);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setMaxWidth(Ui.dp(170));
            r.addView(v);
        }
        p.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onClick = run;
        p.setOnFocusChangeListener((v, has) -> {
            if (has) explain(tag);
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(35));
        l.topMargin = Ui.dp(4);
        rows.addView(p, l);
    }

    /** A quiet label over a group of rows; never focused, so the remote moves straight past it. */
    private void section(String name) {
        TextView t = Ui.caps(act, 7.5f, 0x8CFFFFFF, 0.3f);
        t.setText(name);
        t.setPadding(Ui.dp(16), 0, 0, 0);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Ui.dp(rows.getChildCount() == 0 ? 2 : 16);
        l.bottomMargin = Ui.dp(2);
        rows.addView(t, l);
    }

    /** The focused row's sentence beside the list, faded across; rows that need none leave it empty. */
    private void explain(String tag) {
        String text = HELP.get(tag);
        if (text == null) text = "";
        if (text.contentEquals(help.getText())) return;
        final String next = text;
        help.animate().cancel();
        help.animate().alpha(0f).setDuration(90).setStartDelay(0).withEndAction(() -> {
            help.setText(next);
            help.animate().alpha(1f).setDuration(180).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }).start();
    }

    private static final java.util.Map<String, String> HELP = new java.util.HashMap<>();

    static {
        // Settings
        HELP.put("role", "Which home screen the remote's Home button opens. Aura can take it over from the TV's own.");
        HELP.put("profiles", "Everyone gets their own app order, wallpaper and widgets. Opens Who's watching, to switch.");
        HELP.put("wallpaper", "The picture behind your apps: fresh photographs every day, your own photos, or pure black.");
        HELP.put("drop", "Scan a code with your phone to send photos, GIFs or apps to the TV. No cable, no account.");
        HELP.put("home", "How your apps look, and what else is on the home screen: style, tiles, widgets, app cards.");
        HELP.put("hidden", "Apps you've hidden from the home screen. Bring any of them back from here.");
        HELP.put("picks", "A film or show worth your evening, chosen fresh each day. Pick genres, or use your TMDB watchlist.");
        HELP.put(Prefs.WEATHER, "The temperature and the sky in the corner of the home screen.");
        HELP.put("city", "Where the weather is for. Found from your internet connection, unless you type a city.");
        HELP.put("auto", "Go back to finding your city from your internet connection.");
        HELP.put("shield", "Blocks ads and trackers for the whole TV, in every app, through a private DNS filter.");
        HELP.put("anim", "How fast every menu and app on the TV moves. Fast makes the whole TV feel quicker.");
        HELP.put("tv", "Lets Aura switch on its extras itself (ad blocking, the Home button, a faster TV) through the TV's own debugging. Once, no computer.");
        HELP.put("android", "The TV's own settings: network, picture, sound, accounts.");
        HELP.put("w:forgive", "Cards you turned down from the Smart Stack, and ones it rested after you passed them by. OK lets them all come back.");
        HELP.put("wx:unit", "Celsius or Fahrenheit for the weather, its forecast and the wind (km/h or mph). Auto follows the TV's country.");
        HELP.put("trouble", "What went wrong lately, kept on this TV. Read it on your phone and send it along when you report a problem.");
        HELP.put("backup", "Save your app order, folders and settings to your phone, and bring them back on any TV.");
        HELP.put("updates", "Aura's version, and the new one when it's out. Updating keeps every setting.");
        HELP.put("remove", "Hands the TV back to its own home screen first, then uninstalls Aura.");
        // Home screen
        HELP.put("layout", "tvOS: your apps alone on a dock over the wallpaper. Aura: a sidebar and widgets beside your apps.");
        HELP.put("dockside", "Whether a sidebar slides in from the left, or Up on the remote reaches the buttons at the top.");
        HELP.put("tiles", "How each app's tile is drawn: Apple TV icons, frosted glass, bold colour, dark, or the app's own banner.");
        HELP.put("grid", "How many apps fit across the screen: bigger tiles, or more of them.");
        HELP.put(Prefs.GRID_PAGES, "Past two rows of apps: scroll down, or slide sideways a page at a time.");
        HELP.put("names", "App names under the tiles: always, only on the one in focus, or never.");
        HELP.put(Prefs.PARALLAX, "Tiles tilt toward you and catch the light as you move. Off is stiller, and lighter on older TVs.");
        HELP.put("onlyapps", "The simplest home: your apps on the wallpaper, nothing else. Turns widgets and app cards off together.");
        HELP.put("widgets", "The cards above your apps: the Smart Stack (continue watching, new episodes), Today, and the Sky.");
        HELP.put(Prefs.TOP_SHELF, "Rest on an app and what you were watching in it, and its own picks, line up above it.");
        HELP.put(Prefs.PICKS, "Whether Tonight's pick appears on the home screen at all.");
        HELP.put(Prefs.INPUT_PREVIEW, "HDMI input tiles show a live picture of what's on. Some TVs draw it over the whole screen: switch it off there.");
        HELP.put("light", "Keeps motion and blur to a minimum for slower TVs. Auto decides from this TV's memory and chip.");
        HELP.put("homekeys", "From inside any app: Home twice opens your recent apps, Home held opens Control Center.");
        HELP.put("takeover", "Where Home goes from inside apps: Aura, or the TV's own home screen.");
        HELP.put("previews", "Recent apps show each app as you left it. The pictures stay on this TV, two days at most.");
        HELP.put("keys", "Give a spare button on the remote an app to open: a colour button, or one you never use.");
    }

    // ---- weather city ------------------------------------------------------------------------

    /** A kids' profile waiting for the PIN to be set before it's switched on. */
    private String pinFor;

    private void askPin() {
        inputFor = "pin";
        city.setHint("A PIN of 4 to 8 digits, then OK");
        city.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        city.setText("");
        city.setVisibility(View.VISIBLE);
        city.requestFocus();
        city.post(() -> Compat.svc(act, InputMethodManager.class).showSoftInput(city, InputMethodManager.SHOW_IMPLICIT));
    }

    private void setPin(String pin) {
        hideKeyboard();
        city.setVisibility(View.GONE);
        if (!pin.matches("[0-9]{4,8}")) {
            host.hud(R.drawable.ic_info, "4 to 8 digits");
            build();
            return;
        }
        Profiles.setPin(act, pin);
        if (pinFor != null) Profiles.setKids(act, pinFor, true);
        host.hud(R.drawable.ic_check, pinFor != null ? "Kids' profile on · remember the PIN" : "PIN changed");
        String tag = pinFor != null ? "pr:kids:" + pinFor : "pr:pin";
        pinFor = null;
        build();
        focusTag(tag);
    }

    /** The field again, this time for a new profile's name. */
    private void askProfile() {
        inputFor = "profile";
        city.setHint("A name, then OK");
        city.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        city.setText("");
        city.setVisibility(View.VISIBLE);
        city.requestFocus();
        city.post(() -> Compat.svc(act, InputMethodManager.class).showSoftInput(city, InputMethodManager.SHOW_IMPLICIT));
    }

    private void addProfile(String name) {
        hideKeyboard();
        city.setVisibility(View.GONE);
        if (name.isEmpty()) {
            build();
            return;
        }
        Profiles.Profile pr = Profiles.add(act, name.length() > 18 ? name.substring(0, 18) : name);
        if (pr == null) {
            host.hud(R.drawable.ic_info, "Five profiles at most");
            build();
            return;
        }
        Profiles.use(act, pr.id);
        host.hud(R.drawable.ic_check, "Hello, " + pr.name + "  ·  a fresh home screen, all yours");
        host.applied("profile");
        if (isOpen()) {
            build();
            focusTag("pr:" + pr.id);
        }
    }

    private void askCity() {
        inputFor = "city";
        city.setHint("Type a city, then OK");
        city.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        city.setText("");
        city.setVisibility(View.VISIBLE);
        city.requestFocus();
        city.post(() -> Compat.svc(act, InputMethodManager.class).showSoftInput(city, InputMethodManager.SHOW_IMPLICIT));
    }

    /** Open-Meteo's free geocoder (no key) turns the name into coordinates. */
    private void findCity(String name) {
        if (name.isEmpty()) return;
        hideKeyboard();
        new SafeThread(() -> {
            String found = null;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(name, "UTF-8")).openConnection();
                con.setConnectTimeout(10_000);
                con.setReadTimeout(10_000);
                try (InputStream in = con.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                    JSONObject r = new JSONObject(s.hasNext() ? s.next() : "{}").getJSONArray("results").getJSONObject(0);
                    found = r.optString("name");
                    Prefs.device(act).edit().putBoolean("geo_manual", true).putFloat("geo_lat", (float) r.getDouble("latitude"))
                            .putFloat("geo_lon", (float) r.getDouble("longitude")).putString("wx_city", found)
                            .putLong("wx_at", 0).apply();
                    Weather.placeSun(act);
                } finally {
                    con.disconnect();
                }
            } catch (Exception ignored) {
            }
            final String f = found;
            act.runOnUiThread(() -> {
                city.setVisibility(View.GONE);
                host.hud(R.drawable.ic_place, f != null ? "Weather and the sun now follow " + f : "Couldn't find " + name);
                if (f != null) host.applied("weather");
                build();
                // back on the row it was typed from, not the top of the list
                focusTag("city");
            });
        }, "geocode").start();
    }

    private void hideKeyboard() {
        Compat.svc(act, InputMethodManager.class).hideSoftInputFromWindow(city.getWindowToken(), 0);
    }

    private static LinearLayout.LayoutParams lp(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }
}
