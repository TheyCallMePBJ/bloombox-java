package bloombox.core.color;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PaletteExtractorTest {

    /** n pixels: the given colours repeated in proportion to their counts. */
    private static int[] image(int[] colours, int[] counts) {
        int total = Arrays.stream(counts).sum();
        int[] px = new int[total];
        int i = 0;
        for (int c = 0; c < colours.length; c++) for (int j = 0; j < counts[c]; j++) px[i++] = colours[c];
        return px;
    }

    @Test
    void labRoundTripsSrgb() {
        for (int rgb : new int[]{0x000000, 0xffffff, 0xff0000, 0x00ff00, 0x0000ff, 0xec9fc2, 0x123456}) {
            double[] lab = Lab.fromRgb(rgb);
            assertEquals(rgb, Lab.toRgb(lab[0], lab[1], lab[2]), Integer.toHexString(rgb));
        }
        assertEquals(100, Lab.fromRgb(0xffffff)[0], 0.01);
        assertEquals(0, Lab.chroma(Lab.fromRgb(0x808080)), 0.01, "greys have no chroma");
    }

    @Test
    void solidColourCoverGivesThatHue() {
        PaletteExtractor.Palette p = PaletteExtractor.extract(image(new int[]{0xc02020}, new int[]{500}), 1);
        assertTrue(p.vivid());
        assertEquals(Lab.hue(Lab.fromRgb(0xc02020)), Lab.hue(Lab.fromRgb(p.accent())), 1.0);
        assertEquals(1, p.clustering().swatches().size(), "one distinct colour → one cluster");
        assertEquals(-1, p.secondary(), "no second hue available");
    }

    @Test
    void greyscaleCoverIsNotVivid() {
        int[] px = new int[1000];
        for (int i = 0; i < px.length; i++) {
            int v = i % 256;
            px[i] = (v << 16) | (v << 8) | v;
        }
        assertFalse(PaletteExtractor.extract(px, 7).vivid());
    }

    @Test
    void colourfulMinorityBeatsGreyMajority() {
        // 80% dark grey background, 20% saturated orange: the accent must be the orange
        int[] px = image(new int[]{0x202022, 0xff8800}, new int[]{800, 200});
        PaletteExtractor.Palette p = PaletteExtractor.extract(px, 3);
        assertTrue(p.vivid());
        assertTrue(Lab.hueDistance(Lab.hue(Lab.fromRgb(0xff8800)), Lab.hue(Lab.fromRgb(p.accent()))) < 5);
    }

    @Test
    void twoHuesGiveDistinctAccents() {
        int[] px = image(new int[]{0x1f4fbf, 0xe0a020, 0x101010}, new int[]{500, 300, 200});
        PaletteExtractor.Palette p = PaletteExtractor.extract(px, 11);
        assertTrue(p.secondary() >= 0);
        double gap = Lab.hueDistance(Lab.hue(Lab.fromRgb(p.accent())), Lab.hue(Lab.fromRgb(p.secondary())));
        assertTrue(gap >= PaletteExtractor.MIN_HUE_GAP, "gap " + gap);
    }

    @Test
    void sameSeedSameResultAndSharesSumToOne() {
        java.util.Random r = new java.util.Random(42);
        int[] px = new int[4096];
        for (int i = 0; i < px.length; i++) px[i] = r.nextInt(0x1000000);
        PaletteExtractor.Clustering a = PaletteExtractor.cluster(px, 6, 99), b = PaletteExtractor.cluster(px, 6, 99);
        assertEquals(a.swatches().stream().map(PaletteExtractor.Swatch::rgb).toList(),
                b.swatches().stream().map(PaletteExtractor.Swatch::rgb).toList());
        assertEquals(1.0, a.swatches().stream().mapToDouble(PaletteExtractor.Swatch::share).sum(), 1e-9);
        assertTrue(a.iterations() >= 1 && a.iterations() <= PaletteExtractor.MAX_ITERATIONS);
        for (int i = 1; i < a.swatches().size(); i++)
            assertTrue(a.swatches().get(i - 1).share() >= a.swatches().get(i).share(), "sorted by share");
    }

    @Test
    void kMeansConvergesToTrueCentresOfSeparatedGroups() {
        // three tight groups → three clusters whose centroids are the group colours
        int[] px = image(new int[]{0xff0000, 0x00ff00, 0x0000ff}, new int[]{100, 100, 100});
        PaletteExtractor.Clustering cl = PaletteExtractor.cluster(px, 3, 5);
        assertEquals(3, cl.swatches().size());
        for (PaletteExtractor.Swatch s : cl.swatches()) assertEquals(1 / 3.0, s.share(), 1e-9);
        assertEquals(java.util.Set.of(0xff0000, 0x00ff00, 0x0000ff),
                new java.util.HashSet<>(cl.swatches().stream().map(PaletteExtractor.Swatch::rgb).toList()));
    }

    @Test
    void readableClampsLightnessAndKeepsHue() {
        int darkBlue = 0x0a0a40;
        int r = PaletteExtractor.readable(darkBlue, 70, 82, 32, 62);
        double[] lab = Lab.fromRgb(r);
        assertTrue(lab[0] >= 69 && lab[0] <= 83, "L=" + lab[0]);
        assertTrue(Lab.hueDistance(Lab.hue(Lab.fromRgb(darkBlue)), Lab.hue(lab)) < 12);
    }

    @Test
    void inputValidation() {
        assertThrows(IllegalArgumentException.class, () -> PaletteExtractor.cluster(null, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> PaletteExtractor.cluster(new int[]{1}, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PaletteExtractor.cluster(new int[]{1}, 65, 0));
        assertTrue(PaletteExtractor.cluster(new int[0], 3, 0).swatches().isEmpty());
        assertFalse(PaletteExtractor.extract(new int[0], 0).vivid(), "empty image → default theme");
    }
}
