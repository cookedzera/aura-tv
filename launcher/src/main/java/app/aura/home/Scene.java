package app.aura.home;

import android.content.Context;
import android.view.View;

/**
 * A screensaver scene that draws itself (the sky, the aurora, rain on glass, the photo mosaic,
 * the gradient clock, a festival). Started when the screensaver starts, stopped when it ends or
 * moves on to another scene; it may hand the story card a caption now and then.
 */
abstract class Scene extends View {
    interface Listener {
        void onStory(String context, String title, String line, String credit, long ms);
    }

    protected Listener listener;

    Scene(Context c) {
        super(c);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** True when the scene shows its own clock, and the story card's should step aside. */
    boolean ownClock() {
        return false;
    }

    abstract void start();

    abstract void stop();
}
