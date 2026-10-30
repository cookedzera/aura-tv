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
import java.util.Date;
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
    private static final int COLS = 5, TILE_W = 148, TILE_H = 84, GAP = 19, ROW_H = 122;
    private static final int PAGE_X = 104, WIDGET_Y = 104, WIDGET_H = 150, GRID_Y = 296, FOCUS_LINE = 210;
    /** Each widget's natural width; the ones you keep share the row in these proportions. */
    private static final int[] WIDGET_W = {260, 320, 200};
    private static final int WIDGET_ROW = 816, WIDGET_GAP = 18;
    static final String[] WIDGET_IDS = {"sky", "stack", "today"}, WIDGET_NAMES = {"Sky", "Smart Stack", "Today"};
    /** Browsing, the photograph sits half-soft behind the glass; resting, it comes back sharp. */
    private static final float HOME_DEFOCUS = 0.6f;
    private static final int SIDE = 0, WIDGETS = 1, GRID = 2, SHELF = 3;
    private static final int SIDE_GLOW = 0xFF9FB2D6, DAY_GLOW = 0xFF7FB2FF;
    /** Minimum gap between moves while a direction is held: fast, but every hop stays smooth. */
    private static final long HOLD_STEP_MS = 110;
    private static List<AppRepo.App> cache;
    /** False until the first launch of this process has shown the boot screen. */
    private static boolean warm;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final int[] loc = new int[2];

    private SharedPreferences prefs;
    private FrameLayout root, home, page;
    private Backdrop backdrop;
    private Bloom bloom;
    private Scrim scrim;
    private Splash splash;
    private GlassFrame sidebar, status, tip, hud;
    private Widget skyW, fyW, tdW;
    private SkyArc skyArc;
    private Meter memory;
    private LinearLayout greeting;
    private Story story;
    private TextView hello, dateLine, gridLabel, stTime, tipText, hudText;
    private TextView skyKicker, skyTitle, skyRise, skySet, tdValue, tdSub, tdMem, tdData;
    private LinearLayout tdDataRow;
    private ImageView stWifi, stBt, stWx, hudIcon;
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
    private final Runnable warmCc = () -> {
        if (!ccOpen && resumed && !held) cc.refresh();
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
            if (!held && !photo && !ccOpen && !editing && !switcher.isOpen() && f instanceof Card) ((Card) f).glint();
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
            reloadApps();
        }
    };

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // crashing again and again: back quieter, or Home handed back to the TV's own home screen
        Safety.watch(this);
        final String safe = Safety.check(this);
        Ui.init(this);
        prefs = Prefs.of(this);
        Card.parallax = Prefs.on(this, Prefs.PARALLAX);
        // the TV's own city, if known, before anything asks the sun where it is
        Weather.placeSun(this);
        tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        buildUi();
        setContentView(root);
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
            root.addView(splash, match());
            splashAt = SystemClock.uptimeMillis();
            hideForIntro();
            handler.postDelayed(this::finishSplash, 7000);
        } else if (introPending) {
            playIntro();
        } else {
            backdrop.setDefocus(HOME_DEFOCUS, 0);
        }
        if (cache != null) {
            apps = cache;
            populate();
        }
        reloadApps();
        // a fresh Picture of the Day every day, imported in the background
        worker.execute(() -> Daily.schedule(getApplicationContext()));
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        backdrop.start();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, f);
        tick();
        if (tim != null) {
            tim.registerCallback(inputWatch, handler);
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
        if (HomeRole.held(this)) hud(R.drawable.ic_check, "Home now opens Aura");
        else homeHint();
        if (settings.isOpen()) settings.refresh();
    }

    /** Android won't ask (or was told not to): how to choose Aura by hand. */
    void homeHint() {
        hud(R.drawable.ic_info, "Press Home on the remote and choose Aura, then Always");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        wake();
        // back from an app: "jump back in" now knows what you just watched
        refreshWidgets(true);
        refreshStatus();
        handler.removeCallbacks(glintTick);
        handler.postDelayed(glintTick, 4000);
        if (ccOpen) cc.refresh();
        else if (getCurrentFocus() == null && !cells.isEmpty()) focusGrid(gridAt);
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
        // the switcher opens from this the moment Home is pressed again
        handler.removeCallbacks(warmRecentsSoon);
        handler.postDelayed(warmRecentsSoon, 700);
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
        endLaunch();
        backdrop.stop();
        unregisterReceiver(clockReceiver);
        if (tim != null) tim.unregisterCallback(inputWatch);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        backdrop.trim(level);
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
            if (welcome == null && !ccOpen && !switcher.isOpen()) openCcOver(ccFor);
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
            focusGrid(0);
            wake();
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
        // Home means home: whatever is open closes (Settings, a folder, the hold-OK menu, Now Playing)
        if (menu.isOpen()) closeMenu(null);
        if (folderPanel.isOpen()) closeFolder();
        if (nowPlaying.isOpen()) closeNowPlaying();
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
        layoutWidgets(false);
        // Top Shelf sits exactly where the widgets are, and takes their place while it shows
        shelf = new TopShelf(this);
        page.addView(shelf, frame(Ui.dp(WIDGET_ROW), Ui.dp(WIDGET_H), Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(WIDGET_Y), 0, 0));
        gridLabel = Ui.caps(this, 7.5f, 0xB3FFFFFF, 0.26f);
        page.addView(gridLabel, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(GRID_Y - 24), 0, 0));
        buildSidebar();
        buildStatus();

        // photo mode: the photograph's story and the time, as in the screensaver
        story = new Story(this, backdrop);
        story.setAlpha(0f);
        root.addView(story, match());
        cc = new ControlCenter(this, this);
        root.addView(cc.panel, match());
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
            public void hud(int icon, String text) {
                HomeActivity.this.hud(icon, text);
            }
        });
        root.addView(settings.panel, match());
        folderPanel = new FolderPanel(this, new FolderPanel.Host() {
            @Override
            public Tiles.Painter art(AppRepo.App a) {
                String style = Prefs.tileStyle(HomeActivity.this);
                return style.equals("glass") ? Tiles.glass(a) : style.equals("vivid") ? Tiles.vivid(a)
                        : style.equals("midnight") ? Tiles.midnight(a) : Tiles.app(a);
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
                items.add(new AppMenu.Item(R.drawable.ic_info, "App info", () -> {
                    menu.hide();
                    closeFolder();
                    open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)), from);
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
                faces.remove(a.pkg);
                Previews.forget(getApplicationContext(), a.pkg);
                if (recentCache != null) recentCache.remove(a.pkg);
                final Context app = getApplicationContext();
                worker.execute(() -> {
                    Habits.close(app, a.pkg);
                    // set up on this TV: a real Force stop. Otherwise Android stops it only if it counts
                    // it as in the background, and one playing music carries on
                    if (Adb.forceStop(app, a.pkg)) return;
                    ActivityManager am = Compat.svc(app, ActivityManager.class);
                    if (am != null) am.killBackgroundProcesses(a.pkg);
                });
            }

            @Override
            public android.graphics.Bitmap shot(String pkg) {
                return Previews.fresh(pkg);
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
        hello = Ui.text(this, 26, Ui.heavy, 0xFFFFFFFF);
        hello.setIncludeFontPadding(true);
        hello.setLetterSpacing(-0.03f);
        Ui.lift(hello, 10);
        greeting.addView(hello, lp(WRAP, WRAP, 0, 0));
        dateLine = Ui.text(this, 13, Ui.regular, 0xD9FFFFFF);
        Ui.lift(dateLine, 6);
        greeting.addView(dateLine, lp(WRAP, WRAP, 0, 3));
        page.addView(greeting, frame(WRAP, WRAP, Gravity.TOP | Gravity.START, Ui.dp(PAGE_X), Ui.dp(30), 0, 0));
    }

    /** The sky outside: the light now, the next sunrise or sunset, and the sun on its arc. */
    private void buildSky() {
        skyW = new Widget(this, backdrop);
        LinearLayout s = column(18, 17, 18, 14);
        skyKicker = Ui.caps(this, 7.5f, widgetGlow[0], 0.24f);
        s.addView(skyKicker);
        skyTitle = Ui.text(this, 21, Ui.heavy, 0xFFFFFFFF);
        skyTitle.setIncludeFontPadding(true);
        skyTitle.setLetterSpacing(-0.025f);
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
        skyW.onClick = () -> setPhoto(true);
        placeWidget(skyW, 0);
    }

    /** What you'll probably watch: your usual for this hour, else the last thing you opened. */
    private void buildForYou() {
        fyW = new Widget(this, backdrop);
        stack = new SmartStack(this, 26);
        stack.listener = s -> {
            widgetGlow[1] = s.color;
            if (getCurrentFocus() == fyW) bloom.setColor(s.color, 700);
        };
        fyW.addView(stack, match());
        fyW.onClick = () -> {
            SmartStack.Slide s = stack.current();
            if (s != null && s.intent != null) launch(s.intent, fyW, s.label);
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
        tdValue = Ui.text(this, 30, Ui.heavy, 0xFFFFFFFF);
        tdValue.setIncludeFontPadding(true);
        tdValue.setLetterSpacing(-0.035f);
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
        tdW.addView(d, match());
        tdW.onClick = () -> {
            if (playing == null) {
                openCc(false);
                return;
            }
            // what's playing, large: artwork, progress, previous / play-pause / next
            openNowPlaying();
        };
        placeWidget(tdW, 2);
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
        List<Integer> shown = new ArrayList<>();
        Set<String> hidden = Prefs.hiddenWidgets(this);
        List<Integer> order = Prefs.widgetOrder(this);
        for (int i : order) if (!hidden.contains(WIDGET_IDS[i])) shown.add(i);
        if (shown.isEmpty()) shown.add(order.get(0));
        float natural = 0;
        for (int i : shown) natural += WIDGET_W[i];
        float k = (WIDGET_ROW - WIDGET_GAP * (shown.size() - 1)) / natural, x = 0;
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
            l.leftMargin = Ui.dp(PAGE_X + x);
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
        addSide(col, R.drawable.ic_mic, "Search", this::voiceSearch, false);
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
        final Context app = getApplicationContext();
        worker.execute(() -> {
            final List<AppRepo.App> list = AppRepo.load(app);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                boolean changed = cells.isEmpty() || !samePackages(list, apps);
                apps = list;
                cache = list;
                if (changed) populate();
            });
        });
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

    /** Live inputs first, then your apps in your order, then anything new, alphabetically. */
    private void populate() {
        String keep = gridAt < cells.size() ? cells.get(gridAt).tag : null;
        // on the first fill the window may already have parked focus on the rail; the grid takes it
        boolean firstFill = cells.isEmpty(), hadFocus = zone == GRID && getCurrentFocus() instanceof Card;
        stopPreview();
        for (Cell c : cells) page.removeView(c);
        cells.clear();
        for (String[] in : live) {
            final String id = in[0], label = in[1];
            final Cell cell = new Cell(this, Tiles.input(this, label), 0xFF46D7C8, label, "input:" + id, TILE_W, TILE_H);
            cell.card.glass = backdrop;
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
        String style = Prefs.tileStyle(this);
        boolean glassTiles = style.equals("glass"), namesAlways = Prefs.on(this, Prefs.NAMES_ALWAYS);
        for (Object entry : entries) {
            if (entry instanceof Folders.Folder) {
                final Folders.Folder f = (Folders.Folder) entry;
                final Cell cell = new Cell(this, Tiles.folder(folderApps(f), !glassTiles), 0xFF9AA6BA, f.name, f.tag(), TILE_W, TILE_H);
                if (glassTiles) cell.card.glass = backdrop;
                cell.setNamesAlways(namesAlways);
                cell.card.sway = true;
                cell.card.onClick = () -> openFolder(f.id);
                cell.card.onLongClick = this::openMenu;
                cells.add(cell);
                continue;
            }
            final AppRepo.App a = (AppRepo.App) entry;
            sorted.add(a);
            // glass panes tinted in the app's colour, its colour vivid, midnight, or the original banners
            Tiles.Painter art = glassTiles ? Tiles.glass(a) : style.equals("vivid") ? Tiles.vivid(a)
                    : style.equals("midnight") ? Tiles.midnight(a) : Tiles.app(a);
            final Cell cell = new Cell(this, art, a.color, a.label, a.pkg, TILE_W, TILE_H);
            if (glassTiles) cell.card.glass = backdrop;
            cell.setNamesAlways(namesAlways);
            cell.card.sway = true;
            cell.card.onClick = () -> launch(a.launch, cell.card, a.label);
            cell.card.onLongClick = this::openMenu;
            cells.add(cell);
        }
        int at = 0;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            // only the focused tile casts a shadow: two dozen resting shadows cost frames for nothing
            c.card.setElevation(0f);
            c.setTranslationX(cellX(i));
            c.setTranslationY(cellY(i));
            page.addView(c, new FrameLayout.LayoutParams(Ui.dp(TILE_W), WRAP));
            if (c.tag.equals(keep)) at = i;
        }
        gridLabel.setText("YOUR APPS  ·  " + (sorted.size() + foldered.size()));
        page.getLayoutParams().height = Ui.dp(GRID_Y + ((cells.size() + COLS - 1) / COLS) * ROW_H + 40);
        page.requestLayout();
        gridAt = at;
        if (!ccOpen && (firstFill || hadFocus || getCurrentFocus() == null)) focusGrid(at);
        if (introPending && splash == null) cascade();
        if (!appsReady) {
            appsReady = true;
            progressSplash();
        }
        refreshWidgets(true);
    }

    private float cellX(int i) {
        return Ui.dpf(PAGE_X + (i % COLS) * (TILE_W + GAP));
    }

    private float cellY(int i) {
        return Ui.dpf(GRID_Y + (i / COLS) * ROW_H);
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
        } catch (ActivityNotFoundException | SecurityException e) {
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
        if (beforeSwitcher == null || !beforeSwitcher.isAttachedToWindow() || !beforeSwitcher.requestFocus()) focusGrid(gridAt);
    }

    private void launch(Intent intent, View from, String label) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(intent, opts);
            String pkg = intent.getPackage() != null ? intent.getPackage()
                    : intent.getComponent() != null ? intent.getComponent().getPackageName() : null;
            Habits.opened(getApplicationContext(), pkg);
            if (pkg != null && recentCache != null) {
                // first in the switcher at once, before the history catches up
                java.util.LinkedHashMap<String, Long> r = new java.util.LinkedHashMap<>();
                r.put(pkg, System.currentTimeMillis());
                for (Map.Entry<String, Long> e : recentCache.entrySet()) if (!e.getKey().equals(pkg)) r.put(e.getKey(), e.getValue());
                recentCache = r;
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            hud(R.drawable.ic_info, "Couldn't open " + label);
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
            if (habits && !list.isEmpty()) {
                score = Habits.forNow(app);
                Set<String> installed = new HashSet<>();
                for (AppRepo.App a : list) if (watchable(a.pkg)) installed.add(a.pkg);
                recent = Habits.recent(app, installed);
            }
            final Map<String, Float> s = score;
            final Object[] r = recent;
            // what you're partway through outranks habits
            final List<Continue.Item> cw = habits ? Continue.all(app, 4) : null;
            final Extras x = habits ? extras(app) : null;
            final long data = dataToday(app, c.getTimeInMillis());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                applySky(now, rise, set, up, next, phase);
                applyToday(today, top);
                showData(data);
                if (s != null) applyStack(cw, s, r, top, now, x);
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
        // something playing (or paused) takes the widget over: title, who, and OK plays or pauses
        playing = MediaWatch.now(this);
        if (playing != null) {
            AppRepo.App owner = find(playing.pkg);
            tdKicker.setText(("NOW PLAYING" + (owner != null ? "  ·  " + owner.label : "")).toUpperCase(Locale.getDefault()));
            // a title gets two lines rather than being cut short
            tdValue.setSingleLine(false);
            tdValue.setMaxLines(2);
            tdValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tdValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            tdValue.setLineSpacing(0, 1f);
            tdValue.setText(playing.title);
            tdSub.setText(playing.playing ? (playing.by != null ? playing.by : "Playing") : "Paused" + (playing.by != null ? "  ·  " + playing.by : ""));
            widgetGlow[2] = owner != null ? owner.color : DAY_GLOW;
        } else {
            tdKicker.setText("TODAY");
            tdValue.setSingleLine(true);
            tdValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30);
            widgetGlow[2] = DAY_GLOW;
        }
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
        Picks.Pick pick;
        android.graphics.Bitmap pickArt;
    }

    /** Off the main thread: up to four new releases and one random pick, with their pictures. */
    private static Extras extras(Context c) {
        Extras x = new Extras();
        for (Fresh.Show s : Fresh.load(c)) {
            if (x.shows.size() >= 4) break;
            android.graphics.Bitmap b = Fresh.art(s);
            if (b == null) continue;
            x.shows.add(s);
            x.art.add(b);
        }
        if (Prefs.on(c, Prefs.PICKS)) {
            List<Picks.Pick> pool = Picks.load(c);
            if (!pool.isEmpty()) {
                // the same pick all evening, a new one tomorrow
                Picks.Pick p = pool.get((int) ((System.currentTimeMillis() / 86_400_000L) % pool.size()));
                x.pickArt = Picks.poster(p);
                if (x.pickArt != null) x.pick = p;
            }
        }
        return x;
    }

    /**
     * The smart stack's cards, most useful first: a new episode of something you're watching,
     * everything you're partway through, your usual app for this hour, what's new today on your
     * apps, what you opened last, what you've watched most today, and tonight's pick.
     */
    private void applyStack(List<Continue.Item> cw, Map<String, Float> score, Object[] recent, String top, long now, Extras x) {
        List<SmartStack.Slide> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<Fresh.Show> claimed = new HashSet<>();
        for (Continue.Item it : cw) {
            SmartStack.Slide s = new SmartStack.Slide();
            AppRepo.App owner = it.pkg == null ? null : find(it.pkg);
            String app = owner != null ? "  ·  " + owner.label.toUpperCase(Locale.getDefault()) : "";
            Fresh.Show fresh = x == null ? null : sameShow(it.title, x.shows);
            s.kicker = (fresh != null ? "NEW EPISODE OUT" : "CONTINUE WATCHING") + app;
            s.title = it.title;
            s.sub = fresh != null && !fresh.episode.isEmpty() ? fresh.episode : it.sub;
            s.art = it.art;
            s.progress = it.progress;
            s.intent = it.intent;
            s.label = it.title;
            s.fresh = fresh != null;
            // what you're watching stays a little longer than a suggestion
            s.dwellMs = 9_000;
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
            AppRepo.App a = watchable(e.getKey()) ? find(e.getKey()) : null;
            if (a != null && e.getValue() > best) {
                best = e.getValue();
                usual = a;
            }
        }
        if (usual != null) {
            Calendar c = Calendar.getInstance();
            String hour = DateFormat.is24HourFormat(this) ? c.get(Calendar.HOUR_OF_DAY) + ":00"
                    : (c.get(Calendar.HOUR) == 0 ? 12 : c.get(Calendar.HOUR)) + (c.get(Calendar.AM_PM) == Calendar.AM ? " AM" : " PM");
            out.add(appSlide(usual, "YOUR " + hour + " USUAL", "You tend to open it around this time"));
            used.add(usual.pkg);
        }
        // new today on the streaming apps you have, with the show's own artwork
        for (int i = 0; x != null && i < x.shows.size(); i++) {
            Fresh.Show f = x.shows.get(i);
            AppRepo.App owner = find(f.pkg);
            if (claimed.contains(f) || owner == null) continue;
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = "NEW TODAY  ·  " + owner.label.toUpperCase(Locale.getDefault());
            s.title = f.name;
            s.sub = f.episode.isEmpty() ? "Out now on " + owner.label : f.episode;
            s.art = x.art.get(i);
            s.color = owner.color;
            s.icon = owner.icon;
            s.fresh = true;
            s.intent = owner.launch;
            s.label = owner.label;
            out.add(s);
        }
        AppRepo.App last = recent != null ? find((String) recent[0]) : null;
        if (last != null && used.add(last.pkg)) out.add(appSlide(last, "JUMP BACK IN", "Opened " + Habits.ago(now - (Long) recent[1])));
        AppRepo.App most = top != null && watchable(top) ? find(top) : null;
        if (most != null && used.add(most.pkg)) out.add(appSlide(most, "MOST WATCHED TODAY", "Where most of today's screen time went"));
        if (x != null && x.pick != null) {
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = "TONIGHT'S PICK  ·  TOP RATED";
            s.title = x.pick.name;
            s.sub = x.pick.line;
            s.art = x.pickArt;
            s.color = 0xFFE8B04A;
            Intent yt = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube.tv")
                    .putExtra(android.app.SearchManager.QUERY, x.pick.query);
            s.intent = yt.resolveActivity(getPackageManager()) != null ? yt
                    : new Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, x.pick.query);
            s.label = x.pick.name + " trailer";
            s.dwellMs = 6_000;
            out.add(s);
        }
        // nine cards at most, so the dots stay a glance; the pick (last) always keeps its place
        while (out.size() > 9) out.remove(out.size() - 2);
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
        widgetAt = Math.max(0, Math.min(widgets.size() - 1, i));
        scrollTo(0);
        widgets.get(widgetAt).requestFocus();
    }

    private void focusGrid(int i) {
        if (cells.isEmpty() || ccOpen) return;
        gridAt = Math.max(0, Math.min(cells.size() - 1, i));
        scrollTo(scrollFor(gridAt / COLS));
        cells.get(gridAt).card.requestFocus();
    }

    /**
     * The first row shares the screen with the widgets; deeper rows settle on one steady line,
     * but never past the point where the last row sits at the bottom: no empty screen below it.
     */
    private float scrollFor(int row) {
        if (row == 0) return 0;
        int rows = (cells.size() + COLS - 1) / COLS;
        float screen = root.getHeight() > 0 ? root.getHeight() / Ui.density : 540f;
        float end = GRID_Y + (rows - 1) * ROW_H + TILE_H + 44 - screen; // last row and its name in view
        // when everything nearly fits, stay put: a nudge of a few dp isn't worth leaving the widgets for
        if (end < 28) return 0;
        return Math.max(0, Math.min(GRID_Y + row * ROW_H - FOCUS_LINE, end));
    }

    private ValueAnimator bounceAnim;

    /**
     * The end of the apps: the page gives a little, then springs back, so the edge is felt rather
     * than met with nothing. Only the page's position moves, exactly as when scrolling.
     */
    private void bounce(boolean vertical) {
        if (bounceAnim != null && bounceAnim.isRunning()) return;
        final View target = vertical ? page : cells.get(gridAt).card;
        final float base = vertical ? -Ui.dpf(scrollTarget) : 0f, reach = Ui.dpf(vertical ? -16 : 10);
        bounceAnim = ValueAnimator.ofFloat(0f, 1f);
        bounceAnim.setDuration(520);
        bounceAnim.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            // a quick push out, then a damped spring home
            float k = t < 0.22f ? t / 0.22f : (float) (Math.exp(-6 * (t - 0.22f)) * Math.cos(9 * (t - 0.22f)));
            if (vertical) {
                page.setTranslationY(base + reach * k);
                for (Widget w : widgets) if (w.getAlpha() > 0f) w.invalidate();
                for (Cell c : cells) c.card.invalidate();
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
        scrollAnim = ValueAnimator.ofFloat(page.getTranslationY(), -Ui.dpf(target));
        scrollAnim.setDuration(560);
        scrollAnim.setInterpolator(Ui.EMPHASIZED);
        scrollAnim.addUpdateListener(a -> {
            page.setTranslationY((float) a.getAnimatedValue());
            // the glass samples the photo at its own position, so it is repainted as it travels
            for (Widget w : widgets) if (w.getAlpha() > 0f) w.invalidate();
            for (Cell c : cells) c.card.invalidate();
        });
        scrollAnim.start();
        // the greeting and the status pill leave on any scroll (the apps pass beneath them); the
        // widgets only once they're half off screen, so a short scroll never empties the top
        boolean top = target == 0, shown = target < WIDGET_Y + WIDGET_H / 2f;
        if (top != greetingShown) {
            greetingShown = top;
            fade(greeting, top);
            fade(status, top);
        }
        if (shown != dashboard) {
            dashboard = shown;
            for (View v : new View[]{skyW, fyW, tdW}) fade(v, shown);
        }
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
            glide(Ui.dpf(PAGE_X + widgetX[i] + widgetW[i] / 2f), Ui.dpf(WIDGET_Y + WIDGET_H / 2f), glowOf(widgets.get(i)));
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
        if (!held) backdrop.setParallax(-(cx / w - 0.5f) * Ui.dpf(24));
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
            handler.postDelayed(goPhoto, PHOTO_MS);
        }
    }

    private boolean overlayOpen() {
        return welcome != null || settings != null && settings.isOpen() || folderPanel != null && folderPanel.isOpen()
                || switcher != null && switcher.isOpen() || nowPlaying != null && nowPlaying.isOpen()
                || menu != null && menu.isOpen();
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
        } else {
            racked = false;
            backdrop.setDefocus(HOME_DEFOCUS, 520);
            bloom.setStrength(glow(), 520);
            story.hide(300);
        }
        home.animate().alpha(on ? 0f : 1f).setDuration(on ? 1600 : 420).setStartDelay(0).setInterpolator(Ui.SOFT).start();
    }

    // ---- keys --------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (splash != null && !isVolumeKey(code)) return true;
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
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeNowPlaying();
                return swallow(code);
            }
            // only the three buttons take focus; up and down have nowhere to go
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
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
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeFolder();
                return swallow(code);
            }
            return super.dispatchKeyEvent(e);
        }
        if (first && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_INFO)) {
            if (ccOpen) closeCc(true);
            else openCc(false);
            return swallow(code);
        }
        if (editing) return editKey(code, e);
        if (ccOpen) return cc.handleKey(code, getCurrentFocus()) ? swallow(code) : super.dispatchKeyEvent(e);
        // the settings list focuses and scrolls itself
        if (settings.isOpen()) return super.dispatchKeyEvent(e);
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
                || zone == SHELF && shelf.cards.indexOf(f) < 0) zone = contentZone;
        switch (zone) {
            case SHELF:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && shelfAt > 0) shelf.cards.get(shelfAt - 1).requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && shelfAt < shelf.cards.size() - 1) shelf.cards.get(shelfAt + 1).requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusGrid(gridAt);
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
                } else if (code == KeyEvent.KEYCODE_DPAD_UP && !held && widgets.get(widgetAt) == fyW) {
                    // nothing sits above the stack, so Up flips to its next card, as a swipe would on iOS
                    stack.flip();
                }
                return true;
            default: {
                if (cells.isEmpty()) {
                    focusWidget(widgetAt);
                    return true;
                }
                int col = gridAt % COLS, row = gridAt / COLS, n = cells.size();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (col > 0) focusGrid(gridAt - 1);
                        else if (!held) enterSide();
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (col < COLS - 1 && gridAt + 1 < n) focusGrid(gridAt + 1);
                        else if (!held && gridAt == n - 1) bounce(false);
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (row > 0) focusGrid(gridAt - COLS);
                        else if (!held && shelf.shownFor() != null && shelf.shownFor().equals(cells.get(gridAt).tag)
                                && !shelf.cards.isEmpty()) {
                            // up into this app's Top Shelf, onto the card nearest above
                            shelf.cards.get(Math.min(shelf.cards.size() - 1, col * shelf.cards.size() / COLS)).requestFocus();
                        } else if (!held) {
                            lastCol = col;
                            focusWidget(widgetForCol(col));
                        }
                        break;
                    default:
                        if (gridAt + COLS < n) focusGrid(gridAt + COLS);
                        else if (row < (n - 1) / COLS) focusGrid(n - 1);
                        else if (!held) bounce(true);
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
                moveEditing(-COLS);
                return swallow(code);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveEditing(COLS);
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
    private String shelfWanted;

    /**
     * Resting on an app in the top row brings up its Top Shelf a moment later; anywhere else the
     * widgets come back. Content is looked up off the main thread and cached per app.
     */
    private void shelfFor(int i) {
        handler.removeCallbacks(shelfLoad);
        Cell c = cells.get(i);
        boolean top = i < COLS && !c.tag.startsWith("input:") && Prefs.on(this, Prefs.TOP_SHELF) && !editing;
        if (!top) {
            hideShelf();
            return;
        }
        if (c.tag.equals(shelf.shownFor())) return;
        shelfWanted = c.tag;
        handler.postDelayed(shelfLoad, 650);
    }

    private final Runnable shelfLoad = () -> {
        final String pkg = shelfWanted;
        if (pkg == null) return;
        final Context app = getApplicationContext();
        worker.execute(() -> {
            final List<Continue.Item> items = TopShelf.items(app, pkg);
            runOnUiThread(() -> {
                if (isDestroyed() || !pkg.equals(shelfWanted) || zone != GRID || gridAt >= COLS) return;
                if (items.isEmpty()) {
                    hideShelf();
                    return;
                }
                final AppRepo.App a = find(pkg);
                shelf.show(pkg, items, a != null ? a.color : DAY_GLOW, it -> {
                    Intent go = TopShelf.launchFor(it, a);
                    if (go != null) launch(go, shelf.cards.isEmpty() ? cells.get(gridAt).card : shelf.cards.get(shelfAt), it.title);
                });
                for (Widget w : widgets) w.animate().alpha(0f).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT).start();
            });
        });
    };

    private void hideShelf() {
        handler.removeCallbacks(shelfLoad);
        shelfWanted = null;
        if (shelf.shownFor() == null) return;
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
        tdW.requestFocus();
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
        if (menu.isOpen()) closeMenu(null);
        if (editing) stopEdit();
        stopPreview();
        hideTip();
        switcherOver = over;
        if (over != null) for (View v : behindCc()) v.setVisibility(View.INVISIBLE);
        // straight away from what's kept warm; the list refreshes behind it for next time
        if (recentCache != null) showSwitcher(recentCache);
        warmRecents(recentCache == null);
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

    private void warmRecents(boolean thenOpen) {
        final Context app = getApplicationContext();
        final Set<String> launchable = new HashSet<>();
        for (AppRepo.App a : apps) if (watchable(a.pkg)) launchable.add(a.pkg);
        worker.execute(() -> {
            final java.util.LinkedHashMap<String, Long> recent = Habits.recentList(app, launchable, 8);
            final Map<String, Tiles.Painter> f = new java.util.HashMap<>();
            final Map<String, Long> stamps = new java.util.HashMap<>();
            faces(app, recent.keySet(), f, stamps, keptFaces, keptStamps);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                recentCache = recent;
                faces = f;
                keptFaces = f;
                keptStamps = stamps;
                if (thenOpen && !overlayOpen() && !ccOpen && (resumed || switcherOver != null)) {
                    switcher.prepare(recent, stamps);
                    showSwitcher(recent);
                } else {
                    switcher.prepare(recent, stamps);
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
        MediaWatch.Playing playing = null;
        boolean askedPlaying = false;
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
            if (!askedPlaying) {
                askedPlaying = true;
                playing = MediaWatch.now(c);
            }
            if (playing != null && pkg.equals(playing.pkg) && playing.art != null) {
                out.put(pkg, Tiles.screen(playing.art, playing.title, playing.by, playing.progress));
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
        beforeSwitcher = getCurrentFocus();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        // the photograph beneath the frost stops drifting: otherwise the whole screen redraws every 80 ms
        backdrop.stop();
        switcher.open(new java.util.LinkedHashMap<>(recent), switcherOver);
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
            if (beforeSwitcher == null || !beforeSwitcher.isAttachedToWindow() || !beforeSwitcher.requestFocus()) focusGrid(gridAt);
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
                Folders.rename(this, id);
                populate();
                focusTag(pkg);
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
        if (n > 0) items.add(new AppMenu.Item(R.drawable.ic_move, "Move left", () -> moveWidget(w, -1)));
        if (n < widgets.size() - 1) items.add(new AppMenu.Item(R.drawable.ic_move, "Move right", () -> moveWidget(w, 1)));
        if (widgets.size() > 1) items.add(new AppMenu.Item(R.drawable.ic_hide, "Hide", () -> {
            closeMenu(w);
            Prefs.hideWidget(this, key, true);
            layoutWidgets(true);
            focusWidget(Math.min(n, widgets.size() - 1));
            hud(R.drawable.ic_hide, name + " is hidden  ·  Settings brings it back");
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
        if (back == null || !back.isAttachedToWindow() || !back.requestFocus()) focusGrid(gridAt);
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
            hud(R.drawable.ic_check, "New folder: " + f.name + "  ·  hold OK on it to rename");
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
        for (int k = Math.min(i, j); k <= Math.max(i, j); k++) {
            cells.get(k).animate().translationX(cellX(k)).translationY(cellY(k)).setDuration(k == j ? 420 : 360)
                    .setStartDelay(0).setInterpolator(k == j ? Ui.SPRING : Ui.EMPHASIZED).start();
        }
        gridAt = j;
        scrollTo(scrollFor(j / COLS));
        glideToCell();
    }

    // ---- Live Inputs -------------------------------------------------------------------------

    /** HDMI sources that are actually switched on (not just plugged in and asleep). */
    private List<String[]> liveInputs() {
        List<String[]> out = new ArrayList<>();
        if (tim == null) return out;
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
        return out;
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
        if (beforeCc == null || !beforeCc.isAttachedToWindow() || !beforeCc.requestFocus()) focusGrid(gridAt);
        beforeCc = null;
        wake();
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
        refreshWidgets(hour != lastHour);
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

    private void openSettings() {
        if (settings.isOpen() || ccOpen) return;
        if (editing) stopEdit();
        stopPreview();
        hideTip();
        beforeSettings = getCurrentFocus();
        handler.removeCallbacks(rack);
        handler.removeCallbacks(goPhoto);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stack.hold(true);
        settings.open();
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
        if (beforeSettings == null || !beforeSettings.isAttachedToWindow() || !beforeSettings.requestFocus()) focusGrid(gridAt);
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
                layoutWidgets(true);
                break;
            case "update":
                updateDot.setVisibility(Updater.unseen(this) ? View.VISIBLE : View.GONE);
                break;
            case "profile":
                // another person's home screen: rebuilt from their own settings
                recreate();
                break;
            case "shelf":
                if (!Prefs.on(this, Prefs.TOP_SHELF)) hideShelf();
                break;
            case "parallax":
                Card.parallax = Prefs.on(this, Prefs.PARALLAX);
                break;
            case "photos":
            case "daily":
                // pure black, your own photos only, Picture of the Day, or one removed: the wall moves on to the new set
                backdrop.reload();
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
            story.weather(null);
            return;
        }
        stWx.setImageResource(w.icon());
        stTemp.setText(w.temp + "°");
        stWx.setVisibility(View.VISIBLE);
        stTemp.setVisibility(View.VISIBLE);
        story.weather(w);
    }

    private void refreshStatus() {
        ConnectivityManager cm = Compat.svc(this, ConnectivityManager.class);
        stWifi.setImageAlpha(Compat.online(cm) ? 255 : 80);
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        stBt.setImageAlpha(ba != null && ba.isEnabled() ? 255 : 80);
    }

    @Override
    public void onWallpaperChanged(Backdrop.Wallpaper w, float lumaTop, float lumaBottom, boolean first) {
        scrim.adapt(lumaTop, lumaBottom, first ? 0 : 2800);
        // in photo mode the story turns over with the photograph; otherwise it's simply kept current
        story.set(w, photo);
        if (first && !wallReady) {
            wallReady = true;
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
                    handler.postDelayed(HomeActivity.this::finishSplash, Math.max(0, 1900 - held));
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
        s.finish("WELCOME HOME", this::startIntro, () -> {
            root.removeView(s);
            splash = null;
            if (Welcome.due(this)) openWelcome();
            else {
                wake();
                // started from Android's screensaver settings while Aura wasn't running
                if (fromSaverSettings(getIntent())) openSaverSettings();
            }
        });
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
            public void homeHint() {
                HomeActivity.this.homeHint();
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
        for (View v : new View[]{sidebar, status, greeting, skyW, fyW, tdW, gridLabel}) v.setAlpha(0f);
        bloom.setStrength(0f, 0);
    }

    private void startIntro() {
        backdrop.restartIntro();
        playIntro();
        if (!cells.isEmpty()) cascade();
    }

    /** The photograph arrives sharp, then softens as the glass slides in around it. */
    private void playIntro() {
        enter(sidebar, -Ui.dp(34), 0, 800, 1000);
        enter(status, 0, -Ui.dp(12), 950, 900);
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
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
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
