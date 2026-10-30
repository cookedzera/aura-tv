package app.aura.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
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
 * What sits over photo mode and the screensaver, and as little of it as possible. Bottom left, a
 * large hairline clock with the date and weather in plain sentence case beneath. Bottom right, a
 * small caption (what you're looking at, where, and a faint credit) that rises in when the scene
 * changes, stays a while, and goes, leaving the picture alone. No capitals, no bold, no bars:
 * one soft shade along the bottom keeps the type legible over bright skies.
 */
final class Story extends FrameLayout implements Backdrop.Tick {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** How long a caption stays before it leaves the picture to itself. */
    private static final long CAPTION_MS = 14_000;

    private final LinearLayout clock, caption;
    private final TextView time, date, title, about, credit;
    private final Random rnd = new Random();
    private Backdrop.Wallpaper shown;
    private String weatherLine = "";
    private final Runnable quiet = this::quiet;

    Story(Context c, Backdrop b) {
        super(c);
        b.addTick(this);
        // one shade along the bottom, the GPU's to paint, instead of shadows on every glyph
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x8C000000, 0x33000000, 0x00000000}));
        addView(shade, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(260), Gravity.BOTTOM));

        clock = new LinearLayout(c);
        clock.setOrientation(LinearLayout.VERTICAL);
        time = Ui.text(c, 78, Ui.thin, 0xF7FFFFFF);
        time.setLetterSpacing(-0.035f);
        time.setIncludeFontPadding(true);
        clock.addView(time, lp(0, Gravity.START));
        date = Ui.text(c, 15, Ui.regular, 0xD9FFFFFF);
        date.setLetterSpacing(0.005f);
        clock.addView(date, lp(-2, Gravity.START));
        addView(clock, frame(Gravity.BOTTOM | Gravity.START, Ui.dp(64), 0, 0, Ui.dp(52)));

        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.END);
        title = Ui.text(c, 17, Ui.medium, 0xF2FFFFFF);
        title.setGravity(Gravity.END);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(Ui.dp(420));
        caption.addView(title, lp(0, Gravity.END));
        about = Ui.text(c, 13.5f, Ui.regular, 0xADFFFFFF);
        about.setSingleLine(false);
        about.setMaxLines(2);
        about.setEllipsize(TextUtils.TruncateAt.END);
        about.setGravity(Gravity.END);
        about.setMaxWidth(Ui.dp(400));
        about.setLineSpacing(Ui.dpf(2), 1f);
        caption.addView(about, lp(5, Gravity.END));
        credit = Ui.text(c, 11, Ui.regular, 0x6BFFFFFF);
        credit.setGravity(Gravity.END);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        credit.setMaxWidth(Ui.dp(400));
        caption.addView(credit, lp(9, Gravity.END));
        caption.setAlpha(0f);
        addView(caption, frame(Gravity.BOTTOM | Gravity.END, 0, 0, Ui.dp(64), Ui.dp(58)));
    }

    private static LinearLayout.LayoutParams lp(int top, int gravity) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.topMargin = Ui.dp(top);
        p.gravity = gravity;
        return p;
    }

    private static FrameLayout.LayoutParams frame(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(WRAP, WRAP, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** Fading with the story applies straight to the text: no offscreen layer over the picture. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** A scene that draws its own clock (the gradient clock) hides this one. */
    void setClockShown(boolean on) {
        clock.setVisibility(on ? VISIBLE : GONE);
    }

    /** The clock, thin and large; AM/PM small and quiet beside it. */
    void tick(Calendar now) {
        boolean h24 = DateFormat.is24HourFormat(getContext());
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        if (h24) {
            time.setText(t);
        } else {
            String ampm = now.get(Calendar.AM_PM) == Calendar.AM ? "am" : "pm";
            SpannableString s = new SpannableString(t + " " + ampm);
            s.setSpan(new RelativeSizeSpan(0.3f), t.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(0x99FFFFFF), t.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            time.setText(s);
        }
        String d = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.getTime());
        if (weatherLine.isEmpty()) {
            date.setText(d);
        } else {
            SpannableString s = new SpannableString(d + weatherLine);
            s.setSpan(new ForegroundColorSpan(0x99FFFFFF), d.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            date.setText(s);
        }
    }

    /** "  ·  24°  Partly cloudy" after the date. */
    void weather(Weather.Now w) {
        weatherLine = w == null ? "" : "   ·   " + w.temp + "°  " + w.condition();
    }

    /** A new photograph: its caption rises in, and later leaves. */
    void set(Backdrop.Wallpaper w, boolean animate) {
        if (w == shown) return;
        shown = w;
        String about = !w.line.isEmpty() ? w.line : w.region.replace(" · ", ", ");
        String by = w.author.isEmpty() ? w.license : "Photo by " + w.author + (w.license.isEmpty() ? "" : "  ·  " + w.license);
        change(w.place, about, by, animate);
    }

    /** Any other scene's caption: {@code context} (where, or what kind of scene) joins the credit line. */
    void set(String context, String place, String text, String by, long ms, boolean animate) {
        shown = null;
        String small = context.isEmpty() ? by : by.isEmpty() ? context : context + "   ·   " + by;
        change(place, text, small, animate);
    }

    private void change(String t, String a, String c, boolean animate) {
        removeCallbacks(quiet);
        caption.animate().cancel();
        if (!animate || caption.getAlpha() < 0.05f) {
            fill(t, a, c);
            rise();
            return;
        }
        caption.animate().alpha(0f).translationY(-Ui.dp(6)).setDuration(500).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    fill(t, a, c);
                    rise();
                }).start();
    }

    private void fill(String t, String a, String c) {
        title.setText(t);
        about.setText(a);
        about.setVisibility(a.isEmpty() ? GONE : VISIBLE);
        credit.setText(c);
        credit.setVisibility(c.isEmpty() ? GONE : VISIBLE);
    }

    /** The caption rises a few dp as it appears; a little later, it goes. */
    private void rise() {
        drift();
        caption.setTranslationY(Ui.dp(10));
        caption.animate().alpha(1f).translationY(0f).setDuration(1400).setStartDelay(200).setInterpolator(Ui.EMPHASIZED)
                .withEndAction(null).start();
        postDelayed(quiet, CAPTION_MS);
    }

    private void quiet() {
        caption.animate().alpha(0f).translationY(0f).setDuration(2200).setStartDelay(0).setInterpolator(Ui.SOFT).start();
    }

    /** Moves both blocks a few dp so no edge of the type sits on the same pixels for long. */
    void drift() {
        clock.setTranslationX(rnd.nextInt(Ui.dp(24)));
        clock.setTranslationY(-rnd.nextInt(Ui.dp(18)));
        caption.setTranslationX(-rnd.nextInt(Ui.dp(24)));
    }

    /** Rises in: the clock first, then the caption. */
    void reveal(long delay) {
        animate().cancel();
        setAlpha(1f);
        clock.setAlpha(0f);
        clock.setTranslationY(clock.getTranslationY() + Ui.dp(12));
        clock.animate().alpha(1f).translationY(clock.getTranslationY() - Ui.dp(12)).setStartDelay(delay).setDuration(1800)
                .setInterpolator(Ui.EMPHASIZED).start();
    }

    void hide(long ms) {
        removeCallbacks(quiet);
        animate().alpha(0f).setStartDelay(0).setDuration(ms).setInterpolator(Ui.SOFT).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(quiet);
        super.onDetachedFromWindow();
    }

    /** No progress bar any more: the picture's time on screen needs no ruler. */
    @Override
    public void onTick(long now) {
    }
}
