package app.aura.home;

import android.animation.TimeInterpolator;
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
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
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
    /** LOW's shared baked shadow under the focused tile. */
    private FocusLayer focusLayer;
    /** The app grid: columns, then the tile and row measures that follow from them (see {@link #sizeGrid}). */
    private int COLS = 5, TILE_W = 148, TILE_H = 84, ROW_H = 122;
    private static final int PAGE_X = 104, WIDGET_Y = 98, WIDGET_H = 142, GRID_Y = 310, FOCUS_LINE = 210;
    /** Each widget's natural width; the ones you keep share the row in these proportions. */
    private static final int[] WIDGET_W = {260, 320, 200};
    private static final int WIDGET_ROW = 816, WIDGET_GAP = 18;
    static final String[] WIDGET_IDS = {"sky", "stack", "today"};
    static final int[] WIDGET_NAMES = {R.string.widget_sky, R.string.smart_stack, R.string.today};
    private static final int SIDE = 0, WIDGETS = 1, GRID = 2, SHELF = 3, TOP = 4, TABS = 5;
    private static final int SIDE_GLOW = 0xFF9FB2D6, DAY_GLOW = 0xFF7FB2FF;
    /** The lengths of Spring.row() and Spring.smooth() (ms): a move whose distance floor (Ui.scrollFloorMs) is longer gets a slower spring. */
    private static final long ROW_MS = 350, SMOOTH_MS = 450;
    /** How far Spring.row() trails a target moving at a steady speed (2ζ/ω, in ms of that travel). */
    private static final float ROW_TRAIL_MS = 95f;
    private static List<AppRepo.App> cache;
    /** False until the first launch of this process has shown the boot screen. */
    private static boolean warm;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Compat.background("home", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    /** Recent apps read on their own thread: never queued behind the widgets' work or an app being stopped. */
    private final ExecutorService recentWorker = Compat.pool("recents", 1);
    /** Top Shelf's cards, found as focus settles on an app: never queued behind the rest of home's work. */
    private final ExecutorService shelfWorker = Compat.background("shelf", android.os.Process.THREAD_PRIORITY_DEFAULT);
    /** The tiles' pictures, painted one at a time, nearest first: just below the screen's priority, as the logos are. */
    private final ExecutorService tilePainter = Compat.background("tiles", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    private final int[] loc = new int[2];

    private SharedPreferences prefs;
    private FrameLayout root, home, page;
    private Backdrop backdrop;
    private Bloom bloom;
    private Scrim scrim;
    private Splash splash;
    private GlassFrame sidebar, status, hud;
    private View stickyTopScrim, bottomFeather;
    private FrameLayout homeTabBar;
    private TextView stProfile;
    private GradientDrawable stProfileBg;
    private View stProfileRule;
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
    private WatchScreen watch;
    private WhatsNew news;
    /** Who's watching: the remote's profile button, and Settings' Profile row. */
    private ProfilePicker who;
    private View beforeWho;
    private ImageView skyWx;
    private TextView skyTemp;
    private SkyArc skyArc;
    private Meter memory;
    private LinearLayout greeting;
    private Story story;
    private TextView hello, dateLine, gridLabel, stTime, hudText;
    private TextView skyKicker, skyTitle, skyRise, skySet, tdValue, tdSub, tdMem, tdData;
    private LinearLayout tdDataRow;
    private ImageView stWifi, stBt, stVpn, stWx, hudIcon;
    private TextView stTemp;
    private SmartStack stack;
    private ControlCenter cc;

    private final List<Pad> side = new ArrayList<>();
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
    private ValueAnimator wiggle;
    private boolean ccOpen, racked, photo, resumed, introPending, editing, held, dashboard = true, greetingShown = true;
    private boolean wallReady, appsReady, splashFinishing;
    /** An app changed while home was behind another (light mode): the list is read again on return. */
    private boolean appsDirty;
    /** The stack's cards on show since home was last opened, for {@link Taste}. */
    private final Set<String> shownThisVisit = new HashSet<>();
    /** A start's milestones, for the problem report: when Aura began, its apps and its photograph were ready. */
    private long createdAt, appsAt, wallAt, appsLoadMs;
    /** The last key pressed, and when home was first on show (the boot screen gone). */
    private long keyAt, homeAt;
    /** The last key pressed, for what waits outside home for the remote to rest: Warm (on its own thread), Trouble. */
    static volatile long lastKeyAt;
    private int appsCount;
    private long splashAt, lastStep;
    /** A held key's last repeat (uptime), and how far ahead of focus its row aims meanwhile (columns). */
    private long lastRepeat;
    private float holdLead;
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
        // Light mode keeps the bloom gone (applyLight): a panel closing mustn't bring a full-screen layer back
        return Perf.light(this) ? new View[]{home, backdrop, scrim} : new View[]{home, backdrop, bloom, scrim};
    }
    /** Sheens across the tile in focus since it took focus: two at most, so a home left alone stops drawing them. */
    private int glints;
    private final Runnable glintTick = new Runnable() {
        @Override
        public void run() {
            View f = getWindow().getCurrentFocus();
            // not on the switcher's cards: a sheen across one that size repaints most of the screen for a second
            if (glints < 2 && !held && !photo && !ccOpen && !editing && !Backdrop.calm && !switcher.isOpen() && f instanceof Card) {
                ((Card) f).glint();
                glints++;
            }
            handler.postDelayed(this, 6500);
        }
    };
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(i.getAction())) Sun.timeZoneChanged();
            tick();
        }
    };
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Power.onScreenOff(HomeActivity.this);
            closeOverlays();
            if (ccOpen) closeCc(false);
            if (settings != null && settings.isOpen()) closeSettings();
            if (switcher != null && switcher.isOpen()) closeSwitcher();
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Vpn.forget();
            HomeKeys.packagesChanged();
            // on a small TV an app updating itself behind a video (the store does it in the
            // evening) no longer rebuilds the whole app list under the video: it waits for home
            if (Perf.light(c) && !resumed) appsDirty = true;
            else reloadApps();
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

    /** The TV's language when home was built: Aura keeps itself through most changes, but not this one. */
    private Locale builtIn;

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // the TV's language changed: home is built again, every word in the new one
        @SuppressWarnings("deprecation")
        Locale now = c.locale;
        if (builtIn != null && now != null && !builtIn.equals(now)) recreate();
    }

    @Override
    protected void onCreate(Bundle state) {
        createdAt = SystemClock.uptimeMillis();
        @SuppressWarnings("deprecation")
        Locale lang = getResources().getConfiguration().locale;
        builtIn = lang;
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
            handler.postDelayed(() -> {
                // "Home opens the TV's own home screen" only once the commands really ran
                if (safe.equals("home") && !Boolean.TRUE.equals(Safety.handedBack)) return;
                hud(R.drawable.ic_info, safe.equals("safe")
                        ? getString(R.string.aura_kept_stopping_so_some) : getString(R.string.aura_kept_stopping_home_opens));
            }, 6000);
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
            backdrop.setDefocus(homeDefocus(), 0);
        }
        restoreFocus(state);
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
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        Previews.listener = shotTaken;
        Ambient.makeRoom = makeRoom;
        Ambient.homeComing = homeComing;
    }

    /**
     * The screensaver is starting on a small TV: the home screen, out of sight, lets go of its
     * photographs and kept pictures, so the screensaver's have room. They come back on return.
     */
    private final Runnable makeRoom = () -> runOnUiThread(() -> {
        // only ever run by the screensaver as it starts, so home is out of sight whatever Android
        // says: on older Android the screensaver is a window over home, and word of the lost focus
        // often arrives after this does (the guard on it skipped exactly the small TVs it was for)
        if (isDestroyed()) return;
        backdrop.stop();
        backdrop.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE);
        roomMade = true;
        Previews.trim();
        dropExtras();
        Continue.trim();
    });

    /** The photographs were let go for the screensaver: they come back with home. */
    private boolean roomMade;

    /** The screensaver is ending: the photograph starts loading now, so home isn't dark when it's back. */
    private final Runnable homeComing = () -> runOnUiThread(() -> {
        if (isDestroyed() || !roomMade) return;
        roomMade = false;
        backdrop.start();
    });

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        // back from a screensaver that only covered home (older Android): no onStart to restart it
        if (focus && roomMade && resumed) {
            roomMade = false;
            backdrop.start();
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // home's frames, for Problem reports: a draw listener added before the window is up can be lost
        Trouble.watchFrames(getWindow());
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
        // what was let go behind the app comes back behind the screen: Top Shelf's cards and the
        // tiles on screen at once, the rows and columns past them once the remote rests
        started = true;
        speedWatch = watchSpeed();
        filledAt = SystemClock.uptimeMillis();
        paintNext();
        watchVpn(true);
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
        closeOverlays();
        // a kids' profile is asked for the parents' PIN first, as any way into Settings is, and Settings
        // opens only once it's typed: its page is shown then, not now, or Settings opened on its first page
        guarded(() -> {
            openSettingsNow(null);
            if (settings.isOpen()) settings.show("wallpaper");
        });
    }

    /**
     * The home screen, to bring forward with something to show (put in its extras). Where Aura is
     * the TV's home app it's asked for as Home itself: named directly, Android 10 and later doesn't
     * count it as the home screen and builds a whole second copy in a task of its own, and those
     * piled up, a hundred and more after a day of the switcher and Control Center over apps, holding
     * memory a 1.5 GB TV can't spare. Where another launcher keeps Home, it's named directly, which
     * brings back the one copy there is.
     */
    static Intent intentFor(Context c) {
        Intent i = HomeRole.held(c)
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.getPackageName())
                : new Intent(c, HomeActivity.class);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Android's answer to "Make Aura your home". */
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != HomeRole.REQUEST) return;
        if (HomeRole.held(this)) {
            hud(R.drawable.ic_check, getString(R.string.home_now_opens_aura));
        } else if (result == RESULT_OK) {
            // Android said yes, yet Home still opens another launcher: the Home button service takes it
            // from here. Noted first, or make() would ask again, get another yes, and go round forever.
            HomeRole.choiceIgnored(this);
            if (!Compat.granted(this, android.Manifest.permission.WRITE_SECURE_SETTINGS) && !(HomeKeys.on(this) && HomeKeys.live)) {
                // Aura can't switch its Home button on by itself yet: the welcome's ways to hand Home
                // over (on this TV, from a computer, by hand), rather than a hint and Android's settings
                openWelcome(4);
            } else {
                String m = HomeRole.make(this);
                if (m != null) hud(R.drawable.ic_info, m);
            }
        } else {
            homeHint(getString(R.string.press_home_on_the_remote));
        }
        if (settings.isOpen()) settings.refresh();
    }

    void homeHint(String text) {
        hud(R.drawable.ic_info, text);
    }

    private final Runnable homeCheck = this::checkHome;

    /** How often home has asked again for focus since it last came up, and whether its window is stepped out of focus right now. */
    private int focusAsks;
    private boolean focusStepped;
    private final Runnable focusBack = () -> {
        if (!focusStepped) return;
        focusStepped = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    };

    /**
     * From Android 11 a window is told it has focus by a message queued with the remote's keys, and
     * Android empties that queue whenever an accessibility service changes its key filter. Lost, the
     * message never comes again: home stays on screen and drops every press. So home in front
     * without focus steps its window out of focus for a moment and back, and Android sends it again.
     */
    private final Runnable focusCheck = new Runnable() {
        @Override
        public void run() {
            if (!resumed || isFinishing()) return;
            if (hasWindowFocus()) {
                if (focusAsks > 0) Trouble.note("FOCUS", "home was on screen without the remote; it answered again once asked");
                focusAsks = 0;
                return;
            }
            // twice at most: a system panel over home has the focus by right, and keeps it
            if (focusAsks >= 2) return;
            focusAsks++;
            focusStepped = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            // a frame apart, or Android sees no change and sends nothing
            handler.postDelayed(focusBack, 150);
            handler.postDelayed(this, 2500);
        }
    };

    private void checkFocusSoon(long delay) {
        handler.removeCallbacks(focusCheck);
        focusAsks = 0;
        handler.postDelayed(focusCheck, delay);
    }

    /** Back from Android's Accessibility page: whether the Home button service came on there. */
    private void checkHome() {
        if (!HomeRole.waiting || !resumed) return;
        HomeRole.waiting = false;
        if (HomeKeys.live) hud(R.drawable.ic_check, getString(R.string.home_now_opens_aura));
        else hud(R.drawable.ic_info, getString(R.string.auras_home_button_is_still));
        if (settings.isOpen()) settings.refresh();
    }

    /** The app list read again on return (see appsDirty) at home's first idle moment, never in the way of resuming. */
    private final android.os.MessageQueue.IdleHandler reloadWhenIdle = () -> {
        if (resumed && appsDirty) {
            appsDirty = false;
            reloadApps();
        }
        return false;
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // still stepped back for an app that never hid home (one drawn over it, or one that didn't come)
        comeForward(true);
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
        // the photograph moves only while home is in front (it stops in onPause), and not under All Apps
        if (!allApps.isOpen()) backdrop.start();
        // home resumes while the app it replaces is still pausing (resumeWhilePausing): the list waits for a quiet moment
        if (appsDirty) Looper.myQueue().addIdleHandler(reloadWhenIdle);
        wake();
        // back from an app: what apps put in the TV's listings is read afresh, so "jump back in" knows
        // what you just watched. On a small TV the widgets look a moment later: the app just left
        // is still letting go of its memory then, and home's first frames are worth more than a
        // card a second sooner
        handler.removeCallbacks(afterApp);
        if (leftApp) {
            TopShelf.clearCache();
            Continue.clearCache();
        }
        if (leftApp && Perf.light(this)) handler.postDelayed(afterApp, 1200);
        else refreshWidgets(true);
        leftApp = false;
        refreshStatus();
        handler.removeCallbacks(glintTick);
        glints = 0;
        handler.postDelayed(glintTick, 4000);
        if (ccOpen) cc.refresh();
        else if (getCurrentFocus() == null && !cells.isEmpty()) focusGrid(gridAt);
        else if (awayAt > 0 && zone == SHELF && !cells.isEmpty()) focusGrid(gridAt);
        else if (awayAt > 0 && zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) refreshShelf();
        // have Control Center's toggles and readings current before it's next opened, so opening
        // it does no system lookups on its first frame
        handler.removeCallbacks(warmCc);
        handler.postDelayed(warmCc, 1500);
        // Home button in apps switched on but not running (Aura crashed since): bring it back
        handler.removeCallbacks(healKeys);
        handler.postDelayed(healKeys, 4000);
        // after the Home button service has let its key filter go (it waits for home to settle)
        checkFocusSoon(1500);
        // what's new, once, to someone who has just updated: when home itself is up and at rest
        handler.removeCallbacks(showNews);
        if (news == null && WhatsNew.due(this)) handler.postDelayed(showNews, 1800);
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
        // the beat glow, allowed just now from Settings: it comes on
        if (code == SettingsPanel.BEAT_ASK && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Prefs.set(this, Prefs.BEAT, true);
            if (settings.isOpen()) settings.refresh();
        }
        // Wi-Fi or Bluetooth allowed from Control Center: its pills and status follow at once
        if ((code == 7 || code == 8) && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshStatus();
            if (ccOpen) cc.refresh();
            if (ccOpen && code == 7) cc.wifiGranted();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(focusCheck);
        handler.removeCallbacks(focusBack);
        handler.removeCallbacks(showNews);
        focusBack.run();
        if (quietScreen) wakeQuietScreen();
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
        // an app is opening: the photograph's drift, the glass following it and Top Shelf's lookups
        // wait until home is back, rather than drawing beneath the app (until onStop) as it starts
        backdrop.stop();
        handler.removeCallbacks(shelfLoad);
        shelfWanted = null;
        if (editing) stopEdit();
        if (menu.isOpen()) closeMenu(null);
    }

    private boolean leftApp;

    /** The widgets looked at again, a moment after coming back from an app on a small TV (see onResume). */
    private final Runnable afterApp = this::widgetsAfterApp;

    private void widgetsAfterApp() {
        if (started && !isDestroyed()) refreshWidgets(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        leftApp = true;
        started = false;
        // a grown-up's errand in the TV's own settings, or anywhere else, locks a kids' profile behind it
        unlockedUntil = 0;
        handler.removeCallbacks(paintNext);
        handler.removeCallbacks(tidyTiles);
        if (Perf.light(this)) shed();
        // out of sight: full size again, so home never comes back with a snap
        comeForward(false);
        keepFocus();
        watchVpn(false);
        endLaunch();
        unregisterReceiver(clockReceiver);
        if (tim != null) {
            liveGen++;
            try {
                tim.unregisterCallback(inputWatch);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (gridAt < cells.size()) {
            out.putString("aura_focus_key", cells.get(gridAt).key());
            out.putInt("aura_focus_at", gridAt);
        }
    }

    /** The tile the first fill comes back to (see populate): by its key, else by its place. */
    private String focusKey;
    private int focusIndex = -1;
    /** The tile last written by keepFocus in this process. */
    private static String keptFocus;

    /**
     * Back on the tile you left after Android built home again: from the saved state, else (home
     * opts out of saved state, stateNotNeeded) from where it last went behind an app since the TV
     * started, as when it was closed for memory while a film played.
     */
    private void restoreFocus(Bundle state) {
        if (state != null) {
            focusKey = state.getString("aura_focus_key");
            focusIndex = state.getInt("aura_focus_at", -1);
            return;
        }
        SharedPreferences d = Prefs.device(this);
        if (d.getLong("focus_time", 0) < System.currentTimeMillis() - SystemClock.elapsedRealtime()) return;
        focusKey = d.getString("focus_key", null);
        focusIndex = d.getInt("focus_at", -1);
    }

    /** The tile in focus as home goes behind an app, written only when it's another one. */
    private void keepFocus() {
        if (gridAt >= cells.size()) return;
        String key = cells.get(gridAt).key();
        if ((gridAt + "|" + key).equals(keptFocus)) return;
        keptFocus = gridAt + "|" + key;
        Prefs.device(this).edit().putString("focus_key", key).putInt("focus_at", gridAt)
                .putLong("focus_time", System.currentTimeMillis()).apply();
    }

    @Override
    protected void onDestroy() {
        // nothing posted earlier runs on a torn-down home: several hand work to the workers below,
        // which refuse it once shut (a crash), and the rest touch views that are gone
        handler.removeCallbacksAndMessages(null);
        settings.shutDrop();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenOffReceiver);
        if (Previews.listener == shotTaken) Previews.listener = null;
        if (Ambient.makeRoom == makeRoom) Ambient.makeRoom = null;
        if (Ambient.homeComing == homeComing) Ambient.homeComing = null;
        cc.release();
        backdrop.release();
        worker.shutdown();
        recentWorker.shutdown();
        shelfWorker.shutdown();
        tilePainter.shutdown();
        if (allApps != null) allApps.release();
        handler.removeCallbacks(dropWatch);
        if (watch != null) watch.release();
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
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            // On a small TV the rule above costs more than it saves: with the Home button service
            // bound, Android keeps Aura among the apps it never trims, so those levels never come,
            // and the photographs (tens of MB) sat behind a video app that needed every page on a
            // 1 GB TV, the video stuttering as the kernel paged them in and out. In light mode they
            // go here, as they do for the screensaver, and come back with home over the small copy.
            // The cards and kept screens stay: a Home press still opens the switcher at once.
            if (Perf.light(this)) {
                backdrop.trim(TRIM_MEMORY_MODERATE);
                Previews.dropFresh();
                Continue.trim();
                shed();
                trimWatch();
            }
            return;
        }
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            trimWatch();
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

    /** Short on memory: a Watch screen that's closed goes now; one still up (a trailer playing over it) keeps only the genre on show. */
    private void trimWatch() {
        if (watch == null) return;
        if (watch.isOpen()) watch.shed();
        else dropWatchNow();
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
        // Home pressed on home: a remote that seemed dead gets its answer here
        if (resumed) checkFocusSoon(600);
        if (quietScreen) {
            wakeQuietScreen();
            return;
        }
        // see What's new again: am start -n app.aura.home/.HomeActivity --es aura_news show
        if (intent.hasExtra("aura_news")) {
            handler.postDelayed(this::openNews, 500);
            return;
        }
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
        // the TV's own panel and inputs buttons arrive through PanelBridge, their action carried along
        String act = intent.hasExtra("aura_panel") ? intent.getStringExtra("aura_panel") : intent.getAction();
        if ("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL".equals(act)
                || "android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL".equals(act)
                || "com.amazon.tv.action.NAVIGATE_TO_INPUTS".equals(act)) {
            lastHomeAt = 0;
            if (welcome != null || switcher.isOpen()) return;
            boolean inputs = "com.amazon.tv.action.NAVIGATE_TO_INPUTS".equals(act);
            if (ccOpen && "android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL".equals(act)) closeCcBack();
            else if (!ccOpen) openCc(inputs);
            return;
        }
        if (intent.hasExtra("aura_key_code")) {
            lastHomeAt = 0;
            if (welcome != null || Profiles.kids(this)) return;
            int kCode = intent.getIntExtra("aura_key_code", KeyEvent.KEYCODE_UNKNOWN);
            int kScan = intent.getIntExtra("aura_key_scan", 0);
            if (switcher.isOpen()) closeSwitcher();
            if (ccOpen) closeCc(false);
            closeOverlays();
            if (!settings.isOpen()) openSettingsNow("keys");
            settings.openKeyPicker(kCode, kScan);
            return;
        }
        if (ccFor != null) {
            lastHomeAt = 0;
            if (welcome != null || switcher.isOpen()) return;
            // the remote's notification button on the home screen (HomeKeys): Control Center, or shut again
            if (ccFor.isEmpty()) {
                if (ccOpen) closeCcBack();
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
            closeOverlays();
            if (editing) stopEdit();
            wake();
            focusGrid(0);
            return;
        }
        // see the first-run welcome again: am start -n app.aura.home/.HomeActivity --ez aura_welcome true
        if (intent.getBooleanExtra("aura_welcome", false)) {
            // not in a kids' profile: the welcome changes the whole TV, and any app can send this
            if (!Profiles.kids(this)) handler.postDelayed(this::openWelcome, 500);
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
        // Playing, finding an app, a name or PIN being typed, Who's watching)
        closeOverlays();
        if (settings.isOpen()) closeSettings();
        if (ccOpen) closeCc(true);
        else focusGrid(0);
        wake();
    }

    @Override
    public void onBackPressed() {
        if (ccOpen) {
            if (!cc.back()) closeCcBack();
        } else if (settings.isOpen()) {
            if (!settings.back()) closeSettings();
        } else if (zone == SIDE) {
            returnToContent();
        } else if (rows && zone == GRID && gridAt < rowIdx.length && rowIdx[gridAt] > 0) {
            // on category rows: back up to the first row, at the same place on screen
            focusGrid(rowTarget(0, colIdx[gridAt] - rowInfo.get(rowIdx[gridAt]).first));
        } else if (!rows && zone == GRID && gridAt >= COLS) {
            focusGrid(gridAt % COLS);
        } else if (dock && zone == GRID && rowOf(gridAt) == 0) {
            setPhoto(true);
        } else if (zone == TOP || zone == SHELF || dock && zone == WIDGETS) {
            // the top buttons, Top Shelf, and the dock's widgets: Back returns to the apps below,
            // to the first row at the same place on screen (on category rows the first row is
            // not every fifth tile)
            if (rows && gridAt < rowIdx.length && rowIdx[gridAt] > 0) {
                focusGrid(rowTarget(0, colIdx[gridAt] - rowInfo.get(rowIdx[gridAt]).first));
            } else {
                focusGrid(rows ? gridAt : gridAt % COLS);
            }
        }
    }

    // ---- layout ------------------------------------------------------------------------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        backdrop = new Backdrop(this);
        // the photograph rests at this softness: drawn from one pre-mixed copy, not two layers
        backdrop.setSteadyDefocus(homeDefocus());
        backdrop.setListener(this);
        root.addView(backdrop, match());
        bloom = new Bloom(this);
        backdrop.addTick(bloom);
        root.addView(bloom, match());
        scrim = new Scrim(this, false);
        root.addView(scrim, match());
        backdrop.setShade(scrim);

        home = flat(this);
        home.setClipChildren(false);
        root.addView(home, match());
        // everything right of the sidebar scrolls as one page; the grid is positioned by hand
        page = flat(this);
        page.setClipChildren(false);
        home.addView(page, new FrameLayout.LayoutParams(MATCH, Ui.dp(GRID_Y + 2 * ROW_H)));
        // where focus waits while tiles are rebuilt (see park): it draws nothing, and only takes focus then
        parking = new View(this);
        parking.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= 26) parking.setDefaultFocusHighlightEnabled(false);
        page.addView(parking, new FrameLayout.LayoutParams(1, 1));
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
        page.addView(gridLabel, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(GRID_Y - 28), 0, 0));
        // Aura's sticky header scrim & soft bottom horizon feather (tiles scroll beneath the docked categories)
        stickyTopScrim = new Shade(this, new int[]{0xCC07090E, 0x8007090E, 0x0007090E});
        stickyTopScrim.setAlpha(0f);
        home.addView(stickyTopScrim, frame(MATCH, Ui.dp(92), Gravity.TOP, 0, 0, 0, 0));
        bottomFeather = new Shade(this, new int[]{0x0007090E, 0x8C07090E});
        home.addView(bottomFeather, frame(MATCH, Ui.dp(44), Gravity.BOTTOM, 0, 0, 0, 0));
        // the Aura style's centered categories: inside a stage-wide sticky container that docks to the top on scroll
        homeTabBar = flat(this);
        homeTabBar.setClipChildren(false);
        homeTabBar.setClipToPadding(false);
        homeTabs = new TabStrip(this, 10.5f, 13, 6, new TabStrip.Listener() {
            @Override
            public void pick(int k) {
                pickHomeTab(k);
            }

            @Override
            public void hold(int k, View tab) {
                swallow(heldConfirm);
                categoryMenu(homeTabIds.get(k), tab);
            }
        });
        homeTabs.setVisibility(View.GONE);
        homeTabBar.addView(homeTabs, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        homeTabBar.setVisibility(View.GONE);
        homeTabBar.setTranslationY(Ui.dpf(TABS_REST_Y));
        home.addView(homeTabBar, frame(Ui.dp(WIDGET_ROW), WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), 0, 0, 0));
        // the tvOS style's frosted shelf, edge to edge behind the first row (added before the tiles: beneath them)
        dockGlass = new GlassFrame(this, backdrop, 26, 0.42f);
        dockGlass.frost = 0.09f;
        dockGlass.edgeLight = true;
        dockGlass.setVisibility(View.GONE);
        page.addView(dockGlass, frame(MATCH, Ui.dp(ROW_H), Gravity.TOP | Gravity.START, 0, 0, 0, 0));
        focusLayer = FocusLayer.attach(page, dockGlass);
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
            public void warnLightOff(Runnable turnOff) {
                speedSheet().warn(turnOff);
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
                if (allApps.isOpen()) closeAllApps();
                closeWatch();
                if (who.isOpen()) closeWho(true);
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
                items.add(new AppMenu.Item(R.drawable.ic_apps, getString(R.string.take_out_of_folder), () -> {
                    menu.hide();
                    Folders.remove(HomeActivity.this, a.pkg);
                    String id = folderPanel.openId;
                    closeFolder();
                    populate();
                    Folders.Folder left = id == null ? null : Folders.all(HomeActivity.this).get(id);
                    if (left != null) openFolder(id);
                    hud(R.drawable.ic_check, getString(R.string.name_is_back_on_the_grid, a.label));
                }));
                // a kids' profile can move apps and make folders, nothing more (as on the grid)
                if (!Profiles.kids(HomeActivity.this)) items.add(new AppMenu.Item(R.drawable.ic_info, getString(R.string.app_info), () -> {
                    menu.hide();
                    closeFolder();
                    open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)), from);
                }));
                items.add(new AppMenu.Item(R.drawable.ic_settings, getString(R.string.rename_folder), () -> {
                    menu.hide();
                    String id = folderPanel.openId;
                    closeFolder();
                    if (id != null) nameFolder(id, false);
                }));
                menuBack = from;
                menu.show(from, a.label, items);
            }
        });
        root.addView(folderPanel, match());
        allApps = new AllApps(this, new AllApps.Host() {
            @Override
            public Backdrop backdrop() {
                return backdrop;
            }

            @Override
            public void launch(AppRepo.App a, View from) {
                closeAllApps();
                HomeActivity.this.launch(a.launch, from, a.label);
            }

            @Override
            public void hold(AppRepo.App a, Card from) {
                allAppsMenu(a, from);
            }

            @Override
            public void edit(AllApps.Tab tab, View from) {
                categoryMenu(tab.id, from);
            }

            @Override
            public void close() {
                closeAllApps();
            }

            @Override
            public void depth(float p) {
                // only home's position moves, a property of its own drawing: nothing painted again, no layer
                home.setTranslationY(Ui.dpf(RECEDE) * p);
            }
        });
        root.addView(allApps, match());
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
                // measured now: the card leaves the deck as the switcher's own zoom begins
                final Bundle zoom = zoomFrom(from);
                switcher.launchInto(a, () -> openFromSwitcher(a.launch, a.label, zoom));
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
                hud(R.drawable.ic_check, count == 1 ? getString(R.string.closed) : getString(R.string.closed_n_apps, count));
            }
        });
        root.addView(switcher, match());
        nowPlaying = new NowPlaying(this, backdrop);
        nowPlaying.onQuiet = this::quietScreen;
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
                hud(R.drawable.ic_check, getString(R.string.hello_name, p.name));
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
                Set<String> seen = new HashSet<>();
                for (Cell c : cells) {
                    AppRepo.App a = find(c.tag);
                    if (a != null && seen.add(a.pkg)) out.add(a);
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
            public void hold(AppRepo.App a, Card from) {
                swallow(heldConfirm);
                List<AppMenu.Item> items = new ArrayList<>();
                items.add(new AppMenu.Item(R.drawable.ic_play, getString(R.string.open), () -> {
                    menu.hide();
                    closeFind();
                    launch(a.launch, from, a.label);
                }));
                items.add(new AppMenu.Item(R.drawable.ic_apps, getString(R.string.add_to_folder), () -> {
                    menu.hide();
                    closeFind();
                    chooseFolder(a.pkg, a.label, from);
                }));
                if (!Profiles.kids(HomeActivity.this)) {
                    items.add(new AppMenu.Item(R.drawable.ic_info, getString(R.string.app_info), () -> {
                        menu.hide();
                        closeFind();
                        HomeActivity.this.open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)), from);
                    }));
                }
                menuBack = from;
                menu.show(from, a.label, items);
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
        menu = new AppMenu(this, backdrop);
        // LOW draws no dim layer behind a menu: the tiles step back instead, all but the one it's for
        menu.behind = on -> {
            for (int k = 0; k < cells.size(); k++) {
                float a = rowAlpha(k);
                cells.get(k).setAlpha(on && k != gridAt ? a * Ui.LOW_MENU_ROWS : a);
            }
        };
        root.addView(menu, match());
        ask = new Ask(this, backdrop);
        root.addView(ask, match());
        quietVeil = new View(this) {
            // one flat colour: its fade needs no offscreen layer
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        quietVeil.setBackgroundColor(0xFF000000);
        quietVeil.setAlpha(0f);
        quietVeil.setVisibility(View.GONE);
        root.addView(quietVeil, match());
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
        hello = Ui.text(this, 26, Ui.lightFace, 0xFFF7F4EE);
        hello.setIncludeFontPadding(true);
        hello.setLetterSpacing(-0.02f);
        Ui.lift(hello, 10);
        greeting.addView(hello, lp(WRAP, WRAP, 0, 0));
        dateLine = Ui.text(this, 13, Ui.regular, 0xD9F5F2EB);
        Ui.lift(dateLine, 6);
        greeting.addView(dateLine, lp(WRAP, WRAP, 0, 2));
        page.addView(greeting, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(20), 0, 0));
    }

    /** The sky outside: the light now, the next sunrise or sunset, and the sun on its arc. */
    private void buildSky() {
        skyW = new Widget(this, backdrop);
        LinearLayout s = column(18, 15, 18, 12);
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
        s.addView(skyTitle, lp(WRAP, WRAP, 0, 4));
        skyArc = new SkyArc(this);
        skyArc.setMinimumHeight(Ui.dp(24));
        LinearLayout.LayoutParams arcLp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        arcLp.topMargin = Ui.dp(6);
        s.addView(skyArc, arcLp);
        LinearLayout feet = new LinearLayout(this);
        skyRise = Ui.text(this, 9.5f, Ui.medium, 0x99FFFFFF);
        skySet = Ui.text(this, 9.5f, Ui.medium, 0x99FFFFFF);
        skySet.setGravity(Gravity.END);
        feet.addView(skyRise, new LinearLayout.LayoutParams(0, WRAP, 1f));
        feet.addView(skySet, new LinearLayout.LayoutParams(0, WRAP, 1f));
        s.addView(feet, lp(MATCH, WRAP, 0, 3));
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
        tdKicker.setText(getString(R.string.today_2));
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
        dl.setText(getString(R.string.data));
        tdDataRow.addView(dl, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tdData = Ui.text(this, 9.5f, Ui.medium, 0xCCFFFFFF);
        tdDataRow.addView(tdData);
        tdDataRow.setVisibility(View.GONE);
        d.addView(tdDataRow, lp(MATCH, WRAP, 0, 0));
        LinearLayout m = new LinearLayout(this);
        m.setGravity(Gravity.CENTER_VERTICAL);
        TextView ml = Ui.caps(this, 6.5f, 0x8CFFFFFF, 0.24f);
        ml.setText(getString(R.string.memory_2));
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
                    hud(R.drawable.ic_play, getString(R.string.playing_value, playing.title));
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
        tdMusic = column(18, 16, 18, 14);
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
        top.addView(tdArt, new LinearLayout.LayoutParams(Ui.dp(46), Ui.dp(46)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        // the same small spaced capitals as the card's TODAY
        tdmKicker = Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.24f);
        tdmKicker.setSingleLine(true);
        tdmKicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(tdmKicker, lp(MATCH, WRAP, 0, 0));
        tdmTitle = Ui.text(this, 13.5f, Ui.medium, 0xFFF7F4EE);
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
        tdMusic.addView(tdmBy, lp(MATCH, WRAP, 0, 6));
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
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(4), 1f);
        pl.leftMargin = Ui.dp(12);
        pl.rightMargin = Ui.dp(2);
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
        tdmKicker.setText(live ? (p.playing ? getString(R.string.now_playing_caps) : getString(R.string.paused)) : getString(R.string.last_played));
        // the text column beside the cover: two lines, the type a little smaller before anything is cut
        int col = (tdW.getWidth() > 0 ? tdW.getWidth() : Ui.dp(WIDGET_W[2])) - Ui.dp(18 + 18 + 50 + 12);
        Ui.fitLines(tdmTitle, Ui.songTitle(p.title), col, 2, 13.5f, 11.5f);
        boolean by = p.by != null && !p.by.trim().isEmpty() && !p.by.equalsIgnoreCase(p.title);
        tdmBy.setText(by && app != null ? p.by + "  ·  " + app : by ? p.by : app != null ? app : "");
        if (p.art != null && !p.art.isRecycled()) {
            tdArt.setImageBitmap(p.art);
            tdArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tdArt.setPadding(0, 0, 0, 0);
        } else if (owner != null && owner.icon() != null) {
            // the app's icon whole, edge to edge like an icon on iOS (small in a grey box, it looked lost)
            tdArt.setImageDrawable(owner.icon());
            tdArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tdArt.setPadding(0, 0, 0, 0);
        } else {
            tdArt.setImageResource(R.drawable.ic_play);
            tdArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            tdArt.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        }
        tdProgress.setVisibility(p.progress > 0.005f ? View.VISIBLE : View.INVISIBLE);
        tdProgress.set(Math.max(0f, Math.min(1f, p.progress)));
        boolean pausable = live && p.playing, small = minimised(p);
        tdChipIcon.setImageResource(small ? R.drawable.ic_expand : pausable ? R.drawable.ic_pause : R.drawable.ic_play);
        tdChipText.setText(small ? getString(R.string.expand) : pausable ? getString(R.string.playing) : live ? getString(R.string.resume) : getString(R.string.continue_word));
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
        lTitle = Ui.text(this, 24, Ui.lightFace, 0xFFFFFFFF);
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
        hint.setText(getString(R.string.hold_ok_to_minimise));
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
            hud(R.drawable.ic_check, getString(R.string.minimised_ok_on_the_small));
            updateListening();
            refreshWidgets(false);
        };
        page.addView(listenW, frame(Ui.dp(WIDGET_ROW), Ui.dp(WIDGET_H), Gravity.TOP | Gravity.START,
                Ui.dp(PAGE_X), Ui.dp(WIDGET_Y), 0, 0));
        listenW.setVisibility(View.GONE);
    }

    private final Runnable listen = this::updateListening;

    /** The line under the song, once a second while the card is on show (light mode: while it has focus, else every 5 s). */
    private final Runnable listenTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed || listening == null) return;
            listenProgress();
            handler.postDelayed(this, listenW.hasFocus() || !Perf.light(HomeActivity.this) ? 1000 : 5000);
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
        lKick.setText((getString(R.string.now_playing_caps) + (label.isEmpty() ? "" : "  \u00b7  " + label)).toUpperCase(Locale.getDefault()));
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
        // what the player last reported: asking it again each second fetched the cover picture too
        android.media.MediaMetadata md = m != null ? MediaWatch.meta(m) : null;
        android.media.session.PlaybackState st = m != null ? MediaWatch.state(m) : null;
        long dur = md != null ? md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) : 0;
        boolean on = st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        if (st == null || dur <= 0) {
            lBar.setVisibility(View.INVISIBLE);
            lTime.setText(st != null && !on ? getString(R.string.paused_2) : "");
            return;
        }
        long pos = st.getPosition();
        if (on) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * MediaWatch.speed(st));
        pos = Math.max(0, Math.min(dur, pos));
        lBar.setVisibility(View.VISIBLE);
        lBar.set(pos / (float) dur);
        String clock = NowPlaying.clock(pos) + "  /  " + NowPlaying.clock(dur);
        lTime.setText(on ? clock : getString(R.string.paused_value, clock));
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
        if (widgetsOnHome()) {
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

    /** Widgets above the apps: always in the Aura style, in the tvOS style only when asked for. */
    private boolean widgetsOnHome() {
        return !dock || Prefs.dockWidgets(this);
    }

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
        if (stickyTopScrim != null) {
            stickyTopScrim.setVisibility(aura);
            if (dock) stickyTopScrim.setAlpha(0f);
        }
        feather();
        if (dock) {
            homeTabs.setVisibility(View.GONE);
            if (homeTabBar != null) homeTabBar.setVisibility(View.GONE);
        }
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
        sideShown(!dock, false);
        topShown(!dock, false);
        refreshProfileIdentity();
        if (zone == WIDGETS || zone == TOP && !dock || zone == SIDE && sideHidden) zone = contentZone = GRID;
        placeDock();
        positionHomeTabs(scrollY + giveY);
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
        topCc = topButton(R.drawable.ic_tune, R.string.control_center_2, 40, () -> openCc(false));
        topSettings = topButton(R.drawable.ic_settings, R.string.settings, 40 + 50, this::openSettings);
        topFind = topButton(R.drawable.ic_search, R.string.find_an_app, 40 + 2 * 50, () -> openFind(""));
        tops.add(topFind);
        tops.add(topSettings);
        tops.add(topCc);
        tvosHint = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final String hint = getString(R.string.swipe_up_for_fullscreen);
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
                c.drawText(hint, cx, Ui.dpf(23f), p);
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

    /** A round button of the tvOS top bar: an icon alone, so its name is what TalkBack says. */
    private Pad topButton(int icon, int name, int right, Runnable run) {
        Pad b = new Pad(this, backdrop, 20, 0.26f);
        b.setContentDescription(getString(name));
        b.frost = 0.06f;
        b.focusScale = Ui.FOCUS_SCALE_CONTROL;
        ImageView iv = b.tint(ControlCenter.icon(this, icon), 1f);
        b.addView(iv, frame(Ui.dp(17), Ui.dp(17), Gravity.CENTER, 0, 0, 0, 0));
        b.onClick = run;
        b.setVisibility(View.GONE);
        home.addView(b, frame(Ui.dp(40), Ui.dp(40), Gravity.TOP | Gravity.END, 0, Ui.dp(26), Ui.dp(right), 0));
        return b;
    }

    /**
     * Who's watching, over the home screen. Anything else open closes first: left beneath, it kept
     * the remote's keys. Back returns to where you were.
     */
    private void openWho() {
        if (who.isOpen() || welcome != null) return;
        if (switcher.isOpen()) closeSwitcher();
        if (ccOpen) closeCc(false);
        if (settings.isOpen()) closeSettings();
        closeOverlays();
        if (editing) stopEdit();
        stopPreview();
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
        who.close();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
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
        weatherSheet.close();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
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
        // four things, each said by its icon alone: the apps are a press to the right, the photograph
        // has the screen with Up past the top of the page, and Watch is a tile among the apps
        addSide(col, R.drawable.ic_search, getString(R.string.find_an_app), () -> openFind(""));
        addSide(col, R.drawable.ic_hdmi, getString(R.string.inputs_2), () -> openCc(true));
        LinearLayout.LayoutParams rl = lp(Ui.dp(22), Ui.hair(), 0, 10);
        rl.bottomMargin = Ui.dp(4);
        col.addView(Ui.rule(this, 0x33FFFFFF), rl);
        addSide(col, R.drawable.ic_tune, getString(R.string.control_center_2), () -> openCc(false));
        addSide(col, R.drawable.ic_settings, getString(R.string.settings), this::openSettings);
        // a small red dot on Settings while a new version of Aura waits, as on iOS
        updateDot = redDot(this);
        ((FrameLayout) side.get(side.size() - 1)).addView(updateDot, frame(Ui.dp(8), Ui.dp(8), Gravity.TOP | Gravity.END, 0, Ui.dp(6), Ui.dp(6), 0));
        sidebar.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        home.addView(sidebar, frame(Ui.dp(58), WRAP, Gravity.START | Gravity.CENTER_VERTICAL, Ui.dp(22), 0, 0, 0));
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
                showHud(R.drawable.ic_bolt, getString(R.string.aura_version_is_out_settings, v), 5000);
            }
        }
    };

    /** One of the rail's icons; {@code label} is what TalkBack says for it. */
    private void addSide(LinearLayout col, int iconRes, String label, Runnable action) {
        Pad p = new Pad(this, null, 21, 0f);
        p.plainColor = 0x00FFFFFF;
        p.edgeLight = false;
        p.focusScale = Ui.FOCUS_SCALE_CONTROL;
        p.setContentDescription(label);
        p.addView(p.tint(ControlCenter.icon(this, iconRes), 1f), frame(Ui.dp(19), Ui.dp(19), Gravity.CENTER, 0, 0, 0, 0));
        p.onClick = action;
        col.addView(p, lp(Ui.dp(42), Ui.dp(42), 0, side.isEmpty() ? 0 : 6));
        side.add(p);
    }

    /** Updates the status-pill profile avatar badge and personalized greeting. */
    private void refreshProfileIdentity() {
        Profiles.Profile p = Profiles.active(this);
        String name = p.name != null ? p.name.trim() : "";
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault());
        if (stProfile != null && stProfileBg != null) {
            stProfile.setText(initial);
            stProfileBg.setColor(Ui.mix(p.color, 0xFF141820, 0.18f));
            boolean showBadge = !dock || Profiles.all(this).size() > 1;
            int vis = showBadge ? View.VISIBLE : View.GONE;
            stProfile.setVisibility(vis);
            if (stProfileRule != null) stProfileRule.setVisibility(vis);
        }
    }

    private void buildStatus() {
        status = new GlassFrame(this, backdrop, 20, 0.26f);
        status.frost = 0.06f;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(14), 0, Ui.dp(18), 0);
        stProfile = Ui.text(this, 10f, Ui.bold, 0xFFFFFFFF);
        stProfile.setIncludeFontPadding(false);
        stProfile.setGravity(Gravity.CENTER);
        stProfileBg = new GradientDrawable();
        stProfileBg.setShape(GradientDrawable.OVAL);
        stProfileBg.setColor(0xFF6E8BFF);
        stProfileBg.setStroke(Math.max(1, Ui.dp(0.75f)), 0x4DFFFFFF);
        stProfile.setBackground(stProfileBg);
        LinearLayout.LayoutParams pfl = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
        r.addView(stProfile, pfl);
        stProfileRule = Ui.rule(this, 0x40FFFFFF);
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(Ui.hair(), Ui.dp(14));
        prl.leftMargin = Ui.dp(10);
        prl.rightMargin = Ui.dp(10);
        r.addView(stProfileRule, prl);
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
        // today's sunrise once it's past means tomorrow's, which may not come (polar edge cases)
        long riseShown = rise > now ? rise : Sun.next(now, true);
        String riseAt = riseShown > 0 ? tf.format(new Date(riseShown)).toUpperCase(Locale.getDefault()) : "";
        String setAt = set > 0 ? tf.format(new Date(set)).toUpperCase(Locale.getDefault()) : "";
        switch (m) {
            case FIRST_LIGHT:
                show(m, getString(R.string.sky_dawn), getString(R.string.dawn_is_breaking), getString(R.string.sunrise_at, riseAt));
                break;
            case SUNRISE:
                show(m, getString(R.string.sunrise), getString(R.string.the_sun_is_up), getString(R.string.sunset_at_day, setAt));
                break;
            case GOLDEN:
                show(m, getString(R.string.sky_golden), getString(R.string.the_light_turns_gold), getString(R.string.sunset_at_golden, setAt));
                break;
            default:
                show(m, getString(R.string.sunset), getString(R.string.the_sun_has_set), getString(R.string.sunrise_at_night, riseAt));
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
        // TalkBack reads each new message as it comes, without moving focus
        hud.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
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
                    || !String.valueOf(a.src).equals(b.src) || a.hasBanner() != b.hasBanner()
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
        if (cells.isEmpty() && !appsRead && !AppRepo.hasSaved(app)) {
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
        if (allApps.isOpen()) allApps.open(allAppsTabs());
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
        String keep = gridAt < cells.size() ? cells.get(gridAt).key() : null;
        // on the first fill the window may already have parked focus on the rail; the grid takes it
        boolean firstFill = cells.isEmpty(), hadFocus = zone == GRID && getCurrentFocus() instanceof Card;
        int place = gridAt;
        // the first fill after Android built home again: the tile you left (restoreFocus)
        if (firstFill && focusKey != null) {
            keep = focusKey;
            place = Math.max(0, focusIndex);
        }
        View had = getWindow().getCurrentFocus();
        stopPreview();
        // the tiles on screen are painted at once; the rest wait for the first frames and a quiet remote
        filledAt = SystemClock.uptimeMillis();
        Map<String, Cell> old = new java.util.HashMap<>();
        for (Cell c : cells) old.put(c.key(), c);
        // a new app size: every tile is painted again at its new size, none kept
        if (sizeGrid(Prefs.gridCols(this))) for (Cell c : old.values()) c.sig = null;
        // home keeps no category rows: the categories are All Apps' tabs
        rows = false;
        rowsDim = false;
        if (!rows) {
            // back to the grid: no row slide may keep moving tiles
            for (RowInfo r : rowInfo) if (r.glide != null) r.glide.stop();
            rowInfo.clear();
            rowIdx = colIdx = new int[0];
        }
        paged = !rows && !dock && Prefs.on(this, Prefs.GRID_PAGES);
        if (!paged && pageShift != 0f) {
            // pages switched off: the grid lies flat again, from the first column
            if (pageGlide != null) pageGlide.stop();
            pageGlide = null;
            gridPage = 0;
            pageShift = 0f;
        }
        cells.clear();
        // the Aura style's tabs, made again from the categories; a tab other than All apps shows its apps alone
        AllApps.Tab tab = refreshHomeTabs();
        boolean calm = Perf.light(this);
        String style = Prefs.tileStyle(this);
        boolean glassTiles = style.equals("glass"), tvosTiles = style.equals("tvos") || dock;
        String nameMode = Prefs.nameMode(this);
        for (String[] in : tab != null ? java.util.Collections.<String[]>emptyList() : live) {
            final String id = in[0], label = in[1];
            final int glyph = InputSheet.glyphOf(in.length > 2 ? Integer.parseInt(in[2]) : InputSheet.HDMI);
            final String sig = "input|" + style + "|" + dock + "|" + label + "|" + glyph;
            Cell kept = reuse(old, "input:" + id, sig);
            if (kept != null) {
                kept.setNameMode(nameMode, false);
                kept.card.sway = !calm;
                cells.add(kept);
                continue;
            }
            final Cell cell = new Cell(this, Tiles.input(this, label, glyph), 0xFF46D7C8, label, "input:" + id, TILE_W, TILE_H);
            cell.card.setGrowRoom(GAP / 2f);
            cell.sig = sig;
            cell.card.glass = backdrop;
            behindScreen(old, "input:" + id, cell);
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
        // Watch, once it's switched on: Aura's own tile, after the live inputs and before your apps
        if (tab == null && Prefs.watch(this)) cells.add(watchCell(old, tvosTiles, nameMode, calm));
        // the saved order holds apps and folders; apps inside a folder leave the grid
        Map<String, Folders.Folder> folders = Folders.all(this);
        Set<String> foldered = new HashSet<>();
        for (Folders.Folder f : folders.values()) foldered.addAll(f.apps);
        List<String> hidden = new ArrayList<>(Prefs.hidden(this));
        List<Object> entries = new ArrayList<>();
        if (tab != null) {
            entries.addAll(tab.apps);
        } else {
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
        }
        List<AppRepo.App> sorted = new ArrayList<>();
        int inputs = cells.size();
        if (rows) {
            buildRows(old, entries, hidden, inputs);
        } else {
            // the tvOS style: the dock and one row under it, the rest in All Apps (Down past the second row)
            int room = dock ? Math.max(0, perPage() - inputs) : Integer.MAX_VALUE;
            for (Object entry : entries) {
                if (room-- <= 0) break;
                if (entry instanceof Folders.Folder) {
                    cells.add(folderCell(old, (Folders.Folder) entry, null));
                } else {
                    sorted.add((AppRepo.App) entry);
                    cells.add(appCell(old, (AppRepo.App) entry, null));
                }
            }
        }
        // the tile in focus is going: focus waits on the page meanwhile, as in leanback's grid, rather
        // than Android handing it to the first view it finds (the sidebar sliding in, the zone changing)
        boolean parked = false;
        if (had != null) for (Cell c : old.values()) if (c.card == had) parked = park();
        // tiles nothing kept: gone from the page
        for (Cell c : old.values()) page.removeView(c);
        int at = -1;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            c.setNameMode(nameMode, dock && rowOf(i) == 0);
            // only the focused tile casts a shadow: two dozen resting shadows cost frames for nothing
            if (!c.card.isFocused()) c.card.setElevation(0f);
            c.setTranslationX(cellX(i));
            c.setTranslationY(cellY(i));
            // on category rows, tiles slid past the left edge are out of sight; everywhere else, all are
            c.setAlpha(rows && i < colIdx.length ? rowAlpha(i) : 1f);
            if (c.getParent() == null) page.addView(c, new FrameLayout.LayoutParams(Ui.dp(TILE_W), WRAP));
            if (c.key().equals(keep)) at = i;
        }
        // that app has gone: the tile now in its place
        if (at < 0) at = Math.max(0, Math.min(place, cells.size() - 1));
        gridLabel.setText(getString(R.string.your_apps_n, sorted.size() + foldered.size()));
        allAppsCount = 0;
        for (Object e : entries) allAppsCount += e instanceof Folders.Folder ? folderApps((Folders.Folder) e).size() : 1;
        if (!dock) gridLabel.setVisibility(rows || homeTab != null ? View.GONE : View.VISIBLE);
        // before the labels: the rows are lit for the tile focus goes back to
        gridAt = at;
        placeRowLabels();
        int lines = rows ? rowInfo.size() : (cells.size() + COLS - 1) / COLS;
        page.getLayoutParams().height = Ui.dp(gridY() + (paged ? Math.min(2, lines) * ROW_H : lines * rowH()) + 40);
        placeDock();
        int pages = paged ? (cells.size() + perPage() - 1) / perPage() : 1;
        pageDots.setCount(dock ? 0 : pages);
        pageDots.setPosition(gridPage);
        // apps removed from the last page: back to the last there is
        if (paged && gridPage > pages - 1) slideTo(Math.max(0, pages - 1));
        page.requestLayout();
        if (!ccOpen && (firstFill || hadFocus || parked || getCurrentFocus() == null)) focusGrid(at);
        else if (!cells.isEmpty()) reveal(contentZone == WIDGETS ? 0 : at);
        if (parked) {
            // the grid took no focus back (its last tile went with focus on it): never left on the holder
            if (parking.isFocused()) landEmpty();
            unpark();
        }
        if (firstFill && !cells.isEmpty()) {
            focusKey = null;
            focusIndex = -1;
            // home arrives showing the tile it comes back to, rather than sliding over to it
            settleNow();
        }
        if (introPending && splash == null) cascade();
        if (!appsReady) {
            appsReady = true;
            appsAt = SystemClock.uptimeMillis();
            progressSplash();
        }
        refreshWidgets(true);
        windowMoved();
    }

    /**
     * A new tile's picture is painted behind the screen ({@link #paintNext}); until it lands it shows
     * the picture of the tile it replaces under {@code key}, if there was one, so nothing goes blank.
     */
    private static void behindScreen(Map<String, Cell> old, String key, Cell cell) {
        cell.card.deferred = true;
        Cell was = old.get(key);
        if (was != null) cell.card.standIn(was.card.art());
    }

    /** A folder's tile; {@code row} its category row, or null on the grid. */
    private Cell folderCell(Map<String, Cell> old, Folders.Folder f, String row) {
        String style = Prefs.tileStyle(this);
        boolean glassTiles = style.equals("glass");
        List<AppRepo.App> inside = folderApps(f);
        StringBuilder sig = new StringBuilder("folder|").append(style).append('|').append(dock).append('|').append(f.name);
        for (AppRepo.App a : inside) sig.append('|').append(System.identityHashCode(a));
        String key = row == null ? f.tag() : row + "|" + f.tag();
        Cell cell = reuse(old, key, sig.toString());
        if (cell == null) {
            cell = new Cell(this, Tiles.folder(inside, !glassTiles), 0xFF9AA6BA, f.name, f.tag(), TILE_W, TILE_H);
            cell.card.setGrowRoom(GAP / 2f);
            cell.sig = sig.toString();
            if (glassTiles) cell.card.glass = backdrop;
            // a pane under the icons: nothing transparent
            cell.card.opaque = !glassTiles;
            behindScreen(old, key, cell);
            cell.card.tvos = style.equals("tvos") || dock;
        }
        cell.row = row;
        cell.setNameMode(Prefs.nameMode(this), false);
        cell.card.sway = !Perf.light(this);
        cell.card.onClick = () -> openFolder(f.id);
        cell.card.onLongClick = this::openMenu;
        return cell;
    }

    /** An app's tile; {@code row} its category row, or null on the grid. */
    private Cell appCell(Map<String, Cell> old, AppRepo.App a, String row) {
        String style = Prefs.tileStyle(this);
        // glass panes tinted in the app's colour, its colour vivid, midnight, or the original
        // banners; your own picture for it in any of them
        String sig = "app|" + style + "|" + dock + "|" + System.identityHashCode(a) + "|" + a.label;
        String key = row == null ? a.pkg : row + "|" + a.pkg;
        Cell cell = reuse(old, key, sig);
        if (cell == null) {
            cell = new Cell(this, artFor(a), a.color, a.label, a.pkg, TILE_W, TILE_H);
            cell.card.setGrowRoom(GAP / 2f);
            cell.sig = sig;
            if (style.equals("glass") && a.art == null) cell.card.glass = backdrop;
            // every look but glass covers the tile (tvOS, original, vivid, midnight), as does your own picture
            cell.card.opaque = cell.card.glass == null && (a.art == null || !a.art.hasAlpha());
            behindScreen(old, key, cell);
            cell.card.tvos = style.equals("tvos") || dock;
        }
        cell.row = row;
        cell.setNameMode(Prefs.nameMode(this), false);
        cell.card.sway = !Perf.light(this);
        // installed since Aura came and not opened yet: a small dot
        cell.card.badge = a.fresh;
        final Card card = cell.card;
        cell.card.onClick = () -> launch(a.launch, card, a.label);
        cell.card.onLongClick = this::openMenu;
        return cell;
    }

    /** Watch's tile: Aura's own, painted in its own light; OK opens the Watch screen, hold OK offers to turn Watch off. */
    private Cell watchCell(Map<String, Cell> old, boolean tvosTiles, String nameMode, boolean calm) {
        String label = getString(R.string.watch), beta = getString(R.string.beta_caps);
        String sig = "watch|" + tvosTiles + "|" + label + "|" + beta;
        Cell cell = reuse(old, WATCH_TAG, sig);
        if (cell == null) {
            cell = new Cell(this, Tiles.watch(label, beta), Tiles.WATCH, label, WATCH_TAG, TILE_W, TILE_H);
            cell.card.setGrowRoom(GAP / 2f);
            cell.sig = sig;
            cell.card.opaque = true;
            behindScreen(old, WATCH_TAG, cell);
            cell.card.tvos = tvosTiles;
            cell.card.onClick = this::openWatch;
            cell.card.onLongClick = this::openMenu;
        }
        cell.setNameMode(nameMode, false);
        cell.card.sway = !calm;
        return cell;
    }

    /** How many tiles at the head of the grid keep their place: the live inputs, then Watch. */
    private int fixedTiles() {
        int n = 0;
        while (n < cells.size() && fixedTile(cells.get(n).tag)) n++;
        return n;
    }

    // ---- tile pictures: painted behind the screen, nearest first, kept near what's on screen ----

    /** Home in sight, onStart to onStop: nothing is painted behind a video. Read on the painter thread too. */
    private volatile boolean started;
    /** The painter thread is at work: one picture at a time, so each next pick follows focus. */
    private boolean painterBusy;
    /** When home was last filled or came back: what isn't on screen waits 300 ms past this and past the last key. */
    private long filledAt;
    /** Apps before this in {@code apps} have had their pictures read back since home last went behind an app. */
    private int readBackFrom;
    private final Runnable paintNext = this::paintNext;
    private final Runnable tidyTiles = this::tidyTiles;

    /** What's on screen moved (a row, the page or its pages): tiles coming into view are painted now, far ones let go once the remote rests. */
    private void windowMoved() {
        paintNext();
        handler.removeCallbacks(tidyTiles);
        handler.postDelayed(tidyTiles, 300);
    }

    /**
     * Hands the painter thread the next picture wanted, nearest first: Top Shelf's cards and the
     * tiles on screen at once; the rows and columns just past them (one on LOW, two on HIGH) after the
     * first frames, once the remote has rested for 300 ms; then the banners and icons let go behind a
     * video, so Find, folders and the switcher don't read them back on the main thread.
     */
    private void paintNext() {
        handler.removeCallbacks(paintNext);
        if (painterBusy || !started || isDestroyed()) return;
        Card next = null;
        int best = Integer.MAX_VALUE;
        for (Card c : shelf.cards) {
            if (c.wantsArt()) {
                next = c;
                best = 0;
                break;
            }
        }
        int keep = Ui.light ? 1 : 2;
        for (int i = 0; best > 0 && i < cells.size(); i++) {
            Card c = cells.get(i).card;
            if (!c.wantsArt()) continue;
            int dr = rowsAway(i), dc = colsAway(i);
            // the nearest row first, then the nearest column; the first on screen ends the search
            if (dr > keep || dc > keep || dr * 8 + dc >= best) continue;
            best = dr * 8 + dc;
            next = c;
        }
        int back = -1;
        for (int j = readBackFrom; next == null && j < apps.size(); j++) {
            if (apps.get(j).gone()) {
                back = j;
                break;
            }
        }
        if (next == null && back < 0) return;
        long wait = Math.max(keyAt, filledAt) + 300 - SystemClock.uptimeMillis();
        if (best > 0 && wait > 0) {
            handler.postDelayed(paintNext, wait);
            return;
        }
        if (next != null) paint(next);
        else readBack(back);
    }

    /** {@code card}'s picture painted on the painter thread at the size it has (or will have), then handed over here. */
    private void paint(final Card card) {
        int[] size = card.size();
        if (size == null) return;
        final Tiles.Painter p = card.painter();
        final int w = size[0], h = size[1];
        final float res = card.res;
        final boolean opaque = card.opaque;
        painterBusy = true;
        tilePainter.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                b = Card.paintBehind(p, w, h, res, opaque);
            } catch (RuntimeException e) {
                Trouble.caught("painting a tile", e);
            } finally {
                final android.graphics.Bitmap out = b;
                handler.post(() -> {
                    painterBusy = false;
                    // home went behind an app meanwhile: what it keeps there was decided without this one
                    if (isDestroyed() || !started) return;
                    if (card.painter() == p && card.wantsArt()) {
                        if (out != null) card.take(out);
                        else card.starve();
                    }
                    paintNext();
                });
            }
        });
    }

    /** The banner and icon of {@code apps[at]}, let go behind a video, read back on the painter thread. */
    private void readBack(int at) {
        final AppRepo.App a = apps.get(at);
        readBackFrom = at + 1;
        painterBusy = true;
        tilePainter.execute(() -> {
            try {
                a.banner();
                a.icon();
            } finally {
                handler.post(() -> {
                    painterBusy = false;
                    paintNext();
                });
            }
        });
    }

    /**
     * Pictures kept only near what's on screen: tiles more than a row or column past it (two on HIGH;
     * pages on the paged grid) let theirs go, and are painted again on the way back. Once the remote rests.
     */
    private void tidyTiles() {
        if (!started) return;
        int keep = Ui.light ? 1 : 2;
        View f = getCurrentFocus();
        for (int i = 0; i < cells.size(); i++) {
            Card c = cells.get(i).card;
            if (c != f && (rowsAway(i) > keep || colsAway(i) > keep)) c.release();
        }
        paintNext();
    }

    /**
     * Light mode, home behind an app (a video, most often): the tiles keep only what home's first screen
     * shows, the rows and columns on screen where it was left, and Top Shelf's cards let theirs go. After
     * the picture the painter may be at, banners and icons go too where Aura's saved copy can bring them
     * back (the switcher's apps keep their icons), and the lifted logos come down to 16. Home's return
     * paints what's missing behind the screen, nearest first.
     */
    private void shed() {
        handler.removeCallbacks(paintNext);
        handler.removeCallbacks(tidyTiles);
        for (int i = 0; i < cells.size(); i++) {
            if (rowsAway(i) > 0 || colsAway(i) > 0) cells.get(i).card.release();
        }
        shelf.release();
        Tiles.cap(16);
        final Set<String> icons = new HashSet<>();
        if (recentCache != null) icons.addAll(recentCache.keySet());
        String last = HomeKeys.lastApp;
        if (last != null) icons.add(last);
        final List<AppRepo.App> all = new ArrayList<>(apps);
        readBackFrom = 0;
        tilePainter.execute(() -> {
            for (AppRepo.App a : all) {
                if (started) return;
                a.letGo(icons.contains(a.pkg));
            }
        });
        handler.removeCallbacks(collect);
        handler.postDelayed(collect, 1000);
    }

    /**
     * What home let go behind an app given back for real: with its services bound, Aura idles there at
     * a priority Android doesn't collect for, so nothing else would.
     */
    private final Runnable collect = () -> {
        if (!started) worker.execute(() -> Runtime.getRuntime().gc());
    };

    /** Rows between tile {@code i}'s and the nearest row on screen, where the page is heading: 0 when its row shows. */
    private int rowsAway(int i) {
        // a page of the paged grid shows both its rows
        if (paged) return 0;
        float screen = root.getHeight() > 0 ? root.getHeight() / Ui.density : 540f;
        float pitch = rowH(), top = gridY() + rowOf(i) * pitch - scrollTarget;
        if (top + TILE_H <= 0) return (int) ((-TILE_H - top) / pitch) + 1;
        if (top >= screen) return (int) ((top - screen) / pitch) + 1;
        return 0;
    }

    /** How far tile {@code i} lies outside what's shown across: columns along its row, pages on the paged grid; 0 on screen. */
    private int colsAway(int i) {
        if (rows && i < rowIdx.length && i < colIdx.length && rowIdx[i] < rowInfo.size()) {
            int first = rowInfo.get(rowIdx[i]).first, col = colIdx[i];
            return col < first ? first - col : Math.max(0, col - (first + COLS - 1));
        }
        return paged ? Math.abs(pageOf(i) - gridPage) : 0;
    }

    // ---- category rows ------------------------------------------------------------------------

    /**
     * Category rows ({@link Categories}): the inputs, then each row with something in it, All apps
     * last. Each row slides on its own when focus goes past its edge; Up and Down keep the same
     * place on screen. The tiles are the grid's, so Hold OK, Top Shelf and opening work the same.
     */
    private boolean rows, rowsDim;
    private int[] rowIdx = new int[0], colIdx = new int[0];
    private final List<RowInfo> rowInfo = new ArrayList<>();
    private final List<TextView> rowLabels = new ArrayList<>();
    private boolean labelsTvos;
    /** All apps' count, the apps inside folders included. */
    private int allAppsCount;
    private static final int ROW_LABEL = 28;

    private static final class RowInfo {
        final String id, title;
        final int start, count;
        /** The first column on screen, and where the slide is now (columns, animated). */
        int first;
        float shift;
        /** Its slide, made the first time it moves (see slideRow). */
        Spring.Glide glide;

        RowInfo(String id, String title, int start, int count) {
            this.id = id;
            this.title = title;
            this.start = start;
            this.count = count;
        }
    }

    private int rowH() {
        return rows ? ROW_H + ROW_LABEL : ROW_H;
    }

    private void buildRows(Map<String, Cell> old, List<Object> entries, List<String> hidden, int inputs) {
        Map<String, Integer> firstBefore = new java.util.HashMap<>();
        for (RowInfo r : rowInfo) {
            // a slide still running would move tiles that belong to other rows by now
            if (r.glide != null) r.glide.stop();
            firstBefore.put(r.id, r.first);
        }
        rowInfo.clear();
        List<Integer> ri = new ArrayList<>(), ci = new ArrayList<>();
        // the Aura style gives the inputs a row of their own; in the tvOS style they lead the dock, as on the grid
        int lead = dock ? inputs : 0;
        if (inputs > 0 && !dock) {
            rowInfo.add(new RowInfo("inputs", getString(R.string.cat_inputs), 0, inputs));
            for (int i = 0; i < inputs; i++) {
                ri.add(0);
                ci.add(i);
            }
        }
        List<String> order = AppRepo.order(prefs), installed = new ArrayList<>();
        for (AppRepo.App a : apps) if (!hidden.contains(a.pkg)) installed.add(a.pkg);
        for (Categories.Cat k : Categories.all(this)) {
            if (k.hidden) continue;
            int start = cells.size();
            if (k.id.equals(Categories.ALL)) {
                for (Object e : entries) {
                    cells.add(e instanceof Folders.Folder ? folderCell(old, (Folders.Folder) e, k.id) : appCell(old, (AppRepo.App) e, k.id));
                }
            } else {
                for (AppRepo.App a : categoryApps(k, order, installed)) cells.add(appCell(old, a, k.id));
            }
            int n = cells.size() - start;
            // a row with nothing in it isn't shown
            if (n == 0) continue;
            if (lead > 0) {
                // the dock: the inputs first, then this row's apps
                start = 0;
                n += lead;
                lead = 0;
            }
            RowInfo r = new RowInfo(k.id, Categories.name(this, k), start, n);
            Integer was = firstBefore.get(k.id);
            r.first = was == null ? 0 : Math.max(0, Math.min(was, Math.max(0, n - COLS)));
            r.shift = r.first;
            for (int j = 0; j < n; j++) {
                ri.add(rowInfo.size());
                ci.add(j);
            }
            rowInfo.add(r);
        }
        if (lead > 0) {
            // no apps at all: the inputs alone make the dock
            rowInfo.add(new RowInfo("inputs", getString(R.string.cat_inputs), 0, lead));
            for (int i = 0; i < lead; i++) {
                ri.add(0);
                ci.add(i);
            }
        }
        rowIdx = new int[ri.size()];
        colIdx = new int[ci.size()];
        for (int i = 0; i < ri.size(); i++) {
            rowIdx[i] = ri.get(i);
            colIdx[i] = ci.get(i);
        }
    }

    /** A label above each row, where the grid's "Your apps" sits for the first. */
    private void placeRowLabels() {
        int n = rows ? rowInfo.size() : 0;
        // the tvOS style titles its rows as Apple TV does (a plain name, semibold, no count); the Aura
        // style in its small capitals, as "Your apps"
        if (labelsTvos != dock) {
            for (TextView t : rowLabels) page.removeView(t);
            rowLabels.clear();
            labelsTvos = dock;
        }
        while (rowLabels.size() < n) {
            TextView t = dock ? Ui.text(this, 14, Ui.semibold, 0xE6FFFFFF) : Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.26f);
            if (dock) Ui.lift(t, 8);
            page.addView(t, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, 0, 0, 0, 0));
            rowLabels.add(t);
        }
        for (int i = 0; i < rowLabels.size(); i++) {
            TextView t = rowLabels.get(i);
            if (i >= n) {
                t.setVisibility(View.GONE);
                continue;
            }
            RowInfo r = rowInfo.get(i);
            // the tvOS dock wears no label, as on Apple TV
            t.setVisibility(dock && i == 0 ? View.GONE : View.VISIBLE);
            if (dock) t.setText(r.title);
            else t.setText(r.id.equals(Categories.ALL) ? getString(R.string.your_apps_n, allAppsCount) : r.title + "  ·  " + r.count);
            t.setTranslationX(Ui.dpf(pageX()));
            t.setTranslationY(Ui.dpf(gridY() + i * rowH() - (dock ? 28 : 24)));
        }
        dimRows(zone == GRID && gridAt < rowIdx.length ? rowIdx[gridAt] : -1);
    }

    /** Each row's light: 1 where focus is, Ui.DIM_OTHER_ROWS on the others while rows recede (dimRows). */
    private float[] rowLit = new float[0], litFrom = new float[0], litTo = new float[0];
    private ValueAnimator litAnim;

    /** Rows other than the one you're on recede a little (Settings, Home screen), at once; all lit off the rows. */
    private void dimRows(int on) {
        dimRows(on, false);
    }

    /**
     * The receding is the tiles' own alpha, so nothing is drawn again. {@code eased}, it takes 250 ms
     * as Android TV's rows do, except in light mode, where it changes at once.
     */
    private void dimRows(int on, boolean eased) {
        if (!rows) return;
        int n = rowInfo.size();
        if (rowLit.length != n) {
            rowLit = new float[n];
            litFrom = new float[n];
            litTo = new float[n];
            java.util.Arrays.fill(rowLit, 1f);
            java.util.Arrays.fill(litTo, 1f);
            eased = false;
        }
        boolean changed = false;
        for (int r = 0; r < n; r++) {
            float to = rowsDim && on >= 0 && r != on ? Ui.DIM_OTHER_ROWS : 1f;
            changed |= litTo[r] != to;
            litTo[r] = to;
        }
        // a move along the same row
        if (eased && !changed) return;
        if (!eased || Ui.light) {
            if (litAnim != null) litAnim.cancel();
            System.arraycopy(litTo, 0, rowLit, 0, n);
            for (int r = 0; r < n; r++) lightRow(r);
            return;
        }
        System.arraycopy(rowLit, 0, litFrom, 0, n);
        if (litAnim == null) {
            litAnim = ValueAnimator.ofFloat(0f, 1f);
            litAnim.setDuration(250);
            litAnim.setInterpolator(Ui.DECEL);
            litAnim.addUpdateListener(a -> {
                float k = a.getAnimatedFraction();
                for (int r = 0; r < rowLit.length; r++) {
                    if (litFrom[r] == litTo[r]) continue;
                    rowLit[r] = litFrom[r] + (litTo[r] - litFrom[r]) * k;
                    lightRow(r);
                }
            });
        }
        litAnim.cancel();
        litAnim.start();
    }

    /** Row {@code r}'s tiles and label at its light. */
    private void lightRow(int r) {
        if (r >= rowInfo.size()) return;
        RowInfo row = rowInfo.get(r);
        for (int k = row.start; k < row.start + row.count && k < cells.size(); k++) cells.get(k).setAlpha(rowAlpha(k));
        if (r < rowLabels.size()) rowLabels.get(r).setAlpha(1f - (1f - rowLit[r]) / (1f - Ui.DIM_OTHER_ROWS) * 0.45f);
    }

    /** Slides a row so column {@code col} is on screen; those slid past the left edge fade out. */
    private void revealInRow(int i) {
        RowInfo r = rowInfo.get(rowIdx[i]);
        int col = colIdx[i], first = r.first;
        if (col < first) first = col;
        else if (col >= first + COLS) first = col - COLS + 1;
        int way = Integer.signum(first - r.first);
        r.first = first;
        float to = first;
        // held: aimed ahead by what the spring trails a steady target, so the row keeps pace and the
        // tile in focus stays on screen; settle() brings it to a whole column once the key is let go
        if (held && way != 0) to = Math.max(0f, Math.min(Math.max(0, r.count - COLS), first + way * holdLead));
        if (to != r.shift || r.glide != null && r.glide.moving()) slideRow(r, to);
    }

    /** Row {@code r} glides to column {@code to}, retargeted mid-flight (see Spring.Glide). */
    private void slideRow(RowInfo r, float to) {
        float pitch = Ui.dpf(TILE_W + GAP);
        if (r.glide == null) {
            final RowInfo row = r;
            r.glide = new Spring.Glide(Spring.row(), ROW_MS, 1f / pitch, r.shift, v -> {
                // the rows were built again meanwhile: this slide belongs to none of them
                if (!rowInfo.contains(row)) {
                    if (row.glide != null) row.glide.stop();
                    return;
                }
                row.shift = v;
                for (int k = row.start; k < row.start + row.count && k < cells.size(); k++) {
                    Cell c = cells.get(k);
                    c.setTranslationX(placeX(k, c));
                    c.setAlpha(rowAlpha(k));
                    if (c.card.glass != null) c.card.invalidate();
                }
            });
        }
        r.glide.to(to, Ui.scrollFloorMs(root, Math.abs(to - r.glide.value()) * pitch, false));
    }

    /** Every row, the page and its pages straight to where they're heading. */
    private void settleNow() {
        for (int r = 0; r < rowInfo.size(); r++) if (rowInfo.get(r).glide != null) rowInfo.get(r).glide.finish();
        if (scroll != null) scroll.finish();
        if (pageGlide != null) pageGlide.finish();
    }

    /**
     * How much of tile {@code k} shows as its row slides: those past the left edge fade out; in the
     * tvOS style those past the dock's right edge too, as the glass ends there. Times its row's light.
     */
    private float rowAlpha(int k) {
        if (k < 0 || k >= rowIdx.length || k >= colIdx.length || rowIdx[k] >= rowInfo.size()) return 1f;
        int r = rowIdx[k];
        float shift = rowInfo.get(r).shift;
        float a = Math.min(1f, 1f - (shift - colIdx[k]));
        if (dock) a = Math.min(a, 1f - (colIdx[k] - (shift + COLS - 1)));
        return Math.max(0f, Math.min(1f, a)) * (r < rowLit.length ? rowLit[r] : 1f);
    }

    /** Up or Down from {@code slot} (columns from the row's left edge on screen) to row {@code to}. */
    private int rowTarget(int to, int slot) {
        RowInfo t = rowInfo.get(to);
        return t.start + Math.max(0, Math.min(t.count - 1, t.first + slot));
    }

    private void rowsKey(int code) {
        int r = rowIdx[gridAt], col = colIdx[gridAt];
        RowInfo row = rowInfo.get(r);
        int slot = col - row.first;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) focusGrid(gridAt - 1);
                else if (!held && !scrollingV()) enterSide();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < row.count - 1) focusGrid(gridAt + 1);
                else if (!held) bounce(false);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (r > 0) focusGrid(rowTarget(r - 1, slot));
                else if (!held && shelfOfFocusedApp()) {
                    shelf.cards.get(Math.min(shelf.cards.size() - 1, Math.max(0, slot) * shelf.cards.size() / COLS)).requestFocus();
                } else if (!held && !widgets.isEmpty()) {
                    lastCol = Math.max(0, Math.min(COLS - 1, slot));
                    focusWidget(widgetForCol(lastCol));
                } else if (!held && dock) {
                    // nothing above the dock but the photograph: up to Control Center, top right
                    topCc.requestFocus();
                } else if (!held) {
                    setPhoto(true);
                }
                break;
            default:
                if (r < rowInfo.size() - 1) focusGrid(rowTarget(r + 1, slot));
                else downPastEnd();
                break;
        }
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
    /** The slide between pages, on the page spring (made the first time it moves). */
    private Spring.Glide pageGlide;
    private PageDots pageDots;

    private int perPage() {
        return 2 * COLS;
    }

    private int rowOf(int i) {
        if (rows) return i < rowIdx.length ? rowIdx[i] : 0;
        return paged ? (i % perPage()) / COLS : i / COLS;
    }

    private int pageOf(int i) {
        return paged ? i / perPage() : 0;
    }

    private float pageW() {
        return root.getWidth() > 0 ? root.getWidth() / Ui.density : 960f;
    }

    private float cellX(int i) {
        if (rows && i < colIdx.length && rowIdx[i] < rowInfo.size()) return Ui.dpf(pageX() + (colIdx[i] - rowInfo.get(rowIdx[i]).shift) * (TILE_W + GAP));
        return Ui.dpf(pageX() + pageOf(i) * pageW() + (i % COLS) * (TILE_W + GAP) - pageShift);
    }

    private float cellY(int i) {
        return Ui.dpf(gridY() + rowOf(i) * rowH());
    }

    /** Brings tile {@code i} into view: its page slides in, or the page scrolls to its row. */
    private void reveal(int i) {
        if (rows && i < rowIdx.length) {
            revealInRow(i);
            scrollTo(scrollFor(rowIdx[i]));
        } else if (!paged) {
            scrollTo(scrollFor(rowOf(i)));
        } else {
            // the tvOS style's dock sits low: its second row lifts the page just enough to be seen whole
            scrollTo(dock && rowOf(i) > 0 ? Math.max(0, gridY() + ROW_H + TILE_H + 44 - 540) : 0);
            slideTo(pageOf(i));
        }
        windowMoved();
    }

    /** Slides the dock to page {@code p}: every page moves together, the glass following the photo. */
    private void slideTo(int p) {
        float to = p * pageW();
        if (p == gridPage && (pageGlide == null || !pageGlide.moving()) && pageShift == to) return;
        gridPage = p;
        if (pageGlide == null) pageGlide = new Spring.Glide(Spring.smooth(), SMOOTH_MS, 1f / Ui.density, pageShift, v -> {
            pageShift = v;
            for (int i = 0; i < cells.size(); i++) cells.get(i).setTranslationX(placeX(i, cells.get(i)));
            if (pageDots != null) pageDots.setPosition(v / pageW());
            // like the vertical scroll: panes on screen repaint every 6dp and where they stop (LOW: settle only)
            if (Ui.light ? pageGlide.moving() : pageGlide.moving() && Math.abs(v - pageGlassAt) < 6) return;
            pageGlassAt = v;
            float w = root.getWidth();
            for (int i = 0; i < cells.size(); i++) {
                Cell c = cells.get(i);
                float x = c.getTranslationX();
                if (c.card.glass != null && x + c.getWidth() >= -Ui.dp(32) && x <= w + Ui.dp(32)) c.card.invalidate();
            }
        });
        pageGlide.to(to, Ui.scrollFloorMs(root, Math.abs(to - pageGlide.value()) * Ui.density, false));
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
    private void openFromSwitcher(Intent intent, String label, Bundle zoom) {
        try {
            Bundle opts = zoom != null ? zoom : ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.hold).toBundle();
            startActivity(intent, opts);
            String pkg = intent.getPackage() != null ? intent.getPackage()
                    : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
            Habits.opened(getApplicationContext(), pkg);
            // Most used counts a launch from the switcher as it does one from a tile
            Categories.launched(getApplicationContext(), pkg);
            // should the app never come up, the switcher puts itself away
            handler.removeCallbacks(launchGaveUp);
            handler.postDelayed(launchGaveUp, 12_000);
        } catch (RuntimeException e) {
            // not on this TV (a settings page Fire OS doesn't have), not allowed, or refused
            if (!(e instanceof ActivityNotFoundException)) Trouble.caught("opening " + label, e);
            hud(R.drawable.ic_info, getString(R.string.couldnt_open, label));
            endLaunch();
        }
    }

    /**
     * Android 12 and later ignore a custom animation into another app's task and fade instead: there
     * the system zooms the app out of the card it was picked from. Null below 12 or for no card.
     */
    private Bundle zoomFrom(View card) {
        if (Build.VERSION.SDK_INT < 31 || card == null) return null;
        android.graphics.Rect r = new android.graphics.Rect();
        if (!card.getGlobalVisibleRect(r) || r.isEmpty()) return null;
        card.getLocationInWindow(loc);
        return ActivityOptions.makeScaleUpAnimation(card, r.left - loc[0], r.top - loc[1], r.width(), r.height()).toBundle();
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

    /** {@code label}: what's opened, for "Couldn't open ..."; null for something with no name of its own. */
    private void launch(Intent intent, View from, String label) {
        // the TV's own settings (Wi-Fi, Bluetooth, display, the whole Settings app): on Fire OS 8 they
        // pass through Amazon's home on the way, which the Home button service mustn't answer
        String action = intent.getAction();
        if (action != null && action.startsWith("android.settings.")) {
            HomeKeys.letTvHomePass();
            intent = fireSettings(this, intent);
        }
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(intent, opts);
            recede();
            String pkg = intent.getPackage() != null ? intent.getPackage()
                    : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
            Habits.opened(getApplicationContext(), pkg);
            Categories.launched(getApplicationContext(), pkg);
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
            if (!(e instanceof ActivityNotFoundException)) Trouble.caught("opening " + (label != null ? label : intent.getAction()), e);
            hud(R.drawable.ic_info, label != null ? getString(R.string.couldnt_open, label) : getString(R.string.couldnt_open_that));
        }
    }

    private static final android.view.animation.LinearInterpolator LINEAR = new android.view.animation.LinearInterpolator();
    /** The black veil home recedes under (see recede), made the first time; and whether home is stepped back now. */
    private View launchVeil;
    private boolean receded;
    private final Runnable comeBack = () -> {
        if (resumed) comeForward(true);
    };

    /**
     * Strong TVs: home steps back 3 % under a dark veil while the system zooms the app in, as
     * Launcher3 does (0.97 over 350 ms, the veil over 217 ms), on one hardware layer only while it
     * moves. Light mode adds nothing: the TV's attention goes to the app that's starting.
     */
    private void recede() {
        if (Ui.light || !resumed || photo || ccOpen || home.getVisibility() != View.VISIBLE) return;
        // a live input's picture is a surface: it can't be drawn into the layer home recedes on
        stopPreview();
        if (launchVeil == null) {
            launchVeil = new View(this);
            launchVeil.setBackgroundColor(0xFF000000);
            launchVeil.setAlpha(0f);
            launchVeil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(launchVeil, root.indexOfChild(home) + 1, match());
        }
        receded = true;
        home.animate().scaleX(0.97f).scaleY(0.97f).setStartDelay(0).setDuration(350).setInterpolator(Ui.EMPHASIZED).withLayer().start();
        launchVeil.setVisibility(View.VISIBLE);
        launchVeil.animate().alpha(0.35f).setStartDelay(0).setDuration(217).setInterpolator(LINEAR).start();
        // an app that never comes up leaves home as it was
        handler.removeCallbacks(comeBack);
        handler.postDelayed(comeBack, 1500);
    }

    /** Home back to its full size: gently while it's on screen, at once out of sight. */
    private void comeForward(boolean gently) {
        handler.removeCallbacks(comeBack);
        if (!receded) return;
        receded = false;
        if (gently) {
            home.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(250).setInterpolator(Ui.EMPHASIZED).withLayer().start();
            launchVeil.animate().alpha(0f).setStartDelay(0).setDuration(250).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> launchVeil.setVisibility(View.GONE)).start();
            return;
        }
        // a scale animation of its own replaces the receding one; whatever else home is doing carries on
        home.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(0).start();
        home.setScaleX(1f);
        home.setScaleY(1f);
        launchVeil.animate().cancel();
        launchVeil.setAlpha(0f);
        launchVeil.setVisibility(View.GONE);
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
        // the Smart Stack isn't on the home screen (the tvOS style's dock alone, or hidden): its
        // round of history, artwork and the TV's own readings would only compete with an app starting
        final boolean stackOn = widgetsOnHome();
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
            boolean full = habits && stackOn && !list.isEmpty() && !Prefs.hiddenWidgets(app).contains(WIDGET_IDS[1]);
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
        tdData.setText(getString(R.string.data_today, mb < 1000 ? Math.round(mb) + " MB" : String.format(Locale.getDefault(), "%.2f GB", mb / 1024)));
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
        String title = "";
        if (next > 0) {
            long min = (next - now) / 60_000;
            // a whole sentence for each language, not English words strung together
            title = min < 180 ? getString(up ? R.string.sunset_in : R.string.sunrise_in, Words.minutes(min))
                    : getString(up ? R.string.sunset_at : R.string.sunrise_at, tf.format(new Date(next)).toUpperCase(Locale.getDefault()));
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
            tdSub.setText(getString(R.string.usage_access_is_off));
        } else {
            long min = today / 60_000;
            tdValue.setText(min < 60 ? min + "m" : (min / 60) + "h " + (min % 60) + "m");
            AppRepo.App a = top == null ? null : find(top);
            tdSub.setText(a != null ? getString(R.string.in_apps_mostly, a.label) : getString(R.string.in_apps_so_far));
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        Compat.svc(this, ActivityManager.class).getMemoryInfo(mi);
        memory.set(mi.totalMem > 0 ? mi.availMem / (float) mi.totalMem : 0f);
        tdMem.setText(getString(R.string.mb_free, mi.availMem / (1024 * 1024)));
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
                + "|" + Prefs.picks(c) + "|" + apps + "|" + Taste.changes + "|" + Profiles.current(c);
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
        if (Prefs.picks(c)) {
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
        Set<String> titles = new HashSet<>();
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
            if (!titles.add(key)) continue;
            SmartStack.Slide s = new SmartStack.Slide();
            AppRepo.App owner = it.pkg == null ? null : find(it.pkg);
            String app = owner != null ? "  ·  " + owner.label.toUpperCase(Locale.getDefault()) : "";
            Fresh.Show fresh = x == null ? null : sameShow(it.title, x.shows);
            s.kicker = (fresh != null ? getString(R.string.new_episode_out) : watching ? getString(R.string.continue_watching_caps) : it.kicker != null ? it.kicker : getString(R.string.for_you)) + app;
            s.title = it.title;
            s.sub = fresh != null && !fresh.episode.isEmpty() ? fresh.episode : it.sub;
            s.art = it.art;
            s.progress = it.progress;
            s.intent = it.intent;
            s.label = it.title;
            s.fresh = fresh != null;
            s.key = key;
            s.note = itemNote(it);
            s.decline = watching ? getString(R.string.hide_from_the_stack) : getString(R.string.not_interested);
            // what you're watching stays a little longer than a suggestion
            s.dwellMs = watching ? 9_000 : 7_000;
            if (owner != null) {
                s.color = owner.color;
                s.icon = owner.icon();
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
                    : (c.get(Calendar.HOUR) == 0 ? 12 : c.get(Calendar.HOUR)) + " " + new java.text.DateFormatSymbols().getAmPmStrings()[c.get(Calendar.AM_PM)];
            out.add(appSlide(usual, getString(R.string.your_hour_usual, hour.toUpperCase(Locale.getDefault())), getString(R.string.you_tend_to_open_it)));
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
            if (!titles.add(key)) continue;
            SmartStack.Slide s = new SmartStack.Slide();
            String label = owner.label.toUpperCase(Locale.getDefault());
            s.kicker = known ? getString(R.string.new_episode_out_on, label) : getString(R.string.new_today_on, label);
            s.title = f.name;
            s.sub = f.episode.isEmpty() ? getString(R.string.out_now_on, owner.label) : f.episode;
            s.art = x.art.get(i);
            s.color = owner.color;
            s.icon = owner.icon();
            s.fresh = true;
            s.intent = owner.launch;
            s.label = owner.label;
            s.key = key;
            s.decline = getString(R.string.not_interested);
            if (known) {
                out.add(0, s);
            } else {
                others++;
                out.add(s);
            }
        }
        AppRepo.App last = suggest && recent != null && HomeKeys.allowed(this, (String) recent[0])
                && !taste.declined(Taste.app((String) recent[0])) ? find((String) recent[0]) : null;
        if (last != null && suggested.add(last.pkg)) out.add(appSlide(last, getString(R.string.jump_back_in), getString(R.string.opened_ago, Habits.ago(now - (Long) recent[1]))));
        AppRepo.App most = suggest && top != null && watchable(top) && HomeKeys.allowed(this, top)
                && !taste.declined(Taste.app(top)) ? find(top) : null;
        if (most != null && suggested.add(most.pkg)) out.add(appSlide(most, getString(R.string.most_watched_today), getString(R.string.where_most_of_todays_screen)));
        List<SmartStack.Slide> picks = new ArrayList<>();
        for (int i = 0; x != null && i < x.picks.size(); i++) {
            Picks.Pick p = x.picks.get(i);
            String pkey = Continue.key(p.name);
            if (!titles.add(pkey)) continue;
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = (i == 0 ? getString(R.string.tonights_pick_caps) : getString(R.string.also_tonight)) + "  ·  " + Picks.why(this, p.why);
            s.title = p.name;
            s.sub = p.line;
            s.art = x.pickArt.get(i);
            s.color = 0xFFE8B04A;
            s.intent = Picks.watch(this, p);
            s.label = p.name;
            s.dwellMs = 6_000;
            s.key = pkey;
            s.note = p.about != null && !p.about.isEmpty()
                    ? (p.line != null && !p.line.isEmpty() ? p.line + "\n" + p.about : p.about) : p.line;
            s.decline = getString(R.string.not_interested);
            picks.add(s);
        }
        // the TV itself, before anything to watch: a remote about to go flat, and storage running out
        // (a full TV slows down). Each can be put off with hold OK until its reading changes
        for (int i = 0; remotes != null && i < remotes.size(); i++) {
            String[] d = remotes.get(i);
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = getString(R.string.battery_low);
            s.title = d[0];
            s.sub = getString(R.string.battery_left, d[1]);
            s.color = 0xFFE8A33A;
            s.icon = getDrawable(R.drawable.ic_bluetooth);
            try {
                s.progress = Math.max(0.03f, Math.min(1f, Integer.parseInt(d[1]) / 100f));
            } catch (NumberFormatException ignored) {
            }
            s.key = "battery:" + d[2] + ":" + Integer.parseInt(d[1]) / 5;
            s.decline = getString(R.string.not_now);
            s.action = this::openBluetooth;
            s.label = d[0];
            s.dwellMs = 8_000;
            out.add(0, s);
        }
        if (freeBytes >= 0 && freeBytes < 600L * 1024 * 1024) {
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = getString(R.string.storage_almost_full);
            s.title = getString(R.string.mb_free, freeBytes >> 20);
            s.sub = getString(R.string.a_full_tv_slows_down);
            s.color = 0xFFE06A4A;
            s.icon = getDrawable(R.drawable.ic_info);
            s.progress = Math.max(0.65f, Math.min(0.98f, 1f - freeBytes / (2.5f * 600f * 1024f * 1024f)));
            s.key = "storage:" + (freeBytes >> 20) / 100;
            s.decline = getString(R.string.not_now);
            s.intent = new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Android's storage page uninstalls and clears apps: a kids' profile asks for the PIN first
            final Intent room = s.intent;
            s.action = () -> guarded(() -> launch(room, fyW, null));
            out.add(Math.min(out.size(), remotes != null ? remotes.size() : 0), s);
        }
        for (int i = out.size() - 1; i >= 0; i--) if (out.get(i).decline != null && getString(R.string.not_now).equals(out.get(i).decline) && taste.declined(out.get(i).key)) out.remove(i);
        // nine cards at most, so the dots stay a glance: the last suggestions go first, and
        // tonight's picks (dealt last) always keep their places
        while (out.size() + picks.size() > 9 && !out.isEmpty()) out.remove(out.size() - 1);
        out.addAll(picks);
        if (out.isEmpty()) {
            for (Cell cell : cells) {
                AppRepo.App a = watchable(cell.tag) ? find(cell.tag) : null;
                if (a != null) {
                    out.add(appSlide(a, getString(R.string.start_with), getString(R.string.first_in_your_apps)));
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
    static Intent fireSettings(Context c, Intent wanted) {
        if (!Trouble.fireTv(c)) return wanted;
        android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(wanted, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        String name = ri != null && ri.activityInfo != null ? ri.activityInfo.name : null;
        boolean standIn = name == null
                // Amazon's stand-ins bounce through its home; but its Accessibility one does open the page
                || (name.contains("Dummy") || name.contains("CTS")) && !Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(wanted.getAction())
                // the launcher's own Settings shortcut goes back to Amazon's home
                || "com.amazon.tv.launcher.ui.SettingsActivity".equals(name)
                // pages only Amazon's own apps may open would throw
                || ri.activityInfo.permission != null && c.checkCallingOrSelfPermission(ri.activityInfo.permission) != android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!standIn) return wanted;
        Intent fire = new Intent("amazon.intent.action.MAINSETTINGS")
                .setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return Compat.resolves(c, fire) ? fire : wanted;
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

    /** A few lines about a show or episode for its hold-OK menu: time left, episode line, and synopsis. */
    private String itemNote(Continue.Item it) {
        if (it == null) return null;
        StringBuilder sb = new StringBuilder();
        if (it.leftMin > 0) sb.append(getString(R.string.time_left, Words.minutes(it.leftMin)));
        if (it.sub != null && !it.sub.isEmpty() && (sb.length() == 0 || !it.sub.contains("left"))) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(it.sub);
        }
        if (it.desc != null && !it.desc.isEmpty() && !it.desc.equals(it.sub) && !it.desc.equals(it.title)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(it.desc);
        }
        return sb.length() > 0 ? sb.toString() : null;
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
        s.decline = getString(R.string.dont_suggest, a.label);
        s.kicker = kicker;
        s.title = a.label;
        s.sub = sub;
        s.mark = Tiles.glass(a);
        s.color = a.color;
        s.icon = a.icon();
        s.intent = a.launch;
        s.label = a.label;
        s.dwellMs = 6_000;
        return s;
    }

    /** Settings and the like are tools, not something to suggest watching. */
    private static boolean watchable(String pkg) {
        return !pkg.contains("settings") && !fixedTile(pkg) && !pkg.equals("com.android.vending");
    }

    // ---- focus -------------------------------------------------------------------------------

    private void focusSide(int i) {
        if (side.isEmpty()) return;
        sideAt = Math.max(0, Math.min(side.size() - 1, i));
        // the zone is set here as well as on the focus change: in touch mode no focus change
        // comes, and the next key would have moved from wherever the zone last was
        zone = SIDE;
        side.get(sideAt).requestFocus();
    }

    private void focusWidget(int i) {
        if (widgets.isEmpty()) {
            focusGrid(gridAt);
            return;
        }
        widgetAt = Math.max(0, Math.min(widgets.size() - 1, i));
        zone = contentZone = WIDGETS;
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
        int lines = this.rows ? rowInfo.size() : (cells.size() + COLS - 1) / COLS;
        float screen = root.getHeight() > 0 ? root.getHeight() / Ui.density : 540f;
        float end = gridY() + (lines - 1) * rowH() + TILE_H + 44 - screen; // last row and its name in view
        // when everything nearly fits, stay put: a nudge of a few dp isn't worth leaving the widgets for
        if (end < 28) return 0;
        return Math.max(0, Math.min(gridY() + row * rowH() - FOCUS_LINE, end));
    }

    /**
     * The page's travel up and down (px): its scroll on the page spring, plus the give at the end of
     * the apps on a springier one of its own; each made the first time it moves.
     */
    private Spring.Glide scroll;
    private Spring.Mover give, giveX;
    private float scrollY, giveY, giveOut, giveOutX;
    /** The tile giving at the end of its row, and its place among the cells then. */
    private Cell giveCell;
    private int giveAt;
    private float scrollBlurY;

    /** Where cell {@code k} sits across: its column, plus the give if it's the tile giving at its row's end. */
    private float placeX(int k, Cell c) {
        return cellX(k) + (c == giveCell && giveX != null ? giveX.value() : 0f);
    }

    /** The page still well on its way up or down: sideways presses stay among the tiles, as in Android TV's own rows. */
    private boolean scrollingV() {
        return scroll != null && scroll.moving() && Math.abs(scroll.target() - scroll.value()) > Ui.dpf(24);
    }

    private void placePage() {
        float ty = scrollY + giveY;
        page.setTranslationY(ty);
        positionHomeTabs(ty);
        // the glass samples the photo at its own position, so visible panes repaint as they travel
        repaintScrollGlass(ty, (scroll == null || !scroll.moving()) && (give == null || !give.moving()));
    }

    /** Moves the centered category bar smoothly upward with scroll until it docks cleanly at the header. */
    private void positionHomeTabs(float ty) {
        if (homeTabBar == null || dock) return;
        float rest = Ui.dpf(TABS_REST_Y), dockY = Ui.dpf(TABS_DOCK_Y);
        float raw = rest + ty;
        float y = Math.max(dockY, raw);
        homeTabBar.setTranslationY(y);
        float dockFrac = Ui.clamp((rest - raw) / Math.max(1f, rest - dockY), 0f, 1f);
        if (homeTabs != null) homeTabs.setDocked(dockFrac);
        if (stickyTopScrim != null && homeTabs != null && homeTabs.getVisibility() == View.VISIBLE) {
            stickyTopScrim.setAlpha(dockFrac);
        } else if (stickyTopScrim != null) {
            // Even without category tabs, a soft top scrim cushions scrolling tiles below the edge.
            stickyTopScrim.setAlpha(dockFrac * 0.72f);
        }
    }

    /**
     * The glass samples the photo at its own place, so panes repaint as the page travels: every 6dp
     * (under one texel of the blur, so no step shows) and once more where it stops, and only the
     * panes on screen.
     */
    private void repaintScrollGlass(float ty, boolean done) {
        if (!done && (Ui.light || Math.abs(ty - scrollBlurY) < Ui.dpf(6))) return;
        scrollBlurY = ty;
        int h = root.getHeight() > 0 ? root.getHeight() : Ui.dp(540);
        for (int i = 0; i < widgets.size(); i++) if (widgets.get(i).getAlpha() > 0f) widgets.get(i).invalidate();
        if (dock) dockGlass.invalidate();
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (c.card.glass != null) {
                // cells are placed by translation: getY() is where one sits on the page
                float top = c.getY() + c.card.getTop() + ty;
                if (top + c.card.getHeight() >= -Ui.dp(32) && top <= h + Ui.dp(32)) c.card.invalidate();
            }
        }
    }

    /**
     * Down past the last row: in the tvOS style All Apps rises from below; in the Aura style the
     * page gives a little. A held key stops at the last row, so scrolling down fast never opens it.
     */
    private void downPastEnd() {
        if (held) return;
        if (dock) openAllApps();
        else bounce(true);
    }

    /**
     * The end of the apps: the page (or the tile at a row's end) gives a little, then springs back,
     * so the edge is felt rather than met with nothing. The page's give rides on its scroll.
     */
    private void bounce(boolean vertical) {
        if (vertical) {
            if (give != null && give.moving()) return;
            if (give == null) give = new Spring.Mover(new Spring(0.30, 0.45), 1f, 0f, v -> {
                giveY = v;
                // pushed out far enough: back it springs, carrying on a touch first
                if (giveOut != 0f && Math.abs(v) >= Math.abs(giveOut) * 0.85f) {
                    giveOut = 0f;
                    give.to(0f);
                }
                placePage();
            });
            giveOut = Ui.dpf(-16);
            give.to(giveOut);
            return;
        }
        // the whole tile gives, on top of its place in the row: the card's own moves are its focus
        if (giveX != null && giveX.moving()) return;
        giveAt = gridAt;
        giveCell = cells.get(gridAt);
        if (giveX == null) giveX = new Spring.Mover(new Spring(0.30, 0.45), 1f, 0f, v -> {
            if (giveOutX != 0f && Math.abs(v) >= Math.abs(giveOutX) * 0.85f) {
                giveOutX = 0f;
                giveX.to(0f);
            }
            if (giveAt < cells.size() && cells.get(giveAt) == giveCell) giveCell.setTranslationX(placeX(giveAt, giveCell));
        });
        giveOutX = Ui.dpf(10);
        giveX.to(giveOutX);
    }

    private void scrollTo(float target) {
        if (target == scrollTarget) return;
        scrollTarget = target;
        float to = -Ui.dpf(target);
        if (scroll == null) scroll = new Spring.Glide(Spring.smooth(), SMOOTH_MS, 1f, scrollY, v -> {
            scrollY = v;
            placePage();
        });
        scroll.to(to, Ui.scrollFloorMs(root, Math.abs(to - scroll.value()), true));
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
        windowMoved();
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

    /**
     * Nothing on home at all (every app hidden, an empty kids' profile): the remote must still
     * reach somewhere, or it's stuck with nowhere to go.
     */
    private void landEmpty() {
        if (!widgets.isEmpty()) focusWidget(widgetAt);
        else if (dock) topCc.requestFocus();
        else enterSide();
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

    /** Where focus waits while the tile that had it is rebuilt (made in buildUi, focusable only then). */
    private View parking;

    /** Focus onto the page's holder, which draws nothing, as leanback's grid parks it on itself. */
    private boolean park() {
        parking.setFocusable(true);
        parking.setFocusableInTouchMode(true);
        if (parking.requestFocus()) return true;
        unpark();
        return false;
    }

    /** The holder takes focus no more, once focus has left it: taken from it, Android would hand focus to the first view it finds. */
    private void unpark() {
        if (parking.isFocused()) return;
        parking.setFocusableInTouchMode(false);
        parking.setFocusable(false);
    }

    private void onFocusMoved(View n) {
        if (n == null || ccOpen) return;
        // waiting on the page while tiles are rebuilt: as far as home goes, nothing has moved
        if (n == parking) return;
        if (parking.isFocusable()) unpark();
        glints = 0;
        // the card you're looking at stays put; in light mode, so does the stack of a home left alone
        stack.hold(n == fyW || racked && Perf.light(this));
        int i = side.indexOf(n);
        // the tvOS style's sidebar slides in only while it has focus
        if (dock && !sideHidden) sideShown(i >= 0, true);
        if (dock) topShown(tops.contains(n), true);
        if (tops.contains(n)) {
            zone = TOP;
            n.getLocationInWindow(loc);
            glide(loc[0] + n.getWidth() / 2f, loc[1] + n.getHeight() / 2f, SIDE_GLOW);
            return;
        }
        if (i >= 0) {
            zone = SIDE;
            sideAt = i;
            n.getLocationInWindow(loc);
            glide(loc[0] + n.getWidth() / 2f, loc[1] + n.getHeight() / 2f, SIDE_GLOW);
            return;
        }
        if (n.getParent() == homeTabs) {
            zone = TABS;
            // the cards of the app the focus came up from stay while it passes through the categories:
            // Up again reaches them. Taking them down here put the widgets back, and the cards out of reach
            if (!shelfOfFocusedApp()) hideShelf();
            scrollTo(0);
            n.getLocationInWindow(loc);
            glide(loc[0] + n.getWidth() / 2f, loc[1] + n.getHeight() / 2f, SIDE_GLOW);
            return;
        }
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
            dimRows(-1, true);
            widgetAt = i;
            // the song's clock current at once, and every second from here (light mode slows it out of focus)
            if (n == listenW) {
                handler.removeCallbacks(listenTick);
                handler.post(listenTick);
            }
            glide(Ui.dpf(pageX() + widgetX[i] + widgetW[i] / 2f), Ui.dpf((dock ? gridY() - 18 - 22 - WIDGET_H : WIDGET_Y) + WIDGET_H / 2f), glowOf(widgets.get(i)));
            return;
        }
        for (i = 0; i < cells.size(); i++) {
            if (cells.get(i).card == n) {
                zone = contentZone = GRID;
                gridAt = i;
                if (rows && i < rowIdx.length) dimRows(rowIdx[i], true);
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

    /** Music scenes, when switched on: an aerial video behind a fuller player, in place of the small bar. */
    private MusicScene scene;

    private void photoMusic(boolean on) {
        if (!on) {
            MusicStrip m = photoMusic;
            photoMusic = null;
            if (m != null) m.stop();
            MusicScene sc = scene;
            scene = null;
            if (sc != null) {
                sc.stop();
                // a video stopped mid-change never says it's gone: what it covered comes back regardless
                underVideo(false);
            }
            return;
        }
        MediaWatch.Playing p = listening;
        if (photoMusic != null || scene != null || p == null || p.controller == null || !p.playing) return;
        // the video's safe connection to Apple's server needs Android 7's network rules
        if (Prefs.on(this, Prefs.SCENES) && Build.VERSION.SDK_INT >= 24) {
            startScene(p.controller);
            return;
        }
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

    private void startScene(android.media.session.MediaController c) {
        final MusicScene[] made = new MusicScene[1];
        made[0] = new MusicScene(this, () -> {
            root.removeView(made[0]);
            if (scene != made[0]) return;
            scene = null;
            underVideo(false);
            // the music's player went (its app closed): the photograph's own clock and story come back
            if (photo) story.reveal(0);
        });
        scene = made[0];
        scene.onQuiet = this::quietScreen;
        // under an opaque video the photograph's drift (a new one decoded every minute and a half) was all for nothing
        scene.onShown = () -> {
            backdrop.stop();
            underVideo(true);
        };
        scene.onLost = () -> {
            underVideo(false);
            if (resumed) backdrop.start();
        };
        // above the photograph and its story, which it covers
        root.addView(scene, root.indexOfChild(story) + 1, match());
        story.hide(400);
        try {
            scene.start(c);
        } catch (RuntimeException e) {
            Trouble.caught("music scene", e);
            MusicScene sc = scene;
            scene = null;
            sc.stop();
        }
    }

    /**
     * A scene's video fills the screen: the photograph, its light and its shading stop drawing
     * beneath it, as under Control Center (the video's surface only clears what they drew).
     */
    private void underVideo(boolean on) {
        int v = on ? View.INVISIBLE : View.VISIBLE;
        backdrop.setVisibility(v);
        scrim.setVisibility(v);
        // light mode has no light at all (see applyLight)
        if (bloom.getVisibility() != View.GONE) bloom.setVisibility(v);
    }

    private boolean overlayOpen() {
        return welcome != null || settings != null && settings.isOpen() || folderPanel != null && folderPanel.isOpen()
                || switcher != null && switcher.isOpen() || nowPlaying != null && nowPlaying.isOpen()
                || menu != null && menu.isOpen() || ask != null && ask.isOpen() || findPanel != null && findPanel.isOpen()
                || weatherSheet != null && weatherSheet.isOpen() || who != null && who.isOpen() || allApps != null && allApps.isOpen()
                || watchOpen() || news != null;
    }

    private void setRacked(boolean on) {
        racked = on;
        // light mode: nothing turns over on a home left alone (the card in focus holds anyway)
        if (Perf.light(this)) stack.hold(on || getCurrentFocus() == fyW);
        backdrop.setDefocus(on ? 0f : homeDefocus(), on ? 2600 : 520);
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
        backdrop.setPhotoMode(on);
        if (on) {
            stopPreview();
            racked = true;
            backdrop.setDefocus(0f, 2000);
            bloom.setStrength(0f, 2000);
            story.tick(Calendar.getInstance());
            story.reveal(900);
            photoMusic(true);
            // nothing playing: a quiet moment to compile a new version of Aura, for TVs that never start its screensaver
            MediaWatch.Playing p = MediaWatch.now(this);
            if (p == null || !p.playing) Warm.self(this);
        } else {
            Warm.stop();
            photoMusic(false);
            racked = false;
            backdrop.setDefocus(homeDefocus(), 520);
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
        lastKeyAt = keyAt;
        backdrop.keyed(keyAt);
        if (e.getAction() == KeyEvent.ACTION_DOWN && Pad.isArrow(e.getKeyCode())) {
            Trouble.arrow(keyAt);
            // Light mode off: once the arrows stop, how their frames went is looked at
            if (speedWatch && !speedPending) {
                speedPending = true;
                handler.postDelayed(speedCheck, Trouble.BURST_DONE_MS);
            }
        }
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
        // the Light mode card is over everything, Settings included: its two buttons have the keys
        if (speedSheet != null && speedSheet.isOpen() && !isVolumeKey(code)) {
            return speedSheet.key(e) || super.dispatchKeyEvent(e);
        }
        // once it's dissolving, home is there and answers at once (it used to wait out the fade)
        if (splash != null && !splash.exiting() && !isVolumeKey(code)) {
            // never more than a moment: a press after 9 s takes it away
            if (SystemClock.uptimeMillis() - splashAt > 9000) dropSplash.run();
            return true;
        }
        // screen off with the sound kept on: volume and media keys work in the dark; any other press wakes the screen
        if (quietScreen) {
            if (isVolumeKey(code)) return super.dispatchKeyEvent(e);
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                    || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT
                    || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                    || code == KeyEvent.KEYCODE_MEDIA_REWIND) {
                if (down && e.getRepeatCount() == 0) {
                    MediaWatch.Playing p = MediaWatch.now(this);
                    if (p != null && p.controller != null) {
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                            if (p.playing) p.controller.getTransportControls().pause();
                            else p.controller.getTransportControls().play();
                        } else if (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                            p.controller.getTransportControls().skipToNext();
                        } else {
                            p.controller.getTransportControls().skipToPrevious();
                        }
                    } else MediaWatch.dispatchKey(this, code);
                }
                return true;
            }
            if (!down) {
                if (code == swallowUp) swallowUp = -1;
                return true;
            }
            wakeQuietScreen();
            return swallow(code);
        }
        if (isVolumeKey(code)) return super.dispatchKeyEvent(e);
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
        if (menu.isOpen() && findPanel.isOpen()) {
            if (down && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU)) {
                menu.hide();
                if (menuBack != null && menuBack.isShown()) menuBack.requestFocus();
                return swallow(code);
            }
            if (down && Pad.isConfirm(code) && e.getRepeatCount() > 0) return true;
            return super.dispatchKeyEvent(e);
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
            if (held && Pad.isArrow(code)) settle();
            if (code == swallowUp) {
                swallowUp = -1;
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        // a music scene: the remote works its player; Back with the controls away goes home
        if (photo && scene != null && !isVolumeKey(code) && scene.key(code)) return swallow(code);
        // photo mode with the small music bar (scenes off, or an older TV): media keys work the song without waking home
        if (photo && photoMusic != null && isMediaKey(code)) {
            if (e.getRepeatCount() == 0) {
                MediaWatch.Playing p = MediaWatch.now(this);
                if (p != null && p.controller != null) {
                    if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        if (p.playing) p.controller.getTransportControls().pause();
                        else p.controller.getTransportControls().play();
                    } else if (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                        p.controller.getTransportControls().skipToNext();
                    } else {
                        p.controller.getTransportControls().skipToPrevious();
                    }
                } else MediaWatch.dispatchKey(this, code);
            }
            return swallow(code);
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
            if (first && nowPlaying.key(code)) return swallow(code);
            // only the four buttons take focus; up and down have nowhere to go
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.dispatchKeyEvent(e);
        }
        if (who.isOpen()) {
            if (first || Pad.isArrow(code)) who.key(code);
            return swallow(code);
        }
        if (weatherSheet.isOpen()) {
            // nothing on it to move between: Back, Menu or OK put it away, the arrows rest
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || Pad.isConfirm(code)) {
                if (first) closeWeather();
                return swallow(code);
            }
            if (Pad.isArrow(code)) return true;
            return super.dispatchKeyEvent(e);
        }
        if ((switcher.isClosing() || switcher.isLaunching()) && !isVolumeKey(code)) return swallow(code);
        if (switcher.isOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeSwitcher(code == KeyEvent.KEYCODE_BACK);
                return swallow(code);
            }
            if (Pad.isArrow(code) || Pad.isConfirm(code)) {
                // a held OK doesn't open the same app twice
                if (Pad.isArrow(code) || first) switcher.key(code);
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
        // Watch: its genres and posters have the arrows and OK; Back closes an open title first, then (or Menu) goes home
        // What's new: its two buttons have the arrows and OK; Back (or Menu) puts it away for good
        if (news != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                    || code == KeyEvent.KEYCODE_INFO) {
                closeNews();
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        if (watchOpen()) {
            if (code == KeyEvent.KEYCODE_BACK && watch.back()) return swallow(code);
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                    || code == KeyEvent.KEYCODE_INFO) {
                closeWatch();
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        // All Apps: its tabs and tiles have the arrows and OK; Back (or Menu) goes home
        if (allApps.isOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                    || code == KeyEvent.KEYCODE_INFO) {
                closeAllApps();
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
            if (ccOpen) closeCcBack();
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
                hud(R.drawable.ic_check, getString(p.playing ? R.string.paused_value : R.string.playing_value, p.title));
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
                hud(R.drawable.ic_check, next ? getString(R.string.next_song) : getString(R.string.previous_song));
                handler.postDelayed(() -> refreshWidgets(false), 600);
                return swallow(code);
            }
        }
        // shoulder buttons (L1/R1) hop directly across categories in the Aura style
        if (first && (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1)
                && !dock && homeTab != null && homeTabs.size() > 1 && !folderPanel.isOpen()
                && (zone == GRID || zone == TABS || zone == WIDGETS)) {
            int cur = Math.max(0, homeTabIds.indexOf(homeTab));
            int to = Math.max(0, Math.min(homeTabIds.size() - 1, cur + (code == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1)));
            if (to != cur) {
                boolean onTabs = zone == TABS;
                pickHomeTab(to);
                if (onTabs) homeTabs.tab(to).requestFocus();
                navTick(code == KeyEvent.KEYCODE_BUTTON_L1 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            } else {
                bounce(false);
            }
            return swallow(code);
        }
        // channel up and down (and page keys): a page of apps at a time, or a couple of rows
        if (first && (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN) && !cells.isEmpty() && !folderPanel.isOpen()) {
            boolean back = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP;
            if (rows && gridAt < rowIdx.length && !rowInfo.isEmpty()) {
                // on category rows, a row at a time (each is a category), at the same place on screen
                int r = rowIdx[gridAt], to = Math.max(0, Math.min(rowInfo.size() - 1, r + (back ? -1 : 1)));
                focusGrid(rowTarget(to, colIdx[gridAt] - rowInfo.get(r).first));
                return swallow(code);
            }
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
        if (!Pad.isArrow(code)) return false;
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() > 0) {
            // a held key repeats ~20 times a second: it steps on Ui.holdStep's ladder instead, quicker
            // the longer it's held sideways, and the repeats between go, so focus stops where it's let go
            boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            long step = Ui.holdStep(now - e.getDownTime(), vertical), gap = now - lastRepeat;
            lastRepeat = now;
            if (now - lastStep < step) return true;
            // on the ladder's beat whatever the remote's repeat rate: a step that came late is made up once
            lastStep = now - lastStep < 2 * step ? lastStep + step : now;
            holdLead = 0.5f + ROW_TRAIL_MS / Math.max(step, gap);
            held = true;
        } else {
            // a press of its own is never held back: each one is a step, however quickly they come
            lastStep = now;
            lastRepeat = now;
        }
        View before = getWindow().getCurrentFocus();
        route(code);
        // the TV's own tick, once for each move focus really made
        if (getWindow().getCurrentFocus() != before) navTick(code);
        return true;
    }

    /** The TV's navigation tick for a move by the remote: silent when its system sounds are switched off. */
    private void navTick(int code) {
        int way = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
        try {
            View v = getWindow().getDecorView();
            // Android 13 has a softer tick for a held key's steps
            if (Build.VERSION.SDK_INT >= 33) v.playSoundEffect(SoundEffectConstants.getConstantForFocusDirection(way, held));
            else v.playSoundEffect(SoundEffectConstants.getContantForFocusDirection(way));
        } catch (RuntimeException ignored) {
            // the TV's sound service refused: the move stays silent
        }
    }

    private void route(int code) {
        View f = getCurrentFocus();
        if (zone == SIDE && side.indexOf(f) < 0 || zone == WIDGETS && widgets.indexOf(f) < 0
                || zone == SHELF && shelf.cards.indexOf(f) < 0 || zone == TOP && !tops.contains(f)
                || zone == TABS && homeTabs.focused() < 0) zone = contentZone;
        switch (zone) {
            case TABS: {
                int k = homeTabs.focused();
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (k > 0) homeTabs.tab(k - 1).requestFocus();
                    else if (!held) enterSide();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (k < homeTabs.size() - 1) homeTabs.tab(k + 1).requestFocus();
                    else if (!held) bounce(false);
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // a tab picked a moment ago: its apps come in now, focus on them where it came up from
                    homeTabs.pickNow();
                    if (cells.isEmpty()) landEmpty();
                    else focusGrid(Math.min(Math.max(0, lastCol), cells.size() - 1));
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && !held) {
                    // the app's cards kept above the categories, onto the one nearest above; else the widgets
                    if (shelfOfFocusedApp()) shelf.cards.get(Math.min(shelf.cards.size() - 1, Math.max(0, lastCol) * shelf.cards.size() / COLS)).requestFocus();
                    else if (!widgets.isEmpty()) focusWidget(widgetForCol(Math.max(0, lastCol)));
                    else setPhoto(true);
                }
                return;
            }
            case TOP:
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (shelf.shownFor() != null && !shelf.cards.isEmpty()) shelf.cards.get(Math.min(shelfAt, shelf.cards.size() - 1)).requestFocus();
                    else if (!widgets.isEmpty()) focusWidget(widgets.size() - 1);
                    else returnToContent();
                }
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int at = tops.indexOf(f) + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (at >= 0 && at < tops.size()) tops.get(at).requestFocus();
                    else if (at < 0 && !held && !scrollingV()) enterSide();
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && !held) {
                    setPhoto(true);
                }
                return;
            case SHELF:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && shelfAt > 0) shelf.cards.get(shelfAt - 1).requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && shelfAt < shelf.cards.size() - 1) shelf.cards.get(shelfAt + 1).requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusGrid(gridAt);
                else if (code == KeyEvent.KEYCODE_DPAD_UP && dock && !held) topCc.requestFocus();
                return;
            case SIDE:
                if (code == KeyEvent.KEYCODE_DPAD_UP) focusSide(sideAt - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusSide(sideAt + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) returnToContent();
                return;
            case WIDGETS:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (widgetAt > 0) focusWidget(widgetAt - 1);
                    else if (!scrollingV()) enterSide();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (widgetAt < widgets.size() - 1) focusWidget(widgetAt + 1);
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !cells.isEmpty()) {
                    int slot = colForWidget(widgetAt);
                    focusGrid(rows && !rowInfo.isEmpty() ? rowTarget(0, slot) : Math.min(cells.size() - 1, slot));
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && !held) {
                    if (dock) topCc.requestFocus();
                    else if (widgets.get(widgetAt) == fyW) stack.flip();
                }
                return;
            default: {
                if (cells.isEmpty()) {
                    landEmpty();
                    return;
                }
                if (rows && gridAt < rowIdx.length) {
                    rowsKey(code);
                    return;
                }
                int col = gridAt % COLS, row = rowOf(gridAt), page = pageOf(gridAt), n = cells.size();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (col > 0) focusGrid(gridAt - 1);
                        // past the first column: the previous page, same row, its last column
                        else if (page > 0) focusGrid(gridAt - perPage() + COLS - 1);
                        else if (!held && !scrollingV()) enterSide();
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
                        else if (!held && homeTab != null) {
                            // up into the categories, onto the one on show
                            lastCol = col;
                            homeTabs.tab(Math.max(0, homeTabIds.indexOf(homeTab))).requestFocus();
                        } else if (!held && shelfOfFocusedApp()) {
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
                            else downPastEnd();
                        } else if (gridAt + COLS < n) {
                            focusGrid(gridAt + COLS);
                        } else if (row < (n - 1) / COLS) {
                            focusGrid(n - 1);
                        } else {
                            downPastEnd();
                        }
                        break;
                }
            }
        }
    }

    /**
     * A held key was released: its row, aimed ahead to keep pace, coasts on to the next whole column
     * (never back) with the tile still in view, and the tile you landed on catches the light.
     */
    private void settle() {
        held = false;
        if (rows && zone == GRID && gridAt < rowIdx.length && rowIdx[gridAt] < rowInfo.size()) {
            RowInfo r = rowInfo.get(rowIdx[gridAt]);
            if (r.glide != null && r.glide.moving()) {
                float x = r.glide.value(), aim = r.glide.target();
                int col = colIdx[gridAt], first = r.first;
                if (aim > first && x > first) first = (int) Math.ceil(x - 0.01f);
                else if (aim < first && x < first) first = (int) Math.floor(x + 0.01f);
                r.first = Math.max(Math.max(0, col - COLS + 1), Math.min(Math.min(col, Math.max(0, r.count - COLS)), first));
                slideRow(r, r.first);
                windowMoved();
            }
        }
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
                // on category rows an app moves along its row; Hold OK, Add to puts it in another
                if (!rows && (!paged || rowOf(gridAt) > 0)) moveEditing(-COLS);
                return swallow(code);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!rows && (!paged || rowOf(gridAt) == 0)) moveEditing(COLS);
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

    // ---- Light mode, offered --------------------------------------------------------------------

    private SpeedSheet speedSheet;
    /** Light mode could be offered in this run (see {@link #watchSpeed}), and a look at the frames is due. */
    private boolean speedWatch, speedPending;
    /** When Light mode was last offered (or answered), on the TV's own settings. */
    static final String SPEED_ASKED = "speed_asked";

    /**
     * The Light mode card, on top of everything while it's open: added each time it's wanted (so
     * it's the last of the screen's layers), and it takes itself off again once it has closed.
     */
    private SpeedSheet speedSheet() {
        if (speedSheet == null) speedSheet = new SpeedSheet(this, backdrop);
        if (speedSheet.getParent() == null) root.addView(speedSheet, match());
        return speedSheet;
    }

    /**
     * Whether home's frames are worth watching for Light mode: it's off, Android times frames
     * (7 and later), it isn't a kids' profile (the settings are the grown-ups'), and it wasn't
     * offered in the last month.
     */
    private boolean watchSpeed() {
        return !Ui.light && Build.VERSION.SDK_INT >= 24 && !Profiles.kids(this)
                && System.currentTimeMillis() - Prefs.device(this).getLong(SPEED_ASKED, 0) >= 30L * 86_400_000L;
    }

    private final Runnable speedCheck = this::checkSpeed;

    /** The remote at rest after D-pad presses: three slow bursts in this run and Light mode is offered, home at rest. */
    private void checkSpeed() {
        speedPending = false;
        long wait = lastKeyAt + Trouble.BURST_DONE_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            // still pressing: looked at once they stop, not once for every press
            speedPending = true;
            handler.postDelayed(speedCheck, wait);
            return;
        }
        if (!speedWatch || Ui.light || Trouble.slowBursts() < 3 || !started || overlayOpen() || ccOpen) return;
        speedWatch = false;
        Prefs.device(this).edit().putLong(SPEED_ASKED, System.currentTimeMillis()).apply();
        speedSheet().offer(() -> {
            Perf.set(this, "on");
            applySetting("light");
            // the grid is made again in Light mode's own sizes: home takes the keys back, on the same tile
            unblockHome();
            if (!cells.isEmpty()) focusGrid(Math.min(gridAt, cells.size() - 1));
        });
    }

    private boolean swallow(int code) {
        swallowUp = code;
        return true;
    }

    private static boolean isVolumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    private static boolean isMediaKey(int code) {
        return code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_REWIND;
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
        final AppRepo.App a = find(pkg);
        // Watch's own tile: the first titles of what its screen last fetched, read from the TV
        final boolean ofWatch = WATCH_TAG.equals(pkg);
        final int color = ofWatch ? Tiles.WATCH : a != null ? a.color : DAY_GLOW, rowH = shelf.getLayoutParams().height;
        // on a thread of its own: behind home's worker (the widgets, artwork, the stack) the cards
        // were sometimes quick and sometimes seconds late. Their pictures are painted there too
        shelfWorker.execute(() -> {
            // focus moved on while this waited (or an app is opening): the app it's on now has its own turn
            if (!pkg.equals(shelfWanted)) return;
            final List<Continue.Item> items = ofWatch ? Watch.cards(app, 3) : TopShelf.items(app, pkg);
            if (!pkg.equals(shelfWanted)) return;
            final TopShelf.Ready ready = shelf.paintAll(items, color, a != null ? a.icon() : null, rowH);
            runOnUiThread(() -> {
                if (isDestroyed() || !pkg.equals(shelfWanted) || zone != GRID || rowOf(gridAt) > 0) return;
                if (items.isEmpty()) {
                    hideShelf();
                    return;
                }
                shelf.show(pkg, ready, it -> {
                    if (ofWatch) {
                        openWatch();
                        return;
                    }
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
                }, ofWatch ? null : this::openShelfMenu);
                for (Widget w : widgets) {
                    android.view.ViewPropertyAnimator wa = w.animate().alpha(0f).setDuration(260).setStartDelay(0).setInterpolator(Ui.EASE_STANDARD);
                    if (!Ui.light) wa.scaleX(0.95f).scaleY(0.95f);
                    wa.start();
                }
            });
        });
    };

    /**
     * Back from an app: the cards on show stay, and are looked up again once home has settled (you
     * may have just watched something); they're only made again if something changed. Taking them
     * down and building them again on every return was what made coming home lag in the tvOS
     * style, where focus is always on the dock and every app has cards.
     */
    private void refreshShelf() {
        String on = shelf.upFor();
        if (on == null || !on.equals(cells.get(gridAt).tag)) {
            shelf.clearShown();
            shelfFor(gridAt);
            return;
        }
        shelfWanted = on;
        handler.removeCallbacks(shelfLoad);
        handler.postDelayed(shelfLoad, 1200);
    }

    /** The Top Shelf on show is the one of the app the grid's focus is (or was last) on, with cards to go to. */
    private boolean shelfOfFocusedApp() {
        return gridAt < cells.size() && cells.get(gridAt).tag.equals(shelf.shownFor()) && !shelf.cards.isEmpty();
    }

    private void hideShelf() {
        handler.removeCallbacks(shelfLoad);
        shelfWanted = null;
        if (!shelf.up()) return;
        shelf.hide();
        if (dashboard) {
            for (Widget w : widgets) {
                android.view.ViewPropertyAnimator wa = w.animate().alpha(1f).setDuration(400).setStartDelay(90).setInterpolator(Ui.EMPHASIZED);
                if (!Ui.light) wa.scaleX(1f).scaleY(1f);
                wa.start();
            }
        }
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
        nowPlaying.close();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
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
        // opened again while still closing, the focus is on the switcher itself: keep the place it came from
        if (!switcher.isClosing()) beforeSwitcher = getCurrentFocus();
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
                switcher.launchInto(app, () -> openFromSwitcher(back2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), app.label, null));
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
            // something opened meanwhile (Who's watching, Find): it holds home and the focus, and gives them back as it closes
            if (overlayOpen()) return;
            for (View v : behindCc()) v.setVisibility(View.VISIBLE);
            home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            stack.hold(false);
            if (beforeSwitcher == null || !beforeSwitcher.isShown() || !beforeSwitcher.requestFocus()) focusGrid(gridAt);
            wake();
        });
    }

    // ---- app menu ----------------------------------------------------------------------------

    private AppMenu menu;
    private View menuBack;

    /** Hold OK on a Top Shelf card: its synopsis and remaining time, with Play and Hide / Not interested. */
    private void openShelfMenu(Continue.Item it, Card card) {
        if (editing || ccOpen || menu.isOpen() || it == null || card == null) return;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        swallow(heldConfirm);
        final AppRepo.App a = it.pkg != null ? find(it.pkg) : (gridAt >= 0 && gridAt < cells.size() ? find(cells.get(gridAt).tag) : null);
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(new AppMenu.Item(R.drawable.ic_play, getString(it.nowPlaying || it.progress > 0f ? R.string.resume : R.string.open), () -> {
            closeMenu(card);
            if (it.nowPlaying) {
                MediaWatch.Playing p = MediaWatch.now(HomeActivity.this);
                if (p != null && p.controller != null) {
                    if (!p.playing) p.controller.getTransportControls().play();
                    openNowPlaying();
                    return;
                }
            }
            Intent go = TopShelf.launchFor(it, a);
            if (go != null) launch(go, card, it.title);
        }));
        if (it.nowPlaying) {
            items.add(new AppMenu.Item(R.drawable.ic_moon, getString(R.string.screen_off_keep_sound), () -> {
                closeMenu(card);
                quietScreen();
            }));
            if (Power.canSleep(this)) {
                items.add(new AppMenu.Item(R.drawable.ic_moon, sleepMenuLabel(), () -> {
                    closeMenu(card);
                    cc.cycleSleep();
                }));
            }
        } else if (it.title != null && !it.title.isEmpty()) {
            final String key = Continue.key(it.title);
            boolean watching = it.progress > 0f;
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(watching ? R.string.hide_from_the_stack : R.string.not_interested), () -> {
                closeMenu(card);
                Taste.decline(getApplicationContext(), key);
                TopShelf.clearCache();
                hud(R.drawable.ic_hide, getString(R.string.it_wont_come_back_settings));
                refreshWidgets(true);
                if (zone == SHELF || zone == GRID && rowOf(gridAt) == 0 && !cells.isEmpty()) {
                    shelf.clearShown();
                    shelfFor(gridAt);
                }
            }));
        }
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menuBack = card;
        menu.show(card, it.title != null ? it.title : "", itemNote(it), items);
    }

    /** Hold OK on an app: move it, hide it, see its details in Android's settings, or uninstall it. */
    private void openMenu() {
        if (editing || ccOpen || menu.isOpen() || zone != GRID || cells.isEmpty()) return;
        Cell c = cells.get(gridAt);
        if (c.tag.startsWith("input:")) {
            // an input: it can't move (switched-on sources stand first) but it can leave the home screen;
            // not from a kids' profile, where hiding is a grown-up's to do: its one choice gone, no menu opens
            if (Profiles.kids(this)) return;
            final String tag = c.tag, name = c.label.getText().toString();
            final Card card = c.card;
            stopPreview();
            swallow(heldConfirm);
            List<AppMenu.Item> items = new ArrayList<>();
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.hide_from_home), () -> {
                closeMenu(card);
                Prefs.hide(this, tag, true);
                liveGen++;
                live.clear();
                live.addAll(liveInputs());
                populate();
                focusGrid(0);
                hud(R.drawable.ic_hide, getString(R.string.name_is_hidden_hidden_apps, name));
            }));
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            menuBack = card;
            menu.show(card, name, items);
            return;
        }
        if (c.tag.equals(WATCH_TAG)) {
            // Watch: it keeps its place; its one choice switches it off altogether, tile, screen and what it fetched
            final String name = c.label.getText().toString();
            final Card card = c.card;
            swallow(heldConfirm);
            List<AppMenu.Item> items = new ArrayList<>();
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.watch_turn_off), () -> {
                closeMenu(card);
                Prefs.set(this, Prefs.WATCH, false);
                watchOff();
                populate();
                focusGrid(gridAt);
                hud(R.drawable.ic_hide, getString(R.string.watch_is_off));
            }));
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            menuBack = card;
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
        items.add(new AppMenu.Item(R.drawable.ic_move, getString(R.string.move), () -> {
            closeMenu(card);
            startEdit();
        }));
        if (pkg.startsWith("folder:")) {
            final String id = pkg.substring(7);
            items.add(new AppMenu.Item(R.drawable.ic_settings, getString(R.string.rename), () -> {
                closeMenu(card);
                nameFolder(id, false);
            }));
            items.add(new AppMenu.Item(R.drawable.ic_apps, getString(R.string.remove_folder), () -> {
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
                hud(R.drawable.ic_apps, getString(R.string.folder_removed, label));
            }));
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            menuBack = card;
            menu.show(card, label, items);
            return;
        }
        items.add(new AppMenu.Item(R.drawable.ic_apps, getString(R.string.add_to_folder), () -> {
            closeMenu(card);
            chooseFolder(pkg, label, card);
        }));
        items.add(new AppMenu.Item(R.drawable.ic_add, getString(R.string.add_to_category), () -> {
            closeMenu(card);
            chooseCategory(pkg, label, card);
        }));
        // a kids' profile can move apps and make folders, nothing more
        if (!Profiles.kids(this)) {
            items.add(new AppMenu.Item(R.drawable.ic_picture, getString(R.string.change_its_picture), () -> {
                closeMenu(card);
                openSettings();
                if (settings.isOpen()) settings.art(pkg, label);
            }));
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.hide_from_home), () -> {
                closeMenu(card);
                Prefs.hide(this, pkg, true);
                populate();
                hud(R.drawable.ic_hide, getString(R.string.name_is_hidden_settings, label));
            }));
            items.add(new AppMenu.Item(R.drawable.ic_info, getString(R.string.app_info), () -> {
                closeMenu(card);
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg)), card);
            }));
            if (removable(this, pkg)) items.add(new AppMenu.Item(R.drawable.ic_trash, getString(R.string.uninstall), () -> {
                closeMenu(card);
                // Android asks to confirm; the grid updates itself once the app is gone
                open(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)), card);
            }));
            if (Power.canSleep(this)) {
                items.add(new AppMenu.Item(R.drawable.ic_moon, sleepMenuLabel(), () -> {
                    closeMenu(card);
                    cc.cycleSleep();
                }));
            }
        }
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menuBack = card;
        menu.show(card, label, items);
    }

    private String sleepMenuLabel() {
        long left = Power.sleepLeftMs(this);
        if (left <= 0) return getString(R.string.sleep_timer);
        int min = (int) Math.ceil(left / 60_000.0);
        return getString(R.string.sleep_in_value, Words.minutes(min));
    }

    /** Hold OK on a widget: move it along the row, or hide it (the others widen to fill its place). */
    private void openWidgetMenu(Widget w) {
        int n = widgets.indexOf(w), id = -1;
        for (int i = 0; i < 3; i++) if (allWidgets[i] == w) id = i;
        if (editing || ccOpen || menu.isOpen() || n < 0 || id < 0) return;
        final String name = getString(WIDGET_NAMES[id]), key = WIDGET_IDS[id];
        swallow(heldConfirm);
        List<AppMenu.Item> items = new ArrayList<>();
        // the card on show first: leave it out of the stack from now on
        final SmartStack.Slide card = w == fyW ? stack.current() : null;
        if (card != null && card.key != null && card.decline != null) {
            items.add(new AppMenu.Item(R.drawable.ic_hide, card.decline, () -> {
                closeMenu(w);
                Taste.decline(getApplicationContext(), card.key);
                hud(R.drawable.ic_hide, getString(R.string.it_wont_come_back_settings));
                refreshWidgets(true);
            }));
        }
        if (n > 0) items.add(new AppMenu.Item(R.drawable.ic_move, getString(R.string.move_left), () -> moveWidget(w, -1)));
        if (n < widgets.size() - 1) items.add(new AppMenu.Item(R.drawable.ic_move, getString(R.string.move_right), () -> moveWidget(w, 1)));
        // what the stack deals: its cards are chosen on the Widgets page
        if (w == fyW) items.add(new AppMenu.Item(R.drawable.ic_tune, getString(R.string.choose_cards), () -> {
            closeMenu(w);
            openSettings("widgets");
        }));
        // a kids' profile can move its widgets, nothing more (as with its apps): hiding one, or all of
        // them for the tvOS style, is undone in Settings, behind the parents' PIN
        boolean kids = Profiles.kids(this);
        if (!kids && widgets.size() > 1) items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.hide), () -> {
            closeMenu(w);
            Prefs.hideWidget(this, key, true);
            layoutWidgets(true);
            focusWidget(Math.min(n, widgets.size() - 1));
            hud(R.drawable.ic_hide, getString(R.string.name_is_hidden_settings, name));
        }));
        else if (!kids) items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.only_apps_tvos_style), () -> {
            closeMenu(w);
            Prefs.of(this).edit().putString(Prefs.LAYOUT, "tvos").apply();
            applySetting("layout");
            hud(R.drawable.ic_check, getString(R.string.tvos_style_settings_home_screen));
        }));
        // (a kids' profile's only widget, with nowhere to move to: nothing to offer)
        if (items.isEmpty()) return;
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menuBack = w;
        String head = card != null && card.title != null && !card.title.isEmpty() ? card.title : name;
        menu.show(w, head, card != null ? card.note : null, items);
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
        // over All Apps, home stays out of reach and the focus stays in All Apps
        boolean over = allApps.isOpen() || watchOpen() || news != null;
        home.setDescendantFocusability(over ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (back != null && back.isShown() && back.requestFocus()) return;
        if (!over) focusGrid(gridAt);
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
    /** Which category row an app goes in, or a new one, named straight away. */
    private void chooseCategory(String pkg, String label, Card card) {
        List<AppMenu.Item> items = new ArrayList<>();
        for (Categories.Cat k : Categories.all(this)) {
            if (k.id.equals(Categories.ALL)) continue;
            final String id = k.id, name = Categories.name(this, k);
            items.add(new AppMenu.Item(R.drawable.ic_apps, name, () -> {
                closeMenu(card);
                Categories.addTo(this, id, pkg);
                populate();
                // from All Apps: its tabs follow (the row may be new to them)
                refreshOverlays();
                hud(R.drawable.ic_check, getString(R.string.name_is_in_row, label, name));
            }));
        }
        items.add(new AppMenu.Item(R.drawable.ic_add, getString(R.string.cat_new), () -> {
            closeMenu(card);
            // the name card hands the focus back to home's tiles: from All Apps, home comes back first
            closeAllApps();
            askTag = pkg;
            home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            ask.show(getString(R.string.name_your_new_category), "", getString(R.string.type_a_name_then_ok), Folders.suggestions(this), name -> {
                String id = Categories.create(this, name);
                Categories.addTo(this, id, pkg);
                afterAsk();
                hud(R.drawable.ic_check, getString(R.string.name_is_in_row, label, name));
            });
        }));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menuBack = card;
        menu.show(card, getString(R.string.add_name_to, label), items);
    }

    private void chooseFolder(String pkg, String label, Card card) {
        List<AppMenu.Item> items = new ArrayList<>();
        for (Folders.Folder f : Folders.all(this).values()) {
            items.add(new AppMenu.Item(R.drawable.ic_apps, f.name, () -> {
                closeMenu(card);
                Folders.add(this, f.id, pkg);
                populate();
                focusTag(f.tag());
                hud(R.drawable.ic_check, getString(R.string.name_is_in_folder, label, f.name));
            }));
        }
        items.add(new AppMenu.Item(R.drawable.ic_add, getString(R.string.new_folder), () -> {
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
        menuBack = card;
        menu.show(card, getString(R.string.add_name_to, label), items);
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

    private AllApps allApps;

    /**
     * All Apps' tabs: every app first, then each category home shows, in home's order, with only
     * the apps that aren't hidden. A category with nothing in it isn't a tab.
     */
    private List<AllApps.Tab> allAppsTabs() {
        Set<String> hidden = Prefs.hidden(this);
        List<String> order = AppRepo.order(prefs), installed = new ArrayList<>();
        for (AppRepo.App a : apps) if (!hidden.contains(a.pkg)) installed.add(a.pkg);
        Map<String, AppRepo.App> byPkg = new java.util.HashMap<>();
        for (AppRepo.App a : apps) byPkg.put(a.pkg, a);
        List<AllApps.Tab> out = new ArrayList<>();
        for (Categories.Cat k : Categories.all(this)) {
            boolean all = k.id.equals(Categories.ALL);
            if (k.hidden && !all) continue;
            List<AppRepo.App> in = new ArrayList<>();
            for (String pkg : Categories.members(this, k, order, installed)) {
                AppRepo.App a = byPkg.get(pkg);
                if (a != null) in.add(a);
            }
            if (in.isEmpty()) continue;
            AllApps.Tab t = new AllApps.Tab(k.id, Categories.name(this, k), in);
            if (all) out.add(0, t);
            else out.add(t);
        }
        return out;
    }

    /**
     * What's new waits its turn: not over the boot screen, the welcome, a panel or the photograph on
     * its own. It counts as seen the moment it's on screen, however it's then left.
     */
    private final Runnable showNews = new Runnable() {
        @Override
        public void run() {
            if (!resumed || news != null) return;
            if (splash != null || introPending || ccOpen || photo || overlayOpen()) {
                handler.postDelayed(this, 2000);
                return;
            }
            if (WhatsNew.due(HomeActivity.this)) openNews();
        }
    };

    private void openNews() {
        if (news != null || !resumed || welcome != null) return;
        if (ccOpen) closeCc(false);
        if (settings.isOpen()) closeSettings();
        closeOverlays();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        hideShelf();
        news = new WhatsNew(this, new WhatsNew.Host() {
            @Override
            public Backdrop backdrop() {
                return backdrop;
            }

            @Override
            public void openWatch() {
                closeNews();
                // "Try Watch": switched on as Settings would, its tile among the apps, then opened
                if (!Prefs.watch(HomeActivity.this)) {
                    Prefs.set(HomeActivity.this, Prefs.WATCH, true);
                    populate();
                    // Back from Watch lands on its new tile, as it would after opening it from there
                    for (int i = 0; i < cells.size(); i++) if (cells.get(i).tag.equals(WATCH_TAG)) gridAt = i;
                }
                HomeActivity.this.openWatch();
            }

            @Override
            public void done() {
                closeNews();
            }
        });
        // over All Apps and Watch, under everything that opens over home (the switcher, Control Center, Settings)
        root.addView(news, root.indexOfChild(watch != null ? watch : allApps) + 1, match());
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        WhatsNew.seen(this);
        news.open();
        // its glass covers the screen: home under it is drawn for nothing once it's in (at once on LOW, which doesn't fade)
        handler.postDelayed(() -> {
            if (news != null) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, Perf.light(this) ? 32 : 300);
    }

    /** What's new put away: it leaves the window and is forgotten, and home has the remote again. */
    private void closeNews() {
        if (news == null) return;
        final WhatsNew gone = news;
        news = null;
        gone.close(() -> root.removeView(gone));
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        stack.hold(false);
        focusGrid(gridAt);
        wake();
    }

    /** The Watch tile's tag among the home screen's tiles: not an app, as an input's ("input:…") isn't. */
    static final String WATCH_TAG = "watch:";
    /** HIGH: how long a closed Watch screen is kept, so going straight back in is instant. LOW lets it go at once. */
    static final long WATCH_KEPT_MS = 20_000;

    /** A tile that isn't an app or a folder: a live input, or Watch. It keeps its place and has no app's menu. */
    private static boolean fixedTile(String tag) {
        return tag.startsWith("input:") || tag.equals(WATCH_TAG);
    }

    private boolean watchOpen() {
        return watch != null && watch.isOpen();
    }

    /**
     * Watch: the whole screen given to something to watch, by genre, opened from its tile. Home
     * beneath it stops drawing once it covers the screen. The screen is only made here, the first
     * time it's opened: switched off in Settings, or never opened, none of it exists.
     */
    private void openWatch() {
        if (watchOpen() || ccOpen || settings.isOpen() || welcome != null || !Prefs.watch(this)) return;
        handler.removeCallbacks(dropWatch);
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        hideShelf();
        if (watch == null) {
            watch = new WatchScreen(this, (intent, from) -> {
                try {
                    open(intent, from);
                } catch (ActivityNotFoundException | SecurityException e) {
                    hud(R.drawable.ic_info, getString(R.string.watch_nothing_opens_it));
                }
            });
            // over All Apps, under everything that opens over home (the switcher, Control Center, Settings)
            root.addView(watch, root.indexOfChild(allApps) + 1, match());
        }
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        watch.open();
        // HIGH fades in over home; LOW is there whole on its first frame, and home drawn under it
        // for longer is a second screen a small GPU paints for nothing
        handler.postDelayed(() -> {
            if (!watchOpen()) return;
            for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
            backdrop.stop();
        }, Perf.light(this) ? 32 : 300);
    }

    private void closeWatch() {
        if (!watchOpen()) return;
        if (resumed) backdrop.start();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        watch.close();
        stack.hold(false);
        focusGrid(gridAt);
        wake();
        // closed, it holds nothing for long: its posters and pictures are a few MB a small TV needs
        handler.removeCallbacks(dropWatch);
        handler.postDelayed(dropWatch, Perf.light(this) ? Ui.CLOSE : WATCH_KEPT_MS);
    }

    /** A closed Watch screen leaves the window and is forgotten, its threads ended and its pictures let go. */
    private final Runnable dropWatch = () -> {
        if (watch == null || watch.isOpen()) return;
        root.removeView(watch);
        watch.release();
        watch = null;
        Continue.forget(Watch.dir(this));
    };

    /** Not after its wait: now. */
    private void dropWatchNow() {
        handler.removeCallbacks(dropWatch);
        dropWatch.run();
    }

    /** Watch switched off (Settings, or its tile's menu): closed and let go now, and what it fetched leaves the TV. */
    private void watchOff() {
        closeWatch();
        dropWatchNow();
        if (WATCH_TAG.equals(shelf.upFor())) hideShelf();
        final Context app = getApplicationContext();
        worker.execute(() -> Watch.clear(app));
    }

    /** The apps in category {@code k} that are still here, in its order ({@code installed}: those not hidden). */
    private List<AppRepo.App> categoryApps(Categories.Cat k, List<String> order, List<String> installed) {
        List<AppRepo.App> out = new ArrayList<>();
        for (String pkg : Categories.members(this, k, order, installed)) {
            AppRepo.App a = find(pkg);
            if (a != null) out.add(a);
        }
        return out;
    }

    /**
     * OK held on an app in All Apps: home's own menu for it, less what only home's grid can do (move
     * it, folders). What needs home itself (Settings, the name card) puts All Apps away first.
     */
    private void allAppsMenu(AppRepo.App a, Card card) {
        if (menu.isOpen()) return;
        final String pkg = a.pkg, label = a.label;
        swallow(heldConfirm);
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(new AppMenu.Item(R.drawable.ic_add, getString(R.string.add_to_category), () -> {
            closeMenu(card);
            chooseCategory(pkg, label, card);
        }));
        // out of the category on show (All apps holds every app, so not out of that)
        final String tab = allApps.currentId();
        final Categories.Cat in = tab == null || tab.equals(Categories.ALL) ? null : Categories.find(Categories.all(this), tab);
        if (in != null) {
            final String catName = Categories.name(this, in);
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.remove_from_row, catName), () -> {
                closeMenu(card);
                Categories.removeFrom(this, tab, pkg);
                categoryChanged(tab);
                hud(R.drawable.ic_check, getString(R.string.name_left_row, label, catName));
            }));
        }
        // a kids' profile can sort apps into rows, nothing more (as on home)
        if (!Profiles.kids(this)) {
            items.add(new AppMenu.Item(R.drawable.ic_picture, getString(R.string.change_its_picture), () -> {
                closeMenu(card);
                closeAllApps();
                openSettings();
                if (settings.isOpen()) settings.art(pkg, label);
            }));
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.hide_from_home), () -> {
                closeMenu(card);
                Prefs.hide(this, pkg, true);
                populate();
                refreshOverlays();
                hud(R.drawable.ic_hide, getString(R.string.name_is_hidden_settings, label));
            }));
            items.add(new AppMenu.Item(R.drawable.ic_info, getString(R.string.app_info), () -> {
                closeMenu(card);
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg)), card);
            }));
            if (removable(this, pkg)) items.add(new AppMenu.Item(R.drawable.ic_trash, getString(R.string.uninstall), () -> {
                closeMenu(card);
                open(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)), card);
            }));
        }
        menuBack = card;
        menu.show(card, label, items);
    }

    /** Delete chosen once on this category: the next OK on it really deletes it. */
    private String deleting;
    /** The All Apps tab a rename was asked from, for the focus to go back to. */
    private String askTab;

    /**
     * OK held on an All Apps tab: rename the category, move it along the tabs, hide a built-in one
     * (Settings brings it back) or delete one of your own, the first OK asking for a second.
     */
    private void categoryMenu(String id, View anchor) {
        Categories.Cat k = Categories.find(Categories.all(this), id);
        if (k == null || anchor == null || menu.isOpen() && !id.equals(deleting)) return;
        // a menu opened afresh asks for Delete's second OK again
        if (!menu.isOpen()) deleting = null;
        List<String> ids = new ArrayList<>();
        for (AllApps.Tab t : allAppsTabs()) ids.add(t.id);
        int at = ids.indexOf(id);
        final String name = Categories.name(this, k);
        boolean kids = Profiles.kids(this);
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(new AppMenu.Item(R.drawable.ic_settings, getString(R.string.rename), () -> {
            closeMenu(anchor);
            askTab = id;
            ask.show(getString(R.string.rename_name, name.toUpperCase(Locale.getDefault())), name,
                    getString(R.string.type_a_name_then_ok), Folders.suggestions(this), typed -> {
                        Categories.rename(this, id, typed);
                        afterAsk();
                        hud(R.drawable.ic_check, getString(R.string.renamed_to, typed));
                    });
        }));
        // All apps always leads: the others move among themselves, after it
        for (int d = -1; d <= 1; d += 2) {
            final int to = at + d, dir = d;
            if (at < 1 || to < 1 || to >= ids.size()) continue;
            items.add(new AppMenu.Item(R.drawable.ic_move, getString(d < 0 ? R.string.move_left : R.string.move_right), () -> {
                Categories.moveBeside(this, id, ids.get(to), dir);
                closeMenu(anchor);
                categoryChanged(id);
            }));
        }
        if (!kids && Categories.builtIn(k) && !id.equals(Categories.ALL)) {
            items.add(new AppMenu.Item(R.drawable.ic_hide, getString(R.string.hide), () -> {
                Categories.setHidden(this, id, true);
                closeMenu(anchor);
                categoryChanged(id);
                hud(R.drawable.ic_hide, getString(R.string.category_hidden, name));
            }));
        }
        if (!kids && !Categories.builtIn(k)) {
            final boolean sure = id.equals(deleting);
            items.add(new AppMenu.Item(R.drawable.ic_trash, getString(sure ? R.string.press_ok_again : R.string.delete_category), () -> {
                if (!sure) {
                    deleting = id;
                    categoryMenu(id, anchor);
                    return;
                }
                Categories.delete(this, id);
                closeMenu(anchor);
                categoryChanged(id);
                hud(R.drawable.ic_trash, getString(R.string.category_deleted, name));
            }));
        }
        if (!id.equals(deleting)) swallow(heldConfirm);
        menuBack = anchor;
        menu.show(anchor, name, items);
    }

    /** A category changed: All Apps' tabs, or the Aura home's, made again, the focus back on its tab (or the one on show). */
    private void categoryChanged(String id) {
        if (allApps.isOpen()) {
            refreshOverlays();
            allApps.focusTab(id);
            return;
        }
        populate();
        int k = homeTabIds.indexOf(id);
        if (homeTab == null) returnToContent();
        else homeTabs.tab(k >= 0 ? k : Math.max(0, homeTabIds.indexOf(homeTab))).requestFocus();
    }

    /** dp: the Aura home's centered category capsule height, its rest row above the grid, and its sticky scrolled dock Y. */
    private static final int TABS_REST_Y = 254, TABS_DOCK_Y = 26;
    private TabStrip homeTabs;
    /** The Aura home's tabs (category ids, in order), and the one whose apps the grid shows: null with no tabs up. */
    private final List<String> homeTabIds = new ArrayList<>();
    private String homeTab;

    /**
     * The Aura style's category tabs over the grid, made again (only when they changed) from the
     * categories with something in them; none in the tvOS style, or with a single category. Returns
     * the tab the grid should show alone, or null for every app (All apps).
     */
    private AllApps.Tab refreshHomeTabs() {
        List<AllApps.Tab> list = dock ? java.util.Collections.<AllApps.Tab>emptyList() : allAppsTabs();
        if (list.size() < 2) {
            homeTabs.setVisibility(View.GONE);
            if (homeTabBar != null) homeTabBar.setVisibility(View.GONE);
            homeTabIds.clear();
            homeTab = null;
            return null;
        }
        List<String> ids = new ArrayList<>(), titles = new ArrayList<>();
        for (AllApps.Tab t : list) {
            ids.add(t.id);
            titles.add(t.title);
        }
        boolean changed = !ids.equals(homeTabIds) || homeTabs.size() != titles.size();
        for (int i = 0; !changed && i < titles.size(); i++) changed = !titles.get(i).contentEquals(homeTabs.tab(i).getText());
        int at = homeTab == null ? -1 : ids.indexOf(homeTab);
        // none chosen yet, or its category gone (deleted, hidden, emptied): every app
        if (at < 0) {
            at = 0;
            homeTab = ids.get(0);
        }
        if (changed) {
            boolean had = homeTabs.focused() >= 0;
            homeTabIds.clear();
            homeTabIds.addAll(ids);
            homeTabs.set(titles);
            homeTabs.choose(at, false);
            if (had) homeTabs.tab(at).requestFocus();
        }
        homeTabs.setVisibility(View.VISIBLE);
        if (homeTabBar != null) homeTabBar.setVisibility(View.VISIBLE);
        positionHomeTabs(scrollY + giveY);
        return homeTab.equals(Categories.ALL) ? null : list.get(at);
    }

    /**
     * An Aura home tab picked: the grid shows its apps, sliding in from the side the tab lies on as
     * All Apps' do (HIGH fades and settles them cleanly); focus stays on the tab unless invoked from grid.
     */
    private void pickHomeTab(int k) {
        if (k < 0 || k >= homeTabIds.size()) return;
        int was = homeTabIds.indexOf(homeTab);
        if (k == was) return;
        homeTab = homeTabIds.get(k);
        homeTabs.choose(k, true);
        if (scrollTarget != 0f) scrollTo(0);
        // other apps are coming: the cards kept for the one the focus left go
        hideShelf();
        populate();
        float dx = Ui.dpf(28) * Integer.signum(k - was);
        boolean rich = AllApps.rich(this);
        boolean light = Ui.light;
        // the rows on screen (the first few: the page sits at its top on a tab); those below are simply there
        int animRows = light ? Math.min(2, AllApps.STAGGERED_ROWS) : AllApps.STAGGERED_ROWS;
        for (int i = 0; i < cells.size() && rowOf(i) < animRows; i++) {
            Cell c = cells.get(i);
            float x = cellX(i), y = cellY(i);
            int row = rowOf(i), col = i % Math.max(1, COLS);
            c.animate().cancel();
            c.setTranslationX(x + dx);
            c.setTranslationY(y + (rich ? Ui.dpf(5) : 0f));
            long delay = rich ? col * 14L + row * 28L : 0L;
            long dur = light ? 160L : (rich ? 240L : AllApps.IN_MS) + (long) AllApps.ROW_TRAIL_MS * row;
            android.view.ViewPropertyAnimator in = c.animate().translationX(x).translationY(y)
                    .setStartDelay(delay).setDuration(dur).setInterpolator(rich ? Ui.EMPHASIZED : Ui.EASE_DECEL);
            if (rich) {
                c.setAlpha(0f);
                c.setScaleX(0.96f);
                c.setScaleY(0.96f);
                in.alpha(1f).scaleX(1f).scaleY(1f).withLayer();
            } else {
                c.setScaleX(1f);
                c.setScaleY(1f);
            }
            in.start();
        }
    }

    private void openAllApps() {
        if (allApps.isOpen() || apps.isEmpty()) return;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        hideShelf();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        allApps.open(allAppsTabs());
        // home sinks a little as the glass rises over it, by its position alone (AllApps.Host.depth):
        // fading or scaling the whole home put it on a layer the size of the screen, which a 1 GB TV paid for
        handler.postDelayed(() -> {
            if (!allApps.isOpen()) return;
            for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
            // the photograph rests while the glass covers it: its drift re-blurred the glass five
            // times a second for nothing to see, and on HIGH kept a weak GPU busy as the apps moved
            backdrop.stop();
        }, 300);
    }

    /** dp home sinks while All Apps covers it, falling back as the glass rises and coming up again as it goes. */
    private static final float RECEDE = 14;

    private void closeAllApps() {
        if (!allApps.isOpen()) return;
        if (resumed) backdrop.start();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        allApps.close();
        stack.hold(false);
        focusGrid(gridAt);
        wake();
    }

    private Find findPanel;
    private View beforeFind;

    private void openFind(String typed) {
        if (findPanel.isOpen() || ccOpen || settings.isOpen() || welcome != null) return;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
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
        findPanel.close();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
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
        ask.show(fresh ? getString(R.string.name_your_new_folder) : getString(R.string.rename_name, f.name.toUpperCase(Locale.getDefault())), f.name,
                getString(R.string.type_a_name_then_ok), names, name -> {
                    Folders.rename(this, id, name);
                    afterAsk();
                    hud(R.drawable.ic_check, fresh ? getString(R.string.new_folder_name, name) : getString(R.string.renamed_to, name));
                });
    }

    /** Every small overlay closed: the hold-OK menu, a folder, Now Playing, finding an app, the name or PIN card, Who's watching. */
    private void closeOverlays() {
        if (menu.isOpen()) closeMenu(null);
        if (folderPanel.isOpen()) closeFolder();
        if (allApps.isOpen()) closeAllApps();
        closeWatch();
        closeNews();
        if (nowPlaying.isOpen()) closeNowPlaying();
        if (weatherSheet.isOpen()) closeWeather();
        if (findPanel.isOpen()) closeFind();
        if (ask.isOpen()) closeAsk();
        if (who.isOpen()) closeWho(true);
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
        if (!ccOpen && !settings.isOpen() && !switcher.isOpen() && news == null) home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void afterAsk() {
        if (askTab != null) {
            // a category renamed from All Apps: back to its tab, home still out of reach under it
            String id = askTab;
            askTab = null;
            categoryChanged(id);
            return;
        }
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        populate();
        if (askTag != null) focusTag(askTag);
        askTag = null;
        wake();
    }

    /** Focus the grid tile with this tag, if it's there. */
    private void focusTag(String tag) {
        if (tag == null) return;
        // a category row's tile by its key ("video|com.netflix.ninja"), else the app's first tile:
        // the last match was All apps at the very bottom
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).key().equals(tag)) {
                focusGrid(i);
                return;
            }
        }
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).tag.equals(tag)) {
                focusGrid(i);
                return;
            }
        }
    }

    private boolean menuKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            View back = menuBack != null ? menuBack : (cells.isEmpty() ? null : cells.get(Math.min(gridAt, cells.size() - 1)).card);
            menuBack = null;
            closeMenu(back);
            return swallow(code);
        }
        // the opening press repeating, or a sideways step out of the list: nothing to do
        if (Pad.isConfirm(code) && e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        return super.dispatchKeyEvent(e);
    }

    /** Apps that came with the TV can't be uninstalled, only hidden. */
    static boolean removable(Context c, String pkg) {
        try {
            return (c.getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- rearranging -------------------------------------------------------------------------

    /** Move, from an app's menu: it wiggles, the arrows carry it through the grid and the others make room. */
    private void startEdit() {
        if (editing || ccOpen || zone != GRID || cells.isEmpty()) return;
        Cell c = cells.get(gridAt);
        if (fixedTile(c.tag)) return;
        editing = true;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        focusLayer.hide();
        final Card card = c.card;
        wiggle = ValueAnimator.ofFloat(-1f, 1f);
        wiggle.setDuration(150);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.addUpdateListener(a -> card.setRotation(1.4f * (float) a.getAnimatedValue()));
        wiggle.start();
        showHud(R.drawable.ic_move, getString(R.string.move_with_the_arrows, c.label.getText()), 0);
    }

    private void stopEdit() {
        if (!editing) return;
        editing = false;
        if (wiggle != null) wiggle.cancel();
        for (Cell c : cells) c.card.setRotation(0f);
        List<String> order = new ArrayList<>();
        String row = rows && gridAt < cells.size() ? cells.get(gridAt).row : null;
        for (Cell c : cells) if (!fixedTile(c.tag) && (row == null || row.equals(c.row))) order.add(c.tag);
        // what's on show: a category row, an Aura home tab, or every app (null, or All apps)
        String scope = row != null ? row : homeTab;
        boolean main = scope == null || scope.equals(Categories.ALL);
        // the tvOS home shows only its first two rows: the apps after them keep their places
        if (main) {
            Set<String> shown = new HashSet<>(order);
            for (String tag : AppRepo.order(prefs)) if (shown.add(tag)) order.add(tag);
            AppRepo.saveDock(prefs, order);
        } else {
            Categories.reorder(this, scope, order);
        }
        hud(R.drawable.ic_check, getString(R.string.your_apps_are_arranged));
        wake();
    }

    private void moveEditing(int d) {
        int i = gridAt, j = i + d;
        // the live inputs and Watch always open the grid
        if (j < fixedTiles() || j >= cells.size()) return;
        // on category rows, only along its own row; the row slides first so the move lands in view
        if (rows && (j >= rowIdx.length || rowIdx[j] != rowIdx[i])) return;
        if (rows) {
            cells.add(j, cells.remove(i));
            gridAt = j;
            RowInfo row = rowInfo.get(rowIdx[j]);
            // its next slide starts afresh from where the tiles are put now
            if (row.glide != null) row.glide.stop();
            row.glide = null;
            int first = row.first, col = colIdx[j];
            if (col < first) first = col;
            else if (col >= first + COLS) first = col - COLS + 1;
            row.first = first;
            row.shift = first;
            for (int k = row.start; k < row.start + row.count && k < cells.size(); k++) {
                Cell c = cells.get(k);
                c.animate().translationX(cellX(k)).translationY(cellY(k)).alpha(rowAlpha(k)).setDuration(k == j ? 420 : 360)
                        .setStartDelay(0).setInterpolator(k == j ? Ui.SPRING : Ui.EMPHASIZED).start();
            }
            scrollTo(scrollFor(rowIdx[j]));
            glideToCell();
            return;
        }
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
            String port = String.valueOf(info.loadLabel(this)).replaceAll("(?i)^hdmi\\s*(\\d)", "HDMI $1");
            String label = custom != null && custom.length() > 0 ? String.valueOf(custom) : Cec.name(this, port);
            // the tile's glyph: what's plugged in (a console, a box), as Control Center's deck shows it
            int kind = InputSheet.kindOf(label, type == TvInputInfo.TYPE_HDMI ? Cec.type(this, port) : Cec.UNKNOWN, type == TvInputInfo.TYPE_HDMI, false);
            out.add(new String[]{info.getId(), label, String.valueOf(kind)});
        }
    }

    /** Moved on when {@link #live} is read again in place (a setting changed it) or home stops: an answer still on its way is dropped. */
    private int liveGen;

    /**
     * The inputs read again on home's worker: each one's state and name is a call into the TV's
     * input service, slow on a busy TV, and a box waking up sends several changes in a row.
     */
    private void refreshLive(boolean announce) {
        final int gen = liveGen;
        worker.execute(() -> {
            final List<String[]> now = liveInputs();
            runOnUiThread(() -> {
                if (gen == liveGen && !isDestroyed()) applyLive(now, announce);
            });
        });
    }

    /** A set-top box or console just woke up: it takes the first tile and says so. */
    private void applyLive(List<String[]> now, boolean announce) {
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
            hud(R.drawable.ic_hdmi, getString(R.string.input_is_on, fresh));
            if (!ccOpen && zone == GRID) focusGrid(0);
        }
    }

    // ---- Control Center ----------------------------------------------------------------------

    private void openCc(boolean inputs) {
        if (ccOpen) return;
        // the notification or panel button with Who's watching up: left open, it would cover Control Center and keep the keys
        if (who.isOpen()) closeWho(true);
        if (editing) stopEdit();
        stopPreview();
        beforeCc = getCurrentFocus();
        ccOpen = true;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        // the frost settles over a still home screen (scaling it would re-rasterise all its text
        // every frame); once the frost is opaque the home screen stops drawing altogether
        // (Light mode has the frost whole on the first frame, and the home screen stops with it)
        if (Perf.light(this)) hideHome.run();
        else handler.postDelayed(hideHome, ControlCenter.FROST_MS + 30);
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

    /** Control Center put away as Back does it: opened over an app, that app comes straight back. */
    private void closeCcBack() {
        if (ccOver != null) backToCcApp();
        else closeCc(true);
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

    private View quietVeil;
    private boolean quietScreen;

    /**
     * Screen off while music or a podcast keeps playing: a pitch-black curtain covers the screen,
     * the backlight drops to its lowest step, and everything moving behind it rests until any
     * non-volume/media key is pressed.
     */
    @Override
    public void quietScreen() {
        if (quietScreen) return;
        if (ccOpen) closeCc(false);
        quietScreen = true;
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        backdrop.stop();
        stack.stop();
        stopPreview();
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);
        } catch (RuntimeException ignored) {
        }
        if (quietVeil != null) {
            quietVeil.animate().cancel();
            quietVeil.setVisibility(View.VISIBLE);
            quietVeil.animate().alpha(1f).setDuration(380).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(null).start();
        }
        showHud(R.drawable.ic_moon, getString(R.string.screen_off_press_any_button), 2200);
    }

    private void wakeQuietScreen() {
        if (!quietScreen) return;
        quietScreen = false;
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (RuntimeException ignored) {
        }
        if (quietVeil != null) {
            quietVeil.animate().cancel();
            quietVeil.animate().alpha(0f).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT)
                    .withEndAction(() -> {
                        if (!quietScreen) quietVeil.setVisibility(View.GONE);
                    }).start();
        }
        if (resumed) {
            backdrop.start();
            if (Prefs.on(this, Prefs.STACK_AUTO) && fyW.getVisibility() == View.VISIBLE) stack.start();
            wake();
        }
    }

    @Override
    public Backdrop backdrop() {
        return backdrop;
    }

    @Override
    public void open(Intent intent, View from) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(intent, from, null);
    }

    // ---- clock, status, caption, hud ---------------------------------------------------------

    private void tick() {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(this);
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        stTime.setText(h24 ? t : t + " " + new java.text.DateFormatSymbols().getAmPmStrings()[now.get(Calendar.AM_PM)]);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String greet = hour >= 5 && hour < 12 ? getString(R.string.good_morning)
                : hour >= 12 && hour < 17 ? getString(R.string.good_afternoon)
                : getString(R.string.good_evening);
        Profiles.Profile prof = Profiles.active(this);
        String pname = prof.name != null ? prof.name.trim() : "";
        boolean showName = !pname.isEmpty() && !getString(R.string.me).equalsIgnoreCase(pname);
        hello.setText(showName ? greet + ", " + pname : greet);
        refreshProfileIdentity();
        dateLine.setText(Words.date("EEEE, d MMMM", now.getTimeInMillis()));
        cc.tick(now);
        story.tick(now);
        // the sky and your day every minute; habits only when the hour turns. Not while home is
        // behind something: coming back, onResume reads them all (it used to be done twice in a row)
        if (resumed) {
            refreshStatus();
            refreshWidgets(lastHour != -1 && hour != lastHour);
        }
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

    /**
     * Until when the PIN just typed keeps a kids' profile unlocked: ten minutes at most, and locked
     * again as soon as Settings closes or Aura leaves the screen, so a remote handed back is locked.
     */
    private long unlockedUntil;

    /** {@code then}, after the parents' PIN when this is a kids' profile (see {@link #unlockedUntil}). */
    @Override
    public void guarded(Runnable then) {
        if (!Profiles.kids(this) || !Profiles.hasPin(this) || SystemClock.uptimeMillis() < unlockedUntil) {
            then.run();
            return;
        }
        askTag = null;
        pinFrom = getCurrentFocus();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ask.showPin(getString(R.string.for_grown_ups), pin -> {
            View before = pinFrom;
            pinFrom = null;
            unblockHome();
            if (!Profiles.pinOk(this, pin)) {
                hud(R.drawable.ic_info, getString(R.string.thats_not_the_pin));
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
        unlockedUntil = 0;
        settings.close();
        for (View v : behindCc()) v.setVisibility(View.VISIBLE);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        stack.hold(false);
        if (beforeSettings == null || !beforeSettings.isShown() || !beforeSettings.requestFocus()) focusGrid(gridAt);
        wake();
    }

    /** A setting just changed: put it into effect straight away. */
    /**
     * Browsing, the photograph sits half-soft behind the glass (or nearly sharp, if asked under
     * Settings > Wallpaper); resting, it comes back sharp.
     */
    private float homeDefocus() {
        return Prefs.on(this, Prefs.PHOTO_SHARP) ? 0.12f : 0.38f;
    }

    private void applySetting(String what) {
        switch (what) {
            case "sharp":
                // the pre-mixed resting copy and the live blend both follow the new softness
                backdrop.setSteadyDefocus(homeDefocus());
                if (!racked) backdrop.setDefocus(homeDefocus(), 400);
                break;
            case "tiles":
                switcher.forget();
                // falls through: the grid is rebuilt too
            case "inputs":
                // an input shown again (or its live picture switched): the switched-on list read afresh
                stopPreview();
                liveGen++;
                live.clear();
                live.addAll(liveInputs());
                // falls through: the grid is rebuilt
            case "names":
            case "apps":
                populate();
                // populate() refocuses the grid; the settings list keeps focus while it's open
                home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                break;
            case "watch":
                // its tile comes or goes; switched off, the screen and everything it fetched go with it
                if (!Prefs.watch(this)) watchOff();
                populate();
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
            case "dlna":
                Renderer.apply(this);
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
                liveGen++;
                live.clear();
                live.addAll(liveInputs());
                if (!Prefs.on(this, Prefs.TOP_SHELF)) hideShelf();
                applyLight();
                applyLayout();
                tick();
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
                // tiles sway or stay still with it, and the banners are read again at the size this mode keeps
                populate();
                reloadApps(true);
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
            case "moments":
                // switched off while one was up: it goes now, not eight seconds later
                if (!Prefs.on(this, Prefs.MOMENTS) && momentCard.getVisibility() == View.VISIBLE) {
                    handler.removeCallbacks(hideMoment);
                    hideMoment.run();
                }
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
        Ui.light = light;
        Card.parallax = !light && Prefs.on(this, Prefs.PARALLAX);
        Backdrop.calm = light;
        if (bloom != null) bloom.setVisibility(light ? View.GONE : View.VISIBLE);
        feather();
        speedWatch = watchSpeed();
    }

    /**
     * The soft shade along the foot of the Aura style's page. LOW goes without: a whole screen's
     * width painted again on every frame for a shade you'd hardly miss; the page runs to the edge.
     */
    private void feather() {
        if (bottomFeather != null) bottomFeather.setVisibility(dock || Ui.light ? View.GONE : View.VISIBLE);
    }

    /**
     * A flat colour, or a band shading top to bottom ({@code colors}), that fades by drawing its
     * alpha straight onto the screen: no layer of its own to paint into first, which is a View's
     * default for any alpha below one (a screen-wide buffer every frame of the fade).
     */
    static final class Shade extends View {
        Shade(Context c, int... colors) {
            super(c);
            if (colors.length == 1) setBackgroundColor(colors[0]);
            else setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors));
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
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
        // written once it's on show: a live region only speaks for what changes while it's visible
        hudIcon.setImageResource(icon);
        hudText.setText(text);
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
            splash.setProgress(0.2f, getString(R.string.waking_up));
        } else if (!appsReady) {
            splash.setProgress(0.48f, getString(R.string.arranging_your_apps));
        } else if (!wallReady) {
            splash.setProgress(0.52f, getString(R.string.developing_the_photographs));
        } else {
            splash.setProgress(0.8f, getString(R.string.polishing_the_glass));
            // after the next layout, while the tiles on screen are painted behind it; then reveal
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
        s.finish(getString(R.string.welcome_home_2), this::startIntro, () -> splashGone(s));
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
        // Android's own "Fully drawn" line in its log, for the same moment
        reportFullyDrawn();
        Trouble.started(this, now - createdAt, appsAt > 0 ? appsAt - createdAt : -1, appsLoadMs, appsCount,
                wallAt > 0 ? wallAt - createdAt : -1);
        // the landing never played (no frames came): the home screen its intro would fade in, shown now
        if ((dock ? dockGlass : status).getAlpha() == 0f) {
            for (View v : new View[]{sidebar, status, greeting, skyW, fyW, tdW, gridLabel, homeTabs, topCc, topFind, topSettings, dockGlass, tvosHint}) if (v != null) v.setAlpha(1f);
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
        openWelcome(0);
    }

    private void openWelcome(int start) {
        if (welcome != null || isDestroyed()) return;
        if (settings.isOpen()) closeSettings();
        if (switcher.isOpen()) closeSwitcher();
        if (ccOpen) closeCc(false);
        closeOverlays();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        stopPreview();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        welcome = new Welcome(this, backdrop, new Welcome.Host() {
            @Override
            public List<AppRepo.App> sample() {
                List<AppRepo.App> out = new ArrayList<>();
                // apps nearly everyone knows show the looks best, then whatever is first on this TV
                for (String pkg : new String[]{"com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.firetv.youtube",
                        "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient", "com.amazon.avod", "in.startv.hotstar"}) {
                    AppRepo.App a = find(pkg);
                    if (a != null && out.size() < 4 && !out.contains(a)) out.add(a);
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
        welcome.open(start);
        handler.postDelayed(() -> {
            if (welcome != null && welcome.isOpen()) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        }, 800);
    }

    /** Keeps the interface invisible under the boot screen so nothing pops as it dissolves. */
    private void hideForIntro() {
        for (View v : new View[]{sidebar, status, greeting, skyW, fyW, tdW, gridLabel, homeTabs, topCc, topFind, topSettings, dockGlass, tvosHint, listenW}) if (v != null) v.setAlpha(0f);
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
            for (View v : new View[]{greeting, gridLabel, homeTabs, dockGlass, tvosHint}) if (v != null) enter(v, 0, 0, 500, 600);
            if (!dock) {
                enter(sidebar, 0, 0, 500, 600);
                enter(status, 0, 0, 500, 600);
            }
            for (Widget w : widgets) enter(w, 0, 0, 500, 600);
            backdrop.setDefocus(homeDefocus(), 700);
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
        // the categories stand centered over the grid: they rise smoothly into place
        enter(homeTabs, 0, Ui.dp(10), 1450, 800);
        handler.postDelayed(() -> {
            backdrop.setDefocus(homeDefocus(), 1500);
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

    /**
     * The intro's rise, one animator for every tile: each frame reads its row's light then, as focus
     * moving to another row meanwhile dims and lights the rows through the same alpha.
     */
    private ValueAnimator rise;

    /** The tiles rise into place one after another, row by row. */
    private void cascade() {
        introPending = false;
        if (rise != null) rise.cancel();
        float screen = root.getHeight() > 0 ? root.getHeight() : Ui.dp(540);
        final boolean quiet = quietIntro();
        final List<Cell> going = new ArrayList<>();
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            c.animate().cancel();
            c.setScaleX(1f);
            c.setScaleY(1f);
            // tiles below the screen are simply there (after a restart, all of them only fade in):
            // animating what can't be seen, or two dozen glass tiles on a busy TV, only costs frames
            if (!quiet && cellY(i) > screen) {
                c.setAlpha(risenAlpha(c));
                continue;
            }
            c.setAlpha(0f);
            going.add(c);
            order.add(quiet ? 0 : Math.min(i, 14));
        }
        if (going.isEmpty()) return;
        final long wait = quiet ? 500 : 1350, step = quiet ? 0 : 40, dur = quiet ? 600 : 720;
        final TimeInterpolator ease = quiet ? Ui.SOFT : Ui.EMPHASIZED;
        final long total = wait + order.get(order.size() - 1) * step + dur;
        rise = ValueAnimator.ofFloat(0f, 1f);
        rise.setDuration(total);
        rise.setInterpolator(null);
        rise.addUpdateListener(a -> {
            // by fraction, not play time: with animations off it jumps straight to the end
            float t = a.getAnimatedFraction() * total;
            for (int n = 0; n < going.size(); n++) {
                Cell c = going.get(n);
                float f = ease.getInterpolation(Math.max(0f, Math.min(1f, (t - wait - order.get(n) * step) / dur)));
                c.setAlpha(f * risenAlpha(c));
                if (quiet) continue;
                c.setScaleX(0.9f + 0.1f * f);
                c.setScaleY(0.9f + 0.1f * f);
            }
        });
        rise.start();
    }

    /** A tile's light once risen: on category rows, its row's light now (tiles slid out of sight stay out of sight). */
    private float risenAlpha(Cell c) {
        int k = cells.indexOf(c);
        return rows && k >= 0 && k < colIdx.length ? rowAlpha(k) : 1f;
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
        hud(R.drawable.ic_info, getString(R.string.voice_search_isnt_available));
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
