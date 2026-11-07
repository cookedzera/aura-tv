package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Settings: a "Press OK again" doesn't outlive a trip through From your phone, what a rebuild
 * reads (the phone photos' index, whether a category row is empty) is kept yet follows each
 * change, and the Photos page keeps only forty small thumbnails.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsFixesTest {
    private Context c;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
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

    /** Everything a Settings row says: its name and its value. */
    private static String row(Object settings, String tag) throws Exception {
        ViewGroup rows = field(settings, "rows");
        View r = rows.findViewWithTag(tag);
        assertNotNull(tag, r);
        return lines(r);
    }

    private static String lines(View v) {
        if (v instanceof TextView) return ((TextView) v).getText() + "\n";
        StringBuilder s = new StringBuilder();
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) s.append(lines(((ViewGroup) v).getChildAt(i)));
        }
        return s.toString();
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").putBoolean(Categories.ROWS, true).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        // the apps a test fills in place are the process's cache too: each test starts with none
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
    }

    /** The photo index is read once and kept, and every change to it is seen at once. */
    @Test
    public void photoCountFollowsEveryChangeToTheIndex() throws Exception {
        JSONArray index = new JSONArray();
        for (int i = 0; i < 3; i++) index.put(new JSONObject().put("id", "p" + i).put("path", "/nowhere/" + i).put("place", "Photo " + i));
        Method write = Mine.class.getDeclaredMethod("write", Context.class, JSONArray.class);
        write.setAccessible(true);
        write.invoke(null, c, index);
        assertEquals(3, Mine.count(c));
        assertEquals(3, Mine.list(c).length());
        assertTrue(Mine.remove(c, "p1"));
        assertEquals(2, Mine.count(c));
        assertEquals("p2", Mine.list(c).getJSONObject(1).getString("id"));
        Mine.clear(c);
        assertEquals(0, Mine.count(c));
        // the file changed behind Mine's back (it carries a newer time): read again
        File f = new File(new File(c.getFilesDir(), "mine"), "index.json");
        long was = f.lastModified();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("[{\"id\":\"x\",\"place\":\"\"}]".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(f.setLastModified(was + 2000));
        assertEquals(1, Mine.count(c));
    }

    /** Remove Aura pressed once, then From your phone and Back: one more press only asks again. */
    @Test
    public void removeAuraArmedBeforeFromYourPhoneIsForgotten() throws Exception {
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(1500);
        Object s = field(a, "settings");
        call(a, "openSettings", new Class<?>[]{});
        run(800);
        // the sentence beside the list ends in an ellipsis rather than mid-word
        TextView help = field(s, "help");
        assertEquals(TextUtils.TruncateAt.END, help.getEllipsize());
        String again = a.getString(R.string.press_ok_again);
        call(s, "removeAura", new Class<?>[]{});
        boolean armed = field(s, "confirmRemove");
        assertTrue("one OK arms Remove Aura", armed);
        assertTrue(row(s, "remove").contains(again));

        call(s, "openDrop", new Class<?>[]{});
        run(500);
        assertEquals("drop", field(s, "page"));
        armed = field(s, "confirmRemove");
        assertFalse("From your phone forgets it", armed);
        // and Back from a page forgets anything still armed
        set(s, "confirmRemove", true);
        assertTrue((Boolean) call(s, "back", new Class<?>[]{}));
        run(500);
        assertEquals("", field(s, "page"));
        armed = field(s, "confirmRemove");
        assertFalse(armed);
        assertFalse(row(s, "remove").contains(again));

        call(s, "removeAura", new Class<?>[]{});
        armed = field(s, "confirmRemove");
        assertTrue("a single OK only asks again", armed);
        ac.pause().stop().destroy();
    }

    /** The Photos page's thumbnails are read at the size they're shown, and only the forty last shown are kept. */
    @Test
    public void photoThumbnailsAreReadAtTheirShownSizeAndFortyAtMostAreKept() throws Exception {
        File stored = Mine.thumb(c, "p1");
        assertTrue(stored.getParentFile().isDirectory() || stored.getParentFile().mkdirs());
        Bitmap big = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        big.eraseColor(0xFF336699);
        try (FileOutputStream out = new FileOutputStream(stored)) {
            assertTrue(big.compress(Bitmap.CompressFormat.JPEG, 82, out));
        }
        Bitmap small = Mine.smallThumb(c, "p1");
        assertNotNull(small);
        assertEquals(160, small.getWidth());
        assertEquals(90, small.getHeight());
        assertEquals(Bitmap.Config.RGB_565, small.getConfig());

        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(1500);
        Object s = field(a, "settings");
        call(a, "openSettings", new Class<?>[]{});
        run(800);
        Map<String, Bitmap> thumbs = field(s, "thumbs");
        for (int i = 0; i < 50; i++) thumbs.put("t" + i, small);
        assertEquals(40, thumbs.size());
        assertFalse("the least recently shown go first", thumbs.containsKey("t0"));
        assertTrue(thumbs.containsKey("t49"));
        ac.pause().stop().destroy();
    }

    /** Whether a category row is empty is worked out once a visit, and again once something changed it. */
    @Test
    public void emptyCategoryRowFollowsHiddenApps() throws Exception {
        String pkg = "com.example.fixes.video";
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.name = "Video";
        ai.nonLocalizedLabel = "Video";
        ai.category = ApplicationInfo.CATEGORY_VIDEO;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        shadowOf(c.getPackageManager()).installPackage(pi);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(1500);
        AppRepo.App app = new AppRepo.App();
        app.pkg = pkg;
        app.label = "Video";
        app.icon = new ColorDrawable(0xFF335577);
        app.color = 0xFF335577;
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        apps.add(app);
        call(a, "populate", new Class<?>[]{});
        run(500);

        Object s = field(a, "settings");
        call(a, "openSettings", new Class<?>[]{String.class}, "cats");
        run(800);
        String empty = a.getString(R.string.empty_not_shown);
        assertFalse(row(s, "cat:video").contains(empty));
        Map<String, Boolean> known = field(s, "catsEmpty");
        assertEquals(Boolean.FALSE, known.get("video"));

        // its only app hidden while Settings is open, then Settings told something changed
        Prefs.hide(c, pkg, true);
        call(s, "refresh", new Class<?>[]{});
        assertTrue(row(s, "cat:video").contains(empty));

        // shown again, and the page visited afresh
        Prefs.hide(c, pkg, false);
        call(s, "go", new Class<?>[]{String.class}, "home");
        call(s, "go", new Class<?>[]{String.class}, "cats");
        assertFalse(row(s, "cat:video").contains(empty));
        ac.pause().stop().destroy();
    }
}
