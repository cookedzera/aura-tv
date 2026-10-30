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

        void hud(int icon, String text);
    }

    final FrameLayout panel;
    private final Activity act;
    private final Host host;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final TextView title, note;
    private final EditText city;
    /** The page on show: "" for the main list, else "apps", "widgets", "wallpaper", "drop" or "updates". */
    private String page = "";
    private boolean confirmClear;
    /** The photo whose removal waits for a second OK. */
    private String confirmPhoto;
    /** The profile whose removal waits for a second OK. */
    private String confirmProfile;
    /** "Checking…" while Block ads confirms the filter works; null otherwise. */
    private String shieldNote;
    /** What the text field is for: "city" or "dns". */
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

    SettingsPanel(Activity a, Host h) {
        act = a;
        host = h;
        panel = HomeActivity.flat(a);
        GlassFrame veil = new GlassFrame(a, h.backdrop(), 0, 0.5f);
        veil.edgeLight = false;
        panel.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(a);
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
        rows = new LinearLayout(a);
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

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open() {
        page = "";
        build();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setStartDelay(0).setDuration(260).setInterpolator(Ui.SOFT).start();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    /** Every few seconds while the Wallpaper page is open and flyovers are arriving: "3 of 5 ready". */
    private final Runnable flyoverTick = () -> {
        if (isOpen() && page.equals("wallpaper")) build();
    };

    /** Straight to one page, as when Android's screensaver settings hand over to Aura. */
    void show(String p) {
        go(p);
    }

    /** Redraws the page on show, after something outside changed what it says. */
    void refresh() {
        if (isOpen()) build();
    }

    void close() {
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
            if (was.equals("drop")) stopDrop();
            page = "";
            build();
            // back on the main list, on the row that led to the page
            focusTag(was.equals("apps") ? "hidden" : was);
            return true;
        }
        return false;
    }

    private void go(String p) {
        page = p;
        build();
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) {
                rows.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    // ---- pages -------------------------------------------------------------------------------

    private void build() {
        String keep = focusedTag();
        rows.removeAllViews();
        note.setMaxLines(page.equals("updates") ? 10 : 4);
        if (page.equals("apps")) {
            Ui.fit(title, "Apps", Ui.dp(300), 52, 30);
            note.setText("Hidden apps leave your grid. They stay installed, and you can bring them back here.");
            // inputs hidden from home first (hold OK on an input's tile), by their name on this TV
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) act.getSystemService(android.content.Context.TV_INPUT_SERVICE);
            for (String h : Prefs.hidden(act)) {
                if (!h.startsWith("input:")) continue;
                android.media.tv.TvInputInfo info = tim == null ? null : tim.getTvInputInfo(h.substring(6));
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
            int si = java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act));
            add("Tiles", Prefs.TILE_NAMES[Math.max(0, si)], "tiles", () -> {
                // OK steps through the styles; the grid changes straight away behind the panel
                String next = Prefs.TILE_STYLES[(Math.max(0, si) + 1) % Prefs.TILE_STYLES.length];
                Prefs.of(act).edit().putString(Prefs.TILE_STYLE, next).apply();
                host.applied("tiles");
                build();
                focusTag("tiles");
            });
            toggle("App names", Prefs.NAMES_ALWAYS, "Always", "On focus", "names");
            int hiddenW = Prefs.hiddenWidgets(act).size();
            add("Widgets", hiddenW == 0 ? "All shown" : (3 - hiddenW) + " of 3 shown", "widgets", () -> go("widgets"));
            toggle("Top Shelf", Prefs.TOP_SHELF, "On", "Off", "shelf");
            toggle("Parallax icons", Prefs.PARALLAX, "On", "Off", "parallax");
            toggle("Live picture on input tiles", Prefs.INPUT_PREVIEW, "On", "Off", "inputs");
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
            note.setText("Hide the ones you don't use and the rest widen to fill the row. Holding OK on a widget moves or hides it too.");
            java.util.Set<String> hidden = Prefs.hiddenWidgets(act);
            for (int i : Prefs.widgetOrder(act)) {
                String id = HomeActivity.WIDGET_IDS[i];
                add(HomeActivity.WIDGET_NAMES[i], hidden.contains(id) ? "Hidden" : "Shown", "w:" + id, () -> {
                    boolean hide = !Prefs.hiddenWidgets(act).contains(id);
                    // one always stays: an empty row would leave a hole above your apps
                    if (hide && Prefs.hiddenWidgets(act).size() >= HomeActivity.WIDGET_IDS.length - 1) {
                        host.hud(R.drawable.ic_info, "Keep at least one widget");
                        return;
                    }
                    Prefs.hideWidget(act, id, hide);
                    host.applied("widgets");
                    build();
                });
            }
            toggle("Stack pages", Prefs.STACK_AUTO, "Turn on their own", "Stay put", "stack");
            add("Original order", "", "w:reset", () -> {
                Prefs.setWidgetOrder(act, java.util.Arrays.asList(0, 1, 2));
                host.applied("widgets");
                build();
            });
        } else if (page.equals("shield")) {
            Ui.fit(title, "Block ads", Ui.dp(300), 52, 30);
            note.setText("Ad and tracker servers get no answer, in every app at once. Pick a filter, or find the fastest one from this TV.");
            add("Block ads", shieldNote != null ? shieldNote : Shield.on(act) ? "On" : "Off", "shield:on", this::toggleShield);
            String current = Shield.host(act);
            boolean custom = true;
            for (String[] f : Shield.FILTERS) {
                if (f[0].equals(current)) custom = false;
                add(f[1], filterValue(f[0], f[2], current), "dns:" + f[0], () -> pickFilter(f[0], f[1]));
            }
            add("Your own DNS", custom ? filterValue(current, current, current) : "Type a hostname", "dns:custom", this::askDns);
            add("Find the fastest", probing ? "Measuring…" : "", "dns:probe", this::findFastest);
        } else if (page.equals("wallpaper")) {
            Ui.fit(title, "Wallpaper", Ui.dp(300), 52, 30);
            note.setText(Flyovers.supported()
                    ? "Photographs that follow the sky, or pure black for OLED. The screensaver can hang masterpieces from an open museum collection, show the real wind over the country right now, tonight's real sky, or Earth from the space station. Auto moves between them."
                    : "Photographs that follow the sky, or pure black for OLED. The screensaver can hang masterpieces from an open museum collection, show the real wind over the country right now, or tonight's real sky. Auto moves between them.");
            toggle("Wallpaper", Prefs.BLACK, "Pure black", "Photographs", "photos");
            if (!Prefs.on(act, Prefs.BLACK)) {
                toggle("Picture of the Day", Prefs.DAILY, "On", "Off", "daily");
                toggle("Sky moments", Prefs.MOMENTS, "On", "Off", "moments");
            }
            // the screensaver's scene; Earth from space only where flyovers can play
            final List<String> modes = new java.util.ArrayList<>(java.util.Arrays.asList(Prefs.SAVERS));
            if (!Flyovers.supported()) modes.remove("space");
            String mode = Prefs.saver(act);
            add("Screensaver", Prefs.SAVER_NAMES[java.util.Arrays.asList(Prefs.SAVERS).indexOf(mode)]
                    + (mode.equals("auto") ? " · by the light and weather" : ""), Prefs.SAVER, () -> {
                String next = modes.get((modes.indexOf(Prefs.saver(act)) + 1) % modes.size());
                Prefs.of(act).edit().putString(Prefs.SAVER, next).apply();
                if (next.equals("space") && !Prefs.on(act, Prefs.FLYOVERS)) {
                    Prefs.set(act, Prefs.FLYOVERS, true);
                    Flyovers.schedule(act);
                    host.hud(R.drawable.ic_check, "Earth from space · arriving over Wi-Fi");
                }
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
                add("TV turns off after", Ambient.span(Ambient.offMs(act)) + " without use", "saver:off", () -> {
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
            // a hardware VP9 decoder plays them for almost nothing; without one, no row
            if (Flyovers.supported()) {
                add("Screensaver flyovers", Flyovers.status(act), Prefs.FLYOVERS, () -> {
                    boolean on = !Prefs.on(act, Prefs.FLYOVERS);
                    Prefs.set(act, Prefs.FLYOVERS, on);
                    if (on) {
                        Flyovers.schedule(act);
                        host.hud(R.drawable.ic_check, "Earth from space · arriving over Wi-Fi");
                    } else {
                        Flyovers.clear(act);
                    }
                    build();
                    focusTag(Prefs.FLYOVERS);
                });
                // still downloading: the count keeps up while the page is open
                rows.removeCallbacks(flyoverTick);
                if (Prefs.on(act, Prefs.FLYOVERS) && !Flyovers.status(act).equals("On")) rows.postDelayed(flyoverTick, 3000);
            }
        } else if (page.equals("updates")) {
            Ui.fit(title, "Updates", Ui.dp(300), 52, 30);
            String mine = Updater.installed(act);
            switch (updateState) {
                case "checking":
                    note.setText("Looking for a newer Aura on GitHub…");
                    break;
                case "available":
                case "downloading":
                    note.setText("Aura " + release.version + " is out.\n\n" + release.notes);
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
        } else if (page.equals("drop")) {
            Ui.fit(title, "From your phone", Ui.dp(300), 52, 30);
            note.setText("Point your phone's camera at the code, then choose photos, GIFs or apps. Photos land on your wall; an app asks here before it installs.");
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
                        "p:" + id, android.graphics.BitmapFactory.decodeFile(Mine.thumb(act, id).getPath()), () -> {
                            if (!id.equals(confirmPhoto)) {
                                confirmPhoto = id;
                                build();
                                return;
                            }
                            confirmPhoto = null;
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
                Mine.clear(act);
                host.applied("photos");
                back();
            });
        } else if (page.equals("tv")) {
            buildTv();
        } else if (page.equals("keys")) {
            buildKeys();
        } else {
            Ui.fit(title, "Settings", Ui.dp(300), 52, 30);
            Weather.Now w = Weather.now(act);
            note.setText("Changes apply at once. Everything here can be undone the same way.");
            // only while Home still opens another home screen; Android itself asks
            if (!HomeRole.held(act)) add("Home button", "Opens another home · OK to change", "role", () -> {
                if (!HomeRole.ask(act)) host.hud(R.drawable.ic_info, "Press Home on the remote and choose Aura, then Always");
            });
            add("Home screen", Prefs.TILE_NAMES[Math.max(0, java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act)))]
                    + " tiles", "home", () -> go("home"));
            add("Profile", Profiles.name(act), "profiles", () -> go("profiles"));
            add("Wallpaper", Prefs.on(act, Prefs.BLACK) ? "Pure black" : "Photographs", "wallpaper", () -> go("wallpaper"));
            int mine = Mine.count(act);
            add("From your phone", mine > 0 ? mine + (mine == 1 ? " photo" : " photos") : "Photos, GIFs, apps", "drop", this::openDrop);
            // Private DNS arrived with Android 9: older TVs don't get the row at all
            if (android.os.Build.VERSION.SDK_INT >= 28) add("Block ads", Shield.on(act) ? "On · " + Shield.name(act) : "Off", "shield", () -> go("shield"));
            add("Animations", animLabel(), "anim", this::cycleAnim);
            toggle("Tonight's pick", Prefs.PICKS, "On", "Off", "picks");
            toggle("Weather", Prefs.WEATHER, "On", "Off", "weather");
            boolean manual = Prefs.device(act).getBoolean("geo_manual", false);
            String place = w != null && !w.city.isEmpty() ? w.city : "Not found yet";
            add("Weather city", (manual ? "" : "Auto · ") + place, "city", this::askCity);
            if (manual) add("Use my connection's city", "", "auto", () -> {
                Prefs.device(act).edit().putBoolean("geo_manual", false).putLong("geo_at", 0).putLong("wx_at", 0).apply();
                host.applied("weather");
                build();
            });
            add("Hidden apps", String.valueOf(Prefs.hidden(act).size()), "hidden", () -> go("apps"));
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
            add("Set up on this TV", Adb.on(act) ? Adb.unguarded(act) ? "Connected · open to your Wi-Fi" : "Connected" : "No computer needed", "tv", () -> go("tv"));
            add("Android settings", "", "android", () -> host.open(new Intent(Settings.ACTION_SETTINGS), panel));
        }
        if (keep != null) {
            for (int i = 0; i < rows.getChildCount(); i++) if (keep.equals(rows.getChildAt(i).getTag())) rows.getChildAt(i).requestFocus();
        }
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
        // the open door shuts with a setting Aura may change once setup has granted it
        if (Adb.on(act) && Adb.unguarded(act) && Compat.granted(act, android.Manifest.permission.WRITE_SECURE_SETTINGS)) {
            add("Turn network debugging off", confirmAdbOff ? "Press OK again" : "Closes the open door", "adb:off", this::adbOff);
        }
        add("Developer options", "Network debugging is here", "adb:dev", () -> {
            try {
                host.open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), panel);
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
            host.hud(R.drawable.ic_info, "Run setup-aura.ps1 once to let Aura change this");
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

    private String adbNote() {
        String s = adbState.isEmpty() && Adb.on(act) ? Adb.unguarded(act) ? Adb.OPEN_DOOR : Adb.OK : adbState;
        switch (s) {
            case "asking":
                return "If Android asks on the screen, choose Always allow from this computer, then OK.";
            case Adb.OK:
                return "Aura is connected to this TV. Switch everything on grants what the PC setup grants, and closing an app in the switcher now stops it fully.";
            case Adb.OPEN_DOOR:
                return "Careful: this TV let Aura in without asking. Anything on your Wi-Fi can control it the same way. Switch everything on, then turn network debugging off.";
            case Adb.OFF:
                return "Network debugging is off. In Developer options switch on Network debugging (or USB debugging), then Connect again. No Developer options? Android settings, Device Preferences, About: press OK on Build seven times.";
            case Adb.DENIED:
                return "Android didn't let Aura in in time. If you chose Always allow, Connect again: it goes straight in now. Otherwise Connect, and choose Always allow when it asks.";
            case Adb.FAILED:
                return "Couldn't reach the TV's debugging service. Try again in a moment.";
            default:
                return "Aura can switch on its extras from the TV itself, no computer needed, through the TV's network debugging, the way a PC would. Android asks once: choose Always allow.";
        }
    }

    private void adbConnect() {
        if (adbState.equals("asking") || setupRunning) return;
        adbState = "asking";
        build();
        new Thread(() -> {
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
        final String[][] steps = Adb.setup(act);
        setupNote = "0 of " + steps.length;
        build();
        new Thread(() -> {
            int done = 0;
            List<String> failed = new java.util.ArrayList<>();
            for (int i = 0; i < steps.length; i++) {
                final String now = (i + 1) + " of " + steps.length + (steps[i][0].equals("Speed") ? " · compiling, about a minute" : " · " + steps[i][0]);
                act.runOnUiThread(() -> {
                    setupNote = now;
                    if (page.equals("tv")) build();
                });
                // the last step (install permission) makes Android restart Aura: the result is kept first
                if (i == steps.length - 1 && steps[i][0].equals("Updates")) {
                    Prefs.device(act).edit().putString("setup_note", failed.isEmpty() ? "Done · all " + steps.length
                            : done + 1 + " of " + steps.length + " · not " + TextUtils.join(", ", failed)).commit();
                }
                List<String> out = Adb.run(act, steps[i][1]);
                String o = out == null ? null : out.get(0);
                // pm and settings say nothing when it works; the rest print Success; "already" is fine too
                if (o != null && (!o.contains("Exception") && !o.contains("Error") || o.contains("already"))) done++;
                else failed.add(steps[i][0]);
                if (out == null) break;
            }
            final int ok = done;
            act.runOnUiThread(() -> {
                setupRunning = false;
                setupNote = ok == steps.length ? "Done · all " + ok : ok + " of " + steps.length + " · not " + TextUtils.join(", ", failed);
                Prefs.device(act).edit().putString("setup_note", setupNote).apply();
                host.hud(R.drawable.ic_check, ok == steps.length ? "Everything is switched on" : ok + " of " + steps.length + " switched on");
                if (page.equals("tv")) {
                    build();
                    focusTag("adb:setup");
                }
            });
        }, "adb-setup").start();
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
        new Thread(() -> {
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
        new Thread(() -> {
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
            host.hud(R.drawable.ic_info, "Run setup-aura.ps1 once to let Aura block ads");
            return;
        }
        if (Shield.on(act)) {
            Shield.set(act, false);
            host.hud(R.drawable.ic_check, "Ads are no longer blocked");
            build();
            return;
        }
        Shield.set(act, true);
        checkShield(Shield.name(act));
    }

    /** Confirms the TV is online through the filter; if not, blocking goes off again at once. */
    private void checkShield(String name) {
        shieldNote = "Checking…";
        build();
        new Thread(() -> {
            boolean ok = Shield.verify(act);
            // never leave the TV without internet: switch straight back if the filter can't be reached
            if (!ok) Shield.set(act, false);
            act.runOnUiThread(() -> {
                shieldNote = null;
                host.hud(ok ? R.drawable.ic_check : R.drawable.ic_info,
                        ok ? "Blocking ads with " + name : "Couldn't reach " + name + ", so blocking is off again");
                build();
            });
        }, "shield").start();
    }

    // ---- updates ------------------------------------------------------------------------------

    private void checkUpdate() {
        updateState = "checking";
        build();
        new Thread(() -> {
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
            // Android asks once whether Aura may install apps
            try {
                act.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                        android.net.Uri.parse("package:" + act.getPackageName())));
            } catch (RuntimeException e) {
                host.hud(R.drawable.ic_info, "Allow Aura to install apps: Settings, Apps, Special app access");
            }
            return;
        }
        final Updater.Release r = release;
        updateState = "downloading";
        updateNote = "Starting…";
        build();
        new Thread(() -> {
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
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    updateState = "error";
                    updateNote = "The download stopped part-way. Check the connection, then try again.";
                    if (page.equals("updates")) build();
                });
            }
        }, "update").start();
    }

    private void openDrop() {
        page = "drop";
        confirmClear = false;
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
        if (drop != null) drop.stop();
        drop = null;
    }

    /** The code to scan beside what's happening: waiting, a progress bar, then what just arrived. */
    private View dropCard(String url) {
        LinearLayout card = new LinearLayout(act);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(2), 0, 0, 0);
        View code = url != null ? new Code(act, Qr.encode(url)) : new View(act);
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
    private static final class Code extends View {
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
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(35));
        l.topMargin = Ui.dp(4);
        rows.addView(p, l);
    }

    // ---- weather city ------------------------------------------------------------------------

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
        new Thread(() -> {
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
