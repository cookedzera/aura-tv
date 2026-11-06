package app.aura.home;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A notification as it arrives on home: one banner at a time, top right where the status pill sits,
 * for five seconds. It never takes the focus nor covers what has it, and never comes near the bottom
 * fifth of the screen, where subtitles are. A newer one takes its place and counts those it
 * replaced; one that comes while there's no room (a panel open, home resting) waits a little, the
 * newest few. An app whose banners were muted in Control Center never shows one. HIGH glides it
 * down on a spring, LOW only fades it in; both fade it out in 150 ms.
 */
final class Banner extends FrameLayout {
    interface Host {
        /** Whether a banner may come up now: home in front, with nothing open over it. */
        boolean bannerRoom();

        /** A banner came (true) or went: the status pill beneath steps aside meanwhile. */
        void bannerShown(boolean shown);
    }

    /** Its place on home's root and its size, in dp; how long it stays, and LOW's way in. */
    static final int W = 300, H = 56, TOP = 23, RIGHT = 40, LOW_IN = 200;
    static final long SHOW_MS = 5000;
    /** Waiting for room: the newest few, each for this long at most, looked at again every second. */
    static final int WAIT_MAX = 3;
    static final long WAIT_MS = 30_000, RETRY_MS = 1000;
    private static final float DROP = 12f;
    private static final int FACES = 24, SEEN = 16;
    private static final TimeInterpolator LINEAR = new LinearInterpolator();

    /** A notification on its way, with its app's name and icon once found, and when it came. */
    private static final class Item {
        MediaWatch.Note note;
        final long at;
        String app;
        Drawable icon;

        Item(MediaWatch.Note note, long at) {
            this.note = note;
            this.at = at;
        }
    }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GlassFrame pane;
    private final LinearLayout content;
    private final ImageView icon;
    private final TextView kick, title, body, count;
    private final View timeLine;
    private final Spring.Mover glide;
    /** On show (null: nothing), and how many others it took the place of. */
    private Item shown;
    private int replaced;
    private final List<Item> waiting = new ArrayList<>(), finding = new ArrayList<>();
    /** Apps' names and icons, looked up off the main thread once each. */
    private final Map<String, Object[]> faces = new HashMap<>();
    private final Set<String> looking = new HashSet<>();
    /** Keys lately shown: an update that asks not to alert again stays quiet. */
    private final List<String> seen = new ArrayList<>();
    private final MediaWatch.Posted posted = this::arrived;
    private final Runnable hide = this::hide, retry = this::flush;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusMoved = (was, now) -> {
        if (shown != null && !room()) hide();
    };

    Banner(Context c, Backdrop backdrop, Host host) {
        super(c);
        this.host = host;
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        pane = new GlassFrame(c, backdrop, H / 2f, 0.55f);
        // with no photograph to sample: the opaque glass every overlay uses
        pane.plainColor = Ui.GLASS_OVERLAY;
        pane.setMinimumHeight(Ui.dp(H));
        // it draws its own rounded shape and holds nothing that spills: LOW spares the clip
        if (Ui.light) pane.setClipToOutline(false);
        content = new LinearLayout(c);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(20), Ui.dp(4));
        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        content.addView(icon, new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        kick = line(c, Ui.FLOOR, Ui.medium, 0x99FFFFFF);
        col.addView(kick);
        title = line(c, 14.5f, Ui.semibold, 0xFFFFFFFF);
        col.addView(title);
        body = line(c, Ui.CAPTION, Ui.regular, 0xBFFFFFFF);
        col.addView(body);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = Ui.dp(12);
        content.addView(col, cl);
        pane.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        // the time left, as a hairline along the foot that shortens (HIGH)
        timeLine = new View(c);
        timeLine.setBackgroundColor(0x59FFFFFF);
        timeLine.setPivotX(0f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(2), Gravity.BOTTOM);
        tl.leftMargin = tl.rightMargin = Ui.dp(H / 2f);
        pane.addView(timeLine, tl);
        addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // how many it took the place of, pinned in its top left corner
        count = Ui.text(c, 10.5f, Ui.bold, Pad.DARK);
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(Ui.dp(18));
        count.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0xF2FFFFFF);
        chip.setCornerRadius(Ui.dpf(9));
        count.setBackground(chip);
        count.setVisibility(GONE);
        addView(count, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(18), Gravity.TOP | Gravity.START));
        glide = new Spring.Mover(new Spring(0.36, 0), 1f / 256, 0f, this::at);
        setAlpha(0f);
        setVisibility(INVISIBLE);
    }

    /** Where it goes on home's root: the top right corner, over the status pill. */
    static FrameLayout.LayoutParams place() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Ui.dp(W), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        p.topMargin = Ui.dp(TOP);
        p.rightMargin = Ui.dp(RIGHT);
        return p;
    }

    /** A notification worth a banner (MediaWatch filtered it): shown now, or kept a little for room. */
    void arrived(MediaWatch.Note n) {
        if (n == null || n.key == null || n.alertOnce && seen.contains(n.key)) return;
        // the one on show, changed by its app: its words change where they are
        if (shown != null && n.key.equals(shown.note.key)) {
            shown.note = n;
            words(shown);
            return;
        }
        Item it = new Item(n, SystemClock.uptimeMillis());
        Object[] f = faces.get(n.pkg);
        if (f != null) {
            faced(it, f);
            return;
        }
        finding.add(it);
        if (!looking.add(n.pkg)) return;
        final Context app = getContext().getApplicationContext();
        final String pkg = n.pkg;
        new SafeThread(() -> {
            final Object[] face = face(app, pkg);
            handler.post(() -> found(pkg, face));
        }, "banner-face").start();
    }

    private void found(String pkg, Object[] face) {
        looking.remove(pkg);
        if (!isAttachedToWindow()) return;
        if (faces.size() >= FACES) faces.clear();
        faces.put(pkg, face);
        List<Item> ready = new ArrayList<>();
        for (Iterator<Item> i = finding.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (!pkg.equals(it.note.pkg)) continue;
            i.remove();
            ready.add(it);
        }
        for (Item it : ready) faced(it, face);
    }

    private void faced(Item it, Object[] face) {
        it.app = (String) face[0];
        it.icon = (Drawable) face[1];
        // an older copy of the same notification waiting is replaced, not counted
        for (Iterator<Item> i = waiting.iterator(); i.hasNext(); ) if (i.next().note.key.equals(it.note.key)) i.remove();
        waiting.add(it);
        while (waiting.size() > WAIT_MAX) waiting.remove(0);
        flush();
    }

    /** The newest waiting comes up if there's room now; otherwise they're looked at again in a second, while any wait. */
    private void flush() {
        handler.removeCallbacks(retry);
        long now = SystemClock.uptimeMillis();
        for (Iterator<Item> i = waiting.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (now - it.at > WAIT_MS || MediaWatch.muted(getContext(), it.note.pkg)) i.remove();
        }
        if (waiting.isEmpty()) return;
        if (!room()) {
            handler.postDelayed(retry, RETRY_MS);
            return;
        }
        Item newest = waiting.remove(waiting.size() - 1);
        int others = waiting.size();
        waiting.clear();
        present(newest, others);
    }

    private void present(Item it, int others) {
        boolean showing = shown != null;
        replaced = showing ? replaced + 1 + others : others;
        shown = it;
        seen.remove(it.note.key);
        seen.add(it.note.key);
        if (seen.size() > SEEN) seen.remove(0);
        words(it);
        count.setText("+" + replaced);
        count.setVisibility(replaced > 0 ? VISIBLE : GONE);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, SHOW_MS);
        runTimeLine();
        announceForAccessibility(it.app + ", " + it.note.title + (it.note.text.isEmpty() ? "" : ", " + it.note.text));
        if (showing) {
            // a newer one in its place: only the words change, on HIGH coming up softly
            if (!Ui.light) {
                content.setAlpha(0f);
                content.animate().alpha(1f).setDuration(Ui.CLOSE).setStartDelay(0).setInterpolator(Ui.EASE_DECEL).start();
            }
            return;
        }
        host.bannerShown(true);
        animate().cancel();
        setVisibility(VISIBLE);
        if (Ui.light) {
            glide.stop();
            setTranslationY(0f);
            animate().alpha(1f).setDuration(LOW_IN).setStartDelay(0).setInterpolator(Ui.EASE_DECEL).withEndAction(null).start();
            return;
        }
        // from wherever a fade out had got to
        glide.jump(getAlpha());
        glide.to(1f);
    }

    private void words(Item it) {
        kick.setText(it.app + "  ·  " + getContext().getString(R.string.now));
        title.setText(it.note.title);
        body.setText(it.note.text);
        body.setVisibility(it.note.text.isEmpty() ? GONE : VISIBLE);
        if (icon.getDrawable() != it.icon) icon.setImageDrawable(it.icon);
        icon.setVisibility(it.icon != null ? VISIBLE : GONE);
    }

    /** HIGH: the hairline shortens over the five seconds. LOW keeps still: nothing to draw while it rests. */
    private void runTimeLine() {
        timeLine.animate().cancel();
        if (Ui.light) {
            timeLine.setVisibility(GONE);
            return;
        }
        timeLine.setVisibility(VISIBLE);
        timeLine.setScaleX(1f);
        timeLine.animate().scaleX(0f).setDuration(SHOW_MS).setStartDelay(0).setInterpolator(LINEAR).start();
    }

    /** Out in 150 ms; the ones still waiting keep waiting. */
    void hide() {
        handler.removeCallbacks(hide);
        if (shown == null) return;
        shown = null;
        replaced = 0;
        host.bannerShown(false);
        glide.stop();
        timeLine.animate().cancel();
        animate().cancel();
        animate().alpha(0f).setDuration(Ui.CLOSE).setStartDelay(0).setInterpolator(Ui.EASE_STANDARD).withEndAction(() -> {
            if (shown == null) setVisibility(INVISIBLE);
        }).start();
    }

    /** Gone at once with nothing left waiting: Control Center (which lists them all) or rest has the screen. */
    void clear() {
        waiting.clear();
        finding.clear();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(hide);
        if (shown != null) {
            shown = null;
            replaced = 0;
            host.bannerShown(false);
        }
        glide.stop();
        timeLine.animate().cancel();
        animate().cancel();
        setAlpha(0f);
        setVisibility(INVISIBLE);
    }

    /** Room now: home in front with nothing over it, clear of the bottom fifth, and the focus somewhere else. */
    private boolean room() {
        if (!isAttachedToWindow() || !hasWindowFocus() || !host.bannerRoom()) return false;
        View parent = (View) getParent();
        if (getBottom() > parent.getHeight() * 0.8f) return false;
        View f = getRootView().findFocus();
        return f == null || !overlaps(f);
    }

    private boolean overlaps(View v) {
        int[] a = new int[2], b = new int[2];
        getLocationInWindow(a);
        v.getLocationInWindow(b);
        return a[0] < b[0] + v.getWidth() && b[0] < a[0] + getWidth() && a[1] < b[1] + v.getHeight() && b[1] < a[1] + getHeight();
    }

    /** HIGH's way in: faded in as it comes down from {@link #DROP} dp above its place. */
    private void at(float p) {
        setAlpha(Ui.clamp(p, 0f, 1f));
        setTranslationY(-Ui.dpf(DROP) * (1f - p));
    }

    /** Off the main thread: an app's name and icon; its package name and no icon when it can't be read. */
    private static Object[] face(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            return new Object[]{String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))), pm.getApplicationIcon(pkg)};
        } catch (Exception e) {
            return new Object[]{pkg, null};
        }
    }

    private static TextView line(Context c, float sp, Typeface tf, int color) {
        TextView t = Ui.text(c, sp, tf, color);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Its fades go straight onto its draws, as the glass does: no offscreen copy each frame. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        MediaWatch.onPosted = posted;
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusMoved);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (MediaWatch.onPosted == posted) MediaWatch.onPosted = null;
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusMoved);
        handler.removeCallbacksAndMessages(null);
        waiting.clear();
        finding.clear();
        looking.clear();
        glide.stop();
        super.onDetachedFromWindow();
    }

    /** Something else took the window (the screensaver, a dialog, an app): the banner goes; back, a waiting one comes. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) hide();
        else flush();
    }
}
