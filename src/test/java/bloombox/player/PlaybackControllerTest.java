package bloombox.player;

import bloombox.library.Library;
import bloombox.library.Lyrics;
import bloombox.library.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class PlaybackControllerTest {

    /** In-memory stand-in for mpv. Tests drive its events by hand. */
    public static final class FakeAudio implements AudioBackend {
        final List<MpvPlayer.Listener> listeners = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        public double position, duration = 200, volume = 70;
        public boolean paused = true;

        @Override public void addListener(MpvPlayer.Listener l) { listeners.add(l); }
        @Override public void load(Path f) { log.add("load " + f.getFileName()); position = 0; setPaused(false); }
        @Override public void stop() { log.add("stop"); }
        @Override public void setPaused(boolean p) { paused = p; listeners.forEach(l -> l.onPause(p)); }
        @Override public void seekAbsolute(double s) { log.add("seek " + s); position = s; }
        @Override public void seekRelative(double s) { seekAbsolute(position + s); }
        @Override public void setVolume(double v) { volume = v; listeners.forEach(l -> l.onVolume(v)); }
        @Override public double position() { return position; }
        @Override public double duration() { return duration; }
        @Override public boolean isPaused() { return paused; }
        @Override public double volume() { return volume; }

        void endFile(String reason) { listeners.forEach(l -> l.onEndFile("eof".equals(reason), reason)); }
        void fileLoaded() { listeners.forEach(MpvPlayer.Listener::onFileLoaded); }
        void restart() { listeners.forEach(MpvPlayer.Listener::onPlaybackRestart); }
        void tick(double s) { position = s; listeners.forEach(l -> l.onPosition(s)); }
    }

    FakeAudio audio;
    PlaybackController ctl;
    List<String> events;

    @BeforeEach
    void setUp() {
        List<Track> tracks = new ArrayList<>();
        List<Lyrics> lyrics = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tracks.add(new Track(-1, Path.of("/m/t" + i + ".flac"), "T" + i, "A", "B", i + 1, 0, 200_000, null));
            lyrics.add(Lyrics.EMPTY);
        }
        audio = new FakeAudio();
        ctl = new PlaybackController(Library.of(Path.of("/m"), tracks, lyrics), audio,
                new PlayQueue(new Random(3)), Runnable::run); // synchronous events for testing
        events = new ArrayList<>();
        ctl.addListener(new PlaybackController.Listener() {
            @Override public void onTrackChanged(Track t) { events.add("track " + (t == null ? "-" : t.title())); }
            @Override public void onStatusChanged(PlaybackController.Status s) { events.add("status " + s); }
            @Override public void onSeeked(double s) { events.add("seeked " + s); }
            @Override public void onError(String m) { events.add("error"); }
        });
    }

    @Test
    void playsQueueAndAdvancesOnNaturalEnd() {
        ctl.playQueue(List.of(0, 1, 2), 0, 0);
        assertEquals("T0", ctl.current().title());
        assertEquals(PlaybackController.Status.PLAYING, ctl.status());
        audio.endFile("eof");
        assertEquals("T1", ctl.current().title());
        audio.endFile("stop"); // replaced/stopped by us: must NOT advance
        assertEquals("T1", ctl.current().title());
        audio.endFile("eof");
        audio.endFile("eof");
        assertNull(ctl.current(), "end of queue stops");
        assertEquals(PlaybackController.Status.STOPPED, ctl.status());
        assertEquals(List.of("load t0.flac", "load t1.flac", "load t2.flac"), audio.log);
        assertTrue(events.contains("track -"));
    }

    @Test
    void previousRestartsTrackAfterThreeSeconds() {
        ctl.playQueue(List.of(0, 1), 1, 0);
        audio.position = 10;
        ctl.previous();
        assertEquals("T1", ctl.current().title(), "restart, not previous track");
        assertEquals(0.0, audio.position);
        ctl.previous();
        assertEquals("T0", ctl.current().title());
    }

    @Test
    void startOffsetSeeksAfterLoadAndReportsSeeked() {
        ctl.playQueue(List.of(2), 0, 42.5); // e.g. jump to a lyric line
        assertFalse(audio.log.contains("seek 42.5"), "must wait until the file is loaded");
        audio.fileLoaded();
        assertTrue(audio.log.contains("seek 42.5"));
        audio.restart();
        audio.tick(42.5);
        assertTrue(events.contains("seeked 42.5"));
    }

    @Test
    void pauseToggleAndStatusEvents() {
        ctl.playPause(); // empty queue: no-op
        assertNull(ctl.current());
        ctl.playQueue(List.of(0), 0, 0);
        ctl.playPause();
        assertEquals(PlaybackController.Status.PAUSED, ctl.status());
        ctl.playPause();
        assertEquals(PlaybackController.Status.PLAYING, ctl.status());
        ctl.stop();
        assertEquals(PlaybackController.Status.STOPPED, ctl.status());
        ctl.play(); // resumes the queue from its cursor
        assertEquals("T0", ctl.current().title());
    }

    @Test
    void brokenFilesAreSkippedButNotForever() {
        ctl.playQueue(List.of(0, 1, 2), 0, 0);
        audio.endFile("error");
        assertEquals("T1", ctl.current().title(), "skip the unplayable file");
        audio.endFile("error");
        audio.endFile("error");
        assertNull(ctl.current(), "all files broken: give up instead of looping");
        assertEquals(3, events.stream().filter("error"::equals).count());
    }

    @Test
    void seekIsClampedAndVolumeIsBounded() {
        ctl.seekAbsolute(5); // nothing playing: ignored
        assertTrue(audio.log.isEmpty());
        ctl.playQueue(List.of(0), 0, 0);
        ctl.seekAbsolute(-10);
        assertEquals(0.0, audio.position);
        ctl.seekAbsolute(10_000);
        assertEquals(199.5, audio.position);
        ctl.setVolume(500);
        assertEquals(130.0, audio.volume);
        ctl.changeVolume(-1000);
        assertEquals(0.0, audio.volume);
    }

    @Test
    void rejectsUnknownTrackIds() {
        assertThrows(IllegalArgumentException.class, () -> ctl.playQueue(List.of(0, 99), 0, 0));
    }
}
