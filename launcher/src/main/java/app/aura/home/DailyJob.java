package app.aura.home;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs the Picture of the Day import off the main thread whenever the system grants the job. */
public class DailyJob extends JobService {
    private Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean complete = Daily.run(getApplicationContext());
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
