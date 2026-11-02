package app.aura.home;

/**
 * A thread for work behind the screen whose failure stays its own. Android ends the whole app when
 * any thread throws, so one bad network reply, one odd answer from a TV's firmware or one picture
 * too large took the home screen down with it. Here the error is noted in {@link Trouble} and only
 * this piece of work stops; the home screen goes on. Every thread Aura starts is one of these,
 * the executors' workers included ({@link Compat#background}).
 */
final class SafeThread extends Thread {
    SafeThread(Runnable r) {
        super(r);
    }

    SafeThread(Runnable r, String name) {
        super(r, name);
    }

    @Override
    public void run() {
        try {
            super.run();
        } catch (Throwable e) {
            Trouble.caught(getName(), e);
        }
    }
}
