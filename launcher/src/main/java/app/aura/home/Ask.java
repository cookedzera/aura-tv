package app.aura.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A name, typed with the TV's own keyboard: a small glass card near the top (the keyboard takes the
 * bottom of the screen), the current name already in it and selected, so typing replaces it. Under
 * the field a few suggestions, for naming without the keyboard at all. OK on the keyboard or on a
 * suggestion keeps the name; Back leaves it as it was.
 */
final class Ask extends FrameLayout {
    interface Done {
        void got(String text);
    }

    private final GlassFrame card;
    private final TextView kicker;
    private final EditText field;
    private final LinearLayout chips;
    private Done done;
    private boolean shown;

    Ask(Context c, Backdrop b) {
        super(c);
        setVisibility(GONE);
        setClipChildren(false);
        View dim = new View(c);
        dim.setBackgroundColor(0x99000000);
        addView(dim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card = new GlassFrame(c, b, 24, 0.5f);
        card.setClipContent(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(22), Ui.dp(18), Ui.dp(22), Ui.dp(20));
        col.setClipChildren(false);
        kicker = Ui.caps(c, 7.5f, 0x99FFFFFF, 0.26f);
        col.addView(kicker);
        field = new EditText(c);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x66FFFFFF);
        field.setTextSize(17);
        field.setTypeface(Ui.semibold);
        field.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
        GradientDrawable box = new GradientDrawable();
        box.setColor(0x1FFFFFFF);
        box.setCornerRadius(Ui.dpf(14));
        box.setStroke(Math.max(1, Ui.hair()), 0x40FFFFFF);
        field.setBackground(box);
        field.setOnEditorActionListener((v, id, e) -> {
            finish(field.getText().toString());
            return true;
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44));
        fl.topMargin = Ui.dp(10);
        col.addView(field, fl);
        chips = new LinearLayout(c);
        chips.setClipChildren(false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(14);
        col.addView(chips, cl);
        card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams l = new LayoutParams(Ui.dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        l.topMargin = Ui.dp(56);
        addView(card, l);
    }

    /** Fills and submits the field from the phone. */
    void type(String text) {
        if (!shown || text == null || text.isEmpty()) return;
        field.setText(text);
        field.setSelection(field.length());
        finish(field.getText().toString());
    }

    boolean isOpen() {
        return shown;
    }

    /** A PIN: digits only, hidden as they're typed, no suggestions. */
    void showPin(String title, Done d) {
        show(title, "", "The parents' PIN, then OK", java.util.Collections.emptyList(), d);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
    }

    /** Opens with {@code text} selected, the keyboard up, and {@code suggestions} (up to four) under it. */
    void show(String title, String text, String hint, List<String> suggestions, Done d) {
        done = d;
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        kicker.setText(title);
        field.setHint(hint);
        field.setText(text);
        field.selectAll();
        chips.removeAllViews();
        for (String s : suggestions) {
            if (chips.getChildCount() >= 4) break;
            Pad p = new Pad(getContext(), null, 14, 0f);
            p.plainColor = 0x1FFFFFFF;
            p.edgeLight = false;
            p.focusScale = 1.06f;
            TextView t = p.tint(Ui.text(getContext(), 12, Ui.semibold, 0xFFFFFFFF), 0.9f);
            t.setText(s);
            t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            t.setGravity(Gravity.CENTER);
            p.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            p.onClick = () -> finish(s);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32));
            if (chips.getChildCount() > 0) pl.leftMargin = Ui.dp(8);
            chips.addView(p, pl);
        }
        chips.setVisibility(chips.getChildCount() > 0 ? VISIBLE : GONE);
        shown = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(220).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        card.setTranslationY(-Ui.dp(14));
        card.animate().translationY(0f).setDuration(420).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        field.requestFocus();
        field.post(() -> Compat.svc(getContext(), InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    /** Back: nothing changes. */
    void cancel() {
        done = null;
        hide();
    }

    private void finish(String text) {
        Done d = done;
        done = null;
        hide();
        String t = text.trim().replaceAll("\\s+", " ");
        if (d != null && !t.isEmpty()) d.got(t);
    }

    private void hide() {
        if (!shown) return;
        shown = false;
        Compat.svc(getContext(), InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
        animate().alpha(0f).setDuration(180).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            if (!shown) setVisibility(GONE);
        }).start();
    }
}
