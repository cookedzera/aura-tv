package app.aura.home;

import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.view.animation.AnimationUtils;

/**
 * A damped spring toward a target that may move while it runs. Retargeting starts again from the
 * current position and speed, so a held key or a quick second press never restarts motion from
 * rest (a restarted ease-out drops to zero speed at every step: the pulse of a held D-pad).
 * Springs are given as a duration and a bounce, as tvOS and Android describe them: stiffness
 * (2π/d)², damping ratio 1 − bounce, mass 1. Bounce 0 is critically damped and never overshoots.
 */
final class Spring {
    /** App tiles taking focus: at size in 100 ms, about 10 % past it at 125 ms, settled near 230 ms. */
    static Spring app() {
        return new Spring(0.20, 0.4);
    }

    /** Posters and content cards taking focus: no overshoot, 95 % there by about 230 ms. */
    static Spring focus() {
        return new Spring(0.30, 0);
    }

    /** Menus and sheets coming in. */
    static Spring snappy() {
        return new Spring(0.30, 0.15);
    }

    /** A row or page sliding to keep focus in view. */
    static Spring row() {
        return new Spring(0.35, 0.15);
    }

    /** Long, calm moves: scrolling, a carried tile. */
    static Spring smooth() {
        return new Spring(0.45, 0);
    }

    /** Below API 26 the animator duration scale can't be read without a context: {@link Ui#init} keeps it here. */
    static volatile float legacyScale = 1f;

    private final double w, z, g;
    private double a, b, target;
    private long t0;

    Spring(double durationSec, double bounce) {
        w = 2 * Math.PI / durationSec;
        z = Math.max(0, Math.min(1, 1 - bounce));
        g = z < 1 ? w * Math.sqrt(1 - z * z) : 0;
    }

    /** From {@code x} moving at {@code v} units per second, toward {@code to}; times are in milliseconds. */
    void start(double x, double v, double to, long nowMs) {
        target = to;
        t0 = nowMs;
        a = x - to;
        b = z < 1 ? (v + z * w * a) / g : v + w * a;
    }

    void retarget(double to, long nowMs) {
        start(pos(nowMs), vel(nowMs), to, nowMs);
    }

    double target() {
        return target;
    }

    double pos(long nowMs) {
        double t = Math.max(0, nowMs - t0) / 1000.0, e = Math.exp(-z * w * t);
        if (z < 1) return e * (a * Math.cos(g * t) + b * Math.sin(g * t)) + target;
        return e * (a + b * t) + target;
    }

    /** Units per second. */
    double vel(long nowMs) {
        double t = Math.max(0, nowMs - t0) / 1000.0, e = Math.exp(-z * w * t);
        if (z < 1) {
            double c = Math.cos(g * t), s = Math.sin(g * t);
            return e * ((b * g - z * w * a) * c - (a * g + z * w * b) * s);
        }
        return e * (b - w * (a + b * t));
    }

    /**
     * Close enough to stop: within three quarters of the smallest visible change, and moving slower
     * than that change per frame. {@code minChange}: 1 for pixels, 1/500 for scale, 1/256 for alpha.
     */
    boolean atRest(long nowMs, double minChange) {
        double th = minChange * 0.75;
        return Math.abs(pos(nowMs) - target) < th && Math.abs(vel(nowMs)) < th * 62.5;
    }

    /** The TV's animation speed (Settings → Animations): 1 normal, 0.5 fast, 0 off. */
    static float timeScale() {
        if (android.os.Build.VERSION.SDK_INT >= 26) return ValueAnimator.getDurationScale();
        return legacyScale;
    }

    /** Receives each frame's value. */
    interface Sink {
        void at(float value);
    }

    /**
     * One value moved by a spring on the animation clock: {@link #to} starts it or retargets it
     * mid-flight, and it stops by itself once settled. Main thread only.
     */
    static final class Mover implements TimeAnimator.TimeListener {
        private final Spring spring;
        private final float minChange;
        private final Sink sink;
        private TimeAnimator clock;
        private float value;
        private boolean moving;
        private long epoch;
        private float scale = 1f;
        private Runnable settled;

        Mover(Spring spring, float minChange, float start, Sink sink) {
            this.spring = spring;
            this.minChange = minChange;
            this.sink = sink;
            value = start;
        }

        float value() {
            return value;
        }

        boolean moving() {
            return moving;
        }

        /** Where it's heading, or where it rests. */
        float target() {
            return moving ? (float) spring.target() : value;
        }

        /** Runs once when the current move settles (cleared by the next {@link #to} or {@link #jump}). */
        void then(Runnable r) {
            settled = r;
        }

        /** Heads for {@code to} from wherever the value is now, at the speed it has now. */
        void to(float to) {
            settled = null;
            if (!moving) {
                if (to == value) return;
                scale = timeScale();
                if (scale <= 0f) {
                    jump(to);
                    return;
                }
                epoch = AnimationUtils.currentAnimationTimeMillis();
                spring.start(value, 0, to, 0);
                moving = true;
                if (clock == null) {
                    clock = new TimeAnimator();
                    clock.setTimeListener(this);
                }
                clock.start();
                return;
            }
            spring.retarget(to, simNow());
        }

        /** Sets the value at once and stops. */
        void jump(float v) {
            settled = null;
            stop();
            value = v;
            sink.at(v);
        }

        void stop() {
            if (!moving) return;
            moving = false;
            if (clock != null) clock.cancel();
        }

        private long simNow() {
            return (long) ((AnimationUtils.currentAnimationTimeMillis() - epoch) / scale);
        }

        @Override
        public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
            if (!moving) return;
            long now = simNow();
            if (spring.atRest(now, minChange)) {
                value = (float) spring.target();
                moving = false;
                animation.cancel();
                sink.at(value);
                Runnable r = settled;
                settled = null;
                if (r != null) r.run();
                return;
            }
            value = (float) spring.pos(now);
            sink.at(value);
        }
    }

    /**
     * One value on Aura's retargetable spring ({@link Mover}) that never moves quicker than Android
     * TV's own rows ({@link Ui#scrollFloorMs}): from rest, a move the floor gives longer than its
     * spring gets a calm spring of that length, at most MAX_MS, and anything farther first jumps to
     * where a glide that long begins. Mid-flight it's only retargeted, keeping its speed.
     */
    static final class Glide {
        private static final long MAX_MS = 700;
        private final Spring spring;
        private final long springMs;
        private final float minChange;
        private final Sink sink;
        private Mover mover;
        private boolean slow;

        Glide(Spring spring, long springMs, float minChange, float start, Sink sink) {
            this.spring = spring;
            this.springMs = springMs;
            this.minChange = minChange;
            this.sink = sink;
            mover = new Mover(spring, minChange, start, sink);
        }

        /** Heads for {@code to}; {@code floorMs}: the least time the distance there may take. */
        void to(float to, long floorMs) {
            to(to, floorMs, null);
        }

        /** Heads for {@code to}, taking at least {@code floorMs}; {@code settled} runs once it rests there. */
        void to(float to, long floorMs, Runnable settled) {
            if (!mover.moving()) {
                float from = mover.value();
                if (floorMs > springMs) {
                    float start = from;
                    long ms = floorMs;
                    if (ms > MAX_MS) {
                        start = to - (to - from) * MAX_MS / ms;
                        ms = MAX_MS;
                    }
                    mover = new Mover(new Spring(ms / 1000.0, 0), minChange, start, sink);
                    slow = true;
                    if (start != from) sink.at(start);
                } else if (slow) {
                    mover = new Mover(spring, minChange, from, sink);
                    slow = false;
                }
            }
            mover.to(to);
            if (mover.moving()) mover.then(settled);
            else if (settled != null) settled.run();
        }

        float value() {
            return mover.value();
        }

        float target() {
            return mover.target();
        }

        boolean moving() {
            return mover.moving();
        }

        void stop() {
            mover.stop();
        }

        /** There at once. */
        void jump(float v) {
            mover.jump(v);
        }

        /** Straight to where it's heading. */
        void finish() {
            if (mover.moving()) mover.jump(mover.target());
        }
    }
}
