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
    private Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() == OLD_FLYOVER_JOB) return false;
        worker = new Thread(() -> {
            // downloads and photo resizing, well behind whatever is on screen
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            retireScenes();
            // one small request, first: the photo import below spaces its requests and takes minutes
            Updater.notice(getApplicationContext());
            boolean complete = Daily.run(getApplicationContext());
            // then a few photographs for the wall, from the sources switched on
            complete &= Gallery.run(getApplicationContext());
            try {
                Picks.refresh(getApplicationContext(), false);
                Fresh.refresh(getApplicationContext());
            } catch (java.io.IOException e) {
                complete = false;
            } catch (InterruptedException e) {
                return;
            }
            // a one-off catch-up that hit a network failure asks to be retried later
            jobFinished(params, !complete && params.getJobId() == Daily.JOB_NOW);
        }, "daily-photos");
        worker.start();
        return true;
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
