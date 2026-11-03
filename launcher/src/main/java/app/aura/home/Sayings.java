package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Famous quotes over the photographs, one at a time, and never the same one twice until every one
 * has been shown: ZenQuotes' collection (thousands, free and without a key; credited, as it asks),
 * and DummyJSON's, fetched in batches and kept, with a few dozen bundled for a TV that's offline.
 * Set as Apple Music sets lyrics: big bold lines lighting one after another over the photograph,
 * a soft shade behind them. A new photograph brings a new quote.
 */
final class Sayings extends Scene {
    // each new photograph brings a new quote (see turnNow); this is only for a wall that stays still
    private static final long EVERY_MS = 2 * 60_000L, FADE_MS = 1_400;
    private static final String API = "https://zenquotes.io/api/quotes";
    private static final int GOLD = 0xFFF0D6AA, IVORY = 0xFFFFFBF4;
    /** For a TV that's offline: public-domain words only. */
    private static final String[][] BUNDLED = {
            {"The unexamined life is not worth living.", "Socrates"},
            {"The only true wisdom is in knowing you know nothing.", "Socrates"},
            {"Waste no more time arguing what a good man should be. Be one.", "Marcus Aurelius"},
            {"The soul becomes dyed with the colour of its thoughts.", "Marcus Aurelius"},
            {"Dwell on the beauty of life. Watch the stars, and see yourself running with them.", "Marcus Aurelius"},
            {"We suffer more often in imagination than in reality.", "Seneca"},
            {"It is not that we have a short time to live, but that we waste a lot of it.", "Seneca"},
            {"Luck is what happens when preparation meets opportunity.", "Seneca"},
            {"No man ever steps in the same river twice.", "Heraclitus"},
            {"It does not matter how slowly you go, as long as you do not stop.", "Confucius"},
            {"What you do not want done to yourself, do not do to others.", "Confucius"},
            {"A journey of a thousand miles begins with a single step.", "Lao Tzu"},
            {"Nature does not hurry, yet everything is accomplished.", "Lao Tzu"},
            {"Knowing yourself is the beginning of all wisdom.", "Aristotle"},
            {"Well begun is half done.", "Aristotle"},
            {"Heaven is under our feet as well as over our heads.", "Henry David Thoreau"},
            {"Do nothing that is of no use.", "Miyamoto Musashi"},
            {"Think lightly of yourself and deeply of the world.", "Miyamoto Musashi"},
            {"Today is victory over yourself of yesterday.", "Miyamoto Musashi"},
            {"The best way out is always through.", "Robert Frost"},
            {"Hope is the thing with feathers that perches in the soul.", "Emily Dickinson"},
            {"Adopt the pace of nature: her secret is patience.", "Ralph Waldo Emerson"},
            {"He who has a why to live can bear almost any how.", "Friedrich Nietzsche"},
            {"Nothing in life is to be feared, it is only to be understood.", "Marie Curie"},
            {"Patience is bitter, but its fruit is sweet.", "Jean-Jacques Rousseau"},
            {"All the world's a stage, and all the men and women merely players.", "William Shakespeare"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Compat.background("sayings", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final TextPaint line = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private Page page, before;
    private boolean running;
    private long fadeAt = -1;

    /** One quote, broken into lines, and when it arrived: its lines light from then. */
    private static final class Page {
        StaticLayout layout;
        TextPaint paint;
        String author;
        boolean online;
        long at;
    }

    Sayings(Context c) {
        super(c);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // the photograph stays sharp; a soft darkness rises from the left, where the words are
        shade.setShader(new LinearGradient(0, 0, w * 0.72f, 0, new int[]{0xB3060608, 0x6B060608, 0x00060608},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    boolean ownClock() {
        return true;
    }

    @Override
    void start() {
        running = true;
        next();
        tick.run();
    }

    @Override
    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        worker.shutdown();
        super.onDetachedFromWindow();
    }

    private final Runnable turn = this::next;

    /** A new photograph arrived: a new quote with it, and the timer starts again. */
    void turnNow() {
        main.removeCallbacks(turn);
        next();
    }

    /** The clock moves on the minute: one redraw, and nothing in between. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            Calendar now = Calendar.getInstance();
            main.postDelayed(this, (60 - now.get(Calendar.SECOND)) * 1000L + 50);
        }
    };

    private void next() {
        if (!running || worker.isShutdown()) return;
        final Context app = getContext().getApplicationContext();
        try {
            worker.execute(() -> {
                final String[] q = pick(app);
                main.post(() -> {
                    if (!running || getWidth() == 0 || getHeight() == 0) {
                        if (running) main.postDelayed(turn, 1000);
                        return;
                    }
                    show(q);
                    main.postDelayed(turn, EVERY_MS);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    // ---- choosing: never twice until all have been shown ---------------------------------------

    /** The next quote no one has seen here yet: {text, author, "1" if from ZenQuotes}. Off the main thread. */
    private static synchronized String[] pick(Context c) {
        SharedPreferences p = c.getSharedPreferences("sayings", Context.MODE_PRIVATE);
        Set<String> seen = new HashSet<>(p.getStringSet("seen", new HashSet<>()));
        File f = new File(new File(c.getFilesDir(), "sayings"), "pool.json");
        List<String[]> pool = readPool(f);
        // running low: fifty more, keeping only those never shown
        if (pool.size() < 8) {
            for (String[] q : fetch()) if (!seen.contains(key(q[0])) && !contains(pool, q[0])) pool.add(q);
        }
        String[] chosen = null;
        while (!pool.isEmpty() && chosen == null) {
            String[] q = pool.remove(0);
            // the third field names the source: ZenQuotes asks to be credited on screen
            if (!seen.contains(key(q[0]))) chosen = new String[]{q[0], q[1], q.length > 2 && q[2].equals("z") ? "1" : ""};
        }
        if (chosen == null) {
            List<String[]> offline = new ArrayList<>();
            for (String[] q : BUNDLED) if (!seen.contains(key(q[0]))) offline.add(q);
            // every quote this TV knows has been shown: only now may they come round again
            if (offline.isEmpty()) {
                for (String[] q : BUNDLED) seen.remove(key(q[0]));
                Collections.addAll(offline, BUNDLED);
            }
            String[] q = offline.get((int) (Math.random() * offline.size()));
            chosen = new String[]{q[0], q[1], ""};
        }
        seen.add(key(chosen[0]));
        if (seen.size() > 4000) seen.clear();
        p.edit().putStringSet("seen", seen).apply();
        writePool(f, pool);
        return chosen;
    }

    private static String key(String quote) {
        return Integer.toHexString(quote.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").hashCode());
    }

    private static boolean contains(List<String[]> pool, String quote) {
        for (String[] q : pool) if (q[0].equals(quote)) return true;
        return false;
    }

    /** Fifty from ZenQuotes, short enough to set large; none when offline or turned away. */
    private static List<String[]> fetch() {
        List<String[]> out = new ArrayList<>();
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("User-Agent", "AuraTV (open-source Android TV home screen)");
            try (InputStream in = con.getInputStream()) {
                JSONArray a = new JSONArray(read(in));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    String q = o == null ? "" : o.optString("q").trim(), who = o == null ? "" : o.optString("a").trim();
                    // the service's own notices (too many requests and the like) come signed by it
                    if (q.isEmpty() || who.isEmpty() || who.toLowerCase(Locale.ROOT).contains("zenquotes") || q.length() > 170) continue;
                    out.add(new String[]{q, who, "z"});
                }
            } finally {
                con.disconnect();
            }
        } catch (Exception ignored) {
            // offline, or the service is down: the bundled quotes carry on
        }
        // and fifty from DummyJSON's collection (about 1,500 more), from a random place in it
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("https://dummyjson.com/quotes?limit=50&skip="
                    + (int) (Math.random() * 1400)).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            try (InputStream in = con.getInputStream()) {
                JSONArray a = new JSONObject(read(in)).optJSONArray("quotes");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    String q = o == null ? "" : o.optString("quote").trim(), who = o == null ? "" : o.optString("author").trim();
                    if (!q.isEmpty() && !who.isEmpty() && q.length() <= 170) out.add(new String[]{q, who, "d"});
                }
            } finally {
                con.disconnect();
            }
        } catch (Exception ignored) {
            // the other source and the bundled quotes carry on
        }
        Collections.shuffle(out);
        return out;
    }

    private static List<String[]> readPool(File f) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONArray q = a.optJSONArray(i);
                if (q != null && q.length() >= 2) out.add(new String[]{q.optString(0), q.optString(1), q.optString(2, "z")});
            }
        } catch (Exception ignored) {
            // none kept yet
        }
        return out;
    }

    private static void writePool(File f, List<String[]> pool) {
        JSONArray a = new JSONArray();
        for (String[] q : pool) a.put(new JSONArray().put(q[0]).put(q[1]).put(q.length > 2 ? q[2] : "z"));
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // the next pick fetches again
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- showing ---------------------------------------------------------------------------------

    /** Where the quote sits, as a share of the height: a little higher while a song plays beneath it. */
    private float centre = 0.46f;

    void setCentre(float c) {
        if (c == centre) return;
        centre = c;
        invalidate();
    }

    /** How long each line takes to light, as a lyric would as it's sung. */
    private static final long LINE_MS = 1_700;

    /** Breaks the quote into big bold lines, as large as four lines allow, and crossfades to it. */
    private void show(String[] q) {
        int w = getWidth(), h = getHeight();
        float s = h / 1080f;
        Page p = new Page();
        p.author = q[1];
        p.online = !q[2].isEmpty();
        // its own paint: the quote fading out keeps its size while the new one is laid out
        p.paint = new TextPaint(line);
        p.paint.setTypeface(Ui.bold);
        p.paint.setLetterSpacing(-0.012f);
        float size = 84 * s;
        do {
            p.paint.setTextSize(size);
            p.layout = new StaticLayout(q[0], p.paint, Math.round(w * 0.64f), Layout.Alignment.ALIGN_NORMAL, 1.06f, 0f, false);
            size -= 4 * s;
        } while (p.layout.getLineCount() > 4 && size > 40 * s);
        p.at = SystemClock.uptimeMillis();
        before = page;
        page = p;
        fadeAt = before != null ? p.at : -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (page == null) return;
        c.drawRect(0, 0, w, h, shade);
        long now = SystemClock.uptimeMillis();
        float f = fadeAt < 0 ? 1f : Math.min(1f, (now - fadeAt) / (float) FADE_MS);
        float e = f * f * (3 - 2 * f);
        boolean moving = f < 1f;
        if (before != null && f < 1f) words(c, before, h, 1f - e, now);
        moving |= words(c, page, h, before != null && f < 1f ? e : 1f, now);
        clock(c, w, h);
        if (f >= 1f) before = null;
        if (moving && running) postInvalidateOnAnimation();
    }

    /**
     * A quote's lines lighting one after another: the one lighting bright, those before it stepping
     * back, those to come dim. Once all have lit, they settle bright together. True while still moving.
     */
    private boolean words(Canvas c, Page p, int h, float a, long now) {
        float s = h / 1080f;
        StaticLayout layout = p.layout;
        int n = layout.getLineCount();
        float x = 150 * s, top = h * centre - layout.getHeight() / 2f;
        float t = (now - p.at) / (float) LINE_MS, settle = Ui.clamp(t - n, 0f, 1f);
        for (int i = 0; i < n; i++) {
            float lit = Ui.clamp(t - i, 0f, 1f), past = Ui.clamp(t - i - 1, 0f, 1f);
            lit = lit * lit * (3 - 2 * lit);
            float level = Ui.clamp(0.28f + 0.72f * lit - 0.4f * past * (1f - settle), 0f, 1f);
            p.paint.setColor(Ui.withAlpha(0xFFFFFFFF, Math.round(255 * a * level)));
            c.drawText(layout.getText(), layout.getLineStart(i), layout.getLineEnd(i), x, top + layout.getLineBaseline(i), p.paint);
        }
        float y = top + layout.getHeight() + 40 * s;
        small.setTextAlign(Paint.Align.LEFT);
        small.setLetterSpacing(0f);
        small.setTypeface(Ui.semibold);
        small.setTextSize(28 * s);
        small.setColor(Ui.withAlpha(0xFFFFFFFF, Math.round(185 * a)));
        c.drawText(p.author, x + 2 * s, y - small.ascent(), small);
        if (p.online) {
            // ZenQuotes asks to be credited where its quotes are shown
            float below = y - small.ascent() + small.descent() + 14 * s;
            small.setTypeface(Ui.regular);
            small.setTextSize(15 * s);
            small.setColor(Ui.withAlpha(0xFFFFFFFF, Math.round(95 * a)));
            c.drawText(getContext().getString(R.string.quotes_from_zenquotes_io), x + 2 * s, below - small.ascent(), small);
        }
        return t < n + 1;
    }

    /** The time, small and quiet in the corner, as on Apple TV. */
    private void clock(Canvas c, int w, int h) {
        float s = h / 1080f;
        CharSequence time = android.text.format.DateFormat.format(android.text.format.DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm",
                Calendar.getInstance());
        small.setTextAlign(Paint.Align.RIGHT);
        small.setLetterSpacing(0f);
        small.setTypeface(Ui.semibold);
        small.setTextSize(40 * s);
        small.setColor(0xE0FFFFFF);
        c.drawText(time, 0, time.length(), w - 150 * s, 80 * s - small.ascent(), small);
    }
}
