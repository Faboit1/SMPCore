package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.List;
import net.kyori.adventure.text.format.TextColor;

/**
 * Which colours players may give their chat messages and nicknames. Colours that already mean something stay
 * reserved (the error red for errors and kills, the money green for money), and colours too dark to read on the chat
 * background are refused. Gradients are checked along their whole length, so two fine end colours whose blend passes
 * through red are refused too.
 * <p>
 * "Too close" is perceptual: the CIEDE2000 colour difference between the two colours (CIE Lab, D65) is below
 * {@link #minDistance}, or the colour is the same hue as a reserved one (within {@link #HUE_WINDOW} degrees, both
 * clearly coloured) and only lighter or darker ({@link #SHADE_DISTANCE}). "Too dark" is the WCAG contrast ratio
 * against black, the chat background. Pure and thread-safe.
 *
 * @param reserved    colours nobody may use, with why
 * @param minDistance the smallest CIEDE2000 difference from a reserved colour (20 by default)
 * @param minContrast the smallest contrast ratio against black (3 by default)
 */
record ColorRules(List<Reserved> reserved, double minDistance, double minContrast) {

    /** A colour with a meaning, and the reason players are told. */
    enum Reason {
        /** Red: errors and kills. */
        ERRORS,
        /** Green: money. */
        MONEY,
        /** A colour the owner reserved in the config. */
        SERVER
    }

    /** A reserved colour. */
    record Reserved(TextColor color, Reason reason) {
    }

    /** What the rules say about a colour or gradient. */
    record Verdict(boolean allowed, boolean tooDark, Reason reserved) {

        static final Verdict OK = new Verdict(true, false, null);
        static final Verdict DARK = new Verdict(false, true, null);

        static Verdict reserved(Reason reason) {
            return new Verdict(false, false, reason);
        }
    }

    /**
     * Hues closer than this (degrees in CIE Lab) are "the same colour, lighter or darker". 25 degrees keeps sea greens
     * (#3CB371, #2E8B57) and olive greens with the money green while sky blue, aquamarine, gold and pink stay free.
     */
    static final double HUE_WINDOW = 25.0;
    /** Below this CIEDE2000 difference, a colour of the same hue as a reserved one is refused. */
    static final double SHADE_DISTANCE = 40.0;
    /** Colours with less chroma than this are greys: hue means nothing for them (#8FBC8F, a greyish green, is not). */
    static final double MIN_CHROMA = 25.0;
    /** Points checked along a gradient. */
    static final int GRADIENT_SAMPLES = 17;

    ColorRules {
        reserved = List.copyOf(reserved);
    }

    /** The default rules: the palette's error red and money green, a difference of 20 and a contrast of 3. */
    static ColorRules defaults(TextColor error, TextColor money) {
        return new ColorRules(List.of(new Reserved(error, Reason.ERRORS), new Reserved(money, Reason.MONEY)), 20.0, 3.0);
    }

    /** Checks one colour. */
    Verdict check(TextColor color) {
        if (contrast(color) < this.minContrast) {
            return Verdict.DARK;
        }
        double[] lab = lab(color);
        for (Reserved reserved : this.reserved) {
            if (tooClose(lab, lab(reserved.color()))) {
                return Verdict.reserved(reserved.reason());
            }
        }
        return Verdict.OK;
    }

    /** Checks a two-stop gradient: both ends and every point between them. */
    Verdict check(TextColor from, TextColor to) {
        for (int i = 0; i < GRADIENT_SAMPLES; i++) {
            Verdict verdict = check(lerp(from, to, i / (double) (GRADIENT_SAMPLES - 1)));
            if (!verdict.allowed()) {
                return verdict;
            }
        }
        return Verdict.OK;
    }

    /** Checks a style (no style is always fine). */
    Verdict check(ChatStyle style) {
        return switch (style) {
            case ChatStyle.None none -> Verdict.OK;
            case ChatStyle.Solid solid -> check(solid.color());
            case ChatStyle.Gradient gradient -> check(gradient.from(), gradient.to());
        };
    }

    private boolean tooClose(double[] lab, double[] reserved) {
        double distance = deltaE2000(lab, reserved);
        if (distance < this.minDistance) {
            return true;
        }
        if (distance >= SHADE_DISTANCE || chroma(lab) < MIN_CHROMA || chroma(reserved) < MIN_CHROMA) {
            return false;
        }
        double difference = Math.abs(hue(lab) - hue(reserved)) % 360.0;
        return Math.min(difference, 360.0 - difference) < HUE_WINDOW;
    }

    // ------------------------------------------------------------------ colour science

    /** The colour at {@code t} (0 to 1) between two colours, blended in RGB like Adventure's gradients. */
    static TextColor lerp(TextColor from, TextColor to, double t) {
        double clamped = Math.clamp(t, 0.0, 1.0);
        int r = (int) Math.round(from.red() + (to.red() - from.red()) * clamped);
        int g = (int) Math.round(from.green() + (to.green() - from.green()) * clamped);
        int b = (int) Math.round(from.blue() + (to.blue() - from.blue()) * clamped);
        return TextColor.color(r, g, b);
    }

    /** WCAG 2 contrast ratio of the colour against black (1 to 21). */
    static double contrast(TextColor color) {
        return (luminance(color) + 0.05) / 0.05;
    }

    /** WCAG 2 relative luminance. */
    static double luminance(TextColor color) {
        return 0.2126 * linear(color.red()) + 0.7152 * linear(color.green()) + 0.0722 * linear(color.blue());
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** CIE L*a*b* of an sRGB colour (D65 white). */
    static double[] lab(TextColor color) {
        double r = linear(color.red());
        double g = linear(color.green());
        double b = linear(color.blue());
        double x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047;
        double y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        double z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[] {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
    }

    private static double labF(double t) {
        return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16.0) / 116.0;
    }

    static double chroma(double[] lab) {
        return Math.hypot(lab[1], lab[2]);
    }

    /** Hue angle in degrees, 0 to 360. */
    static double hue(double[] lab) {
        double degrees = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return degrees < 0 ? degrees + 360.0 : degrees;
    }

    /** The CIEDE2000 colour difference (Sharma, Wu and Dalal, 2005) of two Lab colours. */
    static double deltaE2000(double[] lab1, double[] lab2) {
        double l1 = lab1[0];
        double a1 = lab1[1];
        double b1 = lab1[2];
        double l2 = lab2[0];
        double a2 = lab2[1];
        double b2 = lab2[2];
        double cBar = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2.0;
        double cBar7 = Math.pow(cBar, 7);
        double g = 0.5 * (1.0 - Math.sqrt(cBar7 / (cBar7 + Math.pow(25.0, 7))));
        double a1p = (1.0 + g) * a1;
        double a2p = (1.0 + g) * a2;
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);
        double h1p = hueOf(a1p, b1);
        double h2p = hueOf(a2p, b2);
        double dLp = l2 - l1;
        double dCp = c2p - c1p;
        double dhp;
        if (c1p * c2p == 0.0) {
            dhp = 0.0;
        } else {
            dhp = h2p - h1p;
            if (dhp > 180.0) {
                dhp -= 360.0;
            } else if (dhp < -180.0) {
                dhp += 360.0;
            }
        }
        double dHp = 2.0 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp / 2.0));
        double lBarP = (l1 + l2) / 2.0;
        double cBarP = (c1p + c2p) / 2.0;
        double hBarP;
        if (c1p * c2p == 0.0) {
            hBarP = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= 180.0) {
            hBarP = (h1p + h2p) / 2.0;
        } else if (h1p + h2p < 360.0) {
            hBarP = (h1p + h2p + 360.0) / 2.0;
        } else {
            hBarP = (h1p + h2p - 360.0) / 2.0;
        }
        double t = 1.0 - 0.17 * Math.cos(Math.toRadians(hBarP - 30.0)) + 0.24 * Math.cos(Math.toRadians(2.0 * hBarP))
            + 0.32 * Math.cos(Math.toRadians(3.0 * hBarP + 6.0)) - 0.20 * Math.cos(Math.toRadians(4.0 * hBarP - 63.0));
        double dTheta = 30.0 * Math.exp(-Math.pow((hBarP - 275.0) / 25.0, 2));
        double cBarP7 = Math.pow(cBarP, 7);
        double rc = 2.0 * Math.sqrt(cBarP7 / (cBarP7 + Math.pow(25.0, 7)));
        double lShift = Math.pow(lBarP - 50.0, 2);
        double sl = 1.0 + 0.015 * lShift / Math.sqrt(20.0 + lShift);
        double sc = 1.0 + 0.045 * cBarP;
        double sh = 1.0 + 0.015 * cBarP * t;
        double rt = -Math.sin(Math.toRadians(2.0 * dTheta)) * rc;
        double dl = dLp / sl;
        double dc = dCp / sc;
        double dh = dHp / sh;
        return Math.sqrt(dl * dl + dc * dc + dh * dh + rt * dc * dh);
    }

    private static double hueOf(double a, double b) {
        if (a == 0.0 && b == 0.0) {
            return 0.0;
        }
        double degrees = Math.toDegrees(Math.atan2(b, a));
        return degrees < 0 ? degrees + 360.0 : degrees;
    }
}
