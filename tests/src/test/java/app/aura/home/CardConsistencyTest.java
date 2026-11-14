package app.aura.home;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.robolectric.Shadows.shadowOf;

/**
 * Verifies consistency across Watch Next, preview channels, Watched, TopShelf, tvOS Up Next,
 * and Smart Stack cards: progress bounds, picture-less partway cards, kids' profile filtering,
 * duplicate title deduplication, and cache invalidation on return from an app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CardConsistencyTest {
    private static final String APP_A = "com.example.streama";
    private static final String APP_B = "com.example.streamb";
    private static final String HIDDEN_APP = "com.example.adult";
    private static final String YT_TV = "com.google.android.youtube.tv";
    private static final String SMART_TUBE = "com.liskovsoft.smarttubetv";

    private Context c;
    private String artUri;
    private ActivityController<HomeActivity> ac;

    public static final class TvStub extends ContentProvider {
        static File artFile;
        static final List<Map<String, Object>> watchNext = new ArrayList<>();
        static final List<Map<String, Object>> previews = new ArrayList<>();
        static final List<Map<String, Object>> channels = new ArrayList<>();
        static final AtomicInteger watchNextQueries = new AtomicInteger();
        static final AtomicInteger previewQueries = new AtomicInteger();
        static final AtomicInteger channelQueries = new AtomicInteger();

        @Override public boolean onCreate() { return true; }

        @Override
        public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return android.os.ParcelFileDescriptor.open(artFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            String path = uri.getPath();
            List<Map<String, Object>> src;
            if (path != null && path.contains("watch_next")) {
                watchNextQueries.incrementAndGet();
                src = watchNext;
            } else if (path != null && path.contains("preview")) {
                previewQueries.incrementAndGet();
                src = previews;
            } else {
                channelQueries.incrementAndGet();
                src = channels;
            }
            MatrixCursor mc = new MatrixCursor(projection);
            for (Map<String, Object> row : src) {
                Object[] vals = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) {
                    String col = projection[i];
                    if (row.containsKey(col)) vals[i] = row.get(col);
                    else if ("browsable".equals(col)) vals[i] = 1;
                }
                mc.addRow(vals);
            }
            return mc;
        }

        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        resetStatics();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().clear().commit();
        c.getSharedPreferences("aura_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        c.getSharedPreferences("aura_taste", Context.MODE_PRIVATE).edit().clear().commit();
        Profiles.forget();

        shadowOf((android.app.Application) c).grantPermissions("android.permission.READ_TV_LISTINGS");
        TvStub.watchNext.clear();
        TvStub.previews.clear();
        TvStub.channels.clear();
        TvStub.watchNextQueries.set(0);
        TvStub.previewQueries.set(0);
        TvStub.channelQueries.set(0);
        Robolectric.buildContentProvider(TvStub.class).create(TvContract.AUTHORITY);

        installTvApp(APP_A, "StreamA");
        installTvApp(APP_B, "StreamB");
        installTvApp(HIDDEN_APP, "AdultApp");
        installTvApp(YT_TV, "YouTube");
        installTvApp(SMART_TUBE, "SmartTube");

        File img = new File(c.getCacheDir(), "card_art.png");
        Bitmap b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF336699);
        try (FileOutputStream fos = new FileOutputStream(img)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        TvStub.artFile = img;
        artUri = "content://android.media.tv/art";
        new File(c.getFilesDir(), "fresh/fresh3.json").delete();
        new File(c.getFilesDir(), "picks/picks2.json").delete();
    }

    @After
    public void tearDown() throws Exception {
        if (ac != null) ac.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
        resetStatics();
        Profiles.use(c, Profiles.MAIN);
        new File(c.getFilesDir(), "fresh/fresh3.json").delete();
        new File(c.getFilesDir(), "picks/picks2.json").delete();
    }

    private void resetStatics() throws Exception {
        Continue.clearCache();
        TopShelf.clearCache();
        Profiles.forget();
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
        Field lastAny = MediaWatch.class.getDeclaredField("lastAny");
        lastAny.setAccessible(true);
        lastAny.set(null, null);
        Field byPkg = MediaWatch.class.getDeclaredField("byPkg");
        byPkg.setAccessible(true);
        ((Map<?, ?>) byPkg.get(null)).clear();
    }

    private void installTvApp(String pkg, String label) {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new android.content.pm.ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        pi.applicationInfo.name = label;
        pi.applicationInfo.nonLocalizedLabel = label;
        pi.applicationInfo.enabled = true;
        pm.installPackage(pi);
        ComponentName cn = new ComponentName(pkg, pkg + ".Main");
        pm.addActivityIfNotPresent(cn);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        f.addCategory(Intent.CATEGORY_LAUNCHER);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(cn, f);
    }

    private Map<String, Object> wnRow(String pkg, String title, long pos, long dur, String season, String ep, String epTitle, String art, long touched) {
        Map<String, Object> m = new HashMap<>();
        m.put(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, pkg);
        m.put(TvContract.WatchNextPrograms.COLUMN_TITLE, title);
        m.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, pos);
        m.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, dur);
        m.put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, season);
        m.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, ep);
        m.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, epTitle);
        m.put(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, art);
        m.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/" + Continue.key(title))).setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME));
        m.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        m.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, touched);
        return m;
    }

    private Map<String, Object> previewRow(long channelId, String pkg, String title, long pos, long dur, String season, String ep, String desc, String art) {
        Map<String, Object> m = new HashMap<>();
        m.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId);
        m.put(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, pkg);
        m.put(TvContract.PreviewPrograms.COLUMN_TITLE, title);
        m.put(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, pos);
        m.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, dur);
        m.put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, season);
        m.put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, ep);
        m.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, desc);
        m.put(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, art);
        m.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/" + Continue.key(title))).setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME));
        return m;
    }

    private String makeKidsProfile(String hiddenPkg) throws Exception {
        JSONObject main = new JSONObject().put("id", Profiles.MAIN).put("name", "Me").put("color", 0xFF6E8BFF).put("kids", false);
        JSONObject kid = new JSONObject().put("id", "kid1").put("name", "Kids").put("color", 0xFFFF8A65).put("kids", true);
        c.getSharedPreferences("aura_profiles", Context.MODE_PRIVATE).edit()
                .putString("list", new JSONArray().put(main).put(kid).toString())
                .commit();
        Profiles.forget();
        Profiles.use(c, "kid1");
        Prefs.hide(c, hiddenPkg, true);
        return "kid1";
    }

    private File writeArtFile(String name) throws Exception {
        File f = new File(c.getCacheDir(), name);
        Bitmap b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF4477AA);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        return f;
    }

    @Test
    public void progressThresholdsAndNegativePosAreConsistent() {
        long now = System.currentTimeMillis();
        TvStub.watchNext.add(wnRow(APP_A, "Neg Show", -500L, 600_000L, null, null, null, artUri, now));
        TvStub.watchNext.add(wnRow(APP_A, "Tiny Show", 5_000L, 600_000L, "1", "1", "Pilot", artUri, now - 10));
        TvStub.watchNext.add(wnRow(APP_A, "Finished Show", 580_000L, 600_000L, "1", "2", "Finale", artUri, now - 20));
        TvStub.watchNext.add(wnRow(APP_A, "Halfway Show", 1_200_000L, 2_400_000L, "1", "3", "Mid", artUri, now - 30));

        List<Continue.Item> wn = Continue.all(c, 8);
        Map<String, Continue.Item> byTitle = new HashMap<>();
        for (Continue.Item it : wn) byTitle.put(it.title, it);

        Assert.assertTrue("negative pos stays non-negative", byTitle.get("Neg Show").progress == 0f);
        Assert.assertEquals("no leftMin for negative pos", 0L, byTitle.get("Neg Show").leftMin);
        Assert.assertEquals("sub-2% progress treated as unstarted", 0f, byTitle.get("Tiny Show").progress, 0.0001f);
        Assert.assertEquals("no N min left below 2%", 0L, byTitle.get("Tiny Show").leftMin);
        Assert.assertTrue("below 2% keeps episode title when available", byTitle.get("Tiny Show").sub.contains("Pilot"));
        Assert.assertFalse("finished (>95%) Watch Next row is dropped", byTitle.containsKey("Finished Show"));
        Assert.assertEquals(0.5f, byTitle.get("Halfway Show").progress, 0.01f);
        Assert.assertEquals("S1  ·  E3  ·  " + Words.get(R.string.min_left, 20), byTitle.get("Halfway Show").sub);

        Map<String, Object> ch = new HashMap<>();
        ch.put(TvContract.Channels._ID, 11L);
        ch.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Continue Watching");
        ch.put(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, APP_B);
        TvStub.channels.add(ch);
        TvStub.previews.add(previewRow(11L, APP_B, "Channel Tiny", 5_000L, 600_000L, null, null, "Channel Tiny", artUri));
        TvStub.previews.add(previewRow(11L, APP_B, "Channel Finished", 585_000L, 600_000L, "2", "4", "   ", artUri));

        List<Continue.Item> pvs = Continue.previews(c, APP_B, 8);
        Assert.assertEquals(2, pvs.size());
        Assert.assertEquals(0f, pvs.get(0).progress, 0.0001f);
        Assert.assertEquals("no leftMin on >95% preview", 0L, pvs.get(1).leftMin);

        List<Continue.Item> chItems = Continue.channels(c, Collections.singletonList(APP_B), Collections.emptySet(), 4);
        Assert.assertEquals(1, chItems.size());
        Assert.assertEquals("title-equal/blank shortDesc excluded from channel subline", "", chItems.get(0).sub);
    }

    @Test
    public void partwayItemsWithoutPicturesKeptAcrossTopShelfAndChannels() throws Exception {
        long now = System.currentTimeMillis();
        // 1. Previews & Channels: partway item with NO picture URI is still kept
        Map<String, Object> ch = new HashMap<>();
        ch.put(TvContract.Channels._ID, 12L);
        ch.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Continue Watching");
        ch.put(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, APP_B);
        TvStub.channels.add(ch);
        TvStub.previews.add(previewRow(12L, APP_B, "Partway Preview No Art", 900_000L, 1_800_000L, "1", "1", null, null));

        List<Continue.Item> pvs = Continue.previews(c, APP_B, 4);
        Assert.assertEquals("picture-less partway preview kept", 1, pvs.size());
        Assert.assertNull(pvs.get(0).art);
        List<Continue.Item> chs = Continue.channels(c, Collections.singletonList(APP_B), Collections.emptySet(), 4);
        Assert.assertEquals("picture-less partway channel kept", 1, chs.size());

        // 2. TopShelf.items keeps Watched.recent partway item even when art == null
        JSONArray watched = new JSONArray().put(new JSONObject()
                .put("pkg", APP_A)
                .put("title", "Watched No Art")
                .put("by", "S1E1")
                .put("length", 3_600_000L)
                .put("position", 1_800_000L)
                .put("at", now));
        Prefs.device(c).edit().putString("watched", watched.toString()).commit();
        TopShelf.clearCache();
        List<Continue.Item> shelf = TopShelf.items(c, APP_A);
        Assert.assertEquals("TopShelf keeps picture-less Watched item", 1, shelf.size());
        Assert.assertEquals("Watched No Art", shelf.get(0).title);

        // 3. tvOS Up Next keeps picture-less Watch Next items AND falls back to Watched.recent when Watch Next is empty
        Prefs.of(c).edit()
                .putString(Prefs.LAYOUT, "tvos")
                .putBoolean(Prefs.DOCK_WIDGETS, false)
                .putStringSet(Prefs.WIDGETS_HIDDEN, new HashSet<>(Arrays.asList("sky", "stack", "today")))
                .commit();
        TvStub.watchNext.add(wnRow(APP_A, "WN No Art", 900_000L, 1_800_000L, null, null, null, null, now));
        Continue.clearCache();
        TopShelf.clearCache();
        List<Continue.Item> tvosWn = TopShelf.items(c, YT_TV);
        List<String> tvosTitles = new ArrayList<>();
        for (Continue.Item it : tvosWn) tvosTitles.add(it.title);
        Assert.assertEquals("tvOS Up Next keeps picture-less Watch Next item + Watched fallback",
                Arrays.asList("WN No Art", "Watched No Art"), tvosTitles);

        TvStub.watchNext.clear();
        Continue.clearCache();
        TopShelf.clearCache();
        List<Continue.Item> tvosWatched = TopShelf.items(c, YT_TV);
        Assert.assertEquals("tvOS Up Next falls back to Watched.recent", 1, tvosWatched.size());
        Assert.assertEquals("Watched No Art", tvosWatched.get(0).title);
    }

    @Test
    public void tvosFallbackRespectsKidsProfileAndCachesAreProfileKeyed() throws Exception {
        long now = System.currentTimeMillis();
        // Parent profile has YouTube preview row populated into cache first
        TvStub.previews.add(previewRow(20L, YT_TV, "Grownup Video", 600_000L, 1_200_000L, null, null, "Adult", artUri));
        List<Continue.Item> parentPreviews = Continue.previews(c, SMART_TUBE, 4);
        Assert.assertEquals(1, parentPreviews.size());

        // Seed last media playing from HIDDEN_APP
        Prefs.device(c).edit()
                .putString("media_pkg", HIDDEN_APP)
                .putString("media_title", "Restricted Movie")
                .putString("media_by", "Adults")
                .putFloat("media_prog", 0.4f)
                .putLong("media_at", now)
                .commit();

        makeKidsProfile(HIDDEN_APP);
        Prefs.hide(c, YT_TV, true);
        Prefs.of(c).edit()
                .putString(Prefs.LAYOUT, "tvos")
                .putBoolean(Prefs.DOCK_WIDGETS, false)
                .putStringSet(Prefs.WIDGETS_HIDDEN, new HashSet<>(Arrays.asList("sky", "stack", "today")))
                .commit();

        // Kids' SmartTube previews must not see cached YouTube rows from parent profile
        List<Continue.Item> kidsPreviews = Continue.previews(c, SMART_TUBE, 4);
        Assert.assertTrue("hidden YouTube rows must not leak via cached SmartTube previews", kidsPreviews.isEmpty());

        // Kids' tvOS fallback must not surface hidden app's MediaWatch.nowOrLast
        List<Continue.Item> kidsShelf = TopShelf.items(c, APP_A);
        for (Continue.Item it : kidsShelf) {
            Assert.assertNotEquals("Restricted Movie", it.title);
        }
    }

    @Test
    public void duplicateTitlesCollapsedAcrossTopShelfUpNextAndSmartStack() throws Exception {
        long now = System.currentTimeMillis();
        File freshImg = writeArtFile("fresh_dune.png");
        File pickImg = writeArtFile("pick_dune.png");

        File freshDir = new File(c.getFilesDir(), "fresh");
        freshDir.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(freshDir, "fresh3.json"))) {
            String arr = new JSONArray().put(new JSONObject()
                    .put("name", "Dune Part Two")
                    .put("episode", "Special")
                    .put("channel", "StreamA")
                    .put("pkg", APP_A)
                    .put("art", freshImg.getAbsolutePath())).toString();
            fos.write(arr.getBytes("UTF-8"));
        }

        File picksDir = new File(c.getFilesDir(), "picks");
        picksDir.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(picksDir, "picks2.json"))) {
            String arr = new JSONArray().put(new JSONObject()
                    .put("name", "Dune: Part Two")
                    .put("line", "Sci-fi")
                    .put("about", "Desc")
                    .put("poster", pickImg.getAbsolutePath())
                    .put("query", "Dune")
                    .put("why", "trending")
                    .put("yt", "")
                    .put("src", "tmdb")
                    .put("back", "")
                    .put("tmdb", "1")
                    .put("type", "movie")).toString();
            fos.write(arr.getBytes("UTF-8"));
        }

        // 1. TopShelf.items: Watch Next + Fresh + MediaWatch punctuation duplicate -> only one entry
        TvStub.watchNext.add(wnRow(APP_A, "Dune: Part Two", 1_000_000L, 2_000_000L, null, null, null, artUri, now));
        Prefs.device(c).edit()
                .putString("media_pkg", APP_A)
                .putString("media_title", "Dune Part Two")
                .putFloat("media_prog", 0.5f)
                .putLong("media_at", now)
                .commit();
        List<Continue.Item> shelf = TopShelf.items(c, APP_A);
        int matches = 0;
        for (Continue.Item it : shelf) if ("duneparttwo".equals(Continue.key(it.title))) matches++;
        Assert.assertEquals("TopShelf collapses duplicate title across MediaWatch/WN/Fresh", 1, matches);

        // 2. tvOS Up Next: Watch Next + Tonight's Pick duplicate -> Pick is not duplicated
        Prefs.device(c).edit().remove("media_pkg").remove("media_title").commit();
        Prefs.of(c).edit()
                .putString(Prefs.LAYOUT, "tvos")
                .putBoolean(Prefs.DOCK_WIDGETS, false)
                .putStringSet(Prefs.WIDGETS_HIDDEN, new HashSet<>(Arrays.asList("sky", "stack", "today")))
                .putBoolean(Prefs.PICKS, true)
                .commit();
        TopShelf.clearCache();
        Continue.clearCache();
        List<Continue.Item> upNext = TopShelf.items(c, APP_B);
        int upNextMatches = 0;
        for (Continue.Item it : upNext) if ("duneparttwo".equals(Continue.key(it.title))) upNextMatches++;
        Assert.assertEquals("tvOS Up Next does not duplicate Watch Next title with Tonight's Pick", 1, upNextMatches);

        // 3. HomeActivity.applyStack: Watch Next + Fresh + Pick duplicate -> single SmartStack slide
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ac = Robolectric.buildActivity(HomeActivity.class).create().start().resume();
        HomeActivity ha = ac.get();
        List<Continue.Item> cw = Continue.all(c, 4);
        Method makeExtras = HomeActivity.class.getDeclaredMethod("makeExtras", Context.class);
        makeExtras.setAccessible(true);
        Object extras = makeExtras.invoke(ha, c);
        Method applyStack = null;
        for (Method m : HomeActivity.class.getDeclaredMethods()) {
            if ("applyStack".equals(m.getName())) {
                applyStack = m;
                break;
            }
        }
        Assert.assertNotNull(applyStack);
        applyStack.setAccessible(true);
        applyStack.invoke(ha, cw, Collections.emptyList(), Collections.emptyMap(), null, null, now, extras, Collections.emptySet(), Taste.view(c), Collections.emptyList(), -1L);
        Field stackField = HomeActivity.class.getDeclaredField("stack");
        stackField.setAccessible(true);
        SmartStack stack = (SmartStack) stackField.get(ha);
        Field slidesField = SmartStack.class.getDeclaredField("slides");
        slidesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SmartStack.Slide> slides = (List<SmartStack.Slide>) slidesField.get(stack);
        int stackMatches = 0;
        for (SmartStack.Slide s : slides) if ("duneparttwo".equals(s.key)) stackMatches++;
        Assert.assertEquals("SmartStack collapses duplicate titles across WN/Fresh/Picks", 1, stackMatches);
    }

    @Test
    public void continueCacheClearsAllSourcesAndOnlyClearsOnStopToResume() throws Exception {
        long now = System.currentTimeMillis();
        Map<String, Object> ch = new HashMap<>();
        ch.put(TvContract.Channels._ID, 30L);
        ch.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Continue Watching");
        ch.put(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, APP_A);
        TvStub.channels.add(ch);
        TvStub.watchNext.add(wnRow(APP_A, "First Title", 500_000L, 1_000_000L, null, null, null, artUri, now));
        TvStub.previews.add(previewRow(30L, APP_A, "First Preview", 500_000L, 1_000_000L, null, null, null, artUri));

        Assert.assertEquals("First Preview", Continue.previews(c, APP_A, 4).get(0).title);
        Assert.assertEquals("First Preview", Continue.channels(c, Collections.singletonList(APP_A), Collections.emptySet(), 4).get(0).title);

        TvStub.previews.clear();
        TvStub.previews.add(previewRow(30L, APP_A, "Updated Preview", 600_000L, 1_000_000L, null, null, null, artUri));
        Continue.clearCache();
        Assert.assertEquals("clearCache refreshes previews", "Updated Preview", Continue.previews(c, APP_A, 4).get(0).title);
        Assert.assertEquals("clearCache refreshes channels", "Updated Preview", Continue.channels(c, Collections.singletonList(APP_A), Collections.emptySet(), 4).get(0).title);

        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ac = Robolectric.buildActivity(HomeActivity.class).create().start().resume();
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
        Continue.all(c, 4);
        int wnBeforeHomePress = TvStub.watchNextQueries.get();
        int pvBeforeHomePress = TvStub.previewQueries.get();

        // Pause -> Resume WITHOUT stop (Home key while already on Aura / overlay dismissal): keep caches!
        ac.pause().resume();
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
        Continue.all(c, 4);
        Continue.previews(c, APP_A, 4);
        Assert.assertEquals("pause->resume without stop must not re-read Watch Next", wnBeforeHomePress, TvStub.watchNextQueries.get());
        Assert.assertEquals("pause->resume without stop must not re-read Previews", pvBeforeHomePress, TvStub.previewQueries.get());

        // Stop -> Restart -> Start -> Resume (returning from another app): caches cleared, new data read!
        TvStub.watchNext.clear();
        TvStub.watchNext.add(wnRow(APP_A, "After App Title", 700_000L, 1_000_000L, null, null, null, artUri, now + 5000));
        ac.pause().stop();
        ac.restart().start().resume();
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
        Assert.assertEquals("return from stopped app refreshes Watch Next", "After App Title", Continue.all(c, 4).get(0).title);
    }
}
