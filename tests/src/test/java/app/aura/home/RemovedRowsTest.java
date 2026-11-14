package app.aura.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.tv.TvContract;
import android.net.Uri;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.robolectric.Shadows.shadowOf;

/**
 * What you removed from the TV's own home screen (a show from Watch Next) doesn't come back in
 * Aura's Continue watching; a TV whose listings don't say which rows were removed still lists them all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class RemovedRowsTest {
    private static final String APP = "com.example.player";
    private Context c;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.READ_TV_LISTINGS");
        Robolectric.buildContentProvider(Listings.class).create(TvContract.AUTHORITY);
    }

    private static Object[] row(String title, int browsable) {
        return new Object[]{title, null, null, null, null, null, 600_000L, 2_400_000L,
                "intent:#Intent;package=" + APP + ";end", APP, null,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, System.currentTimeMillis() - 3_600_000L, browsable};
    }

    private List<String> titles() {
        Continue.clearCache();
        TopShelf.clearCache();
        List<String> out = new ArrayList<>();
        for (Continue.Item it : TopShelf.items(c, APP)) out.add(it.title);
        TopShelf.clearCache();
        return out;
    }

    @Test
    public void aShowRemovedFromTheTvsHomeStaysAway() {
        Listings.rows = Arrays.asList(row("Kept", 1), row("Removed", 0));
        Listings.knowsBrowsable = true;
        Assert.assertEquals(Arrays.asList("Kept"), titles());
    }

    @Test
    public void listingsWithoutTheColumnAreStillRead() {
        Listings.rows = Arrays.asList(row("Kept", 1), row("Removed", 0));
        Listings.knowsBrowsable = false;
        Assert.assertEquals(Arrays.asList("Kept", "Removed"), titles());
    }

    /** The TV's listings: a Watch Next list; with {@link #knowsBrowsable} off, the column is refused as an unknown one. */
    public static class Listings extends ContentProvider {
        static List<Object[]> rows = new ArrayList<>();
        static boolean knowsBrowsable;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            boolean asks = Arrays.asList(projection).contains("browsable");
            if (asks && !knowsBrowsable) throw new IllegalArgumentException("Invalid column browsable");
            MatrixCursor cur = new MatrixCursor(projection);
            if (uri.getPath() != null && uri.getPath().contains("watch_next")) {
                for (Object[] r : rows) cur.addRow(asks ? r : Arrays.copyOf(r, projection.length));
            }
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
