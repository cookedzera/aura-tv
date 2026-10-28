package app.aura.home;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Runs the background imports off the main thread whenever the system grants the job: the
 * Picture of the Day, then (weekly) the pool of Tonight's picks.
 */
public class DailyJob extends JobService {
    private Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            // one small request, first: the photo import below spaces its requests and takes minutes
            Updater.notice(getApplicationContext());
            boolean complete = Daily.run(getApplicationContext());
            try {
                Picks.refresh(getApplicationContext());
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

    @Override
    public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
