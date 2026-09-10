package ro.auracamera.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.util.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Central capability-gated decision engine for AURA MAX.
 * It does not assume Samsung-private camera APIs; unsupported features are
 * reported and the app can gracefully fall back to Camera2/public Android APIs.
 */
public final class AuraMaxEngine {
    private AuraMaxEngine() {}

    public enum Scene {
        NIGHT, LOW_LIGHT, CLOUDY, DIFFUSE, BALANCED, SUN, HARD_SUN, BACKLIGHT, GOLDEN_HOUR, MIXED_LIGHT
    }

    public static final class FrameStats {
        public float luminance;
        public float contrast;
        public float highlights;
        public float shadows;
        public float saturation;
        public float sharpness;
        public float warmRatio;
        public float coolRatio;
    }

    public static final class SensorState {
        public float lux = -1;
        public float pitch;
        public float roll;
        public float yaw;
        public float motion;
        public boolean stable;
    }

    public static final class Policy {
        public Scene scene = Scene.BALANCED;
        public int evSteps;
        public int isoCeiling = 800;
        public long shutterFloorNs = 1_000_000L;
        public long shutterCeilingNs = 33_333_333L;
        public float saturation = 1.0f;
        public float contrast = 1.0f;
        public float brightness = 0f;
        public float clarity = 1.0f;
        public float denoise = 0.2f;
        public float highlightProtection;
        public float shadowLift;
        public boolean preferHdr;
        public boolean preferBurst;
        public boolean tripodLike;
        public String guidance = "CADRU ECHILIBRAT";
    }

    public static FrameStats analyze(Bitmap bitmap) {
        FrameStats s = new FrameStats();
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) return s;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        double sum = 0, sum2 = 0, sat = 0, edge = 0, warm = 0, cool = 0;
        int hi = 0, sh = 0, n = w * h;
        for (int y = 0; y < h; y++) {
            int prev = -1;
            for (int x = 0; x < w; x++) {
                int c = bitmap.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                float l = (.2126f * r + .7152f * g + .0722f * b) / 255f;
                sum += l; sum2 += l * l;
                if (l > .94f) hi++;
                if (l < .08f) sh++;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max > 0) sat += (max - min) / (double) max;
                if (r > b * 1.08) warm++;
                if (b > r * 1.08) cool++;
                int gray = (r * 3 + g * 6 + b) / 10;
                if (prev >= 0) edge += Math.abs(gray - prev) / 255.0;
                prev = gray;
            }
        }
        s.luminance = (float) (sum / n);
        s.contrast = (float) Math.sqrt(Math.max(0, sum2 / n - s.luminance * s.luminance));
        s.highlights = hi / (float) n;
        s.shadows = sh / (float) n;
        s.saturation = (float) (sat / n);
        s.sharpness = (float) (edge / Math.max(1, n - h));
        s.warmRatio = (float) (warm / n);
        s.coolRatio = (float) (cool / n);
        return s;
    }

    public static Scene classify(FrameStats f, SensorState sensor) {
        float lux = sensor == null ? -1 : sensor.lux;
        if ((lux >= 0 && lux < 8) || f.luminance < .16f) return Scene.NIGHT;
        if ((lux >= 0 && lux < 80) || f.luminance < .28f) return Scene.LOW_LIGHT;
        if (f.highlights > .11f && f.shadows > .12f) return Scene.BACKLIGHT;
        if (f.highlights > .09f && f.contrast > .27f) return Scene.HARD_SUN;
        if (lux > 22000) return Scene.SUN;
        if (f.warmRatio > .38f && f.luminance > .30f && f.luminance < .70f) return Scene.GOLDEN_HOUR;
        if (f.warmRatio > .20f && f.coolRatio > .20f) return Scene.MIXED_LIGHT;
        if ((lux >= 0 && lux < 1800) && f.luminance < .45f) return Scene.CLOUDY;
        if (f.contrast < .16f && f.luminance > .42f) return Scene.DIFFUSE;
        return Scene.BALANCED;
    }

    public static Policy policy(String mode, FrameStats f, SensorState s) {
        Policy p = new Policy();
        p.scene = classify(f, s);
        p.tripodLike = s != null && s.motion < .18f;
        switch (p.scene) {
            case HARD_SUN:
                p.evSteps = -2; p.highlightProtection = .85f; p.contrast = .97f; p.saturation = 1.01f; p.isoCeiling = 200; p.guidance = "SOARE PUTERNIC · highlights protejate"; break;
            case SUN:
                p.evSteps = -1; p.highlightProtection = .60f; p.isoCeiling = 250; p.guidance = "SOARE · expunere protejată"; break;
            case BACKLIGHT:
                p.evSteps = -1; p.preferHdr = true; p.highlightProtection = .85f; p.shadowLift = .45f; p.guidance = "CONTRALUMINĂ · HDR recomandat"; break;
            case CLOUDY:
                p.evSteps = 1; p.shadowLift = .28f; p.saturation = 1.025f; p.guidance = "ÎNNORAT · umbre ridicate"; break;
            case LOW_LIGHT:
                p.evSteps = 1; p.isoCeiling = 1600; p.shutterCeilingNs = p.tripodLike ? 120_000_000L : 33_333_333L; p.denoise = .62f; p.preferBurst = true; p.guidance = "LUMINĂ SLABĂ · ține telefonul stabil"; break;
            case NIGHT:
                p.evSteps = 1; p.isoCeiling = 2400; p.shutterCeilingNs = p.tripodLike ? 250_000_000L : 50_000_000L; p.denoise = .80f; p.preferBurst = true; p.guidance = p.tripodLike ? "NOAPTE · suport stabil detectat" : "NOAPTE · stabilizează telefonul"; break;
            case GOLDEN_HOUR:
                p.evSteps = 0; p.highlightProtection = .35f; p.saturation = 1.015f; p.guidance = "GOLDEN HOUR · tonuri calde protejate"; break;
            case MIXED_LIGHT:
                p.evSteps = 0; p.contrast = .99f; p.guidance = "LUMINĂ MIXTĂ · AWB conservator"; break;
            case DIFFUSE:
                p.evSteps = 0; p.contrast = 1.035f; p.clarity = 1.04f; p.guidance = "LUMINĂ DIFUZĂ · microcontrast ușor"; break;
            default:
                p.evSteps = 0; p.guidance = "CADRU ECHILIBRAT";
        }
        if ("Mașină".equals(mode)) {
            p.saturation *= 1.015f; p.clarity *= 1.06f; p.contrast *= 1.025f;
        } else if ("Selfie".equals(mode)) {
            p.saturation *= 1.005f; p.contrast *= .99f; p.clarity *= .985f;
        } else if ("Portret".equals(mode)) {
            p.contrast *= 1.01f; p.clarity *= 1.01f;
        } else if ("Noapte".equals(mode)) {
            p.denoise = Math.max(p.denoise, .72f); p.preferBurst = true;
        } else if ("TikTok".equals(mode)) {
            p.saturation *= 1.035f; p.contrast *= 1.02f;
        }
        return p;
    }

    public static int auraScore(FrameStats f, SensorState s, boolean compositionGood) {
        int score = 100;
        score -= Math.min(24, Math.round(Math.abs(f.luminance - .50f) * 52));
        score -= Math.min(20, Math.round(f.highlights * 110));
        score -= Math.min(16, Math.round(f.shadows * 55));
        if (f.sharpness < .035f) score -= 14;
        if (s != null && s.motion > 2.2f) score -= 12;
        if (compositionGood) score += 5;
        return Math.max(0, Math.min(100, score));
    }

    public static List<String> capabilities(CameraCharacteristics c, SensorManager sm) {
        List<String> out = new ArrayList<>();
        if (c == null) return out;
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) out.add("RAW/DNG");
        if (has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) out.add("ISO/Shutter manual");
        if (has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) out.add("Post-procesare manuală");
        if (has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)) out.add("Burst rapid");
        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (level != null) out.add("Camera2 level " + levelName(level));
        if (sm != null) {
            if (sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null) out.add("Senzor lumină");
            if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) out.add("Giroscop");
            if (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) out.add("Rotation vector");
            if (sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) out.add("Magnetometru");
        }
        return out;
    }

    private static boolean has(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private static String levelName(int level) {
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "LEVEL_3";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "FULL";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "LIMITED";
        if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "LEGACY";
        return String.format(Locale.US, "%d", level);
    }
}
