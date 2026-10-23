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
    private static final int[] WIDGET_X = {0, 278, 616}, WIDGET_W = {260, 320, 200};
    /** Browsing, the photograph sits half-soft behind the glass; resting, it comes back sharp. */
    private static final float HOME_DEFOCUS = 0.6f;
    private static final int SIDE = 0, WIDGETS = 1, GRID = 2;
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
    private TextView skyKicker, skyTitle, skyRise, skySet, tdValue, tdSub, tdMem;
    private ImageView stWifi, stBt, stWx, hudIcon;
    private TextView stTemp;
    private SmartStack stack;
    private ControlCenter cc;

    private final List<Pad> side = new ArrayList<>();
    private final List<String> sideLabels = new ArrayList<>();
    private final List<Widget> widgets = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final int[] widgetGlow = {0xFFFFC98A, 0xFF8AA0C0, DAY_GLOW};
    private List<AppRepo.App> apps = new ArrayList<>();
    private MediaWatch.Playing playing;
    private TextView tdKicker;
    private int zone = GRID, contentZone = GRID, sideAt, widgetAt, gridAt, lastCol = -1, lastHour = -1;
    private float scrollTarget;
    private ValueAnimator scrollAnim, wiggle;
    private boolean ccOpen, racked, photo, resumed, introPending, editing, held, tipShown, dashboard = true;
    private boolean wallReady, appsReady, splashFinishing;
    private long splashAt, lastStep;
    private View beforeCc;
    private int swallowUp = -1;
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
            if (!held && !photo && !ccOpen && !editing && f instanceof Card) ((Card) f).glint();
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
        Ui.init(this);
        prefs = getSharedPreferences("aura", MODE_PRIVATE);
        // the TV's own city, if known, before anything asks the sun where it is
        Weather.placeSun(this);
        tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        buildUi();
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
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
        if (Prefs.on(this, Prefs.STACK_AUTO)) stack.start();
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
    }

    @Override
    protected void onStop() {
        super.onStop();
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
        if (!hasWindowFocus()) {
            // back from an app with Home: keep your place on the tile you left from
            if (ccOpen) closeCc(false);
            return;
        }
        if (editing) stopEdit();
        if (ccOpen) closeCc(true);
        else focusGrid(0);
        wake();
    }

    @Override
    public void onBackPressed() {
        if (ccOpen) {
            if (!cc.back()) closeCc(true);
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
        LinearLayout m = new LinearLayout(this);
        m.setGravity(Gravity.CENTER_VERTICAL);
        TextView ml = Ui.caps(this, 6.5f, 0x8CFFFFFF, 0.24f);
        ml.setText("MEMORY");
        m.addView(ml, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tdMem = Ui.text(this, 9.5f, Ui.medium, 0xCCFFFFFF);
        m.addView(tdMem);
        d.addView(m, lp(MATCH, WRAP, 0, 0));
        memory = new Meter(this);
        d.addView(memory, lp(MATCH, Ui.dp(4), 0, 6));
        tdW.addView(d, match());
        tdW.onClick = () -> {
            if (playing == null) {
                openCc(false);
                return;
            }
            // play or pause whatever is playing, without leaving home
            if (playing.playing) playing.controller.getTransportControls().pause();
            else playing.controller.getTransportControls().play();
            handler.postDelayed(() -> refreshWidgets(false), 400);
        };
        placeWidget(tdW, 2);
    }

    private void placeWidget(Widget w, int i) {
        page.addView(w, frame(Ui.dp(WIDGET_W[i]), Ui.dp(WIDGET_H), Gravity.TOP | Gravity.START,
                Ui.dp(PAGE_X + WIDGET_X[i]), Ui.dp(WIDGET_Y), 0, 0));
        widgets.add(w);
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
            // resting on a live source shows its picture, muted, inside the tile
            cell.card.focusListener = chain(cell.card.focusListener, (card, gained) -> {
                handler.removeCallbacks(startPreview);
                if (gained) {
                    previewId = id;
                    previewCell = cell;
                    handler.postDelayed(startPreview, 550);
                } else {
                    stopPreview();
                }
            });
            cells.add(cell);
        }
        List<String> order = AppRepo.dock(prefs, apps);
        List<AppRepo.App> sorted = new ArrayList<>();
        for (String pkg : order) sorted.add(find(pkg));
        for (AppRepo.App a : apps) if (!order.contains(a.pkg)) sorted.add(a);
        sorted.removeAll(hiddenApps());
        boolean glassTiles = Prefs.on(this, Prefs.GLASS_TILES), namesAlways = Prefs.on(this, Prefs.NAMES_ALWAYS);
        for (final AppRepo.App a : sorted) {
            // glass panes with the app's mark, tinted in its colour (or, if chosen, the original banners)
            final Cell cell = new Cell(this, glassTiles ? Tiles.glass(a) : Tiles.app(a), a.color, a.label, a.pkg, TILE_W, TILE_H);
            if (glassTiles) cell.card.glass = backdrop;
            cell.setNamesAlways(namesAlways);
            cell.card.sway = true;
            cell.card.onClick = () -> launch(a.launch, cell.card, a.label);
            cell.card.onLongClick = this::startEdit;
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
        gridLabel.setText("YOUR APPS  ·  " + sorted.size());
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

    private void launch(Intent intent, View from, String label) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(intent, opts);
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
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                applySky(now, rise, set, up, next, phase);
                applyToday(today, top);
                if (s != null) applyStack(cw, s, r, top, now);
            });
        });
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
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        memory.set(mi.totalMem > 0 ? mi.availMem / (float) mi.totalMem : 0f);
        tdMem.setText(mi.availMem / (1024 * 1024) + " MB free");
    }

    /**
     * The smart stack's cards, most useful first: everything you're partway through, your usual
     * app for this hour, what you opened last, and what you've watched most today.
     */
    private void applyStack(List<Continue.Item> cw, Map<String, Float> score, Object[] recent, String top, long now) {
        List<SmartStack.Slide> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Continue.Item it : cw) {
            SmartStack.Slide s = new SmartStack.Slide();
            AppRepo.App owner = it.pkg == null ? null : find(it.pkg);
            s.kicker = "CONTINUE WATCHING" + (owner != null ? "  ·  " + owner.label.toUpperCase(Locale.getDefault()) : "");
            s.title = it.title;
            s.sub = it.sub;
            s.art = it.art;
            s.progress = it.progress;
            s.intent = it.intent;
            s.label = it.title;
            if (owner != null) {
                s.color = owner.color;
                if (s.art == null) s.mark = Tiles.glass(owner);
            }
            out.add(s);
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
        AppRepo.App last = recent != null ? find((String) recent[0]) : null;
        if (last != null && used.add(last.pkg)) out.add(appSlide(last, "JUMP BACK IN", "Opened " + Habits.ago(now - (Long) recent[1])));
        AppRepo.App most = top != null && watchable(top) ? find(top) : null;
        if (most != null && used.add(most.pkg)) out.add(appSlide(most, "MOST WATCHED TODAY", "Where most of today's screen time went"));
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

    private SmartStack.Slide appSlide(AppRepo.App a, String kicker, String sub) {
        SmartStack.Slide s = new SmartStack.Slide();
        s.kicker = kicker;
        s.title = a.label;
        s.sub = sub;
        s.mark = Tiles.glass(a);
        s.color = a.color;
        s.intent = a.launch;
        s.label = a.label;
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
        boolean top = target == 0;
        if (top == dashboard) return;
        dashboard = top;
        for (View v : new View[]{greeting, skyW, fyW, tdW}) {
            v.animate().alpha(top ? 1f : 0f).setDuration(top ? 420 : 240).setStartDelay(top ? 120 : 0)
                    .setInterpolator(Ui.SOFT).start();
        }
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

    private static int widgetForCol(int col) {
        return col <= 1 ? 0 : col <= 3 ? 1 : 2;
    }

    private int colForWidget(int w) {
        return lastCol >= 0 && widgetForCol(lastCol) == w ? lastCol : new int[]{0, 2, 4}[w];
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
        preview.setZOrderMediaOverlay(true);
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
        i = widgets.indexOf(n);
        if (i >= 0) {
            zone = contentZone = WIDGETS;
            widgetAt = i;
            glide(Ui.dpf(PAGE_X + WIDGET_X[i] + WIDGET_W[i] / 2f), Ui.dpf(WIDGET_Y + WIDGET_H / 2f), widgetGlow[i]);
            return;
        }
        for (i = 0; i < cells.size(); i++) {
            if (cells.get(i).card == n) {
                zone = contentZone = GRID;
                gridAt = i;
                glideToCell();
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
        if (resumed && !ccOpen && !editing) {
            handler.postDelayed(rack, RACK_MS);
            handler.postDelayed(goPhoto, PHOTO_MS);
        }
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
        if (zone == SIDE && side.indexOf(f) < 0 || zone == WIDGETS && widgets.indexOf(f) < 0) zone = contentZone;
        switch (zone) {
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
                        else if (!held) {
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

    // ---- rearranging -------------------------------------------------------------------------

    /** Hold OK on an app: it wiggles, the arrows carry it through the grid and the others make room. */
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
            if (!digital || info.isHidden(this)) continue;
            if (tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = info.loadCustomLabel(this);
            String label = String.valueOf(custom != null && custom.length() > 0 ? custom : info.loadLabel(this));
            out.add(new String[]{info.getId(), label.replaceAll("(?i)^hdmi\\s*(\\d)", "HDMI $1")});
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

    @Override
    public void closeCc(boolean animate) {
        if (!ccOpen) return;
        ccOpen = false;
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
            case "names":
            case "apps":
                populate();
                // populate() refocuses the grid; the settings list keeps focus while it's open
                home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                break;
            case "stack":
                if (Prefs.on(this, Prefs.STACK_AUTO)) stack.start();
                else stack.stop();
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
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        stWifi.setImageAlpha(nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? 255 : 80);
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
            wake();
        });
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
