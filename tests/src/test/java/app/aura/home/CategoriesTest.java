package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Category rows fill themselves from what each app tells Android it is, or from the known TV
 * apps; you can add to and take out of any row; All apps keeps your own order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class CategoriesTest {
    private Context c;

    private void install(String pkg, String label, int category, boolean game) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.name = label;
        ai.nonLocalizedLabel = label;
        ai.category = category;
        if (game) ai.flags |= ApplicationInfo.FLAG_IS_GAME;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        shadowOf(c.getPackageManager()).installPackage(pi);
    }

    @Before
    public void apps() {
        c = RuntimeEnvironment.getApplication();
        install("com.example.stream", "Stream", ApplicationInfo.CATEGORY_VIDEO, false);
        install("com.example.tunes", "Tunes", ApplicationInfo.CATEGORY_AUDIO, false);
        install("com.example.racer", "Racer", ApplicationInfo.CATEGORY_UNDEFINED, true);
        install("com.example.tool", "Tool", ApplicationInfo.CATEGORY_UNDEFINED, false);
        install("com.netflix.ninja", "Netflix", ApplicationInfo.CATEGORY_UNDEFINED, false);
    }

    private final List<String> installed = Arrays.asList("com.example.stream", "com.example.tunes", "com.example.racer",
            "com.example.tool", "com.netflix.ninja");

    private List<String> row(String id) {
        return Categories.members(c, Categories.find(Categories.all(c), id), Arrays.asList("com.netflix.ninja"), installed);
    }

    @Test
    public void smartRowsFillThemselves() {
        assertEquals("your order first, then the rest", Arrays.asList("com.netflix.ninja", "com.example.stream"), row("video"));
        assertEquals(Arrays.asList("com.example.tunes"), row("music"));
        assertEquals("a game flag counts", Arrays.asList("com.example.racer"), row("games"));
        assertTrue("nothing says News: that row stays empty, and isn't shown", row("news").isEmpty());
        assertTrue("Favorites starts empty", row(Categories.FAV).isEmpty());
        assertEquals("All apps: every app, your order first", "com.netflix.ninja", row(Categories.ALL).get(0));
        assertEquals(installed.size(), row(Categories.ALL).size());
    }

    @Test
    public void byHandAddsAndTakesOut() {
        Categories.addTo(c, Categories.FAV, "com.example.tool");
        Categories.removeFrom(c, "video", "com.netflix.ninja");
        assertEquals(Arrays.asList("com.example.tool"), row(Categories.FAV));
        assertEquals(Arrays.asList("com.example.stream"), row("video"));
        // added back by hand: in again
        Categories.addTo(c, "video", "com.netflix.ninja");
        assertTrue(row("video").contains("com.netflix.ninja"));
    }

    @Test
    public void ownRowsSortAndMove() {
        String id = Categories.create(c, "Kids");
        Categories.addTo(c, id, "com.example.tool");
        Categories.addTo(c, id, "com.example.racer");
        assertEquals(Arrays.asList("com.example.tool", "com.example.racer"), row(id));
        Categories.nextSort(c, id);
        assertEquals("A to Z", Arrays.asList("com.example.racer", "com.example.tool"), row(id));
        List<Categories.Cat> cats = Categories.all(c);
        assertEquals("new rows sit just above All apps", Categories.ALL, cats.get(cats.size() - 1).id);
        Categories.delete(c, id);
        assertNull(Categories.find(Categories.all(c), id));
        Categories.delete(c, "video");
        assertNotNull("built-in rows can only be hidden", Categories.find(Categories.all(c), "video"));
    }
}
