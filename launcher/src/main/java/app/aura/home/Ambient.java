package app.aura.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Calendar;

/** Screensaver: the drifting photograph with its story and the time, in the same type as the home screen. */
public class Ambient extends DreamService implements Backdrop.Listener {
    private Backdrop backdrop;
    private Story story;
    private boolean ticking;
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            story.tick(Calendar.getInstance());
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        Weather.placeSun(this);
        setInteractive(false);
        setFullscreen(true);
        FrameLayout root = new FrameLayout(this);
        backdrop = new Backdrop(this);
        backdrop.setIntro(true);
        backdrop.setListener(this);
        root.addView(backdrop, full());
        root.addView(new Scrim(this, true), full());
        story = new Story(this, backdrop);
        story.setAlpha(0f);
        root.addView(story, full());
        setContentView(root);
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        backdrop.start();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ticking = true;
        story.weather(Weather.now(this));
        story.tick(Calendar.getInstance());
        story.reveal(1400);
    }

    @Override
    public void onDreamingStopped() {
        backdrop.stop();
        if (ticking) {
            unregisterReceiver(tick);
            ticking = false;
        }
        super.onDreamingStopped();
    }

    @Override
    public void onWallpaperChanged(Backdrop.Wallpaper w, float lumaTop, float lumaBottom, boolean first) {
        story.set(w, !first);
    }
}
