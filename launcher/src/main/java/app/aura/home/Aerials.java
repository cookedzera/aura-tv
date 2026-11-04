package app.aura.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Apple's aerial videos for Music scenes: the list (assets/aerials.json, taken from Aerial for
 * Windows, MIT licence) and which one plays next. Each streams from Apple's own server as it plays;
 * nothing is saved on the TV.
 */
final class Aerials {
    /** The kinds a scene can keep to; "mix" is any of them. */
    static final String[] KINDS = {"mix", "landscape", "cityscape", "space", "underwater"};
    static final int[] KIND_NAMES = {R.string.scene_mix, R.string.scene_landscapes, R.string.scene_cities,
            R.string.scene_space, R.string.scene_underwater};

    static final class Video {
        final String label, kind, time, url, hevc;

        Video(String label, String kind, String time, String url, String hevc) {
            this.label = label;
            this.kind = kind;
            this.time = time;
            this.url = url;
            this.hevc = hevc;
        }

        /** The copy to stream: HEVC (a third smaller, so it starts sooner) where the TV decodes it in hardware. */
        String stream() {
            return hevc != null && hevcInHardware() ? hevc : url;
        }
    }

    private static List<Video> all;

    private Aerials() { }

    static synchronized List<Video> all(Context c) {
        if (all != null) return all;
        List<Video> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("aerials.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONArray list = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("videos");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                String url = o.optString("url");
                // only Apple's own server: the one the network rule lets through, checked
                String hevc = o.optString("hevc", null);
                if (hevc != null && !hevc.startsWith("https://sylvan.apple.com/")) hevc = null;
                if (url.startsWith("https://sylvan.apple.com/")) {
                    out.add(new Video(o.optString("label"), o.optString("type"), o.optString("time"), url, hevc));
                }
            }
        } catch (Exception e) {
            Trouble.caught("aerial list", e);
        }
        all = Collections.unmodifiableList(out);
        return all;
    }

    private static Boolean hevc;

    /**
     * Whether a hardware decoder plays HEVC here. Android's own software decoders are left out: one
     * would play it, but on the TV's processor, which is exactly the load scenes are meant to spare.
     */
    static synchronized boolean hevcInHardware() {
        if (hevc != null) return hevc;
        boolean found = false;
        try {
            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                String n = info.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.")) continue;
                if (android.os.Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated()) continue;
                for (String t : info.getSupportedTypes()) if ("video/hevc".equalsIgnoreCase(t)) found = true;
            }
        } catch (RuntimeException ignored) {
        }
        hevc = found;
        return found;
    }

    /** The kind kept to (Settings, or left and right on the player), "mix" when it's unknown. */
    static String kind(Context c) {
        String k = Prefs.of(c).getString(Prefs.SCENE_KIND, "mix");
        for (String s : KINDS) if (s.equals(k)) return k;
        return "mix";
    }

    /**
     * A video of {@code kind} whose light suits the sky outside (a night flight after dark), never
     * the one just shown; any of the kind when none suits; null when the kind has none at all.
     */
    static Video pick(List<Video> list, String kind, Sun.Phase phase, String lastUrl, Random rnd) {
        List<Video> suits = new ArrayList<>(), any = new ArrayList<>();
        for (Video v : list) {
            if (!"mix".equals(kind) && !v.kind.equals(kind)) continue;
            if (v.url.equals(lastUrl)) continue;
            any.add(v);
            if (Sun.suits(mood(v.time), phase)) suits.add(v);
        }
        List<Video> from = !suits.isEmpty() ? suits : any;
        return from.isEmpty() ? null : from.get(rnd.nextInt(from.size()));
    }

    /** Apple's "twilight" is the photographs' golden hour. */
    private static String mood(String time) {
        return "twilight".equals(time) ? "golden" : time;
    }
}
