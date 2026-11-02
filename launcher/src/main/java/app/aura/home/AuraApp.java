package app.aura.home;

import android.app.Application;

/**
 * Aura's process, before any of its parts: the problem log and the crash watch go in first, so a
 * crash in the Home button service, the screensaver, the media listener or a background job is
 * counted and kept too, not only one on the home screen.
 */
public class AuraApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Trouble.init(this);
        Safety.watch(this);
    }
}
