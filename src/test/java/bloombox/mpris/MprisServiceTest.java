package bloombox.mpris;

import bloombox.library.Library;
import bloombox.library.Lyrics;
import bloombox.library.Track;
import bloombox.player.PlayQueue;
import bloombox.player.PlaybackController;
import bloombox.player.PlaybackControllerTest;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the MPRIS object without a bus: property mapping and command dispatch. */
class MprisServiceTest {

    PlaybackControllerTest.FakeAudio audio;
    PlaybackController ctl;
    MprisService mpris;
    boolean[] quit = {false};

    @BeforeEach
    void setUp() {
        Track a = new Track(-1, Path.of("/m/a.flac"), "Digital Love", "Daft Punk", "Discovery", 3, 1, 301_000, null);
        Track b = new Track(-1, Path.of("/m/b.flac"), "Untitled", "", "", 0, 0, 0, null);
        audio = new PlaybackControllerTest.FakeAudio();
        ctl = new PlaybackController(Library.of(Path.of("/m"), List.of(a, b), List.of(Lyrics.EMPTY, Lyrics.EMPTY)),
                audio, new PlayQueue(new Random(1)), Runnable::run);
        mpris = new MprisService(ctl, t -> Path.of("/cache/art.png"), () -> quit[0] = true);
    }

    @Test
    void metadataFollowsSpecTypes() {
        ctl.playQueue(List.of(1, 0), 0, 0); // id 1 = Digital Love (library order puts "" artist first)
        Track t = ctl.current();
        Map<String, Variant<?>> m = MprisService.metadata(t, x -> Path.of("/cache/art.png"));
        assertEquals("o", m.get("mpris:trackid").getSig());
        assertEquals(new DBusPath("/org/bloombox/track/" + t.id()), m.get("mpris:trackid").getValue());
        assertEquals("x", m.get("mpris:length").getSig());
        assertEquals(301_000_000L, m.get("mpris:length").getValue());
        assertEquals("as", m.get("xesam:artist").getSig());
        assertEquals(List.of("Daft Punk"), m.get("xesam:artist").getValue());
        assertEquals("file:///cache/art.png", m.get("mpris:artUrl").getValue());
        assertEquals("file:///m/a.flac", m.get("xesam:url").getValue());
    }

    @Test
    void emptyFieldsAreOmittedAndNoTrackHasSentinelId() {
        Map<String, Variant<?>> none = MprisService.metadata(null, null);
        assertEquals(1, none.size());
        assertEquals(new DBusPath(MprisService.NO_TRACK), none.get("mpris:trackid").getValue());
        Track untitled = ctl.library().tracks().stream().filter(t -> t.artist().isEmpty()).findFirst().orElseThrow();
        Map<String, Variant<?>> m = MprisService.metadata(untitled, t -> null);
        assertFalse(m.containsKey("xesam:artist"));
        assertFalse(m.containsKey("mpris:length"));
        assertFalse(m.containsKey("mpris:artUrl"));
    }

    @Test
    void playerPropertiesReflectControllerState() {
        Map<String, Variant<?>> p = mpris.GetAll(MprisService.PLAYER_IFACE);
        assertEquals("Stopped", p.get("PlaybackStatus").getValue());
        assertEquals(false, p.get("CanPause").getValue());
        ctl.playQueue(List.of(0, 1), 0, 0);
        audio.position = 12.5;
        p = mpris.GetAll(MprisService.PLAYER_IFACE);
        assertEquals("Playing", p.get("PlaybackStatus").getValue());
        assertEquals(12_500_000L, p.get("Position").getValue());
        assertEquals(0.7, (Double) p.get("Volume").getValue(), 1e-9);
        assertEquals(true, p.get("CanGoNext").getValue());
        assertEquals("None", p.get("LoopStatus").getValue());
        assertEquals("Playing", ((Variant<?>) mpris.Get(MprisService.PLAYER_IFACE, "PlaybackStatus")).getValue());
        assertEquals("bloombox", mpris.GetAll(MprisService.ROOT_IFACE).get("Identity").getValue());
    }

    @Test
    void writablePropertiesAndCommandsReachController() {
        ctl.playQueue(List.of(0, 1), 0, 0);
        mpris.Set(MprisService.PLAYER_IFACE, "LoopStatus", "Track");
        assertEquals(PlayQueue.Repeat.ONE, ctl.repeat());
        mpris.Set(MprisService.PLAYER_IFACE, "Shuffle", new Variant<>(true)); // wrapped form also accepted
        assertTrue(ctl.shuffle());
        mpris.Set(MprisService.PLAYER_IFACE, "Volume", 0.5);
        assertEquals(50.0, audio.volume, 1e-9);
        mpris.PlayPause();
        assertEquals(PlaybackController.Status.PAUSED, ctl.status());
        mpris.Seek(5_000_000);
        assertEquals(5.0, audio.position, 1e-9);
        mpris.Quit();
        assertTrue(quit[0]);
    }

    @Test
    void setPositionIgnoresStaleTrackIdAndOutOfRange() {
        ctl.playQueue(List.of(0, 1), 0, 0);
        String cur = MprisService.trackPath(ctl.current());
        mpris.SetPosition(new DBusPath("/org/bloombox/track/999"), 10_000_000);
        assertEquals(0.0, audio.position);
        mpris.SetPosition(new DBusPath(cur), -1);
        mpris.SetPosition(new DBusPath(cur), 10_000_000_000L);
        assertEquals(0.0, audio.position);
        mpris.SetPosition(new DBusPath(cur), 20_000_000);
        assertEquals(20.0, audio.position, 1e-9);
    }

    @Test
    void invalidRequestsRaiseDBusErrors() {
        assertThrows(DBusExecutionException.class, () -> mpris.Get(MprisService.PLAYER_IFACE, "Nope"));
        assertThrows(DBusExecutionException.class, () -> mpris.GetAll("org.example.Nope"));
        assertThrows(DBusExecutionException.class, () -> mpris.Set(MprisService.PLAYER_IFACE, "LoopStatus", "Sometimes"));
        assertThrows(DBusExecutionException.class, () -> mpris.Set(MprisService.ROOT_IFACE, "Identity", "x"));
        assertThrows(DBusExecutionException.class, () -> mpris.OpenUri("file:///x.flac"));
    }
}
