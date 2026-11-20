package bloombox.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayGainTest {

    @Test
    void modesMapToMpvValues() {
        assertEquals("no", ReplayGain.Mode.OFF.mpvValue);
        assertEquals("track", ReplayGain.Mode.TRACK.mpvValue);
        assertEquals("album", ReplayGain.Mode.ALBUM.mpvValue);
    }

    @Test
    void parseIsLenientAndDefaultsToOff() {
        assertEquals(ReplayGain.Mode.ALBUM, ReplayGain.Mode.parse(" Album "));
        assertEquals(ReplayGain.Mode.TRACK, ReplayGain.Mode.parse("TRACK"));
        assertEquals(ReplayGain.Mode.OFF, ReplayGain.Mode.parse("off"));
        assertEquals(ReplayGain.Mode.OFF, ReplayGain.Mode.parse("loud"));
        assertEquals(ReplayGain.Mode.OFF, ReplayGain.Mode.parse(null));
    }

    @Test
    void preampIsClampedToPlusMinus12AndNanBecomesZero() {
        assertEquals(12, new ReplayGain(ReplayGain.Mode.TRACK, 40).preampDb());
        assertEquals(-12, new ReplayGain(ReplayGain.Mode.TRACK, -12.5).preampDb());
        assertEquals(3.5, new ReplayGain(ReplayGain.Mode.TRACK, 3.5).preampDb());
        assertEquals(0, new ReplayGain(ReplayGain.Mode.TRACK, Double.NaN).preampDb());
        assertEquals(12, new ReplayGain(ReplayGain.Mode.TRACK, Double.POSITIVE_INFINITY).preampDb());
        assertEquals(ReplayGain.Mode.OFF, new ReplayGain(null, 0).mode());
    }

    @Test
    void mpvArgsAreLocaleIndependent() {
        java.util.Locale prev = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY); // decimal comma locale
            assertEquals(List.of("--replaygain=album", "--replaygain-preamp=-1.5"),
                    new ReplayGain(ReplayGain.Mode.ALBUM, -1.5).mpvArgs());
            assertEquals("-1.5 dB", ReplayGain.formatDb(-1.5));
        } finally {
            java.util.Locale.setDefault(prev);
        }
        assertEquals(List.of("--replaygain=no", "--replaygain-preamp=0.0"), ReplayGain.DISABLED.mpvArgs());
    }

    @Test
    void formatsDecibels() {
        assertEquals("+3 dB", ReplayGain.formatDb(3));
        assertEquals("0 dB", ReplayGain.formatDb(0));
        assertEquals("-12 dB", ReplayGain.formatDb(-12));
        assertEquals("+0.5 dB", ReplayGain.formatDb(0.5));
    }

    @Test
    void withersKeepTheOtherField() {
        ReplayGain rg = new ReplayGain(ReplayGain.Mode.ALBUM, 4);
        assertEquals(new ReplayGain(ReplayGain.Mode.TRACK, 4), rg.withMode(ReplayGain.Mode.TRACK));
        assertEquals(new ReplayGain(ReplayGain.Mode.ALBUM, 12), rg.withPreamp(13));
        assertFalse(ReplayGain.DISABLED.enabled());
        assertTrue(rg.enabled());
    }
}
