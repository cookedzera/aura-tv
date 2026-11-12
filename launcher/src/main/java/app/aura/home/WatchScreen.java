package app.aura.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Watch, for the evening nobody can decide: the whole screen given to one title at a time, and lit
 * in that title's own colour (taken from its poster). Its wide picture fills the screen behind its
 * name, its score and a few lines about it; along the top, the genres (your own list first, with
 * how many it holds); along the foot, that genre's films and series as a row of posters. The
 * poster in focus stays in one place and the row glides under it, the one just left still in view
 * at the edge; the picture behind sweeps in from the side the focus is heading to, and the words
 * arrive from there one after another.
 *
 * Left alone for three seconds the screen rests: the row sinks, the genres leave and the picture
 * has the screen. OK opens the title: its poster grows into a page with its trailer, the app here
 * that streams it (or the TV's own search) and your list; saving sends a small poster up into My
 * list. Holding OK decides for you: the row spins and lands on one title.
 *
 * A genre's shelf comes from {@link Watch}: read from the TV when it's there, fetched the first
 * time it's opened and once a week after. Everything that moves is a small picture, or one opaque
 * picture swept by a clip: on LOW nothing the size of the screen is ever blended, and the picture
 * changes with a quick crisp sweep; HIGH gets the soft edge, the dissolve at rest and the drift.
 */
final class WatchScreen extends FrameLayout {
    interface Host {
        /** A trailer, a search or an app, opened as home opens anything; home says so when nothing here opens it. */
        void open(Intent intent, View from);
    }

    static final int W = 98, H = 147, GAP = 14, STEP = W + GAP;
    /** dp: where the words and the poster in focus begin, the room round that poster to grow, and the row's top. */
    static final int LEFT = 104, ROOM = 20, ROW_TOP = 372;
    /** dp: the poster of an open title. */
    private static final int BIG_W = 186, BIG_H = 279, BIG_TOP = 126;
    private static final float GROW = 1.15f, LIFT = 3f, PIVOT_Y = 0.7f;
    /** How faint a poster waiting its turn is, and one already passed. */
    private static final float AHEAD = 0.62f, PASSED = 0.28f;
    /** How long the title in focus rests before its wide picture is read: sweeping along the row reads none on the way. */
    private static final long HERO_MS = 220;
    /** Left alone this long, the screen rests. */
    static final long REST_MS = 3000;
    /** How many of a shelf's posters are dealt in one after another: the ones on screen. */
    private static final int DEALT = 9;
    private static final int SOFT = 0xBDFFFFFF, FAINT = 0x80FFFFFF, INK = 0xFF15171B;
    /** The genres after "My list" and "Anything", in this order: the ones people reach for. */
    private static final String[] GENRES = {"action", "comedy", "drama", "thriller", "scifi", "family", "horror", "romance"};
    /** What the screen is doing: a row to move along, at rest with the picture, or one title open. */
    private static final int BROWSE = 0, REST = 1, PAGE = 2;

    private final Activity act;
    private final Host host;
    private final Hero hero;
    private final Glow glow;
    private final TabStrip strip;
    private final Words words;
    private final LinearLayout more, head;
    private final TextView headName, headCount, note;
    private final Hair hair;
    private final Lane window;
    private final Page page;
    private final Mini mini;
    private final List<String> ids = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final ExecutorService reader = Compat.background("watch", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    /** The wide picture has a thread of its own: behind thirty posters still being read, it came seconds late. */
    private final ExecutorService heroReader = Compat.background("watch-hero", android.os.Process.THREAD_PRIORITY_DEFAULT + 2);
    /** The other genres' shelves, fetched one after another once the first is on show: choosing one then waits for nothing. */
    private final ExecutorService ahead = Compat.background("watch-ahead", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    /** The chosen genre's shelf fetched from the network: a fetch that waits holds up no poster read from the TV. */
    private final ExecutorService fetcher = Compat.background("watch-fetch", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    /** Asks where the title in focus streams: a call that may wait on the network holds up no picture. */
    private final ExecutorService asker = Compat.background("watch-where", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private boolean fetchedAhead;
    /** Each genre's posters laid out once, kept while home runs. */
    private final Map<String, Shelf> shelves = new HashMap<>();
    private final Spring.Glide glide;
    /** The give at either end of the row: pushed, it springs back. */
    private final Spring.Mover give;
    private final Random random = new Random();
    private Shelf installed;
    private int current = -1;
    /** Counts the genres asked for, and the titles whose wide picture was: what arrives late isn't shown. */
    private int asked, heroAsked;
    private boolean shown;
    private Poster inFocus;
    /** The title whose wide picture is on show, and the side the focus came to the one in focus from. */
    private Picks.Pick heroShown;
    private int heroDir = 1;
    /** The colour the screen is lit in now, on its way to the colour of the title in focus. */
    private int aura = Watch.PALE;
    private ValueAnimator auraMove, spin;
    private int pose = BROWSE;
    private boolean spinning;
    /** OK went down on a poster or a choice and hasn't yet been let go, or held long enough to decide. */
    private boolean armed;
    /** Opened before its first shelf was read: the focus goes to the row once it is. */
    private boolean wantRow;
    private int mineCount;
    private final Runnable readHero = this::readHero;
    private final Runnable rest = () -> {
        if (shown && pose == BROWSE && !spinning && focused() != null) pose(REST);
    };
    private final Runnable held = () -> {
        if (!armed) return;
        armed = false;
        decide();
    };

    private static final class Shelf {
        final LinearLayout view;
        final Poster[] posters;

        Shelf(LinearLayout view, Poster[] posters) {
            this.view = view;
            this.posters = posters;
        }
    }

    WatchScreen(Activity a, Host h) {
        super(a);
        act = a;
        host = h;
        setVisibility(GONE);
        hero = new Hero(a);
        addView(hero, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the light the poster in focus casts, under where it stands
        glow = new Glow(a);
        addView(glow, at(300, 210, LEFT + W / 2 - 150, ROW_TOP + 24));

        ids.add(Watch.MINE);
        titles.add(a.getString(R.string.watch_my_list));
        ids.add(Watch.ANY);
        titles.add(a.getString(R.string.watch_anything));
        for (String id : GENRES) {
            for (Picks.Genre g : Picks.GENRES) {
                if (!g.id.equals(id)) continue;
                ids.add(id);
                titles.add(a.getString(g.name));
            }
        }
        strip = new TabStrip(a, 12.5f, 12, 6, new TabStrip.Listener() {
            @Override
            public void pick(int k) {
                show(k);
            }

            @Override
            public void hold(int k, View tab) {
                // a genre has no menu
            }
        });
        strip.set(titles);
        LayoutParams sl = at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, LEFT, Ui.TEXT_SAFE_Y + 2);
        addView(strip, sl);
        for (int i = 0; i < strip.size(); i++) {
            strip.tab(i).setOnFocusChangeListener(chain(strip.tab(i).getOnFocusChangeListener()));
        }

        words = new Words(46, 3);
        // what OK and a held OK do, said only once the screen rests
        more = new LinearLayout(a);
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.addView(cap("OK"));
        more.addView(hint(a.getString(R.string.watch_more)));
        more.addView(cap(a.getString(R.string.watch_hold_ok)), gap(14));
        more.addView(hint(a.getString(R.string.watch_decide)));
        more.setAlpha(0f);
        words.addView(more, below(14));
        addView(words, at(460, ViewGroup.LayoutParams.WRAP_CONTENT, LEFT, 100));

        // over the row: the genre on show, how far along it the focus is, and of how many
        head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        headName = Ui.caps(a, 10, FAINT, 0.22f);
        headCount = Ui.caps(a, 10, FAINT, 0.22f);
        hair = new Hair(a);
        head.addView(headName);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(110), Ui.dp(2));
        hl.leftMargin = hl.rightMargin = Ui.dp(12);
        head.addView(hair, hl);
        head.addView(headCount);
        addView(head, at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, LEFT, ROW_TOP - 36));

        // the row's window: the whole width, so the poster just passed stays in view at the left edge
        window = new Lane(a);
        addView(window, at(ViewGroup.LayoutParams.MATCH_PARENT, H + 2 * ROOM, 0, ROW_TOP - ROOM));
        note = Ui.text(a, 14, Ui.medium, 0xB3FFFFFF);
        addView(note, at(520, ViewGroup.LayoutParams.WRAP_CONTENT, LEFT, ROW_TOP + 40));

        page = new Page();
        addView(page, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mini = new Mini(a);
        addView(mini, at(40, 60, 0, 0));

        glide = new Spring.Glide(Spring.smooth(), AllApps.GLIDE_MS, 1f, 0f, v -> slide());
        give = new Spring.Mover(new Spring(0.35, 0.25), 0.5f, 0f, v -> slide());
        tint(aura);
    }

    private static LayoutParams at(int wDp, int hDp, float leftDp, float topDp) {
        LayoutParams l = new LayoutParams(wDp > 0 ? Ui.dp(wDp) : wDp, hDp > 0 ? Ui.dp(hDp) : hDp, Gravity.TOP | Gravity.START);
        l.leftMargin = Ui.dp(leftDp);
        l.topMargin = Ui.dp(topDp);
        return l;
    }

    private static LinearLayout.LayoutParams below(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Ui.dp(topDp);
        return l;
    }

    private static LinearLayout.LayoutParams gap(int leftDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = Ui.dp(leftDp);
        return l;
    }

    /** A remote's key as a small white cap, before what it does. */
    private TextView cap(String key) {
        TextView t = Ui.text(act, 10.5f, Ui.bold, INK);
        t.setText(key);
        t.setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(Ui.dpf(999));
        t.setBackground(g);
        return t;
    }

    private TextView hint(String text) {
        TextView t = Ui.text(act, 11.5f, Ui.medium, FAINT);
        t.setText(text);
        t.setPadding(Ui.dp(8), 0, 0, 0);
        return t;
    }

    /** A genre taking or losing the focus: the strip's own doing first, then the light under the row goes with the posters' focus. */
    private OnFocusChangeListener chain(final OnFocusChangeListener first) {
        return (v, gained) -> {
            if (first != null) first.onFocusChange(v, gained);
            light();
            awake();
        };
    }

    boolean isOpen() {
        return shown;
    }

    /**
     * The screen is being let go (closed a while, switched off in Settings, or home going for good):
     * what was still being read is dropped, nothing of it moves any more, and its threads end. Home
     * takes it out of the window and forgets it, and its posters and pictures go with it.
     */
    void release() {
        asked++;
        heroAsked++;
        removeCallbacks(readHero);
        removeCallbacks(rest);
        removeCallbacks(held);
        animate().cancel();
        hero.animate().cancel();
        if (auraMove != null) auraMove.cancel();
        if (spin != null) spin.cancel();
        shelves.clear();
        reader.shutdown();
        heroReader.shutdown();
        ahead.shutdownNow();
        fetcher.shutdownNow();
        asker.shutdownNow();
    }

    /**
     * Short on memory with the screen still up (a trailer or an app playing over it): only the genre
     * on show keeps its posters. Another is read from the TV again when it's chosen.
     */
    void shed() {
        java.util.Iterator<Shelf> it = shelves.values().iterator();
        while (it.hasNext()) if (it.next() != installed) it.remove();
    }

    /** Opens on the genre it was left on (the first time, every genre together), the focus on its first title. */
    void open() {
        if (shown) return;
        shown = true;
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        animate().cancel();
        setVisibility(VISIBLE);
        if (Ui.light) {
            // LOW: there whole on the first frame; a screen-sized fade is what its GPU can't draw in time
            setAlpha(1f);
        } else {
            setAlpha(0f);
            animate().alpha(1f).setStartDelay(0).setDuration(220).setInterpolator(Ui.EASE_DECEL).start();
            hero.arrive();
        }
        // the genres come down into place as the words and the row come in
        strip.setTranslationY(-Ui.dpf(16));
        strip.setAlpha(0f);
        strip.animate().translationY(0f).alpha(1f).setStartDelay(120).setDuration(560).setInterpolator(Ui.EASE_DECEL).start();
        if (current < 0) {
            show(1);
            strip.choose(1, false);
        }
        strip.live(true);
        // from its first title each time, whose words come in as the focus takes it
        if (installed != null) glide.jump(0f);
        wantRow = !focusRow();
        if (wantRow) strip.tab(current).requestFocus();
        else deal(installed, 300);
        reader.execute(() -> {
            final int n = Watch.mine(act).size();
            post(() -> {
                mineCount = n;
                badge();
            });
        });
        awake();
        String title = act.getString(R.string.watch);
        setContentDescription(title);
        announceForAccessibility(title);
    }

    void close() {
        if (!shown) return;
        shown = false;
        strip.live(false);
        removeCallbacks(readHero);
        removeCallbacks(rest);
        removeCallbacks(held);
        armed = false;
        heroAsked++;
        if (spin != null) spin.cancel();
        page.shut();
        still(BROWSE);
        // on its way out, nothing in it takes the focus
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        animate().cancel();
        if (Ui.light) {
            setVisibility(GONE);
        } else {
            animate().alpha(0f).setStartDelay(0).setDuration(Ui.CLOSE).setInterpolator(Ui.EASE_STANDARD).withEndAction(() -> {
                if (!shown) setVisibility(GONE);
            }).start();
        }
    }

    /** Back: an open title closes, a spin lands; false when the screen itself is what Back leaves. */
    boolean back() {
        if (!shown) return false;
        if (spinning) {
            if (spin != null) spin.end();
            return true;
        }
        if (page.open) {
            page.close();
            return true;
        }
        return false;
    }

    /** Its fade goes straight onto its picture and its words: no screen-sized offscreen pass. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private boolean focusRow() {
        return installed != null && installed.posters.length > 0 && installed.posters[0].requestFocus();
    }

    /** The poster with the focus, or null when the focus is on the genres or an open title. */
    private Poster focused() {
        if (installed == null) return null;
        for (Poster p : installed.posters) if (p.isFocused()) return p;
        return null;
    }

    /** My list changed (a title saved or taken off): shown again when it's the one on show. */
    private void mineChanged() {
        shelves.remove(Watch.MINE);
        if (current >= 0 && ids.get(current).equals(Watch.MINE)) {
            int k = current;
            current = -1;
            // the poster that had the focus goes with the old shelf: its genre holds it meanwhile
            boolean had = hasFocus();
            if (had) strip.tab(k).requestFocus();
            show(k);
            wantRow = had && installed == null;
        }
    }

    /** My list's tab says how many it holds, in the colour the screen is lit in. */
    private void badge() {
        String name = titles.get(0);
        if (mineCount <= 0) {
            strip.relabel(0, name);
            return;
        }
        SpannableStringBuilder s = new SpannableStringBuilder(name + "  " + mineCount);
        s.setSpan(new Badge(), name.length() + 2, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        strip.relabel(0, s);
    }

    // ---- the colour the screen is lit in ---------------------------------------------------------

    /** The screen turns to {@code to}: at once on LOW, over half a second on HIGH. */
    private void lightIn(int to) {
        if (auraMove != null) auraMove.cancel();
        if (to == aura) return;
        if (Ui.light) {
            tint(to);
            return;
        }
        auraMove = ValueAnimator.ofObject(new ArgbEvaluator(), aura, to);
        auraMove.setDuration(520);
        auraMove.addUpdateListener(a -> tint((Integer) a.getAnimatedValue()));
        auraMove.start();
    }

    private void tint(int c) {
        aura = c;
        glow.tint(c);
        words.tint(c);
        hair.tint(c);
        page.tint(c);
        if (mineCount > 0) strip.tab(0).invalidate();
    }

    /** The light under the row is the focused poster's: gone while the focus is on the genres, at rest, or a title is open. */
    private void light() {
        float to = pose == BROWSE && !spinning && focused() != null ? 1f : 0f;
        glow.animate().alpha(to).setStartDelay(0).setDuration(to > 0f ? 420 : 240).start();
    }

    // ---- at rest, and back ----------------------------------------------------------------------

    /** Something was pressed or moved: the screen is as it is for moving along, and rests again after {@link #REST_MS}. */
    private void awake() {
        removeCallbacks(rest);
        if (pose == REST) pose(BROWSE);
        if (shown) postDelayed(rest, REST_MS);
    }

    /** Everything takes its place for {@code to}: the genres, the words, the row and its heading, the light and the picture. */
    private void pose(int to) {
        if (pose == to) return;
        int was = pose;
        pose = to;
        long ms = to == REST ? 900 : to == PAGE || was == PAGE ? 520 : 280;
        if (Ui.light) ms = Math.min(ms, 340);
        place(to, ms);
        if (Ui.light) return;
        // HIGH: at rest the picture's foot clears and it drifts closer; any key puts it back
        if (to == REST) openHero();
        else hero.open(null);
    }

    /** There at once, nothing moving: the screen is going away. */
    private void still(int to) {
        pose = to;
        place(to, 0);
        hero.open(null);
    }

    private void place(int to, long ms) {
        boolean rests = to == REST, open = to == PAGE;
        move(strip, open ? 0.42f : rests ? 0f : 1f, 0f, rests ? -Ui.dpf(10) : 0f, ms);
        move(words, open ? 0f : 1f, open ? -Ui.dpf(24) : 0f, rests ? Ui.dpf(112) : 0f, ms);
        move(more, rests ? 1f : 0f, 0f, 0f, ms);
        float rowA = open ? 0f : rests ? 0.5f : 1f, rowY = open ? Ui.dpf(200) : rests ? Ui.dpf(106) : 0f;
        move(window, rowA, 0f, rowY, ms);
        move(head, rowA, 0f, rowY, ms);
        light();
    }

    private static void move(View v, float alpha, float x, float y, long ms) {
        v.animate().cancel();
        if (ms <= 0) {
            v.setAlpha(alpha);
            v.setTranslationX(x);
            v.setTranslationY(y);
            return;
        }
        v.animate().alpha(alpha).translationX(x).translationY(y).setStartDelay(0).setDuration(ms).setInterpolator(Ui.EASE_DECEL).start();
    }

    /** The picture of the title at rest, made again with its foot's shade lower, and dissolved in. */
    private void openHero() {
        final Poster at = inFocus;
        if (at == null || heroShown != at.pick || !hero.has()) return;
        final int mine = heroAsked, w = getWidth(), h = getHeight();
        heroReader.execute(() -> {
            final Bitmap b = picture(at, w, h, Ui.dpf(104));
            if (b != null) post(() -> {
                if (mine == heroAsked && pose == REST) hero.open(b);
            });
        });
    }

    // ---- a genre's shelf -----------------------------------------------------------------------

    private void show(int k) {
        if (k < 0 || k >= ids.size() || k == current) return;
        current = k;
        wantRow = false;
        final String id = ids.get(k);
        final int mine = ++asked;
        if (installed != null) window.removeView(installed.view);
        installed = null;
        inFocus = null;
        headName.setText(titles.get(k).toUpperCase(Locale.getDefault()));
        headCount.setText("");
        hair.to(0f);
        Shelf kept = shelves.get(id);
        if (kept != null) {
            install(kept);
            return;
        }
        say(null, 0, 0);
        note.setText(id.equals(Watch.MINE) ? "" : act.getString(R.string.watch_finding));
        note.setVisibility(VISIBLE);
        reader.execute(() -> {
            if (id.equals(Watch.MINE)) {
                shelfRead(k, mine, Watch.mine(act), false);
                return;
            }
            final List<Picks.Pick> onTv = Watch.shelf(act, id);
            // kept on the TV and fresh: shown as it is
            if (!onTv.isEmpty() && !Watch.stale(act, id)) {
                shelfRead(k, mine, onTv, false);
                return;
            }
            // else fetched, and what was kept stands in if that fails: on a thread of its own, so the
            // posters of a genre already on the TV aren't read only after a slow network has answered
            fetcher.execute(() -> {
                if (mine != asked) return;
                if (Watch.fetch(act, id, false)) shelfRead(k, mine, Watch.shelf(act, id), false);
                else shelfRead(k, mine, onTv, onTv.isEmpty());
            });
        });
    }

    /** Genre {@code k}'s shelf, read (and perhaps fetched), goes on show if it's still the one chosen. Off the main thread. */
    private void shelfRead(int k, int mine, List<Picks.Pick> got, boolean none) {
        final String id = ids.get(k);
        post(() -> {
            if (mine != asked) return;
            if (got.isEmpty()) {
                note.setText(act.getString(none ? R.string.watch_no_network : id.equals(Watch.MINE) ? R.string.watch_nothing_saved : R.string.watch_nothing_found));
                note.setVisibility(VISIBLE);
                wantRow = false;
                return;
            }
            Shelf s = build(got, strip.tab(k));
            // LOW: one genre's posters at a time (a few MB each); HIGH keeps those visited while the screen lives
            if (Ui.light) shelves.clear();
            shelves.put(id, s);
            install(s);
        });
    }

    private void install(Shelf s) {
        note.setVisibility(GONE);
        // as wide as all its posters, said outright: left to size itself, a row is given no more than the
        // window's width, and every poster past the screen's edge was cut away as the row glided
        int n = s.posters.length;
        int rowWide = Ui.dp(LEFT) + n * Ui.dp(W) + Math.max(0, n - 1) * Ui.dp(GAP) + Ui.dp(ROOM);
        if (s.view.getParent() != null) ((ViewGroup) s.view.getParent()).removeView(s.view);
        window.addView(s.view, new FrameLayout.LayoutParams(rowWide, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START));
        installed = s;
        glide.jump(0f);
        // the first title is what the screen shows until another takes the focus
        if (s.posters.length > 0) {
            Poster first = s.posters[0];
            if (wantRow && shown && !page.open && first.requestFocus()) wantRow = false;
            if (!first.isFocused()) {
                inFocus = first;
                say(first.pick, 0, 0);
                lightIn(first.aura);
                counted(0);
            }
        }
        deal(s, 0);
        fetchAhead();
        final int mine = asked;
        final int wide = Ui.dp(W);
        for (final Poster p : s.posters) {
            if (p.picture != null) continue;
            reader.execute(() -> {
                if (mine != asked) return;
                final Bitmap b = Continue.picture(p.pick.poster, wide);
                if (b == null) return;
                final int its = Watch.aura(b);
                post(() -> {
                    p.take(b, its);
                    if (p == inFocus && !spinning) lightIn(its);
                });
            });
        }
    }

    /** The shelf's posters dealt in from the right, one after another, {@code wait} ms from now; those past the screen's edge are simply there. */
    private void deal(Shelf s, long wait) {
        Poster on = focused();
        for (int i = 0; i < s.posters.length; i++) {
            Poster p = s.posters[i];
            p.want = on == null ? AHEAD : i == on.index ? 1f : i < on.index ? PASSED : AHEAD;
            if (i >= DEALT || on != null && on.index > 0) {
                p.setTranslationX(0f);
                p.setAlpha(p.want);
                continue;
            }
            p.setTranslationX(Ui.dpf(80));
            p.setAlpha(0f);
            p.animate().translationX(0f).alpha(p.want).setStartDelay(wait + i * 42L).setDuration(Ui.light ? 380 : 620)
                    .setInterpolator(Ui.EASE_DECEL).start();
        }
    }

    private Shelf build(List<Picks.Pick> list, View up) {
        LinearLayout row = new LinearLayout(act);
        row.setClipChildren(false);
        // the room for a poster in focus to grow is the row's own: Android cuts a row to its bounds
        // where the window above it clips, and a row only as big as its posters cut the one in focus
        row.setPadding(Ui.dp(LEFT), Ui.dp(ROOM), Ui.dp(ROOM), Ui.dp(ROOM));
        row.setClipToPadding(false);
        int n = Math.min(list.size(), Watch.MOST);
        Poster[] posters = new Poster[n];
        for (int i = 0; i < n; i++) {
            final Poster p = new Poster(list.get(i), i);
            p.setId(View.generateViewId());
            p.setOnClickListener(v -> page.open(p));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(W), Ui.dp(H));
            if (i > 0) pl.leftMargin = Ui.dp(GAP);
            row.addView(p, pl);
            posters[i] = p;
        }
        for (int i = 0; i < n; i++) {
            Poster p = posters[i];
            int self = p.getId();
            p.setNextFocusLeftId(i == 0 ? self : posters[i - 1].getId());
            p.setNextFocusRightId(i == n - 1 ? self : posters[i + 1].getId());
            p.setNextFocusUpId(up.getId());
            p.setNextFocusDownId(self);
        }
        return new Shelf(row, posters);
    }

    /** The screen becomes {@code p}: its words now (arriving from the side the focus went, {@code dir}), its wide picture once the focus has rested on it; null clears the words. */
    private void say(Picks.Pick p, int dir, long wait) {
        words.set(p, inFocus == null ? null : inFocus.app);
        if (p != null) words.arrive(dir, wait);
        removeCallbacks(readHero);
        heroAsked++;
        heroDir = dir < 0 ? -1 : 1;
        if (p != null) postDelayed(readHero, HERO_MS);
    }

    /** The heading over the row: the focus is on title {@code i} of the shelf. */
    private void counted(int i) {
        int n = installed == null ? 0 : installed.posters.length;
        headCount.setText(n == 0 ? "" : String.format(Locale.ROOT, "%02d / %02d", i + 1, n));
        hair.to(n == 0 ? 0f : (i + 1) / (float) n);
    }

    /** Every poster as faint as its place asks: the one in focus whole, those waiting less, those passed least. */
    private void fade(int on) {
        if (installed == null) return;
        for (Poster p : installed.posters) {
            float to = spinning ? 0.8f : p.index == on ? 1f : p.index < on ? PASSED : AHEAD;
            if (p.want == to) continue;
            p.want = to;
            p.animate().alpha(to).setStartDelay(0).setDuration(240).setInterpolator(Ui.EASE_DECEL).start();
        }
    }

    /** Once a shelf is on show, the genres not fetched this week are, quietly, one at a time. */
    private void fetchAhead() {
        if (fetchedAhead) return;
        fetchedAhead = true;
        for (final String id : ids) {
            if (id.equals(Watch.MINE)) continue;
            ahead.execute(() -> {
                if (shown && Watch.stale(act, id)) Watch.fetch(act, id, true);
            });
        }
    }

    /**
     * {@code at}'s picture for the hero, as one opaque picture the size of the screen with its
     * shades already on it ({@link Hero#bake}), so each frame draws it once: its wide picture, read
     * (and the first time fetched), or its poster standing in its own light when it has none.
     * {@code drop}: how far down the foot's shade sits. On the hero's thread.
     */
    private Bitmap picture(Poster at, int w, int h, float drop) {
        if (w == 0 || h == 0) return null;
        File f = Watch.hero(act, at.pick);
        boolean standIn = f == null;
        // as wide as the screen: read at half that, it was drawn at twice its size, soft
        Bitmap b = Continue.picture(standIn ? at.pick.poster : f.getPath(), standIn ? Ui.dp(W) : Math.max(960, w));
        if (b == null) return null;
        try {
            return Hero.bake(b, w, h, standIn, standIn && at.picture == null ? Watch.aura(b) : at.aura, drop);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** The wide picture of the title in focus sweeps in, and the app here that streams it is asked for. */
    private void readHero() {
        final Poster at = inFocus;
        if (at == null || !shown) return;
        ask(at);
        // back on the title whose picture is already there: nothing to sweep
        if (heroShown == at.pick) return;
        final int mine = ++heroAsked, dir = heroDir, w = getWidth(), h = getHeight();
        heroReader.execute(() -> {
            if (mine != heroAsked) return;
            final Bitmap baked = picture(at, w, h, 0f);
            if (baked != null) post(() -> {
                if (mine != heroAsked) return;
                heroShown = at.pick;
                hero.take(baked, dir);
            });
        });
    }

    /** Where {@code at}'s title streams, asked once and only while it's the one in focus or open. */
    private void ask(final Poster at) {
        if (at.asked) return;
        at.asked = true;
        asker.execute(() -> {
            if (at != inFocus) {
                // passed long before its turn came: asked again should the focus come back
                at.asked = false;
                return;
            }
            final Watch.App app = Watch.where(act, at.pick);
            // no answer from the network: asked again when the focus comes back
            if (app == null) at.asked = false;
            else if (app != Watch.NONE) post(() -> {
                at.app = app;
                if (at == inFocus) words.where(app);
                if (page.open && page.from == at) page.label();
            });
        });
    }

    /** The row glides so the poster in focus is where the focus always is. */
    private void glideTo(int i) {
        float to = i * (float) Ui.dp(STEP);
        if (to == glide.target()) return;
        glide.to(to, Ui.scrollFloorMs(window, Math.abs(to - glide.value()), false));
    }

    private void slide() {
        if (installed != null) installed.view.setTranslationX(give.value() - glide.value());
    }

    // ---- decide for me ---------------------------------------------------------------------------

    /**
     * For the evening nobody can decide: the row spins, slows and lands on one title, far enough
     * from where it stood to be a surprise. The focus only moves once it has landed, so nothing is
     * read or fetched for the titles going by.
     */
    private void decide() {
        final Poster from = focused();
        if (from == null || spinning || page.open || installed == null) return;
        final Poster[] ps = installed.posters;
        final int n = ps.length;
        if (n < 4) return;
        int least = Math.min(6, n / 2), to;
        do {
            to = random.nextInt(n);
        } while (Math.abs(to - from.index) < least);
        final Poster land = ps[to];
        final float step = Ui.dp(STEP);
        removeCallbacks(rest);
        removeCallbacks(readHero);
        if (pose == REST) pose(BROWSE);
        spinning = true;
        heroAsked++;
        glide.finish();
        from.lift(false);
        from.invalidate();
        fade(-1);
        light();
        words.deciding(act.getString(R.string.watch_deciding));
        spin = ValueAnimator.ofFloat(from.index * step, to * step);
        spin.setDuration(Math.max(1500, Math.min(2600, 1300 + 70L * Math.abs(to - from.index))));
        spin.setInterpolator(new DecelerateInterpolator(2.2f));
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            int over = from.index;

            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float x = (Float) a.getAnimatedValue();
                glide.jump(x);
                int k = Math.max(0, Math.min(n - 1, Math.round(x / step)));
                if (k == over) return;
                over = k;
                // the name and the colour of each title as it goes by under the focus
                words.name.setText(ps[k].pick.name);
                if (auraMove != null) auraMove.cancel();
                tint(ps[k].aura);
            }
        });
        spin.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                spin = null;
                spinning = false;
                glide.jump(land.index * step);
                if (!shown) return;
                fade(land.index);
                if (!land.requestFocus()) return;
                // it lands: larger than it will stand, settling to its size
                land.animate().cancel();
                land.setScaleX(GROW * 1.16f);
                land.setScaleY(GROW * 1.16f);
                land.lift(true);
                light();
            }
        });
        spin.start();
    }

    // ---- the remote ------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!shown) return super.dispatchKeyEvent(e);
        final int code = e.getKeyCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // while it spins the remote waits; Back, which home asks of back(), lands it
        if (spinning) return true;
        if (down) awake();
        boolean ok = Pad.isConfirm(code);
        if (page.open) {
            if (ok) {
                if (down) {
                    if (e.getRepeatCount() == 0) armed = true;
                } else {
                    if (armed && !e.isCanceled()) page.press();
                    armed = false;
                }
                return true;
            }
            // nothing above or below a title's choices
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.dispatchKeyEvent(e);
        }
        Poster on = focused();
        if (on != null) {
            if (ok) {
                // OK on a title: a press opens it, a hold decides, timed here as an IR remote's own long-press often never arrives
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        armed = true;
                        postDelayed(held, ViewConfiguration.getLongPressTimeout());
                    } else if ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                        removeCallbacks(held);
                        held.run();
                    }
                } else {
                    removeCallbacks(held);
                    if (armed && !e.isCanceled()) page.open(on);
                    armed = false;
                }
                return true;
            }
            // either end of the row gives a little and springs back
            int last = installed.posters.length - 1;
            if (down && (code == KeyEvent.KEYCODE_DPAD_LEFT && on.index == 0 || code == KeyEvent.KEYCODE_DPAD_RIGHT && on.index == last)) {
                if (e.getRepeatCount() == 0 && !give.moving()) {
                    give.jump(code == KeyEvent.KEYCODE_DPAD_LEFT ? Ui.dpf(14) : -Ui.dpf(14));
                    give.to(0f);
                }
                return true;
            }
        }
        if (strip.focused() >= 0 && down) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Down on a genre picked a moment ago: its shelf comes now, and the focus goes to its first title
                strip.pickNow();
                // its shelf still on its way: the focus follows once it's here
                if (!focusRow()) wantRow = installed == null;
                return true;
            }
            // nothing above the genres
            if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- the picture behind ----------------------------------------------------------------------

    /**
     * The wide picture behind everything, under two shades: dark at the left where the words are,
     * and dark at the foot where the posters are. One opaque picture the size of the screen, made
     * behind the screen and drawn once a frame. A new one sweeps in from the side the focus went:
     * each is drawn only on its own side of the sweep, so no frame draws the screen twice; HIGH
     * softens the edge with a band of the new one, a fifth of the screen wide, laid over in slices.
     */
    private static final class Hero extends View {
        private static final int DARK = 0xFF08090C, SLICES = 24;
        private static final TimeInterpolator SWEEP = new PathInterpolator(0.45f, 0.05f, 0.2f, 1f);
        private Bitmap wide, next, open;
        /** How far the sweep is, and how far the picture at rest has dissolved in: 0 to 1. */
        private float swept, opened;
        private boolean fromRight;
        private ValueAnimator sweeper, opener;
        private final Paint fade = new Paint(), seam = new Paint(Paint.DITHER_FLAG);

        Hero(android.content.Context c) {
            super(c);
            float half = Ui.dpf(12);
            seam.setShader(new LinearGradient(-half, 0, half, 0, new int[]{0x00000000, 0x70000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        }

        /**
         * {@code picture} as a {@code w} by {@code h} screen, the two shades over it, as one opaque
         * picture: drawn live, the picture and each shade were a screen blended over a screen on
         * every frame the row moved, more than a low-end GPU draws in time. A wide picture covers
         * the screen from its top; a poster standing in for one ({@code standIn}) stands sharp at
         * the right in a light of its own colour, far too small to be blown up to the screen. The
         * left shade carries a little of {@code aura}; the foot's sits {@code drop} px lower when
         * the screen is at rest. Off the main thread.
         */
        static Bitmap bake(Bitmap picture, int w, int h, boolean standIn, int aura, float drop) {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(DARK);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            if (standIn) {
                stage(c, picture, w, h, aura, p);
            } else {
                float s = Math.max(w / (float) picture.getWidth(), h / (float) picture.getHeight());
                Matrix fit = new Matrix();
                fit.setScale(s, s);
                // from its top: faces sit in the upper half of a wide picture
                fit.postTranslate((w - picture.getWidth() * s) / 2f, 0f);
                c.drawBitmap(picture, fit, p);
            }
            Paint shade = new Paint(Paint.DITHER_FLAG);
            int left = Ui.mix(DARK, aura, 0.08f) & 0xFFFFFF;
            shade.setShader(new LinearGradient(0, 0, w * 0.72f, 0, new int[]{0xF0000000 | left, 0xC708090C, 0x2608090C, 0x0008090C},
                    new float[]{0f, 0.42f, 0.86f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w * 0.72f, h, shade);
            shade.setShader(new LinearGradient(0, h + drop, 0, h * 0.42f + drop, new int[]{0xFA08090C, 0xD108090C, 0x0008090C},
                    new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.42f + drop, w, h, shade);
            out.setHasAlpha(false);
            return out;
        }

        /** A title with no wide picture: its poster sharp at the right, its own colour as a light behind it, and the poster again far out of focus over that side of the screen. */
        private static void stage(Canvas c, Bitmap poster, int w, int h, int aura, Paint p) {
            Paint wash = new Paint(Paint.DITHER_FLAG);
            wash.setShader(new RadialGradient(w * 0.74f, h * 0.42f, w * 0.44f, new int[]{Ui.withAlpha(aura, 130), Ui.withAlpha(aura, 0)}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, wash);
            // read tiny and drawn large, twice over: out of focus without a blur to compute
            Bitmap tiny = Bitmap.createScaledBitmap(poster, 10, 15, true);
            Bitmap soft = Bitmap.createScaledBitmap(tiny, 80, 120, true);
            p.setAlpha(120);
            c.drawBitmap(soft, null, new RectF(w * 0.36f, -h * 0.2f, w * 1.12f, h * 1.2f), p);
            p.setAlpha(255);
            if (tiny != poster) tiny.recycle();
            soft.recycle();
            float pw = Ui.dpf(190), ph = Ui.dpf(285), r = Ui.dpf(12);
            RectF at = new RectF(w - Ui.dpf(118) - pw, Ui.dpf(56), w - Ui.dpf(118), Ui.dpf(56) + ph);
            Paint under = new Paint(Paint.ANTI_ALIAS_FLAG);
            under.setColor(DARK);
            under.setShadowLayer(Ui.dpf(34), 0, Ui.dpf(22), 0xA0000000);
            c.drawRoundRect(at, r, r, under);
            BitmapShader sh = new BitmapShader(poster, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = Math.max(pw / poster.getWidth(), ph / poster.getHeight());
            Matrix fit = new Matrix();
            fit.setScale(s, s);
            fit.postTranslate(at.left + (pw - poster.getWidth() * s) / 2f, at.top + (ph - poster.getHeight() * s) / 2f);
            sh.setLocalMatrix(fit);
            p.setShader(sh);
            c.drawRoundRect(at, r, r, p);
            p.setShader(null);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.hair());
            edge.setColor(0x24FFFFFF);
            c.drawRoundRect(at, r, r, edge);
        }

        boolean has() {
            return wide != null && next == null;
        }

        /** {@code b} is the picture from now on, swept in from the right ({@code dir} 1) or the left (-1); the first one rises out of the dark. */
        void take(Bitmap b, int dir) {
            land();
            open(null);
            if (wide == null || wide.getWidth() != b.getWidth()) {
                wide = b;
                if (Ui.light) {
                    setAlpha(1f);
                } else {
                    setAlpha(0.25f);
                    animate().alpha(1f).setStartDelay(0).setDuration(260).setInterpolator(Ui.EASE_DECEL).start();
                }
                invalidate();
                return;
            }
            next = b;
            fromRight = dir >= 0;
            swept = 0f;
            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            sweeper = a;
            a.setDuration(Ui.light ? 320 : 660);
            a.setInterpolator(SWEEP);
            a.addUpdateListener(v -> {
                if (sweeper != a) return;
                swept = (Float) v.getAnimatedValue();
                invalidate();
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (sweeper == a) land();
                }
            });
            a.start();
        }

        /** A sweep still on its way is finished at once. */
        private void land() {
            ValueAnimator a = sweeper;
            sweeper = null;
            if (a != null) a.cancel();
            if (next != null) {
                wide = next;
                next = null;
                invalidate();
            }
        }

        /** The way in: the picture comes from a touch closer. */
        void arrive() {
            if (wide == null) return;
            setScaleX(1.07f);
            setScaleY(1.07f);
            animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(900).setInterpolator(Ui.EASE_DECEL).start();
        }

        /**
         * At rest: {@code b}, the same picture with its foot clear, dissolves in and then drifts
         * slowly closer, once, and stops. Null dissolves back and takes it away. HIGH only: it's a
         * screen blended over a screen while it dissolves.
         */
        void open(Bitmap b) {
            if (b == null && open == null) return;
            if (opener != null) opener.cancel();
            final boolean in = b != null;
            if (in) open = b;
            final ValueAnimator a = ValueAnimator.ofFloat(opened, in ? 1f : 0f);
            opener = a;
            a.setDuration(in ? 900 : 260);
            a.setInterpolator(Ui.EASE_DECEL);
            a.addUpdateListener(v -> {
                if (opener != a) return;
                opened = (Float) v.getAnimatedValue();
                invalidate();
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (opener != a) return;
                    opener = null;
                    if (!in) {
                        opened = 0f;
                        open = null;
                        invalidate();
                    }
                }
            });
            a.start();
            setPivotX(getWidth() * 0.68f);
            setPivotY(getHeight() * 0.36f);
            if (in) animate().scaleX(1.06f).scaleY(1.06f).setStartDelay(700).setDuration(22_000).setInterpolator(new DecelerateInterpolator(1.2f)).start();
            else animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(320).setInterpolator(Ui.EASE_DECEL).start();
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            final int w = getWidth(), h = getHeight();
            if (wide == null || wide.getWidth() != w) {
                c.drawColor(DARK);
                return;
            }
            if (next == null) {
                if (open != null && opened >= 1f) {
                    c.drawBitmap(open, 0f, 0f, null);
                    return;
                }
                c.drawBitmap(wide, 0f, 0f, null);
                if (open != null && opened > 0f) {
                    fade.setAlpha(Math.round(opened * 255f));
                    c.drawBitmap(open, 0f, 0f, fade);
                }
                return;
            }
            // the band where the two meet: none on LOW, where a dark seam stands in for it
            float band = Ui.light ? 0f : w * 0.22f, at = swept * (w + band);
            // l..r: the band, the new picture whole beyond it on the side it comes from
            float l = fromRight ? w - at : at - band, r = l + band;
            c.save();
            if (fromRight) c.clipRect(0, 0, Ui.clamp(r, 0f, w), h);
            else c.clipRect(Ui.clamp(l, 0f, w), 0, w, h);
            c.drawBitmap(wide, 0f, 0f, null);
            c.restore();
            c.save();
            if (fromRight) c.clipRect(Ui.clamp(r, 0f, w), 0, w, h);
            else c.clipRect(0, 0, Ui.clamp(l, 0f, w), h);
            c.drawBitmap(next, 0f, 0f, null);
            c.restore();
            if (band <= 0f) {
                c.save();
                c.translate(l, 0f);
                c.drawRect(-Ui.dpf(12), 0, Ui.dpf(12), h, seam);
                c.restore();
                return;
            }
            float slice = band / SLICES;
            for (int i = 0; i < SLICES; i++) {
                float x0 = l + i * slice, x1 = x0 + slice;
                if (x1 <= 0f || x0 >= w) continue;
                // how much of the new picture this slice shows: none at the old picture's side, all of it at its own
                float t = (i + 0.5f) / SLICES;
                if (!fromRight) t = 1f - t;
                fade.setAlpha(Math.round(t * t * (3f - 2f * t) * 255f));
                c.save();
                c.clipRect(x0, 0, x1, h);
                c.drawBitmap(next, 0f, 0f, fade);
                c.restore();
            }
        }
    }

    /** A soft round light, as an alpha mask drawn in whatever colour it's asked for: one small picture stretched. */
    private static Bitmap halo;

    private static Bitmap halo() {
        if (halo == null) {
            Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new RadialGradient(32, 32, 32, new int[]{0xFF000000, 0x8A000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            new Canvas(b).drawRect(0, 0, 64, 64, p);
            halo = b;
        }
        return halo;
    }

    /** The light the poster in focus casts on the dark under it, in its title's colour. */
    private static final class Glow extends View {
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF box = new RectF();

        Glow(android.content.Context c) {
            super(c);
        }

        void tint(int color) {
            p.setColor(Ui.withAlpha(color, 128));
            invalidate();
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            box.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(halo(), null, box, p);
        }
    }

    /** The hairline in the row's heading, filled as far as the focus is along the shelf. */
    private static final class Hair extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Spring.Mover along = new Spring.Mover(Spring.smooth(), 1f / 256f, 0f, v -> invalidate());

        Hair(android.content.Context c) {
            super(c);
            track.setColor(0x24FFFFFF);
        }

        void tint(int color) {
            fill.setColor(color);
            invalidate();
        }

        void to(float part) {
            along.to(part);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, getWidth(), h, r, r, track);
            float w = getWidth() * Ui.clamp(along.value(), 0f, 1f);
            if (w > 0f) c.drawRoundRect(0, 0, w, h, r, r, fill);
        }
    }

    /** A title's score out of ten, as a ring that fills to it in its colour. */
    private static final class Dial extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Spring.Mover part = new Spring.Mover(new Spring(0.7, 0), 1f / 256f, 0f, v -> invalidate());

        Dial(android.content.Context c) {
            super(c);
            for (Paint p : new Paint[]{track, arc}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(2.6f));
            }
            arc.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(0x29FFFFFF);
        }

        void tint(int color) {
            arc.setColor(color);
            invalidate();
        }

        /** Fills from empty to {@code score} tenths. */
        void to(float score) {
            part.jump(0f);
            part.to(score / 10f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float in = arc.getStrokeWidth() / 2f + Ui.dpf(1);
            box.set(in, in, getWidth() - in, getHeight() - in);
            c.drawOval(box, track);
            float sweep = 360f * Ui.clamp(part.value(), 0f, 1f);
            if (sweep > 0f) c.drawArc(box, -90f, sweep, false, arc);
        }
    }

    /** A dot of the title's colour before the line over its name. */
    private static final class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dot(android.content.Context c) {
            super(c);
        }

        void tint(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, p);
        }
    }

    /** The row's window: its fade goes straight onto each poster, none overlapping another, with no offscreen pass. */
    private static final class Lane extends FrameLayout {
        Lane(android.content.Context c) {
            super(c);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    /** How many My list holds, after its name on its tab: a small pill in the colour the screen is lit in. */
    private final class Badge extends ReplacementSpan {
        private final RectF box = new RectF();

        private float width(Paint p, CharSequence text, int start, int end) {
            float size = p.getTextSize();
            p.setTextSize(size * 0.78f);
            float w = p.measureText(text, start, end) + size * 0.8f;
            p.setTextSize(size);
            return w;
        }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(width(p, text, start, end));
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            float size = p.getTextSize(), w = width(p, text, start, end), half = size * 0.62f, mid = y - size * 0.36f;
            int color = p.getColor();
            box.set(x, mid - half, x + w, mid + half);
            p.setColor(aura);
            c.drawRoundRect(box, half, half, p);
            p.setColor(INK);
            p.setTextSize(size * 0.78f);
            c.drawText(text, start, end, x + size * 0.4f, mid + size * 0.28f, p);
            p.setTextSize(size);
            p.setColor(color);
        }
    }

    // ---- a title in words ------------------------------------------------------------------------

    /**
     * A title in words: the line over its name (film or series, its genres, a dot of its colour
     * before them), its name, its score as a dial with its year and the app here that streams it,
     * and a few lines about it. They arrive one after another from the side the focus went.
     */
    private final class Words extends LinearLayout {
        final TextView kicker, name, score, line, where, about;
        private final Dot dot;
        private final Dial dial;
        private final LinearLayout top, meta;

        Words(float nameSp, int aboutLines) {
            super(act);
            setOrientation(VERTICAL);
            top = new LinearLayout(act);
            top.setGravity(Gravity.CENTER_VERTICAL);
            dot = new Dot(act);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(6), Ui.dp(6));
            dl.rightMargin = Ui.dp(9);
            top.addView(dot, dl);
            kicker = Ui.caps(act, 10, FAINT, 0.22f);
            top.addView(kicker);
            name = Ui.text(act, nameSp, Ui.heavy, 0xFFFFFFFF);
            name.setLetterSpacing(-0.035f);
            name.setSingleLine(false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLineSpacing(0f, 0.98f);
            meta = new LinearLayout(act);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            dial = new Dial(act);
            meta.addView(dial, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
            score = Ui.text(act, 13, Ui.bold, 0xFFFFFFFF);
            meta.addView(score, gap(5));
            line = Ui.text(act, 13, Ui.semibold, SOFT);
            meta.addView(line, gap(12));
            where = Ui.text(act, 11.5f, Ui.semibold, 0xFFFFFFFF);
            where.setPadding(Ui.dp(9), Ui.dp(3), Ui.dp(9), Ui.dp(3));
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(0x1FFFFFFF);
            chip.setStroke(Ui.hair(), 0x29FFFFFF);
            chip.setCornerRadius(Ui.dpf(999));
            where.setBackground(chip);
            meta.addView(where, gap(12));
            about = Ui.text(act, 13.5f, Ui.regular, SOFT);
            about.setSingleLine(false);
            about.setMaxLines(aboutLines);
            about.setEllipsize(android.text.TextUtils.TruncateAt.END);
            about.setLineSpacing(Ui.dpf(3), 1f);
            addView(top);
            // as wide as the words' own column whatever it says: a new name of as many lines is drawn, not laid out again
            LinearLayout.LayoutParams nl = below(5);
            nl.width = ViewGroup.LayoutParams.MATCH_PARENT;
            addView(name, nl);
            addView(meta, below(7));
            LinearLayout.LayoutParams al = below(9);
            al.width = Ui.dp(Math.round(nameSp > 40 ? 430 : 480));
            addView(about, al);
        }

        /** Says {@code p}, streamed here by {@code app} if anyone knows; null clears it. */
        void set(Picks.Pick p, Watch.App app) {
            top.setVisibility(p == null ? INVISIBLE : VISIBLE);
            meta.setVisibility(p == null ? INVISIBLE : VISIBLE);
            name.setText(p == null ? "" : p.name);
            about.setText(p == null ? "" : p.about);
            if (p == null) return;
            Watch.Facts f = new Watch.Facts(p.line);
            kicker.setText(f.kinds.toUpperCase(Locale.getDefault()));
            top.setVisibility(f.kinds.isEmpty() ? GONE : VISIBLE);
            boolean scored = f.score > 0f;
            dial.setVisibility(scored ? VISIBLE : GONE);
            score.setVisibility(scored ? VISIBLE : GONE);
            score.setText(scored ? String.format(Locale.ROOT, "%.1f", f.score) : "");
            if (scored) dial.to(f.score);
            line.setText(f.year);
            line.setVisibility(f.year.isEmpty() ? GONE : VISIBLE);
            where(app);
        }

        void where(Watch.App app) {
            where.setVisibility(app == null ? GONE : VISIBLE);
            if (app != null) where.setText(act.getString(R.string.watch_on_app, app.label));
        }

        /** While the row spins: only the name of the title going by, under a line that says it's deciding. */
        void deciding(String saying) {
            top.setVisibility(VISIBLE);
            kicker.setText(saying.toUpperCase(Locale.getDefault()));
            meta.setVisibility(INVISIBLE);
            about.setText("");
        }

        void tint(int color) {
            dot.tint(color);
            dial.tint(color);
        }

        /** Each line comes in after the one above it, from the right ({@code dir} 1), the left (-1) or below (0), {@code wait} ms from now. */
        void arrive(int dir, long wait) {
            float dx = dir * Ui.dpf(30), dy = dir == 0 ? Ui.dpf(16) : 0f;
            int k = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v == more || v.getVisibility() != VISIBLE) continue;
                v.animate().cancel();
                v.setTranslationX(dx);
                v.setTranslationY(dy);
                v.setAlpha(0f);
                v.animate().translationX(0f).translationY(0f).alpha(1f).setStartDelay(wait + k++ * 42L).setDuration(Ui.light ? 300 : 480)
                        .setInterpolator(Ui.EASE_DECEL).start();
            }
        }
    }

    // ---- a title, open ---------------------------------------------------------------------------

    /**
     * OK on a title: its poster grows out of the row into a page of its own, with its words beside
     * it and what can be done about it under them: its trailer, the app here that streams it (or
     * the TV's own search when none is known to), and your list. Back puts the poster back in the
     * row. The screen behind is dimmed by one flat colour, there at once on LOW.
     */
    private final class Page extends FrameLayout {
        final View dim;
        final Big big;
        final LinearLayout col;
        final Words says;
        final Acts acts;
        final TextView said, credit;
        Poster from;
        boolean open;
        /** A title was saved or taken off while this was open: My list is shown again once it closes. */
        private boolean mineStale;
        private final Runnable hush = () -> speak(0f);

        Page() {
            super(act);
            setVisibility(INVISIBLE);
            setClipChildren(false);
            dim = new View(act);
            dim.setBackgroundColor(0xBD08090C);
            dim.setAlpha(0f);
            addView(dim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            big = new Big(act);
            addView(big, at(BIG_W, BIG_H, LEFT, BIG_TOP));
            col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            says = new Words(38, 5);
            col.addView(says);
            acts = new Acts();
            col.addView(acts, below(20));
            said = Ui.text(act, 12, Ui.medium, SOFT);
            said.setAlpha(0f);
            col.addView(said, below(12));
            credit = Ui.text(act, 9.5f, Ui.regular, 0x57FFFFFF);
            col.addView(credit, below(10));
            addView(col, at(540, ViewGroup.LayoutParams.WRAP_CONTENT, LEFT + BIG_W + 36, BIG_TOP + 4));
        }

        private void speak(float alpha) {
            said.animate().alpha(alpha).setStartDelay(0).setDuration(220).start();
        }

        void tint(int color) {
            says.tint(color);
            big.tint(color);
            acts.invalidate();
        }

        /** Where the big poster is when it's the one in the row: grown and lifted as the focus has it, at the focus's place. */
        private void slot(float[] xyScale) {
            float w = Ui.dpf(W) * GROW, h = Ui.dpf(H) * GROW;
            xyScale[0] = Ui.dpf(LEFT) + Ui.dpf(W) / 2f - w / 2f - Ui.dpf(LEFT);
            xyScale[1] = Ui.dpf(ROW_TOP) + Ui.dpf(H) * PIVOT_Y - h * PIVOT_Y - Ui.dpf(LIFT) * GROW - Ui.dpf(BIG_TOP);
            xyScale[2] = w / Ui.dpf(BIG_W);
        }

        void open(Poster p) {
            if (open || p == null || spinning || !shown) return;
            open = true;
            from = p;
            inFocus = p;
            removeCallbacks(rest);
            removeCallbacks(hush);
            said.animate().cancel();
            said.setAlpha(0f);
            says.set(p.pick, p.app);
            label();
            big.take(p.picture);
            setVisibility(VISIBLE);
            // the big poster starts over the one in the row and grows to its place
            float[] at = new float[3];
            slot(at);
            big.animate().cancel();
            big.setPivotX(0f);
            big.setPivotY(0f);
            big.setTranslationX(at[0]);
            big.setTranslationY(at[1]);
            big.setScaleX(at[2]);
            big.setScaleY(at[2]);
            big.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(Ui.light ? 300 : 620)
                    .setInterpolator(Ui.EASE_DECEL).start();
            p.hide(true);
            dim.animate().cancel();
            if (Ui.light) dim.setAlpha(1f);
            else dim.animate().alpha(1f).setStartDelay(0).setDuration(420).start();
            col.animate().cancel();
            col.setAlpha(0f);
            col.setTranslationX(Ui.dpf(30));
            col.animate().alpha(1f).translationX(0f).setStartDelay(Ui.light ? 60 : 120).setDuration(Ui.light ? 300 : 560).setInterpolator(Ui.EASE_DECEL).start();
            pose(PAGE);
            acts.first().requestFocus();
            ask(p);
            announceForAccessibility(p.pick.name);
        }

        /** Back: the poster goes back to its place in the row, which comes back up under it. */
        void close() {
            if (!open) return;
            open = false;
            removeCallbacks(hush);
            final Poster p = from;
            float[] at = new float[3];
            slot(at);
            big.animate().cancel();
            big.animate().translationX(at[0]).translationY(at[1]).scaleX(at[2]).scaleY(at[2]).setStartDelay(0).setDuration(Ui.light ? 260 : 520)
                    .setInterpolator(Ui.EASE_DECEL).withEndAction(() -> {
                        if (open) return;
                        setVisibility(INVISIBLE);
                        p.hide(false);
                        if (mineStale) {
                            mineStale = false;
                            mineChanged();
                        }
                    }).start();
            dim.animate().cancel();
            if (Ui.light) dim.setAlpha(0f);
            else dim.animate().alpha(0f).setStartDelay(0).setDuration(300).start();
            col.animate().cancel();
            col.animate().alpha(0f).setStartDelay(0).setDuration(160).start();
            pose(BROWSE);
            if (!p.requestFocus()) strip.tab(Math.max(0, current)).requestFocus();
            awake();
        }

        /** Gone at once: the screen itself is going. */
        void shut() {
            if (from != null) from.hide(false);
            if (!open && getVisibility() != VISIBLE) return;
            open = false;
            removeCallbacks(hush);
            big.animate().cancel();
            dim.animate().cancel();
            col.animate().cancel();
            dim.setAlpha(0f);
            setVisibility(INVISIBLE);
            if (mineStale) {
                mineStale = false;
                shelves.remove(Watch.MINE);
                if (current >= 0 && ids.get(current).equals(Watch.MINE)) current = -1;
            }
        }

        /** What its choices say: the app that streams it once that's known, and whether it's on your list. */
        void label() {
            final Picks.Pick p = from.pick;
            acts.set(0, act.getString(R.string.watch_trailer), R.drawable.ic_play, true);
            boolean finds = find(p).resolveActivity(act.getPackageManager()) != null;
            if (from.app != null) acts.set(1, act.getString(R.string.watch_open_app, from.app.label), 0, true);
            else acts.set(1, act.getString(R.string.watch_find_it), R.drawable.ic_search, finds);
            boolean saved = Watch.saved(act, p);
            acts.set(2, act.getString(saved ? R.string.watch_on_my_list : R.string.watch_save), saved ? R.drawable.ic_check : R.drawable.ic_star, true);
            acts.linked();
            String by = act.getString(R.string.watch_credit, p.src);
            credit.setText(from.app == null ? by : by + "  ·  " + act.getString(R.string.watch_credit_where));
        }

        private Intent find(Picks.Pick p) {
            return new Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, p.name);
        }

        /** OK on the choice in focus. */
        void press() {
            int k = acts.focused();
            if (!open || k < 0) return;
            final Picks.Pick p = from.pick;
            View tab = acts.tabs[k];
            if (k == 0) {
                host.open(Picks.watch(act, p), tab);
            } else if (k == 1) {
                Intent app = null;
                if (from.app != null) {
                    android.content.pm.PackageManager pm = act.getPackageManager();
                    app = pm.getLeanbackLaunchIntentForPackage(from.app.pkg);
                    if (app == null) app = pm.getLaunchIntentForPackage(from.app.pkg);
                }
                host.open(app != null ? app : find(p), tab);
            } else {
                save();
            }
        }

        /** Onto your list, a small poster flying up into its tab; or off it again. */
        private void save() {
            final Picks.Pick p = from.pick;
            boolean on = Watch.toggle(act, p);
            mineStale = true;
            shelves.remove(Watch.MINE);
            label();
            said.setText(act.getString(on ? R.string.watch_saved : R.string.watch_taken_off, p.name));
            speak(1f);
            removeCallbacks(hush);
            postDelayed(hush, 2400);
            final int n = Watch.mine(act).size();
            if (!on || from.picture == null) {
                mineCount = n;
                badge();
                return;
            }
            mini.fly(from.picture, big, strip.tab(0), () -> {
                mineCount = n;
                badge();
                View tab = strip.tab(0);
                tab.animate().cancel();
                tab.setScaleX(1.2f);
                tab.setScaleY(1.2f);
                tab.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420).setInterpolator(Ui.EASE_DECEL).start();
            });
        }
    }

    /**
     * What can be done about an open title, as a row of capsules; the white pill under the one in
     * focus stretches toward the next and gathers there, as the genres' does.
     */
    private final class Acts extends LinearLayout {
        final TextView[] tabs = new TextView[3];
        private final int[] icons = new int[3];
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Spring.Mover left, right;
        private boolean leftLeads;

        Acts() {
            super(act);
            setWillNotDraw(false);
            pill.setColor(0xFFFFFFFF);
            left = new Spring.Mover(Spring.pillTrail(), 0.5f, 0f, v -> invalidate());
            right = new Spring.Mover(Spring.pillLead(), 0.5f, 0f, v -> invalidate());
            for (int i = 0; i < tabs.length; i++) {
                final TextView t = Ui.text(act, 13.5f, Ui.semibold, SOFT);
                t.setPadding(Ui.dp(17), Ui.dp(9), Ui.dp(17), Ui.dp(9));
                t.setCompoundDrawablePadding(Ui.dp(7));
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x14FFFFFF);
                g.setStroke(Ui.hair(), 0x29FFFFFF);
                g.setCornerRadius(Ui.dpf(999));
                t.setBackground(g);
                t.setFocusable(true);
                t.setFocusableInTouchMode(true);
                t.setId(View.generateViewId());
                t.setOnFocusChangeListener((v, gained) -> {
                    paint(t);
                    if (gained) pillTo(t, right.value() > left.value());
                    invalidate();
                });
                t.setOnClickListener(v -> {
                    if (t.requestFocus()) page.press();
                });
                addView(t, i == 0 ? gap(0) : gap(8));
                tabs[i] = t;
            }
        }

        void set(int i, String text, int icon, boolean there) {
            TextView t = tabs[i];
            t.setText(text);
            t.setContentDescription(text);
            t.setVisibility(there ? VISIBLE : GONE);
            icons[i] = icon;
            Drawable d = icon == 0 ? null : act.getResources().getDrawable(icon).mutate();
            if (d != null) d.setBounds(0, 0, Ui.dp(14), Ui.dp(14));
            t.setCompoundDrawables(d, null, null, null);
            paint(t);
        }

        /** Dark on the white pill while it has the focus, soft on its own glass otherwise; its picture with it. */
        private void paint(TextView t) {
            int color = t.isFocused() ? INK : SOFT;
            t.setTextColor(color);
            Drawable d = t.getCompoundDrawables()[0];
            if (d != null) d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }

        /** Left and Right go between the choices that are there; nothing leads out of them. */
        void linked() {
            List<TextView> there = new ArrayList<>();
            for (TextView t : tabs) if (t.getVisibility() == VISIBLE) there.add(t);
            for (int i = 0; i < there.size(); i++) {
                TextView t = there.get(i);
                t.setNextFocusLeftId(there.get(Math.max(0, i - 1)).getId());
                t.setNextFocusRightId(there.get(Math.min(there.size() - 1, i + 1)).getId());
                t.setNextFocusUpId(t.getId());
                t.setNextFocusDownId(t.getId());
            }
        }

        TextView first() {
            left.jump(0f);
            right.jump(0f);
            return tabs[0];
        }

        int focused() {
            for (int i = 0; i < tabs.length; i++) if (tabs[i].isFocused()) return i;
            return -1;
        }

        private void pillTo(View tab, boolean glide) {
            if (tab.getWidth() == 0) return;
            if (glide) {
                boolean toLeft = tab.getLeft() < left.target();
                if (toLeft != leftLeads) {
                    leftLeads = toLeft;
                    left.respring(toLeft ? Spring.pillLead() : Spring.pillTrail());
                    right.respring(toLeft ? Spring.pillTrail() : Spring.pillLead());
                }
                left.to(tab.getLeft());
                right.to(tab.getRight());
            } else {
                left.jump(tab.getLeft());
                right.jump(tab.getRight());
            }
        }

        /** A choice's words changed (the app found, the title saved): the pill takes the new size of the one it's under. */
        @Override
        protected void onLayout(boolean changed, int l, int t, int rr, int b) {
            super.onLayout(changed, l, t, rr, b);
            int k = focused();
            if (k >= 0 && !left.moving() && !right.moving()) pillTo(tabs[k], false);
        }

        @Override
        protected void onDraw(Canvas c) {
            int k = focused();
            float l = left.value(), rt = right.value();
            if (k < 0 || rt <= l) return;
            r.set(l, tabs[k].getTop(), rt, tabs[k].getBottom());
            float rad = r.height() / 2f;
            c.drawRoundRect(r, rad, rad, pill);
        }
    }

    /** The poster of an open title, large, in a light of its own colour. */
    private static final class Big extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint light = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), around = new RectF();
        private final Matrix fit = new Matrix();
        private Bitmap picture;

        Big(android.content.Context c) {
            super(c);
            paint.setColor(0xFF1A1C21);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.hair());
            edge.setColor(0x2EFFFFFF);
        }

        void tint(int color) {
            light.setColor(Ui.withAlpha(color, 110));
            invalidate();
        }

        void take(Bitmap b) {
            picture = b;
            place();
            invalidate();
        }

        private void place() {
            if (picture == null || getWidth() == 0) {
                paint.setShader(null);
                return;
            }
            float s = Math.max(getWidth() / (float) picture.getWidth(), getHeight() / (float) picture.getHeight());
            fit.setScale(s, s);
            fit.postTranslate((getWidth() - picture.getWidth() * s) / 2f, (getHeight() - picture.getHeight() * s) / 2f);
            BitmapShader sh = new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setLocalMatrix(fit);
            paint.setShader(sh);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            box.set(0, 0, w, h);
            float out = Ui.dpf(70);
            around.set(-out, -out * 0.5f, w + out, h + out * 1.3f);
            place();
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Ui.dpf(12);
            c.drawBitmap(halo(), null, around, light);
            c.drawRoundRect(box, r, r, paint);
            c.drawRoundRect(box, r, r, edge);
        }
    }

    /** A small poster that flies from an open title up into My list as it's saved. */
    private static final class Mini extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Matrix fit = new Matrix();
        private final int[] a = new int[2], b = new int[2], me = new int[2];
        private ValueAnimator flight;

        Mini(android.content.Context c) {
            super(c);
            setVisibility(INVISIBLE);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dpf(1.5f));
            ring.setColor(0xFFFFFFFF);
        }

        /** From the middle of {@code from} to the middle of {@code to}, rising first and shrinking as it lands; {@code landed} runs as it does. */
        void fly(Bitmap picture, View from, View to, final Runnable landed) {
            if (flight != null) flight.end();
            float s = Math.max(getWidth() / (float) picture.getWidth(), getHeight() / (float) picture.getHeight());
            fit.setScale(s, s);
            fit.postTranslate((getWidth() - picture.getWidth() * s) / 2f, (getHeight() - picture.getHeight() * s) / 2f);
            BitmapShader sh = new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setLocalMatrix(fit);
            paint.setShader(sh);
            ((View) getParent()).getLocationInWindow(me);
            from.getLocationInWindow(a);
            to.getLocationInWindow(b);
            final float x0 = a[0] - me[0] + from.getWidth() * from.getScaleX() / 2f - getWidth() / 2f;
            final float y0 = a[1] - me[1] + from.getHeight() * from.getScaleY() / 2f - getHeight() / 2f;
            final float x1 = b[0] - me[0] + to.getWidth() / 2f - getWidth() / 2f, y1 = b[1] - me[1] + to.getHeight() / 2f - getHeight() / 2f;
            // it rises before it heads for the tab: a curve, by way of a point above where it started
            final float cx = x0 + (x1 - x0) * 0.2f, cy = Math.min(y0, y1) - Ui.dpf(40);
            setVisibility(VISIBLE);
            flight = ValueAnimator.ofFloat(0f, 1f);
            flight.setDuration(760);
            flight.setInterpolator(Ui.EASE_STANDARD);
            flight.addUpdateListener(v -> {
                float t = (Float) v.getAnimatedValue(), u = 1f - t;
                setTranslationX(u * u * x0 + 2f * u * t * cx + t * t * x1);
                setTranslationY(u * u * y0 + 2f * u * t * cy + t * t * y1);
                float size = 2.4f + (0.3f - 2.4f) * t;
                setScaleX(size);
                setScaleY(size);
                setAlpha(t < 0.12f ? t / 0.12f : t > 0.85f ? (1f - t) / 0.15f : 1f);
            });
            flight.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    flight = null;
                    setVisibility(INVISIBLE);
                    landed.run();
                }
            });
            flight.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            box.set(0, 0, w, h);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Ui.dpf(5);
            c.drawRoundRect(box, r, r, paint);
            c.drawRoundRect(box, r, r, ring);
        }
    }

    // ---- a poster in the row ---------------------------------------------------------------------

    /**
     * A film or series: its poster with rounded corners, faint while it waits and fainter once
     * passed; in focus it grows, lifts, is ringed in white and (on HIGH) a single pass of light
     * crosses it.
     */
    private final class Poster extends View {
        final Picks.Pick pick;
        final int index;
        Bitmap picture;
        /** Its title's colour, known once its picture is read. */
        volatile int aura = Watch.PALE;
        /** The app here that streams it, once asked and found. */
        Watch.App app;
        boolean asked;
        /** How faint it's heading to be. */
        float want = AHEAD;
        /** Its picture is the open title's big one for now: nothing is drawn here. */
        private boolean hidden;
        /** Where the pass of light is across it, 0 to 1; -1 when there's none. */
        private float lit = -1f;
        private ValueAnimator pass;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Matrix fit = new Matrix(), across = new Matrix();
        private Shader shine;

        Poster(Picks.Pick p, int index) {
            super(act);
            pick = p;
            this.index = index;
            setFocusable(true);
            // a mouse or an air remote puts Android in touch mode, where a plain focusable view is passed over
            setFocusableInTouchMode(true);
            setContentDescription(p.name);
            setAlpha(AHEAD);
            paint.setColor(0x33FFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dpf(2.4f));
            ring.setColor(0xFFFFFFFF);
        }

        /** Its picture has been read: covering the poster, cut to it at its middle; {@code its} is its colour. */
        void take(Bitmap b, int its) {
            picture = b;
            aura = its;
            place();
            invalidate();
        }

        void hide(boolean on) {
            hidden = on;
            invalidate();
        }

        private void place() {
            if (picture == null || getWidth() == 0) return;
            float s = Math.max(getWidth() / (float) picture.getWidth(), getHeight() / (float) picture.getHeight());
            fit.setScale(s, s);
            fit.postTranslate((getWidth() - picture.getWidth() * s) / 2f, (getHeight() - picture.getHeight() * s) / 2f);
            BitmapShader sh = new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setLocalMatrix(fit);
            paint.setShader(sh);
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            box.set(0, 0, w, h);
            setPivotX(w / 2f);
            setPivotY(h * PIVOT_Y);
            shine = new LinearGradient(w * 0.3f, 0, w * 0.7f, h * 0.1f, new int[]{0x00FFFFFF, 0x5CFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            sheen.setShader(shine);
            place();
        }

        /** Up to its size in focus, or back down. */
        void lift(boolean up) {
            animate().scaleX(up ? GROW : 1f).scaleY(up ? GROW : 1f).translationY(up ? -Ui.dpf(LIFT) : 0f).setStartDelay(0)
                    .setDuration(up ? Ui.light ? Ui.LOW_FOCUS_IN : 240 : 140).setInterpolator(Ui.EASE_DECEL).start();
            if (pass != null) pass.cancel();
            if (!up || Ui.light) return;
            pass = ValueAnimator.ofFloat(0f, 1f);
            pass.setStartDelay(120);
            pass.setDuration(820);
            pass.addUpdateListener(a -> {
                lit = (Float) a.getAnimatedValue();
                invalidate();
            });
            pass.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    lit = -1f;
                    invalidate();
                }
            });
            pass.start();
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            lift(gained && !spinning);
            invalidate();
            light();
            if (!gained) return;
            inFocus = this;
            fade(index);
            counted(index);
            lightIn(aura);
            say(pick, direction == FOCUS_RIGHT ? 1 : direction == FOCUS_LEFT ? -1 : 0, 0);
            glideTo(index);
            awake();
        }

        /** Its growing, its fade and its ring go straight onto its one picture: no offscreen pass. */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (hidden) return;
            float r = Ui.dpf(9);
            c.drawRoundRect(box, r, r, paint);
            if (lit >= 0f) {
                across.setTranslate((lit * 2.4f - 1.2f) * getWidth(), 0f);
                shine.setLocalMatrix(across);
                c.drawRoundRect(box, r, r, sheen);
            }
            if (isFocused() && !spinning) {
                float i = ring.getStrokeWidth() / 2f;
                c.drawRoundRect(i, i, getWidth() - i, getHeight() - i, r, r, ring);
            }
        }
    }
}
