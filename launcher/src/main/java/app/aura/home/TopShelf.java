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

    void clearShown() {
        shown = null;
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
            // what you're partway through in this app comes first, then what the app itself features
            for (Continue.Item it : Continue.all(c, 8)) {
                boolean match = pkg.equals(it.pkg) || (ytClient && "com.google.android.youtube.tv".equals(it.pkg));
                if (match && it.art != null && base.size() < MAX) {
                    if (ytClient && !pkg.equals(it.pkg) && it.intent != null) {
                        it.intent = new Intent(it.intent);
                        it.intent.setComponent(null);
                        it.intent.setPackage(pkg);
                        it.pkg = pkg;
                    }
                    if (it.progress > 0f) it.kicker = "▶  CONTINUE WATCHING";
                    base.add(it);
                }
            }
            for (Continue.Item it : Continue.previews(c, pkg, MAX * 2)) {
                if (base.size() < MAX && !containsTitle(base, it.title)) {
                    if (it.progress > 0f) it.kicker = "▶  CONTINUE WATCHING";
                    else if ("com.google.android.youtube.tvmusic".equals(pkg)) it.kicker = "♪  YOUTUBE MUSIC";
                    base.add(it);
                }
            }
            if (base.size() < MAX) {
                for (Fresh.Show s : Fresh.load(c)) {
                    if (!pkg.equals(s.pkg) || base.size() >= MAX) continue;
                    Continue.Item it = new Continue.Item();
                    it.title = s.name;
                    it.sub = s.episode.isEmpty() ? "New today" : "New today  ·  " + s.episode;
                    it.kicker = "NEW EPISODE";
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
            live.title = mp.title;
            live.sub = (mp.playing ? "Playing now" : "Continue playing")
                    + (mp.by != null && !mp.by.isEmpty() ? "  ·  " + mp.by : "");
            live.kicker = mp.playing ? "●  NOW PLAYING" : "▶  CONTINUE PLAYING";
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
            ym.sub = "Your mixes, liked songs & playlists";
            ym.kicker = "♪  PLAY NOW";
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
        for (Continue.Item it : Continue.all(c, MAX)) {
            if (out.size() >= MAX) return;
            if (it.art != null && !containsTitle(out, it.title)) {
                it.kicker = "▶  CONTINUE WATCHING";
                out.add(it);
            }
        }
        if (out.size() < MAX && Prefs.on(c, Prefs.PICKS)) {
            List<Picks.Pick> picks = Picks.load(c);
            if (!picks.isEmpty()) {
                int day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
                for (int i = 0; i < picks.size() && out.size() < MAX; i++) {
                    Picks.Pick p = picks.get((day + i) % picks.size());
                    Bitmap b = Picks.poster(p);
                    if (b == null) continue;
                    Continue.Item it = new Continue.Item();
                    it.title = p.name;
                    it.sub = p.line;
                    it.kicker = "★  TONIGHT'S PICK";
                    it.art = b;
                    it.intent = Picks.watch(c, p);
                    it.posterCard = true;
                    out.add(it);
                }
            }
        }
    }

    /** Lays out up to three cards across the row; {@code open} runs when one is chosen. */
    void show(String pkg, List<Continue.Item> items, int color, Opener open) {
        shown = pkg;
        row.removeAllViews();
        cards.clear();
        int n = items.size();
        for (int i = 0; i < n; i++) {
            final Continue.Item it = items.get(i);
            Card card = new Card(getContext(), paint(it, color), color, 22);
            card.tvos = Prefs.tvos(getContext());
            card.onClick = () -> open.open(it);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) l.leftMargin = Ui.dp(18);
            row.addView(card, l);
            cards.add(card);
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
        animate().alpha(0f).translationY(Ui.dp(6)).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    row.removeAllViews();
                    cards.clear();
                }).start();
    }

    interface Opener {
        void open(Continue.Item it);
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
                float ah = h * 0.72f, aw = it.posterCard ? ah * 0.68f : ah, ax = w * 0.052f, ay = (h - ah) / 2f, rad = aw * 0.18f;
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
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (it.kicker != null) {
                    t.setTypeface(Ui.bold);
                    t.setTextSize(h * 0.070f);
                    t.setLetterSpacing(0.14f);
                    t.setColor(Ui.mix(0xE6FFFFFF, color, 0.32f));
                    c.drawText(it.kicker, tx, h * 0.35f, t);
                }
                t.setLetterSpacing(0f);
                t.setTypeface(Ui.heavy);
                t.setColor(0xFFFFFFFF);
                t.setTextSize(h * 0.132f);
                c.drawText(TextUtils.ellipsize(it.title != null ? it.title : "", new android.text.TextPaint(t), room, TextUtils.TruncateAt.END).toString(), tx, h * 0.55f, t);
                if (it.sub != null && !it.sub.isEmpty()) {
                    t.setTypeface(Ui.medium);
                    t.setTextSize(h * 0.086f);
                    t.setColor(0xCCFFFFFF);
                    c.drawText(TextUtils.ellipsize(it.sub, new android.text.TextPaint(t), room, TextUtils.TruncateAt.END).toString(), tx, h * 0.69f, t);
                }
                if (it.progress > 0f) {
                    float py = h * 0.81f, bh = Math.max(Ui.dpf(3.5f), h * 0.030f);
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
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            float x = w * 0.07f, room = w * 0.86f;
            String kicker = it.kicker != null ? it.kicker : it.progress > 0f ? "▶  CONTINUE WATCHING" : null;
            boolean hasSub = it.sub != null && !it.sub.isEmpty();
            float base = hasSub ? (it.progress > 0f ? h * 0.75f : h * 0.80f) : (it.progress > 0f ? h * 0.83f : h * 0.88f);
            if (kicker != null) {
                t.setTypeface(Ui.bold);
                t.setTextSize(h * 0.068f);
                t.setLetterSpacing(0.14f);
                t.setColor(0xCCFFFFFF);
                c.drawText(kicker, x, base - h * 0.16f, t);
            }
            t.setLetterSpacing(0f);
            t.setTypeface(Ui.heavy);
            t.setColor(0xFFFFFFFF);
            t.setTextSize(h * 0.135f);
            c.drawText(TextUtils.ellipsize(it.title != null ? it.title : "", new android.text.TextPaint(t), room, TextUtils.TruncateAt.END).toString(), x, base, t);
            if (hasSub) {
                t.setTypeface(Ui.medium);
                t.setTextSize(h * 0.085f);
                t.setColor(0xCCFFFFFF);
                c.drawText(TextUtils.ellipsize(it.sub, new android.text.TextPaint(t), room, TextUtils.TruncateAt.END).toString(),
                        x, it.progress > 0f ? h * 0.86f : h * 0.91f, t);
            }
            if (it.progress > 0f) {
                float y = h * 0.925f, bh = Math.max(Ui.dpf(3), h * 0.026f);
                p.setColor(0x40FFFFFF);
                c.drawRoundRect(new RectF(x, y, x + room, y + bh), bh / 2f, bh / 2f, p);
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(new RectF(x, y, x + room * it.progress, y + bh), bh / 2f, bh / 2f, p);
            }
        };
    }

    static Intent launchFor(Continue.Item it, AppRepo.App app) {
        return it.intent != null ? it.intent : app != null ? app.launch : null;
    }
}
