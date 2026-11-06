package app.aura.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/** The TV keeps as many phone photos as it's set to, and says so instead of quietly throwing old ones out. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class PhotosCapTest {
    private static void seed(Context c, int n) throws Exception {
        JSONArray index = new JSONArray();
        for (int i = 0; i < n; i++) index.put(new JSONObject().put("id", "p" + i).put("path", "/nowhere/" + i).put("place", ""));
        java.lang.reflect.Method write = Mine.class.getDeclaredMethod("write", Context.class, JSONArray.class);
        write.setAccessible(true);
        write.invoke(null, c, index);
    }

    @Test
    public void fullTvRefusesInsteadOfEvicting() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        assertEquals(60, Mine.keep(c));
        seed(c, 60);
        File part = File.createTempFile("aura", ".jpg");
        try {
            Mine.add(c, part, "a.jpg", "");
            fail("a full TV should refuse the photo");
        } catch (Mine.Full e) {
            assertTrue(e.getMessage(), e.getMessage().contains("60"));
        }
        // nothing was thrown out to make room
        assertEquals(60, Mine.count(c));
        assertTrue(part.delete() || !part.exists());
    }

    @Test
    public void raisingTheLimitLetsMoreIn() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        seed(c, 60);
        Mine.setKeep(c, 120);
        assertEquals(120, Mine.keep(c));
        File part = File.createTempFile("aura", ".jpg");
        try {
            Mine.add(c, part, "a.jpg", "");
        } catch (Mine.Full e) {
            fail("60 of 120 isn't full");
        } catch (IOException | RuntimeException ignored) {
            // an empty file isn't a photo: past the limit check, it's refused for that instead
        }
        assertTrue(part.delete() || !part.exists());
    }

    @Test
    public void ceilingIsOneOfTheChoices() {
        Context c = RuntimeEnvironment.getApplication();
        int ceiling = Mine.keepCeiling(c);
        boolean offered = false;
        for (int n : Mine.KEEP_CHOICES) offered |= n == ceiling;
        assertTrue("ceiling " + ceiling, offered);
    }
}
