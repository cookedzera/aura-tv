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
    private boolean appsPage, dropPage, confirmClear;
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
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(424), Ui.dp(530), Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = Ui.dp(78);
        panel.addView(right, rl);
        panel.setVisibility(View.GONE);
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open() {
        appsPage = false;
        dropPage = false;
        build();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setStartDelay(0).setDuration(260).setInterpolator(Ui.SOFT).start();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    void close() {
        stopDrop();
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
        if (dropPage) {
            stopDrop();
            dropPage = false;
            build();
            focusTag("drop");
            return true;
        }
        return false;
    }

    // ---- pages -------------------------------------------------------------------------------

    private void build() {
        String keep = focusedTag();
        rows.removeAllViews();
        if (appsPage) {
            Ui.fit(title, "Apps", Ui.dp(300), 52, 30);
            note.setText("Hidden apps leave your grid. They stay installed, and you can bring them back here.");
            for (AppRepo.App a : host.apps()) {
                boolean hidden = Prefs.hidden(act).contains(a.pkg);
                add(a.label, hidden ? "Hidden" : "Shown", "app:" + a.pkg, () -> {
                    Prefs.hide(act, a.pkg, !Prefs.hidden(act).contains(a.pkg));
                    host.applied("apps");
                    build();
                });
            }
        } else if (dropPage) {
            Ui.fit(title, "From your phone", Ui.dp(300), 52, 30);
            note.setText("Point your phone's camera at the code, then choose photos or GIFs. They land on your wall and stay on the TV.");
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200));
            cl.bottomMargin = Ui.dp(8);
            rows.addView(dropCard, cl);
            toggle("Show", Prefs.MINE_ONLY, "Only your photos", "Everything", "photos");
            int n = Mine.count(act);
            if (n > 0) add("Remove your photos", confirmClear ? "Press OK again" : n + (n == 1 ? " photo" : " photos"), "clear", () -> {
                if (!confirmClear) {
                    confirmClear = true;
                } else {
                    confirmClear = false;
                    Mine.clear(act);
                    host.applied("photos");
                }
                build();
            });
        } else {
            Ui.fit(title, "Settings", Ui.dp(300), 52, 30);
            Weather.Now w = Weather.now(act);
            note.setText("Changes apply at once. Everything here can be undone the same way.");
            toggle("Tiles", Prefs.GLASS_TILES, "Glass", "Original banners", "tiles");
            toggle("App names", Prefs.NAMES_ALWAYS, "Always", "On focus", "names");
            toggle("Smart Stack", Prefs.STACK_AUTO, "Changes on its own", "Stays put", "stack");
            toggle("Sky moments", Prefs.MOMENTS, "On", "Off", "moments");
            toggle("Tonight's pick", Prefs.PICKS, "On", "Off", "picks");
            toggle("Picture of the Day", Prefs.DAILY, "On", "Off", "daily");
            int mine = Mine.count(act);
            add("From your phone", mine > 0 ? mine + (mine == 1 ? " photo" : " photos") : "Photos and GIFs", "drop", this::openDrop);
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

    private void focusTag(String tag) {
        for (int i = 0; i < rows.getChildCount(); i++) if (tag.equals(rows.getChildAt(i).getTag())) rows.getChildAt(i).requestFocus();
    }

    // ---- from your phone ---------------------------------------------------------------------

    private void openDrop() {
        dropPage = true;
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
                build();
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
