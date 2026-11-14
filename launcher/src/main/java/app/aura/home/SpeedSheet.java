package app.aura.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Is your TV slow?": a small glass card over everything, about Light mode. Two ways it comes:
 * offered once when home is slow to answer the remote with Light mode off, or as a word of warning
 * when Light mode is switched off on a TV with little memory or a slow chip. One choice changes
 * something (switch Light mode on, or off anyway); the other, and Back, leave things as they are.
 * Left and right move between the two buttons; nothing else takes the focus while it's open.
 */
final class SpeedSheet extends FrameLayout {
    private final View dim;
    private final GlassFrame card;
    private final TextView title, body;
    private final Pad first, second;
    private final TextView firstText, secondText;
    private final FrameLayout badge;
    private Runnable change;
    private View before;
    private boolean shown;

    SpeedSheet(Context c, Backdrop b) {
        super(c);
        setVisibility(GONE);
        setClipChildren(false);
        dim = new HomeActivity.Shade(c, 0xB3000000);
        addView(dim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new GlassFrame(c, b, 28, 0.55f);
        card.setClipContent(false);
        // a dark base under the glass: with no photograph on the wall (Pure Black) nothing behind shows through
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xF2121418);
        base.setCornerRadius(Ui.dpf(28));
        card.setBackground(base);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(30), Ui.dp(26), Ui.dp(30), Ui.dp(24));
        col.setClipChildren(false);

        // a warm disc with a bolt: the one bright thing on the card
        badge = new FrameLayout(c);
        GradientDrawable disc = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFD36B, 0xFFFF8A3D});
        disc.setShape(GradientDrawable.OVAL);
        badge.setBackground(disc);
        ImageView bolt = ControlCenter.icon(c, R.drawable.ic_bolt);
        bolt.setColorFilter(0xFF2A1606);
        badge.addView(bolt, new LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
        col.addView(badge, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));

        TextView kicker = Ui.caps(c, 7.5f, 0xFFFFC46B, 0.26f);
        kicker.setText(c.getString(R.string.light_mode).toUpperCase(java.util.Locale.getDefault()));
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = Ui.dp(16);
        col.addView(kicker, kl);

        title = Ui.text(c, 19f, Ui.semibold, 0xFFF7F4EE);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(-0.01f);
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setMaxWidth(Ui.dp(400));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(6);
        col.addView(title, tl);

        body = Ui.text(c, 12f, Ui.regular, 0xB3FFFFFF);
        body.setGravity(Gravity.CENTER);
        body.setSingleLine(false);
        body.setMaxLines(4);
        body.setLineSpacing(Ui.dpf(3), 1f);
        body.setMaxWidth(Ui.dp(380));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(8);
        col.addView(body, bl);

        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        first = button(c);
        firstText = (TextView) first.getChildAt(0);
        second = button(c);
        secondText = (TextView) second.getChildAt(0);
        row.addView(first, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(40)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(40));
        sl.leftMargin = Ui.dp(12);
        row.addView(second, sl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(22);
        col.addView(row, rl);

        card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card, new LayoutParams(Ui.dp(470), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** A glass button: white with dark words while it has the focus, as every button on Aura. */
    private static Pad button(Context c) {
        Pad p = new Pad(c, null, 20, 0f);
        p.plainColor = 0x24FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.06f;
        TextView t = p.tint(Ui.text(c, 12.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        p.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    boolean isOpen() {
        return shown;
    }

    /** Home was slow to answer the remote: Light mode offered. {@code turnOn} runs if it's taken. */
    void offer(Runnable turnOn) {
        Context c = getContext();
        open(c.getString(R.string.speed_offer_title), c.getString(R.string.speed_offer_body),
                c.getString(R.string.speed_turn_on), c.getString(R.string.not_now), true, turnOn);
    }

    /** Light mode about to go off on a small TV: kept unless {@code turnOff} is chosen. */
    void warn(Runnable turnOff) {
        Context c = getContext();
        open(c.getString(R.string.speed_warn_title), c.getString(R.string.speed_warn_body),
                c.getString(R.string.speed_keep), c.getString(R.string.speed_turn_off), false, turnOff);
    }

    private void open(String t, String b, String one, String two, boolean changeIsFirst, Runnable onChange) {
        title.setText(t);
        body.setText(b);
        firstText.setText(one);
        secondText.setText(two);
        (changeIsFirst ? first : second).onClick = () -> choose(true);
        (changeIsFirst ? second : first).onClick = () -> choose(false);
        change = onChange;
        before = getRootView().findFocus();
        shown = true;
        setVisibility(VISIBLE);
        // a TV that has just stuttered (the offer), or one that's small: the card simply fades in,
        // nothing grows, travels or casts a shadow; elsewhere it rises into place
        boolean calm = Ui.light || changeIsFirst || Perf.lowEnd(getContext());
        badge.setElevation(calm ? 0f : Ui.dpf(8));
        dim.animate().cancel();
        card.animate().cancel();
        dim.setAlpha(0f);
        card.setAlpha(0f);
        card.setTranslationY(calm ? 0f : Ui.dpf(14));
        card.setScaleX(calm ? 1f : 0.96f);
        card.setScaleY(calm ? 1f : 0.96f);
        dim.animate().alpha(1f).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(calm ? 200 : 420).setStartDelay(calm ? 40 : 30)
                .setInterpolator(calm ? Ui.SOFT : Ui.EMPHASIZED).start();
        first.requestFocus();
    }

    /**
     * The keys while it's open (from home's own key handling): left and right between the buttons,
     * OK on the one focused (its own), Back as the choice that changes nothing. True when used.
     */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return !Pad.isConfirm(code);
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            choose(false);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) first.requestFocus();
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) second.requestFocus();
        return !Pad.isConfirm(code);
    }

    private void choose(boolean doIt) {
        if (!shown) return;
        Runnable r = doIt ? change : null;
        change = null;
        View back = before;
        before = null;
        close();
        // nothing changed: the focus goes back where it was; a change makes the screen again and places it itself
        if (r != null) r.run();
        else if (back != null && back.isAttachedToWindow()) back.requestFocus();
    }

    /** Fades out, then leaves the screen's layers altogether (the glass stops following the photograph). */
    private void close() {
        shown = false;
        card.animate().cancel();
        card.animate().alpha(0f).setDuration(160).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        dim.animate().cancel();
        dim.animate().alpha(0f).setDuration(200).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            if (shown) return;
            setVisibility(GONE);
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        }).start();
    }
}
