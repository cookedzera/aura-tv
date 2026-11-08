package app.aura.home;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/** Control Center in both styles, with a song playing and a notification: both show, in each. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CcTvosTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    /** Control Center's notes arrive on its own thread: wait for them, then idle the board. */
    private static void settle() throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(30);
            run(50);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(o, args);
    }

    private void check(String layout) throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, layout).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        MediaWatch ws = Robolectric.buildService(MediaWatch.class).create().get();
        ws.onListenerConnected();
        Notification said = new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Download finished").setContentText("Season 2 is ready to watch").build();
        shadowOf(ws).addActiveNotification("com.netflix.ninja", 1, said);

        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(2500);
        Object cc = field(a, "cc");
        MediaWatch.Playing p = new MediaWatch.Playing();
        p.title = "Blinding Lights";
        p.by = "The Weeknd";
        p.pkg = "com.spotify.tv.android";
        p.playing = true;
        p.progress = 0.4f;
        Bitmap art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        art.eraseColor(0xFF8E44AD);
        p.art = art;
        call(cc, "music", new Class<?>[]{MediaWatch.Playing.class}, p);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(1500);
        settle();
        View now = field(cc, "nowCard"), notes = field(cc, "notesBox"), pick = field(cc, "pick");
        View root = a.getWindow().getDecorView();
        Bitmap shot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(shot));
        try (FileOutputStream f = new FileOutputStream("target/cc-" + layout + ".png")) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        Assert.assertTrue("the song shows in Control Center (" + layout + ")", now.isShown());
        Assert.assertTrue("notifications show in Control Center (" + layout + ")", notes.isShown());
        Assert.assertFalse("never the pick beside the song", pick.isShown());
        TextView time = field(cc, "time"), date = field(cc, "date"), title = field(cc, "headTitle"), info = field(cc, "info");
        Assert.assertTrue("the time stays in the column (" + layout + ")", time.isShown());
        Assert.assertTrue("and the date with it (" + layout + ")", date.isShown());
        float sp = time.getTextSize() / time.getResources().getDisplayMetrics().scaledDensity;
        if ("tvos".equals(layout)) {
            Assert.assertFalse("not the large CONTROL CENTER title", title.isShown());
            Assert.assertFalse("nor the day line: the column is time, then what's live", info.isShown());
            Assert.assertEquals("a small clock, aligned with the board", 28f, sp, 0.5f);
            Assert.assertEquals(Gravity.END, ((android.widget.LinearLayout.LayoutParams) time.getLayoutParams()).gravity);
        } else {
            Assert.assertTrue(title.isShown());
            Assert.assertEquals("the large clock on the left", 56f, sp, 0.5f);
        }
        ac.pause().stop().destroy();
        ws.onListenerDisconnected();
    }

    @Test
    public void tvosStyle() throws Exception {
        check("tvos");
    }

    @Test
    public void auraStyle() throws Exception {
        check("aura");
    }
}
