package app.aura.home;

import android.graphics.Matrix;
import android.graphics.RectF;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLegacyMatrix;

/** Test only: Robolectric's legacy Matrix keeps every post-op as a string forever; a set* replaces the matrix, so history goes. */
@Implements(value = Matrix.class, isInAndroidSdk = false)
public class SaneMatrix extends ShadowLegacyMatrix {
    @Implementation @Override protected void setTranslate(float dx, float dy) { reset(); super.setTranslate(dx, dy); }
    @Implementation @Override protected void setScale(float sx, float sy, float px, float py) { reset(); super.setScale(sx, sy, px, py); }
    @Implementation @Override protected void setScale(float sx, float sy) { reset(); super.setScale(sx, sy); }
    @Implementation @Override protected void setRotate(float d, float px, float py) { reset(); super.setRotate(d, px, py); }
    @Implementation @Override protected void setRotate(float d) { reset(); super.setRotate(d); }
    @Implementation @Override protected void setValues(float[] v) { reset(); super.setValues(v); }
    @Implementation @Override protected boolean setRectToRect(RectF s, RectF d, Matrix.ScaleToFit f) { reset(); return super.setRectToRect(s, d, f); }
}
