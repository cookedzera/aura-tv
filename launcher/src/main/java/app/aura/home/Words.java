package app.aura.home;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Aura's words in the TV's own language: res/values holds the English, res/values-de, -es, -fr,
 * -pt, -it and -hi the rest (docs/TRANSLATING.md). Most code reads them from its own Context; this
 * is for the small helpers that write a time, a span or a count with none at hand. Android swaps
 * the words underneath when the TV's language changes.
 */
final class Words {
    private static Context app;

    private Words() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    static String get(int id, Object... args) {
        return args.length == 0 ? app.getString(id) : app.getString(id, args);
    }

    /** "1 hour", "3 hours": the form the language uses for {@code n}, with {@code n} written in. */
    static String count(int id, int n) {
        return app.getResources().getQuantityString(id, n, n);
    }

    /** "12 min", "2 h", "1 h 5 min". */
    static String minutes(long min) {
        if (min < 60) return get(R.string.duration_min, min);
        return min % 60 == 0 ? get(R.string.duration_h, min / 60) : get(R.string.duration_h_min, min / 60, min % 60);
    }

    /**
     * A word that sits mid-sentence: lowered in languages that do that ("cloudy"), kept as it is in
     * those that don't (German keeps its nouns capitalised).
     */
    static String inSentence(String s) {
        return app.getResources().getBoolean(R.bool.lower_in_sentence) ? s.toLowerCase(Locale.getDefault()) : s;
    }

    /**
     * A date as the English {@code pattern} writes it ("EEEE, d MMMM"), and in other languages in the
     * order and with the marks that language uses ("Dienstag, 3. November").
     */
    static String date(String pattern, long when) {
        Locale l = Locale.getDefault();
        String p = l.getLanguage().equals("en") ? pattern
                : android.text.format.DateFormat.getBestDateTimePattern(l, pattern.replaceAll("[^A-Za-z]", ""));
        return new SimpleDateFormat(p, l).format(new Date(when));
    }
}
