package app.aura.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * The photograph's story, shared by photo mode and the screensaver: what it shows set large at the
 * bottom left with its photographer and licence, the time at the top right, and a hairline that
 * fills until the next photograph. Both blocks drift a little with each photograph so nothing burns in.
 */
final class Story extends FrameLayout implements Backdrop.Tick {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Backdrop backdrop;
    private final LinearLayout card, clock;
    private final TextView kicker, title, line, credit, time, date;
    private final View fill;
    private final Random rnd = new Random();
    private Backdrop.Wallpaper shown;

    Story(Context c, Backdrop b) {
        super(c);
        backdrop = b;
        b.addTick(this);

        // Legibility comes from two soft shades the GPU paints for almost nothing, not from text
        // shadows: this TV blurs shadowed glyphs on the CPU every frame (~15 ms for this card).
        addView(shade(GradientDrawable.Orientation.BOTTOM_TOP, 0x99000000),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(300), Gravity.BOTTOM));
        // behind the clock: a soft round glow from the corner, so no edge of it ever shows
        View corner = new View(c);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{0x80000000, 0x40000000, 0x00000000});
        glow.setGradientCenter(1f, 0f);
        glow.setGradientRadius(Ui.dpf(360));
        corner.setBackground(glow);
        addView(corner, new FrameLayout.LayoutParams(Ui.dp(420), Ui.dp(300), Gravity.TOP | Gravity.END));

        clock = new LinearLayout(c);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        time = Ui.text(c, 46, Ui.heavy, 0xFFFFFFFF);
        time.setIncludeFontPadding(true);
        time.setLetterSpacing(-0.04f);
        clock.addView(time, lp(0));
        date = flat(Ui.caps(c, 7.5f, 0xD9FFFFFF, 0.3f));
        clock.addView(date, lp(2));
        addView(clock, frame(Gravity.TOP | Gravity.END, 0, Ui.dp(34), Ui.dp(56), 0));

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        kicker = flat(Ui.caps(c, 8, 0xE6FFFFFF, 0.3f));
        card.addView(kicker, lp(0));
        title = Ui.text(c, 54, Ui.heavy, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setLetterSpacing(-0.04f);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(Ui.dp(820));
        card.addView(title, lp(4));
        line = Ui.text(c, 15, Ui.regular, 0xE6FFFFFF);
        line.setSingleLine(false);
        line.setMaxLines(2);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setMaxWidth(Ui.dp(600));
        line.setLineSpacing(Ui.dpf(3), 1f);
        card.addView(line, lp(4));

        LinearLayout meta = new LinearLayout(c);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout bar = new FrameLayout(c);
        bar.setBackground(pill(0x40FFFFFF));
        fill = new View(c);
        fill.setBackground(pill(0xF2FFFFFF));
        fill.setPivotX(0f);
        fill.setScaleX(0f);
        bar.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        meta.addView(bar, new LinearLayout.LayoutParams(Ui.dp(96), Ui.dp(2)));
        credit = flat(Ui.caps(c, 6.5f, 0xB3FFFFFF, 0.24f));
        LinearLayout.LayoutParams cl = lp(0);
        cl.leftMargin = Ui.dp(14);
        meta.addView(credit, cl);
        card.addView(meta, lp(18));
        addView(card, frame(Gravity.BOTTOM | Gravity.START, Ui.dp(64), 0, 0, Ui.dp(50)));
    }

    private View shade(GradientDrawable.Orientation o, int color) {
        View v = new View(getContext());
        v.setBackground(new GradientDrawable(o, new int[]{color, Ui.withAlpha(color, 0)}));
        return v;
    }

    /** Caps without the default text shadow (see the shades above). */
    private static TextView flat(TextView t) {
        t.setShadowLayer(0, 0, 0, 0);
        return t;
    }

    private static GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dpf(1));
        return g;
    }

    private static LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.topMargin = Ui.dp(top);
        return p;
    }

    private static FrameLayout.LayoutParams frame(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(WRAP, WRAP, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** Fading with the story applies straight to the text: no offscreen layer over the photograph. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** The clock; the AM/PM rides small beside the hour. */
    void tick(Calendar now) {
        boolean h24 = DateFormat.is24HourFormat(getContext());
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        if (h24) {
            time.setText(t);
        } else {
            SpannableString s = new SpannableString(t + " " + (now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM"));
            s.setSpan(new RelativeSizeSpan(0.42f), t.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            time.setText(s);
        }
        date.setText(new SimpleDateFormat("EEEE  ·  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault())
                + weatherLine);
    }

    private String weatherLine = "";

    /** "31° · CLEAR · JODHPUR" under the date. */
    void weather(Weather.Now w) {
        weatherLine = w == null ? "" : ("  ·  " + w.temp + "°  ·  " + w.condition()).toUpperCase(Locale.getDefault());
    }

    /** A new photograph: with {@code animate}, the old story lifts away as the new one rises in. */
    void set(Backdrop.Wallpaper w, boolean animate) {
        if (w == shown) return;
        shown = w;
        if (!animate) {
            fillText(w);
            return;
        }
        for (View v : new View[]{kicker, title, line, credit}) {
            v.animate().alpha(0f).translationY(-Ui.dp(10)).setDuration(420).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }
        postDelayed(() -> {
            fillText(w);
            drift();
            long d = 0;
            for (View v : new View[]{kicker, title, line, credit}) {
                v.setTranslationY(Ui.dp(18));
                v.animate().alpha(1f).translationY(0f).setDuration(1200).setStartDelay(d).setInterpolator(Ui.EMPHASIZED).start();
                d += 140;
            }
        }, 480);
    }

    private void fillText(Backdrop.Wallpaper w) {
        kicker.setText(w.region.toUpperCase(Locale.getDefault()).replace(" · ", "  ·  "));
        // fitted a little inside the title's max width, so rounding in the tracking never forces an ellipsis
        Ui.fit(title, w.place, Ui.dp(780), 54, 30);
        line.setText(w.line);
        line.setVisibility(w.line.isEmpty() ? View.GONE : View.VISIBLE);
        String by = w.author.isEmpty() ? "" : "PHOTO  ·  " + w.author + "  ·  ";
        credit.setText((by + w.license).toUpperCase(Locale.ROOT));
    }

    /** Moves both blocks a few dp so no edge of the type sits on the same pixels for long. */
    void drift() {
        card.setTranslationX(rnd.nextInt(Ui.dp(28)));
        card.setTranslationY(-rnd.nextInt(Ui.dp(22)));
        clock.setTranslationX(-rnd.nextInt(Ui.dp(28)));
        clock.setTranslationY(rnd.nextInt(Ui.dp(16)));
    }

    /** Rises in: the clock first, then the story from the bottom. */
    void reveal(long delay) {
        animate().cancel();
        setAlpha(1f);
        clock.setAlpha(0f);
        clock.animate().alpha(1f).setStartDelay(delay).setDuration(1400).setInterpolator(Ui.SOFT).start();
        card.setAlpha(0f);
        card.setTranslationY(card.getTranslationY() + Ui.dp(24));
        card.animate().alpha(1f).translationY(card.getTranslationY() - Ui.dp(24)).setStartDelay(delay + 300)
                .setDuration(1600).setInterpolator(Ui.EMPHASIZED).start();
    }

    void hide(long ms) {
        animate().alpha(0f).setStartDelay(0).setDuration(ms).setInterpolator(Ui.SOFT).start();
    }

    /** The hairline follows the photograph's time on screen, at the backdrop's own frame rate. */
    @Override
    public void onTick(long now) {
        if (getAlpha() > 0f && isShown()) fill.setScaleX(backdrop.shown());
    }
}
