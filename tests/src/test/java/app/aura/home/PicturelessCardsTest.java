package app.aura.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.robolectric.Shadows.shadowOf;

/**
 * An app whose Continue Watching pictures can't be read (Stremio's add-ons hand out plain http
 * addresses, which Android refuses Aura) still has its cards: what you're partway through is kept,
 * painted in the app's colour, as the Smart Stack already kept it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PicturelessCardsTest {
    private static final String STREMIO = "com.stremio.one";
    private Context c;

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.READ_TV_LISTINGS");
        Robolectric.buildContentProvider(Listings.class).create(TvContract.AUTHORITY);
        // nothing kept from another test's reading
        for (String f : new String[]{"cached", "cachedFor"}) {
            Field k = Continue.class.getDeclaredField(f);
            k.setAccessible(true);
            k.set(null, null);
        }
        TopShelf.clearCache();
    }

    @Test
    public void anAppsContinueWatchingWithoutAPictureStillHasItsCard() {
        // a poster at an http address nobody answers: no picture
        Listings.rows = Collections.singletonList(new Object[]{"The Expanse", null, "2", "5", "http://127.0.0.1:9/poster.jpg", null,
                600_000L, 2_400_000L, "intent:#Intent;package=" + STREMIO + ";end", STREMIO, null,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, System.currentTimeMillis() - 3_600_000L});
        List<Continue.Item> cards = TopShelf.items(c, STREMIO);
        List<String> titles = new ArrayList<>();
        for (Continue.Item it : cards) titles.add(it.title);
        Assert.assertEquals(Collections.singletonList("The Expanse"), titles);
        Continue.Item it = cards.get(0);
        Assert.assertNull("its picture couldn't be read", it.art);
        Assert.assertEquals(0.25f, it.progress, 0.001f);

        // painted in the app's own colour, not left black
        int violet = 0xFF8A5CF6;
        Bitmap b = Bitmap.createBitmap(400, 225, Bitmap.Config.ARGB_8888);
        TopShelf.paint(it, violet, null).paint(new Canvas(b), 400, 225);
        int top = b.getPixel(340, 30);
        Assert.assertTrue("a light of the app's colour at the top: " + Integer.toHexString(top),
                Color.blue(top) > Color.green(top) + 12 && Color.red(top) > Color.green(top));
    }

    @Test
    public void anHttpPictureIsAskedForSecurely() {
        Assert.assertEquals("https://images.example.com/p.jpg", Continue.secure("http://images.example.com/p.jpg"));
        Assert.assertEquals("https://images.example.com/p.jpg", Continue.secure("HTTP://images.example.com/p.jpg"));
        Assert.assertEquals("https://a.example/p.jpg", Continue.secure("https://a.example/p.jpg"));
        Assert.assertEquals("content://app/p.jpg", Continue.secure("content://app/p.jpg"));
    }

    /** The TV's listings: a Watch Next list, and no preview rows. */
    public static class Listings extends ContentProvider {
        static List<Object[]> rows = new ArrayList<>();

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cur = new MatrixCursor(projection);
            if (uri.getPath() != null && uri.getPath().contains("watch_next")) for (Object[] r : rows) cur.addRow(r);
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
