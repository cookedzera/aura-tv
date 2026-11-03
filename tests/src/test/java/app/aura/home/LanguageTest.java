package app.aura.home;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aura in German, Spanish and Portuguese: every translated line fills in the same blanks as the
 * English one (a wrong one would crash where it's shown), and home, Settings' pages and Control
 * Center open in each language, in that language.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class LanguageTest {
    private static final String[] LANGS = {"de", "es", "pt"};
    private static final Pattern BLANK = Pattern.compile("%(\\d+)\\$[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])");

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    /** "1:d 2:s" for "%1$d of %2$s". */
    private static String blanks(CharSequence s) {
        TreeMap<Integer, String> m = new TreeMap<>();
        Matcher x = BLANK.matcher(s);
        while (x.find()) m.put(Integer.parseInt(x.group(1)), x.group(2));
        return m.toString();
    }

    private static Resources in(Context c, String lang) {
        Configuration conf = new Configuration(c.getResources().getConfiguration());
        conf.setLocale(new Locale(lang));
        return c.createConfigurationContext(conf).getResources();
    }

    @Test
    public void sameBlanksAsEnglish() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Resources en = in(c, "en");
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String lang : LANGS) {
            Resources r = in(c, lang);
            for (Field f : R.string.class.getFields()) {
                int id = f.getInt(null);
                String want = blanks(en.getText(id)), got = blanks(r.getText(id));
                if (!want.equals(got)) wrong.add(lang + " " + f.getName() + ": " + got + " instead of " + want);
                checked++;
            }
            for (Field f : R.plurals.class.getFields()) {
                int id = f.getInt(null);
                String want = blanks(en.getQuantityText(id, 5));
                for (int n : new int[]{0, 1, 2, 5}) {
                    String got = blanks(r.getQuantityText(id, n));
                    // the "one" form may leave the number out ("an hour")
                    if (!want.equals(got) && !(n == 1 && got.equals("{}"))) wrong.add(lang + " " + f.getName() + " (" + n + "): " + got + " instead of " + want);
                }
            }
        }
        Assert.assertTrue(checked > 2000);
        Assert.assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }

    @Test
    public void screensOpenInEachLanguage() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        String[] settingsWord = {"Einstellungen", "Ajustes", "Configurações"};
        for (int i = 0; i < LANGS.length; i++) {
            RuntimeEnvironment.setQualifiers(LANGS[i]);
            ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            HomeActivity a = ac.get();
            run(3000);
            Assert.assertEquals(settingsWord[i], a.getString(R.string.settings));
            for (String page : new String[]{"", "home", "wallpaper", "widgets", "updates", "apps", "profiles", "sources"}) {
                call(a, "openSettings", new Class<?>[]{String.class}, page);
                run(1200);
                a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                run(600);
            }
            call(a, "openCc", new Class<?>[]{boolean.class}, false);
            run(1500);
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            run(800);
            // the small helpers with no Context at hand follow the language too
            Assert.assertEquals(a.getString(R.string.never), Ambient.span(-1));
            Assert.assertNotEquals("Never", Ambient.span(-1));
            ac.pause().stop().destroy();
            run(500);
        }
    }
}
