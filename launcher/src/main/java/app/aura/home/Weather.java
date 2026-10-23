package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The weather where the TV is, with no account or key: the place from the connection's IP
 * (GeoJS, checked once a day, or a city chosen in Settings), the weather from Open-Meteo (checked
 * at most hourly, only while the home screen is in use). The same coordinates make Sky Sync's
 * sunrise and sunset exact for that city instead of the time zone's estimate.
 */
final class Weather {
    static final class Now {
        final int temp, code;
        final boolean day;
        final String city;

        Now(int temp, int code, boolean day, String city) {
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.city = city;
        }

        /** A word for the sky, e.g. "Clear", "Light rain". */
        String condition() {
            if (code == 0) return day ? "Sunny" : "Clear";
            if (code <= 2) return "Partly cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code <= 57) return "Drizzle";
            if (code <= 67) return code <= 61 ? "Light rain" : "Rain";
            if (code <= 77) return "Snow";
            if (code <= 82) return "Showers";
            if (code <= 86) return "Snow showers";
            return "Thunderstorm";
        }

        /** The matching line icon. */
        int icon() {
            if (code == 0) return day ? R.drawable.ic_w_sun : R.drawable.ic_moon;
            if (code <= 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_cloud;
            if (code == 3) return R.drawable.ic_w_cloud;
            if (code == 45 || code == 48) return R.drawable.ic_w_fog;
            if (code <= 67 || (code >= 80 && code <= 82)) return R.drawable.ic_w_rain;
            if (code <= 77 || code <= 86) return R.drawable.ic_w_snow;
            return R.drawable.ic_w_storm;
        }
    }

    private static final String UA = "AuraTV/6 (open-source Android TV home screen)";
    private static final long PLACE_MS = 24 * 3_600_000L, WEATHER_MS = 50 * 60_000L;

    private Weather() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("aura", Context.MODE_PRIVATE);
    }

    /** The last reading, or null before the first one. */
    static Now now(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("wx_temp")) return null;
        return new Now(p.getInt("wx_temp", 0), p.getInt("wx_code", 0), p.getBoolean("wx_day", true), p.getString("wx_city", ""));
    }

    /** Hands Sun the saved coordinates, if there are any yet. */
    static void placeSun(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("geo_lat")) Sun.place(p.getFloat("geo_lat", 0), p.getFloat("geo_lon", 0));
    }

    /**
     * Off the main thread. Refreshes the place (daily, unless chosen by hand) and the weather
     * (hourly). Returns true when anything new was saved.
     */
    static boolean refresh(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        boolean changed = false;
        try {
            if (!p.getBoolean("geo_manual", false) && now - p.getLong("geo_at", 0) > PLACE_MS) {
                JSONObject g = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
                p.edit().putFloat("geo_lat", (float) g.optDouble("latitude")).putFloat("geo_lon", (float) g.optDouble("longitude"))
                        .putString("wx_city", g.optString("city")).putLong("geo_at", now).apply();
                placeSun(c);
                changed = true;
            }
            if (p.contains("geo_lat") && now - p.getLong("wx_at", 0) > WEATHER_MS) {
                String url = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
                        p.getFloat("geo_lat", 0), p.getFloat("geo_lon", 0));
                JSONObject cur = new JSONObject(get(url)).getJSONObject("current");
                p.edit().putInt("wx_temp", (int) Math.round(cur.getDouble("temperature_2m"))).putInt("wx_code", cur.getInt("weather_code"))
                        .putBoolean("wx_day", cur.optInt("is_day", 1) == 1).putLong("wx_at", now).apply();
                changed = true;
            }
        } catch (IOException | org.json.JSONException | RuntimeException ignored) {
            // offline or the service is down: keep the last reading
        }
        return changed;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }
}
