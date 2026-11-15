package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Field;

/**
 * The Smart Stack at rest asks for no frames: giving its background a colour on every draw asked
 * for the next one, and home (redrawn whole on a TV without partial updates) never stopped drawing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SmartStackIdleTest {
    private boolean wasLight;

    @Before
    public void setUp() {
        wasLight = Ui.light;
    }

    @After
    public void tearDown() {
        Ui.light = wasLight;
    }

    @Test
    public void drawingAgainAtRestDoesNotRecolourTheBackground() throws Exception {
        for (boolean light : new boolean[]{true, false}) {
            Ui.light = light;
            Context c = RuntimeEnvironment.getApplication();
            SmartStack stack = new SmartStack(c, 24);
            int[] recoloured = {0};
            GradientDrawable counting = new GradientDrawable() {
                @Override
                public void setColor(int argb) {
                    recoloured[0]++;
                    super.setColor(argb);
                }
            };
            Field f = SmartStack.class.getDeclaredField("bg");
            f.setAccessible(true);
            f.set(stack, counting);
            stack.measure(400, 200);
            stack.layout(0, 0, 400, 200);
            Canvas canvas = new Canvas(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888));
            for (int i = 0; i < 5; i++) stack.draw(canvas);
            Assert.assertTrue("light=" + light + ": recoloured " + recoloured[0] + " times over 5 draws at rest",
                    recoloured[0] <= 1);
        }
    }
}
