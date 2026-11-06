package app.aura.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.tv.TvContract;
import android.net.Uri;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.robolectric.Shadows.shadowOf;

/**
 * The switcher's Close keeps other apps' accessibility services switched on, and Watch Next rows
 * an app wrote in seconds still reach Continue Watching, in the right order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class CloseAndContinueTest {

    /** Close never force-stops an app whose accessibility service is on: Android would switch the service off for good. */
    @Test
    public void closeSparesAppsWithAnAccessibilityServiceOn() {
        String on = "com.example.mapper/com.example.mapper.KeyService:app.aura.home/.HomeKeys:com.example.reader/.Reader";
        Assert.assertEquals(Arrays.asList("am kill com.example.mapper", "am force-stop com.netflix.ninja", "am kill com.example.reader"),
                Adb.stopCommands("app.aura.home", on, "com.example.mapper", "com.netflix.ninja", "app.aura.home",
                        "com.example.reader", "com.bad; reboot", null));
        Assert.assertEquals("No list of services: a real Force stop, as before",
                Arrays.asList("am force-stop com.netflix.ninja"), Adb.stopCommands("app.aura.home", null, "com.netflix.ninja"));
        Assert.assertEquals(Arrays.asList("am force-stop com.netflix.ninja"), Adb.stopCommands("app.aura.home", "", "com.netflix.ninja"));
    }

    @Test
    public void watchNextTimesWrittenInSecondsAreKeptInOrder() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.READ_TV_LISTINGS");
        Robolectric.buildContentProvider(WatchNextList.class).create(TvContract.AUTHORITY);
        long now = System.currentTimeMillis(), day = 86_400_000L;
        // in the order the TV's own list gives them: by the raw number, so times in seconds come last
        WatchNextList.rows = Arrays.asList(
                row("Two days ago", "com.example.millis", now - 2 * day),
                row("Two months ago", "com.example.millis", now - 61 * day),
                row("Yesterday", "com.example.seconds", (now - day) / 1000),
                row("Seconds, two months ago", "com.example.seconds", (now - 61 * day) / 1000));
        // read() itself: all() would keep this list for a minute, for whichever test asks next
        Method read = Continue.class.getDeclaredMethod("read", Context.class, int.class);
        read.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Continue.Item> got = (List<Continue.Item>) read.invoke(null, c, 8);
        List<String> titles = new ArrayList<>();
        for (Continue.Item it : got) titles.add(it.title);
        Assert.assertEquals(Arrays.asList("Yesterday", "Two days ago"), titles);
        Assert.assertEquals(1_700_000_000_000L, Continue.engagedAt(1_700_000_000L));
        Assert.assertEquals(1_700_000_000_000L, Continue.engagedAt(1_700_000_000_000L));
    }

    private static Object[] row(String title, String pkg, long engaged) {
        return new Object[]{title, null, null, null, null, null, 0L, 0L, "intent:#Intent;package=" + pkg + ";end", pkg,
                null, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, engaged};
    }

    /** The TV's listings, holding nothing but a Watch Next list. */
    public static class WatchNextList extends ContentProvider {
        static List<Object[]> rows = new ArrayList<>();

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cur = new MatrixCursor(projection);
            for (Object[] r : rows) cur.addRow(r);
            return cur;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }
}
