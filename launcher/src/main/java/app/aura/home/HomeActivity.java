package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aura home: a floating glass sidebar, a status pill, three glass widgets (the sky outside, what
 * you'll probably watch next, your day) over every app in a tvOS-style grid. A coloured light
 * follows focus. Leave it alone and the photograph sharpens; leave it longer and it becomes a
 * title card.
 */
public class HomeActivity extends Activity implements Backdrop.Listener, ControlCenter.Host {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final long RACK_MS = 12_000, PHOTO_MS = 45_000;
    /** Metrics in dp, for the TV's 960 x 540 dp screen. */
    private static final int GAP = 19;
    /** The app grid: columns, then the tile and row measures that follow from them (see {@link #sizeGrid}). */
    private int COLS = 5, TILE_W = 148, TILE_H = 84, ROW_H = 122;
    private static final int PAGE_X = 104, WIDGET_Y = 104, WIDGET_H = 150, GRID_Y = 296, FOCUS_LINE = 210;
    /** Each widget's natural width; the ones you keep share the row in these proportions. */
    private static final int[] WIDGET_W = {260, 320, 200};
    private static final int WIDGET_ROW = 816, WIDGET_GAP = 18;
    static final String[] WIDGET_IDS = {"sky", "stack", "today"}, WIDGET_NAMES = {"Sky", "Smart Stack", "Today"};
    /** Browsing, the photograph sits half-soft behind the glass; resting, it comes back sharp. */
    private static final float HOME_DEFOCUS = 0.38f;
    private static final int SIDE = 0, WIDGETS = 1, GRID = 2, SHELF = 3, TOP = 4;
    private static final int SIDE_GLOW = 0xFF9FB2D6, DAY_GLOW = 0xFF7FB2FF;
    /** Minimum gap between moves while a direction is held: fast, but every hop stays smooth. */
    private static final long HOLD_STEP_MS = 110;
    private static List<AppRepo.App> cache;
    /** False until the first launch of this process has shown the boot screen. */
    private static boolean warm;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Compat.background("home", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    /** Recent apps read on their own thread: never queued behind the widgets' work or an app being stopped. */
    private final ExecutorService recentWorker = Compat.pool("recents", 1);
    /** Top Shelf's cards, found as focus settles on an app: never queued behind the rest of home's work. */
    private final ExecutorService shelfWorker = Compat.background("shelf", android.os.Process.THREAD_PRIORITY_DEFAULT);
    private final int[] loc = new int[2];

    private SharedPreferences prefs;
    private FrameLayout root, home, page;
    private Backdrop backdrop;
    private Bloom bloom;
    private Scrim scrim;
    private Splash splash;
    private GlassFrame sidebar, status, tip, hud;
    private Widget skyW, fyW, tdW;
    /** The tvOS style: apps alone on a frosted dock low on the screen; the sidebar hidden, or sliding in when wanted. */
    private boolean dock, sideHidden, sideIn = true;
    private GlassFrame dockGlass;
    private View tvosHint;
    private boolean topIn = true;
    /** Find and Control Center, round buttons top right beside the clock, in the tvOS style. */
    private Pad topCc, topFind, topSettings;
    /** The round buttons top right in the tvOS style, left to right: Find, Settings, Control Center. */
    private final List<Pad> tops = new ArrayList<>();
    private WeatherSheet weatherSheet;
    /** Who's watching: the remote's profile button, and Settings' Profile row. */
    private ProfilePicker who;
    private View beforeWho;
    private ImageView skyWx;
    private TextView skyTemp;
    private SkyArc skyArc;
    private Meter memory;
    private LinearLayout greeting;
    private Story story;
    private TextView hello, dateLine, gridLabel, stTime, tipText, hudText;
    private TextView skyKicker, skyTitle, skyRise, skySet, tdValue, tdSub, tdMem, tdData;
    private LinearLayout tdDataRow;
    private ImageView stWifi, stBt, stVpn, stWx, hudIcon;
    private TextView stTemp;
    private SmartStack stack;
    private ControlCenter cc;

    private final List<Pad> side = new ArrayList<>();
    private final List<String> sideLabels = new ArrayList<>();
    /** The widgets on show, left to right; {@code allWidgets} keeps them in their natural order. */
    private final List<Widget> widgets = new ArrayList<>();
    private final Widget[] allWidgets = new Widget[3];
    private final float[] widgetX = new float[3], widgetW = new float[3];
    private final List<Cell> cells = new ArrayList<>();
    private final int[] widgetGlow = {0xFFFFC98A, 0xFF8AA0C0, DAY_GLOW};
    private List<AppRepo.App> apps = new ArrayList<>();
    private MediaWatch.Playing playing;
    private TextView tdKicker;
    private int zone = GRID, contentZone = GRID, sideAt, widgetAt, gridAt, lastCol = -1, lastHour = -1;
    private float scrollTarget;
    private ValueAnimator scrollAnim, wiggle;
    private boolean ccOpen, racked, photo, resumed, introPending, editing, held, tipShown, dashboard = true, greetingShown = true;
    private boolean wallReady, appsReady, splashFinishing;
    /** The stack's cards on show since home was last opened, for {@link Taste}. */
    private final Set<String> shownThisVisit = new HashSet<>();
    /** A start's milestones, for the problem report: when Aura began, its apps and its photograph were ready. */
    private long createdAt, appsAt, wallAt, appsLoadMs;
    /** The last key pressed, and when home was first on show (the boot screen gone). */
    private long keyAt, homeAt;
    private int appsCount;
    private long splashAt, lastStep;
    private View beforeCc;
    private int swallowUp = -1, heldConfirm = KeyEvent.KEYCODE_DPAD_CENTER;
    /** Live Inputs: HDMI sources that are switched on right now, as {id, label}. */
    private final List<String[]> live = new ArrayList<>();
    private TvInputManager tim;
    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            refreshLive(true);
        }

        @Override
        public void onInputAdded(String inputId) {
            refreshLive(false);
        }

        @Override
        public void onInputRemoved(String inputId) {
            refreshLive(false);
        }
    };

    private final Runnable rack = () -> setRacked(true);
    private final Runnable goPhoto = () -> setPhoto(true);
    private final Runnable hideHud = this::hideHud;
    private final Runnable warmCc = new Runnable() {
        @Override
        public void run() {
            // under the boot screen it waits: its readings are system calls on the main thread,
            // and just after a restart they'd land in the middle of the sunrise
            if (splash != null) {
                handler.postDelayed(this, 1500);
                return;
            }
            // nor while you're using the remote, nor in the first seconds of home after the TV
            // started: each reading is a call into Android, which is slow to answer then, and
            // together they held the arrows for seconds as home arrived
            long now = SystemClock.uptimeMillis();
            if (now - keyAt < 2500 || Compat.justBooted() && now - homeAt < 8000) {
                handler.postDelayed(this, 1500);
                return;
            }
            if (!ccOpen && resumed && !held) cc.refresh();
        }
    };
    /**
     * Once Control Center's frost is opaque, everything beneath it stops drawing: the photograph
     * (two full-screen passes), the light, the shading and the home screen. The frost samples the
     * photograph's blurred copy directly, so it keeps drifting.
     */
    private final Runnable hideHome = () -> {
        if (ccOpen) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
    };

    private View[] behindCc() {
        return new View[]{home, backdrop, bloom, scrim};
    }
    private final Runnable glintTick = new Runnable() {
        @Override
        public void run() {
            View f = getCurrentFocus();
            // not on the switcher's cards: a sheen across one that size repaints most of the screen for a second
            if (!held && !photo && !ccOpen && !editing && !Backdrop.calm && !switcher.isOpen() && f instanceof Card) ((Card) f).glint();
            handler.postDelayed(this, 6500);
        }
    };
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Vpn.forget();
            reloadApps();
            // an app installed or updated: asked for its home-screen rows, as Google TV's home would
            String act = i.getAction();
            String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : null;
            if (pkg != null && (Intent.ACTION_PACKAGE_ADDED.equals(act) || Intent.ACTION_PACKAGE_REPLACED.equals(act))) {
                final Context app = getApplicationContext();
                worker.execute(() -> Continue.askToPublish(app, pkg));
            }
        }
    };

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        createdAt = SystemClock.uptimeMillis();
        super.onCreate(state);
        // crashing again and again: back quieter, or Home handed back to the TV's own home screen
        Safety.watch(this);
        Safety.tidy(this);
        final String safe = Safety.check(this);
        Ui.init(this);
        prefs = Prefs.of(this);
        applyLight();
        // the TV's own city, if known, before anything asks the sun where it is
        Weather.placeSun(this);
        tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        buildUi();
        setContentView(root);
        applyLight();
        if (safe != null) {
            handler.postDelayed(() -> hud(R.drawable.ic_info, safe.equals("safe")
                    ? "Aura kept stopping, so some extras are off for now" : "Aura kept stopping: Home opens the TV's own home screen again"), 6000);
        }
        getWindow().setBackgroundDrawable(null);
        occlude();
        introPending = state == null;
        backdrop.setIntro(introPending);
        if (!warm) {
            // first launch since boot: hold a loading screen until photos and apps are truly ready
            warm = true;
            splash = new Splash(this);
            // long after the TV started, this is Aura back after Android closed it for memory
            // (common on 1 to 1.5 GB TVs while an app plays): home as soon as it's ready
            splash.quick = !Compat.justBooted();
            root.addView(splash, match());
            splashAt = SystemClock.uptimeMillis();
            hideForIntro();
            // the longest it may hold, should the photograph or the apps be slow to come (a busy boot)
            handler.postDelayed(this::finishSplash, 5000);
        } else if (introPending) {
            playIntro();
        } else {
            backdrop.setDefocus(HOME_DEFOCUS, 0);
        }
        if (cache != null) {
            apps = cache;
            populate();
        } else {
            showSavedApps();
        }
        reloadApps();
        // a fresh Picture of the Day every day, imported in the background
        worker.execute(() -> Daily.schedule(getApplicationContext()));
        // Android's own record of how Aura's last runs ended (Android 11+), for Problem reports
        worker.execute(() -> Trouble.exits(getApplicationContext()));
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
        Previews.listener = shotTaken;
        Ambient.makeRoom = makeRoom;
    }

    /**
     * The screensaver is starting on a small TV: the home screen, out of sight, lets go of its
     * photographs and kept pictures, so the screensaver's have room. They come back on return.
     */
    private final Runnable makeRoom = () -> runOnUiThread(() -> {
        // on older Android the screensaver is only a window over home, which stays resumed beneath
        // it without focus: either way, home is out of sight
        if (isDestroyed() || resumed && hasWindowFocus()) return;
        backdrop.stop();
        backdrop.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE);
        roomMade = true;
        Previews.trim();
        dropExtras();
        Continue.trim();
    });

    /** The photographs were let go for the screensaver: they come back with home. */
    private boolean roomMade;

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        // back from a screensaver that only covered home (older Android): no onStart to restart it
        if (focus && roomMade && resumed) {
            roomMade = false;
            backdrop.start();
        }
    }

    /** A picture of an app just taken (on the capture thread): an open switcher shows it at once. */
    private final Previews.Listener shotTaken = (pkg, shot) -> runOnUiThread(() -> shotArrived(pkg, shot));

    private void shotArrived(String pkg, android.graphics.Bitmap shot) {
        if (isDestroyed() || !switcher.isOpen() || !pkg.equals(switcherOver)) return;
        Tiles.Painter face = Previews.painter(shot);
        faces.put(pkg, face);
        switcher.face(pkg, SystemClock.uptimeMillis());
        switcher.repaint(pkg, face);
    }

    @Override
    protected void onStart() {
        super.onStart();
        roomMade = false;
        watchVpn(true);
        backdrop.start();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, f);
        tick();
        if (tim != null) {
            try {
                tim.registerCallback(inputWatch, handler);
            } catch (RuntimeException e) {
                if (!Compat.noTvInputs(this)) Trouble.caught("TV inputs", e);
            }
            refreshLive(false);
        }
    }

    private static boolean fromSaverSettings(Intent i) {
        return i != null && i.getComponent() != null && i.getComponent().getClassName().endsWith(".SaverSettings");
    }

    private void openSaverSettings() {
        if (welcome != null) return;
        if (switcher.isOpen()) closeSwitcher();
        if (ccOpen) closeCc(false);
        if (!settings.isOpen()) openSettings();
        settings.show("wallpaper");
    }

    /** Android's answer to "Make Aura your home". */
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != HomeRole.REQUEST) return;
        if (HomeRole.held(this)) {
            hud(R.drawable.ic_check, "Home now opens Aura");
        } else if (result == RESULT_OK) {
            // Android said yes, yet Home still opens another launcher: the Home button service takes it from here
            String m = HomeRole.make(this);
            if (m != null) hud(R.drawable.ic_info, m);
        } else {
            homeHint("Press Home on the remote and choose Aura, then Always");
        }
        if (settings.isOpen()) settings.refresh();
    }

    void homeHint(String text) {
        hud(R.drawable.ic_info, text);
    }

    private final Runnable homeCheck = this::checkHome;

    /** Back from Android's Accessibility page: whether the Home button service came on there. */
    private void checkHome() {
        if (!HomeRole.waiting || !resumed) return;
        HomeRole.waiting = false;
        if (HomeKeys.live) hud(R.drawable.ic_check, "Home now opens Aura");
        else hud(R.drawable.ic_info, "Aura's Home button is still off · Settings, Home button");
        if (settings.isOpen()) settings.refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // home is up: the cover over a slow Home on older Fire TVs goes (see HomeCover)
        HomeCover.hide(true);
        // while home is on show, a freeze is caught in the act (see Problem reports)
        Trouble.watchMain(true);
        // the profile button's listener, once setup has allowed it (it costs nothing when already on)
        ProfileButton.start(this);
        MediaWatch.ensureWatching(this);
        MediaWatch.onChange = listen;
        updateListening();
        // a sleep timer that couldn't switch the TV off left the sound faded: it comes back now
        Power.recover(this);
        // an app's "Install?" from Android that couldn't come up while Aura was behind another app
        Updater.confirmPending(this);
        // back from switching the Home button service on in Android's settings
        if (HomeRole.waiting) handler.postDelayed(homeCheck, 800);
        wake();
        if (awayAt > 0) TopShelf.clearCache();
        // back from an app: "jump back in" now knows what you just watched
        refreshWidgets(true);
        refreshStatus();
        handler.removeCallbacks(glintTick);
        handler.postDelayed(glintTick, 4000);
        if (ccOpen) cc.refresh();
        else if (getCurrentFocus() == null && !cells.isEmpty()) focusGrid(gridAt);
        else if (awayAt > 0 && zone == SHELF && !cells.isEmpty()) focusGrid(gridAt);
        else if (awayAt > 0 && zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) {
            shelf.clearShown();
            shelfFor(gridAt);
        }
        // have Control Center's toggles and readings current before it's next opened, so opening
        // it does no system lookups on its first frame
        handler.removeCallbacks(warmCc);
        handler.postDelayed(warmCc, 1500);
        // Home button in apps switched on but not running (Aura crashed since): bring it back
        handler.removeCallbacks(healKeys);
        handler.postDelayed(healKeys, 4000);
        if (Prefs.on(this, Prefs.STACK_AUTO) && fyW.getVisibility() == View.VISIBLE) stack.start();
        askForListings();
        showUpdate();
        // a picture held in memory for a switcher that didn't open is let go
        handler.removeCallbacks(dropFreshSoon);
        handler.postDelayed(dropFreshSoon, 5000);
        // the switcher opens from this the moment Home is pressed again; back from an app, the list
        // is read straight away, since the app just left goes first and Home twice comes quickly
        handler.removeCallbacks(warmRecentsSoon);
        handler.postDelayed(warmRecentsSoon, awayAt > recentAt ? 60 : 700);
    }

    /**
     * Continue Watching needs the TV listings permission. Setup grants it over ADB, but it can be
     * lost (a reinstall, a reset), so Aura asks on screen when it's missing, at most once a week.
     */
    private void askForListings() {
        String perm = "android.permission.READ_TV_LISTINGS";
        // Continue Watching needs Android 8; the on-screen question, Android 6
        if (Build.VERSION.SDK_INT < 26 || splash != null || Compat.granted(this, perm)) return;
        long asked = prefs.getLong("listings_asked", 0), now = System.currentTimeMillis();
        if (now - asked < 7L * 86_400_000) return;
        prefs.edit().putLong("listings_asked", now).apply();
        handler.postDelayed(() -> {
            if (resumed && !ccOpen && !settings.isOpen()) requestPermissions(new String[]{perm}, 41);
        }, 1500);
    }

    // no @Override: the callback only exists from Android 6, and older TVs never call it
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Continue Watching appears as soon as it's allowed
        if (code == 41 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) refreshWidgets(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        Trouble.watchMain(false);
        if (!shownThisVisit.isEmpty()) {
            final Set<String> seenNow = new HashSet<>(shownThisVisit);
            final Context app = getApplicationContext();
            shownThisVisit.clear();
            worker.execute(() -> Taste.shown(app, seenNow));
        }
        if (MediaWatch.onChange == listen) MediaWatch.onChange = null;
        handler.removeCallbacks(listenTick);
        photoMusic(false);
        awayAt = SystemClock.uptimeMillis();
        openWhenWarm = false;
        stack.stop();
        stopPreview();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        handler.removeCallbacks(glintTick);
        if (editing) stopEdit();
        if (menu.isOpen()) closeMenu(null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        watchVpn(false);
        endLaunch();
        backdrop.stop();
        unregisterReceiver(clockReceiver);
        if (tim != null) {
            try {
                tim.unregisterCallback(inputWatch);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        // nothing posted earlier runs on a torn-down home: several hand work to the workers below,
        // which refuse it once shut (a crash), and the rest touch views that are gone
        handler.removeCallbacksAndMessages(null);
        settings.shutDrop();
        unregisterReceiver(packageReceiver);
        if (Previews.listener == shotTaken) Previews.listener = null;
        if (Ambient.makeRoom == makeRoom) Ambient.makeRoom = null;
        cc.release();
        backdrop.release();
        worker.shutdown();
        recentWorker.shutdown();
        shelfWorker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        backdrop.trim(level);
        // UI_HIDDEN only says an app is now in front, which is every app opened: dropping the kept
        // pictures and the ready-made recent-app cards there meant Home twice inside that app built
        // eight cards at the press (the stutter they're made ahead of time to avoid), and home
        // fetched its pictures again on return. They go when Android is actually short of memory:
        // while Aura is on screen (RUNNING_LOW, CRITICAL) or behind an app (BACKGROUND and up).
        // The last screen taken (0.9 MB) stays too: it's how an app that hasn't drawn yet, still
        // showing the previous app's screen, is told apart, and dropping it each time an app opened
        // let that previous screen be kept as the new app's picture.
        if (level == TRIM_MEMORY_UI_HIDDEN) return;
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Previews.trim();
            Tiles.trim();
            dropExtras();
            Continue.trim();
            if (!switcher.isOpen()) {
                faces.clear();
                switcher.forget();
            }
        }
    }

    /**
     * Tells Android that Aura covers the whole screen. Some TVs (this Realtek one) read the home
     * screen as translucent; Android then never hides the app you pressed Home from, and its last
     * frame stays drawn over Aura: a frozen picture that looks like a hang.
     */
    private void occlude() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                setTranslucent(false);
            } else {
                // the same call before Android 11, where it isn't public yet
                Activity.class.getMethod("convertFromTranslucent").invoke(this);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // preview a sky moment: am start -n app.aura.home/.HomeActivity --es aura_moment golden
        String preview = intent.getStringExtra("aura_moment");
        if (preview != null) {
            try {
                // a new intent arrives while the activity is paused; show it once it's resumed
                Moment m = Moment.valueOf(preview.toUpperCase(Locale.ROOT));
                handler.postDelayed(() -> moment(m), 500);
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
        // Aura picked in Android's screensaver settings (res/xml/dream.xml): its screensaver page
        if (fromSaverSettings(intent)) {
            lastHomeAt = 0;
            handler.postDelayed(this::openSaverSettings, 300);
            return;
        }
        // Home twice inside an app (HomeKeys): the switcher over it, the home screen never showing
        String over = intent.getStringExtra("aura_switcher");
        if (over != null) {
            lastHomeAt = 0;
            if (welcome == null && !switcher.isOpen()) openSwitcher(over);
            return;
        }
        // Home held inside an app (HomeKeys): Control Center over it, Back returns to the app
        String ccFor = intent.getStringExtra("aura_cc");
        if (ccFor != null) {
            lastHomeAt = 0;
            if (welcome != null || switcher.isOpen()) return;
            // the remote's notification button on the home screen (HomeKeys): Control Center, or shut again
            if (ccFor.isEmpty()) {
                if (ccOpen) closeCc(true);
                else if (settings.isOpen()) {
                    if (!settings.back()) closeSettings();
                } else openCc(false);
            } else if (!ccOpen) {
                openCcOver(ccFor);
            }
            return;
        }
        // the remote's profile button (HomeKeys): Aura's profiles, never Google TV's picker
        if (intent.hasExtra("aura_profiles")) {
            lastHomeAt = 0;
            if (welcome != null || switcher.isLaunching()) return;
            if (switcher.isOpen()) closeSwitcher();
            if (ccOpen) closeCc(false);
            if (settings.isOpen()) closeSettings();
            handler.postDelayed(this::openWho, 120);
            return;
        }
        // the remote's Recents key on the home screen, and its Apps key anywhere (HomeKeys)
        if (intent.hasExtra("aura_recents") || intent.hasExtra("aura_apps")) {
            lastHomeAt = 0;
            if (welcome != null || switcher.isLaunching()) return;
            if (intent.hasExtra("aura_recents")) {
                if (switcher.isOpen()) closeSwitcher();
                else openSwitcher();
                return;
            }
            if (switcher.isOpen()) closeSwitcher();
            if (ccOpen) closeCc(false);
            if (settings.isOpen()) closeSettings();
            if (editing) stopEdit();
            wake();
            focusGrid(0);
            return;
        }
        // see the first-run welcome again: am start -n app.aura.home/.HomeActivity --ez aura_welcome true
        if (intent.getBooleanExtra("aura_welcome", false)) {
            handler.postDelayed(this::openWelcome, 500);
            return;
        }
        if (welcome != null) return;
        // the launch screen is already handing over to an app: let it arrive
        if (switcher.isLaunching()) return;
        // Home twice in quick succession opens the app switcher, as the TV button does on Apple TV
        long now = SystemClock.uptimeMillis();
        boolean twice = now - lastHomeAt < 1300;
        lastHomeAt = twice ? 0 : now;
        // a switcher still waiting for its list stays shut after a third press
        if (!twice) openWhenWarm = false;
        if (switcher.isOpen()) {
            closeSwitcher();
            return;
        }
        if (twice) {
            openSwitcher();
            return;
        }
        if (!hasWindowFocus()) {
            // back from an app with Home: keep your place on the tile you left from
            if (ccOpen) closeCc(false);
            return;
        }
        if (editing) stopEdit();
        // Home means home: whatever is open closes (Settings, a folder, the hold-OK menu, Now
        // Playing, finding an app, a name or PIN being typed)
        closeOverlays();
        if (settings.isOpen()) closeSettings();
        if (ccOpen) closeCc(true);
        else focusGrid(0);
        wake();
    }

    @Override
    public void onBackPressed() {
        if (ccOpen) {
            if (!cc.back()) {
                if (ccOver != null) backToCcApp();
                else closeCc(true);
            }
        } else if (settings.isOpen()) {
            if (!settings.back()) closeSettings();
        } else if (zone == SIDE) {
            returnToContent();
        } else if (zone == GRID && gridAt >= COLS) {
            focusGrid(gridAt % COLS);
        } else if (dock && zone == GRID && gridAt < COLS) {
            setPhoto(true);
        } else if (zone == TOP || zone == SHELF || dock && zone == WIDGETS) {
            // the top buttons, Top Shelf, and the dock's widgets: Back returns to the apps below
            focusGrid(gridAt % COLS);
        }
    }

    // ---- layout ------------------------------------------------------------------------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        backdrop = new Backdrop(this);
        // the photograph rests at this softness: drawn from one pre-mixed copy, not two layers
        backdrop.setSteadyDefocus(HOME_DEFOCUS);
        backdrop.setListener(this);
        root.addView(backdrop, match());
        bloom = new Bloom(this);
        backdrop.addTick(bloom);
        root.addView(bloom, match());
        scrim = new Scrim(this, false);
        root.addView(scrim, match());

        home = flat(this);
        home.setClipChildren(false);
        root.addView(home, match());
        // everything right of the sidebar scrolls as one page; the grid is positioned by hand
        page = flat(this);
        page.setClipChildren(false);
        home.addView(page, new FrameLayout.LayoutParams(MATCH, Ui.dp(GRID_Y + 2 * ROW_H)));
        buildGreeting();
        buildSky();
        buildForYou();
        buildToday();
        buildListening();
        layoutWidgets(false);
        // Top Shelf sits exactly where the widgets are, and takes their place while it shows
        shelf = new TopShelf(this);
        page.addView(shelf, frame(Ui.dp(WIDGET_ROW), Ui.dp(WIDGET_H), Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(WIDGET_Y), 0, 0));
        gridLabel = Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.26f);
        page.addView(gridLabel, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(GRID_Y - 24), 0, 0));
        // the tvOS style's frosted shelf, edge to edge behind the first row (added before the tiles: beneath them)
        dockGlass = new GlassFrame(this, backdrop, 26, 0.42f);
        dockGlass.frost = 0.09f;
        dockGlass.edgeLight = true;
        dockGlass.frost = 0.09f;
        // rounded dock pill
        dockGlass.setVisibility(View.GONE);
        dockGlass.setVisibility(View.GONE);
        page.addView(dockGlass, frame(MATCH, Ui.dp(ROW_H), Gravity.TOP | Gravity.START, 0, 0, 0, 0));
        // the page dots sit at the foot of the screen, under the dock, centred on it
        pageDots = new PageDots(this);
        pageDots.setVisibility(View.GONE);
        home.addView(pageDots, frame(Ui.dp(WIDGET_ROW), Ui.dp(10), Gravity.BOTTOM | Gravity.START, Ui.dp(PAGE_X), 0, 0, Ui.dp(4)));
        buildSidebar();
        buildStatus();

        // photo mode: the photograph's story and the time, as in the screensaver
        story = new Story(this, backdrop);
        story.setAlpha(0f);
        root.addView(story, match());
        cc = new ControlCenter(this, this);
        root.addView(cc.panel, match());
        buildTopCc();
        applyLayout();
        settings = new SettingsPanel(this, new SettingsPanel.Host() {
            @Override
            public Backdrop backdrop() {
                return backdrop;
            }

            @Override
            public List<AppRepo.App> apps() {
                return apps;
            }

            @Override
            public void applied(String what) {
                applySetting(what);
            }

            @Override
            public void open(Intent intent, View from) {
                closeSettings();
                HomeActivity.this.open(intent, from);
            }

            @Override
            public boolean type(String text) {
                if (!resumed) return false;
                wake();
                if (settings.typeIntoField(text)) return true;
                if (ask.isOpen()) {
                    ask.type(text);
                    return true;
                }
                if (findPanel.isOpen()) {
                    findPanel.type(text);
                    return true;
                }
                if (settings.isOpen()) closeSettings();
                if (ccOpen) closeCc(false);
                if (switcher.isOpen()) closeSwitcher();
                if (nowPlaying.isOpen()) closeNowPlaying();
                if (weatherSheet.isOpen()) closeWeather();
                if (folderPanel.isOpen()) closeFolder();
                openFind(text);
                return true;
            }

            @Override
            public void hud(int icon, String text) {
                HomeActivity.this.hud(icon, text);
            }

            @Override
            public void whoIsWatching() {
                closeSettings();
                openWho();
            }
        });
        root.addView(settings.panel, match());
        folderPanel = new FolderPanel(this, new FolderPanel.Host() {
            @Override
            public Tiles.Painter art(AppRepo.App a) {
                return artFor(a);
            }

            @Override
            public boolean glass() {
                return Prefs.tileStyle(HomeActivity.this).equals("glass");
            }

            @Override
            public Backdrop backdrop() {
                return backdrop;
            }

            @Override
            public void launch(AppRepo.App a, View from) {
                closeFolder();
                HomeActivity.this.launch(a.launch, from, a.label);
            }

            @Override
            public void hold(AppRepo.App a, Card from) {
                swallow(heldConfirm);
                List<AppMenu.Item> items = new ArrayList<>();
                items.add(new AppMenu.Item(R.drawable.ic_apps, "Take out of folder", () -> {
                    menu.hide();
                    Folders.remove(HomeActivity.this, a.pkg);
                    String id = folderPanel.openId;
                    closeFolder();
                    populate();
                    Folders.Folder left = id == null ? null : Folders.all(HomeActivity.this).get(id);
                    if (left != null) openFolder(id);
                    hud(R.drawable.ic_check, a.label + " is back on the grid");
                }));
                // a kids' profile can move apps and make folders, nothing more (as on the grid)
                if (!Profiles.kids(HomeActivity.this)) items.add(new AppMenu.Item(R.drawable.ic_info, "App info", () -> {
                    menu.hide();
                    closeFolder();
                    open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)), from);
                }));
                items.add(new AppMenu.Item(R.drawable.ic_settings, "Rename folder", () -> {
                    menu.hide();
                    String id = folderPanel.openId;
                    closeFolder();
                    if (id != null) nameFolder(id, false);
                }));
                menu.show(from, a.label, items);
            }
        });
        root.addView(folderPanel, match());
        menu = new AppMenu(this, backdrop);
        root.addView(menu, match());
        switcher = new Switcher(this, backdrop, new Switcher.Host() {
            @Override
            public AppRepo.App find(String pkg) {
                return HomeActivity.this.find(pkg);
            }

            @Override
            public Card card(AppRepo.App a) {
                // the screen as you left it, or what you were watching in it
                Tiles.Painter face = faces.get(a.pkg);
                if (face != null) return new Card(HomeActivity.this, face, a.color, 22);
                // no picture yet: its launch screen, the icon large on near-black
                return new Card(HomeActivity.this, Tiles.launch(a), a.color, 22);
            }

            @Override
            public void launch(AppRepo.App a, View from) {
                switcherOver = null;
                switcher.launchInto(a, () -> openFromSwitcher(a.launch, a.label));
            }

            @Override
            public void close(AppRepo.App a) {
                if (a.pkg.equals(switcherOver)) switcherOver = null;
                // closed: not brought back to the front as the app last seen
                if (a.pkg.equals(HomeKeys.lastApp)) HomeKeys.lastApp = null;
                faces.remove(a.pkg);
                Previews.forget(getApplicationContext(), a.pkg);
                if (recentCache != null) recentCache.remove(a.pkg);
                final Context app = getApplicationContext();
                // on a thread of its own, as Close all is: a TV debugging link slow to answer held
                // home's one worker (widgets, the stack, artwork) for as long as it took
                new SafeThread(() -> {
                    Habits.close(app, a.pkg);
                    // set up on this TV: a real Force stop. Otherwise Android stops it only if it counts
                    // it as in the background, and one playing music carries on
                    if (Adb.forceStop(app, a.pkg)) return;
                    ActivityManager am = Compat.svc(app, ActivityManager.class);
                    if (am != null) am.killBackgroundProcesses(a.pkg);
                }, "close-app").start();
            }

            @Override
            public android.graphics.Bitmap shot(String pkg) {
                return Previews.fresh(pkg);
            }

            @Override
            public void closeAll(List<AppRepo.App> all) {
                String[] pkgs = new String[all.size()];
                final Context app = getApplicationContext();
                for (int i = 0; i < all.size(); i++) {
                    String pkg = all.get(i).pkg;
                    pkgs[i] = pkg;
                    if (pkg.equals(switcherOver)) switcherOver = null;
                    if (pkg.equals(HomeKeys.lastApp)) HomeKeys.lastApp = null;
                    faces.remove(pkg);
                    Previews.forget(app, pkg);
                    if (recentCache != null) recentCache.remove(pkg);
                }
                new SafeThread(() -> {
                    for (String pkg : pkgs) Habits.close(app, pkg);
                    if (Adb.forceStop(app, pkgs)) return;
                    ActivityManager am = Compat.svc(app, ActivityManager.class);
                    if (am != null) for (String pkg : pkgs) am.killBackgroundProcesses(pkg);
                }, "close-all").start();
            }

            @Override
            public void cleared(int count) {
                closeSwitcher();
                hud(R.drawable.ic_check, count == 1 ? "Closed" : "Closed " + count + " apps · memory freed");
            }
        });
        root.addView(switcher, match());
        nowPlaying = new NowPlaying(this, backdrop);
        root.addView(nowPlaying, match());
        weatherSheet = new WeatherSheet(this, backdrop);
        root.addView(weatherSheet, match());
        who = new ProfilePicker(this, backdrop, new ProfilePicker.Host() {
            @Override
            public void guarded(Runnable then) {
                // the PIN pad has the screen to itself
                if (Profiles.kids(HomeActivity.this) && Profiles.hasPin(HomeActivity.this)) closeWho(false);
                HomeActivity.this.guarded(then);
            }

            @Override
            public void switched(Profiles.Profile p) {
                // the picker fades as the new person's home screen is built beneath it
                closeWho(false);
                Profiles.use(HomeActivity.this, p.id);
                applySetting("profile");
                hud(R.drawable.ic_check, "Hello, " + p.name);
            }

            @Override
            public void manage() {
                closeWho(false);
                openSettings("profiles");
            }

            @Override
            public void close() {
                closeWho(true);
            }
        });
        root.addView(who, match());
        ask = new Ask(this, backdrop);
        root.addView(ask, match());
        findPanel = new Find(this, new Find.Host() {
            @Override
            public List<AppRepo.App> apps() {
                if (!Profiles.kids(HomeActivity.this)) return apps;
                // a kids' profile doesn't find what's hidden from it
                List<AppRepo.App> out = new ArrayList<>();
                Set<String> hidden = Prefs.hidden(HomeActivity.this);
                for (AppRepo.App a : apps) if (!hidden.contains(a.pkg)) out.add(a);
                return out;
            }

            @Override
            public List<AppRepo.App> suggested() {
                List<AppRepo.App> out = new ArrayList<>();
                for (Cell c : cells) {
                    AppRepo.App a = find(c.tag);
                    if (a != null) out.add(a);
                }
                return out;
            }

            @Override
            public Tiles.Painter art(AppRepo.App a) {
                return artFor(a);
            }

            @Override
            public boolean glass() {
                return Prefs.tileStyle(HomeActivity.this).equals("glass");
            }

            @Override
            public Backdrop backdrop() {
                return backdrop;
            }

            @Override
            public void open(AppRepo.App a, View from) {
                closeFind();
                launch(a.launch, from, a.label);
            }

            @Override
            public void everywhere(String text) {
                closeFind();
                Intent i = new Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (RuntimeException e) {
                    voiceSearch();
                }
            }
        });
        root.addView(findPanel, match());
        buildHud();
        buildMoment();

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> onFocusMoved(newFocus));
    }

    private void buildGreeting() {
        greeting = new LinearLayout(this) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        greeting.setOrientation(LinearLayout.VERTICAL);
        hello = Ui.text(this, 26, Ui.light, 0xFFF7F4EE);
        hello.setIncludeFontPadding(true);
        hello.setLetterSpacing(-0.02f);
        Ui.lift(hello, 10);
        greeting.addView(hello, lp(WRAP, WRAP, 0, 0));
        dateLine = Ui.text(this, 13, Ui.regular, 0xD9F5F2EB);
        Ui.lift(dateLine, 6);
        greeting.addView(dateLine, lp(WRAP, WRAP, 0, 3));
        page.addView(greeting, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(30), 0, 0));
    }

    /** The sky outside: the light now, the next sunrise or sunset, and the sun on its arc. */
    private void buildSky() {
        skyW = new Widget(this, backdrop);
        LinearLayout s = column(18, 17, 18, 14);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        skyKicker = Ui.caps(this, 7.5f, widgetGlow[0], 0.24f);
        head.addView(skyKicker, new LinearLayout.LayoutParams(0, WRAP, 1f));
        // the weather outside, once Open-Meteo has answered
        skyWx = ControlCenter.icon(this, R.drawable.ic_w_sun);
        skyWx.setColorFilter(0xFFFFFFFF);
        skyWx.setVisibility(View.GONE);
        head.addView(skyWx, new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)));
        skyTemp = Ui.text(this, 12, Ui.semibold, 0xF2FFFFFF);
        skyTemp.setVisibility(View.GONE);
        head.addView(skyTemp, lp(WRAP, WRAP, 5, 0));
        s.addView(head, lp(MATCH, WRAP, 0, 0));
        skyTitle = Ui.text(this, 20.5f, Ui.semibold, 0xFFF7F4EE);
        skyTitle.setIncludeFontPadding(true);
        skyTitle.setLetterSpacing(-0.02f);
        s.addView(skyTitle, lp(WRAP, WRAP, 0, 5));
        skyArc = new SkyArc(this);
        s.addView(skyArc, lp(MATCH, Ui.dp(44), 0, 8));
        LinearLayout feet = new LinearLayout(this);
        skyRise = Ui.text(this, 9.5f, Ui.medium, 0x99FFFFFF);
        skySet = Ui.text(this, 9.5f, Ui.medium, 0x99FFFFFF);
        skySet.setGravity(Gravity.END);
        feet.addView(skyRise, new LinearLayout.LayoutParams(0, WRAP, 1f));
        feet.addView(skySet, new LinearLayout.LayoutParams(0, WRAP, 1f));
        s.addView(feet, lp(MATCH, WRAP, 0, 4));
        skyW.addView(s, match());
        skyW.onClick = this::openWeather;
        placeWidget(skyW, 0);
    }

    /** What you'll probably watch: your usual for this hour, else the last thing you opened. */
    private void buildForYou() {
        fyW = new Widget(this, backdrop);
        stack = new SmartStack(this, 26);
        stack.listener = s -> {
            widgetGlow[1] = s.color;
            if (getCurrentFocus() == fyW) bloom.setColor(s.color, 700);
            // on show during this visit: counted once, when you leave (see Taste)
            if (resumed && s.key != null && fyW.isShown()) shownThisVisit.add(s.key);
        };
        fyW.addView(stack, match());
        fyW.onClick = () -> {
            SmartStack.Slide s = stack.current();
            if (s != null && s.action != null) {
                s.action.run();
                return;
            }
            if (s != null && s.intent != null) {
                launch(s.intent, fyW, s.label);
                final String key = s.key;
                final Context app = getApplicationContext();
                worker.execute(() -> Taste.opened(app, key));
                shownThisVisit.remove(key);
            }
        };
        placeWidget(fyW, 1);
    }

    /** Your day: time spent in apps, the one you spent it in, and how much memory is free. */
    private void buildToday() {
        tdW = new Widget(this, backdrop);
        LinearLayout d = column(18, 18, 18, 16);
        tdKicker = Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.24f);
        tdKicker.setText("TODAY");
        tdKicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        d.addView(tdKicker, lp(MATCH, WRAP, 0, 0));
        tdValue = Ui.text(this, 30, Ui.semibold, 0xFFF7F4EE);
        tdValue.setIncludeFontPadding(true);
        tdValue.setLetterSpacing(-0.03f);
        d.addView(tdValue, lp(WRAP, WRAP, 0, 4));
        tdSub = Ui.text(this, 10.5f, Ui.regular, 0xB3FFFFFF);
        tdSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        d.addView(tdSub, lp(MATCH, WRAP, 0, 1));
        d.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        // internet used today, over Wi-Fi and Ethernet: worth watching on a phone's hotspot
        tdDataRow = new LinearLayout(this);
        tdDataRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dl = Ui.caps(this, 6.5f, 0x8CFFFFFF, 0.24f);
        dl.setText("DATA");
        tdDataRow.addView(dl, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tdData = Ui.text(this, 9.5f, Ui.medium, 0xCCFFFFFF);
        tdDataRow.addView(tdData);
        tdDataRow.setVisibility(View.GONE);
        d.addView(tdDataRow, lp(MATCH, WRAP, 0, 0));
        LinearLayout m = new LinearLayout(this);
        m.setGravity(Gravity.CENTER_VERTICAL);
        TextView ml = Ui.caps(this, 6.5f, 0x8CFFFFFF, 0.24f);
        ml.setText("MEMORY");
        m.addView(ml, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tdMem = Ui.text(this, 9.5f, Ui.medium, 0xCCFFFFFF);
        m.addView(tdMem);
        d.addView(m, lp(MATCH, WRAP, 0, 6));
        memory = new Meter(this);
        d.addView(memory, lp(MATCH, Ui.dp(4), 0, 6));
        tdDay = d;
        tdW.addView(d, match());
        buildTodayMusic();
        tdW.onClick = () -> {
            if (playing == null) {
                openCc(false);
                return;
            }
            if (minimised(playing)) {
                // the small player: back to the big card, focus on it
                minimisedPkg = null;
                expandPkg = playing.pkg;
                updateListening();
                return;
            }
            if (playing.controller == null) {
                AppRepo.App owner = find(playing.pkg);
                if (owner != null && owner.launch != null) launch(owner.launch, tdW, owner.label);
                else openCc(false);
                return;
            }
            if (!playing.playing) {
                // paused: the button says Resume, and OK does just that
                try {
                    playing.controller.getTransportControls().play();
                    hud(R.drawable.ic_play, "Playing  ·  " + playing.title);
                    handler.postDelayed(() -> refreshWidgets(false), 250);
                    return;
                } catch (RuntimeException e) {
                    // the app let its player go: its own big view instead
                }
            }
            // what's playing, large: artwork, progress, previous / play-pause / next
            openNowPlaying();
        };
        placeWidget(tdW, 2);
    }

    private LinearLayout tdDay, tdMusic, tdChip;
    private ImageView tdArt, tdChipIcon;
    private TextView tdmKicker, tdmTitle, tdmBy, tdChipText;
    private Meter tdProgress;

    /**
     * The same card while music waits (paused, or the last thing you listened to): the cover with
     * the title beside it, who it's by (and in which app), how far in, and a frosted Resume button,
     * in the type of the other cards. Laid out for the card's height, so nothing is cut off at the bottom.
     */
    private void buildTodayMusic() {
        tdMusic = column(18, 18, 18, 16);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        tdArt = new ImageView(this);
        tdArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(Ui.dpf(9));
        clip.setColor(0x1FFFFFFF);
        tdArt.setBackground(clip);
        tdArt.setClipToOutline(true);
        tdArt.setElevation(Ui.dpf(6));
        top.addView(tdArt, new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        // the same small spaced capitals as the card's TODAY
        tdmKicker = Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.24f);
        tdmKicker.setSingleLine(true);
        tdmKicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(tdmKicker, lp(MATCH, WRAP, 0, 0));
        tdmTitle = Ui.text(this, 13.5f, Ui.semibold, 0xFFF7F4EE);
        tdmTitle.setSingleLine(false);
        tdmTitle.setMaxLines(2);
        tdmTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tdmTitle.setLetterSpacing(-0.015f);
        tdmTitle.setLineSpacing(0, 0.9f);
        words.addView(tdmTitle, lp(MATCH, WRAP, 0, 3));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        wl.leftMargin = Ui.dp(12);
        top.addView(words, wl);
        tdMusic.addView(top, lp(MATCH, WRAP, 0, 0));
        tdmBy = Ui.text(this, 10.5f, Ui.regular, 0xB3FFFFFF);
        tdmBy.setSingleLine(true);
        tdmBy.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tdMusic.addView(tdmBy, lp(MATCH, WRAP, 0, 8));
        tdMusic.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        tdProgress = new Meter(this);
        // the button and how far in, side by side on one line: a frosted pill, as tvOS draws its
        // buttons on glass, and a hairline beside it
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        tdChip = new LinearLayout(this);
        tdChip.setGravity(Gravity.CENTER_VERTICAL);
        tdChip.setPadding(Ui.dp(10), 0, Ui.dp(12), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(Ui.dpf(12));
        pill.setColor(0x29FFFFFF);
        pill.setStroke(Ui.dp(1), 0x1FFFFFFF);
        tdChip.setBackground(pill);
        tdChipIcon = new ImageView(this);
        tdChipIcon.setImageResource(R.drawable.ic_play);
        tdChipIcon.setColorFilter(0xF2FFFFFF);
        tdChip.addView(tdChipIcon, new LinearLayout.LayoutParams(Ui.dp(10), Ui.dp(10)));
        tdChipText = Ui.text(this, 10f, Ui.semibold, 0xF2FFFFFF);
        tdChipText.setSingleLine(true);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cl.leftMargin = Ui.dp(6);
        tdChip.addView(tdChipText, cl);
        foot.addView(tdChip, new LinearLayout.LayoutParams(WRAP, Ui.dp(24)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(3), 1f);
        pl.leftMargin = Ui.dp(12);
        foot.addView(tdProgress, pl);
        tdMusic.addView(foot, lp(MATCH, Ui.dp(24), 0, 0));
        tdMusic.setVisibility(View.GONE);
        tdW.addView(tdMusic, match());
    }

    /** The card dressed for {@code p}, or back to your day without it. */
    private void showTodayMusic(MediaWatch.Playing p, AppRepo.App owner) {
        if (p == null) {
            tdMusic.setVisibility(View.GONE);
            tdDay.setVisibility(View.VISIBLE);
            tdArt.setImageDrawable(null);
            return;
        }
        tdDay.setVisibility(View.GONE);
        tdMusic.setVisibility(View.VISIBLE);
        boolean live = p.controller != null;
        String app = owner != null ? owner.label : null;
        tdmKicker.setText(live ? (p.playing ? "NOW PLAYING" : "PAUSED") : "LAST PLAYED");
        // the text column beside the cover: two lines, the type a little smaller before anything is cut
        int col = (tdW.getWidth() > 0 ? tdW.getWidth() : Ui.dp(WIDGET_W[2])) - Ui.dp(18 + 18 + 50 + 12);
        Ui.fitLines(tdmTitle, Ui.songTitle(p.title), col, 2, 13.5f, 11.5f);
        boolean by = p.by != null && !p.by.trim().isEmpty() && !p.by.equalsIgnoreCase(p.title);
        tdmBy.setText(by && app != null ? p.by + "  ·  " + app : by ? p.by : app != null ? app : "");
        if (p.art != null && !p.art.isRecycled()) {
            tdArt.setImageBitmap(p.art);
            tdArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tdArt.setPadding(0, 0, 0, 0);
        } else if (owner != null && owner.icon != null) {
            tdArt.setImageDrawable(owner.icon);
            tdArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tdArt.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        } else {
            tdArt.setImageResource(R.drawable.ic_play);
            tdArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            tdArt.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        }
        tdProgress.setVisibility(p.progress > 0.005f ? View.VISIBLE : View.INVISIBLE);
        tdProgress.set(Math.max(0f, Math.min(1f, p.progress)));
        boolean pausable = live && p.playing, small = minimised(p);
        tdChipIcon.setImageResource(small ? R.drawable.ic_expand : pausable ? R.drawable.ic_pause : R.drawable.ic_play);
        tdChipText.setText(small ? "Expand" : pausable ? "Playing" : live ? "Resume" : "Continue");
    }

    // ---- listening -----------------------------------------------------------------------------

    private Widget listenW;
    private ImageView lArt;
    private TextView lKick, lTitle, lBy, lTime;
    private Meter lBar;
    /** The song the card shows, or null: shown while music plays, the widgets and app cards set aside. */
    private MediaWatch.Playing listening;
    /** The app whose music was minimised (OK held on the card): the widgets stay until it stops. */
    private String minimisedPkg;
    /** The row was laid out with music minimised (the Today card kept as its small player). */
    private boolean miniRow;
    /** Expand was just pressed for this app's music: its big card comes back, paused or not. */
    private String expandPkg;

    /** Music minimised and still there: the Today card is its small player, and OK brings the big card back. */
    private boolean minimised(MediaWatch.Playing p) {
        return p != null && p.controller != null && p.pkg != null && p.pkg.equals(minimisedPkg);
    }
    private android.graphics.Bitmap lShownArt;
    /** The song ({@code pkg|title}) the card's cover belongs to. */
    private String lShownKey;

    /**
     * While music plays (Spotify, YouTube Music, any app with a media session), one calm card takes
     * the widgets' place: the cover, the song and the artist, how far along it is. OK opens Now
     * Playing; holding OK minimises it and the widgets come back until the music stops.
     */
    private void buildListening() {
        listenW = new Widget(this, backdrop);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(22), Ui.dp(16));
        lArt = new ImageView(this);
        lArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lArt.setClipToOutline(true);
        lArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(14));
            }
        });
        r.addView(lArt, new LinearLayout.LayoutParams(Ui.dp(WIDGET_H - 32), Ui.dp(WIDGET_H - 32)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        lKick = Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.24f);
        lKick.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(lKick, lp(MATCH, WRAP, 0, 0));
        lTitle = Ui.text(this, 24, Ui.light, 0xFFFFFFFF);
        lTitle.setIncludeFontPadding(true);
        lTitle.setLetterSpacing(-0.02f);
        lTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(lTitle, lp(MATCH, WRAP, 0, 6));
        lBy = Ui.text(this, 13, Ui.medium, 0xCCFFFFFF);
        lBy.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(lBy, lp(MATCH, WRAP, 0, 2));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        lBar = new Meter(this);
        col.addView(lBar, lp(MATCH, Ui.dp(3), 0, 0));
        LinearLayout foot = new LinearLayout(this);
        lTime = Ui.text(this, 9.5f, Ui.medium, 0x8CFFFFFF);
        foot.addView(lTime, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView hint = Ui.caps(this, 6.5f, 0x66FFFFFF, 0.24f);
        hint.setText("HOLD OK TO MINIMISE");
        foot.addView(hint);
        col.addView(foot, lp(MATCH, WRAP, 0, 7));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, MATCH, 1f);
        cl.leftMargin = Ui.dp(20);
        r.addView(col, cl);
        listenW.addView(r, match());
        listenW.onClick = this::openNowPlaying;
        listenW.onLongClick = () -> {
            if (listening == null) return;
            minimisedPkg = listening.pkg;
            hud(R.drawable.ic_check, "Minimised \u00b7 OK on the small card brings it back");
            updateListening();
            refreshWidgets(false);
        };
        page.addView(listenW, frame(Ui.dp(WIDGET_ROW), Ui.dp(WIDGET_H), Gravity.TOP | Gravity.START,
                Ui.dp(PAGE_X), Ui.dp(WIDGET_Y), 0, 0));
        listenW.setVisibility(View.GONE);
    }

    private final Runnable listen = this::updateListening;

    /** The line under the song, once a second while the card is on show. */
    private final Runnable listenTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed || listening == null) return;
            listenProgress();
            handler.postDelayed(this, 1000);
        }
    };

    /** Music started, changed or stopped: the card comes or goes, and the widgets the other way. */
    private void updateListening() {
        if (isDestroyed() || listenW == null) return;
        MediaWatch.Playing p = MediaWatch.now(this);
        // the music stopped: the next song brings the card back, minimised or not
        if (p == null) minimisedPkg = null;
        // Expand on the small player brings the big card back even while the song is paused
        boolean asked = p != null && p.pkg != null && p.pkg.equals(expandPkg);
        expandPkg = null;
        boolean show = p != null && p.controller != null && p.pkg != null && !p.pkg.equals(minimisedPkg)
                && (p.playing || asked || listening != null && p.pkg.equals(listening.pkg));
        boolean was = listening != null;
        listening = show ? p : null;
        // in photo mode: a song that starts brings its bar (one that stops takes its own away)
        if (photo && show && p.playing) photoMusic(true);
        if (cc != null) cc.music(p != null && p.controller != null ? p : null);
        // the song changed (or paused, or its cover came) while the app previews show it: they were
        // only made again when focus moved, so the last song stayed on its card. Made again in place
        String song = p != null && p.controller != null ? p.pkg + "|" + p.title + "|" + p.playing + "|" + (p.art != null) : null;
        if (!java.util.Objects.equals(song, shelfSong)) {
            shelfSong = song;
            String on = shelf.shownFor();
            if (!show && on != null && zone == GRID) {
                shelfWanted = on;
                handler.removeCallbacks(shelfLoad);
                handler.post(shelfLoad);
            }
        }
        // the music minimised went (or a song was minimised with the row already set): the small player comes or goes
        if (was == show && miniRow != (minimisedPkg != null)) {
            layoutWidgets(true);
            refreshWidgets(false);
        }
        if (was != show) {
            hideShelf();
            long left = introUntil - SystemClock.uptimeMillis();
            if (splash != null || introPending || left > 0) {
                // the home screen is still arriving: the row takes its place in that, never before the apps
                swapRow(show);
                for (Widget w : widgets) {
                    if (splash != null || introPending) w.setAlpha(0f);
                    else enter(w, 0, Ui.dp(26), Math.max(0, left - 1200), 1000);
                }
            } else {
                // what's there now fades away, then the new row rises into its place
                final int gen = ++rowGen;
                List<Widget> leaving = new ArrayList<>(widgets);
                for (Widget w : leaving) w.animate().alpha(0f).translationY(-Ui.dp(8)).setDuration(220).setStartDelay(0).setInterpolator(Ui.SOFT).start();
                handler.postDelayed(() -> {
                    if (gen != rowGen) return;
                    for (Widget w : leaving) w.setTranslationY(0f);
                    swapRow(listening != null);
                    for (int i = 0; i < widgets.size(); i++) enter(widgets.get(i), 0, Ui.dp(18), i * 70L, 720);
                }, leaving.isEmpty() ? 0 : 230);
            }
            if (!show) {
                lShownArt = null;
                lShownKey = null;
            }
        }
        handler.removeCallbacks(listenTick);
        if (show) {
            fillListening(p);
            handler.post(listenTick);
        }
    }

    private int rowGen;

    /** The widget row as it now should be (the music card, or the widgets), focus kept on it if it was there. */
    private void swapRow(boolean music) {
        boolean onWidget = zone == WIDGETS && !ccOpen && !settings.isOpen();
        layoutWidgets(false);
        if (onWidget) {
            if (!widgets.isEmpty()) widgets.get(0).requestFocus();
            else focusGrid(gridAt);
        }
        if (!music && zone == GRID && !cells.isEmpty()) shelfFor(gridAt);
    }

    private void fillListening(MediaWatch.Playing p) {
        AppRepo.App owner = find(p.pkg);
        String label = owner != null ? owner.label : "";
        // an app kept off the home screen (hidden, or only reachable from search) still has a name
        if (label.isEmpty()) {
            try {
                label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p.pkg, 0)));
            } catch (Exception ignored) {
            }
        }
        lKick.setText(("NOW PLAYING" + (label.isEmpty() ? "" : "  \u00b7  " + label)).toUpperCase(Locale.getDefault()));
        lTitle.setText(p.title);
        lBy.setText(p.by != null ? p.by : "");
        lBy.setVisibility(p.by == null || p.by.isEmpty() ? View.GONE : View.VISIBLE);
        // a new song without a cover of its own: the last one's isn't left up while its own is fetched
        String song = p.pkg + "|" + p.title;
        if (!song.equals(lShownKey)) {
            lShownKey = song;
            if (p.art == null) lShownArt = null;
        }
        if (p.art != null) {
            showListenArt(p.art);
        } else {
            if (lShownArt == null) {
                try {
                    lArt.setImageDrawable(getPackageManager().getApplicationIcon(p.pkg));
                } catch (Exception e) {
                    lArt.setImageDrawable(null);
                }
            }
            final Context app = getApplicationContext();
            final String title = p.title;
            worker.execute(() -> {
                android.graphics.Bitmap b = MediaWatch.resolveArt(app, p);
                if (b != null) runOnUiThread(() -> {
                    if (listening != null && title.equals(listening.title)) showListenArt(b);
                });
            });
        }
        listenProgress();
    }

    private void showListenArt(android.graphics.Bitmap b) {
        if (b == lShownArt) return;
        boolean fade = lShownArt != null;
        lShownArt = b;
        lArt.setImageBitmap(b);
        if (fade) {
            lArt.setAlpha(0.2f);
            lArt.animate().alpha(1f).setDuration(500).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }
    }

    private void listenProgress() {
        android.media.session.MediaController m = listening != null ? listening.controller : null;
        android.media.MediaMetadata md = m != null ? m.getMetadata() : null;
        android.media.session.PlaybackState st = m != null ? m.getPlaybackState() : null;
        long dur = md != null ? md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) : 0;
        boolean on = st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        if (st == null || dur <= 0) {
            lBar.setVisibility(View.INVISIBLE);
            lTime.setText(st != null && !on ? "Paused" : "");
            return;
        }
        long pos = st.getPosition();
        if (on) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        pos = Math.max(0, Math.min(dur, pos));
        lBar.setVisibility(View.VISIBLE);
        lBar.set(pos / (float) dur);
        lTime.setText((on ? "" : "Paused  \u00b7  ") + NowPlaying.clock(pos) + "  /  " + NowPlaying.clock(dur));
    }

    private void placeWidget(Widget w, int i) {
        page.addView(w, frame(Ui.dp(WIDGET_W[i]), Ui.dp(WIDGET_H), Gravity.TOP | Gravity.START,
                Ui.dp(PAGE_X), Ui.dp(WIDGET_Y), 0, 0));
        allWidgets[i] = w;
        w.onLongClick = () -> openWidgetMenu(w);
    }

    /**
     * The widgets you keep, in your order, sharing the row in proportion to their natural widths:
     * hide one and the others widen to fill its place. With {@code animate}, they glide there.
     */
    private void layoutWidgets(boolean animate) {
        // music playing: the listening card alone, across the whole row
        if (listening != null && listenW != null) {
            for (Widget w : allWidgets) w.setVisibility(View.GONE);
            FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) listenW.getLayoutParams();
            l.leftMargin = Ui.dp(pageX());
            l.topMargin = Ui.dp(dock ? gridY() - 18 - 22 - WIDGET_H : WIDGET_Y);
            l.width = Ui.dp(WIDGET_ROW);
            listenW.setLayoutParams(l);
            listenW.setVisibility(View.VISIBLE);
            widgets.clear();
            widgets.add(listenW);
            widgetX[0] = 0;
            widgetW[0] = WIDGET_ROW;
            if (stack != null) stack.stop();
            return;
        }
        if (listenW != null) listenW.setVisibility(View.GONE);
        List<Integer> shown = new ArrayList<>();
        Set<String> hidden = Prefs.hiddenWidgets(this);
        List<Integer> order = Prefs.widgetOrder(this);
        if (!dock || Prefs.dockWidgets(this)) {
            for (int i : order) if (!hidden.contains(WIDGET_IDS[i])) shown.add(i);
        }
        float natural = 0;
        for (int i : shown) natural += WIDGET_W[i];
        // music minimised: the Today card stays as its small player, in either style (the tvOS
        // style shows no widgets otherwise, so there was nothing left to bring the music back from).
        // On its own it keeps its own size rather than stretching across the row
        boolean mini = minimisedPkg != null && !shown.contains(2);
        if (mini) {
            shown.add(2);
            natural += WIDGET_W[2];
        }
        miniRow = minimisedPkg != null;
        float k = mini && shown.size() == 1 ? 1f : (WIDGET_ROW - WIDGET_GAP * (shown.size() - 1)) / Math.max(1f, natural), x = 0;
        boolean[] was = new boolean[3];
        for (int i = 0; i < 3; i++) {
            was[i] = allWidgets[i].getVisibility() == View.VISIBLE;
            if (!shown.contains(i)) allWidgets[i].setVisibility(View.GONE);
        }
        widgets.clear();
        for (int n = 0; n < shown.size(); n++) {
            int i = shown.get(n);
            Widget w = allWidgets[i];
            FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) w.getLayoutParams();
            int before = l.leftMargin;
            widgetX[n] = x;
            widgetW[n] = WIDGET_W[i] * k;
            l.leftMargin = Ui.dp(pageX() + x);
            l.topMargin = Ui.dp(dock ? gridY() - 18 - 22 - WIDGET_H : WIDGET_Y);
            l.width = Ui.dp(widgetW[n]);
            w.setLayoutParams(l);
            w.setVisibility(View.VISIBLE);
            if (animate && was[i] && before != l.leftMargin) {
                w.setTranslationX(before - l.leftMargin);
                w.animate().translationX(0f).setDuration(520).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
            }
            widgets.add(w);
            x += widgetW[n] + WIDGET_GAP;
        }
        // a hidden Smart Stack stops turning its pages
        if (stack != null && fyW.getVisibility() != View.VISIBLE) stack.stop();
        else if (stack != null && resumed && Prefs.on(this, Prefs.STACK_AUTO)) stack.start();
    }

    /** Where the first row of apps sits: under the widgets, or low on the screen on the dock. */
    /** Where the grid starts from the left: beside the sidebar, or centred on the screen in the tvOS style. */
    private int pageX() {
        return dock ? (960 - WIDGET_ROW) / 2 : PAGE_X;
    }

    /** Where the first row of apps sits: under the widgets, or low on the screen on the dock with the second row peeking below. */
    private int gridY() {
        return dock ? 514 - ROW_H : GRID_Y;
    }


    /**
     * The home screen's style, from Settings: the Aura style (sidebar, widgets, then apps), or the
     * tvOS style, apps alone on a frosted dock with the photograph filling the screen above them,
     * the clock and Control Center's button top right, the sidebar hidden unless wanted.
     */
    private void applyLayout() {
        dock = Prefs.tvos(this);
        sideHidden = dock && !Prefs.dockSidebar(this);
        int aura = dock ? View.GONE : View.VISIBLE;
        greeting.setVisibility(aura);
        gridLabel.setVisibility(aura);
        layoutWidgets(false);
        dockGlass.setVisibility(dock ? View.VISIBLE : View.GONE);
        dockGlass.setAlpha(1f);
        tvosHint.setVisibility(dock ? View.VISIBLE : View.GONE);
        tvosHint.setAlpha(1f);
        for (Pad b : tops) {
            b.setVisibility(dock ? View.VISIBLE : View.GONE);
            b.setAlpha(1f);
        }
        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) status.getLayoutParams();
        sl.rightMargin = Ui.dp(dock ? 40 + tops.size() * (40 + 10) : 40);
        status.setLayoutParams(sl);
        sidebar.setVisibility(sideHidden ? View.GONE : View.VISIBLE);
        // the sidebar's name label goes with it
        if (sideHidden) hideTip();
        sideShown(!dock, false);
        topShown(!dock, false);
        if (zone == WIDGETS || zone == TOP && !dock || zone == SIDE && sideHidden) zone = contentZone = GRID;
        placeDock();
        cc.top(dock);
    }

    /** The dock's glass and the Top Shelf's place follow the first row (its height changes with the app size). */
    private void placeDock() {
        int px = pageX(), y = gridY();
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) dockGlass.getLayoutParams();
        l.leftMargin = Ui.dp(px - 18);
        l.topMargin = Ui.dp(y - 18);
        String nm = Prefs.nameMode(this);
        l.width = Ui.dp(WIDGET_ROW + 36);
        l.height = Ui.dp(TILE_H + ("focus".equals(nm) || "always".equals(nm) ? 58 : 36));
        dockGlass.setLayoutParams(l);
        FrameLayout.LayoutParams s = (FrameLayout.LayoutParams) shelf.getLayoutParams();
        s.leftMargin = Ui.dp(px);
        s.topMargin = Ui.dp(dock ? y - 18 - 22 - WIDGET_H : WIDGET_Y);
        shelf.setLayoutParams(s);
        if (pageDots != null) {
            FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) pageDots.getLayoutParams();
            dl.leftMargin = Ui.dp(px);
            pageDots.setLayoutParams(dl);
        }

    }

    /** The tvOS style's sidebar: out of sight to the left until it has focus. */
    private void sideShown(boolean in, boolean animate) {
        if (in == sideIn && animate) return;
        sideIn = in;
        sidebar.animate().cancel();
        float a = in ? 1f : 0f, x = in ? 0f : -Ui.dpf(30);
        if (!animate) {
            sidebar.setAlpha(a);
            sidebar.setTranslationX(x);
            return;
        }
        sidebar.animate().alpha(a).translationX(x).setStartDelay(0).setDuration(in ? 340 : 260).setInterpolator(Ui.EMPHASIZED).start();
    }

    /** Find and Control Center as round glass buttons, top right beside the clock, as on Apple TV. */
    private void buildTopCc() {
        // Settings has its own button: with the sidebar hidden, Control Center's Settings is Android's
        topCc = topButton(R.drawable.ic_tune, 40, () -> openCc(false));
        topSettings = topButton(R.drawable.ic_settings, 40 + 50, this::openSettings);
        topFind = topButton(R.drawable.ic_search, 40 + 2 * 50, () -> openFind(""));
        tops.add(topFind);
        tops.add(topSettings);
        tops.add(topCc);
        tvosHint = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path ch = new android.graphics.Path();
            @Override
            protected void onDraw(Canvas c) {
                float cx = getWidth() / 2f;
                p.setColor(0xE6FFFFFF);
                p.setShadowLayer(Ui.dpf(4), 0, Ui.dpf(1), 0x80000000);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1.8f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                ch.reset();
                ch.moveTo(cx - Ui.dpf(7.5f), Ui.dpf(9f));
                ch.lineTo(cx, Ui.dpf(4f));
                ch.lineTo(cx + Ui.dpf(7.5f), Ui.dpf(9f));
                c.drawPath(ch, p);
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Ui.medium);
                p.setTextSize(Ui.dpf(11f));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("Swipe up for fullscreen", cx, Ui.dpf(23f), p);
            }
        };
        tvosHint.setVisibility(View.GONE);
        home.addView(tvosHint, frame(Ui.dp(200), Ui.dp(32), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Ui.dp(16), 0, 0));
    }

    /** In the tvOS style, the top-right status pill and buttons stay out of sight until focus moves up to them. */
    private void topShown(boolean in, boolean animate) {
        if (in == topIn && animate) return;
        topIn = in;
        float a = in ? 1f : 0f;
        for (View v : new View[]{status, topFind, topSettings, topCc}) {
            v.animate().cancel();
            if (!animate) v.setAlpha(a);
            else v.animate().alpha(a).setStartDelay(0).setDuration(in ? 280 : 220).setInterpolator(Ui.SOFT).start();
        }
        if (dock && tvosHint != null) {
            tvosHint.animate().cancel();
            float ha = !in && scrollTarget == 0 ? 1f : 0f;
            if (!animate) tvosHint.setAlpha(ha);
            else tvosHint.animate().alpha(ha).setStartDelay(0).setDuration(240).setInterpolator(Ui.SOFT).start();
        }
    }

    private Pad topButton(int icon, int right, Runnable run) {
        Pad b = new Pad(this, backdrop, 20, 0.26f);
        b.frost = 0.06f;
        b.focusScale = 1.14f;
        ImageView iv = b.tint(ControlCenter.icon(this, icon), 1f);
        b.addView(iv, frame(Ui.dp(17), Ui.dp(17), Gravity.CENTER, 0, 0, 0, 0));
        b.onClick = run;
        b.setVisibility(View.GONE);
        home.addView(b, frame(Ui.dp(40), Ui.dp(40), Gravity.TOP | Gravity.END, 0, Ui.dp(26), Ui.dp(right), 0));
        return b;
    }

    /** Who's watching, over whatever was on show; Back returns to it untouched. */
    private void openWho() {
        if (who.isOpen() || welcome != null) return;
        if (weatherSheet.isOpen()) closeWeather();
        if (editing) stopEdit();
        stopPreview();
        hideTip();
        beforeWho = getCurrentFocus();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        who.open();
        handler.postDelayed(() -> {
            if (who.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 340);
    }

    /** {@code back}: nothing chosen, so focus returns to where it was. */
    private void closeWho(boolean back) {
        if (!who.isOpen()) return;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        who.close();
        stack.hold(false);
        View before = beforeWho;
        beforeWho = null;
        if (back && before != null && before.isShown()) before.requestFocus();
        else if (back) focusGrid(gridAt);
        wake();
    }

    /** The Sky widget, opened: the weather now, today's sun, the next hours and the week. */
    private void openWeather() {
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        boolean on = Prefs.on(this, Prefs.WEATHER);
        weatherSheet.open(on);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        handler.postDelayed(() -> {
            if (weatherSheet.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 300);
        // a reading over the hour old (or the week not fetched yet) comes now, and the card follows
        if (on) {
            final Context app = getApplicationContext();
            worker.execute(() -> {
                if (Weather.refresh(app)) runOnUiThread(this::showWeather);
            });
        }
    }

    private void closeWeather() {
        if (!weatherSheet.isOpen()) return;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        weatherSheet.close();
        stack.hold(false);
        if (skyW.getVisibility() == View.VISIBLE) skyW.requestFocus();
        else focusGrid(gridAt);
        wake();
    }

    private int glowOf(Widget w) {
        for (int i = 0; i < 3; i++) if (allWidgets[i] == w) return widgetGlow[i];
        return DAY_GLOW;
    }

    /** A floating glass rail, visionOS style; the page you're on keeps a soft disc behind its icon. */
    private void buildSidebar() {
        sidebar = new GlassFrame(this, backdrop, 29, 0.3f);
        sidebar.frost = 0.06f;
        sidebar.setClipContent(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        addSide(col, R.drawable.ic_apps, "Apps", () -> focusGrid(0), true);
        addSide(col, R.drawable.ic_search, "Find an app", () -> openFind(""), false);
        addSide(col, R.drawable.ic_hdmi, "Inputs", () -> openCc(true), false);
        addSide(col, R.drawable.ic_photo, "Photo mode", () -> setPhoto(true), false);
        LinearLayout.LayoutParams rl = lp(Ui.dp(22), Ui.hair(), 0, 10);
        rl.bottomMargin = Ui.dp(4);
        col.addView(Ui.rule(this, 0x33FFFFFF), rl);
        addSide(col, R.drawable.ic_tune, "Control Center", () -> openCc(false), false);
        addSide(col, R.drawable.ic_settings, "Settings", this::openSettings, false);
        // a small red dot on Settings while a new version of Aura waits, as on iOS
        updateDot = redDot(this);
        ((FrameLayout) side.get(side.size() - 1)).addView(updateDot, frame(Ui.dp(8), Ui.dp(8), Gravity.TOP | Gravity.END, 0, Ui.dp(6), Ui.dp(6), 0));
        sidebar.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        home.addView(sidebar, frame(Ui.dp(58), WRAP, Gravity.START | Gravity.CENTER_VERTICAL, Ui.dp(22), 0, 0, 0));

        // the name of the focused icon, in a small glass label beside the rail
        tip = new GlassFrame(this, backdrop, 15, 0.5f);
        tipText = Ui.text(this, 12, Ui.semibold, 0xFFFFFFFF);
        tip.addView(tipText, frame(WRAP, WRAP, Gravity.CENTER_VERTICAL | Gravity.START, Ui.dp(14), 0, Ui.dp(14), 0));
        tip.setAlpha(0f);
        tip.setVisibility(View.INVISIBLE);
        home.addView(tip, frame(WRAP, Ui.dp(30), Gravity.TOP | Gravity.START, Ui.dp(22 + 58 + 12), 0, 0, 0));
    }

    private View updateDot;

    /** The small red notification dot, with a hairline ring so it reads on any colour. */
    static View redDot(Context c) {
        View d = new View(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFFFF453A);
        g.setStroke(Math.max(1, Ui.hair()), 0x66000000);
        d.setBackground(g);
        d.setVisibility(View.GONE);
        return d;
    }

    /** The dot follows whether a new version is waiting and unseen; the message comes once per version. */
    private void showUpdate() {
        handler.removeCallbacks(tellUpdate);
        updateDot.setVisibility(Updater.unseen(this) ? View.VISIBLE : View.GONE);
        if (Updater.pending(this) != null) handler.postDelayed(tellUpdate, 2500);
    }

    /**
     * The one-time message, shown only when it can really be seen: after the boot screen, on the
     * home screen itself. Until then it waits, and only a message actually shown counts as told.
     */
    private final Runnable tellUpdate = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (splash != null || ccOpen || settings.isOpen() || photo) {
                handler.postDelayed(this, 2000);
                return;
            }
            String v = Updater.pending(HomeActivity.this);
            if (v != null && Updater.tellOnce(HomeActivity.this)) {
                showHud(R.drawable.ic_bolt, "Aura " + v + " is out  ·  Settings → Updates", 5000);
            }
        }
    };

    private void addSide(LinearLayout col, int iconRes, String label, Runnable action, boolean current) {
        Pad p = new Pad(this, null, 21, 0f);
        p.plainColor = current ? 0x29FFFFFF : 0x00FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.12f;
        p.addView(p.tint(ControlCenter.icon(this, iconRes), 1f), frame(Ui.dp(19), Ui.dp(19), Gravity.CENTER, 0, 0, 0, 0));
        p.onClick = action;
        col.addView(p, lp(Ui.dp(42), Ui.dp(42), 0, side.isEmpty() ? 0 : 6));
        side.add(p);
        sideLabels.add(label);
    }

    private void buildStatus() {
        status = new GlassFrame(this, backdrop, 20, 0.26f);
        status.frost = 0.06f;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), 0, Ui.dp(18), 0);
        stWifi = ControlCenter.icon(this, R.drawable.ic_wifi);
        stWifi.setColorFilter(0xFFFFFFFF);
        r.addView(stWifi, new LinearLayout.LayoutParams(Ui.dp(15), Ui.dp(15)));
        stBt = ControlCenter.icon(this, R.drawable.ic_bluetooth);
        stBt.setColorFilter(0xFFFFFFFF);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(15), Ui.dp(15));
        bl.leftMargin = Ui.dp(10);
        r.addView(stBt, bl);
        // a VPN carrying the TV's traffic: a small shield beside them, only while it's on
        stVpn = ControlCenter.icon(this, R.drawable.ic_vpn);
        stVpn.setColorFilter(0xFFFFFFFF);
        stVpn.setVisibility(View.GONE);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(Ui.dp(15), Ui.dp(15));
        vl.leftMargin = Ui.dp(10);
        r.addView(stVpn, vl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.hair(), Ui.dp(14));
        sl.leftMargin = Ui.dp(14);
        sl.rightMargin = Ui.dp(14);
        r.addView(Ui.rule(this, 0x40FFFFFF), sl);
        // the weather outside, once Open-Meteo has answered
        stWx = ControlCenter.icon(this, R.drawable.ic_w_sun);
        stWx.setColorFilter(0xFFFFFFFF);
        stWx.setVisibility(View.GONE);
        r.addView(stWx, new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
        stTemp = Ui.text(this, 14, Ui.semibold, 0xE6FFFFFF);
        stTemp.setVisibility(View.GONE);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.leftMargin = Ui.dp(7);
        tl.rightMargin = Ui.dp(14);
        r.addView(stTemp, tl);
        stTime = Ui.text(this, 14, Ui.bold, 0xFFFFFFFF);
        r.addView(stTime);
        status.addView(r, new FrameLayout.LayoutParams(WRAP, MATCH));
        home.addView(status, frame(WRAP, Ui.dp(40), Gravity.TOP | Gravity.END, 0, Ui.dp(26), Ui.dp(40), 0));
    }

    // ---- sky moments -------------------------------------------------------------------------

    private enum Moment { FIRST_LIGHT, SUNRISE, GOLDEN, SUNSET }

    private GlassFrame momentCard;
    private SkyArc momentArc;
    private TextView momentKicker, momentTitle, momentSub;
    private Sun.Phase lastPhase;
    private boolean lastUp;
    private final Runnable hideMoment = () -> momentCard.animate().alpha(0f).translationY(-Ui.dp(10)).setStartDelay(0)
            .setDuration(420).setInterpolator(Ui.SOFT).withEndAction(() -> momentCard.setVisibility(View.INVISIBLE)).start();

    /** A small glass card for the turns of the day: the sun on its arc, what just changed, what's next. */
    private void buildMoment() {
        momentCard = new GlassFrame(this, backdrop, 22, 0.55f);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(22), Ui.dp(10));
        momentArc = new SkyArc(this);
        r.addView(momentArc, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(34)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        momentKicker = Ui.caps(this, 6.5f, 0xFFFFC98A, 0.26f);
        momentKicker.setShadowLayer(0, 0, 0, 0);
        col.addView(momentKicker, lp(WRAP, WRAP, 0, 0));
        momentTitle = Ui.text(this, 16, Ui.heavy, 0xFFFFFFFF);
        momentTitle.setIncludeFontPadding(true);
        col.addView(momentTitle, lp(WRAP, WRAP, 0, 1));
        momentSub = Ui.text(this, 11, Ui.regular, 0xB3FFFFFF);
        col.addView(momentSub, lp(WRAP, WRAP, 0, 0));
        LinearLayout.LayoutParams cl = lp(WRAP, WRAP, 0, 0);
        cl.leftMargin = Ui.dp(14);
        r.addView(col, cl);
        momentCard.addView(r, new FrameLayout.LayoutParams(WRAP, WRAP));
        momentCard.setAlpha(0f);
        momentCard.setVisibility(View.INVISIBLE);
        root.addView(momentCard, frame(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Ui.dp(26), 0, 0));
    }

    /** Called on the minute: notices the sun crossing the horizon or the light changing phase. */
    private void watchSky() {
        long t = System.currentTimeMillis();
        Sun.Phase ph = Sun.phase(t);
        boolean up = Sun.isUp(t);
        if (lastPhase != null) {
            if (up != lastUp) moment(up ? Moment.SUNRISE : Moment.SUNSET);
            else if (ph != lastPhase && ph == Sun.Phase.GOLDEN) moment(Moment.GOLDEN);
            else if (ph != lastPhase && ph == Sun.Phase.DAWN) moment(Moment.FIRST_LIGHT);
        }
        lastPhase = ph;
        lastUp = up;
    }

    private void moment(Moment m) {
        if (!resumed || ccOpen || editing || !Prefs.on(this, Prefs.MOMENTS)) return;
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long rise = Sun.next(c.getTimeInMillis(), true), set = rise > 0 ? Sun.next(rise, false) : -1;
        java.text.DateFormat tf = DateFormat.getTimeFormat(this);
        String riseAt = rise > 0 ? tf.format(new Date(rise > now ? rise : Sun.next(now, true))).toUpperCase(Locale.getDefault()) : "";
        String setAt = set > 0 ? tf.format(new Date(set)).toUpperCase(Locale.getDefault()) : "";
        switch (m) {
            case FIRST_LIGHT:
                show(m, "FIRST LIGHT", "Dawn is breaking", "Sunrise at " + riseAt);
                break;
            case SUNRISE:
                show(m, "SUNRISE", "The sun is up", "Sunset at " + setAt + " · the photographs turn to day");
                break;
            case GOLDEN:
                show(m, "GOLDEN HOUR", "The light turns gold", "Sunset at " + setAt + " · the photographs follow it");
                break;
            default:
                show(m, "SUNSET", "The sun has set", "Sunrise at " + riseAt + " · night skies from here");
        }
        momentArc.set(rise, set, now);
        // and the photograph moves on to one that matches the new light
        if (!photo) backdrop.skip();
    }

    private void show(Moment m, String kicker, String title, String sub) {
        momentKicker.setTextColor(m == Moment.SUNSET ? 0xFFB9C6FF : m == Moment.FIRST_LIGHT ? 0xFFFFB8C8 : 0xFFFFC98A);
        momentKicker.setText(kicker);
        momentTitle.setText(title);
        momentSub.setText(sub);
        handler.removeCallbacks(hideMoment);
        hideHud();
        momentCard.setVisibility(View.VISIBLE);
        momentCard.setAlpha(0f);
        momentCard.setTranslationY(-Ui.dp(18));
        momentCard.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(700).setInterpolator(Ui.EMPHASIZED).start();
        handler.postDelayed(hideMoment, 8000);
    }

    private void buildHud() {
        hud = new GlassFrame(this, backdrop, 21, 0.6f);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(15), 0, Ui.dp(19), 0);
        hudIcon = ControlCenter.icon(this, R.drawable.ic_check);
        r.addView(hudIcon, new LinearLayout.LayoutParams(Ui.dp(17), Ui.dp(17)));
        hudText = Ui.text(this, 14, Ui.medium, 0xFFFFFFFF);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.leftMargin = Ui.dp(10);
        r.addView(hudText, tl);
        hud.addView(r, new FrameLayout.LayoutParams(WRAP, MATCH));
        hud.setAlpha(0f);
        hud.setVisibility(View.INVISIBLE);
        root.addView(hud, frame(WRAP, Ui.dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Ui.dp(28), 0, 0));
    }

    // ---- apps --------------------------------------------------------------------------------

    private void reloadApps() {
        reloadApps(false);
    }

    /** The real list has been read at least once since Aura started: a saved one is no longer wanted. */
    private volatile boolean appsRead;
    /** The saved list home is showing until the real one is read. */
    private List<AppRepo.App> savedApps;

    /** The saved list is exactly what's installed (same apps, names, screens and package versions). */
    private static boolean sameApps(List<AppRepo.App> real, List<AppRepo.App> saved) {
        if (saved == null || real.size() != saved.size()) return false;
        for (int i = 0; i < real.size(); i++) {
            AppRepo.App a = real.get(i), b = saved.get(i);
            if (!a.pkg.equals(b.pkg) || !a.label.equals(b.label) || !String.valueOf(a.cls).equals(b.cls)
                    || !String.valueOf(a.src).equals(b.src) || (a.banner != null) != (b.banner != null)
                    || (a.art != null) != (b.art != null)) return false;
        }
        return true;
    }

    /**
     * Just started with nothing in memory: the apps as they were last time, from Aura's own saved
     * copy, so home (and the boot screen's wait) doesn't hang on opening every app's package. The
     * real list replaces it the moment it's read, repainting only if anything differs.
     */
    private void showSavedApps() {
        final Context app = getApplicationContext();
        new SafeThread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            final List<AppRepo.App> saved = AppRepo.saved(app);
            if (saved == null) return;
            runOnUiThread(() -> {
                if (isDestroyed() || appsRead || !cells.isEmpty()) return;
                apps = saved;
                savedApps = saved;
                populate();
            });
        }, "apps-saved").start();
    }

    private void reloadApps(boolean force) {
        final Context app = getApplicationContext();
        Runnable read = () -> {
            long t0 = SystemClock.uptimeMillis();
            final List<AppRepo.App> read0 = AppRepo.load(app);
            appsLoadMs = SystemClock.uptimeMillis() - t0;
            appsCount = read0.size();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                boolean first = !appsRead;
                appsRead = true;
                List<AppRepo.App> list = read0;
                // the saved list on show is exactly this one: it stays, and the copy just read is let
                // go, so home never holds every banner twice (and no tile is painted again)
                List<AppRepo.App> was = savedApps;
                savedApps = null;
                if (was != null && was == apps && sameApps(read0, was)) {
                    for (int i = 0; i < was.size(); i++) was.get(i).fresh = read0.get(i).fresh;
                    list = was;
                }
                final List<AppRepo.App> keep = list;
                // a saved list that turned out stale (an app updated while Aura was off): every tile
                // painted again from the real one, which also lets the saved pictures go
                boolean changed = force || cells.isEmpty() || was != null && list != was || !samePackages(list, apps);
                apps = list;
                cache = list;
                // kept for the next start, once the TV has settled (it's only written when something changed)
                handler.postDelayed(() -> worker.execute(() -> AppRepo.save(app, keep)), first ? 20_000 : 2_000);
                if (!changed) return;
                populate();
                refreshOverlays();
            });
        };
        if (cells.isEmpty() && !appsRead) {
            // the first read, with the grid waiting on it: its own thread at normal priority, not
            // behind whatever home's background worker has queued
            new SafeThread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                read.run();
            }, "apps-first").start();
        } else {
            worker.execute(read);
        }
    }

    /** The open folder, find and Settings, made again from the new list of apps (one may have gone). */
    private void refreshOverlays() {
        if (folderPanel.isOpen()) {
            String id = folderPanel.openId;
            Folders.Folder f = id == null ? null : Folders.all(this).get(id);
            if (f == null || folderApps(f).isEmpty()) closeFolder();
            else folderPanel.open(f, folderApps(f));
        }
        if (findPanel.isOpen()) findPanel.refresh();
        if (settings.isOpen()) settings.refresh();
    }

    private static boolean samePackages(List<AppRepo.App> a, List<AppRepo.App> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).pkg.equals(b.get(i).pkg) || !a.get(i).label.equals(b.get(i).label)) return false;
        }
        return true;
    }

    private AppRepo.App find(String pkg) {
        for (AppRepo.App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /**
     * Live inputs first, then your apps in your order, then anything new, alphabetically. A tile
     * painted from exactly the same things as before (app, look, picture, name) is kept as it is
     * and only moved: hiding one app or renaming a folder no longer repaints every tile.
     */
    private void populate() {
        String keep = gridAt < cells.size() ? cells.get(gridAt).tag : null;
        // on the first fill the window may already have parked focus on the rail; the grid takes it
        boolean firstFill = cells.isEmpty(), hadFocus = zone == GRID && getCurrentFocus() instanceof Card;
        stopPreview();
        Map<String, Cell> old = new java.util.HashMap<>();
        for (Cell c : cells) old.put(c.tag, c);
        // a new app size: every tile is painted again at its new size, none kept
        if (sizeGrid(Prefs.gridCols(this))) for (Cell c : old.values()) c.sig = null;
        paged = Prefs.on(this, Prefs.GRID_PAGES);
        if (!paged && pageShift != 0f) {
            // pages switched off: the grid lies flat again, from the first column
            if (pageAnim != null) pageAnim.cancel();
            gridPage = 0;
            pageShift = 0f;
        }
        cells.clear();
        boolean calm = Perf.light(this);
        String style = Prefs.tileStyle(this);
        boolean glassTiles = style.equals("glass"), tvosTiles = style.equals("tvos") || dock;
        String nameMode = Prefs.nameMode(this);
        for (String[] in : live) {
            final String id = in[0], label = in[1], sig = "input|" + style + "|" + dock + "|" + label;
            Cell kept = reuse(old, "input:" + id, sig);
            if (kept != null) {
                kept.setNameMode(nameMode, false);
                kept.card.sway = !calm;
                cells.add(kept);
                continue;
            }
            final Cell cell = new Cell(this, Tiles.input(this, label), 0xFF46D7C8, label, "input:" + id, TILE_W, TILE_H);
            cell.sig = sig;
            cell.card.glass = backdrop;
            cell.card.tvos = tvosTiles;
            cell.setNameMode(nameMode, false);
            cell.card.sway = !calm;
            cell.card.onClick = () -> open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)), cell.card);
            cell.card.onLongClick = this::openMenu;
            // resting on a live source shows its picture, muted, inside the tile (when switched on in
            // Settings: some TVs draw it over the whole screen)
            cell.card.focusListener = chain(cell.card.focusListener, (card, gained) -> {
                handler.removeCallbacks(startPreview);
                if (gained && Prefs.on(this, Prefs.INPUT_PREVIEW)) {
                    previewId = id;
                    previewCell = cell;
                    handler.postDelayed(startPreview, 550);
                } else {
                    stopPreview();
                }
            });
            cells.add(cell);
        }
        // the saved order holds apps and folders; apps inside a folder leave the grid
        Map<String, Folders.Folder> folders = Folders.all(this);
        Set<String> foldered = new HashSet<>();
        for (Folders.Folder f : folders.values()) foldered.addAll(f.apps);
        List<String> hidden = new ArrayList<>(Prefs.hidden(this));
        List<Object> entries = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String tag : AppRepo.order(prefs)) {
            if (tag.startsWith("folder:")) {
                Folders.Folder f = folders.get(tag.substring(7));
                if (f != null && !folderApps(f).isEmpty() && placed.add(tag)) entries.add(f);
            } else {
                AppRepo.App a = find(tag);
                if (a != null && !foldered.contains(tag) && !hidden.contains(tag) && placed.add(tag)) entries.add(a);
            }
        }
        for (AppRepo.App a : apps) if (!foldered.contains(a.pkg) && !hidden.contains(a.pkg) && placed.add(a.pkg)) entries.add(a);
        for (Folders.Folder f : folders.values()) if (!folderApps(f).isEmpty() && placed.add(f.tag())) entries.add(f);
        List<AppRepo.App> sorted = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Folders.Folder) {
                final Folders.Folder f = (Folders.Folder) entry;
                List<AppRepo.App> inside = folderApps(f);
                StringBuilder sig = new StringBuilder("folder|").append(style).append('|').append(dock).append('|').append(f.name);
                for (AppRepo.App a : inside) sig.append('|').append(System.identityHashCode(a));
                Cell cell = reuse(old, f.tag(), sig.toString());
                if (cell == null) {
                    cell = new Cell(this, Tiles.folder(inside, !glassTiles), 0xFF9AA6BA, f.name, f.tag(), TILE_W, TILE_H);
                    cell.sig = sig.toString();
                    if (glassTiles) cell.card.glass = backdrop;
                    cell.card.tvos = tvosTiles;
                }
                cell.setNameMode(nameMode, false);
                cell.card.sway = !calm;
                cell.card.onClick = () -> openFolder(f.id);
                cell.card.onLongClick = this::openMenu;
                cells.add(cell);
                continue;
            }
            final AppRepo.App a = (AppRepo.App) entry;
            sorted.add(a);
            // glass panes tinted in the app's colour, its colour vivid, midnight, or the original
            // banners; your own picture for it in any of them
            String sig = "app|" + style + "|" + dock + "|" + System.identityHashCode(a) + "|" + a.label;
            Cell cell = reuse(old, a.pkg, sig);
            if (cell == null) {
                cell = new Cell(this, artFor(a), a.color, a.label, a.pkg, TILE_W, TILE_H);
                cell.sig = sig;
                if (glassTiles && a.art == null) cell.card.glass = backdrop;
                cell.card.tvos = tvosTiles;
            }
            cell.setNameMode(nameMode, false);
            cell.card.sway = !calm;
            // installed since Aura came and not opened yet: a small dot
            cell.card.badge = a.fresh;
            final Card card = cell.card;
            cell.card.onClick = () -> launch(a.launch, card, a.label);
            cell.card.onLongClick = this::openMenu;
            cells.add(cell);
        }
        // tiles nothing kept: gone from the page
        for (Cell c : old.values()) page.removeView(c);
        int at = 0;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            c.setNameMode(nameMode, dock && rowOf(i) == 0);
            // only the focused tile casts a shadow: two dozen resting shadows cost frames for nothing
            if (!c.card.isFocused()) c.card.setElevation(0f);
            c.setTranslationX(cellX(i));
            c.setTranslationY(cellY(i));
            if (c.getParent() == null) page.addView(c, new FrameLayout.LayoutParams(Ui.dp(TILE_W), WRAP));
            if (c.tag.equals(keep)) at = i;
        }
        gridLabel.setText("YOUR APPS  ·  " + (sorted.size() + foldered.size()));
        int rows = (cells.size() + COLS - 1) / COLS;
        page.getLayoutParams().height = Ui.dp(gridY() + (paged ? Math.min(2, rows) : rows) * ROW_H + 40);
        placeDock();
        int pages = paged ? (cells.size() + perPage() - 1) / perPage() : 1;
        pageDots.setCount(dock ? 0 : pages);
        pageDots.setPosition(gridPage);
        // apps removed from the last page: back to the last there is
        if (paged && gridPage > pages - 1) slideTo(Math.max(0, pages - 1));
        page.requestLayout();
        gridAt = at;
        if (!ccOpen && (firstFill || hadFocus || getCurrentFocus() == null)) focusGrid(at);
        else if (!cells.isEmpty()) reveal(contentZone == WIDGETS ? 0 : at);
        if (introPending && splash == null) cascade();
        if (!appsReady) {
            appsReady = true;
            appsAt = SystemClock.uptimeMillis();
            progressSplash();
        }
        refreshWidgets(true);
    }

    /** The tile for {@code tag} from before, taken out of {@code old}, if it was painted from {@code sig}. */
    private static Cell reuse(Map<String, Cell> old, String tag, String sig) {
        Cell c = old.get(tag);
        if (c == null || !sig.equals(c.sig)) return null;
        old.remove(tag);
        return c;
    }

    /** An app's art in the look chosen, or your own picture for it. */
    private Tiles.Painter artFor(AppRepo.App a) {
        return Tiles.forStyle(a, Prefs.tileStyle(this));
    }

    /**
     * Sizes the grid for {@code cols} apps a row across the widgets' width: five large tiles, or six
     * or seven smaller ones so more of your apps are on screen at once, as on tvOS. False if unchanged.
     */
    private boolean sizeGrid(int cols) {
        if (cols == COLS) return false;
        COLS = cols;
        TILE_W = Math.round((WIDGET_ROW - (cols - 1) * GAP) / (float) cols);
        TILE_H = Math.round(TILE_W * 84 / 148f);
        // room beneath each row for the focused tile's name
        ROW_H = TILE_H + 38;
        return true;
    }

    /**
     * Two rows of apps, the rest on pages to the side (Settings → Home screen), so the widgets never
     * leave: Right past the last column slides the next page in, dots beneath say where you are.
     * Off, the grid grows downwards and the page scrolls, as before.
     */
    private boolean paged;
    private int gridPage;
    /** How far the dock has slid to the left, in dp: a page is a screen's width. */
    private float pageShift, pageGlassAt;
    private ValueAnimator pageAnim;
    private PageDots pageDots;

    private int perPage() {
        return 2 * COLS;
    }

    private int rowOf(int i) {
        return paged ? (i % perPage()) / COLS : i / COLS;
    }

    private int pageOf(int i) {
        return paged ? i / perPage() : 0;
    }

    private float pageW() {
        return root.getWidth() > 0 ? root.getWidth() / Ui.density : 960f;
    }

    private float cellX(int i) {
        return Ui.dpf(pageX() + pageOf(i) * pageW() + (i % COLS) * (TILE_W + GAP) - pageShift);
    }

    private float cellY(int i) {
        return Ui.dpf(gridY() + rowOf(i) * ROW_H);
    }

    /** Brings tile {@code i} into view: its page slides in, or the page scrolls to its row. */
    private void reveal(int i) {
        if (!paged) {
            scrollTo(scrollFor(rowOf(i)));
            return;
        }
        // the tvOS style's dock sits low: its second row lifts the page just enough to be seen whole
        scrollTo(dock && rowOf(i) > 0 ? Math.max(0, gridY() + ROW_H + TILE_H + 44 - 540) : 0);
        slideTo(pageOf(i));
    }

    /** Slides the dock to page {@code p}: every page moves together, the glass following the photo. */
    private void slideTo(int p) {
        if (p == gridPage && (pageAnim == null || !pageAnim.isRunning()) && pageShift == p * pageW()) return;
        gridPage = p;
        if (pageAnim != null) pageAnim.cancel();
        pageAnim = ValueAnimator.ofFloat(pageShift, p * pageW());
        pageAnim.setDuration(560);
        pageAnim.setInterpolator(Ui.EMPHASIZED);
        pageAnim.addUpdateListener(a -> {
            pageShift = (float) a.getAnimatedValue();
            for (int i = 0; i < cells.size(); i++) cells.get(i).setTranslationX(cellX(i));
            if (pageDots != null) pageDots.setPosition(pageShift / pageW());
            // like the vertical scroll: panes on screen repaint every 6dp and where they stop
            boolean done = a.getAnimatedFraction() >= 1f;
            if (!done && Math.abs(pageShift - pageGlassAt) < 6) return;
            pageGlassAt = pageShift;
            float w = root.getWidth();
            for (Cell c : cells) {
                float x = c.getTranslationX();
                if (c.card.glass != null && x + c.getWidth() >= -Ui.dp(32) && x <= w + Ui.dp(32)) c.card.invalidate();
            }
        });
        pageAnim.start();
    }

    /** The dots beneath the dock, one a page, the current one a pill that glides along with the slide. */
    static final class PageDots extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private int count;
        private float pos;

        PageDots(Context c) {
            super(c);
        }

        void setCount(int n) {
            count = n;
            setVisibility(n > 1 ? VISIBLE : GONE);
            invalidate();
        }

        void setPosition(float page) {
            pos = page;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (count < 2) return;
            float dot = Ui.dpf(5), gap = Ui.dpf(7), pill = Ui.dpf(16);
            float total = (count - 1) * (dot + gap) + pill, x = (getWidth() - total) / 2f, cy = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                // how much of the pill sits on this dot right now, 0 to 1
                float k = Math.max(0f, 1f - Math.abs(pos - i));
                float w = dot + (pill - dot) * k;
                p.setColor(Ui.withAlpha(0xFFFFFFFF, Math.round(90 + 150 * k)));
                r.set(x, cy - dot / 2f, x + w, cy + dot / 2f);
                c.drawRoundRect(r, dot / 2f, dot / 2f, p);
                x += w + gap;
            }
        }
    }

    /**
     * From the switcher's launch screen: the app fades in over it, which holds still beneath, so the
     * wait for the app is never a black screen or a frozen switcher.
     */
    private void openFromSwitcher(Intent intent, String label) {
        try {
            Bundle opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.hold).toBundle();
            startActivity(intent, opts);
            String pkg = intent.getPackage() != null ? intent.getPackage()
                    : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
            Habits.opened(getApplicationContext(), pkg);
            // should the app never come up, the switcher puts itself away
            handler.removeCallbacks(launchGaveUp);
            handler.postDelayed(launchGaveUp, 12_000);
        } catch (RuntimeException e) {
            // not on this TV (a settings page Fire OS doesn't have), not allowed, or refused
            if (!(e instanceof ActivityNotFoundException)) Trouble.caught("opening " + label, e);
            hud(R.drawable.ic_info, "Couldn't open " + label);
            endLaunch();
        }
    }

    private final Runnable launchGaveUp = this::endLaunch;

    /** The launched app is in front (or never came): the switcher's launch screen is put away unseen. */
    private void endLaunch() {
        handler.removeCallbacks(launchGaveUp);
        Previews.dropFresh();
        if (!switcher.isLaunching()) return;
        switcher.reset();
        switcherOver = null;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        stack.hold(false);
        if (beforeSwitcher == null || !beforeSwitcher.isShown() || !beforeSwitcher.requestFocus()) focusGrid(gridAt);
    }

    private void launch(Intent intent, View from, String label) {
        // the TV's own settings (Wi-Fi, Bluetooth, display, the whole Settings app): on Fire OS 8 they
        // pass through Amazon's home on the way, which the Home button service mustn't answer
        String action = intent.getAction();
        if (action != null && action.startsWith("android.settings.")) {
            HomeKeys.letTvHomePass();
            intent = fireSettings(intent);
        }
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(intent, opts);
            String pkg = intent.getPackage() != null ? intent.getPackage()
                    : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
            Habits.opened(getApplicationContext(), pkg);
            seen(pkg);
            if (pkg != null && recentCache != null) {
                // first in the switcher at once, before the history catches up
                java.util.LinkedHashMap<String, Long> r = new java.util.LinkedHashMap<>();
                r.put(pkg, System.currentTimeMillis());
                for (Map.Entry<String, Long> e : recentCache.entrySet()) if (!e.getKey().equals(pkg)) r.put(e.getKey(), e.getValue());
                recentCache = r;
            }
        } catch (RuntimeException e) {
            // not on this TV (a settings page Fire OS doesn't have), not allowed, or refused
            if (!(e instanceof ActivityNotFoundException)) Trouble.caught("opening " + label, e);
            hud(R.drawable.ic_info, "Couldn't open " + label);
        }
    }

    /** Opened at last: a new app's dot goes. */
    private void seen(String pkg) {
        AppRepo.App a = pkg == null ? null : find(pkg);
        if (a == null || !a.fresh) return;
        a.fresh = false;
        NewApps.opened(getApplicationContext(), pkg);
        for (Cell c : cells) {
            if (c.tag.equals(pkg)) {
                c.card.badge = false;
                c.card.invalidate();
            }
        }
    }

    // ---- widgets -----------------------------------------------------------------------------

    /** Reads the sky, your day and (with {@code habits}) your viewing history off-thread, then fills the widgets. */
    private void refreshWidgets(boolean habits) {
        final Context app = getApplicationContext();
        final List<AppRepo.App> list = apps;
        worker.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            // exactly midnight, so every screen's 2-minute sun search lands on the same minute
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            final long rise = Sun.next(c.getTimeInMillis(), true), set = rise > 0 ? Sun.next(rise, false) : -1;
            final boolean up = Sun.isUp(now);
            // by day the countdown and the arc's right foot are the same sunset, to the minute
            final long next = up && set > now ? set : Sun.next(now, !up);
            final Sun.Phase phase = Sun.phase(now);
            final long today = Habits.todayMs(app);
            final String top = Habits.topToday(app);
            Map<String, Float> score = null;
            Object[] recent = null;
            List<String> ranked = null;
            boolean full = habits && !list.isEmpty();
            // every app opened in the last four weeks: "apps you use", as against apps you merely have
            final Set<String> used = new HashSet<>();
            if (full) {
                score = Habits.forNow(app, used);
                Set<String> installed = new HashSet<>();
                for (AppRepo.App a : list) if (watchable(a.pkg)) installed.add(a.pkg);
                recent = Habits.recent(app, installed);
                ranked = ranked(installed, score, top, recent);
            }
            final Map<String, Float> s = score;
            final Object[] r = recent;
            // what you're partway through outranks habits
            final List<Continue.Item> cw = full ? Continue.all(app, 4) : null;
            // then your own rows from apps that skip Watch Next (Netflix): never the same title twice
            Set<String> seen = new HashSet<>();
            for (int i = 0; cw != null && i < cw.size(); i++) seen.add(Continue.key(cw.get(i).title));
            // what video apps said they were playing (their media sessions): Continue watching where
            // the app never filled Watch Next, and on Fire TV, which has none at all
            if (cw != null) {
                for (Continue.Item it : Watched.recent(app, seen, Math.max(0, 4 - cw.size()))) {
                    cw.add(it);
                    seen.add(Continue.key(it.title));
                }
            }
            final List<Continue.Item> mine = full ? Continue.channels(app, ranked, seen, 3) : null;
            final Extras x = full ? extras(app, list.size()) : null;
            // what the stack has learned from you, read once for this round
            final Taste.View taste = full ? Taste.view(app) : null;
            // things worth knowing about the TV itself: a remote running flat, storage running out
            final List<String[]> remotes = full ? BtSheet.lowBatteries(app) : null;
            final long freeBytes = full ? freeStorage() : -1;
            final long data = dataToday(app, c.getTimeInMillis());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                applySky(now, rise, set, up, next, phase);
                applyToday(today, top);
                showData(data);
                if (s != null) applyStack(cw, mine, s, r, top, now, x, used, taste, remotes, freeBytes);
            });
        });
    }

    /**
     * Bytes over Wi-Fi and Ethernet since {@code since}, or -1 when Android won't say. Network
     * statistics come in two-hour buckets, so "today" starts at the bucket holding midnight.
     */
    static long dataToday(Context c, long since) {
        // per-network totals arrived in Android 6
        if (Build.VERSION.SDK_INT < 23) return -1;
        android.app.usage.NetworkStatsManager nsm = c.getSystemService(android.app.usage.NetworkStatsManager.class);
        if (nsm == null) return -1;
        long sum = -1;
        for (int type : new int[]{ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET}) {
            try {
                android.app.usage.NetworkStats.Bucket b = nsm.querySummaryForDevice(type, null, since, System.currentTimeMillis());
                if (b != null) sum = Math.max(sum, 0) + b.getRxBytes() + b.getTxBytes();
            } catch (Exception ignored) {
                // no usage access, or no such network on this TV
            }
        }
        return sum;
    }

    private void showData(long bytes) {
        tdDataRow.setVisibility(bytes >= 0 ? View.VISIBLE : View.GONE);
        if (bytes < 0) return;
        double mb = bytes / 1048576.0;
        tdData.setText((mb < 1000 ? Math.round(mb) + " MB" : String.format(Locale.getDefault(), "%.2f GB", mb / 1024)) + " today");
    }

    private void applySky(long now, long rise, long set, boolean up, long next, Sun.Phase phase) {
        int color;
        switch (phase) {
            case NIGHT:
                color = 0xFFB9C6FF;
                break;
            case DAWN:
                color = 0xFFFFB8C8;
                break;
            case GOLDEN:
                color = 0xFFFFC98A;
                break;
            default:
                color = 0xFFFFE29A;
        }
        widgetGlow[0] = color;
        skyKicker.setTextColor(color);
        skyKicker.setText("●  " + Sun.label(phase));
        java.text.DateFormat tf = DateFormat.getTimeFormat(this);
        String event = up ? "Sunset" : "Sunrise", title = "";
        if (next > 0) {
            long min = (next - now) / 60_000;
            title = min < 180 ? event + " in " + (min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min")
                    : event + " at " + tf.format(new Date(next)).toUpperCase(Locale.getDefault());
        }
        Ui.fit(skyTitle, title, Ui.dp(224), 21, 15);
        skyArc.set(rise, set, now);
        skyRise.setText(rise > 0 ? "↑ " + tf.format(new Date(rise)).toUpperCase(Locale.getDefault()) : "");
        skySet.setText(set > 0 ? tf.format(new Date(set)).toUpperCase(Locale.getDefault()) + " ↓" : "");
    }

    private void applyToday(long today, String top) {
        // something playing (or paused/remembered) takes the widget over: title, who, and OK plays or resumes
        playing = MediaWatch.nowOrLast(this);
        AppRepo.App owner = playing != null ? find(playing.pkg) : null;
        showTodayMusic(playing, owner);
        widgetGlow[2] = owner != null ? owner.color : DAY_GLOW;
        if (playing != null) {
            // memory still shows below
        } else if (today < 0) {
            tdValue.setText("–");
            tdSub.setText("Usage access is off");
        } else {
            long min = today / 60_000;
            tdValue.setText(min < 60 ? min + "m" : (min / 60) + "h " + (min % 60) + "m");
            AppRepo.App a = top == null ? null : find(top);
            tdSub.setText(a != null ? "in apps · mostly " + a.label : "in apps so far");
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        Compat.svc(this, ActivityManager.class).getMemoryInfo(mi);
        memory.set(mi.totalMem > 0 ? mi.availMem / (float) mi.totalMem : 0f);
        tdMem.setText(mi.availMem / (1024 * 1024) + " MB free");
    }

    /** What the stack shows beyond your own history: new releases on your apps and tonight's pick, art decoded. */
    private static final class Extras {
        final List<Fresh.Show> shows = new ArrayList<>();
        final List<android.graphics.Bitmap> art = new ArrayList<>();
        /** Tonight's picks, two a day, with their posters. */
        final List<Picks.Pick> picks = new ArrayList<>();
        final List<android.graphics.Bitmap> pickArt = new ArrayList<>();
    }

    /** The last extras made, and what they were made from: they're kept until any of it changes. */
    private static Extras extrasKept;
    private static String extrasKey;

    /**
     * Off the main thread: up to four new releases and one pick, with their pictures. They change
     * at most once a day, so they're made once and kept: every press of Home used to read and
     * decode all five pictures again (and the stack then worked out each one's blur again).
     */
    private static Extras extras(Context c, int apps) {
        String key = localDay() + "|" + Fresh.stamp(c) + "|" + Picks.stamp(c)
                + "|" + Prefs.on(c, Prefs.PICKS) + "|" + apps + "|" + Taste.changes + "|" + Profiles.current(c);
        synchronized (HomeActivity.class) {
            if (key.equals(extrasKey) && extrasKept != null) return extrasKept;
        }
        Extras x = makeExtras(c);
        synchronized (HomeActivity.class) {
            extrasKept = x;
            extrasKey = key;
        }
        return x;
    }

    /** Short on memory: the kept extras go (the cards on show keep their own pictures). */
    private static synchronized void dropExtras() {
        extrasKept = null;
        extrasKey = null;
    }

    private static Extras makeExtras(Context c) {
        Extras x = new Extras();
        for (Fresh.Show s : Fresh.load(c)) {
            if (x.shows.size() >= 4) break;
            android.graphics.Bitmap b = Fresh.art(s);
            if (b == null) continue;
            x.shows.add(s);
            x.art.add(b);
        }
        if (Prefs.on(c, Prefs.PICKS)) {
            List<Picks.Pick> pool = new ArrayList<>();
            Taste.View t = Taste.view(c);
            // what you turned down, or passed over day after day, is left out of the pool
            for (Picks.Pick p : Picks.load(c)) if (!t.skip(Continue.key(p.name))) pool.add(p);
            if (!pool.isEmpty()) {
                // two a day, the same two all evening and new ones tomorrow (at your midnight), walking
                // the pool in order: thirty picks last a fortnight, and the pool is renewed weekly
                int n = pool.size(), first = (int) ((localDay() * 2) % n);
                for (int i = 0; i < Math.min(2, n); i++) {
                    Picks.Pick p = pool.get((first + i) % n);
                    android.graphics.Bitmap b = Picks.poster(p);
                    if (b == null) continue;
                    x.picks.add(p);
                    x.pickArt.add(b);
                }
            }
        }
        return x;
    }

    /** Days since 1970 on this TV's own calendar: "tomorrow" starts at your midnight, not at UTC's. */
    private static long localDay() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L;
    }

    /**
     * Your watchable apps, the ones you use (at this hour, today, last) first: the order the stack
     * asks apps for your own rows in. Apps you don't use come only when none are used yet.
     */
    private static List<String> ranked(Set<String> installed, Map<String, Float> score, String top, Object[] recent) {
        final Map<String, Float> weight = new HashMap<>();
        for (String pkg : installed) {
            Float s = score.get(pkg);
            float w = (s != null ? s : 0f) + (pkg.equals(top) ? 2f : 0f) + (recent != null && pkg.equals(recent[0]) ? 1f : 0f);
            if (w > 0f) weight.put(pkg, w);
        }
        List<String> out = new ArrayList<>(weight.isEmpty() ? installed : weight.keySet());
        Collections.sort(out, (a, b) -> {
            Float wa = weight.get(a), wb = weight.get(b);
            return Float.compare(wb != null ? wb : 0f, wa != null ? wa : 0f);
        });
        return out;
    }

    /**
     * The smart stack's cards, most useful first: a new episode of something you're watching (or
     * have opened from the stack before), everything you're partway through (from Watch Next, then
     * from the rows of apps that keep it there instead, such as Netflix), your own picks from those
     * rows, your usual app for this hour, what's new today on the apps you use, what you opened
     * last, what you've watched most today, and tonight's two picks.
     *
     * It learns ({@link Taste}): a card you turn down (hold OK) isn't dealt again, and a suggestion
     * shown on visit after visit that you never opened rests for a while. New releases come from
     * the country's schedule, not your account, so only a show you've opened before, or one release
     * from an app you actually use, makes it. New releases and suggested apps can each be switched off.
     */
    private void applyStack(List<Continue.Item> cw, List<Continue.Item> mine, Map<String, Float> score, Object[] recent,
                            String top, long now, Extras x, Set<String> used, Taste.View taste,
                            List<String[]> remotes, long freeBytes) {
        List<SmartStack.Slide> out = new ArrayList<>();
        Set<String> suggested = new HashSet<>();
        Set<Fresh.Show> claimed = new HashSet<>();
        boolean suggest = Prefs.on(this, Prefs.STACK_APPS);
        List<String> yourTitles = taste.openedTitles();
        List<Continue.Item> yours = new ArrayList<>(cw);
        if (mine != null) yours.addAll(mine);
        for (Continue.Item it : yours) {
            if (it.pkg != null && !HomeKeys.allowed(this, it.pkg)) continue;
            String key = Continue.key(it.title);
            boolean watching = cw.contains(it) || it.progress > 0f;
            // what you're partway through only goes when you say so; an app's suggestion can also wear out
            if (taste.declined(key) || !watching && taste.tired(key)) continue;
            SmartStack.Slide s = new SmartStack.Slide();
            AppRepo.App owner = it.pkg == null ? null : find(it.pkg);
            String app = owner != null ? "  ·  " + owner.label.toUpperCase(Locale.getDefault()) : "";
            Fresh.Show fresh = x == null ? null : sameShow(it.title, x.shows);
            s.kicker = (fresh != null ? "NEW EPISODE OUT" : watching ? "CONTINUE WATCHING" : it.kicker != null ? it.kicker : "FOR YOU") + app;
            s.title = it.title;
            s.sub = fresh != null && !fresh.episode.isEmpty() ? fresh.episode : it.sub;
            s.art = it.art;
            s.progress = it.progress;
            s.intent = it.intent;
            s.label = it.title;
            s.fresh = fresh != null;
            s.key = key;
            s.decline = watching ? "Hide from the stack" : "Not interested";
            // what you're watching stays a little longer than a suggestion
            s.dwellMs = watching ? 9_000 : 7_000;
            if (owner != null) {
                s.color = owner.color;
                s.icon = owner.icon;
                if (s.art == null) s.mark = Tiles.glass(owner);
            }
            // a new episode of something you're already watching goes first
            if (fresh != null) {
                claimed.add(fresh);
                out.add(0, s);
            } else {
                out.add(s);
            }
        }
        AppRepo.App usual = null;
        float best = 1.2f; // about two recent visits at this hour before it counts as a habit
        for (Map.Entry<String, Float> e : score.entrySet()) {
            AppRepo.App a = watchable(e.getKey()) && HomeKeys.allowed(this, e.getKey()) && !taste.declined(Taste.app(e.getKey()))
                    ? find(e.getKey()) : null;
            if (a != null && e.getValue() > best) {
                best = e.getValue();
                usual = a;
            }
        }
        if (usual != null && suggest) {
            Calendar c = Calendar.getInstance();
            String hour = DateFormat.is24HourFormat(this) ? c.get(Calendar.HOUR_OF_DAY) + ":00"
                    : (c.get(Calendar.HOUR) == 0 ? 12 : c.get(Calendar.HOUR)) + (c.get(Calendar.AM_PM) == Calendar.AM ? " AM" : " PM");
            out.add(appSlide(usual, "YOUR " + hour + " USUAL", "You tend to open it around this time"));
            suggested.add(usual.pkg);
        }
        // new today on your streaming apps, with the show's own artwork. A show you've opened from the
        // stack before is yours: its new episode leads, like one you're partway through. Anything
        // else is the country's schedule, not your account: one at most, and only from an app you
        // actually use (when Android says which; usage access off, the most popular one)
        int others = 0;
        for (int i = 0; x != null && Prefs.on(this, Prefs.STACK_NEW) && i < x.shows.size(); i++) {
            Fresh.Show f = x.shows.get(i);
            AppRepo.App owner = find(f.pkg);
            String key = Continue.key(f.name);
            if (claimed.contains(f) || owner == null || !HomeKeys.allowed(this, f.pkg) || taste.skip(key)) continue;
            boolean known = Taste.matches(f.name, yourTitles);
            if (!known && (others >= 1 || !used.isEmpty() && !used.contains(f.pkg))) continue;
            SmartStack.Slide s = new SmartStack.Slide();
            String label = owner.label.toUpperCase(Locale.getDefault());
            s.kicker = known ? "NEW EPISODE OUT  ·  " + label : "NEW TODAY ON " + label;
            s.title = f.name;
            s.sub = f.episode.isEmpty() ? "Out now on " + owner.label : f.episode;
            s.art = x.art.get(i);
            s.color = owner.color;
            s.icon = owner.icon;
            s.fresh = true;
            s.intent = owner.launch;
            s.label = owner.label;
            s.key = key;
            s.decline = "Not interested";
            if (known) {
                out.add(0, s);
            } else {
                others++;
                out.add(s);
            }
        }
        AppRepo.App last = suggest && recent != null && HomeKeys.allowed(this, (String) recent[0])
                && !taste.declined(Taste.app((String) recent[0])) ? find((String) recent[0]) : null;
        if (last != null && suggested.add(last.pkg)) out.add(appSlide(last, "JUMP BACK IN", "Opened " + Habits.ago(now - (Long) recent[1])));
        AppRepo.App most = suggest && top != null && watchable(top) && HomeKeys.allowed(this, top)
                && !taste.declined(Taste.app(top)) ? find(top) : null;
        if (most != null && suggested.add(most.pkg)) out.add(appSlide(most, "MOST WATCHED TODAY", "Where most of today's screen time went"));
        List<SmartStack.Slide> picks = new ArrayList<>();
        for (int i = 0; x != null && i < x.picks.size(); i++) {
            Picks.Pick p = x.picks.get(i);
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = (i == 0 ? "TONIGHT'S PICK" : "ALSO TONIGHT") + "  ·  " + p.why;
            s.title = p.name;
            s.sub = p.line;
            s.art = x.pickArt.get(i);
            s.color = 0xFFE8B04A;
            s.intent = Picks.watch(this, p);
            s.label = p.name + " trailer";
            s.dwellMs = 6_000;
            s.key = Continue.key(p.name);
            s.decline = "Not interested";
            picks.add(s);
        }
        // the TV itself, before anything to watch: a remote about to go flat, and storage running out
        // (a full TV slows down). Each can be put off with hold OK until its reading changes
        for (int i = 0; remotes != null && i < remotes.size(); i++) {
            String[] d = remotes.get(i);
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = "BATTERY LOW";
            s.title = d[0];
            s.sub = d[1] + "% left  ·  time for new batteries";
            s.color = 0xFFE8A33A;
            s.icon = getDrawable(R.drawable.ic_bluetooth);
            s.key = "battery:" + d[2] + ":" + Integer.parseInt(d[1]) / 5;
            s.decline = "Not now";
            s.action = this::openBluetooth;
            s.label = d[0];
            s.dwellMs = 8_000;
            out.add(0, s);
        }
        if (freeBytes >= 0 && freeBytes < 600L * 1024 * 1024) {
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = "STORAGE ALMOST FULL";
            s.title = (freeBytes >> 20) + " MB free";
            s.sub = "A full TV slows down  ·  OK to make room";
            s.color = 0xFFE06A4A;
            s.icon = getDrawable(R.drawable.ic_info);
            s.key = "storage:" + (freeBytes >> 20) / 100;
            s.decline = "Not now";
            s.intent = new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Android's storage page uninstalls and clears apps: a kids' profile asks for the PIN first
            final Intent room = s.intent;
            s.action = () -> guarded(() -> launch(room, fyW, "storage"));
            s.label = "storage";
            out.add(Math.min(out.size(), remotes != null ? remotes.size() : 0), s);
        }
        for (int i = out.size() - 1; i >= 0; i--) if (out.get(i).decline != null && "Not now".equals(out.get(i).decline) && taste.declined(out.get(i).key)) out.remove(i);
        // nine cards at most, so the dots stay a glance: the last suggestions go first, and
        // tonight's picks (dealt last) always keep their places
        while (out.size() + picks.size() > 9 && !out.isEmpty()) out.remove(out.size() - 1);
        out.addAll(picks);
        if (out.isEmpty()) {
            for (Cell cell : cells) {
                AppRepo.App a = watchable(cell.tag) ? find(cell.tag) : null;
                if (a != null) {
                    out.add(appSlide(a, "START WITH", "First in your apps"));
                    break;
                }
            }
        }
        stack.setSlides(out);
    }

    /**
     * Fire TV answers most of Android's settings links with a stand-in that only bounces through
     * Amazon's home (or with nothing at all), and keeps its real pages behind a permission only its
     * own apps hold. Its launcher does open Settings itself to any app, by name: there it goes.
     */
    private Intent fireSettings(Intent wanted) {
        if (!Trouble.fireTv(this)) return wanted;
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(wanted, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        String name = ri != null && ri.activityInfo != null ? ri.activityInfo.name : null;
        if (name != null && !name.contains("Dummy") && !name.contains("CTS")) return wanted;
        Intent fire = new Intent("amazon.intent.action.MAINSETTINGS")
                .setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return fire.resolveActivity(getPackageManager()) != null ? fire : wanted;
    }

    /** Free space where apps keep their data, or -1 when the TV won't say. Off the main thread. */
    private static long freeStorage() {
        try {
            return new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Control Center, straight to its Bluetooth sheet. */
    private void openBluetooth() {
        openCc(false);
        cc.showBluetoothSoon();
    }

    /** A release of the show you're partway through: names matched loosely, ignoring case and punctuation. */
    private static Fresh.Show sameShow(String title, List<Fresh.Show> shows) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (t.length() < 3) return null;
        for (Fresh.Show s : shows) {
            String n = s.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (n.length() >= 3 && (t.startsWith(n) || n.startsWith(t))) return s;
        }
        return null;
    }

    private SmartStack.Slide appSlide(AppRepo.App a, String kicker, String sub) {
        SmartStack.Slide s = new SmartStack.Slide();
        s.key = Taste.app(a.pkg);
        s.decline = "Don't suggest " + a.label;
        s.kicker = kicker;
        s.title = a.label;
        s.sub = sub;
        s.mark = Tiles.glass(a);
        s.color = a.color;
        s.icon = a.icon;
        s.intent = a.launch;
        s.label = a.label;
        s.dwellMs = 6_000;
        return s;
    }

    /** Settings and the like are tools, not something to suggest watching. */
    private static boolean watchable(String pkg) {
        return !pkg.contains("settings") && !pkg.startsWith("input:") && !pkg.equals("com.android.vending");
    }

    // ---- focus -------------------------------------------------------------------------------

    private void focusSide(int i) {
        sideAt = Math.max(0, Math.min(side.size() - 1, i));
        side.get(sideAt).requestFocus();
    }

    private void focusWidget(int i) {
        if (widgets.isEmpty()) {
            focusGrid(gridAt);
            return;
        }
        widgetAt = Math.max(0, Math.min(widgets.size() - 1, i));
        scrollTo(0);
        widgets.get(widgetAt).requestFocus();
    }

    private void focusGrid(int i) {
        if (cells.isEmpty() || ccOpen) return;
        gridAt = Math.max(0, Math.min(cells.size() - 1, i));
        reveal(gridAt);
        cells.get(gridAt).card.requestFocus();
    }

    /**
     * The first row shares the screen with the widgets; deeper rows settle on one steady line,
     * but never past the point where the last row sits at the bottom: no empty screen below it.
     */
    private float scrollFor(int row) {
        if (row == 0 || paged) return 0;
        int rows = (cells.size() + COLS - 1) / COLS;
        float screen = root.getHeight() > 0 ? root.getHeight() / Ui.density : 540f;
        float end = gridY() + (rows - 1) * ROW_H + TILE_H + 44 - screen; // last row and its name in view
        // when everything nearly fits, stay put: a nudge of a few dp isn't worth leaving the widgets for
        if (end < 28) return 0;
        return Math.max(0, Math.min(gridY() + row * ROW_H - FOCUS_LINE, end));
    }

    private ValueAnimator bounceAnim;
    private float scrollBlurY;

    /**
     * The glass samples the photo at its own place, so panes repaint as the page travels: every 6dp
     * (under one texel of the blur, so no step shows) and once more where it stops, and only the
     * panes on screen.
     */
    private void repaintScrollGlass(float ty, boolean done) {
        if (!done && Math.abs(ty - scrollBlurY) < Ui.dpf(6)) return;
        scrollBlurY = ty;
        int h = root.getHeight() > 0 ? root.getHeight() : Ui.dp(540);
        for (Widget w : widgets) if (w.getAlpha() > 0f) w.invalidate();
        if (dock) dockGlass.invalidate();
        for (Cell c : cells) {
            if (c.card.glass != null) {
                // cells are placed by translation: getY() is where one sits on the page
                float top = c.getY() + c.card.getTop() + ty;
                if (top + c.card.getHeight() >= -Ui.dp(32) && top <= h + Ui.dp(32)) c.card.invalidate();
            }
        }
    }

    /**
     * The end of the apps: the page gives a little, then springs back, so the edge is felt rather
     * than met with nothing. Only the page's position moves, exactly as when scrolling.
     */
    private void bounce(boolean vertical) {
        if (bounceAnim != null && bounceAnim.isRunning()) return;
        if (vertical && scrollAnim != null) scrollAnim.cancel();
        final View target = vertical ? page : cells.get(gridAt).card;
        final float base = vertical ? -Ui.dpf(scrollTarget) : 0f, reach = Ui.dpf(vertical ? -16 : 10);
        bounceAnim = ValueAnimator.ofFloat(0f, 1f);
        bounceAnim.setDuration(520);
        bounceAnim.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            // a quick push out, then a damped spring home
            float k = t < 0.22f ? t / 0.22f : (float) (Math.exp(-6 * (t - 0.22f)) * Math.cos(9 * (t - 0.22f)));
            if (vertical) {
                float ty = base + reach * k;
                page.setTranslationY(ty);
                repaintScrollGlass(ty, t >= 0.99f);
            } else {
                target.setTranslationX(reach * k);
            }
        });
        bounceAnim.start();
    }

    private void scrollTo(float target) {
        if (target == scrollTarget) return;
        scrollTarget = target;
        if (scrollAnim != null) scrollAnim.cancel();
        if (bounceAnim != null) bounceAnim.cancel();
        scrollAnim = ValueAnimator.ofFloat(page.getTranslationY(), -Ui.dpf(target));
        scrollAnim.setDuration(560);
        scrollAnim.setInterpolator(Ui.EMPHASIZED);
        scrollAnim.addUpdateListener(a -> {
            float ty = (float) a.getAnimatedValue();
            page.setTranslationY(ty);
            // the glass samples the photo at its own position, so visible panes repaint as they travel
            repaintScrollGlass(ty, a.getAnimatedFraction() >= 0.99f);
        });
        scrollAnim.start();
        // the greeting and the status pill leave on any scroll (the apps pass beneath them); the
        // widgets only once they're half off screen, so a short scroll never empties the top
        boolean top = target == 0, shown = dock ? top : target < WIDGET_Y + WIDGET_H / 2f;
        if (top != greetingShown) {
            greetingShown = top;
            fade(greeting, top);
            if (!dock) fade(status, top);
            else if (tvosHint != null) fade(tvosHint, top && zone != TOP);
        }
        if (shown != dashboard) {
            dashboard = shown;
            for (View v : new View[]{skyW, fyW, tdW}) sink(v, shown);
        }
    }

    /**
     * The dashboard doesn't vanish as the apps scroll over it: it sinks into the photograph, a
     * little smaller, falling behind the page as it goes, and rises back into place on the way up.
     */
    private static void sink(View v, boolean in) {
        v.animate().alpha(in ? 1f : 0f).scaleX(in ? 1f : 0.9f).scaleY(in ? 1f : 0.9f).translationY(in ? 0f : Ui.dpf(28))
                .setDuration(in ? 560 : 420).setStartDelay(in ? 80 : 0).setInterpolator(Ui.EMPHASIZED).start();
    }

    private static void fade(View v, boolean in) {
        v.animate().alpha(in ? 1f : 0f).setDuration(in ? 420 : 240).setStartDelay(in ? 120 : 0)
                .setInterpolator(Ui.SOFT).start();
    }

    private void returnToContent() {
        if (contentZone == WIDGETS || cells.isEmpty()) focusWidget(widgetAt);
        else focusGrid(gridAt);
    }

    private void enterSide() {
        if (sideHidden) return;
        View s = Nav.next(new ArrayList<>(side), getCurrentFocus(), View.FOCUS_LEFT);
        if (s != null) s.requestFocus();
        else focusSide(sideAt);
    }

    /** The widget above a column of apps: the one spanning its centre, else the nearest. */
    private int widgetForCol(int col) {
        float cx = col * (TILE_W + GAP) + TILE_W / 2f, best = Float.MAX_VALUE;
        int at = 0;
        for (int n = 0; n < widgets.size(); n++) {
            float d = cx >= widgetX[n] && cx <= widgetX[n] + widgetW[n] ? 0 : Math.abs(cx - (widgetX[n] + widgetW[n] / 2f));
            if (d < best) {
                best = d;
                at = n;
            }
        }
        return at;
    }

    /** Down from a widget: back to the column you came up from, else the one under its centre. */
    private int colForWidget(int w) {
        if (lastCol >= 0 && widgetForCol(lastCol) == w) return lastCol;
        float c = widgetX[w] + widgetW[w] / 2f;
        return Math.max(0, Math.min(COLS - 1, Math.round((c - TILE_W / 2f) / (TILE_W + GAP))));
    }

    // ---- live preview of a connected source ------------------------------------------------

    private android.media.tv.TvView preview;
    private String previewId;
    private Cell previewCell;
    private final Runnable startPreview = this::startPreview;

    private static Card.FocusListener chain(Card.FocusListener a, Card.FocusListener b) {
        return (card, gained) -> {
            if (a != null) a.onFocus(card, gained);
            b.onFocus(card, gained);
        };
    }

    /**
     * A TvView tuned to the input's passthrough channel, muted, laid over the focused tile a few
     * dp inside its glass. It exists only while the tile has focus, so it costs nothing otherwise.
     */
    private void startPreview() {
        if (!resumed || ccOpen || photo || previewCell == null || !previewCell.card.isFocused()) return;
        stopPreview();
        int i = cells.indexOf(previewCell);
        if (i < 0) return;
        float inset = Ui.dpf(6);
        preview = new android.media.tv.TvView(this);
        if (Build.VERSION.SDK_INT >= 24) preview.setZOrderMediaOverlay(true);
        preview.setStreamVolume(0f);
        preview.setAlpha(0f);
        preview.setCallback(new android.media.tv.TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                if (preview != null) preview.animate().alpha(1f).setStartDelay(0).setDuration(380).setInterpolator(Ui.SOFT).start();
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                if (preview != null) preview.animate().alpha(0f).setStartDelay(0).setDuration(200).start();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(Ui.dpf(TILE_W) - 2 * inset), Math.round(Ui.dpf(TILE_H) - 2 * inset));
        preview.setTranslationX(cellX(i) + inset);
        preview.setTranslationY(cellY(i) + inset);
        // match the focused tile's lift
        preview.setScaleX(1.1f);
        preview.setScaleY(1.1f);
        page.addView(preview, lp);
        preview.tune(previewId, TvContract.buildChannelUriForPassthroughInput(previewId));
    }

    private void stopPreview() {
        handler.removeCallbacks(startPreview);
        if (preview == null) return;
        preview.reset();
        page.removeView(preview);
        preview = null;
    }

    private void onFocusMoved(View n) {
        if (n == null || ccOpen) return;
        stack.hold(n == fyW); // the card you're looking at stays put
        int i = side.indexOf(n);
        // the tvOS style's sidebar slides in only while it has focus
        if (dock && !sideHidden) sideShown(i >= 0, true);
        if (dock) topShown(tops.contains(n), true);
        if (tops.contains(n)) {
            zone = TOP;
            hideTip();
            n.getLocationInWindow(loc);
            glide(loc[0] + n.getWidth() / 2f, loc[1] + n.getHeight() / 2f, SIDE_GLOW);
            return;
        }
        if (i >= 0) {
            zone = SIDE;
            sideAt = i;
            showTip(n, sideLabels.get(i));
            n.getLocationInWindow(loc);
            glide(loc[0] + n.getWidth() / 2f, loc[1] + n.getHeight() / 2f, SIDE_GLOW);
            return;
        }
        hideTip();
        i = shelf.cards.indexOf(n);
        if (i >= 0) {
            zone = SHELF;
            shelfAt = i;
            n.getLocationInWindow(loc);
            glide(loc[0] + n.getWidth() / 2f, loc[1] + n.getHeight() / 2f, ((Card) n).color);
            return;
        }
        i = widgets.indexOf(n);
        if (i >= 0) {
            hideShelf();
            zone = contentZone = WIDGETS;
            widgetAt = i;
            glide(Ui.dpf(pageX() + widgetX[i] + widgetW[i] / 2f), Ui.dpf((dock ? gridY() - 18 - 22 - WIDGET_H : WIDGET_Y) + WIDGET_H / 2f), glowOf(widgets.get(i)));
            return;
        }
        for (i = 0; i < cells.size(); i++) {
            if (cells.get(i).card == n) {
                zone = contentZone = GRID;
                gridAt = i;
                glideToCell();
                shelfFor(i);
                return;
            }
        }
    }

    private void glideToCell() {
        Cell c = cells.get(gridAt);
        glide(cellX(gridAt) + Ui.dpf(TILE_W / 2f), cellY(gridAt) + Ui.dpf(TILE_H / 2f) - Ui.dpf(scrollTarget), c.card.color);
    }

    /** The coloured light glides to where focus is heading (not where the scrolling page is now) and takes its colour. */
    private void glide(float cx, float cy, int color) {
        float w = Math.max(1, root.getWidth()), h = Math.max(1, root.getHeight());
        bloom.moveTo(cx / w, cy / h, 900);
        bloom.setColor(color, 700);
        // while a key is held the parallax waits: restarting it every hop would keep the GPU at full rate
        if (!held && !Backdrop.calm) backdrop.setParallax(-(cx / w - 0.5f) * Ui.dpf(24));
    }

    private void showTip(View n, String label) {
        n.getLocationInWindow(loc);
        float y = loc[1] + n.getHeight() / 2f - Ui.dpf(15);
        tipText.setText(label);
        if (!tipShown) {
            tipShown = true;
            tip.setVisibility(View.VISIBLE);
            tip.setTranslationY(y);
            tip.setTranslationX(-Ui.dp(8));
            tip.animate().alpha(1f).translationX(0f).setDuration(320).setStartDelay(40).setInterpolator(Ui.EMPHASIZED)
                    .setUpdateListener(a -> tip.invalidate()).start();
        } else {
            tip.animate().translationY(y).setDuration(320).setStartDelay(0).setInterpolator(Ui.EMPHASIZED)
                    .setUpdateListener(a -> tip.invalidate()).start();
        }
    }

    private void hideTip() {
        if (!tipShown) return;
        tipShown = false;
        tip.animate().alpha(0f).translationX(-Ui.dp(6)).setDuration(180).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    if (!tipShown) tip.setVisibility(View.INVISIBLE);
                }).start();
    }

    // ---- attention: soft while you browse, sharp when you rest, a title card when you leave ----

    private void wake() {
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        if (photo) setPhoto(false);
        if (racked) setRacked(false);
        // nothing drifts into photo mode under an open panel: the next press would only wake it
        if (resumed && !ccOpen && !editing && !overlayOpen()) {
            handler.postDelayed(rack, RACK_MS);
            // a song playing: the photograph and the song's quiet bar come sooner, as a screensaver
            // of Aura's own (the full-screen player no longer takes over by itself)
            handler.postDelayed(goPhoto, listening != null ? MUSIC_PHOTO_MS : PHOTO_MS);
        }
    }

    /** With music on, how long home idles before the photograph and the song take the screen. */
    private static final long MUSIC_PHOTO_MS = 20_000;

    /** The song's small bar over the photograph in photo mode, while music plays; null otherwise. */
    private MusicStrip photoMusic;

    private void photoMusic(boolean on) {
        if (!on) {
            MusicStrip m = photoMusic;
            photoMusic = null;
            if (m != null) m.stop();
            return;
        }
        MediaWatch.Playing p = listening;
        if (photoMusic != null || p == null || p.controller == null || !p.playing) return;
        final MusicStrip m = new MusicStrip(this);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
        // above the photograph's clock, lined up with it
        ml.leftMargin = Ui.dp(64);
        ml.bottomMargin = Ui.dp(196);
        root.addView(m, root.indexOfChild(story) + 1, ml);
        photoMusic = m;
        try {
            m.start(p.controller, () -> {
                root.removeView(m);
                if (photoMusic == m) photoMusic = null;
            });
        } catch (RuntimeException e) {
            // the player went as it was asked for
            root.removeView(m);
            photoMusic = null;
        }
    }

    private boolean overlayOpen() {
        return welcome != null || settings != null && settings.isOpen() || folderPanel != null && folderPanel.isOpen()
                || switcher != null && switcher.isOpen() || nowPlaying != null && nowPlaying.isOpen()
                || menu != null && menu.isOpen() || ask != null && ask.isOpen() || findPanel != null && findPanel.isOpen()
                || weatherSheet != null && weatherSheet.isOpen();
    }

    private void setRacked(boolean on) {
        racked = on;
        backdrop.setDefocus(on ? 0f : HOME_DEFOCUS, on ? 2600 : 520);
        bloom.setStrength(on ? 0.35f * glow() : glow(), on ? 2600 : 520);
    }

    /** After dark the coloured light is softer, so the room isn't lit up by the menu. */
    private static float glow() {
        return Sun.phase(System.currentTimeMillis()) == Sun.Phase.NIGHT ? 0.7f : 1f;
    }

    private void setPhoto(boolean on) {
        if (photo == on) return;
        stack.hold(on || getCurrentFocus() == fyW); // nothing changes behind the title card
        photo = on;
        if (on) {
            stopPreview();
            racked = true;
            backdrop.setDefocus(0f, 2000);
            bloom.setStrength(0f, 2000);
            story.tick(Calendar.getInstance());
            story.reveal(900);
            photoMusic(true);
        } else {
            photoMusic(false);
            racked = false;
            backdrop.setDefocus(HOME_DEFOCUS, 520);
            bloom.setStrength(glow(), 520);
            story.hide(300);
            home.setVisibility(View.VISIBLE);
            if (!ccOpen && !overlayOpen()) returnToContent();
        }
        home.animate().alpha(on ? 0f : 1f).translationY(on && dock ? Ui.dpf(140) : 0f).setDuration(on ? (dock ? 680 : 1600) : 420).setStartDelay(0).setInterpolator(dock ? Ui.EMPHASIZED : Ui.SOFT)
                .withEndAction(on ? () -> {
                    if (photo) home.setVisibility(View.INVISIBLE);
                } : null).start();
    }

    // ---- keys --------------------------------------------------------------------------------

    /**
     * Every press of the remote, with a net under it: a bug in what one key does is noted (Problem
     * reports) and that press is let go, rather than the home screen closing in the viewer's hands.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        keyAt = SystemClock.uptimeMillis();
        try {
            return handleKey(e);
        } catch (RuntimeException ex) {
            Trouble.caught("key " + KeyEvent.keyCodeToString(e.getKeyCode()), ex);
            return true;
        }
    }

    private boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // once it's dissolving, home is there and answers at once (it used to wait out the fade)
        if (splash != null && !splash.exiting() && !isVolumeKey(code)) {
            // never more than a moment: a press after 9 s takes it away
            if (SystemClock.uptimeMillis() - splashAt > 9000) dropSplash.run();
            return true;
        }
        // teaching a remote button (Settings, Remote buttons): it may reach this window and not the service
        if (HomeKeys.teaching() && HomeKeys.teachable(code)) {
            if (down && e.getRepeatCount() == 0) HomeKeys.taught(code, e.getScanCode());
            return true;
        }
        // a remote button given an app (Settings, Remote buttons), where Android lets it reach here
        String given = settings.isOpen() || welcome != null ? null : HomeKeys.mapped(this, code, e.getScanCode());
        if (given != null) {
            if (!down && !e.isCanceled()) HomeKeys.launch(this, given);
            return true;
        }
        if (welcome != null && welcome.isOpen() && !isVolumeKey(code)) {
            if (!down) return true;
            welcome.key(code);
            return swallow(code);
        }
        // finding an app: the field, the keyboard and the results have the keys; Back leaves it
        if (findPanel.isOpen()) {
            if (down && code == KeyEvent.KEYCODE_BACK) {
                closeFind();
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        // the name card: the field and the keyboard have the keys; Back leaves it
        if (ask.isOpen()) {
            if (down && code == KeyEvent.KEYCODE_BACK) {
                closeAsk();
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        if (!down) {
            if (held && isArrow(code)) settle();
            if (code == swallowUp) {
                swallowUp = -1;
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        boolean wasResting = photo;
        wake();
        if (wasResting && !isVolumeKey(code)) return swallow(code);
        boolean first = e.getRepeatCount() == 0;
        if (first && Pad.isConfirm(code)) heldConfirm = code;
        if (nowPlaying.isOpen()) {
            nowPlaying.poke();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeNowPlaying();
                return swallow(code);
            }
            // only the three buttons take focus; up and down have nowhere to go
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.dispatchKeyEvent(e);
        }
        if (who.isOpen()) {
            if (first || isArrow(code)) who.key(code);
            return swallow(code);
        }
        if (weatherSheet.isOpen()) {
            // nothing on it to move between: Back, Menu or OK put it away, the arrows rest
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || Pad.isConfirm(code)) {
                if (first) closeWeather();
                return swallow(code);
            }
            if (isArrow(code)) return true;
            return super.dispatchKeyEvent(e);
        }
        if ((switcher.isClosing() || switcher.isLaunching()) && !isVolumeKey(code)) return swallow(code);
        if (switcher.isOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeSwitcher(code == KeyEvent.KEYCODE_BACK);
                return swallow(code);
            }
            if (isArrow(code) || Pad.isConfirm(code)) {
                // a held OK doesn't open the same app twice
                if (isArrow(code) || first) switcher.key(code);
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        if (menu.isOpen() && folderPanel.isOpen()) {
            // a menu over an open folder: Back closes the menu, and the folder's tile keeps focus
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                menu.hide();
                return swallow(code);
            }
            if (Pad.isConfirm(code) && e.getRepeatCount() > 0) return true;
            return super.dispatchKeyEvent(e);
        }
        if (menu.isOpen()) return menuKey(code, e);
        if (folderPanel.isOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                    || code == KeyEvent.KEYCODE_INFO) {
                closeFolder();
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        // Menu over Settings closes Settings, rather than stacking Control Center on top of it
        if (first && settings.isOpen() && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_INFO)) {
            if (!settings.back()) closeSettings();
            return swallow(code);
        }
        if (first && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_INFO
                || code == KeyEvent.KEYCODE_NOTIFICATION)) {
            if (ccOpen) closeCc(true);
            else openCc(false);
            return swallow(code);
        }
        // Google TV remotes' profile button: Aura's profiles (288 is KEYCODE_PROFILE_SWITCH, named from Android 12)
        if (first && code == HomeKeys.KEYCODE_PROFILE_SWITCH) {
            if (ccOpen) closeCc(false);
            openWho();
            return swallow(code);
        }
        if (editing) return editKey(code, e);
        if (ccOpen) return cc.handleKey(code, getCurrentFocus()) ? swallow(code) : super.dispatchKeyEvent(e);
        // the settings list focuses and scrolls itself
        if (settings.isOpen()) return super.dispatchKeyEvent(e);
        if (first && (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            MediaWatch.Playing p = MediaWatch.now(this);
            if (p != null && p.controller != null) {
                if (p.playing) p.controller.getTransportControls().pause();
                else p.controller.getTransportControls().play();
                hud(R.drawable.ic_check, (p.playing ? "Paused  ·  " : "Playing  ·  ") + p.title);
                handler.postDelayed(() -> {
                    refreshWidgets(false);
                    if (zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) {
                        shelf.clearShown();
                        shelfFor(gridAt);
                    }
                }, 250);
                return swallow(code);
            }
        }
        // a Fire TV remote has no next or previous: with music playing, its rewind and fast-forward skip
        if (first && (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND
                || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            MediaWatch.Playing p = MediaWatch.now(this);
            if (p != null && p.controller != null && p.playing) {
                boolean next = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_NEXT;
                if (next) p.controller.getTransportControls().skipToNext();
                else p.controller.getTransportControls().skipToPrevious();
                hud(R.drawable.ic_check, next ? "Next song" : "Previous song");
                handler.postDelayed(() -> refreshWidgets(false), 600);
                return swallow(code);
            }
        }
        // channel up and down (and page keys): a page of apps at a time, or a couple of rows
        if (first && (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN) && !cells.isEmpty() && !folderPanel.isOpen()) {
            boolean back = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP;
            int step = paged ? perPage() : COLS * 2;
            focusGrid(Math.max(0, Math.min(cells.size() - 1, gridAt + (back ? -step : step))));
            return swallow(code);
        }
        // a letter on a keyboard remote: straight into finding an app, the letter typed
        if (first && code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z && !folderPanel.isOpen()) {
            int ch = e.getUnicodeChar();
            openFind(ch > 0 ? String.valueOf((char) ch) : "");
            return swallow(code);
        }
        return homeKey(code, e) ? swallow(code) : super.dispatchKeyEvent(e);
    }

    /** Every arrow is routed by hand: rows, columns and zones move exactly where you expect, held or tapped. */
    private boolean homeKey(int code, KeyEvent e) {
        if (!isArrow(code)) return false;
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() > 0) {
            // a held key repeats ~20 times a second; step at a steady pace in short hops instead
            if (now - lastStep < HOLD_STEP_MS) return true;
            held = true;
        }
        lastStep = now;
        View f = getCurrentFocus();
        if (zone == SIDE && side.indexOf(f) < 0 || zone == WIDGETS && widgets.indexOf(f) < 0
                || zone == SHELF && shelf.cards.indexOf(f) < 0 || zone == TOP && !tops.contains(f)) zone = contentZone;
        switch (zone) {
            case TOP:
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (shelf.shownFor() != null && !shelf.cards.isEmpty()) shelf.cards.get(Math.min(shelfAt, shelf.cards.size() - 1)).requestFocus();
                    else if (!widgets.isEmpty()) focusWidget(widgets.size() - 1);
                    else returnToContent();
                }
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int at = tops.indexOf(f) + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (at >= 0 && at < tops.size()) tops.get(at).requestFocus();
                    else if (at < 0 && !held) enterSide();
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && !held) {
                    setPhoto(true);
                }
                return true;
            case SHELF:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && shelfAt > 0) shelf.cards.get(shelfAt - 1).requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && shelfAt < shelf.cards.size() - 1) shelf.cards.get(shelfAt + 1).requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusGrid(gridAt);
                else if (code == KeyEvent.KEYCODE_DPAD_UP && dock && !held) topCc.requestFocus();
                return true;
            case SIDE:
                if (code == KeyEvent.KEYCODE_DPAD_UP) focusSide(sideAt - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusSide(sideAt + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) returnToContent();
                return true;
            case WIDGETS:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (widgetAt > 0) focusWidget(widgetAt - 1);
                    else enterSide();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (widgetAt < widgets.size() - 1) focusWidget(widgetAt + 1);
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !cells.isEmpty()) {
                    focusGrid(Math.min(cells.size() - 1, colForWidget(widgetAt)));
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && !held) {
                    if (dock) topCc.requestFocus();
                    else if (widgets.get(widgetAt) == fyW) stack.flip();
                }
                return true;
            default: {
                if (cells.isEmpty()) {
                    focusWidget(widgetAt);
                    return true;
                }
                int col = gridAt % COLS, row = rowOf(gridAt), page = pageOf(gridAt), n = cells.size();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (col > 0) focusGrid(gridAt - 1);
                        // past the first column: the previous page, same row, its last column
                        else if (page > 0) focusGrid(gridAt - perPage() + COLS - 1);
                        else if (!held) enterSide();
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (col < COLS - 1 && gridAt + 1 < n && pageOf(gridAt + 1) == page && rowOf(gridAt + 1) == row) {
                            focusGrid(gridAt + 1);
                        } else if (paged && (page + 1) * perPage() < n) {
                            // the next page slides in: same row, first column (or its last app, if shorter)
                            focusGrid(Math.min(n - 1, (page + 1) * perPage() + row * COLS));
                        } else if (!held) {
                            bounce(false);
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (row > 0) focusGrid(gridAt - COLS);
                        else if (!held && shelf.shownFor() != null && shelf.shownFor().equals(cells.get(gridAt).tag)
                                && !shelf.cards.isEmpty()) {
                            // up into this app's Top Shelf, onto the card nearest above
                            shelf.cards.get(Math.min(shelf.cards.size() - 1, col * shelf.cards.size() / COLS)).requestFocus();
                        } else if (!held && !widgets.isEmpty()) {
                            lastCol = col;
                            focusWidget(widgetForCol(col));
                        } else if (!held && dock) {
                            // nothing above the dock but the photograph: up to Control Center, top right
                            topCc.requestFocus();
                        } else if (!held) {
                            setPhoto(true);
                        }
                        break;
                    default:
                        if (paged) {
                            // two rows a page: down to the second, or to its last app if it's shorter
                            int below = Math.min(n - 1, gridAt + COLS);
                            if (row == 0 && pageOf(below) == page && rowOf(below) == 1) focusGrid(below);
                            else if (!held) bounce(true);
                        } else if (gridAt + COLS < n) {
                            focusGrid(gridAt + COLS);
                        } else if (row < (n - 1) / COLS) {
                            focusGrid(n - 1);
                        } else if (!held) {
                            bounce(true);
                        }
                        break;
                }
                return true;
            }
        }
    }

    /** A held key was released: the tile you landed on catches the light. */
    private void settle() {
        held = false;
        View f = getCurrentFocus();
        if (f instanceof Card) ((Card) f).glint();
        if (f != null) onFocusMoved(f);
    }

    private boolean editKey(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveEditing(-1);
                return swallow(code);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveEditing(1);
                return swallow(code);
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!paged || rowOf(gridAt) > 0) moveEditing(-COLS);
                return swallow(code);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!paged || rowOf(gridAt) == 0) moveEditing(COLS);
                return swallow(code);
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // the OK that started rearranging is usually still held: its repeats mustn't end it.
                // Only a fresh press does.
                if (e.getRepeatCount() > 0) return true;
                stopEdit();
                return swallow(code);
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private boolean swallow(int code) {
        swallowUp = code;
        return true;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean isVolumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    // ---- top shelf ---------------------------------------------------------------------------

    private TopShelf shelf;
    private int shelfAt;
    private volatile String shelfWanted;
    /** The song the app previews last saw ({@code pkg|title|playing|has cover}): a change draws them again. */
    private String shelfSong;

    /**
     * Resting on an app in the top row brings up its Top Shelf a moment later; anywhere else the
     * widgets come back. Content is looked up off the main thread and cached per app.
     */
    private void shelfFor(int i) {
        handler.removeCallbacks(shelfLoad);
        // the music has the space above the apps to itself
        if (listening != null) {
            hideShelf();
            return;
        }
        Cell c = cells.get(i);
        boolean top = rowOf(i) == 0 && !c.tag.startsWith("input:") && Prefs.on(this, Prefs.TOP_SHELF) && !editing;
        if (!top) {
            hideShelf();
            return;
        }
        if (c.tag.equals(shelf.shownFor())) return;
        shelfWanted = c.tag;
        // the last app's cards don't stay over this one while its own are found
        shelf.fade();
        // while the home screen is still arriving (the TV just started or woke), an app's cards
        // wait for its tile: nothing appears above the dock before the dock itself. Cards found a
        // moment ago come almost at once; others wait until focus has settled on the app
        long settle = TopShelf.cached(c.tag) ? 150 : 650;
        handler.postDelayed(shelfLoad, Math.max(settle, introUntil - SystemClock.uptimeMillis()));
    }

    /** When the intro's last tile has settled: Top Shelf waits for it. */
    private long introUntil;

    private final Runnable shelfLoad = () -> {
        final String pkg = shelfWanted;
        if (pkg == null) return;
        // the loading screen is still up, or the home screen is still arriving: ask again once it has
        long wait = introUntil - SystemClock.uptimeMillis();
        if (splash != null || introPending || wait > 0) {
            handler.postDelayed(this.shelfLoad, Math.max(300, wait));
            return;
        }
        final Context app = getApplicationContext();
        // on a thread of its own: behind home's worker (the widgets, artwork, the stack) the cards
        // were sometimes quick and sometimes seconds late
        shelfWorker.execute(() -> {
            // focus moved on while this waited: the app it's on now has its own turn
            if (!pkg.equals(shelfWanted)) return;
            final List<Continue.Item> items = TopShelf.items(app, pkg);
            runOnUiThread(() -> {
                if (isDestroyed() || !pkg.equals(shelfWanted) || zone != GRID || rowOf(gridAt) > 0) return;
                if (items.isEmpty()) {
                    hideShelf();
                    return;
                }
                final AppRepo.App a = find(pkg);
                shelf.show(pkg, items, a != null ? a.color : DAY_GLOW, it -> {
                    if (it.nowPlaying) {
                        MediaWatch.Playing p = MediaWatch.now(HomeActivity.this);
                        if (p != null && p.controller != null) {
                            if (!p.playing) p.controller.getTransportControls().play();
                            openNowPlaying();
                            return;
                        }
                    }
                    Intent go = TopShelf.launchFor(it, it.pkg != null ? find(it.pkg) : a);
                    if (go != null) launch(go, shelf.cards.isEmpty() ? cells.get(gridAt).card : shelf.cards.get(shelfAt), it.title);
                });
                for (Widget w : widgets) w.animate().alpha(0f).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT).start();
            });
        });
    };

    private void hideShelf() {
        handler.removeCallbacks(shelfLoad);
        shelfWanted = null;
        if (!shelf.up()) return;
        shelf.hide();
        if (dashboard) for (Widget w : widgets) w.animate().alpha(1f).setDuration(380).setStartDelay(120).setInterpolator(Ui.SOFT).start();
    }

    // ---- now playing -------------------------------------------------------------------------

    private NowPlaying nowPlaying;

    private void openNowPlaying() {
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        MediaWatch.Playing p = MediaWatch.now(this);
        if (p == null || !nowPlaying.open(p.controller)) {
            refreshWidgets(false);
            return;
        }
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        handler.postDelayed(() -> {
            if (nowPlaying.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 300);
    }

    private void closeNowPlaying() {
        if (!nowPlaying.isOpen()) return;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        nowPlaying.close();
        stack.hold(false);
        if (listening != null && listenW.isShown()) listenW.requestFocus();
        else if (dashboard) tdW.requestFocus();
        else focusGrid(gridAt);
        refreshWidgets(false);
        wake();
    }

    // ---- app switcher ------------------------------------------------------------------------

    private Switcher switcher;
    private long lastHomeAt;
    private View beforeSwitcher;

    /** The app the switcher was opened over (Home twice inside it), to go back to with Back. */
    private String switcherOver;

    private void openSwitcher() {
        openSwitcher(null);
    }

    /** With {@code over}: opened from inside that app, so nothing of the home screen shows. */
    private void openSwitcher(String over) {
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        if (switcher.isOpen() || apps.isEmpty()) return;
        if (ccOpen) closeCc(false);
        if (settings.isOpen()) closeSettings();
        // recent apps covers the whole screen: nothing stays open beneath it to come back to
        closeOverlays();
        if (editing) stopEdit();
        stopPreview();
        hideTip();
        switcherOver = over;
        if (over != null) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        // the app just left always goes first: the one opened over, else the last one the Home
        // button service saw. Without either, a list read before Aura last went to an app can't
        // know it, so the deck waits the moment it takes to read it afresh
        String newest = newest(over, recentCache);
        if (recentCache != null && (recentAt >= awayAt || newest != null)) {
            recentCache = withNewest(recentCache, newest);
            showSwitcher(recentCache);
            // the list refreshes behind it for next time
            warmRecents(false);
        } else {
            warmRecents(true);
        }
    }

    /** The app the switcher should put first: the one it opens over, else the newest the Home button service saw. */
    private static String newest(String over, Map<String, Long> recent) {
        if (over != null) return over;
        String last = HomeKeys.lastApp;
        if (last == null || !HomeKeys.live) return null;
        Long head = recent == null || recent.isEmpty() ? null : recent.values().iterator().next();
        return head == null || HomeKeys.lastAppAt > head ? last : null;
    }

    /** {@code recent} with {@code pkg} moved (or added) to the front. */
    private java.util.LinkedHashMap<String, Long> withNewest(java.util.LinkedHashMap<String, Long> recent, String pkg) {
        if (pkg == null || find(pkg) == null || !HomeKeys.allowed(this, pkg)) return recent;
        if (!recent.isEmpty() && recent.keySet().iterator().next().equals(pkg)) return recent;
        java.util.LinkedHashMap<String, Long> r = new java.util.LinkedHashMap<>();
        r.put(pkg, pkg.equals(HomeKeys.lastApp) ? HomeKeys.lastAppAt : System.currentTimeMillis());
        for (Map.Entry<String, Long> e : recent.entrySet()) if (!e.getKey().equals(pkg)) r.put(e.getKey(), e.getValue());
        return r;
    }

    private final Runnable dropFreshSoon = () -> {
        if (!switcher.isOpen() && !switcher.isLaunching()) Previews.dropFresh();
    };

    private final Runnable healKeys = () -> HomeKeys.heal(getApplicationContext());

    private final Runnable warmRecentsSoon = () -> {
        if (!apps.isEmpty()) warmRecents(false);
    };

    /** The switcher's list, newest first, read off the main thread and kept for an instant open. */
    private java.util.LinkedHashMap<String, Long> recentCache;
    /** When that list was read, and when Aura last went behind an app (uptime): older than that, it may miss the app just left. */
    private long recentAt, awayAt;
    /** Home twice came while the list was being read: the switcher opens as soon as it's in. */
    private boolean openWhenWarm;
    private int warming;

    private void warmRecents(boolean thenOpen) {
        if (thenOpen) openWhenWarm = true;
        final Context app = getApplicationContext();
        final Set<String> launchable = new HashSet<>();
        // a kids' profile doesn't see (or open) what it hides, here either
        for (AppRepo.App a : apps) if (watchable(a.pkg) && HomeKeys.allowed(this, a.pkg)) launchable.add(a.pkg);
        final long readAt = SystemClock.uptimeMillis();
        warming++;
        recentWorker.execute(() -> {
            final java.util.LinkedHashMap<String, Long> recent = Habits.recentList(app, launchable, 8);
            final Map<String, Tiles.Painter> f = new java.util.HashMap<>();
            final Map<String, Long> stamps = new java.util.HashMap<>();
            faces(app, recent.keySet(), f, stamps, keptFaces, keptStamps);
            runOnUiThread(() -> {
                warming--;
                if (isDestroyed()) return;
                recentAt = Math.max(recentAt, readAt);
                recentCache = withNewest(recent, newest(switcherOver, recent));
                faces = f;
                keptFaces = f;
                keptStamps = stamps;
                switcher.prepare(recentCache, stamps);
                // the last read in flight answers a Home twice that came while it was reading
                if (openWhenWarm && warming == 0) {
                    openWhenWarm = false;
                    if (!overlayOpen() && !ccOpen && (resumed || switcherOver != null)) showSwitcher(recentCache);
                }
            });
        });
    }

    /** Each switcher card's picture: the screen as you left the app, then what you were watching in it. */
    private Map<String, Tiles.Painter> faces = new java.util.HashMap<>();

    /** The last faces made, reused while their picture hasn't changed: decoding is skipped. */
    private volatile Map<String, Tiles.Painter> keptFaces = new java.util.HashMap<>();
    private volatile Map<String, Long> keptStamps = new java.util.HashMap<>();

    /** Off the main thread: the faces for {@code pkgs}, with when each was made (to know a stale card). */
    private static void faces(Context c, Set<String> pkgs, Map<String, Tiles.Painter> out, Map<String, Long> stamps,
                              Map<String, Tiles.Painter> kept, Map<String, Long> keptStamp) {
        boolean shots = Previews.on(c);
        // pictures are 640x360; the card paints at 0.6 of its size, which is about that
        int w = 640, h = 360;
        List<Continue.Item> watching = null;
        for (String pkg : pkgs) {
            long when = shots ? Previews.stamp(c, pkg) : 0;
            if (when > 0 && Long.valueOf(when).equals(keptStamp.get(pkg)) && kept.get(pkg) != null) {
                out.put(pkg, kept.get(pkg));
                stamps.put(pkg, when);
                continue;
            }
            android.graphics.Bitmap shot = shots ? Previews.load(c, pkg, w, h) : null;
            if (shot != null) {
                out.put(pkg, Previews.painter(shot));
                stamps.put(pkg, Previews.stamp(c, pkg));
                continue;
            }
            // no picture of its screen (left before one was taken, or a protected video filled it):
            // what's playing in it, laid out as the paused player would show it
            MediaWatch.Playing playing = MediaWatch.forApp(c, pkg);
            android.graphics.Bitmap pArt = MediaWatch.resolveArt(c, playing);
            if (playing != null && pArt != null) {
                out.put(pkg, Tiles.screen(pArt, playing.title, playing.by, playing.progress));
                stamps.put(pkg, -2L - Math.abs((long) String.valueOf(playing.title).hashCode()));
                continue;
            }
            if (watching == null) watching = Continue.all(c, 8);
            for (Continue.Item it : watching) {
                if (pkg.equals(it.pkg) && it.art != null) {
                    out.put(pkg, Tiles.screen(it.art, it.title, it.sub, it.progress));
                    stamps.put(pkg, -1L - Math.abs((long) String.valueOf(it.title).hashCode()));
                    break;
                }
            }
        }
        // pictures of apps no longer in the list (or uninstalled) go
        if (shots) Previews.prune(c, pkgs);
    }

    private void showSwitcher(Map<String, Long> recent) {
        // opened over an app: its picture, taken a moment ago, is the freshest face it has
        android.graphics.Bitmap fresh = Previews.fresh(switcherOver);
        if (fresh != null) {
            faces.put(switcherOver, Previews.painter(fresh));
            switcher.face(switcherOver, android.os.SystemClock.uptimeMillis());
        }
        // checked again as it opens: a list read before a switch to a kids' profile (or before an
        // app was hidden from it) mustn't offer what that profile hides
        java.util.LinkedHashMap<String, Long> shown = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Long> e : recent.entrySet()) if (HomeKeys.allowed(this, e.getKey())) shown.put(e.getKey(), e.getValue());
        beforeSwitcher = getCurrentFocus();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        // the photograph beneath the frost stops drifting: otherwise the whole screen redraws every 80 ms
        backdrop.stop();
        switcher.open(shown, switcherOver);
        // the frost is opaque from the first frame, so nothing beneath it needs to draw at all
        for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
    }

    private void closeSwitcher() {
        closeSwitcher(false);
    }

    /** With {@code back}, opened over an app: that app comes straight back, as on tvOS. */
    private void closeSwitcher(boolean back) {
        String over = switcherOver;
        switcherOver = null;
        if (back && over != null) {
            Intent again = getPackageManager().getLeanbackLaunchIntentForPackage(over);
            if (again == null) again = getPackageManager().getLaunchIntentForPackage(over);
            AppRepo.App app = find(over);
            if (again != null && app != null && switcher.isOpen()) {
                final Intent back2 = again;
                switcher.launchInto(app, () -> openFromSwitcher(back2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), app.label));
                return;
            }
            if (again != null) {
                try {
                    startActivity(again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    overridePendingTransition(0, 0);
                    // the home screen is put back once the app covers it
                    handler.postDelayed(() -> closeSwitcher(false), 400);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
            }
        }
        // the photograph drifts again (it stopped while the switcher was up)
        if (resumed) backdrop.start();
        if (!switcher.isOpen()) {
            if (!switcher.isClosing()) for (View v : behindCc()) v.setVisibility(View.VISIBLE);
            return;
        }
        switcher.close(() -> {
            Previews.dropFresh();
            for (View v : behindCc()) v.setVisibility(View.VISIBLE);
            home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            stack.hold(false);
            if (beforeSwitcher == null || !beforeSwitcher.isShown() || !beforeSwitcher.requestFocus()) focusGrid(gridAt);
            wake();
        });
    }

    // ---- app menu ----------------------------------------------------------------------------

    private AppMenu menu;

    /** Hold OK on an app: move it, hide it, see its details in Android's settings, or uninstall it. */
    private void openMenu() {
        if (editing || ccOpen || menu.isOpen() || zone != GRID || cells.isEmpty()) return;
        Cell c = cells.get(gridAt);
        if (c.tag.startsWith("input:")) {
            // an input: it can't move (switched-on sources stand first) but it can leave the home screen
            final String tag = c.tag, name = c.label.getText().toString();
            final Card card = c.card;
            stopPreview();
            swallow(heldConfirm);
            List<AppMenu.Item> items = new ArrayList<>();
            items.add(new AppMenu.Item(R.drawable.ic_hide, "Hide from home", () -> {
                closeMenu(card);
                Prefs.hide(this, tag, true);
                live.clear();
                live.addAll(liveInputs());
                populate();
                focusGrid(0);
                hud(R.drawable.ic_hide, name + " is hidden  ·  Settings, Hidden apps brings it back");
            }));
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            menu.show(card, name, items);
            return;
        }
        final String pkg = c.tag, label = c.label.getText().toString();
        final Card card = c.card;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        // the OK that opened the menu is still held: its release mustn't press the first row
        swallow(heldConfirm);
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(new AppMenu.Item(R.drawable.ic_move, "Move", () -> {
            closeMenu(card);
            startEdit();
        }));
        if (pkg.startsWith("folder:")) {
            final String id = pkg.substring(7);
            items.add(new AppMenu.Item(R.drawable.ic_settings, "Rename", () -> {
                closeMenu(card);
                nameFolder(id, false);
            }));
            items.add(new AppMenu.Item(R.drawable.ic_apps, "Remove folder", () -> {
                closeMenu(card);
                // its apps return to where the folder stood, in the folder's order
                Folders.Folder gone = Folders.all(this).get(id);
                List<String> order = AppRepo.order(prefs);
                int at = order.indexOf(pkg);
                if (gone != null && at >= 0) {
                    order.remove(at);
                    order.removeAll(gone.apps);
                    order.addAll(Math.min(at, order.size()), gone.apps);
                    AppRepo.saveDock(prefs, order);
                }
                Folders.dissolve(this, id);
                populate();
                hud(R.drawable.ic_apps, label + " removed  ·  its apps are back on the grid");
            }));
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            menu.show(card, label, items);
            return;
        }
        items.add(new AppMenu.Item(R.drawable.ic_apps, "Add to folder", () -> {
            closeMenu(card);
            chooseFolder(pkg, label, card);
        }));
        // a kids' profile can move apps and make folders, nothing more
        if (!Profiles.kids(this)) {
            items.add(new AppMenu.Item(R.drawable.ic_picture, "Change its picture", () -> {
                closeMenu(card);
                openSettings();
                if (settings.isOpen()) settings.art(pkg, label);
            }));
            items.add(new AppMenu.Item(R.drawable.ic_hide, "Hide from home", () -> {
                closeMenu(card);
                Prefs.hide(this, pkg, true);
                populate();
                hud(R.drawable.ic_hide, label + " is hidden  ·  Settings brings it back");
            }));
            items.add(new AppMenu.Item(R.drawable.ic_info, "App info", () -> {
                closeMenu(card);
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg)), card);
            }));
            if (removable(pkg)) items.add(new AppMenu.Item(R.drawable.ic_trash, "Uninstall", () -> {
                closeMenu(card);
                // Android asks to confirm; the grid updates itself once the app is gone
                open(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)), card);
            }));
        }
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menu.show(card, label, items);
    }

    /** Hold OK on a widget: move it along the row, or hide it (the others widen to fill its place). */
    private void openWidgetMenu(Widget w) {
        int n = widgets.indexOf(w), id = -1;
        for (int i = 0; i < 3; i++) if (allWidgets[i] == w) id = i;
        if (editing || ccOpen || menu.isOpen() || n < 0 || id < 0) return;
        final String name = WIDGET_NAMES[id], key = WIDGET_IDS[id];
        swallow(heldConfirm);
        List<AppMenu.Item> items = new ArrayList<>();
        // the card on show first: leave it out of the stack from now on
        final SmartStack.Slide card = w == fyW ? stack.current() : null;
        if (card != null && card.key != null && card.decline != null) {
            items.add(new AppMenu.Item(R.drawable.ic_hide, card.decline, () -> {
                closeMenu(w);
                Taste.decline(getApplicationContext(), card.key);
                hud(R.drawable.ic_hide, "It won't come back  ·  Settings → Widgets brings it back");
                refreshWidgets(true);
            }));
        }
        if (n > 0) items.add(new AppMenu.Item(R.drawable.ic_move, "Move left", () -> moveWidget(w, -1)));
        if (n < widgets.size() - 1) items.add(new AppMenu.Item(R.drawable.ic_move, "Move right", () -> moveWidget(w, 1)));
        // what the stack deals: its cards are chosen on the Widgets page
        if (w == fyW) items.add(new AppMenu.Item(R.drawable.ic_tune, "Choose cards", () -> {
            closeMenu(w);
            openSettings("widgets");
        }));
        if (widgets.size() > 1) items.add(new AppMenu.Item(R.drawable.ic_hide, "Hide", () -> {
            closeMenu(w);
            Prefs.hideWidget(this, key, true);
            layoutWidgets(true);
            focusWidget(Math.min(n, widgets.size() - 1));
            hud(R.drawable.ic_hide, name + " is hidden  ·  Settings brings it back");
        }));
        else items.add(new AppMenu.Item(R.drawable.ic_hide, "Only apps · tvOS style", () -> {
            closeMenu(w);
            Prefs.of(this).edit().putString(Prefs.LAYOUT, "tvos").apply();
            applySetting("layout");
            hud(R.drawable.ic_check, "tvOS style  ·  Settings → Home screen brings widgets back");
        }));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menu.show(w, name, items);
    }

    /** Swaps a widget with its neighbour on screen; hidden widgets keep their place in the order. */
    private void moveWidget(Widget w, int d) {
        closeMenu(w);
        int n = widgets.indexOf(w), m = n + d;
        if (n < 0 || m < 0 || m >= widgets.size()) return;
        List<Integer> order = Prefs.widgetOrder(this);
        int a = -1, b = -1;
        for (int i = 0; i < 3; i++) {
            if (allWidgets[i] == w) a = i;
            if (allWidgets[i] == widgets.get(m)) b = i;
        }
        int pa = order.indexOf(a), pb = order.indexOf(b);
        order.set(pa, b);
        order.set(pb, a);
        Prefs.setWidgetOrder(this, order);
        layoutWidgets(true);
        focusWidget(m);
    }

    private void closeMenu(View back) {
        if (!menu.isOpen()) return;
        menu.hide();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (back == null || !back.isShown() || !back.requestFocus()) focusGrid(gridAt);
    }

    // ---- folders -----------------------------------------------------------------------------

    private FolderPanel folderPanel;

    /** A folder's apps that are still installed and not hidden, in the folder's order. */
    private List<AppRepo.App> folderApps(Folders.Folder f) {
        List<AppRepo.App> out = new ArrayList<>();
        Set<String> hidden = Prefs.hidden(this);
        for (String pkg : f.apps) {
            AppRepo.App a = find(pkg);
            if (a != null && !hidden.contains(pkg)) out.add(a);
        }
        return out;
    }

    /** Which folder the app joins: one of yours, or a new one. */
    private void chooseFolder(String pkg, String label, Card card) {
        List<AppMenu.Item> items = new ArrayList<>();
        for (Folders.Folder f : Folders.all(this).values()) {
            items.add(new AppMenu.Item(R.drawable.ic_apps, f.name, () -> {
                closeMenu(card);
                Folders.add(this, f.id, pkg);
                populate();
                focusTag(f.tag());
                hud(R.drawable.ic_check, label + " is in " + f.name);
            }));
        }
        items.add(new AppMenu.Item(R.drawable.ic_add, "New folder", () -> {
            closeMenu(card);
            Folders.Folder f = Folders.create(this, pkg);
            // the new folder takes the app's place in the grid
            List<String> order = AppRepo.order(prefs);
            int at = order.indexOf(pkg);
            if (at >= 0) order.set(at, f.tag());
            else order.add(f.tag());
            AppRepo.saveDock(prefs, order);
            populate();
            focusTag(f.tag());
            // named straight away, the suggestion already typed in: OK keeps it, or type your own
            nameFolder(f.id, true);
        }));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menu.show(card, "ADD " + label + " TO", items);
    }

    private void openFolder(String id) {
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        Folders.Folder f = Folders.all(this).get(id);
        if (f == null) return;
        stopPreview();
        hideShelf();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        folderPanel.open(f, folderApps(f));
        handler.postDelayed(() -> {
            if (folderPanel.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 300);
    }

    private void closeFolder() {
        if (!folderPanel.isOpen()) return;
        String tag = "folder:" + folderPanel.openId;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        folderPanel.close();
        stack.hold(false);
        focusTag(tag);
    }

    private Find findPanel;
    private View beforeFind;

    private void openFind(String typed) {
        if (findPanel.isOpen() || ccOpen || settings.isOpen() || welcome != null) return;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        hideTip();
        beforeFind = getCurrentFocus();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        findPanel.open(typed);
        handler.postDelayed(() -> {
            if (findPanel.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 300);
    }

    private void closeFind() {
        if (!findPanel.isOpen()) return;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        findPanel.close();
        stack.hold(false);
        if (beforeFind == null || !beforeFind.isShown() || !beforeFind.requestFocus()) focusGrid(gridAt);
        wake();
    }

    private Ask ask;
    /** The tile focus returns to once the name card closes. */
    private String askTag;

    /** A folder's name, typed with the TV's keyboard or picked from the suggestions. */
    private void nameFolder(String id, boolean fresh) {
        Folders.Folder f = Folders.all(this).get(id);
        if (f == null) return;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        askTag = f.tag();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        List<String> names = Folders.suggestions(this);
        names.remove(f.name);
        ask.show(fresh ? "NAME YOUR NEW FOLDER" : "RENAME " + f.name.toUpperCase(Locale.getDefault()), f.name,
                "Type a name, then OK", names, name -> {
                    Folders.rename(this, id, name);
                    afterAsk();
                    hud(R.drawable.ic_check, fresh ? "New folder: " + name : "Renamed to " + name);
                });
    }

    /** Every small overlay closed: the hold-OK menu, a folder, Now Playing, finding an app, the name or PIN card. */
    private void closeOverlays() {
        if (menu.isOpen()) closeMenu(null);
        if (folderPanel.isOpen()) closeFolder();
        if (nowPlaying.isOpen()) closeNowPlaying();
        if (weatherSheet.isOpen()) closeWeather();
        if (findPanel.isOpen()) closeFind();
        if (ask.isOpen()) closeAsk();
    }

    /** Back on the name card: the name stays as it was. */
    private void closeAsk() {
        ask.cancel();
        if (pinFrom != null) {
            // the PIN card, put away: back to where it was asked from (Control Center's Settings, say)
            View before = pinFrom;
            pinFrom = null;
            unblockHome();
            if (before.isShown() && before.requestFocus()) return;
            if (!ccOpen) focusGrid(gridAt);
            return;
        }
        afterAsk();
    }

    /** What had focus when the PIN was asked for; null while no PIN is being asked. */
    private View pinFrom;

    /** The home screen takes focus again, unless something still covers it. */
    private void unblockHome() {
        if (!ccOpen && !settings.isOpen() && !switcher.isOpen()) home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void afterAsk() {
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        populate();
        if (askTag != null) focusTag(askTag);
        askTag = null;
        wake();
    }

    /** Focus the grid tile with this tag, if it's there. */
    private void focusTag(String tag) {
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).tag.equals(tag)) focusGrid(i);
    }

    private boolean menuKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            closeMenu(cells.isEmpty() ? null : cells.get(Math.min(gridAt, cells.size() - 1)).card);
            return swallow(code);
        }
        // the opening press repeating, or a sideways step out of the list: nothing to do
        if (Pad.isConfirm(code) && e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        return super.dispatchKeyEvent(e);
    }

    /** Apps that came with the TV can't be uninstalled, only hidden. */
    private boolean removable(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- rearranging -------------------------------------------------------------------------

    /** Move, from an app's menu: it wiggles, the arrows carry it through the grid and the others make room. */
    private void startEdit() {
        if (editing || ccOpen || zone != GRID || cells.isEmpty()) return;
        Cell c = cells.get(gridAt);
        if (c.tag.startsWith("input:")) return;
        editing = true;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        final Card card = c.card;
        wiggle = ValueAnimator.ofFloat(-1f, 1f);
        wiggle.setDuration(150);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.addUpdateListener(a -> card.setRotation(1.4f * (float) a.getAnimatedValue()));
        wiggle.start();
        showHud(R.drawable.ic_move, "Move " + c.label.getText() + " with the arrows  ·  OK when done", 0);
    }

    private void stopEdit() {
        if (!editing) return;
        editing = false;
        if (wiggle != null) wiggle.cancel();
        for (Cell c : cells) c.card.setRotation(0f);
        List<String> order = new ArrayList<>();
        for (Cell c : cells) if (!c.tag.startsWith("input:")) order.add(c.tag);
        AppRepo.saveDock(prefs, order);
        hud(R.drawable.ic_check, "Your apps are arranged");
        wake();
    }

    private void moveEditing(int d) {
        int i = gridAt, j = i + d;
        // live inputs always open the grid
        if (j < live.size() || j >= cells.size()) return;
        cells.add(j, cells.remove(i));
        boolean cross = paged && pageOf(j) != gridPage;
        for (int k = Math.min(i, j); k <= Math.max(i, j); k++) {
            cells.get(k).setNameMode(Prefs.nameMode(this), dock && rowOf(k) == 0);
            if (cross) {
                cells.get(k).animate().cancel();
                cells.get(k).setTranslationY(cellY(k));
            } else cells.get(k).animate().translationX(cellX(k)).translationY(cellY(k)).setDuration(k == j ? 420 : 360)
                    .setStartDelay(0).setInterpolator(k == j ? Ui.SPRING : Ui.EMPHASIZED).start();
        }
        gridAt = j;
        reveal(j);
        glideToCell();
    }

    // ---- Live Inputs -------------------------------------------------------------------------

    /** HDMI sources that are actually switched on (not just plugged in and asleep). */
    private List<String[]> liveInputs() {
        List<String[]> out = new ArrayList<>();
        if (tim == null) return out;
        try {
            readInputs(out);
        } catch (RuntimeException e) {
            // the TV's input service not ready yet (just after a restart) or missing on this box:
            // no input tiles this time, rather than a home screen that stops as the TV starts
            out.clear();
        }
        return out;
    }

    private void readInputs(List<String[]> out) {
        for (TvInputInfo info : tim.getTvInputList()) {
            // digital sources only: composite (AV) ports report "connected" even with nothing playing
            int type = info.getType();
            boolean digital = type == TvInputInfo.TYPE_HDMI || type == TvInputInfo.TYPE_DVI
                    || type == TvInputInfo.TYPE_DISPLAY_PORT || type == TvInputInfo.TYPE_COMPONENT;
            if (!digital || Build.VERSION.SDK_INT >= 24 && info.isHidden(this)) continue;
            // hidden from home (hold OK on its tile); Settings, Hidden apps brings it back
            if (Prefs.hidden(this).contains("input:" + info.getId())) continue;
            if (tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = Build.VERSION.SDK_INT >= 24 ? info.loadCustomLabel(this) : null;
            String label = custom != null && custom.length() > 0 ? String.valueOf(custom)
                    : Cec.name(this, String.valueOf(info.loadLabel(this)).replaceAll("(?i)^hdmi\\s*(\\d)", "HDMI $1"));
            out.add(new String[]{info.getId(), label});
        }
    }

    /** A set-top box or console just woke up: it takes the first tile and says so. */
    private void refreshLive(boolean announce) {
        List<String[]> now = liveInputs();
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (String[] x : live) a.append(x[0]).append('|');
        for (String[] x : now) b.append(x[0]).append('|');
        if (a.toString().equals(b.toString())) return;
        String fresh = null;
        for (String[] x : now) if (a.indexOf(x[0] + "|") < 0) fresh = x[1];
        live.clear();
        live.addAll(now);
        if (apps.isEmpty() || editing) return;
        populate();
        if (announce && fresh != null) {
            hud(R.drawable.ic_hdmi, fresh + " is on  ·  it's your first tile");
            if (!ccOpen && zone == GRID) focusGrid(0);
        }
    }

    // ---- Control Center ----------------------------------------------------------------------

    private void openCc(boolean inputs) {
        if (ccOpen) return;
        if (editing) stopEdit();
        stopPreview();
        beforeCc = getCurrentFocus();
        hideTip();
        ccOpen = true;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        // the frost settles over a still home screen (scaling it would re-rasterise all its text
        // every frame); once the frost is opaque the home screen stops drawing altogether
        handler.postDelayed(hideHome, ControlCenter.FROST_MS + 30);
        bloom.setStrength(0.3f * glow(), 460);
        cc.open(inputs);
    }

    /** The app Control Center was opened over (Home held inside it), and its last picture beneath. */
    private String ccOver;
    private ImageView ccShot;

    /** Control Center over an app: the frost settles over the app's picture, never the home screen. */
    private void openCcOver(String pkg) {
        if (settings.isOpen()) closeSettings();
        if (menu.isOpen()) closeMenu(null);
        for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        android.graphics.Bitmap shot = Previews.fresh(pkg);
        if (shot != null) {
            if (ccShot == null) {
                ccShot = new ImageView(this);
                ccShot.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(ccShot, root.indexOfChild(cc.panel), match());
            }
            ccShot.setImageBitmap(shot);
            ccShot.setVisibility(View.VISIBLE);
        }
        openCc(false);
        ccOver = pkg;
    }

    /** Back from Control Center opened over an app: the app again, the home screen never showing. */
    private void backToCcApp() {
        String over = ccOver;
        ccOver = null;
        Intent again = getPackageManager().getLeanbackLaunchIntentForPackage(over);
        if (again == null) again = getPackageManager().getLaunchIntentForPackage(over);
        if (again != null) {
            try {
                startActivity(again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                overridePendingTransition(0, 0);
                // the home screen is put back once the app covers it
                handler.postDelayed(() -> closeCc(false), 400);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        closeCc(true);
    }

    @Override
    public void closeCc(boolean animate) {
        if (!ccOpen) return;
        ccOpen = false;
        ccOver = null;
        if (ccShot != null) {
            ccShot.setImageDrawable(null);
            ccShot.setVisibility(View.GONE);
        }
        handler.removeCallbacks(hideHome);
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cc.close(animate, null);
        bloom.setStrength(glow(), animate ? 560 : 0);
        if (beforeCc == null || !beforeCc.isShown() || !beforeCc.requestFocus()) focusGrid(gridAt);
        beforeCc = null;
        wake();
    }

    @Override
    public void recents() {
        closeCc(false);
        openSwitcher();
    }

    @Override
    public void nowPlaying() {
        closeCc(false);
        // the tile asks for the music back too, if it was minimised
        minimisedPkg = null;
        openNowPlaying();
        updateListening();
    }

    @Override
    public void photoMode() {
        handler.postDelayed(() -> setPhoto(true), 450);
    }

    @Override
    public Backdrop backdrop() {
        return backdrop;
    }

    @Override
    public void open(Intent intent, View from) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(intent, from, "that");
    }

    // ---- clock, status, caption, hud ---------------------------------------------------------

    private void tick() {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(this);
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        stTime.setText(h24 ? t : t + (now.get(Calendar.AM_PM) == Calendar.AM ? " AM" : " PM"));
        int hour = now.get(Calendar.HOUR_OF_DAY);
        hello.setText(hour >= 5 && hour < 12 ? "Good morning" : hour >= 12 && hour < 17 ? "Good afternoon" : "Good evening");
        dateLine.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.getTime()));
        cc.tick(now);
        story.tick(now);
        refreshStatus();
        // the sky and your day every minute; habits only when the hour turns
        refreshWidgets(lastHour != -1 && hour != lastHour);
        watchSky();
        showWeather();
        if (resumed && Prefs.on(this, Prefs.WEATHER)) {
            final Context app = getApplicationContext();
            worker.execute(() -> {
                if (Weather.refresh(app)) runOnUiThread(this::showWeather);
            });
        }
        lastHour = hour;
    }

    // ---- settings ----------------------------------------------------------------------------

    private SettingsPanel settings;
    private View beforeSettings;

    /** Until when the PIN just typed keeps a kids' profile unlocked. */
    private long unlockedUntil;

    /** {@code then}, after the parents' PIN when this is a kids' profile (one PIN lasts ten minutes). */
    @Override
    public void guarded(Runnable then) {
        if (!Profiles.kids(this) || !Profiles.hasPin(this) || SystemClock.uptimeMillis() < unlockedUntil) {
            then.run();
            return;
        }
        askTag = null;
        pinFrom = getCurrentFocus();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ask.showPin("FOR GROWN-UPS", pin -> {
            View before = pinFrom;
            pinFrom = null;
            unblockHome();
            if (!Profiles.pinOk(this, pin)) {
                hud(R.drawable.ic_info, "That's not the PIN");
                if (before != null && before.isShown()) before.requestFocus();
                return;
            }
            unlockedUntil = SystemClock.uptimeMillis() + 10 * 60_000L;
            then.run();
        });
    }

    private void openSettings() {
        openSettings(null);
    }

    /** Settings, on {@code page} when given (Back from it leads up through Settings as usual). */
    private void openSettings(String page) {
        if (!Profiles.kids(this) || SystemClock.uptimeMillis() < unlockedUntil || !Profiles.hasPin(this)) {
            openSettingsNow(page);
            return;
        }
        guarded(() -> openSettingsNow(page));
    }

    private void openSettingsNow(String page) {
        if (settings.isOpen() || ccOpen) return;
        if (editing) stopEdit();
        stopPreview();
        hideTip();
        beforeSettings = getCurrentFocus();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        if (page != null) settings.open(page);
        else settings.open();
        // as with Control Center, nothing under the frost keeps drawing
        handler.postDelayed(() -> {
            if (settings.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 300);
    }

    private void closeSettings() {
        if (!settings.isOpen()) return;
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        settings.close();
        stack.hold(false);
        if (beforeSettings == null || !beforeSettings.isShown() || !beforeSettings.requestFocus()) focusGrid(gridAt);
        wake();
    }

    /** A setting just changed: put it into effect straight away. */
    private void applySetting(String what) {
        switch (what) {
            case "tiles":
                switcher.forget();
                // falls through: the grid is rebuilt too
            case "inputs":
                // an input shown again (or its live picture switched): the switched-on list read afresh
                stopPreview();
                live.clear();
                live.addAll(liveInputs());
                // falls through: the grid is rebuilt
            case "names":
            case "apps":
                populate();
                // populate() refocuses the grid; the settings list keeps focus while it's open
                home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                break;
            case "stack":
                if (Prefs.on(this, Prefs.STACK_AUTO) && fyW.getVisibility() == View.VISIBLE) stack.start();
                else stack.stop();
                break;
            case "widgets":
                TopShelf.clearCache();
                hideShelf();
                layoutWidgets(true);
                for (Widget w : widgets) w.setAlpha(dashboard ? 1f : 0f);
                if (!widgets.isEmpty()) refreshWidgets(true);
                else if (Prefs.on(this, Prefs.TOP_SHELF) && zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) shelfFor(gridAt);
                break;
            case "layout":
                beforeSettings = null;
                switcher.forget();
                gridAt = 0;
                applyLayout();
                scrollTo(0);
                populate();
                // widgets back: the stack is dealt again, now that its pages have a size to draw at
                if (!widgets.isEmpty()) refreshWidgets(true);
                if (settings.isOpen()) home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                break;
            case "sources":
                // photographs from the sources now on, and those switched off leave, on a run in a moment
                Daily.soon(this);
                break;
            case "cards":
                // the Smart Stack's choice of cards changed: deal them again
                refreshWidgets(true);
                break;
            case "update":
                updateDot.setVisibility(Updater.unseen(this) ? View.VISIBLE : View.GONE);
                break;
            case "profile":
                // another person's home screen: rebuilt from their own settings
                prefs = Prefs.of(this);
                unlockedUntil = 0;
                beforeSettings = null;
                switcher.forget();
                stopPreview();
                live.clear();
                live.addAll(liveInputs());
                if (!Prefs.on(this, Prefs.TOP_SHELF)) hideShelf();
                applyLight();
                applyLayout();
                gridAt = 0;
                scrollTo(0);
                populate();
                if (settings.isOpen()) {
                    if (Profiles.kids(this) && Profiles.hasPin(this)) closeSettings();
                    else {
                        settings.refresh();
                        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    }
                }
                backdrop.reload();
                cc.picksChanged();
                showWeather();
                // their own Smart Stack (what they watch, what they turned down) and Top Shelf, not
                // the last person's until the next press of Home
                TopShelf.clearCache();
                hideShelf();
                if (!widgets.isEmpty()) refreshWidgets(true);
                // the music card read again for this person: a kids' profile doesn't see what it hides
                minimisedPkg = null;
                updateListening();
                // and their own recent apps, read afresh (a kids' profile without what it hides)
                recentCache = null;
                recentAt = 0;
                openWhenWarm = false;
                if (!apps.isEmpty()) warmRecents(false);
                break;
            case "shelf":
                TopShelf.clearCache();
                hideShelf();
                if (Prefs.on(this, Prefs.TOP_SHELF) && zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) shelfFor(gridAt);
                break;
            case "parallax":
            case "light":
                applyLight();
                // tiles sway or stay still with it
                populate();
                home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                break;
            case "art":
                // a tile's own picture came or went: the list read again, the grid repainted
                reloadApps(true);
                break;
            case "photos":
            case "daily":
                // pure black, your own photos only, Picture of the Day, or one removed: the wall moves on to the new set
                backdrop.reload();
                break;
            case "picks":
                // a new taste (or Tonight's pick switched on or off): the Top Shelf, stack and Control Center follow
                TopShelf.clearCache();
                hideShelf();
                if (Prefs.on(this, Prefs.TOP_SHELF) && zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) shelfFor(gridAt);
                cc.picksChanged();
                refreshWidgets(true);
                break;
            case "weather":
                showWeather();
                worker.execute(() -> {
                    if (Weather.refresh(getApplicationContext())) runOnUiThread(() -> {
                        showWeather();
                        refreshWidgets(false);
                    });
                });
                break;
            default:
                break;
        }
    }

    /** Light mode (see {@link Perf}): stillness wherever movement costs frames. */
    private void applyLight() {
        boolean light = Perf.light(this);
        Card.parallax = !light && Prefs.on(this, Prefs.PARALLAX);
        Backdrop.calm = light;
        if (bloom != null) bloom.setVisibility(light ? View.GONE : View.VISIBLE);
    }

    private List<AppRepo.App> hiddenApps() {
        List<AppRepo.App> out = new ArrayList<>();
        Set<String> hidden = Prefs.hidden(this);
        for (AppRepo.App a : apps) if (hidden.contains(a.pkg)) out.add(a);
        return out;
    }

    private void showWeather() {
        Weather.Now w = Prefs.on(this, Prefs.WEATHER) ? Weather.now(this) : null;
        if (w == null) {
            stWx.setVisibility(View.GONE);
            stTemp.setVisibility(View.GONE);
            skyWx.setVisibility(View.GONE);
            skyTemp.setVisibility(View.GONE);
            story.weather(null);
            return;
        }
        stWx.setImageResource(w.icon());
        stTemp.setText(w.temp + "°");
        stWx.setVisibility(View.VISIBLE);
        stTemp.setVisibility(View.VISIBLE);
        skyWx.setImageResource(w.icon());
        skyTemp.setText(w.temp + "°");
        skyWx.setVisibility(View.VISIBLE);
        skyTemp.setVisibility(View.VISIBLE);
        if (weatherSheet.isOpen()) weatherSheet.show(true);
        story.weather(w);
    }

    private void refreshStatus() {
        ConnectivityManager cm = Compat.svc(this, ConnectivityManager.class);
        stWifi.setImageAlpha(Compat.online(cm) ? 255 : 80);
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        stBt.setImageAlpha(ba != null && ba.isEnabled() ? 255 : 80);
        stVpn.setVisibility(Vpn.on(this) ? View.VISIBLE : View.GONE);
    }

    /** A VPN coming up or going down: the status bar (and Control Center, if open) follow at once. */
    private android.net.ConnectivityManager.NetworkCallback vpnWatch;

    private void watchVpn(boolean on) {
        ConnectivityManager cm = Compat.svc(this, ConnectivityManager.class);
        if (cm == null) return;
        try {
            if (on && vpnWatch == null) {
                vpnWatch = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(android.net.Network n) {
                        changed();
                    }

                    @Override
                    public void onLost(android.net.Network n) {
                        changed();
                    }

                    private void changed() {
                        handler.post(() -> {
                            if (isDestroyed()) return;
                            refreshStatus();
                            if (ccOpen) cc.refresh();
                        });
                    }
                };
                cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                        .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpnWatch);
            } else if (!on && vpnWatch != null) {
                cm.unregisterNetworkCallback(vpnWatch);
                vpnWatch = null;
            }
        } catch (RuntimeException e) {
            vpnWatch = null;
        }
    }

    @Override
    public void onWallpaperChanged(Backdrop.Wallpaper w, float lumaTop, float lumaBottom, boolean first) {
        scrim.adapt(lumaTop, lumaBottom, first ? 0 : 2800);
        // in photo mode the story turns over with the photograph; otherwise it's simply kept current
        story.set(w, photo);
        if (first && !wallReady) {
            wallReady = true;
            wallAt = SystemClock.uptimeMillis();
            progressSplash();
        }
    }

    @Override
    public void hud(int icon, String text) {
        showHud(icon, text, 2600);
    }

    private void showHud(int icon, String text, long ms) {
        hudIcon.setImageResource(icon);
        hudText.setText(text);
        // with Settings open the list fills the top right: speak under the title instead
        FrameLayout.LayoutParams hl = (FrameLayout.LayoutParams) hud.getLayoutParams();
        boolean side = settings != null && settings.isOpen();
        int g = side ? Gravity.BOTTOM | Gravity.START : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (hl.gravity != g) {
            hl.gravity = g;
            hl.leftMargin = side ? Ui.dp(106) : 0;
            hl.topMargin = side ? 0 : Ui.dp(28);
            hl.bottomMargin = side ? Ui.dp(96) : 0;
            hud.setLayoutParams(hl);
        }
        handler.removeCallbacks(hideHud);
        if (hud.getVisibility() != View.VISIBLE || hud.getAlpha() < 0.5f) {
            hud.setVisibility(View.VISIBLE);
            hud.setTranslationY(-Ui.dp(12));
            hud.setScaleX(0.94f);
            hud.setScaleY(0.94f);
        }
        hud.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(520)
                .setStartDelay(0).setUpdateListener(a -> hud.invalidate()).start();
        if (ms > 0) handler.postDelayed(hideHud, ms);
    }

    private void hideHud() {
        hud.animate().alpha(0f).translationY(-Ui.dp(8)).setInterpolator(Ui.SOFT).setDuration(340).setStartDelay(0)
                .setUpdateListener(a -> hud.invalidate()).withEndAction(() -> hud.setVisibility(View.INVISIBLE)).start();
    }

    // ---- boot screen and intro ---------------------------------------------------------------

    /** Moves the boot bar on real milestones: photograph decoded, apps and artwork built, first frame drawn. */
    private void progressSplash() {
        if (splash == null) return;
        if (!wallReady && !appsReady) {
            splash.setProgress(0.2f, "WAKING UP");
        } else if (!appsReady) {
            splash.setProgress(0.48f, "ARRANGING YOUR APPS");
        } else if (!wallReady) {
            splash.setProgress(0.52f, "DEVELOPING THE PHOTOGRAPHS");
        } else {
            splash.setProgress(0.8f, "POLISHING THE GLASS");
            // after the next layout every tile has its artwork; only then reveal
            page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    page.getViewTreeObserver().removeOnPreDrawListener(this);
                    long held = SystemClock.uptimeMillis() - splashAt;
                    // a moment for the sun to be seen rising, no more: ready means home
                    handler.postDelayed(HomeActivity.this::finishSplash, Math.max(0, (splash != null && splash.quick ? 0 : 600) - held));
                    return true;
                }
            });
            page.invalidate();
        }
    }

    private void finishSplash() {
        if (splash == null || splashFinishing) return;
        splashFinishing = true;
        final Splash s = splash;
        s.finish("WELCOME HOME", this::startIntro, () -> splashGone(s));
        // its landing animation needs frames; should none come (Aura started under an app, a busy
        // TV), the loading screen still goes, so it can never hold the remote
        handler.postDelayed(dropSplash, 4000);
    }

    private final Runnable dropSplash = () -> {
        if (splash != null) splashGone(splash);
    };

    private void splashGone(Splash s) {
        handler.removeCallbacks(dropSplash);
        if (splash != s) return;
        root.removeView(s);
        splash = null;
        long now = SystemClock.uptimeMillis();
        homeAt = now;
        Trouble.started(this, now - createdAt, appsAt > 0 ? appsAt - createdAt : -1, appsLoadMs, appsCount,
                wallAt > 0 ? wallAt - createdAt : -1);
        // the landing never played (no frames came): the home screen its intro would fade in, shown now
        if ((dock ? dockGlass : status).getAlpha() == 0f) {
            for (View v : new View[]{sidebar, status, greeting, skyW, fyW, tdW, gridLabel, topCc, topFind, topSettings, dockGlass, tvosHint}) if (v != null) v.setAlpha(1f);
            if (dock) {
                sideShown(false, false);
                topShown(false, false);
            }
            bloom.setStrength(glow(), 0);
        }
        if (Welcome.due(this)) openWelcome();
        else {
            wake();
            // started from Android's screensaver settings while Aura wasn't running
            if (fromSaverSettings(getIntent())) openSaverSettings();
        }
    }

    // ---- first run ---------------------------------------------------------------------------

    private Welcome welcome;

    /** The one-time welcome over the frosted wall; built now, dropped when done. */
    private void openWelcome() {
        if (welcome != null || isDestroyed()) return;
        if (settings.isOpen()) closeSettings();
        if (switcher.isOpen()) closeSwitcher();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        hideTip();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        welcome = new Welcome(this, backdrop, new Welcome.Host() {
            @Override
            public List<AppRepo.App> sample() {
                List<AppRepo.App> out = new ArrayList<>();
                // apps nearly everyone knows show the looks best, then whatever is first on this TV
                for (String pkg : new String[]{"com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar"}) {
                    AppRepo.App a = find(pkg);
                    if (a != null) out.add(a);
                }
                for (String tag : AppRepo.order(prefs)) {
                    AppRepo.App a = find(tag);
                    if (a != null && out.size() < 4 && !out.contains(a)) out.add(a);
                }
                for (AppRepo.App a : apps) if (out.size() < 4 && !out.contains(a)) out.add(a);
                return out;
            }

            @Override
            public void applied(String what) {
                applySetting(what);
            }

            @Override
            public void done() {
                root.removeView(welcome);
                welcome = null;
                for (View v : behindCc()) v.setVisibility(View.VISIBLE);
                home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                stack.hold(false);
                focusGrid(0);
                wake();
            }

            @Override
            public void homeHint(String text) {
                HomeActivity.this.homeHint(text);
            }
        });
        root.addView(welcome, match());
        welcome.open();
        handler.postDelayed(() -> {
            if (welcome != null && welcome.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 800);
    }

    /** Keeps the interface invisible under the boot screen so nothing pops as it dissolves. */
    private void hideForIntro() {
        for (View v : new View[]{sidebar, status, greeting, skyW, fyW, tdW, gridLabel, topCc, topFind, topSettings, dockGlass, tvosHint, listenW}) if (v != null) v.setAlpha(0f);
        bloom.setStrength(0f, 0);
    }

    private void startIntro() {
        backdrop.restartIntro();
        playIntro();
        if (!cells.isEmpty()) cascade();
    }

    /**
     * Just after the TV started, or in Light mode: the home screen arrives in one soft fade instead
     * of a choreography of a dozen moving parts, while Android is still busy starting everything else.
     */
    private boolean quietIntro() {
        return Compat.justBooted() || Perf.light(this);
    }

    /** The photograph arrives sharp, then softens as the glass slides in around it. */
    private void playIntro() {
        introUntil = SystemClock.uptimeMillis() + (quietIntro() ? 1300 : 2300);
        // cards left up from before (the TV slept on them) go, and come back after the tiles do
        if (shelf.shownFor() != null) {
            hideShelf();
            shelf.clearShown();
            if (zone == GRID && !cells.isEmpty()) shelfFor(gridAt);
        }
        if (quietIntro()) {
            for (View v : new View[]{greeting, gridLabel, dockGlass, tvosHint}) if (v != null) enter(v, 0, 0, 500, 600);
            if (!dock) {
                enter(sidebar, 0, 0, 500, 600);
                enter(status, 0, 0, 500, 600);
            }
            for (Widget w : widgets) enter(w, 0, 0, 500, 600);
            backdrop.setDefocus(HOME_DEFOCUS, 700);
            bloom.setStrength(glow(), 700);
            return;
        }
        if (!dock) {
            enter(sidebar, -Ui.dp(34), 0, 800, 1000);
            enter(status, 0, -Ui.dp(12), 950, 900);
        } else {
            enter(dockGlass, 0, Ui.dp(18), 1000, 900);
            if (tvosHint != null) enter(tvosHint, 0, -Ui.dp(8), 980, 900);
        }
        enter(greeting, 0, Ui.dp(18), 1000, 1000);
        for (int i = 0; i < widgets.size(); i++) enter(widgets.get(i), 0, Ui.dp(26), 1120 + i * 90L, 1000);
        enter(gridLabel, 0, 0, 1450, 800);
        handler.postDelayed(() -> {
            backdrop.setDefocus(HOME_DEFOCUS, 1500);
            bloom.setStrength(glow(), 1500);
        }, 1100);
        bloom.setStrength(0f, 0);
    }

    private static void enter(View v, float dx, float dy, long delay, long dur) {
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(delay).setDuration(dur)
                .setInterpolator(Ui.EMPHASIZED).start();
    }

    /** The tiles rise into place one after another, row by row. */
    private void cascade() {
        introPending = false;
        float screen = root.getHeight() > 0 ? root.getHeight() : Ui.dp(540);
        boolean quiet = quietIntro();
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            // tiles below the screen (or all of them, after a restart) are simply there: animating
            // what can't be seen, or two dozen glass tiles on a busy TV, only costs frames
            if (quiet || cellY(i) > screen) {
                c.animate().cancel();
                c.setAlpha(1f);
                c.setScaleX(1f);
                c.setScaleY(1f);
                if (quiet) {
                    c.setAlpha(0f);
                    c.animate().alpha(1f).setStartDelay(500).setDuration(600).setInterpolator(Ui.SOFT).start();
                }
                continue;
            }
            c.setAlpha(0f);
            c.setScaleX(0.9f);
            c.setScaleY(0.9f);
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(1350 + Math.min(i, 14) * 40L).setDuration(720)
                    .setInterpolator(Ui.EMPHASIZED).start();
        }
    }

    // ---- misc --------------------------------------------------------------------------------

    private void voiceSearch() {
        Intent[] tries = {
                new Intent("android.search.action.GLOBAL_SEARCH"),
                new Intent(Intent.ACTION_ASSIST),
                new Intent(RecognizerIntent.ACTION_WEB_SEARCH)};
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) == null) continue;
            try {
                startActivity(i);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        hud(R.drawable.ic_info, "Voice search isn't available");
    }

    private LinearLayout column(int l, int t, int r, int b) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(l), Ui.dp(t), Ui.dp(r), Ui.dp(b));
        return c;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }

    /**
     * A container whose fades go straight onto its children's draws. A plain FrameLayout fading over
     * the photograph is first painted whole into an offscreen layer, every frame of the fade.
     */
    static FrameLayout flat(Context c) {
        return new FrameLayout(c) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(MATCH, MATCH);
    }

    private static FrameLayout.LayoutParams frame(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    /** A slim rounded bar: how much memory is free. */
    static final class Meter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;

        Meter(Context c) {
            super(c);
        }

        void set(float v) {
            value = Ui.clamp(v, 0f, 1f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f;
            p.setColor(0x2EFFFFFF);
            c.drawRoundRect(0, 0, getWidth(), h, r, r, p);
            p.setColor(0xE6FFFFFF);
            c.drawRoundRect(0, 0, Math.max(h, getWidth() * value), h, r, r, p);
        }
    }
}
