package app.aura.home;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings' trust pages, under Aura: every server Aura talks to, with its switch (Privacy); what
 * Android has granted Aura and why (Permissions); what the Home button service can and can't see,
 * quoted from its own file (Accessibility); how the TV's debugging stands (Network debugging); and
 * the fingerprints that tell this copy is the real Aura (About). Whatever asks Android or the disk
 * is read once a visit, behind the screen; the pages are drawn from that as often as Settings likes.
 */
final class TrustPages {
    static final String PRIVACY = "privacy", PERMS = "perms", A11Y = "a11y", DEBUGGING = "debugging", ABOUT = "about";

    /** The permissions Aura asks for: what each is for (a feature's name) and, where it needs one, why in plain words. */
    private static final String[] NAMES = {
            "READ_LOGS", "DUMP", "WRITE_SECURE_SETTINGS", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "READ_TV_LISTINGS",
            "PACKAGE_USAGE_STATS", "BLUETOOTH_CONNECT", "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES",
            "KILL_BACKGROUND_PROCESSES", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "ACCESS_NETWORK_STATE",
            "REQUEST_DELETE_PACKAGES", "BLUETOOTH", "BLUETOOTH_ADMIN", "INTERNET", "RECEIVE_BOOT_COMPLETED",
            "CHANGE_WIFI_MULTICAST_STATE", "MODIFY_AUDIO_SETTINGS"};
    private static final int[] FOR = {
            R.string.profile_button, R.string.wi_fi_name, R.string.block_ads_animations, R.string.beat_glow, R.string.wi_fi_sheet,
            R.string.continue_watching, R.string.smart_stack_today, R.string.bluetooth, R.string.updates, R.string.apps,
            R.string.close_all, R.string.wi_fi, R.string.wi_fi_sheet, R.string.wi_fi, R.string.uninstall,
            R.string.bluetooth, R.string.bluetooth, R.string.privacy, R.string.home_button,
            R.string.play_from_phones_pcs, R.string.beat_glow};
    private static final int[] WHY = {
            R.string.perm_logs, R.string.perm_dump, R.string.perm_secure, R.string.perm_audio, R.string.perm_location,
            R.string.perm_setup, R.string.perm_setup, R.string.perm_setup, 0, R.string.perm_normal,
            R.string.perm_normal, R.string.perm_normal, R.string.perm_normal, R.string.perm_normal, R.string.perm_normal,
            R.string.perm_normal, R.string.perm_normal, R.string.perm_normal, R.string.perm_normal,
            R.string.perm_normal, R.string.perm_normal};
    /**
     * What setup grants with "pm grant", which Aura can take back the same way over its debugging
     * link: debugging's own permissions anywhere, and from Android 6 the ones Android asks about.
     */
    private static final String[] GRANTED_BY_SETUP = {"WRITE_SECURE_SETTINGS", "DUMP", "READ_LOGS", "PACKAGE_USAGE_STATS"},
            ASKED_BY_ANDROID = {"ACCESS_FINE_LOCATION", "READ_TV_LISTINGS", "RECORD_AUDIO", "BLUETOOTH_CONNECT"};

    /** One permission Android says Aura holds: its full name, Android's own words for it, and its description. */
    static final class Perm {
        final String name, label, about;

        Perm(String name, String label, String about) {
            this.name = name;
            this.label = label;
            this.about = about;
        }

        /** "READ_LOGS" for android.permission.READ_LOGS. */
        String shortName() {
            return name.startsWith("android.permission.") ? name.substring(19) : name;
        }
    }

    /** How the TV's debugging stood as the page was opened, and whether it's a Fire TV (which names it differently). */
    static final class Door {
        final boolean on, port, wireless, mayShut, fire;

        Door(boolean on, boolean port, boolean wireless, boolean mayShut, boolean fire) {
            this.on = on;
            this.port = port;
            this.wireless = wireless;
            this.mayShut = mayShut;
            this.fire = fire;
        }
    }

    private final SettingsPanel s;
    private final Activity act;
    /** What Android has granted, read once a visit; null until it's in. */
    private List<Perm> perms;
    private boolean permsReading, keysOn, mediaOn, adminOn;
    /** The permission whose Revoke waits for a second OK. */
    private String confirmRevoke;
    private Door door;
    private boolean doorReading, hashing;
    /** The APK's and its signing certificate's SHA-256, lower-case hex ("" when unreadable), worked out once a run. */
    private static String apkSha, certSha;

    TrustPages(SettingsPanel s) {
        this.s = s;
        act = s.act;
    }

    static boolean owns(String page) {
        return page.equals(PRIVACY) || page.equals(PERMS) || page.equals(A11Y) || page.equals(DEBUGGING) || page.equals(ABOUT);
    }

    /** A visit begins: what may have changed since the last one is read afresh. */
    void entered(String page) {
        if (page.equals(PERMS)) perms = null;
        if (page.equals(DEBUGGING)) door = null;
    }

    void disarm() {
        confirmRevoke = null;
    }

    void build(String page) {
        switch (page) {
            case PRIVACY:
                privacy();
                break;
            case PERMS:
                permissions();
                break;
            case A11Y:
                accessibility();
                break;
            case DEBUGGING:
                debugging();
                break;
            default:
                about();
                break;
        }
    }

    /** The main list's line for Network debugging: how Aura's own link stands. */
    static String link(Context c) {
        return c.getString(!Adb.on(c) ? R.string.not_connected_2 : Adb.unguarded(c) ? R.string.connected_open_to_your_wi : R.string.connected);
    }

    // ---- privacy ------------------------------------------------------------------------------

    /** Every destination in the code, under what it's for; each switch is the same one as on its own page. */
    private void privacy() {
        s.head(act.getString(R.string.privacy), act.getString(R.string.privacy_note));
        group(R.string.weather);
        pref("Open-Meteo · GeoJS", Prefs.WEATHER, "weather", "pv:weather",
                "api.open-meteo.com\ngeocoding-api.open-meteo.com\nget.geojs.io\n\n" + act.getString(R.string.help_city));
        group(R.string.photo_sources);
        pref(act.getString(R.string.fresh_hd_photographs), Prefs.DAILY, "daily", "pv:daily", act.getString(R.string.where_the_walls_photographs_come));
        pref("Windows Spotlight", Prefs.SRC_SPOTLIGHT, "sources", "pv:spotlight", "fd.api.iris.microsoft.com");
        pref("Bing", Prefs.SRC_BING, "sources", "pv:bing", "www.bing.com\nbing.npanuhin.me");
        pref("Wikimedia Commons", Prefs.SRC_WIKIMEDIA, "sources", "pv:wikimedia", "commons.wikimedia.org\nupload.wikimedia.org");
        pref("Wallhaven", Prefs.SRC_WALLHAVEN, "sources", "pv:wallhaven", "wallhaven.cc\nw.wallhaven.cc");
        String unsplash = "api.unsplash.com\nimages.unsplash.com";
        // never asked without your own key
        if (Gallery.unsplashKey(act).isEmpty()) fact("Unsplash", onOff(false), "pv:unsplash", unsplash);
        else pref("Unsplash", Prefs.SRC_UNSPLASH, "sources", "pv:unsplash", unsplash);
        // TVmaze, or TMDB once it has your key: the one not in use is never asked
        group(R.string.tonights_pick);
        String maze = "api.tvmaze.com\nstatic.tvmaze.com", tmdb = "api.themoviedb.org\nimage.tmdb.org";
        boolean key = Picks.tmdb(act);
        if (key) fact("TVmaze", onOff(false), "pv:tvmaze", maze);
        else pref("TVmaze", Prefs.PICKS, "picks", "pv:tvmaze", maze);
        if (key) pref("TMDB", Prefs.PICKS, "picks", "pv:tmdb", tmdb);
        else fact("TMDB", onOff(false), "pv:tmdb", tmdb);
        group(R.string.new_today_on_your_apps);
        pref("TVmaze", Prefs.STACK_NEW, "cards", "pv:fresh", maze);
        // the aerials stream on Android 7 and later only
        boolean aerials = Build.VERSION.SDK_INT >= 24;
        String apple = "sylvan.apple.com\n\n" + act.getString(R.string.aerials_use_data);
        if (aerials) {
            group(R.string.music_scenes);
            pref("Apple", Prefs.SCENES, "scenes", "pv:scenes", apple);
        }
        // the screensaver's scene decides: quotes in Auto and the quote scenes, aerials where it streams them
        String mode = Prefs.saver(act);
        group(R.string.screensaver);
        fact("ZenQuotes · DummyJSON", onOff(!mode.equals("photos") && !mode.equals("aerials")), "pv:quotes", "zenquotes.io\ndummyjson.com");
        if (aerials) fact("Apple", onOff(Prefs.aerialSaver(act, mode)), "pv:saver", apple);
        group(R.string.updates);
        fact("GitHub", "", "pv:github", "api.github.com\ngithub.com\n*.githubusercontent.com");
        group(R.string.test_speed);
        fact("Cloudflare", "", "pv:speed", "speed.cloudflare.com");
        if (Build.VERSION.SDK_INT >= 28) {
            group(R.string.block_ads);
            fact(Shield.name(act), onOff(Shield.on(act)), "pv:dns", Shield.host(act) + "\n\n" + act.getString(R.string.help_shield));
        }
        group(R.string.continue_watching);
        fact(act.getString(R.string.apps), "", "pv:apps", act.getString(R.string.privacy_apps_help));
        group(R.string.play_from_phones_pcs);
        pref("DLNA", Prefs.DLNA, "dlna", "pv:dlna", "UPnP · SSDP 239.255.255.250:1900 · HTTP 49494");
    }

    /** A section named by a title the rest of Settings already uses: "WEATHER", "PHOTO SOURCES". */
    private void group(int name) {
        s.section(act.getString(name).toUpperCase(Locale.getDefault()));
    }

    /** A destination with a switch: OK flips it, as on the page it belongs to. */
    private void pref(String who, String key, String applied, String tag, String help) {
        s.rowHelp.put(tag, help);
        s.add(who, onOff(Prefs.on(act, key)), tag, () -> {
            Prefs.set(act, key, !Prefs.on(act, key));
            s.host.applied(applied);
            s.build();
            s.focusTag(tag);
        });
    }

    /** A row that only tells: its sentence shows beside the list while it has focus. */
    private void fact(String label, String value, String tag, String help) {
        s.rowHelp.put(tag, help);
        s.add(label, value, tag, () -> { });
    }

    private String onOff(boolean on) {
        return act.getString(on ? R.string.on : R.string.off);
    }

    // ---- permissions --------------------------------------------------------------------------

    /** Whatever Android reports as granted, each with what it's for; Revoke where setup granted it and the link is up. */
    private void permissions() {
        s.head(act.getString(R.string.permissions), act.getString(R.string.permissions_note));
        List<Perm> list = perms;
        if (list == null) {
            readPermissions();
            fact(act.getString(R.string.permissions), act.getString(R.string.checking), "pm:wait", "");
            return;
        }
        // what Android lets Aura do beyond permissions, each listed only while it's switched on
        if (keysOn) fact(act.getString(R.string.home_button_in_apps), onOff(true), "pm:keys", act.getString(R.string.home_keys_about));
        if (mediaOn) fact(act.getString(R.string.now_playing), onOff(true), "pm:media", act.getString(R.string.perm_setup));
        if (adminOn) fact(act.getString(R.string.standby), onOff(true), "pm:admin", act.getString(R.string.perm_admin));
        // Android wouldn't say, or granted nothing: the page still has a row to stand on, or focus
        // falls to the list itself and nothing on screen is lit
        if (list.isEmpty() && !keysOn && !mediaOn && !adminOn) fact(act.getString(R.string.permissions), act.getString(R.string.none_granted), "pm:none", act.getString(R.string.permissions_note));
        boolean link = Adb.on(act);
        for (final Perm p : list) {
            String tag = "pm:" + p.name;
            s.rowHelp.put(tag, why(p) + "\n\n" + p.shortName());
            if (link && revocable(p.shortName())) {
                s.add(p.label, act.getString(p.name.equals(confirmRevoke) ? R.string.press_ok_again : R.string.revoke), tag, () -> revoke(p));
            } else {
                s.add(p.label, "", tag, () -> { });
            }
        }
    }

    /** What a permission is for, then why; Android's own description for one this list doesn't know. */
    private String why(Perm p) {
        String n = p.shortName();
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(n)) return act.getString(FOR[i]) + (WHY[i] != 0 ? " · " + act.getString(WHY[i]) : "");
        }
        return p.about;
    }

    private static boolean revocable(String shortName) {
        for (String n : GRANTED_BY_SETUP) if (n.equals(shortName)) return true;
        if (Build.VERSION.SDK_INT < 23) return false;
        for (String n : ASKED_BY_ANDROID) if (n.equals(shortName)) return true;
        return false;
    }

    /** The granted permissions as Android reports them (what dumpsys lists), and the special accesses, behind the screen. */
    private void readPermissions() {
        if (permsReading) return;
        permsReading = true;
        final Context app = act.getApplicationContext();
        new SafeThread(() -> {
            List<Perm> out = new ArrayList<>();
            PackageManager pm = app.getPackageManager();
            try {
                PackageInfo pi = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
                String[] names = pi.requestedPermissions;
                int[] flags = pi.requestedPermissionsFlags;
                for (int i = 0; names != null && i < names.length; i++) {
                    if (flags != null && i < flags.length && (flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) out.add(perm(pm, names[i]));
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // Android wouldn't say this time: the special accesses below still are
            }
            String listeners = Settings.Secure.getString(app.getContentResolver(), "enabled_notification_listeners");
            android.app.admin.DevicePolicyManager dpm = Compat.svc(app, android.app.admin.DevicePolicyManager.class);
            final boolean keys = HomeKeys.on(app), media = listeners != null && listeners.contains(app.getPackageName() + "/"),
                    admin = dpm != null && dpm.isAdminActive(new android.content.ComponentName(app, SleepAdmin.class));
            act.runOnUiThread(() -> {
                permsReading = false;
                perms = out;
                keysOn = keys;
                mediaOn = media;
                adminOn = admin;
                if (s.page.equals(PERMS) && s.isOpen()) s.build();
            });
        }, "permissions", () -> {
            permsReading = false;
            if (perms == null) perms = new ArrayList<>();
            if (s.page.equals(PERMS) && s.isOpen()) s.build();
        }).start();
    }

    /** Android's own name for a permission ("read sensitive log data") and its description, in the TV's language. */
    private static Perm perm(PackageManager pm, String name) {
        String label = "", about = "";
        try {
            PermissionInfo info = pm.getPermissionInfo(name, 0);
            CharSequence l = info.loadLabel(pm), d = info.loadDescription(pm);
            if (l != null && l.length() > 0 && !l.toString().equals(name)) label = l.toString();
            if (d != null) about = d.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        String shown = label.isEmpty() ? name.substring(name.lastIndexOf('.') + 1) : Character.toUpperCase(label.charAt(0)) + label.substring(1);
        return new Perm(name, shown, about);
    }

    /** Takes a permission back over the TV's debugging after a second OK; Android may restart Aura as it does. */
    private void revoke(Perm p) {
        if (!p.name.equals(confirmRevoke)) {
            confirmRevoke = p.name;
            s.build();
            return;
        }
        confirmRevoke = null;
        // a permission's name, and nothing a shell would read as more
        if (!p.name.matches("[A-Za-z0-9_.]+")) return;
        final Context app = act.getApplicationContext();
        new SafeThread(() -> {
            List<String> out = Adb.run(app, "pm revoke " + app.getPackageName() + " " + p.name);
            final String said = out == null ? null : out.get(0);
            act.runOnUiThread(() -> {
                boolean ok = said != null && !said.contains("Exception") && !said.contains("Error");
                s.host.hud(ok ? R.drawable.ic_check : R.drawable.ic_info, said == null ? act.getString(R.string.couldnt_reach_the_tvs_debugging)
                        : ok ? act.getString(R.string.name_removed, p.label) : act.getString(R.string.android_didnt_allow_that_change));
                readPermissions();
            });
        }, "revoke").start();
    }

    // ---- accessibility ------------------------------------------------------------------------

    /**
     * The Home button service in its own words: res/xml/home_keys.xml, quoted line by line as written
     * there (a change to one is a change to the other), then its switch and the switcher pictures'.
     */
    private void accessibility() {
        s.head(act.getString(R.string.accessibility), act.getString(R.string.home_keys_about));
        String file = "res/xml/home_keys.xml\n";
        fact("canRetrieveWindowContent", "false", "ax:screen", file + act.getString(R.string.a11y_screen));
        fact("accessibilityEventTypes", "typeWindowStateChanged", "ax:window", file + act.getString(R.string.a11y_window));
        fact("canRequestFilterKeyEvents", "true", "ax:keys", file + act.getString(R.string.a11y_keys));
        // the one picture it may take, from Android 11: the app in front, as Home is pressed
        if (Build.VERSION.SDK_INT >= 30) fact("canTakeScreenshot", "true", "ax:shot", file + act.getString(R.string.help_previews));
        boolean on = HomeKeys.on(act);
        s.rowHelp.put("ax:on", act.getString(R.string.help_homekeys));
        s.add(act.getString(R.string.home_button_in_apps), onOff(on), "ax:on", () -> s.toggleHomeKeys("ax:on"));
        if (Build.VERSION.SDK_INT >= 30 && on) {
            s.rowHelp.put("ax:previews", act.getString(R.string.help_previews));
            s.add(act.getString(R.string.pictures_in_the_switcher), act.getString(Previews.on(act) ? R.string.apps_as_you_left_them : R.string.off),
                    "ax:previews", () -> s.togglePreviews("ax:previews"));
        }
    }

    // ---- network debugging --------------------------------------------------------------------

    /** Plain network debugging on port 5555 against wireless debugging's paired, encrypted link, and the way to shut it. */
    private void debugging() {
        s.head(act.getString(R.string.network_debugging), act.getString(R.string.debugging_note));
        Door d = door;
        if (d == null) lookAtDoor();
        String wait = act.getString(R.string.checking);
        // a TV that let Aura in without asking lets anything on the Wi-Fi in the same way
        fact("Port 5555", d == null ? wait : onOff(d.port), "nd:port", act.getString(Adb.unguarded(act) ? R.string.careful_this_tv_let_aura : R.string.port_help));
        if (Build.VERSION.SDK_INT >= 30) fact(act.getString(R.string.wireless_debugging), d == null ? wait : onOff(d.wireless), "nd:tls", act.getString(R.string.tls_help));
        fact(act.getString(R.string.set_up_on_this_tv), link(act), "nd:link", act.getString(R.string.help_tv));
        // the switch there is today: debugging off for the whole TV, Aura's own link included
        if (d != null && d.on && d.mayShut) {
            s.add(act.getString(R.string.turn_network_debugging_off), act.getString(s.confirmAdbOff ? R.string.press_ok_again : R.string.closes_the_open_door), "nd:off", () -> {
                if (s.confirmAdbOff) door = null;
                s.adbOff();
            });
        }
        s.add(act.getString(R.string.developer_options), d == null ? "" : act.getString(d.fire ? R.string.adb_debugging_is_here : R.string.usb_debugging_is_here), "nd:dev", () -> {
            try {
                s.host.open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), s.panel);
            } catch (RuntimeException e) {
                s.host.open(new Intent(Settings.ACTION_SETTINGS), s.panel);
            }
        });
    }

    /** How the TV's debugging stands, asked behind the screen: port 5555 is tried on the TV itself. */
    private void lookAtDoor() {
        if (doorReading) return;
        doorReading = true;
        final Context app = act.getApplicationContext();
        new SafeThread(() -> {
            ContentResolver r = app.getContentResolver();
            final Door d = new Door(Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) != 0,
                    Adb.probeLocalPort(400, Adb.PORT) == Adb.PORT,
                    Build.VERSION.SDK_INT >= 30 && Settings.Global.getInt(r, "adb_wifi_enabled", 0) != 0,
                    Compat.granted(app, android.Manifest.permission.WRITE_SECURE_SETTINGS), Trouble.fireTv(app));
            act.runOnUiThread(() -> {
                doorReading = false;
                door = d;
                if (s.page.equals(DEBUGGING) && s.isOpen()) s.build();
            });
        }, "debugging", () -> doorReading = false).start();
    }

    // ---- about --------------------------------------------------------------------------------

    /** The version, and the two fingerprints to hold against the GitHub release page. */
    private void about() {
        s.head(act.getString(R.string.about_aura), act.getString(R.string.about_note));
        if (apkSha == null || certSha == null) fingerprints();
        String wait = act.getString(R.string.checking);
        fact(act.getString(R.string.version_name, s.version()), "MIT", "ab:version", "github.com/cookedzera/aura-tv");
        fact("APK · SHA-256", apkSha == null ? wait : brief(apkSha), "ab:apk", apkSha == null ? "" : grouped(apkSha));
        fact(act.getString(R.string.signing_certificate) + " · SHA-256", certSha == null ? wait : brief(certSha), "ab:cert",
                certSha == null ? "" : grouped(certSha));
    }

    /** Worked out behind the screen once a run of Aura: the APK is read whole. */
    private void fingerprints() {
        if (hashing) return;
        hashing = true;
        final Context app = act.getApplicationContext();
        new SafeThread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            final String apk = sha256(app.getPackageCodePath()), cert = certificate(app);
            act.runOnUiThread(() -> {
                hashing = false;
                apkSha = apk;
                certSha = cert;
                if (s.page.equals(ABOUT) && s.isOpen()) s.build();
            });
        }, "fingerprints", () -> hashing = false).start();
    }

    private static String sha256(String path) {
        try (InputStream in = new FileInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    /** The signing certificate's SHA-256, as apksigner prints it. */
    @SuppressWarnings("deprecation")
    private static String certificate(Context c) {
        try {
            android.content.pm.Signature[] sig = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            if (sig == null || sig.length == 0) return "";
            return hex(MessageDigest.getInstance("SHA-256").digest(sig[0].toByteArray()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** "3f2a9b1c…e4d2c0a1": enough to tell two apart in a row. */
    private static String brief(String h) {
        return h.length() < 16 ? "—" : h.substring(0, 8) + "…" + h.substring(h.length() - 8);
    }

    /** The whole digest in four lines of four groups, to read off against the release page. */
    private static String grouped(String h) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length(); i += 4) {
            if (i > 0) b.append(i % 16 == 0 ? '\n' : ' ');
            b.append(h, i, Math.min(h.length(), i + 4));
        }
        return b.toString();
    }
}
