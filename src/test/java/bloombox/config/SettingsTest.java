package bloombox.config;

import bloombox.player.ReplayGain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @TempDir
    Path dir;

    @Test
    void replayGainDefaultsToOffWithZeroPreamp() {
        assertEquals(ReplayGain.DISABLED, Settings.load(dir.resolve("missing.properties")).replayGain());
    }

    @Test
    void replayGainRoundTripsThroughTheFile() throws IOException {
        Path f = dir.resolve("config.properties");
        Settings s = Settings.load(f);
        s.setReplayGain(new ReplayGain(ReplayGain.Mode.ALBUM, -4.5));
        s.save();
        assertEquals(new ReplayGain(ReplayGain.Mode.ALBUM, -4.5), Settings.load(f).replayGain());
    }

    @Test
    void handEditedBadValuesAreSanitised() throws IOException {
        Path f = dir.resolve("config.properties");
        Files.writeString(f, "player.replaygain=LOUDER\nplayer.replaygain.preamp=abc\n");
        assertEquals(ReplayGain.DISABLED, Settings.load(f).replayGain());
        Files.writeString(f, "player.replaygain=track\nplayer.replaygain.preamp=99\n");
        assertEquals(new ReplayGain(ReplayGain.Mode.TRACK, 12), Settings.load(f).replayGain());
    }
}
