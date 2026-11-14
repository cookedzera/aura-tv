package app.aura.home;

import android.Manifest;
import android.app.Application;
import android.app.job.JobParameters;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.Process;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowJobService;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BootThrottleTest {

    private ActivityController<HomeActivity> homeController;

    @Before
    public void setUp() throws Exception {
        resetStatics();
    }

    @After
    public void tearDown() throws Exception {
        if (homeController != null) {
            try {
                homeController.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
            homeController = null;
        }
        resetStatics();
        ShadowSystemClock.reset();
    }

    private static void resetStatics() throws Exception {
        Continue.clearCache();
        TopShelf.clearCache();
        Profiles.forget();
        setStatic(HomeActivity.class, "cache", null);
        setStatic(MediaWatch.class, "lastAny", null);
        Object byPkg = getStatic(MediaWatch.class, "byPkg");
        if (byPkg instanceof Map) ((Map<?, ?>) byPkg).clear();
        setStatic(Cec.class, "readAt", -60_000L);
        setStatic(Cec.class, "reading", false);
        setStatic(Cec.class, "names", new HashMap<>());
        setStatic(Cec.class, "types", new HashMap<>());
        setStaticIfPresent(Cec.class, "hdmiMissing", false);
        setStaticIfPresent(Cec.class, "retryMs", 30_000L);
        Object busy = getStatic(DailyJob.class, "busy");
        if (busy instanceof AtomicBoolean) ((AtomicBoolean) busy).set(false);
        HomeKeys.live = false;
        Application app = RuntimeEnvironment.getApplication();
        deleteDir(new File(app.getFilesDir(), "apps"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteDir(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void setStatic(Class<?> cls, String name, Object val) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, val);
    }

    private static void setStaticIfPresent(Class<?> cls, String name, Object val) throws Exception {
        try {
            setStatic(cls, name, val);
        } catch (NoSuchFieldException ignored) {
        }
    }

    private static Object getStatic(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static JobParameters makeJobParams(int jobId) throws Exception {
        Constructor<JobParameters> ctor = JobParameters.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        JobParameters jp = ctor.newInstance();
        Field f = JobParameters.class.getDeclaredField("jobId");
        f.setAccessible(true);
        f.setInt(jp, jobId);
        return jp;
    }

    private static Drawable recordingDrawable(
            Set<String> observedLiveThreads, List<Integer> threadPriorities, CountDownLatch loaded) {
        return new ColorDrawable(0xFF335577) {
            private void record() {
                if (!"apps".equals(Thread.currentThread().getName())) return;
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    String n = t.getName();
                    if ("apps-first".equals(n) || "home".equals(n) || "apps".equals(n)) {
                        observedLiveThreads.add(n);
                    }
                }
                threadPriorities.add(Process.getThreadPriority(Process.myTid()));
                if (loaded != null) loaded.countDown();
            }

            @Override
            public ConstantState getConstantState() {
                // AppRepo.colorOf clones drawables via getConstantState(); return null so our draw() hook runs.
                return null;
            }

            @Override
            public int getIntrinsicWidth() {
                record();
                return 64;
            }

            @Override
            public int getIntrinsicHeight() {
                record();
                return 64;
            }

            @Override
            public void draw(Canvas canvas) {
                record();
                super.draw(canvas);
            }
        };
    }

    private static void registerTvApp(Application app, String pkg, String label, Drawable drawable) {
        ShadowPackageManager spm = Shadows.shadowOf(app.getPackageManager());
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        pi.applicationInfo.name = label;
        pi.applicationInfo.nonLocalizedLabel = label;
        pi.applicationInfo.icon = 1;
        pi.applicationInfo.enabled = true;
        spm.installPackage(pi);

        ComponentName cn = new ComponentName(pkg, pkg + ".Main");
        ActivityInfo ai = spm.addActivityIfNotPresent(cn);
        ai.packageName = pkg;
        ai.name = pkg + ".Main";
        ai.nonLocalizedLabel = label;
        ai.icon = 1;
        ai.enabled = true;
        ai.exported = true;
        ai.applicationInfo = pi.applicationInfo;

        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        spm.addIntentFilterForActivity(cn, f);

        spm.addActivityIcon(cn, drawable);
        spm.setApplicationIcon(pkg, drawable);
        spm.addDrawableResolution(pkg, 1, drawable);
    }

    @Test
    public void cecStopsProbingMissingHdmiControlServiceAndBacksOffEmptyReports() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ShadowApplication sa = Shadows.shadowOf(app);
        sa.grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.DUMP);

        AtomicInteger hdmiCalls = new AtomicInteger();
        Context counting = new ContextWrapper(app) {
            @Override
            public Object getSystemService(String name) {
                if ("hdmi_control".equals(name)) {
                    hdmiCalls.incrementAndGet();
                    return null;
                }
                return super.getSystemService(name);
            }
        };

        for (int i = 0; i < 5; i++) {
            Assert.assertFalse(Cec.externalActiveSource(counting));
        }
        Assert.assertEquals("HDMI 1", Cec.name(counting, "HDMI 1"));
        Assert.assertEquals(Cec.UNKNOWN, Cec.type(counting, "HDMI 1"));

        long deadline = System.currentTimeMillis() + 3_000L;
        while ((boolean) getStatic(Cec.class, "reading") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long firstReadAt = (long) getStatic(Cec.class, "readAt");

        ShadowSystemClock.advanceBy(Duration.ofSeconds(35));
        Assert.assertEquals("HDMI 1", Cec.name(counting, "HDMI 1"));
        Assert.assertFalse(Cec.externalActiveSource(counting));
        long secondReadAt = (long) getStatic(Cec.class, "readAt");

        Assert.assertEquals("missing hdmi_control service must be latched after first probe", 1, hdmiCalls.get());
        Assert.assertEquals("empty hdmi_control report must back off well past 30 s", firstReadAt, secondReadAt);
    }

    @Test
    public void dailyJobDefersBackgroundSyncDuringBootWindowAndRunsHealOrPostBoot() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Assert.assertTrue("test precondition: must be in justBooted window", Compat.justBooted());

        DailyJob job = Robolectric.buildService(DailyJob.class).create().get();
        JobParameters dailyParams = makeJobParams(41); // Daily.JOB_DAILY
        boolean deferHandled = job.onStartJob(dailyParams);
        ShadowJobService sjs = Shadows.shadowOf(job);

        Field workerField = DailyJob.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        Assert.assertTrue("JOB_DAILY stays active until jobFinished(true)", deferHandled);
        Assert.assertTrue("JOB_DAILY must reschedule itself during boot window",
                sjs.getIsJobFinished() && sjs.getIsRescheduleNeeded());
        Assert.assertNull("daily-photos thread must not start while justBooted()", workerField.get(job));

        // HEAL_JOB (Home button watchdog) must NOT be suppressed during boot.
        ShadowApplication sa = Shadows.shadowOf(app);
        sa.grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        Prefs.device(app).edit().putBoolean("keys_wanted", true).commit();
        JobParameters healParams = makeJobParams(DailyJob.HEAL_JOB);
        Assert.assertTrue("HEAL_JOB must still arm after boot", job.onStartJob(healParams));
        job.onStopJob(healParams);

        // Advance past Compat.justBooted() (> 4 min) and verify JOB_DAILY starts normally.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        Assert.assertFalse(Compat.justBooted());
        boolean postBootHandled = job.onStartJob(dailyParams);
        Assert.assertTrue(postBootHandled);
        Thread postBootWorker = (Thread) workerField.get(job);
        Assert.assertNotNull("JOB_DAILY must run once past the boot window", postBootWorker);
        job.onStopJob(dailyParams);
        postBootWorker.join(1_000L);
    }

    @Test
    public void appRepoAndHomeStartupUseBackgroundThreadsWhenSavedIndexExists() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String pkg = "com.example.savedboot";
        Bitmap bmp = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        Drawable savedDrawable = new BitmapDrawable(app.getResources(), bmp);

        AppRepo.App savedItem = new AppRepo.App();
        savedItem.pkg = pkg;
        savedItem.label = "Saved App";
        savedItem.cls = pkg + ".Main";
        savedItem.category = Intent.CATEGORY_LEANBACK_LAUNCHER;
        savedItem.color = 0xFF335577;
        savedItem.banner = savedDrawable;
        savedItem.light = Perf.light(app);
        AppRepo.save(app, Collections.singletonList(savedItem));
        Assert.assertNotNull("saved list must round-trip before test", AppRepo.saved(app));

        Set<String> repoThreads = new CopyOnWriteArraySet<>();
        List<Integer> repoPrios = new CopyOnWriteArrayList<>();
        registerTvApp(app, pkg, "Saved App", recordingDrawable(repoThreads, repoPrios, null));

        List<AppRepo.App> loaded = AppRepo.load(app);
        boolean foundPkg = false;
        for (AppRepo.App a : loaded) if (pkg.equals(a.pkg)) foundPkg = true;
        Assert.assertTrue("AppRepo.load must find registered app " + pkg, foundPkg);
        Assert.assertFalse("PackageManager loader should have run on apps thread", repoPrios.isEmpty());
        for (int prio : repoPrios) {
            Assert.assertTrue("AppRepo.load pool must drop to background priority (>= 10) when saved index exists, was " + prio,
                    prio >= Process.THREAD_PRIORITY_BACKGROUND);
        }

        repoThreads.clear();
        repoPrios.clear();
        CountDownLatch scanRan = new CountDownLatch(1);
        Drawable secondDrawable = recordingDrawable(repoThreads, repoPrios, scanRan);
        ShadowPackageManager spm = Shadows.shadowOf(app.getPackageManager());
        spm.addActivityIcon(new ComponentName(pkg, pkg + ".Main"), secondDrawable);
        spm.setApplicationIcon(pkg, secondDrawable);
        spm.addDrawableResolution(pkg, 1, secondDrawable);

        homeController = Robolectric.buildActivity(HomeActivity.class).create().start().resume();
        Assert.assertTrue("background reload should finish quickly in test", scanRan.await(5, TimeUnit.SECONDS));
        Assert.assertFalse("Home startup with saved index must not spawn apps-first, saw " + repoThreads,
                repoThreads.contains("apps-first"));
        for (int prio : repoPrios) {
            Assert.assertTrue("Home startup full scan with saved index must run at background priority (>= 10), was " + prio,
                    prio >= Process.THREAD_PRIORITY_BACKGROUND);
        }
    }
}
