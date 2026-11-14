package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * A picture from the web that Aura let go of (a small TV drops them each time an app opens) comes
 * back from Aura's cache on the TV, not over the network again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtOnDiskTest {
    // nothing answers at this address: a picture can only come from the TV's own cache
    private static final String URI = "https://art.invalid/poster.jpg";
    private Context c;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        Continue.trim();
    }

    private void saved(byte[] data, long age) throws Exception {
        File f = Continue.artOnDisk(c, URI);
        Assert.assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        Assert.assertTrue(f.setLastModified(System.currentTimeMillis() - age));
    }

    private static byte[] png() {
        Bitmap b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.rgb(200, 80, 40));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    @Test
    public void aPictureLetGoComesBackFromTheTv() throws Exception {
        saved(png(), 0);
        Bitmap b = Continue.load(c, URI);
        Assert.assertNotNull("read from the cache on the TV", b);
        Assert.assertEquals(64, b.getWidth());
    }

    @Test
    public void aPictureDaysOldIsAskedForAgain() throws Exception {
        saved(png(), 3 * 86_400_000L);
        Assert.assertNull("older than two days: fetched afresh (and nothing answers here)", Continue.load(c, URI));
    }
}
