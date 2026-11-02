package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Continue watching from what the apps' media sessions said: what counts, and what doesn't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class WatchedTest {
    private static void installVideoApp(Context c, String pkg) {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ComponentName main = new ComponentName(pkg, pkg + ".Main");
        pm.addActivityIfNotPresent(main);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            pm.addIntentFilterForActivity(main, f);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MediaWatch.Playing playing(String pkg, String title) {
        MediaWatch.Playing p = new MediaWatch.Playing();
        p.pkg = pkg;
        p.title = title;
        return p;
    }

    private static void settle() throws InterruptedException {
        // the list is written behind the main thread
        Thread.sleep(400);
    }

    @Test
    public void programmesNotSongs() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        installVideoApp(c, "com.netflix.ninja");
        installVideoApp(c, "com.spotify.tv.android");
        Watched.forget(c);
        // an episode, ten minutes into forty-five
        Watched.note(c, playing("com.netflix.ninja", "The Great Indian Kapil Show"), 45 * 60_000L, 10 * 60_000L);
        settle();
        // a song is too short; a long album on a music app isn't watching
        Watched.note(c, playing("com.netflix.ninja", "A trailer"), 4 * 60_000L, 60_000L);
        Watched.note(c, playing("com.spotify.tv.android", "A long mix"), 60 * 60_000L, 20 * 60_000L);
        settle();
        List<Continue.Item> got = Watched.recent(c, new HashSet<>(), 4);
        assertEquals(1, got.size());
        Continue.Item it = got.get(0);
        assertEquals("The Great Indian Kapil Show", it.title);
        assertEquals("com.netflix.ninja", it.pkg);
        assertEquals(10 / 45f, it.progress, 0.01f);
        assertTrue(it.sub, it.sub.contains("35 min left"));
        assertNotNull(it.intent);
        // already on the stack from Watch Next: not twice
        HashSet<String> skip = new HashSet<>();
        skip.add(Continue.key("The Great Indian Kapil Show"));
        assertTrue(Watched.recent(c, skip, 4).isEmpty());
        // watched to the end: no longer something to come back to, even within the minute
        Watched.note(c, playing("com.netflix.ninja", "The Great Indian Kapil Show"), 45 * 60_000L, 44 * 60_000L);
        settle();
        assertTrue(Watched.recent(c, new HashSet<>(), 4).isEmpty());
    }
}
