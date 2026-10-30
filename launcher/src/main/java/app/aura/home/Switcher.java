package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recent apps, as a deck: press Home twice and the app you were last in stands large in the middle,
 * the screen as you left it, under its icon and name; the ones before it stack behind to the left,
 * each a little smaller and darker, the newer ones tucked in on the right. Left and Right glide the
 * deck; OK opens; Up throws the app away (Android stops it); Close all sits on the right. Over the
 * top, how much memory is in use, which falls as apps are closed. Back or Home goes back.
 *
 * Only the frost fades: cards slide and scale, never fade, since a fading card is painted offscreen
 * first, and on this GPU that's the difference between smooth and stutter. The focused card always
 * stands in the same place, so its shadow is drawn once and never moves.
 */
final class Switcher extends FrameLayout {
    interface Host {
        AppRepo.App find(String pkg);

        /** A card for {@code a} in the home screen's tile style. */
        Card card(AppRepo.App a);

        void launch(AppRepo.App a, View from);

        /** Forget it in recents and ask Android to free it. */
        void close(AppRepo.App a);

        /** Everything was closed: {@code count} apps. */
        void cleared(int count);

        /** The screen as {@code pkg} was just left, if it's in memory; else null. */
        Bitmap shot(String pkg);
    }

    /** The focused card, 16:9, nearly the whole screen as on tvOS; screen pictures are loaded at this size. */
    static final int CARD_W = 700, CARD_H = 394;
    /** Above each card: its icon chip and name. */
    private static final int HEAD = 40, HEAD_GAP = 12;
    /** How far each card behind sits from the one in front: older ones as slices on the left. */
    private static final int STEP_OLDER = 64, STEP_NEWER = 36;
    private static final float SHRINK = 0.07f;
    /** The deck stands a little right of centre: the slices of older apps take the left. */
    private static final int DECK_SHIFT = 66;
    private static final int RISE = 560;

    private final Activity act;
    private final Host host;
    private final View veil;
    private final Deck deck;
    private final View shadow;
    private final LinearLayout closeAll;
    private final FrameLayout closeDisc;
    private final ImageView closeIcon;
    private final TextView empty, hint, closeLabel, memText;
    private final View memFill;
    private final List<AppRepo.App> apps = new ArrayList<>();
    private final List<LinearLayout> items = new ArrayList<>();
    private int at;
    /** On Close all rather than the cards. */
    private boolean onButton;
    private boolean busy;

    /** Draws the cards farthest from focus first, so the focused one is always on top. */
    private final class Deck extends FrameLayout {
        private int[] order = new int[0];

        Deck(Context c) {
            super(c);
            setChildrenDrawingOrderEnabled(true);
            setClipChildren(false);
            setClipToPadding(false);
        }

        void reorder(int focus) {
            int n = getChildCount();
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            // farthest first; at the same distance the newer (right) ones go under the older
            Arrays.sort(idx, (x, y) -> {
                int dx = Math.abs(x - focus), dy = Math.abs(y - focus);
                if (dx != dy) return dy - dx;
                return x - y;
            });
            order = new int[n];
            for (int i = 0; i < n; i++) order[i] = idx[i];
            invalidate();
        }

        @Override
        protected int getChildDrawingOrder(int count, int i) {
            return order.length == count ? order[i] : i;
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    Switcher(Activity a, Backdrop b, Host h) {
        super(a);
        act = a;
        host = h;
        setVisibility(GONE);
        setClipChildren(false);
        // a deep night-blue room, lit a little from above, as tvOS has it: a gradient costs this GPU
        // next to nothing, where the frosted photograph cost ~18 ms a frame and had to be uploaded
        // again each time Aura came back from an app
        veil = new View(a);
        GradientDrawable room = new GradientDrawable();
        room.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        room.setGradientCenter(0.42f, 0.3f);
        room.setGradientRadius(Ui.dp(900));
        room.setColors(new int[]{0xFF232B3B, 0xFF10141C, 0xFF05070A});
        veil.setBackground(room);
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the focused card's shadow, always in the same place and painted once: only the band
        // that shows below its edge (a whole card's worth hid under the card, costing the GPU a
        // third of a screen every frame)
        shadow = new ImageView(a);
        ((ImageView) shadow).setImageBitmap(shadowBitmap());
        LayoutParams sl = new LayoutParams(Ui.dp(CARD_W + 80), Ui.dp(SHADOW_H), Gravity.CENTER);
        sl.leftMargin = Ui.dp(DECK_SHIFT);
        // its top sits SHADOW_UNDER above the card's bottom edge (a centred view moves by half its margin)
        sl.topMargin = 2 * (Ui.dp((HEAD + HEAD_GAP) / 2 + CARD_H / 2) - Ui.dp(SHADOW_UNDER) + Ui.dp(SHADOW_H) / 2);
        addView(shadow, sl);

        deck = new Deck(a);
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dl.leftMargin = Ui.dp(DECK_SHIFT);
        addView(deck, dl);

        // in the gutter on the right: Close all, and under it how much memory is in use
        closeAll = new LinearLayout(a);
        closeAll.setOrientation(LinearLayout.VERTICAL);
        closeAll.setGravity(Gravity.CENTER_HORIZONTAL);
        closeDisc = new FrameLayout(a);
        closeIcon = new ImageView(a);
        closeIcon.setImageResource(R.drawable.ic_trash);
        closeDisc.addView(closeIcon, new FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
        closeAll.addView(closeDisc, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)));
        closeLabel = Ui.text(a, 13, Ui.semibold, 0xE6FFFFFF);
        closeLabel.setText("Close all");
        LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cll.topMargin = Ui.dp(10);
        closeAll.addView(closeLabel, cll);
        memText = Ui.text(a, 11, Ui.medium, 0x99FFFFFF);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(26);
        closeAll.addView(memText, ml);
        FrameLayout track = new FrameLayout(a);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(0x2EFFFFFF);
        tg.setCornerRadius(Ui.dpf(2));
        track.setBackground(tg);
        memFill = new View(a);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0xCCFFFFFF);
        fg.setCornerRadius(Ui.dpf(2));
        memFill.setBackground(fg);
        memFill.setPivotX(0f);
        track.addView(memFill, new FrameLayout.LayoutParams(Ui.dp(60), Ui.dp(4)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(60), Ui.dp(4));
        tl.topMargin = Ui.dp(7);
        closeAll.addView(track, tl);
        LayoutParams cal = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        cal.rightMargin = Ui.dp(14);
        cal.topMargin = Ui.dp(HEAD + HEAD_GAP) + Ui.dp(40);
        addView(closeAll, cal);

        hint = Ui.text(a, 11.5f, Ui.regular, 0x73FFFFFF);
        hint.setText("OK opens  ·  ▲ on an app closes it  ·  ► reaches Close all");
        LayoutParams hl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = Ui.dp(10);
        addView(hint, hl);

        empty = Ui.text(a, 17, Ui.medium, 0xCCFFFFFF);
        empty.setText("Apps you open will line up here");
        addView(empty, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // the app between card and full screen: its colour, its icon and name in the middle
        zoom = new FrameLayout(a);
        zoomBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF222833, 0xFF0B0D12});
        zoom.setBackground(zoomBg);
        zoom.setPivotX(0f);
        zoom.setPivotY(0f);
        zoom.setVisibility(GONE);
        zoomMid = new LinearLayout(a);
        zoomMid.setOrientation(LinearLayout.VERTICAL);
        zoomMid.setGravity(Gravity.CENTER_HORIZONTAL);
        zoomIcon = new ImageView(a);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(Ui.dpf(22));
        zoomIcon.setBackground(clip);
        zoomIcon.setClipToOutline(true);
        zoomMid.addView(zoomIcon, new LinearLayout.LayoutParams(Ui.dp(96), Ui.dp(96)));
        zoomName = Ui.text(a, 19, Ui.semibold, 0xF2FFFFFF);
        LinearLayout.LayoutParams zl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zl.topMargin = Ui.dp(18);
        zoomMid.addView(zoomName, zl);
        zoomShot = new ImageView(a);
        zoomShot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        zoom.addView(zoomShot, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        zoom.addView(zoomMid, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(zoom, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** A soft shadow the size of the focused card, blurred once in software (an alpha mask, ~0.3 MB). */
    private static final int SHADOW_H = 90, SHADOW_UNDER = 50;

    /** The bottom of a soft shadow, card-wide, blurred once in software (an alpha mask, ~0.1 MB). */
    private static Bitmap shadowBitmap() {
        int w = Ui.dp(CARD_W + 80), h = Ui.dp(SHADOW_H);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x80000000);
        p.setMaskFilter(new BlurMaskFilter(Ui.dpf(20), BlurMaskFilter.Blur.NORMAL));
        float m = Ui.dpf(48);
        c.drawRoundRect(new RectF(m, -Ui.dpf(80), w - m, Ui.dpf(SHADOW_UNDER + 6)), Ui.dpf(22), Ui.dpf(22), p);
        return bm;
    }

    // ---- zoom: an app grows out of its card, or folds back into it -----------------------------

    private final FrameLayout zoom;
    private final LinearLayout zoomMid;
    private final ImageView zoomIcon, zoomShot;
    private final TextView zoomName;
    private final GradientDrawable zoomBg;
    private final RectF zoomFrom = new RectF();
    private ValueAnimator zoomAnim, breathe;
    private boolean launching;

    /**
     * The zoom dressed as {@code a}: a deep wash of its colour, its icon, its name; with {@code shot},
     * the screen as you left it on top, which the launch screen later dissolves from.
     */
    private void dress(AppRepo.App a, Bitmap shot) {
        zoomBg.setColors(new int[]{Ui.mix(a.color, 0xFF000000, 0.35f), Ui.mix(a.color, 0xFF000000, 0.78f)});
        zoomIcon.setImageDrawable(a.icon);
        zoomName.setText(a.label);
        zoomIcon.setScaleX(1f);
        zoomIcon.setScaleY(1f);
        zoomIcon.setAlpha(1f);
        zoomShot.animate().cancel();
        zoomShot.setImageBitmap(shot);
        zoomShot.setAlpha(1f);
        zoomShot.setVisibility(shot != null ? VISIBLE : GONE);
        zoomMid.setVisibility(shot != null ? INVISIBLE : VISIBLE);
    }

    /**
     * The zoom between {@link #zoomFrom} (f = 0) and the whole screen (f = 1). It is scaled, never
     * laid out again, and its corners are kept at a card's radius as it grows, easing to square.
     */
    private void frame(float f) {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        float l = zoomFrom.left * (1 - f), t = zoomFrom.top * (1 - f);
        float zw = zoomFrom.width() + (w - zoomFrom.width()) * f, zh = zoomFrom.height() + (h - zoomFrom.height()) * f;
        float sx = zw / w, sy = zh / h;
        zoom.setTranslationX(l);
        zoom.setTranslationY(t);
        zoom.setScaleX(sx);
        zoom.setScaleY(sy);
        zoomBg.setCornerRadius(Ui.dpf(22) * (1 - f) / Math.max(0.05f, Math.min(sx, sy)));
        // the name comes in only as it nears full size; on a card it would be a smudge
        zoomName.setAlpha(Math.max(0f, f * 1.6f - 0.6f));
        // the icon grows evenly however the pane stretches, and never smaller than a third
        float k = Math.max(0.3f, Math.min(sx, sy));
        zoomMid.setScaleX(k / sx);
        zoomMid.setScaleY(k / sy);
    }

    /** Where card {@code i} sits on screen once its rise has finished. */
    private RectF rectOf(int i) {
        Card c = card(i);
        LinearLayout item = items.get(i);
        int[] a = new int[2], b = new int[2];
        c.getLocationInWindow(a);
        getLocationInWindow(b);
        float s = item.getScaleX();
        RectF r = new RectF(a[0] - b[0], a[1] - b[1], a[0] - b[0] + c.getWidth() * s, a[1] - b[1] + c.getHeight() * s);
        r.offset(0, -item.getTranslationY());
        return r;
    }

    private void runZoom(float from, float to, long ms, Runnable end) {
        if (zoomAnim != null) zoomAnim.cancel();
        zoomAnim = ValueAnimator.ofFloat(from, to);
        zoomAnim.setDuration(ms);
        zoomAnim.setInterpolator(Ui.EMPHASIZED);
        zoomAnim.addUpdateListener(v -> frame((float) v.getAnimatedValue()));
        zoomAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator x) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                if (!cancelled && end != null) end.run();
            }
        });
        frame(from);
        zoomAnim.start();
    }

    /** Opened over an app: that app, full screen, folds down into its card as the others rise. */
    private void foldInto(int i) {
        LinearLayout item = items.get(i);
        Card c = card(i);
        zoomFrom.set(rectOf(i));
        item.animate().cancel();
        item.setTranslationY(0f);
        c.setVisibility(INVISIBLE);
        dress(apps.get(i), host.shot(apps.get(i).pkg));
        zoom.setVisibility(VISIBLE);
        runZoom(1f, 0f, 520, () -> {
            c.setVisibility(VISIBLE);
            zoom.setVisibility(GONE);
        });
    }

    boolean isLaunching() {
        return launching;
    }

    /**
     * Opening {@code a}: its card grows to fill the screen and becomes a launch screen, breathing
     * gently, while the other cards fall away. {@code start} opens the app at once, so however long
     * it takes to appear is spent behind the launch screen rather than on a frozen switcher.
     */
    void launchInto(AppRepo.App a, Runnable start) {
        busy = true;
        launching = true;
        int i = -1;
        for (int k = 0; k < apps.size(); k++) if (apps.get(k).pkg.equals(a.pkg)) i = k;
        Bitmap from = host.shot(a.pkg);
        if (from == null && i >= 0) from = card(i).art();
        if (i >= 0) {
            zoomFrom.set(rectOf(i));
            card(i).setVisibility(INVISIBLE);
        } else {
            float w = getWidth(), h = getHeight();
            zoomFrom.set(w * 0.4f, h * 0.4f, w * 0.6f, h * 0.6f);
        }
        // the card already covers most of the screen: the rest of the deck simply goes, so the
        // zoom is all the GPU draws while the app starts beside it
        deck.setVisibility(INVISIBLE);
        away(200);
        shadow.setVisibility(INVISIBLE);
        dress(a, from);
        zoom.setVisibility(VISIBLE);
        runZoom(0f, 1f, 420, () -> {
            // fully covered, and from here on perfectly still: the app's picture (or its icon and
            // name) waits without a single further frame, leaving the GPU to the app that's loading
            veil.setVisibility(INVISIBLE);
        });
        // the first frame of the zoom goes out before the app's own start takes the CPU
        postDelayed(start, 48);
    }

    /** The app is in front now (or never came): everything put back, out of sight, ready for next time. */
    void reset() {
        if (zoomAnim != null) zoomAnim.cancel();
        if (breathe != null) breathe.cancel();
        zoom.setVisibility(GONE);
        zoomShot.setImageBitmap(null);
        zoomMid.setAlpha(1f);
        veil.setVisibility(VISIBLE);
        deck.setVisibility(VISIBLE);
        shadow.setVisibility(VISIBLE);
        for (LinearLayout it : made.values()) {
            it.animate().cancel();
            it.getChildAt(1).setVisibility(VISIBLE);
        }
        launching = false;
        busy = false;
        closing = false;
        setVisibility(GONE);
        deck.removeAllViews();
        items.clear();
        apps.clear();
    }

    /** Fading applies to each child's draws, never to an offscreen copy of the whole screen. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE && !closing;
    }

    /** Still on screen, dropping away: keys wait. */
    boolean isClosing() {
        return closing;
    }

    private boolean closing;

    /**
     * Opens with {@code recent}, most recent first. Over an app ({@code current}), that app is
     * focused, as on tvOS: it folds into its card and OK goes straight back. The frost is there at
     * once: the cards do the moving.
     */
    void open(Map<String, Long> recent, String current) {
        deck.removeAllViews();
        apps.clear();
        items.clear();
        long now = System.currentTimeMillis();
        int start = 0, cur = -1;
        // the app playing right now says so on its card
        MediaWatch.Playing np = MediaWatch.now(act);
        String playing = np != null && np.playing ? np.pkg : null;
        for (Map.Entry<String, Long> e : recent.entrySet()) {
            AppRepo.App a = host.find(e.getKey());
            if (a == null) continue;
            if (a.pkg.equals(current)) start = cur = apps.size();
            apps.add(a);
            boolean live = a.pkg.equals(playing);
            LinearLayout item = item(a, live ? "●  Playing now" : apps.size() == 1 && a.pkg.equals(current) ? "Just now" : Habits.ago(now - e.getValue()));
            ((TextView) item.getTag()).setTextColor(live ? 0xFF7BE0A8 : 0x8CFFFFFF);
            if (item.getParent() != null) ((ViewGroup) item.getParent()).removeView(item);
            items.add(item);
            deck.addView(item, new FrameLayout.LayoutParams(Ui.dp(CARD_W), Ui.dp(HEAD + HEAD_GAP + CARD_H), Gravity.CENTER));
        }
        boolean none = apps.isEmpty();
        empty.setVisibility(none ? VISIBLE : GONE);
        closeAll.setVisibility(none ? GONE : VISIBLE);
        hint.setVisibility(none ? GONE : VISIBLE);
        shadow.setVisibility(none ? GONE : VISIBLE);
        busy = false;
        launching = false;
        zoom.setVisibility(GONE);
        veil.setVisibility(VISIBLE);
        deck.setVisibility(VISIBLE);
        onButton = false;
        paintButton();
        memory(false);
        setVisibility(VISIBLE);
        veil.animate().cancel();
        veil.setAlpha(1f);
        closing = false;
        if (none) return;
        at = Math.min(start, apps.size() - 1);
        layoutDeck(false);
        for (int k = 0; k < items.size(); k++) {
            LinearLayout v = items.get(k);
            // they rise from below the screen, nearest first: a slide, which this GPU draws for free
            v.setTranslationY(Ui.dp(RISE));
            v.animate().translationY(0f).setStartDelay(35L * Math.abs(k - at)).setDuration(560).setInterpolator(Ui.EMPHASIZED).start();
        }
        shadow.setTranslationY(Ui.dp(RISE));
        shadow.animate().translationY(0f).setStartDelay(0).setDuration(560).setInterpolator(Ui.EMPHASIZED).start();
        closeAll.setTranslationX(Ui.dp(140));
        closeAll.animate().translationX(0f).setStartDelay(120).setDuration(560).setInterpolator(Ui.EMPHASIZED).start();
        // the app you were in (when it's in the list) folds from full screen into its card
        final int from = cur;
        if (from >= 0 && from < items.size()) {
            // the app you were in fills the screen from the very first frame, then folds away
            dress(apps.get(from), host.shot(apps.get(from).pkg));
            zoom.setTranslationX(0f);
            zoom.setTranslationY(0f);
            zoom.setScaleX(1f);
            zoom.setScaleY(1f);
            zoomMid.setScaleX(1f);
            zoomMid.setScaleY(1f);
            zoomName.setAlpha(1f);
            zoomBg.setCornerRadius(0f);
            zoom.setVisibility(VISIBLE);
        }
        deck.post(() -> {
            card(at).requestFocus();
            if (from >= 0 && from < items.size()) foldInto(from);
        });
    }

    /**
     * Every card in its place for the focus: the focused one large in the middle, older ones stacked
     * behind to the left, newer ones tucked in on the right; the far ones hidden, so they cost nothing.
     */
    private void layoutDeck(boolean animate) {
        for (int k = 0; k < items.size(); k++) {
            LinearLayout v = items.get(k);
            int d = k - at;
            float s = 1f - SHRINK * Math.abs(d);
            float x = d == 0 ? 0f : d > 0 ? -Ui.dp(STEP_OLDER) * d : Ui.dp(STEP_NEWER) * -d;
            // big cards: only what peeks out is drawn
            boolean show = d >= -1 && d <= 2;
            v.setPivotX(Ui.dp(CARD_W) / 2f);
            v.setPivotY(Ui.dp(HEAD + HEAD_GAP) + Ui.dp(CARD_H) / 2f);
            if (show) v.setVisibility(VISIBLE);
            if (animate) {
                v.animate().translationX(x).scaleX(s).scaleY(s).setStartDelay(0).setDuration(440).setInterpolator(Ui.EMPHASIZED)
                        .withEndAction(show ? null : () -> v.setVisibility(INVISIBLE)).start();
            } else {
                v.setTranslationX(x);
                v.setScaleX(s);
                v.setScaleY(s);
                v.setVisibility(show ? VISIBLE : INVISIBLE);
            }
            dim(v, d, animate);
        }
        deck.reorder(at);
    }

    /** How much memory is in use, now; with {@code animate} the bar glides to it. */
    private void memory(boolean animate) {
        ActivityManager am = Compat.svc(act, ActivityManager.class);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) return;
        float gb = 1024f * 1024f * 1024f, used = (mi.totalMem - mi.availMem) / gb, total = mi.totalMem / gb;
        memText.setText(String.format(Locale.getDefault(), "%.1f of %.1f GB", used, total));
        float f = Math.max(0.03f, Math.min(1f, used / total));
        if (animate) memFill.animate().scaleX(f).setStartDelay(0).setDuration(700).setInterpolator(Ui.EMPHASIZED).start();
        else memFill.setScaleX(f);
    }

    /** Close all (and the memory under it) slides away with the cards. */
    private void away(long ms) {
        hint.setVisibility(GONE);
        closeAll.animate().translationX(Ui.dp(140)).setStartDelay(0).setDuration(ms).setInterpolator(Ui.SOFT).start();
    }

    /**
     * The cards drop away over the frost, which stays opaque; only then does {@code after} bring the
     * home screen back and the frost go, in one step. Drawing both at once costs this GPU ~20 ms a frame.
     */
    void close(Runnable after) {
        if (!isOpen()) {
            if (after != null) after.run();
            return;
        }
        closing = true;
        for (View v : items) {
            // cards already flown off the top (Close all) stay gone
            if (v.getTranslationY() > -Ui.dp(100)) v.animate().translationY(Ui.dp(RISE)).setStartDelay(0).setDuration(200).setInterpolator(Ui.SOFT).start();
        }
        shadow.animate().translationY(Ui.dp(RISE)).setStartDelay(0).setDuration(200).setInterpolator(Ui.SOFT).start();
        away(200);
        postDelayed(() -> {
            if (after != null) after.run();
            setVisibility(GONE);
            closing = false;
            deck.removeAllViews();
            items.clear();
            apps.clear();
        }, 210);
    }

    /** Every key while it's open; false only for the ones HomeActivity handles (Back, Home). */
    boolean key(int code) {
        if (busy || apps.isEmpty()) return true;
        if (onButton) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                onButton = false;
                paintButton();
                card(at).requestFocus();
            } else if (Pad.isConfirm(code)) {
                clearAll();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            // left goes back in time, through the stack
            if (at < apps.size() - 1) move(at + 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (at > 0) move(at - 1);
            else {
                onButton = true;
                paintButton();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            closeAt(at, true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            onButton = true;
            paintButton();
            return true;
        }
        if (Pad.isConfirm(code)) {
            host.launch(apps.get(at), card(at));
            return true;
        }
        return true;
    }

    private void move(int to) {
        at = to;
        layoutDeck(true);
        card(at).requestFocus();
    }

    /** The card flies up and away, the deck closes the gap, and the memory it held shows as freed. */
    private void closeAt(int i, boolean swipe) {
        if (busy) return;
        busy = true;
        AppRepo.App a = apps.get(i);
        LinearLayout v = items.get(i);
        host.close(a);
        v.animate().translationY(-Ui.dp(swipe ? 380 : 320)).setStartDelay(0).setDuration(320).setInterpolator(Ui.SOFT).withEndAction(() -> {
            deck.removeView(v);
            items.remove(i);
            apps.remove(i);
            busy = false;
            if (apps.isEmpty()) {
                host.cleared(1);
                return;
            }
            at = Math.min(i, apps.size() - 1);
            layoutDeck(true);
            if (!onButton) card(at).requestFocus();
        }).start();
        // Android takes a moment to hand the memory back
        postDelayed(() -> {
            if (isOpen()) memory(true);
        }, 900);
    }

    private void clearAll() {
        if (busy) return;
        busy = true;
        int n = apps.size();
        for (int k = 0; k < items.size(); k++) {
            host.close(apps.get(k));
            items.get(k).animate().translationY(-Ui.dp(420)).setStartDelay(40L * Math.abs(k - at)).setDuration(340).setInterpolator(Ui.SOFT).start();
        }
        shadow.animate().translationY(-Ui.dp(420)).setStartDelay(0).setDuration(340).setInterpolator(Ui.SOFT).start();
        postDelayed(() -> host.cleared(n), 380 + 40L * items.size());
    }

    private Card card(int i) {
        return (Card) items.get(i).getChildAt(1);
    }

    /**
     * Behind the focus, cards darken with depth (by their own paint); the focused one shows its name,
     * the older ones only their icon, the newer ones nothing above them, tucked in as they are.
     */
    private void dim(LinearLayout item, int d, boolean animate) {
        LinearLayout head = (LinearLayout) item.getChildAt(0);
        Card c = (Card) item.getChildAt(1);
        c.setDim(d == 0 ? 0f : Math.min(0.72f, 0.38f + 0.12f * (Math.abs(d) - 1)));
        float chip = d == 0 ? 1f : d > 0 ? 0.7f : 0f, words = d == 0 ? 1f : 0f;
        View icon = head.getChildAt(0), names = head.getChildAt(1);
        if (animate) {
            icon.animate().alpha(chip).setDuration(300).setStartDelay(0).start();
            names.animate().alpha(words).setDuration(d == 0 ? 360 : 160).setStartDelay(d == 0 ? 120 : 0).start();
        } else {
            icon.setAlpha(chip);
            names.setAlpha(words);
        }
    }

    /**
     * Cards kept between openings, painted ahead of time: building and painting eight at the moment
     * Home is pressed twice made the first frames stutter. Each costs ~2 MB at this size; at most six.
     */
    private final Map<String, LinearLayout> made = new java.util.HashMap<>();
    private final java.util.ArrayDeque<AppRepo.App> toPaint = new java.util.ArrayDeque<>();
    private final Runnable paintNext = new Runnable() {
        @Override
        public void run() {
            AppRepo.App a = toPaint.poll();
            if (a == null || isOpen()) return;
            Card c = (Card) item(a, "").getChildAt(1);
            if (c.getWidth() == 0) {
                // a layout of its own, detached: this is what paints its artwork
                int w = Ui.dp(CARD_W), h = Ui.dp(CARD_H);
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                c.layout(0, 0, w, h);
            }
            // one card a frame, so the home screen never feels it
            if (!toPaint.isEmpty()) postDelayed(this, 32);
        }
    };

    /** When each card's picture was taken (a screenshot, or what you were watching): what it was made from. */
    private final Map<String, Long> stamps = new java.util.HashMap<>(), madeStamp = new java.util.HashMap<>();

    /** A new face for {@code pkg} (its picture changed): its card is made again next time. */
    void face(String pkg, long stamp) {
        stamps.put(pkg, stamp);
    }

    /** While the home screen idles: the cards for {@code recent} made and painted, ready for Home twice. */
    void prepare(Map<String, Long> recent, Map<String, Long> faces) {
        if (isOpen()) return;
        stamps.clear();
        stamps.putAll(faces);
        if (made.size() > 6) made.clear();
        toPaint.clear();
        for (String pkg : recent.keySet()) {
            AppRepo.App a = host.find(pkg);
            if (a != null && (!made.containsKey(a.pkg) || stale(a.pkg))) toPaint.add(a);
        }
        removeCallbacks(paintNext);
        if (!toPaint.isEmpty()) postDelayed(paintNext, 32);
    }

    /** The tile look changed: cards are made again in the new one. */
    void forget() {
        made.clear();
        toPaint.clear();
    }

    private boolean stale(String pkg) {
        Long want = stamps.get(pkg), have = madeStamp.get(pkg);
        return want == null ? have != null : !want.equals(have);
    }

    /** One app: its icon in a dark chip and its name above (when you last used it beside it), the card below. */
    private LinearLayout item(AppRepo.App a, String when) {
        LinearLayout kept = made.get(a.pkg);
        if (kept != null && stale(a.pkg)) {
            made.remove(a.pkg);
            kept = null;
        }
        if (kept != null) {
            ((TextView) kept.getTag()).setText(when);
            return kept;
        }
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout chip = new FrameLayout(act);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xE6121419);
        cg.setStroke(Ui.dp(1), 0x1FFFFFFF);
        cg.setCornerRadius(Ui.dpf(11));
        chip.setBackground(cg);
        ImageView icon = new ImageView(act);
        icon.setImageDrawable(a.icon);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(Ui.dpf(6));
        icon.setClipToOutline(true);
        icon.setBackground(clip);
        chip.addView(icon, new FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
        head.addView(chip, new LinearLayout.LayoutParams(Ui.dp(HEAD), Ui.dp(HEAD)));
        LinearLayout names = new LinearLayout(act);
        names.setGravity(Gravity.BOTTOM);
        TextView name = Ui.text(act, 19, Ui.semibold, 0xFFFFFFFF);
        name.setText(a.label);
        names.addView(name);
        TextView ago = Ui.text(act, 12.5f, Ui.regular, 0x8CFFFFFF);
        ago.setText(when);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = Ui.dp(10);
        al.bottomMargin = Ui.dp(1);
        names.addView(ago, al);
        col.setTag(ago);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.leftMargin = Ui.dp(14);
        head.addView(names, nl);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(HEAD));
        hl.bottomMargin = Ui.dp(HEAD_GAP);
        col.addView(head, hl);

        Card card = host.card(a);
        card.lift = false;
        card.bezel = false;
        card.res = 0.6f;
        card.onClick = () -> host.launch(a, card);
        col.addView(card, new LinearLayout.LayoutParams(Ui.dp(CARD_W), Ui.dp(CARD_H)));

        made.put(a.pkg, col);
        madeStamp.put(a.pkg, stamps.get(a.pkg));
        return col;
    }

    /** Close all: a frosted disc; focused, it turns white and the bin dark, and grows a touch. */
    private void paintButton() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(onButton ? 0xFFFFFFFF : 0x29FFFFFF);
        if (!onButton) g.setStroke(Ui.dp(1), 0x33FFFFFF);
        closeDisc.setBackground(g);
        closeIcon.setColorFilter(onButton ? 0xFF111111 : 0xFFFFFFFF);
        closeLabel.setTextColor(onButton ? 0xFFFFFFFF : 0xB3FFFFFF);
        closeDisc.animate().scaleX(onButton ? 1.1f : 1f).scaleY(onButton ? 1.1f : 1f).setDuration(260).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        if (onButton) {
            closeDisc.setFocusable(true);
            closeDisc.requestFocus();
            // the card the button acts on stays as it was, only its focus moves
        }
    }
}
