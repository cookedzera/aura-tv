package app.aura.home;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Find an app by typing: one frosted pill at the top and, under it, the apps that match, as you
 * type, in your tile look. Nothing else on screen. A name's start counts most, then a word's
 * start ("pr" finds Amazon Prime Video), then its initials ("yk" finds YouTube Kids), then anywhere
 * in it. OK on the keyboard opens the first; Down reaches them all; the last row searches the
 * whole TV for what you typed.
 */
final class Find extends FrameLayout {
    interface Host {
        /** Every app that can be found (hidden ones too, except in a kids' profile). */
        List<AppRepo.App> apps();

        /** The apps to offer before anything is typed: the first on the grid. */
        List<AppRepo.App> suggested();

        Tiles.Painter art(AppRepo.App a);

        boolean glass();

        Backdrop backdrop();

        void open(AppRepo.App a, View from);

        /** The TV's own search, for what isn't an app. */
        void everywhere(String text);
    }

    private static final int MAX = 5, W = 148, H = 84;

    private final Host host;
    private final EditText field;
    private LinearLayout results;
    private final TextView label, everywhere;
    private final View everywhereRow;
    private boolean shown;
    /** The best match on show, for OK on the keyboard. */
    private AppRepo.App first;

    Find(Context c, Host h) {
        super(c);
        host = h;
        setVisibility(GONE);
        setClipChildren(false);
        GlassFrame veil = new GlassFrame(c, h.backdrop(), 0, 0.62f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);

        // the pill: a glass bar, the glass icon and the words as they're typed
        GlassFrame pill = new GlassFrame(c, h.backdrop(), 27, 0.35f);
        pill.frost = 0.08f;
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        ImageView glass = new ImageView(c);
        glass.setImageResource(R.drawable.ic_search);
        glass.setColorFilter(0xCCFFFFFF);
        bar.addView(glass, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        field = new EditText(c);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setHint("Find an app");
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x73FFFFFF);
        field.setTextSize(20);
        field.setTypeface(Ui.semibold);
        field.setPadding(Ui.dp(14), 0, 0, 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (shown) fill(s.toString());
            }
        });
        field.setOnEditorActionListener((v, id, e) -> {
            // Go: the best match
            if (first != null) host.open(first, ((ViewGroup) results.getChildAt(0)).getChildAt(0));
            else if (field.length() > 0) host.everywhere(field.getText().toString());
            return true;
        });
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        pill.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(pill, new LinearLayout.LayoutParams(Ui.dp(560), Ui.dp(54)));

        label = Ui.caps(c, 7.5f, 0x8CFFFFFF, 0.28f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = Ui.dp(30);
        col.addView(label, ll);

        results = new LinearLayout(c);
        results.setClipChildren(false);
        results.setClipToPadding(false);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(16);
        col.addView(results, rl);

        // the TV's own search, for a film or a song rather than an app
        Pad row = new Pad(c, null, 18, 0f);
        row.plainColor = 0x14FFFFFF;
        row.edgeLight = false;
        row.focusScale = 1.04f;
        everywhere = row.tint(Ui.text(c, 13.5f, Ui.medium, 0xFFFFFFFF), 0.85f);
        everywhere.setGravity(Gravity.CENTER);
        row.addView(everywhere, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.onClick = () -> host.everywhere(field.getText().toString());
        everywhereRow = row;
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(Ui.dp(360), Ui.dp(38));
        el.topMargin = Ui.dp(40);
        col.addView(row, el);

        LayoutParams cl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cl.topMargin = Ui.dp(58);
        addView(col, cl);
    }

    boolean isOpen() {
        return shown;
    }

    /** Text sent from the phone while Find is open: replaces what's in the pill. */
    void type(String typed) {
        field.setText(typed);
        field.setSelection(field.length());
    }

    /** Opens with {@code typed} already in the field (a letter pressed on the home screen), the keyboard up. */
    void open(String typed) {
        shown = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        field.setText(typed);
        field.setSelection(field.length());
        fill(typed);
        field.requestFocus();
        field.post(() -> Compat.svc(getContext(), InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    void close() {
        if (!shown) return;
        shown = false;
        Compat.svc(getContext(), InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
        animate().alpha(0f).setDuration(180).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            if (shown) return;
            setVisibility(GONE);
            results.removeAllViews();
        }).start();
    }

    /** The matches for {@code q}, best first; before anything is typed, the suggestions. */
    private void fill(String q) {
        String query = fold(q.trim());
        List<AppRepo.App> found = new ArrayList<>();
        if (query.isEmpty()) {
            for (AppRepo.App a : host.suggested()) if (found.size() < MAX) found.add(a);
            label.setText("SUGGESTED");
        } else {
            List<Object[]> scored = new ArrayList<>();
            for (AppRepo.App a : host.apps()) {
                int s = score(fold(a.label), query);
                if (s >= 0) scored.add(new Object[]{s, a});
            }
            Collections.sort(scored, (x, y) -> (int) x[0] != (int) y[0] ? (int) x[0] - (int) y[0]
                    : ((AppRepo.App) x[1]).label.compareToIgnoreCase(((AppRepo.App) y[1]).label));
            for (Object[] s : scored) if (found.size() < MAX) found.add((AppRepo.App) s[1]);
            label.setText(found.isEmpty() ? "NO APP BY THAT NAME" : found.size() == 1 ? "1 APP" : found.size() + " APPS");
        }
        results.removeAllViews();
        first = found.isEmpty() ? null : found.get(0);
        for (AppRepo.App a : found) {
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setClipChildren(false);
            Card card = new Card(getContext(), host.art(a), a.color, 14);
            if (host.glass() && a.art == null) card.glass = host.backdrop();
            card.sway = true;
            card.onClick = () -> host.open(a, card);
            item.addView(card, new LinearLayout.LayoutParams(Ui.dp(W), Ui.dp(H)));
            TextView name = Ui.text(getContext(), 12, Ui.medium, 0xD9FFFFFF);
            name.setText(a.label);
            name.setMaxWidth(Ui.dp(W));
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = Ui.dp(12);
            item.addView(name, nl);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (results.getChildCount() > 0) il.leftMargin = Ui.dp(22);
            results.addView(item, il);
        }
        everywhereRow.setVisibility(query.isEmpty() ? GONE : VISIBLE);
        everywhere.setText("Search the TV for “" + q.trim() + "”");
    }

    /** Lower case, accents folded: "Pokémon" is found by "pokemon". */
    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** 0: the name starts with it; 1: a word does; 2: the initials do; 3: it's somewhere inside; -1: no match. */
    static int score(String name, String q) {
        if (name.startsWith(q)) return 0;
        String[] words = name.split("[^\\p{L}\\p{N}]+");
        StringBuilder initials = new StringBuilder();
        boolean word = false;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (w.startsWith(q)) word = true;
            initials.append(w.charAt(0));
        }
        if (word) return 1;
        if (q.length() >= 2 && initials.toString().startsWith(q)) return 2;
        return name.contains(q) ? 3 : -1;
    }
}
