package app.aura.home;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wi-Fi without leaving Control Center: the radio on or off, the network you're on (OK to
 * disconnect), saved networks first, then everything nearby by signal; new secured networks ask
 * for a password with the TV keyboard. Listens for Wi-Fi changes only while it's open.
 */
final class WifiSheet extends GlassFrame {
    interface Host {
        void hud(int icon, String text);

        void openSettings(Intent intent);
    }

    private static final int BLUE = 0xFF2F7BFF, GREEN = 0xFF30D158;

    private final Activity act;
    private final Host host;
    private final WifiManager wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView status, toggleText;
    private final Pad toggle;
    private final LinearLayout list, join;
    private final ScrollView scroll;
    private final EditText password;
    private final TextView joinLabel;
    private String joinSsid;
    private boolean listening;

    /**
     * One fresh scan when the sheet opens; after that the system's own periodic results are
     * enough. A scan takes the radio off its channel for a moment, which some TVs (and the
     * hotspots they hang off) don't take kindly to, so it's never repeated on a timer.
     */
    private final Runnable scan;
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            rebuild();
        }
    };

    WifiSheet(Activity a, Backdrop b, Host h) {
        super(a, b, 26, 0.5f);
        act = a;
        host = h;
        wifi = a.getApplicationContext().getSystemService(WifiManager.class);
        scan = () -> {
            if (wifi.isWifiEnabled()) wifi.startScan();
        };
        setClipContent(false);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(12));
        col.setClipChildren(false);
        col.setClipToPadding(false);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(a, 21, Ui.heavy, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setText("Wi-Fi");
        titles.addView(title);
        status = Ui.text(a, 11, Ui.regular, 0xB3FFFFFF);
        status.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(status);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toggle = new Pad(a, null, 15, 0f);
        toggle.edgeLight = false;
        toggle.focusScale = 1.08f;
        toggleText = toggle.tint(Ui.text(a, 11, Ui.bold, 0xFFFFFFFF), 1f);
        toggleText.setGravity(Gravity.CENTER);
        toggle.addView(toggleText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toggle.onClick = this::flip;
        head.addView(toggle, new LinearLayout.LayoutParams(Ui.dp(58), Ui.dp(30)));
        col.addView(head);

        // asked only when joining a new secured network
        join = new LinearLayout(a);
        join.setGravity(Gravity.CENTER_VERTICAL);
        join.setVisibility(View.GONE);
        LinearLayout jcol = new LinearLayout(a);
        jcol.setOrientation(LinearLayout.VERTICAL);
        joinLabel = Ui.caps(a, 6.5f, 0x99FFFFFF, 0.2f);
        jcol.addView(joinLabel);
        password = new EditText(a);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_GO);
        password.setTextColor(0xFFFFFFFF);
        password.setHintTextColor(0x66FFFFFF);
        password.setHint("Password");
        password.setTextSize(13);
        password.setTypeface(Ui.medium);
        password.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        GradientDrawable field = new GradientDrawable();
        field.setColor(0x1FFFFFFF);
        field.setCornerRadius(Ui.dpf(12));
        field.setStroke(Math.max(1, Ui.hair()), 0x40FFFFFF);
        password.setBackground(field);
        password.setOnEditorActionListener((v, actionId, e) -> {
            joinWithPassword();
            return true;
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(34));
        pl.topMargin = Ui.dp(4);
        jcol.addView(password, pl);
        join.addView(jcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Pad go = new Pad(a, null, 15, 0f);
        go.plainColor = BLUE;
        go.edgeLight = false;
        TextView gt = go.tint(Ui.text(a, 11, Ui.bold, 0xFFFFFFFF), 1f);
        gt.setText("Join");
        gt.setGravity(Gravity.CENTER);
        go.addView(gt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        go.onClick = this::joinWithPassword;
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(58), Ui.dp(34));
        gl.leftMargin = Ui.dp(10);
        gl.topMargin = Ui.dp(14);
        join.addView(go, gl);
        LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jl.topMargin = Ui.dp(12);
        col.addView(join, jl);

        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(18));
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(8);
        col.addView(scroll, sl);
        addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(View.GONE);
    }

    boolean isOpen() {
        return getVisibility() == View.VISIBLE;
    }

    void open() {
        setVisibility(View.VISIBLE);
        join.setVisibility(View.GONE);
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 7);
        }
        if (!listening) {
            IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            act.registerReceiver(changes, f);
            listening = true;
        }
        handler.removeCallbacks(scan);
        handler.post(scan);
        rebuild();
        setAlpha(0f);
        setTranslationX(Ui.dp(24));
        animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(380).setInterpolator(Ui.EMPHASIZED).start();
        View first = list.getChildCount() > 0 ? list.getChildAt(0) : toggle;
        first.requestFocus();
    }

    void close() {
        handler.removeCallbacks(scan);
        if (listening) {
            act.unregisterReceiver(changes);
            listening = false;
        }
        hideKeyboard();
        setVisibility(View.GONE);
    }

    /** Back first leaves the password field, then the sheet. */
    boolean back() {
        if (join.getVisibility() == View.VISIBLE) {
            join.setVisibility(View.GONE);
            hideKeyboard();
            rebuild();
            return true;
        }
        return false;
    }

    // ---- the list ----------------------------------------------------------------------------

    private static final class Net {
        String ssid, caps;
        int level, configId = -1;
        boolean connected;

        boolean open() {
            return caps == null || !(caps.contains("WEP") || caps.contains("PSK") || caps.contains("SAE") || caps.contains("EAP"));
        }
    }

    private static String unquote(String s) {
        return s != null && s.length() > 1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private void rebuild() {
        boolean on = wifi.isWifiEnabled();
        toggle.plainColor = on ? BLUE : 0x26FFFFFF;
        toggleText.setText(on ? "On" : "Off");
        toggle.invalidate();

        WifiInfo info = wifi.getConnectionInfo();
        String current = info != null ? unquote(info.getSSID()) : null;
        ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
        // without location access WifiInfo hides the network's name; the older NetworkInfo still
        // carries it on many Android TV builds, so try that before giving up on the name
        if (current == null || current.isEmpty() || "<unknown ssid>".equals(current)) current = legacyName(cm);
        boolean named = current != null && !current.isEmpty() && !"<unknown ssid>".equals(current);
        SupplicantState state = info != null ? info.getSupplicantState() : null;
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean onWifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean connected = on && (onWifi || (info != null && info.getNetworkId() != -1 && state == SupplicantState.COMPLETED));
        if (!on) status.setText("Off");
        else if (connected) status.setText(named ? "Connected to " + current : "Connected · the name shows when location is on");
        else if (state == SupplicantState.ASSOCIATING || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE || state == SupplicantState.GROUP_HANDSHAKE) status.setText("Connecting…");
        else status.setText("Not connected");

        Map<String, Net> nets = new HashMap<>();
        if (on) {
            try {
                for (ScanResult r : wifi.getScanResults()) {
                    if (r.SSID == null || r.SSID.isEmpty()) continue;
                    Net n = nets.get(r.SSID);
                    int level = WifiManager.calculateSignalLevel(r.level, 4);
                    if (n == null) {
                        n = new Net();
                        n.ssid = r.SSID;
                        nets.put(r.SSID, n);
                    }
                    if (level >= n.level) {
                        n.level = level;
                        n.caps = r.capabilities;
                    }
                }
                List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
                if (saved != null) {
                    for (WifiConfiguration c : saved) {
                        Net n = nets.get(unquote(c.SSID));
                        if (n != null) n.configId = c.networkId;
                    }
                }
            } catch (SecurityException ignored) {
                // no location permission yet: only the current network is known
            }
            if (connected && named) {
                Net n = nets.get(current);
                if (n == null) {
                    n = new Net();
                    n.ssid = current;
                    n.level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
                    n.configId = info.getNetworkId();
                    nets.put(current, n);
                }
                n.connected = true;
            }
        }
        List<Net> sorted = new ArrayList<>(nets.values());
        sorted.sort((x, y) -> {
            if (x.connected != y.connected) return x.connected ? -1 : 1;
            if ((x.configId >= 0) != (y.configId >= 0)) return x.configId >= 0 ? -1 : 1;
            return y.level != x.level ? y.level - x.level : x.ssid.compareToIgnoreCase(y.ssid);
        });

        View focused = act.getCurrentFocus();
        String keep = focused != null && focused.getParent() == list ? (String) focused.getTag() : null;
        list.removeAllViews();
        for (Net n : sorted) list.addView(row(n), rowParams());
        if (on && sorted.isEmpty()) {
            // Android lists nearby networks only while location is on. Without it (some TVs drop
            // Wi-Fi when location is switched on) the essentials still work from here.
            if (connected) {
                list.addView(action(named ? current : "Disconnect", "CONNECTED", true, () -> {
                    wifi.disconnect();
                    host.hud(R.drawable.ic_wifi, "Disconnected");
                }), rowParams());
            } else {
                list.addView(action("Reconnect", "", false, () -> {
                    wifi.reconnect();
                    status.setText("Connecting…");
                }), rowParams());
            }
        }
        if (on) {
            list.addView(action("Other networks…", "", false,
                    () -> host.openSettings(new Intent(Settings.ACTION_WIFI_SETTINGS))), rowParams());
        }
        if (keep != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (keep.equals(list.getChildAt(i).getTag())) list.getChildAt(i).requestFocus();
            }
        } else if (isOpen() && focused != null && focused.getParent() == list && list.getChildCount() > 0) {
            list.getChildAt(0).requestFocus();
        }
    }

    private static LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        p.topMargin = Ui.dp(5);
        return p;
    }

    /** One network: signal arcs, its name, and what OK will do. */
    private Pad row(Net n) {
        Pad p = new Pad(act, null, 14, 0f);
        p.plainColor = n.connected ? 0x2630D158 : 0x14FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.03f;
        p.setTag(n.ssid);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        r.addView(p.tint(new Signal(act, p, n.level), 1f), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(14)));
        TextView name = p.tint(Ui.text(act, 13, Ui.semibold, 0xFFFFFFFF), 1f);
        name.setText(n.ssid);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.leftMargin = Ui.dp(10);
        r.addView(name, nl);
        String tag = n.connected ? "CONNECTED" : n.configId >= 0 ? "SAVED" : n.open() ? "OPEN" : "";
        if (!tag.isEmpty()) {
            TextView t = p.tint(Ui.caps(act, 6.5f, 0xFFFFFFFF, 0.2f), n.connected ? 1f : 0.6f);
            t.setShadowLayer(0, 0, 0, 0);
            t.setText(tag);
            r.addView(t);
        }
        p.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onClick = () -> choose(n);
        return p;
    }

    @SuppressWarnings("deprecation")
    private static String legacyName(ConnectivityManager cm) {
        if (cm == null) return null;
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI ? unquote(ni.getExtraInfo()) : null;
    }

    /** A row that does one thing:the same glass as a network row, a label and an optional tag. */
    private Pad action(String label, String tag, boolean live, Runnable run) {
        Pad p = new Pad(act, null, 14, 0f);
        p.plainColor = live ? 0x2630D158 : 0x14FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.03f;
        p.setTag("action:" + label);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(14), 0, Ui.dp(12), 0);
        TextView name = p.tint(Ui.text(act, 13, Ui.semibold, 0xFFFFFFFF), 1f);
        name.setText(label);
        name.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!tag.isEmpty()) {
            TextView t = p.tint(Ui.caps(act, 6.5f, 0xFFFFFFFF, 0.2f), 1f);
            t.setShadowLayer(0, 0, 0, 0);
            t.setText(tag);
            r.addView(t);
        }
        p.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onClick = run;
        return p;
    }

    // ---- actions -----------------------------------------------------------------------------

    private void flip() {
        boolean on = !wifi.isWifiEnabled();
        if (!wifi.setWifiEnabled(on)) {
            host.openSettings(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        status.setText(on ? "Turning on…" : "Turning off…");
    }

    private void choose(Net n) {
        if (n.connected) {
            wifi.disconnect();
            host.hud(R.drawable.ic_wifi, "Disconnected from " + n.ssid);
        } else if (n.configId >= 0) {
            connect(n.configId, n.ssid);
        } else if (n.caps != null && n.caps.contains("EAP")) {
            // enterprise sign-in needs certificates and identities: the system screen handles those
            host.openSettings(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } else if (n.open()) {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + n.ssid + "\"";
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            connect(wifi.addNetwork(c), n.ssid);
        } else {
            joinSsid = n.ssid;
            joinLabel.setText(("Password for " + n.ssid).toUpperCase(Locale.getDefault()));
            password.setText("");
            join.setVisibility(View.VISIBLE);
            password.requestFocus();
            InputMethodManager imm = act.getSystemService(InputMethodManager.class);
            password.post(() -> imm.showSoftInput(password, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void joinWithPassword() {
        String pass = password.getText().toString();
        if (joinSsid == null || pass.length() < 8) {
            host.hud(R.drawable.ic_info, "Wi-Fi passwords are at least 8 characters");
            return;
        }
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + joinSsid + "\"";
        c.preSharedKey = "\"" + pass + "\"";
        c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        int id = wifi.addNetwork(c);
        hideKeyboard();
        join.setVisibility(View.GONE);
        connect(id, joinSsid);
    }

    private void connect(int id, String ssid) {
        if (id < 0 || !wifi.enableNetwork(id, true)) {
            host.hud(R.drawable.ic_info, "Couldn't join " + ssid);
            return;
        }
        wifi.reconnect();
        status.setText("Connecting to " + ssid + "…");
        host.hud(R.drawable.ic_wifi, "Joining " + ssid);
    }

    private void hideKeyboard() {
        act.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(password.getWindowToken(), 0);
    }

    /** Wi-Fi strength as three arcs and a dot, in the row's current content colour. */
    static final class Signal extends View {
        private final Pad host;
        private final int level;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Signal(Context c, Pad host, int level) {
            super(c);
            this.host = host;
            this.level = level;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dpf(1.6f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, base = getHeight() - Ui.dpf(1.5f);
            for (int i = 1; i <= 3; i++) {
                float r = getHeight() * (0.32f * i);
                box.set(cx - r, base - r, cx + r, base + r);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(host.content(level >= i ? 1f : 0.25f));
                c.drawArc(box, 225, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(host.content(1f));
            c.drawCircle(cx, base - Ui.dpf(0.5f), Ui.dpf(1.4f), p);
        }
    }
}
