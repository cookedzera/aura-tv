package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.session.MediaSession;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tonight's pick and the paused-music card, in both styles, at the usual text size and the larger
 * one many TVs are set to: every line fits its card (nothing shortened or clipped). And music
 * minimised leaves the small player, in both styles, whose button says Expand.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetTextTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    /** Every visible line in {@code card}: shortened with an ellipsis, or running past the card's edges. */
    private static void problems(View card, String name, List<String> out) {
        int[] c = new int[2];
        card.getLocationInWindow(c);
        List<TextView> texts = new ArrayList<>();
        collect(card, texts);
        for (TextView t : texts) {
            if (!t.isShown() || t.getText().length() == 0) continue;
            Layout l = t.getLayout();
            int[] p = new int[2];
            t.getLocationInWindow(p);
            String what = name + " '" + t.getText() + "' (" + t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity + "sp)";
            if (l != null) {
                for (int i = 0; i < l.getLineCount(); i++) {
                    if (l.getEllipsisCount(i) > 0) out.add(what + ": shortened");
                }
                // drawn lines taller than the view gives them
                if (l.getHeight() > t.getHeight() - t.getPaddingTop() - t.getPaddingBottom() + 1) out.add(what + ": clipped (" + l.getHeight() + " in " + t.getHeight() + ")");
            }
            if (p[1] + t.getHeight() > c[1] + card.getHeight() + 1 || p[0] + t.getWidth() > c[0] + card.getWidth() + 1) {
                out.add(what + ": runs past the card");
            }
        }
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }

    private List<String> check(String layout, float scale, StringBuilder log) throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        RuntimeEnvironment.setFontScale(scale);
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, layout).putBoolean(Prefs.DOCK_WIDGETS, true).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(3000);

        // Tonight's pick, with names as long as real ones get
        SmartStack.Slide pick = new SmartStack.Slide();
        pick.kicker = "TONIGHT'S PICK  ·  TVMAZE";
        pick.title = "The Lord of the Rings: The Rings of Power";
        pick.sub = "Fantasy · Adventure  ·  2022  ·  Prime Video";
        Bitmap art = Bitmap.createBitmap(400, 225, Bitmap.Config.ARGB_8888);
        art.eraseColor(0xFF2C3E50);
        pick.art = art;
        pick.color = 0xFF2C3E50;
        Object stack = field(a, "stack");
        call(stack, "setSlides", new Class<?>[]{List.class}, new ArrayList<>(Collections.singletonList(pick)));

        // the music card, paused, with a long title
        AppRepo.App owner = new AppRepo.App();
        owner.pkg = "com.spotify.tv.android";
        owner.label = "Spotify";
        owner.color = 0xFF1DB954;
        MediaWatch.Playing p = new MediaWatch.Playing();
        p.title = "Don't Stop Me Now - Remastered 2011";
        p.by = "Queen";
        p.pkg = owner.pkg;
        p.art = art;
        p.progress = 0.3f;
        call(a, "showTodayMusic", new Class<?>[]{MediaWatch.Playing.class, AppRepo.App.class}, p, owner);
        run(1500);

        View root = a.getWindow().getDecorView();
        Bitmap shot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(shot));
        try (FileOutputStream f = new FileOutputStream("target/widgets-" + layout + "-" + scale + ".png")) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        List<String> out = new ArrayList<>();
        View fy = field(a, "fyW"), td = field(a, "tdW");
        problems(fy, "pick", out);
        problems(td, "music", out);
        log.append(layout).append(" at ").append(scale).append("x: ").append(fy.getWidth()).append("x").append(fy.getHeight())
                .append(" stack, ").append(td.getWidth()).append("x").append(td.getHeight()).append(" today\n");
        for (String s : out) log.append("  ").append(s).append('\n');

        // music minimised: the Today card stays as the small player, its own size, button Expand
        MediaSession session = new MediaSession(c, "t");
        p.controller = session.getController();
        p.playing = true;
        set(a, "minimisedPkg", owner.pkg);
        call(a, "layoutWidgets", new Class<?>[]{boolean.class}, false);
        call(a, "showTodayMusic", new Class<?>[]{MediaWatch.Playing.class, AppRepo.App.class}, p, owner);
        run(500);
        List<?> row = field(a, "widgets");
        Assert.assertTrue("the small player is there (" + layout + ")", row.contains(td));
        Assert.assertEquals(View.VISIBLE, td.getVisibility());
        TextView chip = field(a, "tdChipText");
        Assert.assertEquals("Expand", chip.getText().toString());
        if (layout.equals("tvos") && row.size() == 1) Assert.assertEquals("its own size, not stretched", 200f, td.getWidth() / a.getResources().getDisplayMetrics().density, 1f);
        set(a, "minimisedPkg", null);
        call(a, "layoutWidgets", new Class<?>[]{boolean.class}, false);
        session.release();
        ac.pause().stop().destroy();
        RuntimeEnvironment.setFontScale(1f);
        return out;
    }

    /** The tvOS style's app previews (Top Shelf): long names, drawn at the card's real size, to look at. */
    @Test
    public void shelfCards() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Ui.init(c);
        int w = Ui.dp(260), h = Ui.dp(150);
        Bitmap poster = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
        poster.eraseColor(0xFF8E44AD);
        Bitmap wide = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        wide.eraseColor(0xFF2E86C1);
        Continue.Item[] items = new Continue.Item[4];
        items[0] = new Continue.Item();
        items[0].title = "The Lord of the Rings: The Rings of Power";
        items[0].sub = "Fantasy · Adventure  ·  2022  ·  Prime Video";
        items[0].kicker = "★  TONIGHT'S PICK";
        items[0].art = poster;
        items[0].posterCard = true;
        items[1] = new Continue.Item();
        items[1].title = Ui.songTitle("Don't Stop Me Now - Remastered 2011");
        items[1].sub = "Queen";
        items[1].kicker = "▶  CONTINUE PLAYING";
        items[1].art = poster;
        items[1].nowPlaying = true;
        items[1].progress = 0.4f;
        items[2] = new Continue.Item();
        items[2].title = "Avatar: The Last Airbender — The Legend of Korra";
        items[2].sub = "S2 · E7  ·  24 min left";
        items[2].kicker = "▶  CONTINUE WATCHING";
        items[2].art = wide;
        items[2].progress = 0.6f;
        items[3] = new Continue.Item();
        items[3].title = "Squid Game";
        items[3].sub = "New today  ·  S2 · E1";
        items[3].kicker = "NEW EPISODE";
        items[3].art = wide;
        Bitmap out = Bitmap.createBitmap(w * 2 + 20, h * 2 + 20, Bitmap.Config.ARGB_8888);
        Canvas all = new Canvas(out);
        all.drawColor(0xFF000000);
        for (int i = 0; i < 4; i++) {
            Bitmap one = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            TopShelf.paint(items[i], 0xFF3366CC).paint(new Canvas(one), w, h);
            all.drawBitmap(one, (i % 2) * (w + 20), (i / 2) * (h + 20), null);
        }
        try (FileOutputStream f = new FileOutputStream("target/shelf-cards.png")) {
            out.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    @Test
    public void nothingCut() throws Exception {
        StringBuilder log = new StringBuilder();
        List<String> all = new ArrayList<>();
        for (String layout : new String[]{"aura", "tvos"}) {
            for (float scale : new float[]{1f, 1.3f}) all.addAll(check(layout, scale, log));
        }
        try (FileWriter f = new FileWriter("target/widget-text.txt")) {
            f.write(log.toString());
        }
        Assert.assertTrue(log.toString(), all.isEmpty());
    }
}
