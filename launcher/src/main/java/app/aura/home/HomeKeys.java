package app.aura.home;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Home twice from inside any app opens the switcher straight over it, as the TV button does on
 * Apple TV, instead of passing through the home screen first; Home held opens Control Center over
 * it. Android hands Home to the launcher only after it has already gone home, so this needs to see
 * the button sooner: an accessibility service, which sees nothing on screen, only which app is in
 * front and the keys. A single press still goes home, a fifth of a second later, once it's clear
 * no second press is coming.
 *
 * Android does nothing with a held Home on a TV (config_longPressOnHomeBehavior is 0), and sends a
 * remote's Apps key only to the factory launcher (MATCH_SYSTEM_ONLY) and its Recents key to the
 * system's own recents: here both open Aura's instead. Any other button can be given an app.
 *
 * Seeing keys first has a price on some TVs: Android waits for this service's answer to every key
 * (up to half a second), and some firmware then delays releases or drops a held key's repeats. So
 * keys pass through here only while another app is in front, never on Aura's own screens, and not
 * at all while a game controller is connected.
 */
public class HomeKeys extends AccessibilityService {
    private static final long TWICE_MS = 200, HOLD_MS = 550, TEACH_MS = 30_000;
    /** A picture of the app in front is refreshed on a press once the last is this old. */
    private static final long REFRESH_MS = 30_000;
    /** Remote buttons given an app: "k<keycode>" (or "s<scancode>" for keys with no code) to a package. */
    static final String KEYS = "remote_keys";

    /** Running in this process right now. */
    static volatile boolean live;
    private static HomeKeys running;
    private static boolean healTried;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String front;
    private long firstUpAt;
    /**
     * waiting: a first press is held back; released: it has let go; taken: this press's down was
     * kept from Android; holding: this press became a hold, and its release only needs swallowing.
     */
    private boolean waiting, released, taken, holding;
    /** Whether keys come here at all (the XML asks for it; changed as apps come and go). */
    private boolean filtering = true, pad;
    /** How late D-pad releases arrive, smoothed: the firmware's own delay, for Settings to show. */
    private long lag, lagN;
    private InputManager.InputDeviceListener devices;
    /** Whether a package is an app you open (it has a launcher entry), cached: overlays and dialogs aren't. */
    private final HashMap<String, Boolean> openable = new HashMap<>();
    private final Runnable goHome = () -> {
        handler.removeCallbacks(this.held);
        waiting = false;
        performGlobalAction(GLOBAL_ACTION_HOME);
        refilterSoon();
    };
    /** Still down after a moment: Control Center, over the app. */
    private final Runnable held = () -> {
        if (!waiting || released || !taken) return;
        handler.removeCallbacks(goHome);
        waiting = false;
        holding = true;
        if (!open("aura_cc", front)) performGlobalAction(GLOBAL_ACTION_HOME);
    };
    private final Runnable refilter = this::refilter;
    private final Runnable settleShot = () -> {
        if (front != null && !front.equals(getPackageName())) shoot(front);
    };
    private final Runnable settleShotLate = () -> {
        if (front != null && !front.equals(getPackageName())) shoot(front);
    };

    /** Aura, with what to show; false when it couldn't be started. */
    private boolean open(String extra, String over) {
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .putExtra(extra, over)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = true;
        running = this;
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im != null) {
            devices = new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int id) {
                    padChanged();
                }

                @Override
                public void onInputDeviceRemoved(int id) {
                    padChanged();
                }

                @Override
                public void onInputDeviceChanged(int id) {
                    padChanged();
                }
            };
            im.registerInputDeviceListener(devices, handler);
        }
        pad = padConnected();
        refilter();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // voice search, the keyboard, volume and system dialogs come and go over an app without replacing it
        Boolean ok = openable.get(pkg);
        if (ok == null) {
            ok = pkg.equals(getPackageName()) || getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null
                    || getPackageManager().getLaunchIntentForPackage(pkg) != null;
            openable.put(pkg, ok);
        }
        if (ok) {
            front = pkg;
            refilter();
            // a picture once the app has drawn its first screen, and again once it has loaded (a
            // splash screen is mostly black and isn't kept): every app has one, pressed or not
            handler.removeCallbacks(settleShot);
            handler.removeCallbacks(settleShotLate);
            if (!pkg.equals(getPackageName()) && Previews.on(this)) {
                handler.postDelayed(settleShot, 4000);
                handler.postDelayed(settleShotLate, 12000);
            }
        }
    }

    // ---- when keys come here at all -------------------------------------------------------------

    /** On while another app is in front (or a button is being taught), off on Aura's own screens and with a controller in. */
    private void refilter() {
        // never between a press and its release: Android would see only half of it
        if (taken || waiting || holding) return;
        // on Aura's own screen too once a button has an app: some (Apps, Recents) never reach its window
        boolean want = teaching != null || !pad && front != null && (!front.equals(getPackageName()) || !keys(this).isEmpty());
        if (want == filtering) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        if (want) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        filtering = want;
    }

    private void refilterSoon() {
        handler.removeCallbacks(refilter);
        handler.post(refilter);
    }

    /** A game controller plugged in or paired: games get its buttons as its own, not re-sent through here. */
    private void padChanged() {
        boolean now = padConnected();
        if (now == pad) return;
        pad = now;
        refilter();
    }

    private static boolean padConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD && (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    // ---- keys -----------------------------------------------------------------------------------

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        if (up && e.getRepeatCount() == 0 && key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) measure(e);
        // teaching a button: the next one that isn't for moving around is the one
        // the release of a button just taught: it did its job on the press
        if (up && key == eatUp) {
            eatUp = -1;
            return true;
        }
        if (teaching != null) {
            if (SystemClock.uptimeMillis() > teachUntil) {
                teaching = null;
                refilterSoon();
            } else if (teachable(key)) {
                // taken on the press: a slow release mustn't miss the wait
                if (!up) {
                    final Taught t = teaching;
                    final int scan = e.getScanCode();
                    teaching = null;
                    eatUp = key;
                    handler.post(() -> {
                        t.got(key, scan);
                        refilter();
                    });
                }
                return true;
            }
        }
        // the switcher's picture of the app: taken as you leave it by Back as well as by Home, and
        // kept fresh while you browse it, so the card shows the screen you last saw, not its icon
        if (!up && e.getRepeatCount() == 0 && key != KeyEvent.KEYCODE_HOME && front != null
                && !front.equals(getPackageName()) && Previews.on(this)) {
            Long last = shotOf.get(front);
            if (key == KeyEvent.KEYCODE_BACK || last == null || SystemClock.uptimeMillis() - last > REFRESH_MS) shoot(front);
        }
        // a button given an app
        String app = mapped(this, key, e.getScanCode());
        if (app != null) {
            if (up && !e.isCanceled()) {
                launch(this, app);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_APP_SWITCH || key == KeyEvent.KEYCODE_ALL_APPS) {
            // both halves are kept from Android; Aura answers on the release
            if (up && !e.isCanceled()) {
                String over = front != null && !front.equals(getPackageName()) ? front : null;
                if (key == KeyEvent.KEYCODE_ALL_APPS) open("aura_apps", "");
                else if (over != null) open("aura_switcher", over);
                else open("aura_recents", "");
            }
            return true;
        }
        if (key != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            // a release always follows its own press, whatever is in front by now, so Android never
            // sees half a press
            if (!taken) return false;
            taken = false;
            handler.removeCallbacks(held);
            if (holding) {
                holding = false;
                refilterSoon();
                return true;
            }
            if (waiting) {
                // the wait for a second press starts when the first lets go
                released = true;
                firstUpAt = SystemClock.uptimeMillis();
                handler.removeCallbacks(goHome);
                handler.postDelayed(goHome, TWICE_MS);
            }
            return true;
        }
        if (e.getRepeatCount() > 0) return taken;
        // on Aura's own screen, Aura handles Home itself
        if (front == null || front.equals(getPackageName())) {
            taken = false;
            return false;
        }
        taken = true;
        if (waiting && released && SystemClock.uptimeMillis() - firstUpAt < TWICE_MS + 60) {
            // the second press: the switcher, over the app
            handler.removeCallbacks(goHome);
            handler.removeCallbacks(held);
            waiting = false;
            // whatever went wrong, Home still goes home
            if (!open("aura_switcher", front)) performGlobalAction(GLOBAL_ACTION_HOME);
            return true;
        }
        waiting = true;
        released = false;
        holding = false;
        // the app as you're leaving it, for its card in the switcher: taken while it's still on screen
        if (Previews.on(this)) shoot(front);
        handler.removeCallbacks(goHome);
        handler.removeCallbacks(held);
        handler.postDelayed(held, HOLD_MS);
        // should the release never come (and the hold not take), go home anyway
        handler.postDelayed(goHome, 1000);
        return true;
    }

    /** How long after the remote let go the release reached Aura; kept as a smoothed figure. */
    private void measure(KeyEvent e) {
        long late = SystemClock.uptimeMillis() - e.getEventTime();
        if (late < 0 || late > 3000) return;
        lag = lagN == 0 ? late : (lag * 7 + late) / 8;
        if (++lagN % 10 == 0) Prefs.device(this).edit().putInt("keys_lag", (int) lag).apply();
    }

    private long shotAt;
    /** When each app's picture was last taken. */
    private final HashMap<String, Long> shotOf = new HashMap<>();

    /** One screenshot, handed straight to {@link Previews}; Android allows one a second. */
    @android.annotation.TargetApi(30)
    private void shoot(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - shotAt < 1100) return;
        shotAt = now;
        shotOf.put(pkg, now);
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult shot) {
                    Previews.take(getApplicationContext(), pkg, shot);
                }

                @Override
                public void onFailure(int error) {
                    // no picture this time: the card keeps the last one, or what you were watching
                }
            });
        } catch (RuntimeException e) {
            // not allowed on this TV: cards stay as they are
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(held);
        handler.removeCallbacks(goHome);
        if (waiting) goHome.run();
    }

    /** Switched off or updated mid-press: a press held back still goes home. */
    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(held);
        handler.removeCallbacks(goHome);
        if (waiting) goHome.run();
        live = false;
        if (running == this) running = null;
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im != null && devices != null) im.unregisterInputDeviceListener(devices);
        return super.onUnbind(intent);
    }

    // ---- remote buttons given an app ------------------------------------------------------------

    interface Taught {
        void got(int keycode, int scancode);
    }

    private static volatile Taught teaching;
    /** The key whose release is still to come after it was taught on its press. */
    private int eatUp = -1;
    private static long teachUntil;
    private static Map<String, String> keys;

    /** Waits for the next button on the remote (30 seconds at most). False when the service isn't running. */
    static boolean teach(Taught t) {
        HomeKeys s = running;
        if (s == null) return false;
        teaching = t;
        teachUntil = SystemClock.uptimeMillis() + TEACH_MS;
        s.refilterSoon();
        return true;
    }

    static boolean teaching() {
        return teaching != null && SystemClock.uptimeMillis() < teachUntil;
    }

    /**
     * A button reached Aura's own window while teaching (some TVs' key services keep a few buttons
     * from the accessibility filter but still hand them to the app in front): it's the one.
     */
    static void taught(int code, int scan) {
        Taught t = teaching;
        if (t == null) return;
        teaching = null;
        t.got(code, scan);
        HomeKeys s = running;
        if (s != null) s.refilterSoon();
    }

    static void stopTeaching() {
        teaching = null;
        HomeKeys s = running;
        if (s != null) s.refilterSoon();
    }

    /**
     * Buttons that move you around, change the volume or turn the TV off stay as they are. The
     * Apps and Recents keys can be given an app: a button given one does that instead.
     */
    static boolean teachable(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return false;
            default:
                // numbers stay for channels, play and pause for the app playing
                return !(k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) && !media(k);
        }
    }

    private static boolean media(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_CLOSE:
            case KeyEvent.KEYCODE_MEDIA_EJECT:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                return true;
            default:
                return false;
        }
    }

    static String id(int code, int scan) {
        return code != KeyEvent.KEYCODE_UNKNOWN ? "k" + code : "s" + scan;
    }

    /** Every button given an app, in the order they were given. */
    static synchronized Map<String, String> keys(Context c) {
        if (keys == null) {
            keys = new LinkedHashMap<>();
            String s = Prefs.device(c).getString(KEYS, "");
            for (String pair : TextUtils.split(s, ";")) {
                int at = pair.indexOf('=');
                if (at > 0) keys.put(pair.substring(0, at), pair.substring(at + 1));
            }
        }
        return keys;
    }

    static synchronized void give(Context c, int code, int scan, String pkg) {
        keys(c).put(id(code, scan), pkg);
        save(c);
        HomeKeys s = running;
        if (s != null) s.refilterSoon();
    }

    static synchronized void forget(Context c, String id) {
        keys(c).remove(id);
        save(c);
        HomeKeys s = running;
        if (s != null) s.refilterSoon();
    }

    private static void save(Context c) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> k : keys.entrySet()) b.append(b.length() == 0 ? "" : ";").append(k.getKey()).append('=').append(k.getValue());
        Prefs.device(c).edit().putString(KEYS, b.toString()).apply();
    }

    /** The app a button opens, or null. */
    static String mapped(Context c, int code, int scan) {
        Map<String, String> k = keys(c);
        return k.isEmpty() ? null : k.get(id(code, scan));
    }

    static void launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** "Button 3", "Prog red", "Guide". */
    static String keyName(String id) {
        int n;
        try {
            n = Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return id;
        }
        if (id.charAt(0) == 's') return "Button " + n;
        String s = KeyEvent.keyCodeToString(n);
        if (s.startsWith("KEYCODE_")) s = s.substring(8);
        if (s.matches("\\d+")) return "Button " + s;
        s = s.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- switching it on and off from Settings (needs WRITE_SECURE_SETTINGS, granted at setup) --

    private static String me(Context c) {
        return new ComponentName(c, HomeKeys.class).flattenToString();
    }

    static boolean on(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        for (String s : TextUtils.split(list, ":")) if (s.equalsIgnoreCase(me(c))) return true;
        return false;
    }

    /** Adds or removes Aura from the TV's accessibility services, leaving any others as they were. */
    static boolean set(Context c, boolean on) {
        if (!Compat.granted(c, android.Manifest.permission.WRITE_SECURE_SETTINGS)) return false;
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        java.util.List<String> keep = new java.util.ArrayList<>();
        if (list != null) for (String s : TextUtils.split(list, ":")) if (!s.isEmpty() && !s.equalsIgnoreCase(me(c))) keep.add(s);
        if (on) keep.add(me(c));
        try {
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    keep.isEmpty() ? null : TextUtils.join(":", keep));
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, keep.isEmpty() ? 0 : 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Switched on, yet not running. When a service's process dies (Aura crashing, say), Android 11
     * puts it on a "crashed" list and won't connect it again until a restart, an update, or someone
     * switching it off and on (AccessibilityUserState.serviceDisconnectedLocked). So Aura does the
     * switching, once per start of its process.
     */
    static void heal(Context c) {
        final Context app = c.getApplicationContext();
        // a restart cut short (Aura killed between off and on) left it off: on again, first thing
        if (Prefs.device(app).getBoolean("keys_healing", false)) {
            set(app, true);
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
            return;
        }
        if (healTried || live || !on(c)) return;
        healTried = true;
        // written before switching off, so the switch back on survives Aura being killed in between
        Prefs.device(app).edit().putBoolean("keys_healing", true).commit();
        if (!set(app, false)) {
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            set(app, true);
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
        }, 400);
    }
}
