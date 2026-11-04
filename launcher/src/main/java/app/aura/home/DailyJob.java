package app.aura.home;

import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;

import java.io.File;

/**
 * Runs the background imports off the main thread whenever the system grants the job: a few new
 * photographs for the wall, then (weekly) the pool of Tonight's picks.
 */
public class DailyJob extends JobService {
    /** The space station flyovers' download job, from before the screensaver kept only photographs and quotes. */
    private static final int OLD_FLYOVER_JOB = 43;
    private static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    private Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() == OLD_FLYOVER_JOB || !busy.compareAndSet(false, true)) return false;
        final android.content.Context c = getApplicationContext();
        worker = new SafeThread(() -> {
            try {
                // downloads and photo resizing, well behind whatever is on screen
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                boolean complete = true;
                // each step on its own: one that fails (offline, a source that changed its answers)
                // no longer skips the ones after it, and Android always hears the job is done
                step("retire", () -> {
                    retireScenes();
                    return true;
                });
                // one small request, first: the photo import below spaces its requests and takes minutes
                step("update-check", () -> {
                    Updater.notice(c);
                    return true;
                });
                complete &= step("daily", () -> Daily.run(c));
                // then a few photographs for the wall, from the sources switched on
                complete &= step("photos", () -> Gallery.run(c));
                complete &= step("picks", () -> {
                    Picks.refresh(c, false);
                    return true;
                });
                complete &= step("new-today", () -> {
                    Fresh.refresh(c);
                    return true;
                });
                // stopped by Android (onStopJob): it already knows
                if (Thread.currentThread().isInterrupted()) return;
                // a one-off catch-up that hit a network failure asks to be retried later
                jobFinished(params, !complete && params.getJobId() == Daily.JOB_NOW);
            } finally {
                busy.set(false);
            }
        }, "daily-photos");
        worker.start();
        return true;
    }

    private interface Step {
        boolean run() throws Exception;
    }

    /** One step of the job: false when it couldn't finish. Being offline is normal; anything else is noted. */
    private static boolean step(String name, Step s) {
        if (Thread.currentThread().isInterrupted()) return false;
        try {
            return s.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.io.IOException e) {
            return false;
        } catch (Throwable e) {
            Trouble.caught("daily job: " + name, e);
            return false;
        }
    }

    /**
     * The flyover videos and museum pictures of the old screensaver scenes are let go, once: the
     * videos alone could take hundreds of megabytes.
     */
    private void retireScenes() {
        JobScheduler js = Compat.svc(this, JobScheduler.class);
        if (js != null) js.cancel(OLD_FLYOVER_JOB);
        for (String dir : new String[]{"flyovers", "museum"}) delete(new File(getFilesDir(), dir));
    }

    private static void delete(File f) {
        File[] inside = f.listFiles();
        for (int i = 0; inside != null && i < inside.length; i++) delete(inside[i]);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
