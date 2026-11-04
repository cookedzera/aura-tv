package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top Shelf, as on Apple TV: rest on an app in the top row and the row of widgets above becomes
 * that app's own content: what it shows on its Android TV home-screen rows, what you're partway
 * through in it, or what's new in it today. Up moves into the shelf, OK opens straight to it.
 */
final class TopShelf extends FrameLayout {
    private static final int MAX = 3;
    /** Per app: what was found and when, so resting on an app again costs nothing for a while. */
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    final List<Card> cards = new ArrayList<>();
    private final LinearLayout row;
    private String shown;

    TopShelf(Context c) {
        super(c);
        setVisibility(GONE);
        setClipChildren(false);
        row = new LinearLayout(c);
        row.setClipChildren(false);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    String shownFor() {
        return isShown() ? shown : null;
    }

    /** The app whose cards are up, even while home itself is out of sight behind an app. */
    String upFor() {
        return up() ? shown : null;
    }

    void clearShown() {
        shown = null;
    }

    /** Cards for {@code pkg} were found a moment ago: they can show almost at once. */
    static boolean cached(String pkg) {
        synchronized (CACHE) {
            Object[] hit = CACHE.get(pkg);
            return hit != null && SystemClock.elapsedRealtime() - (Long) hit[0] < 120_000;
        }
    }

    /** On screen (or fading), whatever app it's for. */
    boolean up() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Focus moved to another app: these cards are no longer its, so they fade at once rather than
     * stand over the new app (in its colour) until the new app's cards arrive.
     */
    void fade() {
        if (getVisibility() != VISIBLE) return;
        shown = null;
        animate().alpha(0f).translationY(0f).setDuration(140).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(null).start();
    }

    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /** Off the main thread: up to three things to show for {@code pkg}, cached for two minutes, plus any live/recent song or video. */
    @SuppressWarnings("unchecked")
    static List<Continue.Item> items(Context c, String pkg) {
        long now = SystemClock.elapsedRealtime();
        List<Continue.Item> base = null;
        synchronized (CACHE) {
            Object[] hit = CACHE.get(pkg);
            if (hit != null && now - (Long) hit[0] < 120_000) base = (List<Continue.Item>) hit[1];
        }
        boolean ytClient = "io.gh.reisxd.tizentube.cobalt".equals(pkg)
                || "com.liskovsoft.smarttubetv".equals(pkg)
                || "com.liskovsoft.smarttubetv.beta".equals(pkg);
        if (base == null) {
            base = new ArrayList<>();
            Taste.View taste = Taste.view(c);
            // what you're partway through in this app comes first, then what the app itself features
            for (Continue.Item it : Continue.all(c, 8)) {
                if (taste.declined(Continue.key(it.title))) continue;
                boolean match = pkg.equals(it.pkg) || (ytClient && "com.google.android.youtube.tv".equals(it.pkg));
                if (match && it.art != null && base.size() < MAX) {
                    if (ytClient && !pkg.equals(it.pkg) && it.intent != null) {
                        it.intent = new Intent(it.intent);
                        it.intent.setComponent(null);
                        it.intent.setPackage(pkg);
                        it.pkg = pkg;
                    }
                    if (it.progress > 0f) it.kicker = "▶  " + Words.get(R.string.continue_watching_caps);
                    base.add(it);
                }
            }
            for (Continue.Item it : Continue.previews(c, pkg, MAX * 2)) {
                if (taste.declined(Continue.key(it.title))) continue;
                if (base.size() < MAX && !containsTitle(base, it.title)) {
                    if (it.progress > 0f) it.kicker = "▶  " + Words.get(R.string.continue_watching_caps);
                    else if ("com.google.android.youtube.tvmusic".equals(pkg)) it.kicker = "♪  YOUTUBE MUSIC";
                    base.add(it);
                }
            }
            // an app with no rows of its own for Android (SonyLIV, and others like it): what you were
            // watching in it, as its own player told Android while it played
            if (base.size() < MAX) {
                java.util.Set<String> have = new java.util.HashSet<>();
                for (Continue.Item it : base) have.add(Continue.key(it.title));
                for (Continue.Item it : Watched.recent(c, have, MAX - base.size(), pkg)) {
                    if (it.art == null || taste.declined(Continue.key(it.title))) continue;
                    it.kicker = "▶  " + Words.get(R.string.continue_watching_caps);
                    base.add(it);
                }
            }
            if (base.size() < MAX) {
                for (Fresh.Show s : Fresh.load(c)) {
                    if (!pkg.equals(s.pkg) || base.size() >= MAX || taste.declined(Continue.key(s.name))) continue;
                    Continue.Item it = new Continue.Item();
                    it.title = s.name;
                    it.sub = s.episode.isEmpty() ? Words.get(R.string.new_today) : Words.get(R.string.new_today_value, s.episode);
                    it.kicker = Words.get(R.string.new_episode);
                    it.art = Fresh.art(s);
                    it.pkg = pkg;
                    if (it.art != null) base.add(it);
                }
            }
            synchronized (CACHE) {
                CACHE.put(pkg, new Object[]{now, base});
            }
        }
        boolean hasWidgets = (!Prefs.tvos(c) || Prefs.dockWidgets(c)) && Prefs.hiddenWidgets(c).size() < 3;
        boolean tvosFallback = Prefs.tvos(c) && !hasWidgets && base.isEmpty();
        MediaWatch.Playing mp = MediaWatch.forApp(c, pkg);
        if (mp == null && tvosFallback) mp = MediaWatch.nowOrLast(c);
        if (mp != null) {
            List<Continue.Item> out = new ArrayList<>();
            Continue.Item live = new Continue.Item();
            // the label already says playing or paused: under the song, only who it's by
            live.title = Ui.songTitle(mp.title);
            live.sub = mp.by != null && !mp.by.isEmpty() ? mp.by : Words.get(mp.playing ? R.string.playing_now : R.string.paused_2);
            live.kicker = mp.playing ? "●  " + Words.get(R.string.now_playing_caps) : "▶  " + Words.get(R.string.continue_playing);
            live.pkg = pkg;
            live.art = MediaWatch.resolveArt(c, mp);
            live.progress = mp.progress;
            live.nowPlaying = true;
            live.intent = launchForMedia(c, pkg, mp);
            out.add(live);
            for (Continue.Item it : base) {
                if (out.size() >= MAX) break;
                if (!mp.title.equalsIgnoreCase(it.title)) out.add(it);
            }
            if (tvosFallback && out.size() < MAX) fillTvosUpNext(c, out);
            return out;
        }
        if ("com.google.android.youtube.tvmusic".equals(pkg) && base.isEmpty()) {
            List<Continue.Item> out = new ArrayList<>();
            Continue.Item ym = new Continue.Item();
            ym.title = "YouTube Music";
            ym.sub = Words.get(R.string.your_mixes_liked_songs_playlists);
            ym.kicker = "♪  " + Words.get(R.string.play_now);
            ym.pkg = pkg;
            ym.nowPlaying = true;
            ym.intent = c.getPackageManager().getLaunchIntentForPackage(pkg);
            out.add(ym);
            if (tvosFallback) fillTvosUpNext(c, out);
            return out;
        }
        if (tvosFallback) {
            List<Continue.Item> out = new ArrayList<>();
            fillTvosUpNext(c, out);
            return out;
        }
        return base;
    }

    private static boolean containsTitle(List<Continue.Item> list, String title) {
        if (title == null) return false;
        String k = Continue.key(title);
        for (Continue.Item it : list) if (k.equals(Continue.key(it.title))) return true;
        return false;
    }

    private static Intent launchForMedia(Context c, String shelfPkg, MediaWatch.Playing mp) {
        if ("com.google.android.youtube.tvmusic".equals(shelfPkg) && mp != null && mp.pkg != null
                && !mp.pkg.equals(shelfPkg) && !"com.google.android.youtube.tv".equals(mp.pkg)) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/topic/music?launch=menu"));
            i.setPackage(mp.pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(c.getPackageManager()) != null) return i;
        }
        String target = mp != null && mp.pkg != null && !"com.google.android.youtube.tvmusic".equals(shelfPkg)
                ? mp.pkg : shelfPkg;
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(target);
        if (launch == null && mp != null && mp.pkg != null) {
            launch = c.getPackageManager().getLaunchIntentForPackage(mp.pkg);
        }
        if (launch != null) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launch;
    }

    /** In tvOS mode, dock apps without their own TV channel still show Up Next (Continue Watching) and Tonight's Pick. */
    private static void fillTvosUpNext(Context c, List<Continue.Item> out) {
        Taste.View taste = Taste.view(c);
        for (Continue.Item it : Continue.all(c, MAX)) {
            if (out.size() >= MAX) return;
            // a kids' profile isn't offered what it hides, as the Smart Stack already does
            if (it.pkg != null && !HomeKeys.allowed(c, it.pkg) || taste.declined(Continue.key(it.title))) continue;
            if (it.art != null && !containsTitle(out, it.title)) {
                it.kicker = "▶  " + Words.get(R.string.continue_watching_caps);
                out.add(it);
            }
        }
        if (out.size() < MAX && Prefs.on(c, Prefs.PICKS)) {
            List<Picks.Pick> picks = Picks.load(c);
            if (!picks.isEmpty()) {
                int day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
                for (int i = 0; i < picks.size() && out.size() < MAX; i++) {
                    Picks.Pick p = picks.get((day + i) % picks.size());
                    if (taste.skip(Continue.key(p.name))) continue;
                    Bitmap b = Picks.poster(p);
                    if (b == null) continue;
                    Continue.Item it = new Continue.Item();
                    it.title = p.name;
                    it.sub = p.line;
                    it.desc = p.about;
                    it.kicker = "★  " + Words.get(R.string.tonights_pick_caps);
                    it.art = b;
                    it.intent = Picks.watch(c, p);
                    it.posterCard = true;
                    out.add(it);
                }
            }
        }
    }

    /** Cards whose pictures were painted off the main thread, at the sizes they take in the row. */
    static final class Ready {
        final List<Continue.Item> items;
        final Bitmap[] art;
        final int[] widths;
        final int color;
        final String sig;

        private Ready(List<Continue.Item> items, Bitmap[] art, int[] widths, int color, String sig) {
            this.items = items;
            this.art = art;
            this.widths = widths;
            this.color = color;
            this.sig = sig;
        }
    }

    /** What the cards on show were painted from: the same again keeps them. */
    private volatile String sig;

    /**
     * Off the main thread: every card's picture, for this shelf's row {@code h} pixels high.
     * Painting them as the cards were laid out held the main thread up just as you came back home,
     * and again on each app passed over on the dock. The cards already on show, found again, aren't
     * painted at all.
     */
    Ready paintAll(List<Continue.Item> items, int color, int h) {
        int[] widths = widths(getLayoutParams().width, items.size());
        Bitmap[] art = new Bitmap[items.size()];
        StringBuilder b = new StringBuilder().append(color);
        for (Continue.Item it : items) {
            b.append('\n').append(it.title).append('|').append(it.sub).append('|').append(it.kicker)
                    .append('|').append(Math.round(it.progress * 200)).append('|').append(look(it.art))
                    .append('|').append(it.posterCard).append('|').append(it.nowPlaying);
        }
        String made = b.toString();
        if (made.equals(sig)) return new Ready(items, art, widths, color, made);
        for (int i = 0; i < art.length; i++) {
            if (widths[i] <= 0 || h <= 0) continue;
            try {
                art[i] = Bitmap.createBitmap(widths[i], h, Bitmap.Config.ARGB_8888);
                paint(items.get(i), color).paint(new Canvas(art[i]), widths[i], h);
            } catch (OutOfMemoryError e) {
                // the card paints itself as it's laid out, as before
                art[i] = null;
            }
        }
        return new Ready(items, art, widths, color, made);
    }

    /**
     * A picture told apart by its size and a few of its pixels: read from disk again for the next
     * lookup, it's still the same picture, and its card can stay.
     */
    private static String look(Bitmap b) {
        if (b == null) return "-";
        StringBuilder s = new StringBuilder().append(b.getWidth()).append('x').append(b.getHeight());
        try {
            for (int y = 1; y < 4; y++) {
                for (int x = 1; x < 4; x++) s.append(',').append(Integer.toHexString(b.getPixel(b.getWidth() * x / 4, b.getHeight() * y / 4)));
            }
        } catch (RuntimeException e) {
            // pixels that can't be read: the picture itself is what's compared
            s.append('@').append(System.identityHashCode(b));
        }
        return s.toString();
    }

    /** Each card's width across a row {@code w} pixels wide, 18 dp apart. */
    private static int[] widths(int w, int n) {
        int[] out = new int[n];
        int room = w - Ui.dp(18) * Math.max(0, n - 1);
        for (int i = 0; i < n; i++) out[i] = room * (i + 1) / n - room * i / n;
        return out;
    }

    /** Lays out up to three cards across the row; {@code open} runs when one is chosen. */
    void show(String pkg, Ready r, Opener open) {
        show(pkg, r, open, null);
    }

    /** Lays out up to three cards across the row; {@code open} runs on OK and {@code hold} on hold-OK. */
    void show(String pkg, Ready r, Opener open, Hold hold) {
        boolean same = up() && r.sig.equals(sig) && cards.size() == r.items.size();
        shown = pkg;
        if (same) {
            // the very cards on show (back from an app, or a song's card looked up again unchanged):
            // kept, only told where they now lead
            for (int i = 0; i < cards.size(); i++) {
                final Continue.Item it = r.items.get(i);
                final Card card = cards.get(i);
                card.onClick = () -> open.open(it);
                card.onLongClick = hold != null ? () -> hold.hold(it, card) : null;
            }
            if (getAlpha() == 1f && getTranslationY() == 0f) return;
        } else {
            sig = r.sig;
            row.removeAllViews();
            cards.clear();
            int n = r.items.size();
            boolean tvos = Prefs.tvos(getContext());
            for (int i = 0; i < n; i++) {
                final Continue.Item it = r.items.get(i);
                final Card card = new Card(getContext(), paint(it, r.color), r.color, 22);
                card.ready = r.art[i];
                card.tvos = tvos;
                card.onClick = () -> open.open(it);
                card.onLongClick = hold != null ? () -> hold.hold(it, card) : null;
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(r.widths[i], ViewGroup.LayoutParams.MATCH_PARENT);
                if (i > 0) l.leftMargin = Ui.dp(18);
                row.addView(card, l);
                cards.add(card);
            }
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setTranslationY(Ui.dp(10));
        }
        animate().alpha(1f).translationY(0f).setDuration(420).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).withEndAction(null).start();
    }

    void hide() {
        shown = null;
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        animate().alpha(0f).translationY(Ui.dp(6)).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    row.removeAllViews();
                    cards.clear();
                    sig = null;
                }).start();
    }

    interface Opener {
        void open(Continue.Item it);
    }

    interface Hold {
        void hold(Continue.Item it, Card card);
    }

    /** A card's picture: wide show/film art or a tvOS Music/Play Now card with album cover and progress. */
    static Tiles.Painter paint(Continue.Item it, int color) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            c.drawColor(0xFF101216);
            Bitmap b = it.art;
            boolean musicCard = it.posterCard || it.nowPlaying;
            if (musicCard) {
                if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                    // subtle atmospheric wash from the artwork itself behind the dark glass
                    float bgScale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight()) * 1.15f;
                    Matrix bm = new Matrix();
                    bm.setScale(bgScale, bgScale);
                    bm.postTranslate((w - b.getWidth() * bgScale) * 0.5f, (h - b.getHeight() * bgScale) * 0.5f);
                    p.setAlpha(72);
                    c.drawBitmap(b, bm, p);
                    p.setAlpha(255);
                }
                p.setShader(new LinearGradient(0, 0, w, h,
                        Ui.withAlpha(Ui.mix(0xFF161922, color, 0.36f), b != null ? 0xD8 : 0xFF),
                        0xF20A0C10, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new android.graphics.RadialGradient(w * 0.20f, h * 0.5f, w * 0.72f,
                        Ui.withAlpha(color, 0x58), 0x00000000, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                // a song's square cover a little smaller than a poster is tall, leaving the words room
                float ah = h * (it.posterCard ? 0.72f : 0.62f), aw = it.posterCard ? ah * 0.68f : ah, ax = w * 0.052f, ay = (h - ah) / 2f, rad = aw * 0.18f;
                RectF cover = new RectF(ax, ay, ax + aw, ay + ah);
                if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                    android.graphics.BitmapShader bs = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    float s = Math.max(aw / b.getWidth(), ah / b.getHeight());
                    Matrix m = new Matrix();
                    m.setScale(s, s);
                    m.postTranslate(ax + (aw - b.getWidth() * s) * 0.5f, ay + (ah - b.getHeight() * s) * 0.5f);
                    bs.setLocalMatrix(m);
                    p.setShader(bs);
                    c.drawRoundRect(cover, rad, rad, p);
                    p.setShader(null);
                } else {
                    p.setShader(new LinearGradient(ax, ay, ax + aw, ay + ah,
                            Ui.mix(0xFF232834, color, 0.48f), Ui.mix(0xFF12151C, color, 0.22f), Shader.TileMode.CLAMP));
                    c.drawRoundRect(cover, rad, rad, p);
                    p.setShader(null);
                    // subtle play triangle inside the album tile when no artwork bitmap is present
                    Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
                    icon.setColor(0xCCFFFFFF);
                    icon.setStyle(Paint.Style.FILL);
                    float cx = ax + aw * 0.5f, cy = ay + ah * 0.5f, r = aw * 0.16f;
                    Path tri = new Path();
                    tri.moveTo(cx - r * 0.45f, cy - r * 0.65f);
                    tri.lineTo(cx + r * 0.75f, cy);
                    tri.lineTo(cx - r * 0.45f, cy + r * 0.65f);
                    tri.close();
                    c.drawPath(tri, icon);
                }
                // hairline glass rim around the album cover
                Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(Math.max(1f, Ui.dpf(1)));
                rim.setColor(0x2EFFFFFF);
                c.drawRoundRect(cover, rad, rad, rim);

                float tx = ax + aw + w * 0.045f, room = w - tx - w * 0.05f;
                // the words as a block, centred beside the cover: the label, the name (on two lines,
                // a little smaller, before anything is cut short), the line under it, how far in
                android.text.TextPaint k = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                k.setTypeface(Ui.bold);
                k.setTextSize(h * 0.066f);
                k.setLetterSpacing(0.14f);
                k.setColor(Ui.mix(0xE6FFFFFF, color, 0.32f));
                android.text.TextPaint t = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                t.setTypeface(Ui.heavy);
                t.setColor(0xFFFFFFFF);
                String[] name = lines(it.title, t, room, h * 0.13f, h * 0.112f, h * 0.088f);
                android.text.TextPaint u = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                u.setTypeface(Ui.medium);
                u.setColor(0xCCFFFFFF);
                u.setTextSize(h * 0.084f);
                boolean hasSub = it.sub != null && !it.sub.isEmpty();
                // the line under the name: two lines of its own when there's the height (the name on
                // one line, or no progress bar below)
                String[] subLines = !hasSub ? new String[0] : name.length == 1 || it.progress <= 0f
                        ? lines(it.sub, u, room, h * 0.084f, h * 0.074f, h * 0.066f) : null;
                if (hasSub && subLines == null) {
                    shrink(u, it.sub, room, h * 0.064f);
                    subLines = new String[]{TextUtils.ellipsize(it.sub, u, room, TextUtils.TruncateAt.END).toString()};
                }
                if (it.kicker != null) shrink(k, it.kicker, room, h * 0.054f);
                float bh = Math.max(Ui.dpf(3.5f), h * 0.030f);
                float kh = it.kicker != null ? h * 0.066f + h * 0.05f : 0, lh = t.getTextSize() * 1.12f;
                float block = kh + lh * name.length + (hasSub ? h * 0.03f + u.getTextSize() * 1.1f * subLines.length : 0) + (it.progress > 0f ? h * 0.07f + bh : 0);
                float y = ay + (ah - block) / 2f;
                if (it.kicker != null) {
                    y += h * 0.066f;
                    c.drawText(TextUtils.ellipsize(it.kicker, k, room, TextUtils.TruncateAt.END).toString(), tx, y, k);
                    y += h * 0.05f;
                }
                for (String line : name) {
                    y += lh;
                    c.drawText(line, tx, y - lh * 0.2f, t);
                }
                if (hasSub) {
                    y += h * 0.03f;
                    for (String line : subLines) {
                        y += u.getTextSize() * 1.1f;
                        c.drawText(line, tx, y - u.getTextSize() * 0.1f, u);
                    }
                }
                if (it.progress > 0f) {
                    float py = y + h * 0.07f;
                    p.setColor(0x33FFFFFF);
                    c.drawRoundRect(new RectF(tx, py, tx + room, py + bh), bh / 2f, bh / 2f, p);
                    p.setColor(0xF5FFFFFF);
                    float fillW = Math.max(bh, room * Math.min(1f, it.progress));
                    c.drawRoundRect(new RectF(tx, py, tx + fillW, py + bh), bh / 2f, bh / 2f, p);
                }
                return;
            }
            if (b != null) {
                float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
                Matrix m = new Matrix();
                m.setScale(s, s);
                m.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
                c.drawBitmap(b, m, p);
            }
            p.setShader(new LinearGradient(0, h * 0.28f, 0, h, 0x00000000, 0xE0000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            // laid out from the bottom up: how far in, the line under the name, the name (two lines
            // if it needs them, a little smaller, before anything is cut short), the label above
            float x = w * 0.07f, room = w * 0.86f;
            String kicker = it.kicker != null ? it.kicker : it.progress > 0f ? "▶  " + Words.get(R.string.continue_watching_caps) : null;
            boolean hasSub = it.sub != null && !it.sub.isEmpty();
            android.text.TextPaint t = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.heavy);
            t.setColor(0xFFFFFFFF);
            String[] name = lines(it.title, t, room, h * 0.135f, h * 0.115f, h * 0.092f);
            android.text.TextPaint u = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            u.setTypeface(Ui.medium);
            u.setColor(0xCCFFFFFF);
            u.setTextSize(h * 0.085f);
            if (hasSub) shrink(u, it.sub, room, h * 0.066f);
            float y = h * (it.progress > 0f ? 0.925f : 0.95f);
            if (it.progress > 0f) {
                float bh = Math.max(Ui.dpf(3), h * 0.026f);
                p.setColor(0x40FFFFFF);
                c.drawRoundRect(new RectF(x, y, x + room, y + bh), bh / 2f, bh / 2f, p);
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(new RectF(x, y, x + room * Math.min(1f, it.progress), y + bh), bh / 2f, bh / 2f, p);
                y -= h * 0.05f;
            }
            if (hasSub) {
                c.drawText(TextUtils.ellipsize(it.sub, u, room, TextUtils.TruncateAt.END).toString(), x, y, u);
                y -= u.getTextSize() + h * 0.04f;
            }
            float lh = t.getTextSize() * 1.1f;
            for (int i = name.length - 1; i >= 0; i--) {
                c.drawText(name[i], x, y, t);
                y -= lh;
            }
            if (kicker != null) {
                android.text.TextPaint k = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                k.setTypeface(Ui.bold);
                k.setTextSize(h * 0.068f);
                k.setLetterSpacing(0.14f);
                k.setColor(0xCCFFFFFF);
                c.drawText(TextUtils.ellipsize(kicker, k, room, TextUtils.TruncateAt.END).toString(), x, y + lh - t.getTextSize() - h * 0.035f, k);
            }
        };
    }

    /**
     * {@code text} in {@code t} within {@code room}: one line from {@code big} down to {@code one};
     * failing that two lines, from {@code one} down to {@code small}, broken between words; only
     * what still doesn't fit is shortened at its end. Leaves {@code t} at the size chosen.
     */
    static String[] lines(String text, android.text.TextPaint t, float room, float big, float one, float small) {
        String s = text == null ? "" : text.trim();
        for (float size = big; size >= one; size -= big * 0.04f) {
            t.setTextSize(size);
            if (t.measureText(s) <= room) return new String[]{s};
        }
        for (float size = one; ; size -= one * 0.04f) {
            if (size < small) size = small;
            t.setTextSize(size);
            int n = t.breakText(s, true, room, null);
            int cut = n;
            if (n < s.length()) {
                int space = s.lastIndexOf(' ', n);
                if (space > 0) cut = space;
            }
            String first = s.substring(0, cut).trim(), rest = s.substring(cut).trim();
            if (t.measureText(rest) <= room || size <= small) {
                return new String[]{first, TextUtils.ellipsize(rest, t, room, TextUtils.TruncateAt.END).toString()};
            }
        }
    }

    /** One line: smaller, down to {@code min}, until {@code text} fits. */
    static void shrink(android.text.TextPaint t, String text, float room, float min) {
        while (t.getTextSize() > min && t.measureText(text) > room) t.setTextSize(Math.max(min, t.getTextSize() * 0.95f));
    }

    static Intent launchFor(Continue.Item it, AppRepo.App app) {
        return it.intent != null ? it.intent : app != null ? app.launch : null;
    }
}
