package app.aura.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Recent apps opened, shut and opened again quickly: the second deck must survive the first one's
 * tidy-up, and no card may stay blank after a fold that was cut short.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SwitcherTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static final class Host implements Switcher.Host {
        final Map<String, AppRepo.App> apps = new HashMap<>();
        final Activity act;
        int cleared;

        Host(Activity a) {
            act = a;
            for (String p : new String[]{"com.a", "com.b", "com.c"}) {
                AppRepo.App x = new AppRepo.App();
                x.pkg = p;
                x.label = p;
                x.icon = new ColorDrawable(0xFF336699);
                x.color = 0xFF336699;
                apps.put(p, x);
            }
        }

        @Override public AppRepo.App find(String pkg) { return apps.get(pkg); }
        @Override public Card card(AppRepo.App a) { return new Card(act, Tiles.launch(a), a.color, 22); }
        @Override public void launch(AppRepo.App a, View from) { }
        @Override public void close(AppRepo.App a) { }
        @Override public void closeAll(List<AppRepo.App> all) { }
        @Override public void cleared(int count) { cleared++; }
        @Override public Bitmap shot(String pkg) { return null; }
    }

    private static LinkedHashMap<String, Long> recent() {
        LinkedHashMap<String, Long> r = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        r.put("com.a", now);
        r.put("com.b", now - 60_000);
        r.put("com.c", now - 120_000);
        return r;
    }

    @Test
    public void reopenedWhileClosing() throws Exception {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        Host h = new Host(act);
        Switcher s = new Switcher(act, null, h);
        root.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        run(100);

        // opened over com.a: it folds into its card; shut before the fold ends, opened again at once
        s.open(recent(), "com.a");
        run(120);
        boolean[] after = {false};
        s.close(() -> after[0] = true);
        run(60);
        Assert.assertTrue(s.isClosing());
        s.open(recent(), "com.b");
        run(1500);
        Assert.assertTrue("the second deck stays open", s.isOpen());
        Assert.assertEquals(View.VISIBLE, s.getVisibility());
        List<?> items = field(s, "items");
        Assert.assertEquals("its cards weren't torn down by the first close", 3, items.size());
        Assert.assertFalse("the first close's tidy-up never ran over it", after[0]);
        // every card shows (the one folded into in the first deck too)
        List<ViewGroup> cards = new ArrayList<>();
        for (Object o : items) cards.add((ViewGroup) o);
        for (ViewGroup it : cards) Assert.assertEquals(View.VISIBLE, it.getChildAt(1).getVisibility());

        // Close all, then opened again before its word arrives: the new deck isn't shut by it
        s.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
        s.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        run(100);
        s.close(null);
        run(300);
        Assert.assertFalse(s.isOpen());
        s.open(recent(), null);
        run(1500);
        Assert.assertEquals("Close all's word was dropped with the deck it belonged to", 0, h.cleared);
        Assert.assertTrue(s.isOpen());
    }
}
