package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The Sky widget, opened: the weather now and how it feels, today's sun on its arc with sunrise,
 * sunset and the length of the day, the next hours, and the week ahead with each day's range set
 * against the whole week's, on frosted glass. Any key closes it.
 */
final class WeatherSheet extends FrameLayout {
    private static final int LEFT_W = 372, RIGHT_W = 330, HOURS = 7;

    private final Activity act;
    private final LinearLayout body;
    private final TextView city, temp, condition, range, details, rise, set, daylight, note;
    private final ImageView icon;
    private final SkyArc arc;
    private final LinearLayout nowBlock, hours, days, hoursBlock, daysBlock;

    WeatherSheet(Activity a, Backdrop b) {
        super(a);
        act = a;
        setVisibility(GONE);
        setFocusable(true);
        setClipChildren(false);
        GlassFrame veil = new GlassFrame(a, b, 0, 0.62f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        body = new LinearLayout(a);
        body.setClipChildren(false);

        // ---- left: now, the sun, the next hours
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        city = Ui.caps(a, 8, 0xB3FFFFFF, 0.26f);
        left.addView(city);
        nowBlock = new LinearLayout(a);
        nowBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout big = new LinearLayout(a);
        big.setGravity(Gravity.CENTER_VERTICAL);
        icon = ControlCenter.icon(a, R.drawable.ic_w_sun);
        icon.setColorFilter(0xFFFFFFFF);
        big.addView(icon, new LinearLayout.LayoutParams(Ui.dp(46), Ui.dp(46)));
        temp = Ui.text(a, 60, Ui.light, 0xFFFFFFFF);
        temp.setIncludeFontPadding(true);
        temp.setLetterSpacing(-0.04f);
        big.addView(temp, lp(14, 0));
        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        condition = Ui.text(a, 19, Ui.semibold, 0xFFFFFFFF);
        side.addView(condition);
        range = Ui.text(a, 13, Ui.medium, 0xB3FFFFFF);
        side.addView(range, lp(0, 4));
        big.addView(side, lp(18, 0));
        nowBlock.addView(big, lp(0, 6));
        details = Ui.text(a, 11.5f, Ui.medium, 0x99FFFFFF);
        nowBlock.addView(details, lp(0, 4));
        left.addView(nowBlock);
        note = Ui.text(a, 13, Ui.medium, 0xB3FFFFFF);
        note.setSingleLine(false);
        note.setVisibility(GONE);
        left.addView(note, lp(0, 10));

        left.addView(heading(a, "SUN"), lp(0, 26));
        arc = new SkyArc(a);
        left.addView(arc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(62)));
        LinearLayout feet = new LinearLayout(a);
        rise = Ui.text(a, 11, Ui.semibold, 0xCCFFFFFF);
        daylight = Ui.text(a, 10.5f, Ui.medium, 0x80FFFFFF);
        daylight.setGravity(Gravity.CENTER_HORIZONTAL);
        set = Ui.text(a, 11, Ui.semibold, 0xCCFFFFFF);
        set.setGravity(Gravity.END);
        feet.addView(rise, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        feet.addView(daylight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        feet.addView(set, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(6);
        left.addView(feet, fl);

        hoursBlock = new LinearLayout(a);
        hoursBlock.setOrientation(LinearLayout.VERTICAL);
        hoursBlock.addView(heading(a, "NEXT HOURS"));
        hours = new LinearLayout(a);
        hoursBlock.addView(hours, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // the full column's width: the hours share it equally
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(26);
        left.addView(hoursBlock, hl);
        body.addView(left, new LinearLayout.LayoutParams(Ui.dp(LEFT_W), ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- right: the week
        daysBlock = new LinearLayout(a);
        daysBlock.setOrientation(LinearLayout.VERTICAL);
        View rule = Ui.rule(a, 0x1FFFFFFF);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.hair(), ViewGroup.LayoutParams.MATCH_PARENT);
        rl.leftMargin = Ui.dp(44);
        rl.rightMargin = Ui.dp(40);
        daysBlock.addView(heading(a, "7-DAY FORECAST"));
        days = new LinearLayout(a);
        days.setOrientation(LinearLayout.VERTICAL);
        daysBlock.addView(days, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout right = new LinearLayout(a);
        right.addView(rule, rl);
        right.addView(daysBlock, new LinearLayout.LayoutParams(Ui.dp(RIGHT_W), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open(boolean weatherOn) {
        show(weatherOn);
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        body.setTranslationY(Ui.dp(18));
        body.setScaleX(0.96f);
        body.setScaleY(0.96f);
        body.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ui.EMPHASIZED).start();
        requestFocus();
    }

    void close() {
        if (!isOpen()) return;
        body.animate().scaleX(0.97f).scaleY(0.97f).setDuration(200).setInterpolator(Ui.SOFT).start();
        animate().alpha(0f).setDuration(200).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Fills it from the last reading; called again when a fresher one arrives while it's open. */
    void show(boolean weatherOn) {
        long now = System.currentTimeMillis();
        java.text.DateFormat tf = DateFormat.getTimeFormat(act);
        sun(now, tf);

        Weather.Now w = weatherOn ? Weather.now(act) : null;
        Weather.Forecast f = w != null ? Weather.forecast(act) : null;
        String place = w != null && w.city != null && !w.city.isEmpty() ? w.city : "Outside";
        city.setText(place.toUpperCase(Locale.getDefault()));
        nowBlock.setVisibility(w != null ? VISIBLE : GONE);
        hoursBlock.setVisibility(f != null && !f.hours.isEmpty() ? VISIBLE : GONE);
        daysBlock.setVisibility(f != null && !f.days.isEmpty() ? VISIBLE : GONE);
        ((View) daysBlock.getParent()).setVisibility(daysBlock.getVisibility());
        note.setVisibility(w == null ? VISIBLE : GONE);
        note.setText(weatherOn ? "The weather shows here once Aura has been online for a moment."
                : "Weather is switched off. Settings → Weather turns it on.");
        if (w == null) return;

        String unit = "°";
        icon.setImageResource(w.icon());
        temp.setText(w.temp + unit);
        condition.setText(w.condition());
        Weather.Day today = f != null && !f.days.isEmpty() ? f.days.get(0) : null;
        range.setText(today != null ? "H " + today.max + unit + "   L " + today.min + unit : "");
        StringBuilder d = new StringBuilder();
        if (f != null && f.feels != Integer.MIN_VALUE) d.append("Feels like ").append(f.feels).append(unit);
        if (f != null && f.humidity >= 0) d.append(d.length() > 0 ? "  ·  " : "").append("Humidity ").append(f.humidity).append('%');
        if (f != null && f.wind >= 0) d.append(d.length() > 0 ? "  ·  " : "").append("Wind ").append(f.wind).append(Weather.fahrenheit() ? " mph" : " km/h");
        details.setText(d);
        details.setVisibility(d.length() > 0 ? VISIBLE : GONE);
        if (f == null) return;

        hours.removeAllViews();
        for (int i = 0; i < f.hours.size() && i < HOURS; i++) {
            Weather.Hour h = f.hours.get(i);
            LinearLayout c = new LinearLayout(act);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView t = Ui.text(act, 10.5f, Ui.semibold, i == 0 ? 0xFFFFFFFF : 0x99FFFFFF);
            t.setText(i == 0 ? "Now" : hour(h.time));
            c.addView(t);
            // "Now" is the reading itself, so it never disagrees with the large one above
            ImageView iv = ControlCenter.icon(act, i == 0 ? w.icon() : Weather.icon(h.code, h.day));
            iv.setColorFilter(0xFFFFFFFF);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
            il.topMargin = Ui.dp(9);
            c.addView(iv, il);
            TextView rain = Ui.text(act, 9, Ui.bold, 0xFF8FC8FF);
            rain.setText(h.rain >= 20 ? h.rain + "%" : "");
            c.addView(rain, lp(0, 3));
            TextView v = Ui.text(act, 14, Ui.semibold, 0xFFFFFFFF);
            v.setText((i == 0 ? w.temp : h.temp) + unit);
            c.addView(v, lp(0, 3));
            hours.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        // the week's coldest and warmest set the scale every day's bar is drawn on
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Weather.Day day : f.days) {
            lo = Math.min(lo, day.min);
            hi = Math.max(hi, day.max);
        }
        days.removeAllViews();
        for (int i = 0; i < f.days.size(); i++) {
            Weather.Day day = f.days.get(i);
            LinearLayout r = new LinearLayout(act);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(act, 14, i == 0 ? Ui.bold : Ui.medium, 0xFFFFFFFF);
            name.setText(i == 0 ? "Today" : DateUtils.formatDateTime(act, day.date, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY));
            r.addView(name, new LinearLayout.LayoutParams(Ui.dp(62), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout sky = new LinearLayout(act);
            sky.setOrientation(LinearLayout.VERTICAL);
            sky.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = ControlCenter.icon(act, Weather.icon(day.code, true));
            iv.setColorFilter(0xFFFFFFFF);
            sky.addView(iv, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
            if (day.rain >= 20) {
                TextView rain = Ui.text(act, 8.5f, Ui.bold, 0xFF8FC8FF);
                rain.setText(day.rain + "%");
                sky.addView(rain, lp(0, 2));
            }
            r.addView(sky, new LinearLayout.LayoutParams(Ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView min = Ui.text(act, 13, Ui.medium, 0x80FFFFFF);
            min.setGravity(Gravity.END);
            min.setText(day.min + unit);
            r.addView(min, new LinearLayout.LayoutParams(Ui.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
            Range bar = new Range(act, lo, hi, day.min, day.max, i == 0 ? w.temp : Integer.MIN_VALUE);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(14), 1f);
            bl.leftMargin = Ui.dp(12);
            bl.rightMargin = Ui.dp(12);
            r.addView(bar, bl);
            TextView max = Ui.text(act, 13, Ui.semibold, 0xFFFFFFFF);
            max.setText(day.max + unit);
            r.addView(max, new LinearLayout.LayoutParams(Ui.dp(38), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(43));
            if (i > 0) {
                View line = Ui.rule(act, 0x14FFFFFF);
                days.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.hair()));
            }
            days.addView(r, rp);
        }
    }

    /** Today's sunrise and sunset, the sun between them, and how long the day is. */
    private void sun(long now, java.text.DateFormat tf) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long r = Sun.next(c.getTimeInMillis(), true), s = r > 0 ? Sun.next(r, false) : -1;
        arc.set(r, s, now);
        rise.setText(r > 0 ? "↑  " + tf.format(new Date(r)).toUpperCase(Locale.getDefault()) : "");
        set.setText(s > 0 ? tf.format(new Date(s)).toUpperCase(Locale.getDefault()) + "  ↓" : "");
        long len = s > r && r > 0 ? (s - r) / 60_000 : -1;
        daylight.setText(len > 0 ? (len / 60) + " h " + (len % 60) + " min of daylight" : "");
    }

    private String hour(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (DateFormat.is24HourFormat(act)) return String.format(Locale.getDefault(), "%02d", c.get(Calendar.HOUR_OF_DAY));
        int h = c.get(Calendar.HOUR);
        return (h == 0 ? 12 : h) + (c.get(Calendar.AM_PM) == Calendar.AM ? " AM" : " PM");
    }

    private static TextView heading(Context c, String s) {
        TextView t = Ui.caps(c, 7.5f, 0x80FFFFFF, 0.26f);
        t.setText(s);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.bottomMargin = Ui.dp(10);
        t.setLayoutParams(l);
        return t;
    }

    private static LinearLayout.LayoutParams lp(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }

    /** One day's low to high, placed on the week's scale and coloured cool to warm; today marks where it is now. */
    private static final class Range extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int lo, hi, min, max, now;

        Range(Context c, int lo, int hi, int min, int max, int now) {
            super(c);
            this.lo = lo;
            this.hi = hi;
            this.min = min;
            this.max = max;
            this.now = now;
            track.setColor(0x1FFFFFFF);
            dot.setColor(0xFFFFFFFF);
            dot.setShadowLayer(Ui.dpf(3), 0, 0, 0x80000000);
            if (now != Integer.MIN_VALUE) setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        private float x(int t) {
            float span = Math.max(1, hi - lo), pad = Ui.dpf(4);
            return pad + (getWidth() - 2 * pad) * (t - lo) / span;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            fill.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xFF6FC3FF, 0xFF8EE3B0, 0xFFFFD36B, 0xFFFF8A5C}, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), cy = h / 2f, th = Ui.dpf(4.5f), rad = th / 2f;
            r.set(0, cy - rad, getWidth(), cy + rad);
            c.drawRoundRect(r, rad, rad, track);
            r.set(x(min) - rad, cy - rad, x(max) + rad, cy + rad);
            c.drawRoundRect(r, rad, rad, fill);
            if (now != Integer.MIN_VALUE) c.drawCircle(x(Math.max(min, Math.min(max, now))), cy, Ui.dpf(3.6f), dot);
        }
    }
}
