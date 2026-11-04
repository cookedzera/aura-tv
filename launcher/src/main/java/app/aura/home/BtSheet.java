package app.aura.home;

import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Bluetooth headphones and speakers without leaving Control Center: the radio on or off, your
 * paired audio devices with battery level when they report it, one OK to connect or disconnect,
 * and a shortcut to Android's pairing screen at the foot of the list.
 */
final class BtSheet extends GlassFrame {
    interface Host {
        void hud(int icon, String text);

        void openSettings(Intent intent);
    }

    private static final int BLUE = 0xFF2F7BFF;

    private final Activity act;
    private final Host host;
    private final BluetoothAdapter bt;
    private final TextView status, toggleText;
    private final Pad toggle;
    private final LinearLayout list;
    private BluetoothProfile a2dp;
    private boolean listening;

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            rebuild();
        }
    };

    BtSheet(Activity a, Backdrop b, Host h) {
        super(a, b, 26, 0.5f);
        act = a;
        host = h;
        bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null) {
            try {
                bt.getProfileProxy(a.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        a2dp = proxy;
                        if (isOpen()) act.runOnUiThread(BtSheet.this::rebuild);
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        a2dp = null;
                    }
                }, BluetoothProfile.A2DP);
            } catch (SecurityException ignored) {
            }
        }
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
        title.setText("Bluetooth");
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

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(18));
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(2);
        sl.leftMargin = sl.rightMargin = -Ui.dp(10);
        col.addView(scroll, sl);
        addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(View.GONE);
    }

    boolean isOpen() {
        return getVisibility() == View.VISIBLE;
    }

    void open() {
        setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 31 && !Compat.granted(act, "android.permission.BLUETOOTH_CONNECT")) {
            act.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 8);
        }
        if (!listening) {
            IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
            act.registerReceiver(changes, f);
            listening = true;
        }
        rebuild();
        setAlpha(0f);
        setTranslationX(Ui.dp(24));
        animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(380).setInterpolator(Ui.EMPHASIZED).start();
        View first = list.getChildCount() > 0 ? list.getChildAt(0) : toggle;
        first.requestFocus();
    }

    void close() {
        if (listening) {
            act.unregisterReceiver(changes);
            listening = false;
        }
        setVisibility(View.GONE);
    }

    void release() {
        close();
        if (bt != null && a2dp != null) {
            try {
                bt.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            } catch (RuntimeException ignored) {
            }
            a2dp = null;
        }
    }

    /** The connected headphones' name for the Control Center caption, or "Bluetooth" when none is connected. */
    String connectedName() {
        if (bt == null || !bt.isEnabled()) return act.getString(R.string.bluetooth_off);
        try {
            if (a2dp != null) {
                for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                    String n = d.getName();
                    if (n != null && !n.isEmpty()) return n;
                }
                return "Bluetooth";
            }
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (isAudio(d) && isConnected(d)) {
                        String n = d.getName();
                        if (n != null && !n.isEmpty()) return n;
                    }
                }
            }
        } catch (SecurityException ignored) {
        }
        return "Bluetooth";
    }

    /** Headphones, speakers and receivers; never the TV's own Bluetooth remote, keyboard or gamepad. */
    private static boolean isAudio(BluetoothDevice b) {
        if (b.getType() == BluetoothDevice.DEVICE_TYPE_LE) return false;
        BluetoothClass bc = b.getBluetoothClass();
        if (bc == null) return false;
        int major = bc.getMajorDeviceClass();
        if (major == BluetoothClass.Device.Major.PERIPHERAL) return false;
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
                || bc.hasService(BluetoothClass.Service.RENDER)
                || bc.hasService(BluetoothClass.Service.AUDIO);
    }

    private static final class Dev {
        BluetoothDevice raw;
        String name, addr;
        boolean connected, connecting, audio;
        int battery = -1;
    }

    private void rebuild() {
        boolean on = bt != null && bt.isEnabled();
        toggle.plainColor = on ? BLUE : 0x26FFFFFF;
        toggleText.setText(on ? act.getString(R.string.on) : act.getString(R.string.off));
        toggle.invalidate();

        List<Dev> devs = new ArrayList<>();
        Dev active = null;
        if (on) {
            try {
                Set<BluetoothDevice> bonded = bt.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice b : bonded) {
                        int st = a2dp != null ? a2dp.getConnectionState(b) : BluetoothProfile.STATE_DISCONNECTED;
                        boolean a2dpConn = st == BluetoothProfile.STATE_CONNECTED, a2dpWait = st == BluetoothProfile.STATE_CONNECTING;
                        if (!a2dpConn && !a2dpWait && !isAudio(b)) continue;
                        Dev d = new Dev();
                        d.raw = b;
                        d.addr = b.getAddress();
                        String n = b.getName();
                        d.name = n != null && !n.isEmpty() ? n : d.addr;
                        d.connected = a2dp != null ? a2dpConn : isConnected(b);
                        d.connecting = a2dpWait;
                        d.audio = true;
                        d.battery = batteryLevel(b);
                        if (d.connected && active == null) active = d;
                        devs.add(d);
                    }
                }
            } catch (SecurityException ignored) {
            }
        }
        Collections.sort(devs, (x, y) -> {
            if (x.connected != y.connected) return x.connected ? -1 : 1;
            if (x.connecting != y.connecting) return x.connecting ? -1 : 1;
            if (x.audio != y.audio) return x.audio ? -1 : 1;
            return x.name.compareToIgnoreCase(y.name);
        });

        if (!on) status.setText(act.getString(R.string.off));
        else if (active != null) status.setText(act.getString(R.string.connected_to, active.name) + (active.battery >= 0 ? "  ·  " + active.battery + "%" : ""));
        else status.setText(devs.isEmpty() ? act.getString(R.string.no_paired_devices) : act.getString(R.string.ready_to_connect));

        View focused = act.getCurrentFocus();
        String keep = focused != null && focused.getParent() == list ? (String) focused.getTag() : null;
        list.removeAllViews();
        for (Dev d : devs) list.addView(row(d), rowParams());
        if (on) {
            list.addView(action(act.getString(R.string.pair_new_device), "", false,
                    () -> host.openSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), rowParams());
        }
        boolean kept = false;
        if (keep != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (keep.equals(list.getChildAt(i).getTag())) kept = list.getChildAt(i).requestFocus();
            }
        }
        if (!kept && isOpen() && keep != null && list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private static LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        p.topMargin = Ui.dp(5);
        return p;
    }

    private Pad row(Dev d) {
        Pad p = new Pad(act, null, 14, 0f);
        p.plainColor = d.connected ? 0x2630D158 : 0x14FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.03f;
        p.setTag(d.addr);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        r.addView(p.tint(ControlCenter.icon(act, R.drawable.ic_bluetooth), d.connected ? 1f : 0.75f),
                new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
        TextView name = p.tint(Ui.text(act, 13, Ui.semibold, 0xFFFFFFFF), 1f);
        name.setText(d.name);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.leftMargin = Ui.dp(10);
        r.addView(name, nl);
        String tag = d.connected ? act.getString(R.string.connected_caps) + (d.battery >= 0 ? "  ·  " + d.battery + "%" : "")
                : d.connecting ? act.getString(R.string.connecting_2) : d.audio ? act.getString(R.string.audio) : act.getString(R.string.paired);
        TextView t = p.tint(Ui.caps(act, 6.5f, 0xFFFFFFFF, 0.2f), d.connected ? 1f : 0.6f);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(tag);
        r.addView(t);
        p.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onClick = () -> choose(d);
        return p;
    }

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

    private void flip() {
        if (bt == null) return;
        try {
            boolean on = !bt.isEnabled();
            boolean ok = on ? bt.enable() : bt.disable();
            if (!ok) {
                host.openSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                return;
            }
            status.setText(on ? act.getString(R.string.turning_on) : act.getString(R.string.turning_off));
        } catch (SecurityException e) {
            host.openSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private void choose(Dev d) {
        if (a2dp != null) {
            try {
                String method = d.connected ? "disconnect" : "connect";
                Object ok = a2dp.getClass().getMethod(method, BluetoothDevice.class).invoke(a2dp, d.raw);
                if (Boolean.TRUE.equals(ok)) {
                    status.setText(act.getString(d.connected ? R.string.disconnecting_ellipsis : R.string.connecting_to_ellipsis, d.name));
                    host.hud(R.drawable.ic_bluetooth, act.getString(d.connected ? R.string.disconnecting : R.string.connecting_to, d.name));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        host.openSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    static boolean isConnected(BluetoothDevice d) {
        try {
            Object r = BluetoothDevice.class.getMethod("isConnected").invoke(d);
            return Boolean.TRUE.equals(r);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Connected remotes, controllers and headphones running low (20% or less), as {name, level,
     * address}: Android knows each one's battery (the same reading its own Bluetooth settings show)
     * but no app is told. Off the main thread.
     */
    static java.util.List<String[]> lowBatteries(android.content.Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return out;
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (!isConnected(d)) continue;
                int level = batteryLevel(d);
                if (level <= 0 || level > 20) continue;
                String name = d.getName() != null && !d.getName().trim().isEmpty() ? d.getName().trim() : c.getString(R.string.your_remote);
                out.add(new String[]{name, String.valueOf(level), d.getAddress()});
            }
        } catch (RuntimeException ignored) {
            // no Bluetooth to ask, or not allowed to: nothing to say
        }
        return out;
    }

    static int batteryLevel(BluetoothDevice d) {
        try {
            Object r = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(d);
            if (r instanceof Integer) {
                int v = (Integer) r;
                if (v >= 0 && v <= 100) return v;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
