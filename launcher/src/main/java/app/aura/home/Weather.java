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
            return Weather.condition(code, day);
        }

        /** The matching line icon. */
        int icon() {
            return Weather.icon(code, day);
        }
    }

    /** One day ahead: its date (midnight, local), the sky, the high and low, and the chance of rain. */
    static final class Day {
        final long date;
        final int code, max, min, rain;

        Day(long date, int code, int max, int min, int rain) {
            this.date = date;
            this.code = code;
            this.max = max;
            this.min = min;
            this.rain = rain;
        }
    }

    /** One hour ahead. */
    static final class Hour {
        final long time;
        final int code, temp, rain;
        final boolean day;

        Hour(long time, int code, int temp, int rain, boolean day) {
            this.time = time;
            this.code = code;
            this.temp = temp;
            this.rain = rain;
            this.day = day;
        }
    }

    /** What the weather card shows when opened: how it feels now, the next hours, the week. */
    static final class Forecast {
        int feels = Integer.MIN_VALUE, humidity = -1, wind = -1;
        final java.util.List<Day> days = new java.util.ArrayList<>();
        final java.util.List<Hour> hours = new java.util.ArrayList<>();
    }

    /** The last forecast, read from what the last refresh kept; null before the first. */
    private static Forecast cached;
    private static String cachedJson;
    private static long cachedHour = -1;

    static Forecast forecast(Context c) {
        String json = prefs(c).getString("wx_json", null);
        if (json == null) return null;
        long hNow = System.currentTimeMillis() / 3_600_000L;
        if (cached != null && hNow == cachedHour && json.equals(cachedJson)) return cached;
        Forecast f = new Forecast();
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.optJSONObject("current");
            if (cur != null) {
                if (cur.has("apparent_temperature")) f.feels = (int) Math.round(cur.getDouble("apparent_temperature"));
                f.humidity = cur.optInt("relative_humidity_2m", -1);
                if (cur.has("wind_speed_10m")) f.wind = (int) Math.round(cur.getDouble("wind_speed_10m"));
            }
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT),
                    hour = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
            // the times are the place's own (timezone=auto), the same as the TV's for a TV at home
            JSONObject d = o.optJSONObject("daily");
            if (d != null) {
                org.json.JSONArray t = d.getJSONArray("time");
                for (int i = 0; i < t.length(); i++) {
                    f.days.add(new Day(day.parse(t.getString(i)).getTime(), d.getJSONArray("weather_code").optInt(i),
                            (int) Math.round(d.getJSONArray("temperature_2m_max").optDouble(i)),
                            (int) Math.round(d.getJSONArray("temperature_2m_min").optDouble(i)),
                            d.getJSONArray("precipitation_probability_max").optInt(i, 0)));
                }
            }
            JSONObject h = o.optJSONObject("hourly");
            long sys = System.currentTimeMillis(), shift = o.has("utc_offset_seconds")
                    ? o.optInt("utc_offset_seconds") * 1000L - java.util.TimeZone.getDefault().getOffset(sys) : 0L;
            long now = sys + shift - 3_600_000L;
            if (h != null) {
                org.json.JSONArray t = h.getJSONArray("time");
                for (int i = 0; i < t.length() && f.hours.size() < 8; i++) {
                    long at = hour.parse(t.getString(i)).getTime();
                    if (at < now) continue;
                    f.hours.add(new Hour(at, h.getJSONArray("weather_code").optInt(i), (int) Math.round(h.getJSONArray("temperature_2m").optDouble(i)),
                            h.getJSONArray("precipitation_probability").optInt(i, 0), h.getJSONArray("is_day").optInt(i, 1) == 1));
                }
            }
        } catch (org.json.JSONException | java.text.ParseException | RuntimeException e) {
            return null;
        }
        cached = f;
        cachedJson = json;
        cachedHour = hNow;
        return f;
    }

    static String condition(int code, boolean day) {
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

    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_sun : R.drawable.ic_moon;
        if (code <= 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_cloud;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code <= 67 || (code >= 80 && code <= 82)) return R.drawable.ic_w_rain;
        if (code <= 86) return R.drawable.ic_w_snow;
        return R.drawable.ic_w_storm;
    }

    /** Fahrenheit and miles an hour where those are what people use: the US and a few others. */
    static boolean fahrenheit() {
        switch (Locale.getDefault().getCountry()) {
            case "US": case "LR": case "MM": case "BS": case "BZ": case "KY": case "PW": case "FM": case "MH":
                return true;
            default:
                return false;
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
        float lat = p.getFloat("geo_lat", Float.NaN), lon = p.getFloat("geo_lon", Float.NaN);
        if (!Float.isNaN(lat) && !Float.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) Sun.place(lat, lon);
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
                double lat = g.optDouble("latitude"), lon = g.optDouble("longitude");
                if (!Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                    p.edit().putFloat("geo_lat", (float) lat).putFloat("geo_lon", (float) lon)
                            .putString("wx_city", g.optString("city")).putLong("geo_at", now).apply();
                    placeSun(c);
                    changed = true;
                }
            }
            float lat = p.getFloat("geo_lat", Float.NaN), lon = p.getFloat("geo_lon", Float.NaN);
            // the same one request carries the week and the next hours, for the weather card
            if (!Float.isNaN(lat) && !Float.isNaN(lon) && (now - p.getLong("wx_at", 0) > WEATHER_MS || !p.contains("wx_json")
                    || p.getBoolean("wx_f", false) != fahrenheit())) {
                String url = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m"
                                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=36"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=7"
                                + "&timezone=auto" + (fahrenheit() ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : ""),
                        lat, lon);
                String body = get(url);
                JSONObject cur = new JSONObject(body).getJSONObject("current");
                p.edit().putInt("wx_temp", (int) Math.round(cur.getDouble("temperature_2m"))).putInt("wx_code", cur.getInt("weather_code"))
                        .putBoolean("wx_day", cur.optInt("is_day", 1) == 1).putString("wx_json", body).putBoolean("wx_f", fahrenheit()).putLong("wx_at", now).apply();
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
