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
        city.setHint(act.getString(R.string.type_a_city_then_ok));
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
            Ui.fit(title, act.getString(R.string.apps), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.hidden_apps_leave_your_grid));
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
                String name = info != null ? String.valueOf(info.loadLabel(act)) : act.getString(R.string.an_input);
                add(name, act.getString(R.string.hidden_ok_shows_it), h, () -> {
                    Prefs.hide(act, h, false);
                    host.applied("inputs");
                    build();
                });
            }
            for (AppRepo.App a : host.apps()) {
                boolean hidden = Prefs.hidden(act).contains(a.pkg);
                add(a.label, hidden ? act.getString(R.string.hidden) : act.getString(R.string.shown), "app:" + a.pkg, () -> {
                    Prefs.hide(act, a.pkg, !Prefs.hidden(act).contains(a.pkg));
                    host.applied("apps");
                    build();
                });
            }
        } else if (page.equals("home")) {
            Ui.fit(title, act.getString(R.string.home_screen), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.how_your_apps_look_and));
            boolean tvos = Prefs.tvos(act);
            section(act.getString(R.string.look));
            // the two styles: Aura's own (sidebar, widgets, apps) or Apple TV's (apps alone on a dock)
            add(act.getString(R.string.style), tvos ? act.getString(R.string.tvos_apps_on_a_dock) : act.getString(R.string.aura_widgets_and_sidebar), "layout", () -> {
                Prefs.of(act).edit().putString(Prefs.LAYOUT, Prefs.tvos(act) ? "aura" : "tvos").remove(Prefs.TILE_STYLE).remove(Prefs.NAME_MODE).apply();
                host.applied("layout");
                build();
                focusTag("layout");
            });
            if (tvos) add(act.getString(R.string.sidebar), Prefs.dockSidebar(act) ? act.getString(R.string.slides_in_from_the_left) : act.getString(R.string.hidden_up_for_the_top), "dockside", () -> {
                Prefs.of(act).edit().putBoolean(Prefs.DOCK_SIDEBAR, !Prefs.dockSidebar(act)).apply();
                host.applied("layout");
                build();
                focusTag("dockside");
            });
            int si = java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act));
            add(act.getString(R.string.tiles), act.getString(Prefs.TILE_NAMES[Math.max(0, si)]), "tiles", () -> {
                // OK steps through the styles; the grid changes straight away behind the panel
                String next = Prefs.TILE_STYLES[(Math.max(0, si) + 1) % Prefs.TILE_STYLES.length];
                Prefs.of(act).edit().putString(Prefs.TILE_STYLE, next).apply();
                host.applied("tiles");
                build();
                focusTag("tiles");
            });
            int gi = Math.max(0, Prefs.gridCols(act) - 5);
            add(act.getString(R.string.app_size), act.getString(Prefs.GRID_NAMES[gi]), "grid", () -> {
                // OK steps through the sizes; the grid is laid out again behind the panel
                Prefs.of(act).edit().putInt(Prefs.GRID, Prefs.GRID_COLS[(gi + 1) % Prefs.GRID_COLS.length]).apply();
                host.applied("apps");
                build();
                focusTag("grid");
            });
            toggle(act.getString(R.string.apps_beyond_two_rows), Prefs.GRID_PAGES, act.getString(R.string.pages_to_the_side), act.getString(R.string.scroll_down), "apps");
            int ni = Math.max(0, java.util.Arrays.asList(Prefs.NAME_MODES).indexOf(Prefs.nameMode(act)));
            add(act.getString(R.string.app_names), act.getString(Prefs.NAME_LABELS[ni]), "names", () -> {
                String next = Prefs.NAME_MODES[(ni + 1) % Prefs.NAME_MODES.length];
                Prefs.of(act).edit().putString(Prefs.NAME_MODE, next).putBoolean(Prefs.NAMES_ALWAYS, "always".equals(next)).apply();
                host.applied("names");
                build();
                focusTag("names");
            });
            toggle(act.getString(R.string.parallax_icons), Prefs.PARALLAX, act.getString(R.string.on), act.getString(R.string.off), "parallax");
            section(act.getString(R.string.what_shows));
            int hiddenW = Prefs.hiddenWidgets(act).size();
            boolean wShown = (!tvos || Prefs.dockWidgets(act)) && hiddenW < 3;
            // the plainest home: your apps and the wall, no widgets and no app cards (continue
            // watching, an app's rows); the two rows below still change each on its own
            boolean only = !wShown && !Prefs.on(act, Prefs.TOP_SHELF);
            add(act.getString(R.string.only_apps), only ? act.getString(R.string.on_nothing_but_your_apps) : act.getString(R.string.off), "onlyapps", () -> {
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
            add(act.getString(R.string.widgets), !wShown ? act.getString(R.string.hidden_clean) : hiddenW == 0 ? act.getString(R.string.all_shown) : act.getString(R.string.n_of_3_shown, 3 - hiddenW), "widgets", () -> go("widgets"));
            // an app's own cards above it when it has focus (JioHotstar's rows, YouTube's videos); off for only apps
            toggle(tvos ? act.getString(R.string.app_cards) : act.getString(R.string.top_shelf), Prefs.TOP_SHELF, act.getString(R.string.on), act.getString(R.string.hidden_clean), "shelf");
            toggle(act.getString(R.string.tonights_pick), Prefs.PICKS, act.getString(R.string.shown), act.getString(R.string.hidden_clean), "picks");
            toggle(act.getString(R.string.live_picture_on_input_tiles), Prefs.INPUT_PREVIEW, act.getString(R.string.on), act.getString(R.string.off), "inputs");
            section(act.getString(R.string.speed));
            // stillness for slow TVs; Auto decides from the TV's memory and chip
            String lm = Perf.mode(act);
            add(act.getString(R.string.light_mode), lm.equals("auto") ? act.getString(R.string.auto_value, Perf.lowEnd(act) ? act.getString(R.string.on_for_this_tv) : act.getString(R.string.off_for_this_tv)) : lm.equals("on") ? act.getString(R.string.on) : act.getString(R.string.off), "light", () -> {
                String next = Perf.MODES[(java.util.Arrays.asList(Perf.MODES).indexOf(Perf.mode(act)) + 1) % Perf.MODES.length];
                Prefs.device(act).edit().putString(Perf.KEY, next).apply();
                host.applied("light");
                build();
                focusTag("light");
            });
            section(act.getString(R.string.remote));
            // switched by Aura where setup allowed it; elsewhere, Android's own Accessibility screen does it
            // how late the TV hands over remote releases while Aura watches the keys: some firmware adds a lot
            int lag = Prefs.device(act).getInt("keys_lag", 0);
            add(act.getString(R.string.home_button_in_apps), HomeKeys.on(act) ? act.getString(R.string.homekeys_on) + (lag >= 150 ? " · " + act.getString(R.string.remote_ms_late, lag) : "") : act.getString(R.string.off), "homekeys", () -> {
                if (!HomeKeys.set(act, !HomeKeys.on(act))) {
                    host.open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), panel);
                    return;
                }
                build();
                focusTag("homekeys");
            });
            // where Android sends Home to another launcher, the service can send it to Aura instead
            if (HomeKeys.on(act) && !HomeRole.held(act)) {
                add(act.getString(R.string.home_goes_to), HomeKeys.takeover(act) ? "Aura" : HomeRole.other(act), "takeover", () -> {
                    HomeKeys.setTakeover(act, !HomeKeys.takeover(act));
                    build();
                    focusTag("takeover");
                });
            }
            // pictures need the service above, and Android 11
            if (android.os.Build.VERSION.SDK_INT >= 30 && HomeKeys.on(act)) {
                add(act.getString(R.string.pictures_in_the_switcher), Previews.on(act) ? act.getString(R.string.apps_as_you_left_them) : act.getString(R.string.off), "previews", () -> {
                    Previews.set(act, !Previews.on(act));
                    host.hud(R.drawable.ic_check, Previews.on(act) ? act.getString(R.string.kept_only_on_this_tv) : act.getString(R.string.pictures_deleted));
                    build();
                    focusTag("previews");
                });
            }
            if (HomeKeys.on(act)) {
                int n = HomeKeys.keys(act).size();
                add(act.getString(R.string.remote_buttons), n == 0 ? act.getString(R.string.give_a_button_an_app) : act.getResources().getQuantityString(R.plurals.buttons_with_apps, n, n), "keys", () -> go("keys"));
            }
        } else if (page.equals("profiles")) {
            Ui.fit(title, act.getString(R.string.profiles), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.everyone_gets_their_own_app));
            String cur = Profiles.current(act);
            for (Profiles.Profile pr : Profiles.all(act)) {
                add(pr.name, pr.id.equals(cur) ? act.getString(R.string.in_use) : act.getString(R.string.switch_word), "pr:" + pr.id, () -> {
                    if (pr.id.equals(Profiles.current(act))) return;
                    Profiles.use(act, pr.id);
                    host.hud(R.drawable.ic_check, act.getString(R.string.hello_name, pr.name));
                    host.applied("profile");
                });
            }
            if (Profiles.all(act).size() < Profiles.MAX) add(act.getString(R.string.add_a_profile), act.getString(R.string.type_a_name), "pr:add", this::askProfile);
            // a profile for kids: grown-up things (Settings, hiding, uninstalling) ask for the parents' PIN
            for (Profiles.Profile pr : Profiles.all(act)) {
                if (Profiles.MAIN.equals(pr.id)) continue;
                add(act.getString(R.string.name_is_for_kids, pr.name), pr.kids ? act.getString(R.string.on_pin_for_grown_up) : act.getString(R.string.off), "pr:kids:" + pr.id, () -> {
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
            if (Profiles.hasPin(act)) add(act.getString(R.string.parents_pin), act.getString(R.string.change), "pr:pin", () -> {
                pinFor = null;
                askPin();
            });
            for (Profiles.Profile pr : Profiles.all(act)) {
                if (Profiles.MAIN.equals(pr.id)) continue;
                add(act.getString(R.string.remove_name, pr.name), ("pr:del:" + pr.id).equals(confirmProfile) ? act.getString(R.string.press_ok_again) : "", "pr:del:" + pr.id, () -> {
                    String tag = "pr:del:" + pr.id;
                    if (!tag.equals(confirmProfile)) {
                        confirmProfile = tag;
                        build();
                        return;
                    }
                    confirmProfile = null;
                    boolean wasCurrent = pr.id.equals(Profiles.current(act));
                    Profiles.remove(act, pr.id);
                    host.hud(R.drawable.ic_check, act.getString(R.string.name_removed, pr.name));
                    if (wasCurrent) host.applied("profile");
                    else {
                        build();
                        focusTag("pr:" + Profiles.current(act));
                    }
                });
            }
        } else if (page.equals("widgets")) {
            Ui.fit(title, act.getString(R.string.widgets), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.widgets_note_1) + " "
                    + act.getString(R.string.widgets_note_2));
            boolean tvos = Prefs.tvos(act);
            java.util.Set<String> hidden = Prefs.hiddenWidgets(act);
            boolean anyOn = (!tvos || Prefs.dockWidgets(act)) && hidden.size() < HomeActivity.WIDGET_IDS.length;
            add(act.getString(R.string.show_widgets), anyOn ? act.getString(R.string.on) : act.getString(R.string.hidden_clean), "w:all", () -> {
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
                add(act.getString(HomeActivity.WIDGET_NAMES[i]), shown ? act.getString(R.string.shown) : act.getString(R.string.hidden), "w:" + id, () -> {
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
            toggle(act.getString(R.string.stack_pages), Prefs.STACK_AUTO, act.getString(R.string.turn_on_their_own), act.getString(R.string.stay_put), "stack");
            // the stack's extras: today's releases come from the country's schedule, not your account
            toggle(act.getString(R.string.new_today_on_your_apps), Prefs.STACK_NEW, act.getString(R.string.in_the_stack), act.getString(R.string.left_out), "cards");
            toggle(act.getString(R.string.suggested_apps), Prefs.STACK_APPS, act.getString(R.string.in_the_stack), act.getString(R.string.left_out), "cards");
            // cards turned down from the stack (hold OK on a card): all back at once
            int declined = Taste.declinedCount(act);
            if (declined > 0) add(act.getString(R.string.cards_you_turned_down), act.getString(R.string.n_ok_brings_them_back, declined), "w:forgive", () -> {
                Taste.forgive(act);
                host.applied("widgets");
                host.hud(R.drawable.ic_check, act.getString(R.string.theyre_back_in_the_stack));
                build();
            });
            add(act.getString(R.string.original_order), "", "w:reset", () -> {
                Prefs.setWidgetOrder(act, java.util.Arrays.asList(0, 1, 2));
                host.applied("widgets");
                build();
            });
        } else if (page.equals("shield")) {
            Ui.fit(title, act.getString(R.string.block_ads), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.ad_and_tracker_servers_get));
            add(act.getString(R.string.block_ads), shieldNote != null ? shieldNote : Shield.on(act) ? act.getString(R.string.on) : act.getString(R.string.off), "shield:on", this::toggleShield);
            String current = Shield.host(act);
            boolean custom = true;
            for (String[] f : Shield.FILTERS) {
                if (f[0].equals(current)) custom = false;
                add(f[1], filterValue(f[0], Shield.about(act, f[0]), current), "dns:" + f[0], () -> pickFilter(f[0], f[1]));
            }
            add(act.getString(R.string.your_own_dns), custom ? filterValue(current, current, current) : act.getString(R.string.type_a_hostname), "dns:custom", this::askDns);
            add(act.getString(R.string.find_the_fastest), probing ? act.getString(R.string.measuring) : "", "dns:probe", this::findFastest);
            add(act.getString(R.string.wi_fi_dns_settings), act.getString(R.string.if_this_tv_ignores_private), "dns:wifi",
                    () -> host.open(new Intent(Settings.ACTION_WIFI_SETTINGS), panel));
        } else if (page.equals("wallpaper")) {
            Ui.fit(title, act.getString(R.string.wallpaper), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.photographs_that_follow_the_sky));
            toggle(act.getString(R.string.wallpaper), Prefs.BLACK, act.getString(R.string.pure_black), act.getString(R.string.photographs), "photos");
            if (!Prefs.on(act, Prefs.BLACK)) {
                toggle(act.getString(R.string.fresh_hd_photographs), Prefs.DAILY, act.getString(R.string.on), act.getString(R.string.off), "daily");
                if (Prefs.on(act, Prefs.DAILY)) {
                    List<String> on = Gallery.sources(act);
                    add(act.getString(R.string.photo_sources), on.isEmpty() ? act.getString(R.string.none_northern_lights_only) : act.getString(R.string.n_on, on.size()), "sources", () -> go("sources"));
                }
                toggle(act.getString(R.string.sky_moments), Prefs.MOMENTS, act.getString(R.string.on), act.getString(R.string.off), "moments");
            }
            // the screensaver's scene: the photographs, the quotes, or both by turns
            final List<String> modes = java.util.Arrays.asList(Prefs.SAVERS);
            String mode = Prefs.saver(act);
            add(act.getString(R.string.screensaver), act.getString(Prefs.SAVER_NAMES[modes.indexOf(mode)])
                    + (mode.equals("auto") ? " · " + act.getString(R.string.photographs_and_quotes_by_turns) : ""), Prefs.SAVER, () -> {
                String next = modes.get((modes.indexOf(Prefs.saver(act)) + 1) % modes.size());
                Prefs.of(act).edit().putString(Prefs.SAVER, next).apply();
                build();
                focusTag(Prefs.SAVER);
            });
            // the TV's own screensaver settings, here instead of three menus deep in Android's
            if (Ambient.canSet(act)) {
                boolean mine = Ambient.chosen(act);
                add(act.getString(R.string.aura_as_the_screensaver), mine ? act.getString(R.string.on) : act.getString(R.string.off_another_one_is_chosen), "saver:use", () -> {
                    if (!Ambient.choose(act, !Ambient.chosen(act))) host.hud(R.drawable.ic_info, act.getString(R.string.the_tv_didnt_allow_that));
                    build();
                    focusTag("saver:use");
                });
                if (mine) {
                    add(act.getString(R.string.starts_after), Ambient.span(Ambient.startMs(act)), "saver:start", () -> {
                        Ambient.next(act, true, Ambient.startMs(act));
                        build();
                        focusTag("saver:start");
                    });
                }
                int off = Ambient.offMs(act);
                add(act.getString(R.string.tv_turns_off_after), (mine && off >= 0 && off < Ambient.startMs(act) ? act.getString(R.string.span_before_the_screensaver, Ambient.span(off)) : act.getString(R.string.span_without_use, Ambient.span(off))), "saver:off", () -> {
                    Ambient.next(act, false, Ambient.offMs(act));
                    build();
                    focusTag("saver:off");
                });
            } else {
                add(act.getString(R.string.aura_as_the_screensaver), Ambient.chosen(act) ? act.getString(R.string.on) : act.getString(R.string.choose_it_in_androids_settings), "saver:use", () -> {
                    try {
                        host.open(new Intent(Settings.ACTION_DREAM_SETTINGS), panel);
                    } catch (RuntimeException e) {
                        host.open(new Intent(Settings.ACTION_SETTINGS), panel);
                    }
                });
            }
            add(act.getString(R.string.preview_the_screensaver), "", "saver:preview", () -> {
                try {
                    act.startActivity(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    host.hud(R.drawable.ic_info, act.getString(R.string.this_tv_cant_preview_it));
                }
            });
        } else if (page.equals("updates")) {
            Ui.fit(title, act.getString(R.string.updates), Ui.dp(300), 52, 30);
            String mine = Updater.installed(act);
            switch (updateState) {
                case "checking":
                    note.setText(act.getString(R.string.looking_for_a_newer_aura));
                    break;
                case "available":
                case "downloading":
                    note.setText((installHint != null && !Updater.allowed(act) ? installHint + "\n\n" : "")
                            + act.getString(R.string.aura_version_is_out, release.version) + "\n\n" + release.notes);
                    break;
                case "error":
                    note.setText(updateNote);
                    break;
                default:
                    note.setText(act.getString(R.string.you_have_the_latest_aura));
            }
            add(act.getString(R.string.installed), mine, "u:mine", () -> { });
            if (release != null && (updateState.equals("available") || updateState.equals("downloading"))) {
                add(act.getString(R.string.install_name, release.version), updateState.equals("downloading") ? updateNote
                        : String.format(java.util.Locale.ROOT, "%.1f MB", release.size / 1048576f), "u:install", this::installUpdate);
            }
            if (!updateState.equals("checking") && !updateState.equals("downloading")) add(act.getString(R.string.check_again), "", "u:check", this::checkUpdate);
            // the way round a TV whose installer won't take Aura's own updates (seen on Fire TV)
            final Intent downloader = updateState.equals("error")
                    ? act.getPackageManager().getLaunchIntentForPackage("com.esaba.downloader") : null;
            if (downloader != null) add(act.getString(R.string.open_downloader), act.getString(R.string.type_the_code_4172997), "u:downloader",
                    () -> host.open(downloader.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), panel));
        } else if (page.equals("drop")) {
            Ui.fit(title, act.getString(R.string.from_your_phone), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.point_your_phones_camera_at));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200));
            cl.bottomMargin = Ui.dp(8);
            rows.addView(dropCard, cl);
            toggle(act.getString(R.string.show), Prefs.MINE_ONLY, act.getString(R.string.only_your_photos), act.getString(R.string.everything), "photos");
            int n = Mine.count(act);
            if (n > 0) add(act.getString(R.string.your_photos), act.getString(R.string.n_on_the_tv, n), "photos", () -> {
                confirmPhoto = null;
                confirmClear = false;
                go("photos");
            });
        } else if (page.equals("photos")) {
            Ui.fit(title, act.getString(R.string.your_photos), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.everything_sent_from_your_phone));
            org.json.JSONArray list = Mine.list(act);
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.optJSONObject(i);
                final String id = o.optString("id");
                add(o.optString("title") + (o.optBoolean("gif") ? "  ·  GIF" : ""), id.equals(confirmPhoto) ? act.getString(R.string.press_ok_again) : act.getString(R.string.remove),
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
            if (list.length() > 1) add(act.getString(R.string.remove_all), confirmClear ? act.getString(R.string.press_ok_again) : act.getResources().getQuantityString(R.plurals.n_photos, list.length(), list.length()), "clear", () -> {
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
            Ui.fit(title, act.getString(R.string.genres), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.picks_come_from_any_of));
            java.util.Set<String> g = Picks.genres(act);
            add(act.getString(R.string.any_genre), g.isEmpty() ? act.getString(R.string.on) : "", "g:any", () -> {
                Picks.setGenres(act, new java.util.HashSet<>());
                tasteChanged();
            });
            for (Picks.Genre x : Picks.GENRES) {
                add(act.getString(x.name), g.contains(x.id) ? act.getString(R.string.on) : act.getString(R.string.off), "g:" + x.id, () -> {
                    java.util.Set<String> now = Picks.genres(act);
                    if (!now.remove(x.id)) now.add(x.id);
                    Picks.setGenres(act, now);
                    tasteChanged();
                });
            }
        } else if (page.equals("pair")) {
            Ui.fit(title, act.getString(R.string.wireless_debugging), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.in_developer_options_switch_on));
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
            add(act.getString(R.string.developer_options), "", "pair:dev", () -> host.open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), panel));
        } else if (page.equals("backup")) {
            Ui.fit(title, act.getString(R.string.your_setup), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.save_your_profiles_app_order));
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("trouble")) {
            Ui.fit(title, act.getString(R.string.problem_reports), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.when_something_goes_wrong_aura));
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
            if (Trouble.count(act) > 0) add(act.getString(R.string.clear_them), confirmClearTrouble ? act.getString(R.string.press_ok_again) : "", "trouble:clear", () -> {
                if (!confirmClearTrouble) {
                    confirmClearTrouble = true;
                    build();
                    focusTag("trouble:clear");
                    return;
                }
                confirmClearTrouble = false;
                Trouble.clear(act);
                host.hud(R.drawable.ic_check, act.getString(R.string.problem_reports_cleared));
                build();
            });
        } else if (page.equals("art")) {
            Ui.fit(title, artLabel, Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.your_own_picture_for_its));
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
            if (Art.has(act, artPkg)) add(act.getString(R.string.use_the_apps_own), "", "art:reset", () -> {
                Art.remove(act, artPkg);
                host.hud(R.drawable.ic_check, act.getString(R.string.name_has_its_own_art_again, artLabel));
                host.applied("art");
                build();
            });
        } else if (page.equals("sources")) {
            Ui.fit(title, act.getString(R.string.photo_sources), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.where_the_walls_photographs_come));
            toggle("Windows Spotlight", Prefs.SRC_SPOTLIGHT, act.getString(R.string.on), act.getString(R.string.off), "sources");
            toggle("Bing", Prefs.SRC_BING, act.getString(R.string.on), act.getString(R.string.off), "sources");
            toggle("Wikimedia Commons", Prefs.SRC_WIKIMEDIA, act.getString(R.string.on), act.getString(R.string.off), "sources");
            toggle(act.getString(R.string.wallhaven_nature), Prefs.SRC_WALLHAVEN, act.getString(R.string.on), act.getString(R.string.off), "sources");
            if (Gallery.unsplashKey(act).isEmpty()) {
                add("Unsplash", act.getString(R.string.needs_a_key_send_it), "src:unsplash", this::openUnsplashDrop);
            } else {
                toggle("Unsplash", Prefs.SRC_UNSPLASH, act.getString(R.string.on), act.getString(R.string.off), "sources");
                add(act.getString(R.string.unsplash_key), act.getString(R.string.saved_send_a_new_one), "src:key", this::openUnsplashDrop);
            }
        } else if (page.equals("unsplashphone")) {
            Ui.fit(title, act.getString(R.string.unsplash_key), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.scan_the_code_then_paste));
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("tmdbphone")) {
            Ui.fit(title, act.getString(R.string.tmdb_key), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.scan_the_code_then_paste_2));
            if (keyCard != null) rows.addView(keyCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("tmdbauth")) {
            Ui.fit(title, act.getString(R.string.tmdb_account), Ui.dp(300), 52, 30);
            note.setText(act.getString(R.string.scan_the_code_sign_in));
            if (authCard != null) rows.addView(authCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        } else if (page.equals("tv")) {
            buildTv();
        } else if (page.equals("keys")) {
            buildKeys();
        } else {
            Ui.fit(title, act.getString(R.string.settings), Ui.dp(300), 52, 30);
            Weather.Now w = Weather.now(act);
            note.setText(act.getString(R.string.changes_apply_at_once_everything));
            // only while Android still sends Home to another launcher: Android asks, or (Google TV,
            // Fire TV, which keep Home whatever is chosen) the Home button service takes it over
            if (!HomeRole.held(act)) {
                boolean taken = HomeKeys.on(act) && HomeKeys.takeover(act);
                add(act.getString(R.string.home_button), taken ? act.getString(R.string.opens_aura) : act.getString(R.string.opens_name_ok_to_change, HomeRole.other(act)), "role", () -> {
                    // where only setup can take Home over, straight to the page that does it
                    if (HomeRole.needsSetup(act) && !HomeKeys.set(act, true) && !HomeRole.byHand(act)) {
                        go("tv");
                        return;
                    }
                    String m = HomeRole.make(act);
                    if (m != null) host.hud(m.equals(act.getString(R.string.home_now_opens_aura)) || m.equals(act.getString(R.string.home_already_opens_aura))
                            ? R.drawable.ic_check : R.drawable.ic_info, m);
                    build();
                    focusTag("role");
                });
            }
            section(act.getString(R.string.you));
            add(act.getString(R.string.profile), Profiles.name(act), "profiles", host::whoIsWatching);
            add(act.getString(R.string.wallpaper), Prefs.on(act, Prefs.BLACK) ? act.getString(R.string.pure_black) : act.getString(R.string.photographs), "wallpaper", () -> go("wallpaper"));
            int mine = Mine.count(act);
            add(act.getString(R.string.from_your_phone), mine > 0 ? act.getResources().getQuantityString(R.plurals.n_photos, mine, mine) : act.getString(R.string.photos_gifs_apps), "drop", this::openDrop);
            toggle(act.getString(R.string.play_from_phones_pcs), Prefs.DLNA, act.getString(R.string.on_dlna), act.getString(R.string.off), "dlna");
            section(act.getString(R.string.home_screen_2));
            add(act.getString(R.string.home_screen), act.getString(R.string.name_tiles, act.getString(Prefs.TILE_NAMES[Math.max(0, java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act)))]
                    )), "home", () -> go("home"));
            add(act.getString(R.string.hidden_apps), String.valueOf(Prefs.hidden(act).size()), "hidden", () -> go("apps"));
            add(act.getString(R.string.tonights_pick), !Prefs.on(act, Prefs.PICKS) ? act.getString(R.string.off) : Picks.tmdb(act) ? picksSummary() : act.getString(R.string.shows_value, Picks.genreSummary(act)),
                    "picks", () -> go("picks"));
            toggle(act.getString(R.string.weather), Prefs.WEATHER, act.getString(R.string.on), act.getString(R.string.off), "weather");
            boolean manual = Prefs.device(act).getBoolean("geo_manual", false);
            String place = w != null && !w.city.isEmpty() ? w.city : act.getString(R.string.not_found_yet);
            add(act.getString(R.string.weather_city), manual ? place : act.getString(R.string.auto_value, place), "city", this::askCity);
            // Auto by the TV's country, or set: OK goes round the three
            String u = Weather.unit(act);
            add(act.getString(R.string.temperature), u.equals("c") ? act.getString(R.string.celsius_c) : u.equals("f") ? act.getString(R.string.fahrenheit_f)
                    : act.getString(R.string.auto_value, Weather.fahrenheit(act) ? "°F" : "°C"), "wx:unit", () -> {
                String next = Weather.UNITS[(java.util.Arrays.asList(Weather.UNITS).indexOf(Weather.unit(act)) + 1) % Weather.UNITS.length];
                Prefs.device(act).edit().putString(Weather.UNIT, next).apply();
                // fetched again in the new unit straight away (temperatures and wind alike)
                host.applied("weather");
                build();
                focusTag("wx:unit");
            });
            if (manual) add(act.getString(R.string.use_my_connections_city), "", "auto", () -> {
                Prefs.device(act).edit().putBoolean("geo_manual", false).putLong("geo_at", 0).putLong("wx_at", 0).apply();
                host.applied("weather");
                build();
            });
            section(act.getString(R.string.this_tv));
            // Private DNS arrived with Android 9: older TVs don't get the row at all
            if (android.os.Build.VERSION.SDK_INT >= 28) add(act.getString(R.string.block_ads), Shield.on(act) ? act.getString(R.string.on_value, Shield.name(act)) : act.getString(R.string.off), "shield", () -> go("shield"));
            add(act.getString(R.string.animations), animLabel(), "anim", this::cycleAnim);
            add(act.getString(R.string.set_up_on_this_tv), Adb.on(act) ? Adb.unguarded(act) ? act.getString(R.string.connected_open_to_your_wi) : act.getString(R.string.connected) : act.getString(R.string.no_computer_needed), "tv", () -> go("tv"));
            add(act.getString(R.string.android_settings), "", "android", () -> host.open(new Intent(Settings.ACTION_SETTINGS), panel));
            section("AURA");
            add(act.getString(R.string.your_setup), act.getString(R.string.save_to_your_phone_or), "backup", this::openBackup);
            int problems = Trouble.count(act);
            add(act.getString(R.string.problem_reports), problems == 0 ? act.getString(R.string.none_lately) : act.getResources().getQuantityString(R.plurals.n_notes_read_on_your_phone, problems, problems),
                    "trouble", this::openTrouble);
            String waiting = Updater.pending(act);
            add(act.getString(R.string.updates), waiting != null ? act.getString(R.string.aura_version_is_ready, waiting) : act.getString(R.string.version_name, Updater.installed(act)), "updates", () -> {
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
            add(act.getString(R.string.remove_aura), confirmRemove ? act.getString(R.string.press_ok_again) : act.getString(R.string.puts_the_tv_back_first), "remove", this::removeAura);
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
        if (src.equals("watchlist")) return act.getString(R.string.your_tmdb_watchlist);
        String what = act.getString(Picks.KIND_WORDS[java.util.Arrays.asList(Picks.KINDS).indexOf(Picks.kind(act))]);
        return act.getString(Picks.SOURCE_NAMES[java.util.Arrays.asList(Picks.SOURCES).indexOf(src)]) + " · " + what;
    }

    /** Where picks come from, what kind, which genres, how good, how new: OK on each changes it. */
    private void buildPicks() {
        Ui.fit(title, act.getString(R.string.tonights_pick), Ui.dp(300), 52, 30);
        boolean tmdb = Picks.tmdb(act), on = Prefs.on(act, Prefs.PICKS);
        note.setText(tmdb ? act.getString(R.string.films_and_shows_from_tmdb)
                : act.getString(R.string.top_rated_shows_from_tvmaze));
        add(act.getString(R.string.tonights_pick), on ? act.getString(R.string.on) : act.getString(R.string.off), Prefs.PICKS, () -> {
            Prefs.set(act, Prefs.PICKS, !Prefs.on(act, Prefs.PICKS));
            host.applied("picks");
            build();
        });
        if (on) {
            String src = Picks.source(act);
            if (tmdb) {
                add(act.getString(R.string.from), act.getString(Picks.SOURCE_NAMES[java.util.Arrays.asList(Picks.SOURCES).indexOf(src)]), "pk:src", () -> {
                    List<String> can = new java.util.ArrayList<>(java.util.Arrays.asList("top", "trending"));
                    if (Picks.signedIn(act)) can.add("watchlist");
                    Prefs.device(act).edit().putString(Picks.SOURCE, can.get((can.indexOf(Picks.source(act)) + 1) % can.size())).apply();
                    tasteChanged();
                });
                int k = java.util.Arrays.asList(Picks.KINDS).indexOf(Picks.kind(act));
                add(act.getString(R.string.films_or_shows), act.getString(Picks.KIND_NAMES[k]), "pk:kind", () -> {
                    Prefs.device(act).edit().putString(Picks.KIND, Picks.KINDS[(k + 1) % Picks.KINDS.length]).apply();
                    tasteChanged();
                });
            }
            // your watchlist is yours as it is: no filters on it
            if (!src.equals("watchlist")) {
                add(act.getString(R.string.genres), Picks.genreSummary(act), "pk:genres", () -> go("genres"));
                // trending is what's popular this week, whatever it's rated
                if (src.equals("top")) {
                    float r = Picks.rating(act);
                    add(act.getString(R.string.rated_at_least), String.format(java.util.Locale.getDefault(), "★ %.1f", r), "pk:rating", () -> {
                        int at = 0;
                        for (int i = 0; i < Picks.RATINGS.length; i++) if (Math.abs(Picks.RATINGS[i] - r) < 0.01f) at = i;
                        Prefs.device(act).edit().putFloat(Picks.RATING, Picks.RATINGS[(at + 1) % Picks.RATINGS.length]).apply();
                        tasteChanged();
                    });
                }
                int e = java.util.Arrays.asList(Picks.ERAS).indexOf(Picks.era(act));
                add(act.getString(R.string.years), act.getString(Picks.ERA_NAMES[e]), "pk:era", () -> {
                    Prefs.device(act).edit().putString(Picks.ERA, Picks.ERAS[(e + 1) % Picks.ERAS.length]).apply();
                    tasteChanged();
                });
                if (tmdb && src.equals("top")) {
                    add(act.getString(R.string.only_whats_on_my_apps), Picks.myApps(act) ? act.getString(R.string.on) : act.getString(R.string.off), "pk:apps", () -> {
                        Prefs.device(act).edit().putBoolean(Picks.MY_APPS, !Picks.myApps(act)).apply();
                        tasteChanged();
                    });
                }
            }
            int n = Picks.count(act);
            add(act.getString(R.string.find_new_picks), picksBusy ? act.getString(R.string.finding) : n == 0 ? act.getString(R.string.none_match_try_fewer_limits) : n > 0 ? act.getString(R.string.n_ready, n) : "",
                    "pk:now", this::refreshPicks);
        }
        add(act.getString(R.string.tmdb_key), tmdb ? act.getString(R.string.set_ok_to_change) : act.getString(R.string.optional_free_at_themoviedb_org), "pk:key", this::askTmdb);
        add(act.getString(R.string.paste_the_key_from_your), "", "pk:phone", this::openKeyDrop);
        if (tmdb) {
            add(act.getString(R.string.tmdb_account), !Picks.signedIn(act) ? act.getString(R.string.sign_in_for_your_watchlist)
                    : confirmSignOut ? act.getString(R.string.press_ok_again_to_sign) : act.getString(R.string.signed_in_as, Picks.user(act)), "pk:auth", () -> {
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
                host.hud(R.drawable.ic_check, act.getString(R.string.signed_out_of_tmdb));
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
                        ? act.getString(R.string.couldnt_reach_try_again, Picks.tmdb(act) ? "TMDB" : "TVmaze")
                        : n == 0 ? act.getString(R.string.nothing_matches_all_that_try) : act.getResources().getQuantityString(R.plurals.n_picks_to_your_taste, n, n));
                if (isOpen() && (page.equals("picks") || page.equals("genres"))) build();
            });
        }, "picks").start();
    }

    private void askTmdb() {
        inputFor = "tmdb";
        city.setHint(act.getString(R.string.your_tmdb_api_key_then));
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
                host.hud(R.drawable.ic_check, act.getString(R.string.tmdb_key_removed_shows_from));
                tasteChanged();
            }
            build();
            return;
        }
        host.hud(R.drawable.ic_info, act.getString(R.string.checking_the_key_with_tmdb));
        new SafeThread(() -> {
            boolean ok = Picks.check(k);
            act.runOnUiThread(() -> {
                if (!ok) {
                    host.hud(R.drawable.ic_info, act.getString(R.string.tmdb_didnt_accept_that_key));
                    build();
                    return;
                }
                Picks.setKey(act, k);
                host.hud(R.drawable.ic_check, act.getString(R.string.tmdb_key_works_films_trending));
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
        keyCard = qrCard(url == null ? null : url + "/key", url != null ? act.getString(R.string.scan_with_your_phone) : act.getString(R.string.not_connected),
                url != null ? act.getString(R.string.paste_your_unsplash_key) : act.getString(R.string.no_network), url != null ? act.getString(R.string.same_wi_fi_no_app) : act.getString(R.string.connect_the_tv_to_wi));
        go("unsplashphone");
    }

    /** Checks the key with Unsplash, then keeps it on this TV and switches Unsplash on. */
    private void setUnsplashKey(String k) {
        host.hud(R.drawable.ic_info, act.getString(R.string.checking_the_key_with_unsplash));
        final android.content.Context app = act.getApplicationContext();
        new SafeThread(() -> {
            boolean ok = Gallery.checkUnsplash(k);
            act.runOnUiThread(() -> {
                if (!ok) {
                    host.hud(R.drawable.ic_info, act.getString(R.string.unsplash_didnt_accept_that_key));
                    return;
                }
                Prefs.device(app).edit().putString(Prefs.UNSPLASH_KEY, k).apply();
                Prefs.set(act, Prefs.SRC_UNSPLASH, true);
                host.hud(R.drawable.ic_check, act.getString(R.string.unsplash_key_works_its_photographs));
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
        keyCard = qrCard(url == null ? null : url + "/key", url != null ? act.getString(R.string.scan_with_your_phone) : act.getString(R.string.not_connected),
                url != null ? act.getString(R.string.paste_your_tmdb_key) : act.getString(R.string.no_network), url != null ? act.getString(R.string.same_wi_fi_no_app) : act.getString(R.string.connect_the_tv_to_wi));
        go("tmdbphone");
    }

    /** Wireless debugging's code, typed on the phone while the TV's pairing dialog stays open. */
    private void openPair() {
        keepDrop = false;
        stopDrop();
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onPairCode(String code) {
                host.hud(R.drawable.ic_info, act.getString(R.string.pairing_with_the_tv));
                new SafeThread(() -> {
                    String r = Adb.pair(act, code);
                    act.runOnUiThread(() -> {
                        adbState = r;
                        host.hud(r.equals(Adb.OK) || r.equals(Adb.OPEN_DOOR) ? R.drawable.ic_check : R.drawable.ic_info,
                                r.equals(Adb.OK) || r.equals(Adb.OPEN_DOOR) ? act.getString(R.string.paired_and_connected) : r.equals(Adb.DENIED)
                                        ? act.getString(R.string.that_code_didnt_match_send) : act.getString(R.string.no_pairing_dialog_found_open));
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
        keyCard = qrCard(url == null ? null : url + "/pair", url != null ? act.getString(R.string.scan_with_your_phone) : act.getString(R.string.not_connected),
                url != null ? act.getString(R.string.type_the_pairing_code) : act.getString(R.string.no_network), url != null ? act.getString(R.string.same_wi_fi_no_app) : act.getString(R.string.connect_the_tv_to_wi));
        go("pair");
    }

    /** Save or bring back your setup, from the phone, behind a QR code. */
    private void openBackup() {
        keepDrop = false;
        stopDrop();
        drop = new Drop(act, new Drop.Quiet() {
            @Override
            public void onRestored(int count) {
                host.hud(R.drawable.ic_check, act.getResources().getQuantityString(R.plurals.setup_is_back, count, count));
                stopDrop();
                // every profile, order and choice read afresh
                host.applied("profile");
                if (isOpen()) go("");
            }
        });
        drop.only("/backup", "/backup.json", "/restore");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/backup", url != null ? act.getString(R.string.scan_with_your_phone) : act.getString(R.string.not_connected),
                url != null ? act.getString(R.string.save_or_bring_back) : act.getString(R.string.no_network), url != null ? act.getString(R.string.same_wi_fi_no_app) : act.getString(R.string.connect_the_tv_to_wi));
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
        keyCard = qrCard(url == null ? null : url + "/report", url != null ? act.getString(R.string.scan_with_your_phone) : act.getString(R.string.not_connected),
                url != null ? act.getString(R.string.read_the_report) : act.getString(R.string.no_network), url != null ? act.getString(R.string.same_wi_fi_no_app) : act.getString(R.string.connect_the_tv_to_wi));
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
                host.hud(R.drawable.ic_check, act.getString(R.string.new_picture_for, artLabel));
                host.applied("art");
                if (page.equals("art")) build();
            }
        });
        drop.artFor = pkg;
        drop.only("/art");
        String url = drop.start();
        keyCard = qrCard(url == null ? null : url + "/art", url != null ? act.getString(R.string.scan_with_your_phone) : act.getString(R.string.not_connected),
                url != null ? act.getString(R.string.choose_a_picture) : act.getString(R.string.no_network), url != null ? act.getString(R.string.same_wi_fi_no_app) : act.getString(R.string.connect_the_tv_to_wi));
        go("art");
    }

    /** Signing in to TMDB: a token, approved on the phone from a QR code, then a session kept on the TV. */
    private void signIn() {
        authToken = null;
        authCard = qrCard(null, act.getString(R.string.one_moment), act.getString(R.string.asking_tmdb), "");
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
                    host.hud(R.drawable.ic_info, act.getString(R.string.couldnt_reach_tmdb_check_the));
                    back();
                    return;
                }
                authToken = token;
                authCard = qrCard(Picks.approveUrl(token), act.getString(R.string.scan_with_your_phone), act.getString(R.string.approve_aura_on_tmdb), act.getString(R.string.waiting_for_you_to_approve));
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
                        host.hud(R.drawable.ic_check, act.getString(R.string.signed_in_as, Picks.user(act)) + " · " + act.getString(R.string.picks_from_your_watchlist));
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
            host.hud(R.drawable.ic_info, act.getString(R.string.connect_in_set_up_on));
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
        host.hud(R.drawable.ic_info, act.getString(R.string.handing_the_tv_back));
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
                    host.hud(R.drawable.ic_info, act.getString(R.string.uninstall_aura_in_androids_settings));
                }
            });
        }, "remove").start();
    }

    /** Remote buttons: press one, pick an app; the list of those given so far, each removable. */
    private void buildKeys() {
        Ui.fit(title, act.getString(R.string.remote_buttons), Ui.dp(300), 52, 30);
        if (newKey != null) {
            final String id = HomeKeys.id(newKey[0], newKey[1]);
            note.setText(act.getString(R.string.which_app_should_open, HomeKeys.keyName(id)));
            for (AppRepo.App a : host.apps()) {
                add(a.label, "", "ka:" + a.pkg, () -> {
                    HomeKeys.give(act, newKey[0], newKey[1], a.pkg);
                    host.hud(R.drawable.ic_check, act.getString(R.string.key_opens_app, HomeKeys.keyName(id), a.label));
                    newKey = null;
                    build();
                    focusTag("key:" + id);
                });
            }
            return;
        }
        note.setText(teachingKey ? act.getString(R.string.press_the_button_on_the)
                : act.getString(R.string.give_any_button_on_the));
        for (java.util.Map.Entry<String, String> k : HomeKeys.keys(act).entrySet()) {
            final String tag = "key:" + k.getKey(), id = k.getKey();
            add(HomeKeys.keyName(id), tag.equals(confirmKey) ? act.getString(R.string.press_ok_again_to_remove) : appLabel(k.getValue()), tag, () -> {
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
        add(act.getString(R.string.add_a_button), teachingKey ? act.getString(R.string.press_it_now) : "", "keys:add", this::teachKey);
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
            host.hud(R.drawable.ic_info, act.getString(R.string.switch_on_home_button_in));
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
        Ui.fit(title, act.getString(R.string.set_up_on_this_tv), Ui.dp(300), 52, 30);
        note.setText(adbNote());
        add(act.getString(R.string.connect), adbState.equals("asking") ? act.getString(R.string.waiting_for_you) : Adb.on(act) ? act.getString(R.string.connected) : act.getString(R.string.not_connected_2), "adb:connect", this::adbConnect);
        if (setupNote.isEmpty()) setupNote = Prefs.device(act).getString("setup_note", "");
        if (Adb.on(act)) add(act.getString(R.string.switch_everything_on), setupNote.isEmpty() ? act.getResources().getQuantityString(R.plurals.n_extras, Adb.setup(act).length, Adb.setup(act).length) : setupNote, "adb:setup", this::adbSetup);
        if (Adb.on(act) && Safety.hasGoogleTvHome(act)) {
            if (Safety.googleTvHomeOn(act)) {
                add(act.getString(R.string.retire_google_tv_home), confirmRetire ? act.getString(R.string.press_ok_again_voice_search) : act.getString(R.string.voice_search_side_panel_accounts),
                        "adb:gtv", () -> retireGoogleHome(true));
            } else {
                add(act.getString(R.string.bring_back_google_tv_home), act.getString(R.string.restores_voice_search_side_panel), "adb:gtv", () -> retireGoogleHome(false));
            }
        }
        // newer TVs without plain network debugging: wireless debugging, paired with a code typed on the phone
        if (!Adb.on(act) && Adb.wireless()) add(act.getString(R.string.use_wireless_debugging), act.getString(R.string.pair_with_a_code), "adb:pair", this::openPair);
        // the open door shuts with a setting Aura may change once setup has granted it
        if (Adb.on(act) && Adb.unguarded(act) && Compat.granted(act, android.Manifest.permission.WRITE_SECURE_SETTINGS)) {
            add(act.getString(R.string.turn_network_debugging_off), confirmAdbOff ? act.getString(R.string.press_ok_again) : act.getString(R.string.closes_the_open_door), "adb:off", this::adbOff);
        }
        boolean dev = Settings.Global.getInt(act.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        add(act.getString(R.string.developer_options), dev ? act.getString(R.string.usb_debugging_is_here) : act.getString(R.string.off_about_press_build_7), "adb:dev", () -> {
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
        return s <= 0f ? act.getString(R.string.off) : s < 0.75f ? act.getString(R.string.fast) : s > 1.25f ? act.getString(R.string.slow) : act.getString(R.string.normal);
    }

    /**
     * The whole TV's animation speed, the developer-options trick: Normal, Fast (half as long:
     * every app and menu feels quicker), or Off. Aura's own motion follows the same setting.
     */
    private void cycleAnim() {
        if (!Shield.allowed(act)) {
            host.hud(R.drawable.ic_info, act.getString(R.string.connect_in_set_up_on_2));
            go("tv");
            return;
        }
        float s = animScale(), next = s > 0.75f ? 0.5f : s > 0f ? 0f : 1f;
        try {
            for (String k : ANIM_KEYS) Settings.Global.putFloat(act.getContentResolver(), k, next);
        } catch (SecurityException e) {
            host.hud(R.drawable.ic_info, act.getString(R.string.the_tv_didnt_allow_that));
            return;
        }
        host.hud(R.drawable.ic_bolt, next == 0.5f ? act.getString(R.string.everything_moves_twice_as_fast) : next == 0f ? act.getString(R.string.animations_off) : act.getString(R.string.animations_back_to_normal));
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
                    host.hud(R.drawable.ic_info, act.getString(R.string.couldnt_reach_the_tvs_debugging));
                } else if (!took) {
                    host.hud(R.drawable.ic_info, act.getString(R.string.android_didnt_allow_that_change));
                } else if (retire) {
                    host.hud(R.drawable.ic_check, act.getString(R.string.google_tv_home_retired_bring));
                } else {
                    host.hud(R.drawable.ic_check, act.getString(R.string.google_tv_home_is_back));
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
            return act.getString(R.string.voice_search_google_assistant_in);
        }
        String s = adbState.isEmpty() && Adb.on(act) ? Adb.unguarded(act) ? Adb.OPEN_DOOR : Adb.OK : adbState;
        switch (s) {
            case "asking":
                return act.getString(R.string.if_android_asks_on_the);
            case Adb.OK:
                return act.getString(R.string.aura_is_connected_to_this);
            case Adb.OPEN_DOOR:
                return act.getString(R.string.careful_this_tv_let_aura);
            case Adb.OFF:
                return act.getString(R.string.debugging_is_off_in_developer);
            case Adb.DENIED:
                return act.getString(R.string.android_didnt_let_aura_in);
            case Adb.FAILED:
                return act.getString(R.string.couldnt_reach_the_tvs_debugging_2);
            default:
                return (HomeRole.stubborn(act) && !HomeRole.held(act) ? act.getString(R.string.keeps_the_home_button, HomeRole.other(act)) + " " : "")
                        + act.getString(R.string.aura_switches_on_its_extras);
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
                if (r.equals(Adb.OK)) host.hud(R.drawable.ic_check, act.getString(R.string.connected_to_this_tv));
                else if (r.equals(Adb.OPEN_DOOR)) host.hud(R.drawable.ic_info, act.getString(R.string.connected_but_the_tv_never));
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
        setupNote = act.getString(R.string.n_of_m, 0, Adb.setup(act).length);
        build();
        Adb.runSetup(act, new Adb.Progress() {
            @Override
            public void step(int i, int n, String name) {
                final String now = act.getString(R.string.n_of_m, i + 1, n) + " · " + (name.equals(act.getString(R.string.adb_speed)) ? act.getString(R.string.compiling_about_a_minute) : name);
                act.runOnUiThread(() -> {
                    setupNote = now;
                    if (page.equals("tv")) build();
                });
            }

            @Override
            public void restarting(int ok, int n, List<String> failed) {
                Prefs.device(act).edit().putString("setup_note", failed.isEmpty() ? act.getString(R.string.done_all_n, n)
                        : act.getString(R.string.n_of_m, ok, n) + " · " + act.getString(R.string.not_list, TextUtils.join(", ", failed))).commit();
            }

            @Override
            public void done(int ok, int n, List<String> failed) {
                act.runOnUiThread(() -> {
                    setupRunning = false;
                    setupNote = ok == n ? act.getString(R.string.done_all_n, ok) : act.getString(R.string.n_of_m, ok, n) + " · " + act.getString(R.string.not_list, TextUtils.join(", ", failed));
                    Prefs.device(act).edit().putString("setup_note", setupNote).apply();
                    host.hud(R.drawable.ic_check, ok == n ? act.getString(R.string.everything_is_switched_on) : act.getString(R.string.n_of_m_switched_on, ok, n));
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
            host.hud(R.drawable.ic_info, act.getString(R.string.the_tv_didnt_allow_that));
            return;
        }
        Prefs.device(act).edit().putBoolean("adb_ok", false).putBoolean("adb_open", false).apply();
        adbState = "";
        host.hud(R.drawable.ic_check, act.getString(R.string.network_debugging_off_the_door));
        build();
        focusTag("adb:connect");
    }

    // ---- block ads ----------------------------------------------------------------------------

    /** "In use", else the speed measured here (and whether it blocks), else what it's for. */
    private String filterValue(String host, String about, String current) {
        Long ms = probed.get(host);
        String speed = ms == null ? null : ms < 0 ? act.getString(R.string.unreachable) : ms + " ms" + (blocksAds.contains(host) ? "" : " · " + act.getString(R.string.no_blocking));
        if (host.equals(current)) return act.getString(R.string.in_use) + (speed != null ? "  ·  " + speed : "");
        return speed != null ? speed : about;
    }

    private void pickFilter(String hostname, String name) {
        if (shieldNote != null) return;
        boolean was = Shield.on(act);
        Shield.choose(act, hostname);
        if (!was) {
            host.hud(R.drawable.ic_check, act.getString(R.string.name_chosen, name) + "  ·  " + act.getString(R.string.switch_block_ads_on_to_use));
            build();
            return;
        }
        // already blocking: the TV moves to the new filter now, and it's checked like a fresh switch-on
        checkShield(name);
    }

    private void askDns() {
        inputFor = "dns";
        city.setHint(act.getString(R.string.a_dns_over_tls_hostname));
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
        host.hud(R.drawable.ic_info, act.getString(R.string.trying_name, h));
        new SafeThread(() -> {
            Shield.Probe p = Shield.probe(h);
            act.runOnUiThread(() -> {
                if (p == null) {
                    host.hud(R.drawable.ic_info, act.getString(R.string.couldnt_reach_for_private_dns, h));
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
                host.hud(R.drawable.ic_check, b != null ? act.getString(R.string.fastest_from_this_tv, b) + "  ·  " + bm + " ms" : act.getString(R.string.none_of_the_filters_answered));
                if (page.equals("shield")) build();
                for (String[] f : Shield.FILTERS) if (f[1].equals(b)) focusTag("dns:" + f[0]);
            });
        }, "dns-probe").start();
    }

    private void toggleShield() {
        if (shieldNote != null) return;
        if (!Shield.allowed(act)) {
            host.hud(R.drawable.ic_info, act.getString(R.string.connect_in_set_up_on_3));
            go("tv");
            return;
        }
        if (Shield.on(act)) {
            Shield.set(act, false);
            host.hud(R.drawable.ic_check, act.getString(R.string.ads_are_no_longer_blocked));
            build();
            return;
        }
        if (!Shield.set(act, true)) {
            host.hud(R.drawable.ic_info, act.getString(R.string.this_tv_didnt_allow_it));
            build();
            return;
        }
        checkShield(Shield.name(act));
    }

    /** Confirms the TV is online through the filter; if not, tries another reachable filter or switches back. */
    private void checkShield(String name) {
        shieldNote = act.getString(R.string.checking);
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
                host.hud(done ? R.drawable.ic_check : R.drawable.ic_info, done ? act.getString(R.string.blocking_ads_with, who)
                        : dot ? act.getString(R.string.this_tv_ignores_private_dns)
                        : act.getString(R.string.couldnt_reach_port_853, name));
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
                updateNote = act.getString(R.string.couldnt_reach_github_check_the);
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
                    ? act.getString(R.string.fire_tv_asks_once_whether)
                    : act.getString(R.string.android_asks_once_whether_aura);
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
            if (!opened) host.hud(R.drawable.ic_info, fire ? act.getString(R.string.my_fire_tv_developer_options) : act.getString(R.string.settings_apps_special_app_access));
            return;
        }
        final Updater.Release r = release;
        updateState = "downloading";
        updateNote = act.getString(R.string.starting);
        build();
        new SafeThread(() -> {
            try {
                Updater.install(act, r, (done, total) -> {
                    String t = total > 0 ? act.getString(R.string.downloading_percent, Math.round(100f * done / total)) : act.getString(R.string.downloading);
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
                    updateNote = act.getString(R.string.the_download_stopped_part_way);
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
        // a field's lambda: the panel's own act, set by the time this runs
        Activity a = SettingsPanel.this.act;
        updateNote = (why != null ? a.getString(R.string.tv_wouldnt_install_it, why) : a.getString(R.string.the_tvs_installer_didnt_answer))
                + "\n\n" + a.getString(R.string.install_it_with_downloader);
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
                dropTitle.setText(count > 1 ? act.getString(R.string.receiving_n_of_m, index + 1, count) : act.getString(R.string.receiving_name, shorten(name)));
                dropSub.setText(Math.round(100f * done / total) + "%  ·  " + act.getString(R.string.x_of_y, mb(done), mb(total)));
                dropMeter.setVisibility(View.VISIBLE);
                dropMeter.set(done / (float) total);
            }

            @Override
            public void onAdded(JSONObject e, int index, int count) {
                host.backdrop().show(e.optString("path"));
                dropTitle.setText(act.getString(R.string.name_is_on_your_wall, e.optString("place")));
                int n = Mine.count(act);
                dropSub.setText(index + 1 < count ? act.getString(R.string.more_on_the_way) : act.getResources().getQuantityString(R.plurals.n_photos_from_your_phone, n, n));
                if (index + 1 >= count) dropMeter.setVisibility(View.INVISIBLE);
                thumb(e.optString("id"));
                build();
            }

            @Override
            public void onFailed(String name, String why) {
                dropTitle.setText(act.getString(R.string.couldnt_add, shorten(name)));
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
                dropTitle.setText(n == 0 ? act.getString(R.string.waiting_for_your_phone) : act.getString(R.string.removed_from_your_wall));
                dropSub.setText(n == 0 ? act.getString(R.string.same_wi_fi_no_app) : act.getResources().getQuantityString(R.plurals.n_photos_from_your_phone, n, n));
                build();
            }

            @Override
            public boolean onType(String text) {
                if (keepDrop) {
                    rows.removeCallbacks(stopDropIdle);
                    rows.postDelayed(stopDropIdle, 10 * 60_000L);
                }
                if (dropTitle != null && isOpen() && page.equals("drop")) {
                    dropTitle.setText(act.getString(R.string.typed_from_your_phone));
                    dropSub.setText(act.getString(R.string.quoted, shorten(text)));
                }
                return host.type(text);
            }

            @Override
            public void onApk(String name) {
                dropTitle.setText(act.getString(R.string.install_name_q, shorten(name.replaceFirst("(?i)\\.apk$", ""))));
                dropSub.setText(act.getString(R.string.confirm_on_the_tv_to));
                dropMeter.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onShut() {
                // the page closed itself: said here, or the phone just stops reaching the TV
                keepDrop = false;
                dropTitle.setText(act.getString(R.string.drop_shut));
                dropSub.setText(act.getString(R.string.drop_shut_new_code));
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
        kicker.setText(url != null ? act.getString(R.string.scan_with_your_camera) : act.getString(R.string.not_connected));
        col.addView(kicker);
        TextView addr = Ui.text(act, 11.5f, Ui.medium, 0x8CFFFFFF);
        addr.setText(url != null ? act.getString(R.string.or_open, url.replaceFirst("^http://", "")) : act.getString(R.string.connect_the_tv_to_wi));
        addr.setSingleLine(false);
        addr.setMaxLines(2);
        col.addView(addr, lp(0, 6));
        dropTitle = Ui.text(act, 15, Ui.semibold, 0xFFFFFFFF);
        dropTitle.setEllipsize(TextUtils.TruncateAt.END);
        dropTitle.setText(url != null ? act.getString(R.string.waiting_for_your_phone) : act.getString(R.string.no_network));
        col.addView(dropTitle, lp(0, 22));
        dropSub = Ui.text(act, 12, Ui.regular, 0xA6FFFFFF);
        dropSub.setEllipsize(TextUtils.TruncateAt.END);
        dropSub.setText(url != null ? act.getString(R.string.same_wi_fi_no_app) : "");
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

    private String shorten(String name) {
        String n = name == null || name.isEmpty() ? act.getString(R.string.a_photo) : name;
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
        Ui.shrinkToWidth(name, 11f);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!value.isEmpty()) {
            TextView v = p.tint(Ui.text(act, 12, Ui.medium, 0xFFFFFFFF), 0.7f);
            v.setText(value);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setMaxWidth(Ui.dp(170));
            Ui.shrinkToWidth(v, 10f);
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
        Integer id = HELP.get(tag);
        String text = id == null ? "" : act.getString(id);
        if (text.contentEquals(help.getText())) return;
        final String next = text;
        help.animate().cancel();
        help.animate().alpha(0f).setDuration(90).setStartDelay(0).withEndAction(() -> {
            help.setText(next);
            help.animate().alpha(1f).setDuration(180).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }).start();
    }

    private static final java.util.Map<String, Integer> HELP = new java.util.HashMap<>();

    static {
        // Settings
        HELP.put("role", R.string.help_role);
        HELP.put("profiles", R.string.help_profiles);
        HELP.put("wallpaper", R.string.help_wallpaper);
        HELP.put("drop", R.string.help_drop);
        HELP.put("home", R.string.help_home);
        HELP.put("hidden", R.string.help_hidden);
        HELP.put("picks", R.string.help_picks);
        HELP.put(Prefs.WEATHER, R.string.help_weather);
        HELP.put("city", R.string.help_city);
        HELP.put("auto", R.string.help_auto);
        HELP.put("shield", R.string.help_shield);
        HELP.put("anim", R.string.help_anim);
        HELP.put("tv", R.string.help_tv);
        HELP.put("android", R.string.help_android);
        HELP.put("w:forgive", R.string.help_w_forgive);
        HELP.put("wx:unit", R.string.help_wx_unit);
        HELP.put("trouble", R.string.help_trouble);
        HELP.put("backup", R.string.help_backup);
        HELP.put("updates", R.string.help_updates);
        HELP.put("remove", R.string.help_remove);
        // Home screen
        HELP.put("layout", R.string.help_layout);
        HELP.put("dockside", R.string.help_dockside);
        HELP.put("tiles", R.string.help_tiles);
        HELP.put("grid", R.string.help_grid);
        HELP.put(Prefs.GRID_PAGES, R.string.help_grid_pages);
        HELP.put("names", R.string.help_names);
        HELP.put(Prefs.PARALLAX, R.string.help_parallax);
        HELP.put("onlyapps", R.string.help_onlyapps);
        HELP.put("widgets", R.string.help_widgets);
        HELP.put(Prefs.TOP_SHELF, R.string.help_top_shelf);
        HELP.put(Prefs.PICKS, R.string.help_picks_shown);
        HELP.put(Prefs.INPUT_PREVIEW, R.string.help_input_preview);
        HELP.put("light", R.string.help_light);
        HELP.put("homekeys", R.string.help_homekeys);
        HELP.put("takeover", R.string.help_takeover);
        HELP.put("previews", R.string.help_previews);
        HELP.put("keys", R.string.help_keys);
    }

    // ---- weather city ------------------------------------------------------------------------

    /** A kids' profile waiting for the PIN to be set before it's switched on. */
    private String pinFor;

    private void askPin() {
        inputFor = "pin";
        city.setHint(act.getString(R.string.a_pin_of_4_to));
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
            host.hud(R.drawable.ic_info, act.getString(R.string.n_4_to_8_digits));
            build();
            return;
        }
        Profiles.setPin(act, pin);
        if (pinFor != null) Profiles.setKids(act, pinFor, true);
        host.hud(R.drawable.ic_check, pinFor != null ? act.getString(R.string.kids_profile_on_remember_the) : act.getString(R.string.pin_changed));
        String tag = pinFor != null ? "pr:kids:" + pinFor : "pr:pin";
        pinFor = null;
        build();
        focusTag(tag);
    }

    /** The field again, this time for a new profile's name. */
    private void askProfile() {
        inputFor = "profile";
        city.setHint(act.getString(R.string.a_name_then_ok));
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
            host.hud(R.drawable.ic_info, act.getString(R.string.five_profiles_at_most));
            build();
            return;
        }
        Profiles.use(act, pr.id);
        host.hud(R.drawable.ic_check, act.getString(R.string.hello_name, pr.name) + "  ·  " + act.getString(R.string.a_fresh_home_screen));
        host.applied("profile");
        if (isOpen()) {
            build();
            focusTag("pr:" + pr.id);
        }
    }

    private void askCity() {
        inputFor = "city";
        city.setHint(act.getString(R.string.type_a_city_then_ok));
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
                host.hud(R.drawable.ic_place, f != null ? act.getString(R.string.weather_now_follows, f) : act.getString(R.string.couldnt_find, name));
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
