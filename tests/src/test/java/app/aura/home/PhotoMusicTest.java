package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.View;

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

/**
 * Music playing and home left alone: the photograph comes sooner, with the song's small bar over
 * it (never the full-screen player by itself), and the bar goes when home wakes.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoMusicTest {
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

    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(o, args);
    }

    @Test
    public void songBarInPhotoMode() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "tvos").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(2500);

        // a song, from a real media session
        MediaSession session = new MediaSession(c, "test");
        Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        art.eraseColor(0xFFC0392B);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Blinding Lights")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "The Weeknd").putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 80_000, 1f).build());
        session.setActive(true);
        MediaWatch.Playing p = new MediaWatch.Playing();
        p.title = "Blinding Lights";
        p.by = "The Weeknd";
        p.pkg = c.getPackageName();
        p.playing = true;
        p.controller = session.getController();
        org.robolectric.shadows.ShadowMediaController sc = org.robolectric.Shadows.shadowOf(p.controller);
        sc.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Blinding Lights")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "The Weeknd").putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
        sc.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 80_000, 1f).build());
        set(a, "listening", p);

        // left alone: no full-screen player by itself, and the photograph in 20 s, not 45
        call(a, "wake", new Class<?>[]{});
        run(19_000);
        Assert.assertFalse("not yet", (Boolean) field(a, "photo"));
        run(2_000);
        Assert.assertTrue("photo mode after 20 s with music on", (Boolean) field(a, "photo"));
        Object np = field(a, "nowPlaying");
        Method isOpen = np.getClass().getDeclaredMethod("isOpen");
        isOpen.setAccessible(true);
        Assert.assertFalse("the full-screen player doesn't take over by itself", (Boolean) isOpen.invoke(np));
        run(2500);
        View bar = field(a, "photoMusic");
        Assert.assertNotNull("the song's bar is over the photograph", bar);
        Assert.assertTrue(bar.isShown());
        View root = a.getWindow().getDecorView();
        Bitmap shot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(shot));
        try (FileOutputStream f = new FileOutputStream("target/photo-music.png")) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, f);
        }

        // a press wakes home: the bar fades and goes
        call(a, "wake", new Class<?>[]{});
        run(1500);
        Assert.assertNull(field(a, "photoMusic"));
        Assert.assertNull("removed from the screen", bar.getParent());

        // photo mode by hand, then home goes behind an app: nothing left ticking
        call(a, "setPhoto", new Class<?>[]{boolean.class}, true);
        run(500);
        Assert.assertNotNull(field(a, "photoMusic"));
        ac.pause();
        run(1500);
        Assert.assertNull(field(a, "photoMusic"));
        ac.stop().destroy();
        session.release();
    }
}
