package app.aura.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Calendar;
import java.util.List;

/**
 * The screensaver: the drifting photographs, each with its place, a few lines about it and who took
 * it, a hairline filling until the next; or famous quotes over them, set as Apple Music sets lyrics.
 * Auto moves between the two every quarter of an hour through a soft fade.
 */
public class Ambient extends DreamService implements Backdrop.Listener {
    private static final long SCENE_MS = 15 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root, stage;
    private View veil;
    private Backdrop backdrop;
    private Story story;
    /** The hairline that fills until the next photograph, and its fill. */
    private FrameLayout progress;
    private View progressFill;
    private Scene scene;
    private boolean ticking, photos, dreaming, firstCaption = true;
    private int turn;
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            story.tick(Calendar.getInstance());
        }
    };
    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            show(choose(++turn), true);
            main.postDelayed(this, SCENE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        Weather.placeSun(this);
        setInteractive(false);
        setFullscreen(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        stage = new FrameLayout(this);
        root.addView(stage, full());
        backdrop = new Backdrop(this);
        backdrop.setIntro(true);
        backdrop.setListener(this);
        // resting, the eye wants change sooner than on the home screen
        backdrop.setShowMs(40_000);
        story = new Story(this, backdrop);
        story.setAlpha(0f);
        root.addView(story, full());
        // a hairline across the foot, filling until the next photograph: one animation per photograph, run by the view itself
        progress = new FrameLayout(this);
        View track = new View(this);
        track.setBackgroundColor(0x1FFFFFFF);
        progress.addView(track, full());
        progressFill = new View(this);
        progressFill.setBackgroundColor(0x99FFF4E6);
        progressFill.setPivotX(0f);
        progressFill.setScaleX(0f);
        progress.addView(progressFill, full());
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, Ui.dp(1.5f)), android.view.Gravity.BOTTOM);
        pl.setMargins(Ui.dp(64), 0, Ui.dp(64), Ui.dp(26));
        progress.setVisibility(View.GONE);
        root.addView(progress, pl);
        veil = new View(this);
        veil.setBackgroundColor(0xFF000000);
        veil.setAlpha(0f);
        root.addView(veil, full());
        setContentView(root);
        // each rest starts somewhere new in the rotation
        turn = Prefs.device(this).getInt("saver_turn", 0);
        Prefs.device(this).edit().putInt("saver_turn", turn + 1).apply();
        show(choose(turn), false);
    }

    // ---- the TV's own screensaver settings (WRITE_SECURE_SETTINGS, granted at setup) ---------

    /** When the screensaver starts: TV Settings keeps it as the screen-off timeout. */
    static final int[] START_MS = {5 * 60_000, 15 * 60_000, 30 * 60_000, 60 * 60_000, 120 * 60_000};
    /** When the TV turns itself off, screensaver or not; -1 is never. */
    static final int[] OFF_MS = {60 * 60_000, 2 * 60 * 60_000, 4 * 60 * 60_000, 8 * 60 * 60_000, -1};

    static boolean canSet(Context c) {
        return Compat.granted(c, android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    private static String me(Context c) {
        return new android.content.ComponentName(c, Ambient.class).flattenToString();
    }

    /** Whether a component string names Aura's screensaver, written either way ("pkg/.Ambient" or in full). */
    private static boolean mine(Context c, String flat) {
        android.content.ComponentName n = flat == null ? null : android.content.ComponentName.unflattenFromString(flat.split(",")[0].trim());
        return n != null && n.equals(new android.content.ComponentName(c, Ambient.class));
    }

    /** Whether Aura's is the TV's screensaver, and switched on. */
    static boolean chosen(Context c) {
        android.content.ContentResolver r = c.getContentResolver();
        return android.provider.Settings.Secure.getInt(r, "screensaver_enabled", 1) != 0
                && mine(c, android.provider.Settings.Secure.getString(r, "screensaver_components"));
    }

    /**
     * Makes Aura's the screensaver, or hands over to another: the one chosen before, else any other
     * the TV has (Android's own Colors, say), else none at all. False if not allowed.
     */
    static boolean choose(Context c, boolean on) {
        android.content.ContentResolver r = c.getContentResolver();
        try {
            if (on) {
                String was = android.provider.Settings.Secure.getString(r, "screensaver_components");
                if (was != null && !was.isEmpty() && !mine(c, was)) Prefs.device(c).edit().putString("saver_prev", was).apply();
                android.provider.Settings.Secure.putString(r, "screensaver_components", me(c));
                android.provider.Settings.Secure.putInt(r, "screensaver_enabled", 1);
                android.provider.Settings.Secure.putInt(r, "screensaver_activate_on_sleep", 1);
            } else {
                String other = Prefs.device(c).getString("saver_prev", null);
                if (other == null || mine(c, other)) other = android.provider.Settings.Secure.getString(r, "screensaver_default_component");
                if (other == null || mine(c, other)) other = anotherDream(c);
                if (other == null) android.provider.Settings.Secure.putInt(r, "screensaver_enabled", 0);
                else android.provider.Settings.Secure.putString(r, "screensaver_components", other);
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Any screensaver on the TV other than Aura's. */
    private static String anotherDream(Context c) {
        for (android.content.pm.ResolveInfo ri : c.getPackageManager().queryIntentServices(
                new Intent(DreamService.SERVICE_INTERFACE), 0)) {
            if (ri.serviceInfo == null || ri.serviceInfo.packageName.equals(c.getPackageName())) continue;
            return new android.content.ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name).flattenToString();
        }
        return null;
    }

    static int startMs(Context c) {
        return android.provider.Settings.System.getInt(c.getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60_000);
    }

    static int offMs(Context c) {
        return android.provider.Settings.Secure.getInt(c.getContentResolver(), "sleep_timeout", -1);
    }

    /**
     * The next of {@code steps} longer than {@code now} (a TV's own 10 minutes goes on to 15), back to
     * the first after the last; never (-1) counts as longest. Written to the TV.
     */
    static int next(Context c, boolean start, int now) {
        int[] steps = start ? START_MS : OFF_MS;
        long cur = now < 0 ? Long.MAX_VALUE : now;
        int v = steps[0];
        for (int s : steps) {
            if ((s < 0 ? Long.MAX_VALUE : s) > cur) {
                v = s;
                break;
            }
        }
        try {
            if (start) {
                android.provider.Settings.System.putInt(c.getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT, v);
                // Android switches off at whichever comes first: a screensaver starting after the TV
                // has turned off would never show, so turning off moves to just after it
                int off = offMs(c);
                if (off >= 0 && off < v) {
                    int later = -1;
                    for (int s : OFF_MS) {
                        if (s >= v) {
                            later = s;
                            break;
                        }
                    }
                    android.provider.Settings.Secure.putInt(c.getContentResolver(), "sleep_timeout", later);
                }
            } else {
                android.provider.Settings.Secure.putInt(c.getContentResolver(), "sleep_timeout", v);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return now;
        }
        return v;
    }

    /** "15 minutes", "2 hours", "Never". */
    static String span(int ms) {
        if (ms < 0) return "Never";
        int min = ms / 60_000;
        if (min < 60) return min + (min == 1 ? " minute" : " minutes");
        int h = min / 60;
        return h + (h == 1 ? " hour" : " hours");
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * The home screen's way to make room while the screensaver plays, set while it exists (see
     * HomeActivity): two sets of full-screen photographs at once is more than a small TV can hold.
     */
    static volatile Runnable makeRoom;

    /**
     * A TV where the screensaver's photographs and the home screen's shouldn't be held at once:
     * Android 7 and older (photographs count against Aura's own memory limit there, which is how
     * the screensaver ran out of memory on Fire OS 6), or 2 GB of memory or less.
     */
    static boolean tight(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 26 || Perf.lowEnd(c)) return true;
        android.app.ActivityManager am = Compat.svc(c, android.app.ActivityManager.class);
        if (am == null) return false;
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 && mi.totalMem <= (2048L << 20);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        Runnable room = makeRoom;
        if (room != null && tight(this)) room.run();
        startScene();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ticking = true;
        story.weather(Weather.now(this));
        story.tick(Calendar.getInstance());
        story.reveal(1400);
        if (Prefs.saver(this).equals("auto")) main.postDelayed(rotate, SCENE_MS);
        listen();
        // while the TV rests, the apps you use get compiled (set up on this TV only)
        Warm.start(this);
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        if (music != null) {
            MusicStrip m = music;
            music = null;
            m.stop();
            root.removeView(m);
        }
        Warm.stop();
        main.removeCallbacks(rotate);
        stopScene();
        if (backdrop != null) backdrop.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        if (ticking) {
            unregisterReceiver(tick);
            ticking = false;
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null);
        stopScene();
        if (backdrop != null) {
            backdrop.release();
            backdrop = null;
        }
        if (stage != null) stage.removeAllViews();
        if (root != null) root.removeAllViews();
        scene = null;
        story = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onWallpaperChanged(Backdrop.Wallpaper w, float lumaTop, float lumaBottom, boolean first) {
        if (photos) story.set(w, !first);
        if (!photos) return;
        // the line fills again, over exactly the time until the next photograph
        progressFill.animate().cancel();
        progressFill.setScaleX(0f);
        progressFill.animate().scaleX(1f).setDuration(backdrop.cycleMs()).setStartDelay(0)
                .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        // with the quotes, a new photograph brings a new quote
        if (!first && scene instanceof Sayings) ((Sayings) scene).turnNow();
    }

    // ---- music ------------------------------------------------------------------------------------

    /** The song under the quote, while one plays; gone when it stops or the screensaver ends. */
    private MusicStrip music;

    /**
     * Music left playing when the TV goes to rest: the quotes over the photographs, the quote a
     * little higher, and the song in a quiet player beneath it, lined up with the words.
     */
    private void listen() {
        MediaWatch.Playing p = MediaWatch.now(this);
        if (p == null || !p.playing || p.controller == null || root == null || backdrop == null) return;
        main.removeCallbacks(rotate);
        if (!(scene instanceof Sayings)) build("sayings");
        if (scene instanceof Sayings) ((Sayings) scene).setCentre(0.38f);
        music = new MusicStrip(this);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.START);
        // the quote's own left edge (150 of 1080 lines), and clear of the hairline at the foot
        ml.leftMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 150f / 1080f);
        ml.bottomMargin = Ui.dp(64);
        root.addView(music, root.indexOfChild(veil), ml);
        final MusicStrip mine = music;
        music.start(p.controller, () -> {
            // the music stopped: the quote settles back to the middle, the photographs carry on
            if (music != mine) return;
            root.removeView(mine);
            music = null;
            if (scene instanceof Sayings) ((Sayings) scene).setCentre(0.46f);
            if (Prefs.saver(this).equals("auto")) main.postDelayed(rotate, SCENE_MS);
        });
    }

    // ---- choosing and switching scenes --------------------------------------------------------

    /** The scene for this turn: the one chosen in Settings, or in Auto the photographs and the quotes by turns. */
    private String choose(int n) {
        String mode = Prefs.saver(this);
        if (!mode.equals("auto")) return mode;
        return Compat.mod(n, 2) == 0 ? "photos" : "sayings";
    }

    /** Moves to {@code kind}; with {@code fade}, through a soft dip to black. */
    private void show(String kind, boolean fade) {
        if (!fade) {
            build(kind);
            return;
        }
        veil.animate().alpha(1f).setDuration(900).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            build(kind);
            veil.animate().alpha(0f).setDuration(1400).setStartDelay(300).setInterpolator(Ui.SOFT).withEndAction(null).start();
        }).start();
    }

    private void build(String kind) {
        stopScene();
        stage.removeAllViews();
        scene = null;
        // both are the drifting photographs; the quotes lie over them
        photos = true;
        stage.addView(backdrop, full());
        if (kind.equals("sayings")) {
            scene = new Sayings(this);
            stage.addView(scene, full());
        }
        // the quotes carry their own words and clock: the photograph's caption steps aside
        story.setVisibility(scene != null ? View.GONE : View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        story.setClockShown(scene == null || !scene.ownClock());
        if (dreaming) startScene();
    }

    private void startScene() {
        if (scene != null) scene.start();
        backdrop.start();
    }

    private void stopScene() {
        if (scene != null) scene.stop();
        backdrop.stop();
    }
}
