package app.aura.home;

import android.content.Context;
import android.view.KeyEvent;

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

/** Opens the new and changed screens: Problem reports, Widgets (with a turned-down card), Updates, and a card's hold-OK menu. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PagesTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    @Test
    public void pagesOpen() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        // something to show on the reports page, and a card turned down
        Trouble.caught("test", new IllegalStateException("a test problem"));
        Taste.decline(c, Continue.key("Squid Game"));
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(3000);
        StringBuilder log = new StringBuilder();
        for (String page : new String[]{"widgets", "updates", ""}) {
            call(a, "openSettings", new Class<?>[]{String.class}, page);
            run(1500);
            log.append("opened settings ").append(page.isEmpty() ? "(main)" : page).append('\n');
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            run(800);
        }
        // the Problem reports page itself, through Settings' own method
        Object settings = field(a, "settings");
        call(a, "openSettings", new Class<?>[]{String.class}, "");
        run(800);
        call(settings, "openTrouble", new Class<?>[]{});
        run(1500);
        log.append("opened problem reports; page=").append(field(settings, "page")).append('\n');
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        run(800);
        // a card's hold-OK menu on the Smart Stack
        Object fy = field(a, "fyW");
        call(a, "openWidgetMenu", new Class<?>[]{Widget.class}, fy);
        run(800);
        log.append("opened the stack's menu\n");
        ac.pause().stop().destroy();
        log.append("\n").append(Trouble.report(c));
        try (java.io.FileWriter fw = new java.io.FileWriter("target/pages.txt")) {
            fw.write(log.toString());
        }
    }

    @Test
    public void screensaverMakesRoomAndHomeComesBack() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        Trouble.clear(c);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        run(3000);
        Object backdrop = field(ac.get(), "backdrop");
        // out of sight, the screensaver starts and asks for room
        ac.pause().stop();
        run(300);
        Runnable room = Ambient.makeRoom;
        org.junit.Assert.assertNotNull(room);
        room.run();
        run(300);
        Object afterTrim = field(backdrop, "current");
        ac.restart().start().resume();
        // the simulator decodes slowly in real time: wait for the photograph as a TV would show it
        for (int i = 0; i < 200 && field(backdrop, "current") == null; i++) {
            run(100);
            Thread.sleep(100);
        }
        Object back = field(backdrop, "current");
        String state = "running=" + field(backdrop, "running") + " loading=" + field(backdrop, "loading")
                + " walls=" + ((java.util.List<?>) field(backdrop, "walls")).size() + " index=" + field(backdrop, "index")
                + " generation=" + field(backdrop, "generation") + " failures=" + field(backdrop, "failures")
                + " nextAt=" + field(backdrop, "nextAt") + " now=" + android.os.SystemClock.uptimeMillis()
                + " io.shutdown=" + ((java.util.concurrent.ExecutorService) field(backdrop, "io")).isShutdown();
        StringBuilder stacks = new StringBuilder();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().equals("photos")) continue;
            stacks.append("thread photos ").append(e.getKey().getState()).append('\n');
            for (int i = 0; i < Math.min(14, e.getValue().length); i++) stacks.append("  at ").append(e.getValue()[i]).append('\n');
        }
        state += "\n" + stacks;
        String out = "photo after making room: " + (afterTrim == null ? "let go" : "kept")
                + "; after coming back: " + (back == null ? "missing" : "shown") + "\nproblems: " + Trouble.count(c) + "\n" + state + "\n";
        ac.pause().stop().destroy();
        org.junit.Assert.assertNull("Ambient.makeRoom cleared on destroy", Ambient.makeRoom);
        try (java.io.FileWriter fw = new java.io.FileWriter("target/room.txt")) {
            fw.write(out);
        }
        org.junit.Assert.assertNull(afterTrim);
        org.junit.Assert.assertNotNull(back);
    }
}
