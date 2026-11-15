package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/**
 * The focused tile's shadow on home's page in Light mode. A LOW tile casts no real
 * shadow (the GPU would work it out every frame) and doesn't redraw a baked one as it rises: two
 * shared views beneath the tiles carry the baked shadow instead, moved and faded by view properties
 * alone, one under the tile rising into focus and one under the tile it left while that one settles.
 * It follows focus and the page by itself; on HIGH it stays out of the way and tiles keep their own.
 */
final class FocusLayer implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalFocusChangeListener {
    private final ViewGroup parent;
    private final Shade[] shades = new Shade[2];

    private FocusLayer(ViewGroup parent) {
        this.parent = parent;
    }

    /**
     * Puts the layer into {@code parent}, the page home's cells are placed on (so it scrolls with
     * them), straight above {@code below}, or at the back when that's null or not in it: the cells,
     * added later, draw over it.
     */
    static FocusLayer attach(ViewGroup parent, View below) {
        FocusLayer layer = new FocusLayer(parent);
        int at = below != null ? parent.indexOfChild(below) + 1 : 0;
        for (int i = 0; i < layer.shades.length; i++) {
            layer.shades[i] = new Shade(parent.getContext(), layer);
            parent.addView(layer.shades[i], at + i, new ViewGroup.LayoutParams(0, 0));
        }
        return layer;
    }

    /** Focus moved to {@code focused}: on LOW a cell's card on this page gets a shade beneath it as it rises. */
    void follow(View focused) {
        if (!Ui.light || !(focused instanceof Card) || !(focused.getParent() instanceof Cell)) return;
        Card card = (Card) focused;
        if (!card.lift || focused.getParent().getParent() != parent) return;
        if (shades[0].card == card || shades[1].card == card) return;
        Shade s = shades[0].card == null ? shades[0] : shades[1].card == null ? shades[1]
                : shades[0].card.risen() <= shades[1].card.risen() ? shades[0] : shades[1];
        s.track(card);
    }

    /** Both shades gone at once (a panel over home, edit mode); the next focus move brings one back. */
    void hide() {
        for (Shade s : shades) s.release();
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        follow(newFocus);
    }

    @Override
    public boolean onPreDraw() {
        if (!Ui.light) return true;
        for (Shade s : shades) if (s.card != null) s.sync();
        return true;
    }

    /** One shared shadow: the baked mask of the tile it's under, stretched over the tile and its reach. */
    private static final class Shade extends View {
        private final FocusLayer layer;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix stretch = new Matrix();
        private Bitmap mask;
        private float peak, drop;
        Card card;

        Shade(Context c, FocusLayer layer) {
            super(c);
            this.layer = layer;
            setAlpha(0f);
            setVisibility(INVISIBLE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void track(Card c) {
            card = c;
            // it lies beneath the card from now on: the card draws none of its own
            c.layered = true;
            c.invalidate();
            peak = (c.tvos ? 165 : 120) / 255f;
            drop = Ui.dpf(c.tvos ? 11 : 9);
            setAlpha(0f);
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            fit(c);
        }

        void release() {
            card = null;
            if (getAlpha() != 0f) setAlpha(0f);
            if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
        }

        /** Sized to its card and the shadow's reach, with the card's baked mask (made when the card was laid out). */
        private void fit(Card c) {
            int reach = Math.round(Ui.dpf(Ui.SHADOW_REACH));
            int w = c.getWidth() + 2 * reach, h = c.getHeight() + 2 * reach;
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null && (lp.width != w || lp.height != h)) {
                lp.width = w;
                lp.height = h;
                setLayoutParams(lp);
            }
            Bitmap b = c.shadow();
            if (b != mask) {
                mask = b;
                invalidate();
            }
        }

        /** Under its card as it is this frame: its place on the page, its scale, how far it has risen. */
        void sync() {
            Card c = card;
            if (c == null) return;
            ViewParent vp = c.getParent();
            float p = c.risen();
            if (!Ui.light || !c.isAttachedToWindow() || !(vp instanceof View) || vp.getParent() != layer.parent
                    || p <= 0.004f && !c.isFocused()) {
                release();
                return;
            }
            if (!c.isShown()) {
                if (getAlpha() != 0f) setAlpha(0f);
                if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
                return;
            }
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            fit(c);
            View cell = (View) vp;
            // the card's centre on the page: its own place, lean and scale about its pivot, then its cell's
            float cx = c.getLeft() + c.getTranslationX() + c.getPivotX() + (c.getWidth() / 2f - c.getPivotX()) * c.getScaleX();
            float cy = c.getTop() + c.getTranslationY() + c.getPivotY() + (c.getHeight() / 2f - c.getPivotY()) * c.getScaleY();
            float x = cell.getLeft() + cell.getTranslationX() + cell.getPivotX() + (cx - cell.getPivotX()) * cell.getScaleX();
            float y = cell.getTop() + cell.getTranslationY() + cell.getPivotY() + (cy - cell.getPivotY()) * cell.getScaleY();
            float sc = c.getScaleX() * cell.getScaleX(), lit = Math.min(1f, p);
            float tx = x - getLeft() - getWidth() / 2f;
            float ty = y - getTop() - getHeight() / 2f + drop * lit * sc;
            float a = lit * peak * cell.getAlpha() * c.getAlpha();
            if (getScaleX() != sc) setScaleX(sc);
            if (getScaleY() != sc) setScaleY(sc);
            if (getTranslationX() != tx) setTranslationX(tx);
            if (getTranslationY() != ty) setTranslationY(ty);
            if (getAlpha() != a) setAlpha(a);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (layer.shades[0] != this) return;
            getViewTreeObserver().addOnPreDrawListener(layer);
            getViewTreeObserver().addOnGlobalFocusChangeListener(layer);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (layer.shades[0] == this) {
                getViewTreeObserver().removeOnPreDrawListener(layer);
                getViewTreeObserver().removeOnGlobalFocusChangeListener(layer);
            }
            release();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mask == null) return;
            stretch.setScale(getWidth() / (float) mask.getWidth(), getHeight() / (float) mask.getHeight());
            c.drawBitmap(mask, stretch, paint);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }
}
