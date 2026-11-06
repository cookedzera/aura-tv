package app.aura.home;

/**
 * A thread for work behind the screen whose failure stays its own. Android ends the whole app when
 * any thread throws, so one bad network reply, one odd answer from a TV's firmware or one picture
 * too large took the home screen down with it. Here the error is noted in {@link Trouble} and only
 * this piece of work stops; the home screen goes on. Every thread Aura starts is one of these,
 * the executors' workers included ({@link Compat#background}).
 */
final class SafeThread extends Thread {
    private final Runnable onFail;

    SafeThread(Runnable r) {
        super(r);
        onFail = null;
    }

    SafeThread(Runnable r, String name) {
        this(r, name, null);
    }

    /**
     * With {@code onFail}, run on the main thread should the work throw: for a button that set a
     * "busy" state before starting the work, so it doesn't stay busy for good.
     */
    SafeThread(Runnable r, String name, Runnable onFail) {
        super(r, name);
        this.onFail = onFail;
    }

    @Override
    public void run() {
        try {
            super.run();
        } catch (Throwable e) {
            Trouble.caught(getName(), e);
            if (onFail != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onFail);
        }
    }
}
