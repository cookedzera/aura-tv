package app.aura.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The screensaver: one scene at a time under the same quiet clock and caption. Masterpieces from
 * an open museum collection; the real wind over the country, right now; tonight's real sky; Earth
 * from the space station (on the hardware video layer); a mosaic of your photos; the drifting
 * photographs. Auto chooses by the light and the weather, and moves on every quarter of an hour
 * through a soft fade, so a long rest never sits on one picture. Anything amiss falls back to the
 * photographs.
 */
public class Ambient extends DreamService implements Backdrop.Listener {
    private static final long FADE = 1200, SCENE_MS = 15 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root, stage;
    private View veil;
    private Backdrop backdrop;
    private Story story;
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

    // flyovers
    private SurfaceView video;
    private View curtain;
    private MediaPlayer player;
    private List<File> clips;
    private int clipAt;
    private boolean surfaceReady;
    private final Runnable fadeOut = () -> {
        if (curtain != null) curtain.animate().alpha(1f).setDuration(FADE).setInterpolator(Ui.SOFT).start();
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
        story = new Story(this, backdrop);
        story.setAlpha(0f);
        root.addView(story, full());
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
            if (start) android.provider.Settings.System.putInt(c.getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT, v);
            else android.provider.Settings.Secure.putInt(c.getContentResolver(), "sleep_timeout", v);
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

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        startScene();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ticking = true;
        story.weather(Weather.now(this));
        story.tick(Calendar.getInstance());
        story.reveal(1400);
        if (Prefs.saver(this).equals("auto")) main.postDelayed(rotate, SCENE_MS);
        // while the TV rests, the apps you use get compiled (set up on this TV only)
        Warm.start(this);
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        Warm.stop();
        main.removeCallbacks(rotate);
        stopScene();
        if (ticking) {
            unregisterReceiver(tick);
            ticking = false;
        }
        super.onDreamingStopped();
    }

    @Override
    public void onWallpaperChanged(Backdrop.Wallpaper w, float lumaTop, float lumaBottom, boolean first) {
        if (photos) story.set(w, !first);
    }

    // ---- choosing and switching scenes --------------------------------------------------------

    private List<File> flyoverClips() {
        if (!Prefs.on(this, Prefs.FLYOVERS) || !Flyovers.supported()) return new ArrayList<>();
        return Flyovers.ready(this);
    }

    /**
     * The scene for this turn. A chosen one is kept (if it can show here); Auto picks by the light
     * and the weather: the museum and the live wind at any hour, the real sky on clear nights,
     * and by day Earth from space, the mosaic or the photographs.
     */
    private String choose(int n) {
        String mode = Prefs.saver(this);
        boolean space = !flyoverClips().isEmpty();
        if (!mode.equals("auto")) {
            if (mode.equals("space") && !space) return "photos";
            if (mode.equals("sky") && !Sky.available(this)) return "photos";
            if (mode.equals("museum") && !Museum.available(this)) return "photos";
            if (mode.equals("wind") && !Wind.available(this)) return "photos";
            return mode;
        }
        long t = System.currentTimeMillis();
        boolean dark = Sun.elevation(t) < -6;
        Weather.Now w = Weather.now(this);
        boolean cloudy = w != null && w.code >= 2;
        List<String> pick = new ArrayList<>();
        if (Museum.available(this)) pick.add("museum");
        if (Wind.available(this)) pick.add("wind");
        if (dark) {
            if (!cloudy && Sky.available(this)) pick.add("sky");
            pick.add("mosaic");
        } else {
            if (space) pick.add("space");
            pick.add("mosaic");
            pick.add("photos");
        }
        return pick.get(Compat.mod(n, pick.size()));
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
        photos = false;
        video = null;
        curtain = null;
        switch (kind) {
            case "space":
                clips = flyoverClips();
                if (clips.isEmpty()) {
                    photos();
                    break;
                }
                video();
                break;
            case "sky":
                scene = new Sky(this);
                break;
            case "museum":
                scene = new Museum(this);
                break;
            case "wind":
                scene = new Wind(this);
                break;
            case "mosaic":
                scene = new Mosaic(this);
                break;
            default:
                photos();
        }
        if (scene != null) {
            stage.addView(scene, full());
            scene.setListener((context, title, line, credit, ms) -> {
                story.set(context, title, line, credit, ms, !firstCaption);
                firstCaption = false;
            });
        }
        story.setClockShown(scene == null || !scene.ownClock());
        if (dreaming) startScene();
    }

    private void photos() {
        photos = true;
        stage.addView(backdrop, full());
    }

    private void startScene() {
        if (scene != null) scene.start();
        else if (photos) backdrop.start();
        else if (video != null && surfaceReady) play();
    }

    private void stopScene() {
        if (scene != null) scene.stop();
        if (photos) backdrop.stop();
        release();
    }

    // ---- flyovers ----------------------------------------------------------------------------

    private void video() {
        clipAt = Flyovers.nextStart(this, clips.size());
        surfaceReady = false;
        video = new SurfaceView(this);
        final SurfaceView mine = video;
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                if (video != mine) return;
                surfaceReady = true;
                if (dreaming) play();
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) { }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                if (video != mine) return;
                surfaceReady = false;
                release();
            }
        });
        stage.addView(video, new FrameLayout.LayoutParams(full().width, full().height, Gravity.CENTER));
        curtain = new View(this);
        curtain.setBackgroundColor(0xFF000000);
        stage.addView(curtain, full());
    }

    /** The current clip, from black; a fade back to black just before it ends, then the next. */
    private void play() {
        release();
        if (video == null || clips == null || clips.isEmpty()) return;
        File f = clips.get(Compat.mod(clipAt, clips.size()));
        int i = Flyovers.index(f);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(f.getPath());
            mp.setDisplay(video.getHolder());
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((m, w, h) -> cover(w, h));
            mp.setOnPreparedListener(m -> {
                if (m != player || curtain == null) return;
                m.start();
                long ms = m.getDuration();
                curtain.animate().alpha(0f).setDuration(FADE).setStartDelay(150).setInterpolator(Ui.SOFT).start();
                curtain.removeCallbacks(fadeOut);
                if (ms > FADE * 3) curtain.postDelayed(fadeOut, ms - FADE - 200);
                String[] clip = Flyovers.CLIPS[i];
                story.set("From the International Space Station", clip[1], clip[2], "NASA  ·  Public domain", ms, !firstCaption);
                firstCaption = false;
            });
            mp.setOnCompletionListener(m -> {
                clipAt++;
                play();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                main.post(() -> show("photos", false));
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            main.post(() -> show("photos", false));
        }
    }

    /** Fills the screen edge to edge, centred: the space station's 3:2 frames lose a little top and bottom. */
    private void cover(int w, int h) {
        if (video == null || w <= 0 || h <= 0 || root.getWidth() == 0) return;
        float s = Math.max(root.getWidth() / (float) w, root.getHeight() / (float) h);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        int vw = Math.round(w * s), vh = Math.round(h * s);
        if (lp.width == vw && lp.height == vh) return;
        lp.width = vw;
        lp.height = vh;
        video.setLayoutParams(lp);
    }

    private void release() {
        if (curtain != null) curtain.removeCallbacks(fadeOut);
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.reset();
            } catch (IllegalStateException ignored) {
            }
            mp.release();
        }
    }
}
