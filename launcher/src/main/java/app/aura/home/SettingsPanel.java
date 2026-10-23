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
    private boolean appsPage;

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
            findCity(city.getText().toString().trim());
            return true;
        });
        right.addView(city, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40)));
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(24));
        scroll.setClipToPadding(false);
        // room on every side for a focused row to grow into, so it's never clipped
        scroll.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        scroll.setClipChildren(false);
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // tall enough that the whole main list fits without scrolling: no row is ever cut at an edge
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(424), Ui.dp(500), Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = Ui.dp(78);
        panel.addView(right, rl);
        panel.setVisibility(View.GONE);
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open() {
        appsPage = false;
        build();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setStartDelay(0).setDuration(260).setInterpolator(Ui.SOFT).start();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    void close() {
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
        if (appsPage) {
            appsPage = false;
            build();
            if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            return true;
        }
        return false;
    }

    // ---- pages -------------------------------------------------------------------------------

    private void build() {
        String keep = focusedTag();
        rows.removeAllViews();
        if (appsPage) {
            title.setText("Apps");
            note.setText("Hidden apps leave your grid. They stay installed, and you can bring them back here.");
            for (AppRepo.App a : host.apps()) {
                boolean hidden = Prefs.hidden(act).contains(a.pkg);
                add(a.label, hidden ? "Hidden" : "Shown", "app:" + a.pkg, () -> {
                    Prefs.hide(act, a.pkg, !Prefs.hidden(act).contains(a.pkg));
                    host.applied("apps");
                    build();
                });
            }
        } else {
            title.setText("Settings");
            Weather.Now w = Weather.now(act);
            note.setText("Changes apply at once. Everything here can be undone the same way.");
            toggle("Tiles", Prefs.GLASS_TILES, "Glass", "Original banners", "tiles");
            toggle("App names", Prefs.NAMES_ALWAYS, "Always", "On focus", "names");
            toggle("Smart Stack", Prefs.STACK_AUTO, "Changes on its own", "Stays put", "stack");
            toggle("Sky moments", Prefs.MOMENTS, "On", "Off", "moments");
            toggle("Tonight's pick", Prefs.PICKS, "On", "Off", "picks");
            toggle("Picture of the Day", Prefs.DAILY, "On", "Off", "daily");
            toggle("Weather", Prefs.WEATHER, "On", "Off", "weather");
            boolean manual = Prefs.of(act).getBoolean("geo_manual", false);
            String place = w != null && !w.city.isEmpty() ? w.city : "Not found yet";
            add("Weather city", (manual ? "" : "Auto · ") + place, "city", this::askCity);
            if (manual) add("Use my connection's city", "", "auto", () -> {
                Prefs.of(act).edit().putBoolean("geo_manual", false).putLong("geo_at", 0).putLong("wx_at", 0).apply();
                host.applied("weather");
                build();
            });
            add("Hidden apps", String.valueOf(Prefs.hidden(act).size()), "hidden", () -> {
                appsPage = true;
                build();
                if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            });
            add("Android settings", "", "android", () -> host.open(new Intent(Settings.ACTION_SETTINGS), panel));
        }
        if (keep != null) {
            for (int i = 0; i < rows.getChildCount(); i++) if (keep.equals(rows.getChildAt(i).getTag())) rows.getChildAt(i).requestFocus();
        }
    }

    private void toggle(String label, String key, String onText, String offText, String what) {
        add(label, Prefs.on(act, key) ? onText : offText, key, () -> {
            Prefs.set(act, key, !Prefs.on(act, key));
            host.applied(what);
            build();
        });
    }

    private String focusedTag() {
        View f = act.getCurrentFocus();
        return f != null && f.getParent() == rows ? (String) f.getTag() : null;
    }

    /** One choice: its name on the left, its value on the right; OK changes it. */
    private void add(String label, String value, String tag, Runnable run) {
        Pad p = new Pad(act, null, 16, 0f);
        p.plainColor = 0x1AFFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.03f;
        p.setTag(tag);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
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
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        l.topMargin = Ui.dp(5);
        rows.addView(p, l);
    }

    // ---- weather city ------------------------------------------------------------------------

    private void askCity() {
        city.setText("");
        city.setVisibility(View.VISIBLE);
        city.requestFocus();
        city.post(() -> act.getSystemService(InputMethodManager.class).showSoftInput(city, InputMethodManager.SHOW_IMPLICIT));
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
                    Prefs.of(act).edit().putBoolean("geo_manual", true).putFloat("geo_lat", (float) r.getDouble("latitude"))
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
        act.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(city.getWindowToken(), 0);
    }

    private static LinearLayout.LayoutParams lp(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }
}
