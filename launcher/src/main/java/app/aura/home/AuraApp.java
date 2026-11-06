package app.aura.home;

import android.app.Application;

/**
 * Aura's process, before any of its parts: the problem log and the crash watch go in first, so a
 * crash in the Home button service, the screensaver, the media listener or a background job is
 * kept too, not only one on the home screen.
 */
public class AuraApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Words.init(this);
        Trouble.init(this);
        Safety.watch(this);
        // phones and computers can play on the TV whenever Aura's process runs (the Home button
        // service keeps it running), not only while home is on screen
        Renderer.apply(this);
        // the process is back (a job, a cast, a remote command) after Android ended it for memory:
        // on Android 11+ that put the Home button service on a "crashed" list, where only a switch
        // off and on brings it back, and waiting for someone to open Aura's home screen could mean
        // a Home button that does nothing for hours
        if (android.os.Build.VERSION.SDK_INT >= 30 && HomeKeys.wanted(this)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!HomeKeys.live && HomeKeys.on(this) && HomeKeys.takeover(this)) HomeKeys.heal(this);
            }, 6000);
        }
    }
}
