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
 *
 * Google TV and Fire TV keep Home for their own launcher even after Aura is chosen. There this
 * service is what makes Aura home: Home opens Aura instead (on Aura's own screens too, so those
 * keys pass through here as well), and should the TV's launcher come up anyway (after a restart,
 * or with a controller's Home) Aura is brought straight back over it.
 */
public class HomeKeys extends AccessibilityService {
    private static final long TWICE_MS = 150, LATE_TWICE_MS = 340, HOLD_MS = 550, TEACH_MS = 30_000;
    /** A picture of the app in front is refreshed on a press once the last is this old. */
    private static final long REFRESH_MS = 30_000;
    /** Remote buttons given an app: "k<keycode>" (or "s<scancode>" for keys with no code) to a package. */
    static final String KEYS = "remote_keys";
    /** KeyEvent.KEYCODE_PROFILE_SWITCH, named only from Android 12: Google TV remotes send it on older TVs too. */
    static final int KEYCODE_PROFILE_SWITCH = 288;
    /** Home goes to Aura even where Android hands it to another launcher (on unless switched off). */
    private static final String TAKEOVER = "home_takeover";

    /** Running in this process right now. */
    static volatile boolean live;
    /** The app last in front (Aura's own screens aside) and when, by the wall clock: newest in recent apps. */
    static volatile String lastApp;
    static volatile long lastAppAt;
    private static HomeKeys running;
    private static boolean healTried;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String front, firstFront;
    private long firstUpAt;
    /**
     * waiting: a first press is held back; released: it has let go; taken: this press's down was
     * kept from Android; holding: this press became a hold, and its release only needs swallowing;
     * own: a press on Aura's own screen, answered on its release.
     */
    private boolean waiting, released, taken, holding, own;
    /** Whether keys come here at all (the XML asks for it; changed as apps come and go). */
    private boolean filtering = true, pad;
    /** A volume button held down (its key code), and since when; 0 when none. */
    private int volumeHeld;
    private long volumeSince;
    /**
     * As Android's own repeat: a short pause after the press (< 400 ms InputDispatcher repeat
     * timeout on Realtek/Amlogic TVs), then a smooth step every 95 ms, never past 8 s (a lost release).
     */
    private static final long VOLUME_WAIT_MS = 320, VOLUME_STEP_MS = 95, VOLUME_MAX_MS = 8000;
    private final Runnable volumeRepeat = new Runnable() {
        @Override
        public void run() {
            if (volumeHeld == 0 || SystemClock.uptimeMillis() - volumeSince > VOLUME_MAX_MS) {
                volumeHeld = 0;
                return;
            }
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int dir = volumeHeld == KeyEvent.KEYCODE_VOLUME_UP
                    ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
            try {
                int before = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                am.adjustSuggestedStreamVolume(dir, android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.FLAG_SHOW_UI);
                if (!am.isVolumeFixed() && am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == before) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir,
                            android.media.AudioManager.FLAG_SHOW_UI);
                }
            } catch (RuntimeException ex) {
                volumeHeld = 0;
                return;
            }
            handler.postDelayed(this, VOLUME_STEP_MS);
        }
    };
    /** A mapped remote button held down: short press opens its app, holding reassigns it. */
    private int mappedKeyDown = -1, mappedScanDown = -1;
    private boolean mappedHoldFired;
    private final Runnable mappedHold = () -> {
        if (mappedKeyDown < 0 || mappedHoldFired) return;
        mappedHoldFired = true;
        if (!Profiles.kids(this)) openKeyPicker(mappedKeyDown, mappedScanDown);
    };
    /** How late D-pad releases arrive, smoothed: the firmware's own delay, for Settings to show. */
    private long lag, lagN;
    private InputManager.InputDeviceListener devices;
    /** Whether a package is an app you open (it has a launcher entry), cached: overlays and dialogs aren't. */
    private final HashMap<String, Boolean> openable = new HashMap<>();
    /** Whether a window's "package/class" is an activity, cached: an app's pop-ups and panels aren't. */
    private final HashMap<String, Boolean> activities = new HashMap<>();
    /** The TV's other home screens, as "package/class". */
    private java.util.Set<String> others = java.util.Collections.emptySet();
    /** Whether Home resolves to Aura, read at most every few seconds (it's a call into Android). */
    private boolean aurasHome;
    private long homeReadAt = -1;
    /** Bringing Aura back over the TV's own launcher, kept from ever becoming a tug of war. */
    private long bouncedAt, restUntil;
    /** Until when the TV's own home may come up once, unanswered: Aura opened the TV's settings. */
    private static long passUntil;

    /** Aura is opening the TV's settings: the TV's home showing on the way there isn't a Home press. */
    static void letTvHomePass() {
        passUntil = SystemClock.uptimeMillis() + 4_000;
    }
    private int bounces;
    private final Runnable goHome = () -> {
        handler.removeCallbacks(this.held);
        waiting = false;
        home();
        refilterSoon();
    };
    /** Still down after a moment: Control Center, over the app. */
    private final Runnable held = () -> {
        if (!waiting || released || !taken) return;
        handler.removeCallbacks(goHome);
        waiting = false;
        holding = true;
        if (!open("aura_cc", front)) home();
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

    /** Opens Aura straight to the app picker for a held remote button. */
    private void openKeyPicker(int code, int scan) {
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .putExtra("aura_key_code", code)
                    .putExtra("aura_key_scan", scan)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }

    /** Home, wherever it should lead: Aura when another launcher keeps Home, else Android's own. */
    private void home() {
        if (!takeover() || !openHome()) performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** Aura as Android opens it for Home, so the home screen answers exactly as to its own Home press. */
    private boolean openHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Home is Aura's to answer: Android gives it to another launcher, and that wasn't switched off. */
    private boolean takeover() {
        long now = SystemClock.uptimeMillis();
        if (homeReadAt < 0 || now - homeReadAt > 5000) {
            homeReadAt = now;
            aurasHome = HomeRole.held(this);
        }
        return !aurasHome && takeover(this);
    }

    static boolean takeover(Context c) {
        return Prefs.device(c).getBoolean(TAKEOVER, true);
    }

    static void setTakeover(Context c, boolean on) {
        Prefs.device(c).edit().putBoolean(TAKEOVER, on).apply();
        HomeKeys s = running;
        if (s != null) {
            s.homeReadAt = -1;
            s.refilterSoon();
        }
    }

    /** An app was installed, updated or removed: check packages and launchers afresh next time. */
    static void packagesChanged() {
        HomeKeys s = running;
        if (s == null) return;
        s.handler.post(() -> {
            s.openable.clear();
            s.activities.clear();
            s.others = HomeRole.others(s);
            s.homeReadAt = -1;
        });
    }

    /**
     * The TV's own launcher came up anyway: Aura over it at once. Three times in ten seconds is a
     * tug of war (something keeps opening it on purpose), so then it's left alone for a minute.
     */
    private void bounce() {
        if (!takeover()) return;
        long now = SystemClock.uptimeMillis();
        if (now < restUntil) return;
        // Aura has just opened the TV's settings: on Fire OS 8 they open through Amazon's home (the
        // settings app starts a Home intent, and the pages live inside Amazon's launcher), so that
        // one appearance is let be, or the TV's Settings would never stay open
        if (now < passUntil) {
            passUntil = 0;
            return;
        }
        if (now - bouncedAt > 10_000) bounces = 0;
        bouncedAt = now;
        if (++bounces > 3) {
            bounces = 0;
            restUntil = now + 60_000;
            return;
        }
        // Android 9 and older hold Aura back for up to five seconds after Home: covered meanwhile
        HomeCover.show(this);
        if (!openHome()) HomeCover.hide(false);
    }

    /** An activity's window, not a dialog, panel or pop-up drawn over one. */
    private boolean activity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean is = activities.get(key);
        if (is == null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                is = true;
            } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
                is = false;
            }
            activities.put(key, is);
        }
        return is;
    }

    /** Whether the service is meant to be on: switched on by Aura or by hand, and not switched off since. */
    static boolean wanted(Context c) {
        return Prefs.device(c).getBoolean("keys_wanted", false);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = true;
        running = this;
        Prefs.device(this).edit().putBoolean("keys_wanted", true).apply();
        others = HomeRole.others(this);
        // the TV has just started and opened its own launcher before this service was up
        if (SystemClock.elapsedRealtime() < 180_000) handler.postDelayed(this::afterBoot, 1500);
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
        ProfileButton.start(this);
    }

    /**
     * Just after a restart: the launcher Android opened is the TV's own, and Aura takes its place.
     * Only when that's certain (the usage history says so), or on a box with no inputs of its own
     * (a TV may have started on its tuner or an HDMI input, and stays there), and never when an
     * external HDMI-CEC device woke the TV via One Touch Play.
     */
    private void afterBoot() {
        if (front != null || !takeover() || Cec.externalActiveSource(this)) return;
        String last = lastForeground();
        if (last != null ? isOther(last) : !hasInputs()) openHome();
    }

    private String lastForeground() {
        if (!Habits.granted(this)) return null;
        try {
            android.app.usage.UsageStatsManager usm = Compat.svc(this, android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 180_000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isOther(String pkg) {
        for (String o : others) if (o.startsWith(pkg + "/")) return true;
        return false;
    }

    private boolean hasInputs() {
        android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        try {
            return tim != null && !tim.getTvInputList().isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * This service answers Home in every app: should it ever throw, Android ends it, and Home stops
     * working on the whole TV until it's switched back on. So whatever goes wrong in one event is
     * noted and that event let go.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            handleEvent(e);
        } catch (RuntimeException ex) {
            Trouble.caught("Home button service (window)", ex);
        }
    }

    private void handleEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        String cls = e.getClassName() != null ? e.getClassName().toString() : null;
        // Aura's home is up: whatever covered the wait for it goes (the cover's own window doesn't count)
        if (pkg.equals(getPackageName()) && HomeActivity.class.getName().equals(cls)) HomeCover.hide(true);
        // the TV's own launcher came up (Google TV's, say, which keeps Home): Aura goes back over it
        // Google TV's "who's watching" picker: this TV's firmware (Realtek's key service) opens it
        // straight from the remote's profile button, before any app sees the key, and Google TV
        // raises it by itself on waking. Aura's own picker takes its place
        if (cls != null && pkg.equals(Safety.GOOGLE_TV_HOME) && cls.endsWith(".ProfileChooserActivity") && takeover()) {
            front = null;
            refilter();
            open("aura_profiles", "");
            return;
        }
        if (cls != null && others.contains(pkg + "/" + cls)) {
            // no app is in front now: no picture is taken of the launcher under an app's name
            front = null;
            handler.removeCallbacks(settleShot);
            handler.removeCallbacks(settleShotLate);
            refilter();
            bounce();
            return;
        }
        // voice search, the keyboard, volume and system dialogs come and go over an app without replacing it
        Boolean ok = openable.get(pkg);
        if (ok == null) {
            ok = pkg.equals(getPackageName()) || getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null
                    || getPackageManager().getLaunchIntentForPackage(pkg) != null;
            openable.put(pkg, ok);
        }
        // nor do an app's own pop-ups: another app's dialog over the one you're in doesn't make it the app in front
        if (ok && cls != null && !pkg.equals(getPackageName()) && !activity(pkg, cls)) ok = false;
        if (ok) {
            if (!pkg.equals(front) && !pkg.equals(getPackageName()) && !Habits.isSystemNoise(pkg)) {
                // recent apps learn it here, however it was opened (a remote's own button, another app)
                lastApp = pkg;
                lastAppAt = System.currentTimeMillis();
                Habits.opened(this, pkg);
            }
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

    /**
     * On while another app is in front (or a button is being taught), off on Aura's own screens and
     * with a controller in. On Aura's own screens too where another launcher keeps Home, or once a
     * button has an app: some buttons (Apps, Recents) never reach Aura's window.
     */
    private void refilter() {
        // never between a press and its release: Android would see only half of it
        if (taken || waiting || holding) return;
        if (firstFront != null && released && SystemClock.uptimeMillis() - firstUpAt < LATE_TWICE_MS) {
            refilterLater();
            return;
        }
        firstFront = null;
        boolean want = teaching != null || !pad && (front == null ? takeover()
                : !front.equals(getPackageName()) || !keys(this).isEmpty() || takeover());
        if (want == filtering) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        if (want) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        filtering = want;
    }

    private void refilterLater() {
        handler.removeCallbacks(refilter);
        handler.postDelayed(refilter, 220);
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
        try {
            return handleKey(e);
        } catch (RuntimeException ex) {
            // the key goes on to the app as if this service weren't here
            Trouble.caught("Home button service (key)", ex);
            return false;
        }
    }

    private boolean handleKey(KeyEvent e) {
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
        // held volume: Android re-sends every key that passes through here, and a re-sent key gets no
        // repeats of its own (or sends a single repeatCount=1 before stopping), so holding Volume moved
        // it one step. Handled before screenshots so Volume never triggers takeScreenshot().
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (up || e.isCanceled()) {
                volumeHeld = 0;
                handler.removeCallbacks(volumeRepeat);
                return false;
            }
            if (e.getRepeatCount() == 0) {
                handler.removeCallbacks(volumeRepeat);
                volumeHeld = key;
                volumeSince = SystemClock.uptimeMillis();
                handler.postDelayed(volumeRepeat, VOLUME_WAIT_MS);
                return false;
            }
            // held repeat: keep volumeRepeat running and swallow the hardware repeat so re-injection
            // never resets the hold or causes double-steps while volumeRepeat steps smoothly
            if (volumeHeld != key) {
                volumeHeld = key;
                volumeSince = SystemClock.uptimeMillis();
                handler.removeCallbacks(volumeRepeat);
                handler.post(volumeRepeat);
            }
            return true;
        }
        // the switcher's picture of the app: taken as you leave it by Back as well as by Home, and
        // kept fresh while you browse it, so the card shows the screen you last saw, not its icon
        if (!up && e.getRepeatCount() == 0 && key != KeyEvent.KEYCODE_HOME && key != KeyEvent.KEYCODE_VOLUME_MUTE
                && front != null && !front.equals(getPackageName()) && Previews.on(this)) {
            Long last = shotOf.get(front);
            if (key == KeyEvent.KEYCODE_BACK || last == null || SystemClock.uptimeMillis() - last > REFRESH_MS) shoot(front);
        }
        // a button given an app: short press opens it, holding reassigns it
        String app = mapped(this, key, e.getScanCode());
        if (app != null) {
            if (!up) {
                if (e.getRepeatCount() == 0) {
                    mappedKeyDown = key;
                    mappedScanDown = e.getScanCode();
                    mappedHoldFired = false;
                    handler.removeCallbacks(mappedHold);
                    handler.postDelayed(mappedHold, HOLD_MS);
                }
            } else {
                handler.removeCallbacks(mappedHold);
                boolean wasHeld = mappedHoldFired;
                mappedKeyDown = -1;
                mappedScanDown = -1;
                mappedHoldFired = false;
                if (!wasHeld && !e.isCanceled()) {
                    launch(this, app);
                }
            }
            return true;
        }
        // Google TV's profile and notification buttons open its own profile picker and side panel over
        // anything; here they open Aura's profiles and Control Center (over the app in front, if any)
        if (key == KEYCODE_PROFILE_SWITCH || key == KeyEvent.KEYCODE_NOTIFICATION || key == KeyEvent.KEYCODE_SETTINGS) {
            if (up && !e.isCanceled()) {
                String over = front != null && !front.equals(getPackageName()) ? front : "";
                if (key == KEYCODE_PROFILE_SWITCH) open("aura_profiles", "");
                else if (!Profiles.kids(this)) open("aura_cc", over);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_APP_SWITCH || key == KeyEvent.KEYCODE_ALL_APPS) {
            // both halves are kept from Android; Aura answers on the release
            if (up && !e.isCanceled() && !(key == KeyEvent.KEYCODE_ALL_APPS && Profiles.kids(this))) {
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
            if (own) {
                own = false;
                if (!e.isCanceled()) openHome();
                refilterSoon();
                return true;
            }
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
            } else {
                refilterSoon();
            }
            return true;
        }
        if (e.getRepeatCount() > 0) return taken;
        if (firstFront != null && released && SystemClock.uptimeMillis() - firstUpAt < LATE_TWICE_MS) {
            // the second press: the switcher, over the app that was in front at the first press
            handler.removeCallbacks(goHome);
            handler.removeCallbacks(held);
            waiting = false;
            String over = firstFront;
            firstFront = null;
            firstUpAt = 0;
            taken = true;
            if (!open("aura_switcher", over)) home();
            return true;
        }
        // on Aura's own screen, Aura handles Home itself: Android hands it the press, unless Android
        // would open another launcher, in which case the press goes to Aura from here
        if (front == null || front.equals(getPackageName())) {
            own = takeover();
            taken = own;
            return own;
        }
        taken = true;
        waiting = true;
        firstFront = front;
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
        volumeHeld = 0;
        mappedKeyDown = -1;
        handler.removeCallbacks(volumeRepeat);
        handler.removeCallbacks(mappedHold);
        handler.removeCallbacks(settleShot);
        handler.removeCallbacks(settleShotLate);
        handler.removeCallbacks(refilter);
        handler.removeCallbacks(held);
        handler.removeCallbacks(goHome);
        if (waiting) goHome.run();
    }

    /** Switched off or updated mid-press: a press held back still goes home. */
    @Override
    public boolean onUnbind(Intent intent) {
        HomeCover.hide(false);
        volumeHeld = 0;
        mappedKeyDown = -1;
        handler.removeCallbacks(volumeRepeat);
        handler.removeCallbacks(mappedHold);
        handler.removeCallbacks(settleShot);
        handler.removeCallbacks(settleShotLate);
        handler.removeCallbacks(refilter);
        handler.removeCallbacks(held);
        handler.removeCallbacks(goHome);
        if (waiting) goHome.run();
        live = false;
        if (running == this) running = null;
        ProfileButton.stop();
        // switched off in Android's settings (not a crash or a restart): it stays off after a restart too
        if (!on(this)) Prefs.device(this).edit().putBoolean("keys_wanted", false).apply();
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im != null && devices != null) im.unregisterInputDeviceListener(devices);
        return super.onUnbind(intent);
    }

    /** Standby, as the remote's power button gives it: the sleep timer's way where Aura has no device admin. */
    static boolean sleep() {
        HomeKeys s = running;
        return s != null && android.os.Build.VERSION.SDK_INT >= 28 && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
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

    /** Read afresh next time (a backup was just restored). */
    static synchronized void forgetKeys() {
        keys = null;
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
        String pkg = k.isEmpty() ? null : k.get(id(code, scan));
        if (pkg == null) return null;
        // in a kids' profile, a button given YouTube opens YouTube Kids when installed and allowed;
        // any other hidden app does nothing (and isn't passed on)
        if (!allowed(c, pkg)) {
            if (("com.google.android.youtube.tv".equals(pkg) || "com.amazon.firetv.youtube".equals(pkg))
                    && allowed(c, "com.google.android.youtube.tvkids")
                    && (c.getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.youtube.tvkids") != null
                    || c.getPackageManager().getLaunchIntentForPackage("com.google.android.youtube.tvkids") != null)) {
                return "com.google.android.youtube.tvkids";
            }
            return "";
        }
        return pkg;
    }

    /** Whether the profile in use may open {@code pkg}: a kids' profile can't open what it hides. */
    static boolean allowed(Context c, String pkg) {
        return !Profiles.kids(c) || !Prefs.hidden(c).contains(pkg);
    }

    static void launch(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
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
        if (id.charAt(0) == 's') return Words.get(R.string.button_n, String.valueOf(n));
        String s = KeyEvent.keyCodeToString(n);
        if (s.startsWith("KEYCODE_")) s = s.substring(8);
        if (s.matches("\\d+")) return Words.get(R.string.button_n, s);
        s = s.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- switching it on and off from Settings (needs WRITE_SECURE_SETTINGS, granted at setup) --

    private static String me(Context c) {
        return new ComponentName(c, HomeKeys.class).flattenToString();
    }

    private static boolean isMe(Context c, String s) {
        return new ComponentName(c, HomeKeys.class).equals(ComponentName.unflattenFromString(s));
    }

    static boolean on(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        for (String s : TextUtils.split(list, ":")) if (isMe(c, s)) return true;
        return false;
    }

    /** The shell command that switches this service on, leaving any others as they were: for setup over ADB. */
    static String enableCommand(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        java.util.List<String> keep = new java.util.ArrayList<>();
        if (list != null) for (String s : TextUtils.split(list, ":")) if (!s.isEmpty() && !isMe(c, s)) keep.add(s);
        keep.add(me(c));
        return "settings put secure enabled_accessibility_services '" + TextUtils.join(":", keep).replace("'", "")
                + "' && settings put secure accessibility_enabled 1";
    }

    /** Adds or removes Aura from the TV's accessibility services, leaving any others as they were. */
    static boolean set(Context c, boolean on) {
        if (!Compat.granted(c, android.Manifest.permission.WRITE_SECURE_SETTINGS)) return false;
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        java.util.List<String> keep = new java.util.ArrayList<>();
        if (list != null) for (String s : TextUtils.split(list, ":")) if (!s.isEmpty() && !isMe(c, s)) keep.add(s);
        if (on) keep.add(me(c));
        try {
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    keep.isEmpty() ? null : TextUtils.join(":", keep));
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, keep.isEmpty() ? 0 : 1);
            // heal() switches it off and on: only a real switch-off counts as not wanted
            if (on || !Prefs.device(c).getBoolean("keys_healing", false)) Prefs.device(c).edit().putBoolean("keys_wanted", on).apply();
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
        heal(c, null);
    }

    static void heal(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        // a restart cut short (Aura killed between off and on) left it off: on again, first thing
        if (Prefs.device(app).getBoolean("keys_healing", false)) {
            set(app, true);
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
            if (done != null) done.run();
            return;
        }
        if (healTried || live || !on(c)) {
            if (done != null) done.run();
            return;
        }
        healTried = true;
        // written before switching off, so the switch back on survives Aura being killed in between
        Prefs.device(app).edit().putBoolean("keys_healing", true).commit();
        if (!set(app, false)) {
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
            if (done != null) done.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            set(app, true);
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
            if (done != null) done.run();
        }, 400);
    }
}
