package bloombox.core.color;

/**
 * sRGB ⇄ CIELAB (D65) conversions. Lab is perceptually roughly uniform, so Euclidean distance
 * in Lab approximates how different two colours look, which is what clustering needs; and
 * lightness L / chroma C / hue h (LCh, the polar form of Lab) let us adjust readability
 * without shifting the hue.
 */
public final class Lab {
    private Lab() {}

    private static final double XN = 0.95047, YN = 1.0, ZN = 1.08883;

    /** @return {L, a, b} for a 0xRRGGBB colour (alpha ignored). L in [0,100]. */
    public static double[] fromRgb(int rgb) {
        double r = lin(((rgb >> 16) & 0xff) / 255.0);
        double g = lin(((rgb >> 8) & 0xff) / 255.0);
        double b = lin((rgb & 0xff) / 255.0);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN;
        double y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    /** Lab → 0xRRGGBB, clamping each channel; {@link #inGamut} says whether clamping was needed. */
    public static int toRgb(double l, double a, double b) {
        double[] c = toLinear(l, a, b);
        return (channel(c[0]) << 16) | (channel(c[1]) << 8) | channel(c[2]);
    }

    public static boolean inGamut(double l, double a, double b) {
        for (double v : toLinear(l, a, b)) if (v < -1e-4 || v > 1 + 1e-4) return false;
        return true;
    }

    /** LCh → sRGB, reducing chroma (keeping L and hue) until the colour is displayable. */
    public static int fromLch(double l, double c, double hDeg) {
        double h = Math.toRadians(hDeg);
        while (c > 0 && !inGamut(l, c * Math.cos(h), c * Math.sin(h))) c = Math.max(0, c - 1);
        return toRgb(l, c * Math.cos(h), c * Math.sin(h));
    }

    public static double chroma(double[] lab) {
        return Math.hypot(lab[1], lab[2]);
    }

    /** Hue angle in degrees, [0, 360). */
    public static double hue(double[] lab) {
        double h = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return h < 0 ? h + 360 : h;
    }

    /** Smallest angle between two hues, [0, 180]. */
    public static double hueDistance(double h1, double h2) {
        double d = Math.abs(h1 - h2) % 360;
        return d > 180 ? 360 - d : d;
    }

    public static double distanceSq(double[] p, double[] q) {
        double dl = p[0] - q[0], da = p[1] - q[1], db = p[2] - q[2];
        return dl * dl + da * da + db * db;
    }

    private static double[] toLinear(double l, double a, double b) {
        double fy = (l + 16) / 116, fx = fy + a / 500, fz = fy - b / 200;
        double x = XN * finv(fx), y = YN * finv(fy), z = ZN * finv(fz);
        return new double[]{
                3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
                -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
                0.0556434 * x - 0.2040259 * y + 1.0572252 * z};
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int channel(double linear) {
        double v = Math.max(0, Math.min(1, linear));
        double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    private static final double EPS = 216.0 / 24389, KAPPA = 24389.0 / 27;

    private static double f(double t) {
        return t > EPS ? Math.cbrt(t) : (KAPPA * t + 16) / 116;
    }

    private static double finv(double t) {
        double t3 = t * t * t;
        return t3 > EPS ? t3 : (116 * t - 16) / KAPPA;
    }
}
