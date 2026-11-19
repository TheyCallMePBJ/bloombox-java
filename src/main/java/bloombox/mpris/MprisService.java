package bloombox.mpris;

import bloombox.library.Track;
import bloombox.player.PlayQueue;
import bloombox.player.PlaybackController;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Publishes bloombox on the D-Bus session bus as an MPRIS2 player so desktop media
 * keys, {@code playerctl}, and panel widgets can control it. Every command is
 * forwarded to the {@link PlaybackController}; controller events are turned into
 * {@code PropertiesChanged} / {@code Seeked} signals.
 *
 * <p>Bus name: {@code org.mpris.MediaPlayer2.bloombox}, or
 * {@code org.mpris.MediaPlayer2.bloombox.instance<pid>} if another instance holds it.
 */
public final class MprisService implements MediaPlayer2, MediaPlayer2Player, Properties,
        PlaybackController.Listener, AutoCloseable {

    public static final String OBJECT_PATH = "/org/mpris/MediaPlayer2";
    static final String ROOT_IFACE = "org.mpris.MediaPlayer2";
    static final String PLAYER_IFACE = "org.mpris.MediaPlayer2.Player";
    static final String NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack";

    private final PlaybackController player;
    private final Runnable onQuit;
    private final Function<Track, Path> artLookup;
    private DBusConnection conn;
    private String busName;

    /**
     * @param artLookup returns a cover-art image file for a track (or null); exposed as mpris:artUrl
     * @param onQuit    invoked when a client calls Quit()
     */
    public MprisService(PlaybackController player, Function<Track, Path> artLookup, Runnable onQuit) {
        this.player = player;
        this.artLookup = artLookup == null ? t -> null : artLookup;
        this.onQuit = onQuit == null ? () -> { } : onQuit;
    }

    /** Connects to the session bus and claims a name. Throws if no session bus is available. */
    public void start() throws DBusException {
        conn = DBusConnectionBuilder.forSessionBus().withShared(false).build();
        conn.exportObject(OBJECT_PATH, this);
        String base = "org.mpris.MediaPlayer2.bloombox";
        try {
            conn.requestBusName(base);
            busName = base;
        } catch (DBusException taken) {
            busName = base + ".instance" + ProcessHandle.current().pid();
            conn.requestBusName(busName);
        }
        player.addListener(this);
    }

    public String busName() {
        return busName;
    }

    @Override
    public void close() {
        if (conn == null) return;
        try {
            if (busName != null) conn.releaseBusName(busName);
        } catch (DBusException | RuntimeException ignored) {
            // shutting down anyway
        }
        try {
            conn.close();
        } catch (Exception ignored) {
            // shutting down anyway
        }
        conn = null;
    }

    // ---- DBusInterface ----------------------------------------------------------

    @Override
    public String getObjectPath() {
        return OBJECT_PATH;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    // ---- org.mpris.MediaPlayer2 -------------------------------------------------

    @Override
    public void Raise() {
        // a terminal app cannot raise its own window
    }

    @Override
    public void Quit() {
        onQuit.run();
    }

    // ---- org.mpris.MediaPlayer2.Player ------------------------------------------

    @Override public void Next() { player.next(); }
    @Override public void Previous() { player.previous(); }
    @Override public void Pause() { player.pause(); }
    @Override public void PlayPause() { player.playPause(); }
    @Override public void Stop() { player.stop(); }
    @Override public void Play() { player.play(); }

    @Override
    public void Seek(long offsetMicros) {
        player.seekRelative(offsetMicros / 1e6);
    }

    @Override
    public void SetPosition(DBusPath trackId, long positionMicros) {
        Track t = player.current();
        if (t == null || !trackPath(t).equals(trackId.getPath())) return; // stale request: spec says ignore
        if (positionMicros < 0 || positionMicros > player.duration() * 1e6) return;
        player.seekAbsolute(positionMicros / 1e6);
    }

    @Override
    public void OpenUri(String uri) {
        throw new DBusExecutionException("OpenUri is not supported by bloombox");
    }

    // ---- org.freedesktop.DBus.Properties ----------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <A> A Get(String iface, String property) {
        Variant<?> v = GetAll(iface).get(property);
        if (v == null) throw new DBusExecutionException("no such property " + iface + "." + property);
        return (A) v;
    }

    @Override
    public <A> void Set(String iface, String property, A value) {
        Object v = value instanceof Variant<?> var ? var.getValue() : value;
        if (!PLAYER_IFACE.equals(iface)) throw new DBusExecutionException("read-only property " + property);
        switch (property) {
            case "Volume" -> player.setVolume(((Number) v).doubleValue() * 100.0);
            case "Shuffle" -> player.setShuffle((Boolean) v);
            case "LoopStatus" -> player.setRepeat(repeatFromLoopStatus((String) v));
            case "Rate" -> { /* only 1.0 supported (MinimumRate == MaximumRate == 1.0) */ }
            default -> throw new DBusExecutionException("read-only property " + property);
        }
    }

    @Override
    public Map<String, Variant<?>> GetAll(String iface) {
        Map<String, Variant<?>> m = new LinkedHashMap<>();
        if (ROOT_IFACE.equals(iface)) {
            m.put("CanQuit", new Variant<>(true));
            m.put("CanRaise", new Variant<>(false));
            m.put("HasTrackList", new Variant<>(false));
            m.put("Identity", new Variant<>("bloombox"));
            m.put("SupportedUriSchemes", new Variant<>(List.of("file"), "as"));
            m.put("SupportedMimeTypes", new Variant<>(
                    List.of("audio/flac", "audio/mpeg", "audio/ogg", "audio/opus"), "as"));
        } else if (PLAYER_IFACE.equals(iface)) {
            boolean hasTrack = player.current() != null;
            m.put("PlaybackStatus", new Variant<>(playbackStatus(player.status())));
            m.put("LoopStatus", new Variant<>(loopStatus(player.repeat())));
            m.put("Rate", new Variant<>(1.0));
            m.put("Shuffle", new Variant<>(player.shuffle()));
            m.put("Metadata", new Variant<>(metadata(player.current(), artLookup), "a{sv}"));
            m.put("Volume", new Variant<>(player.volume() / 100.0));
            m.put("Position", new Variant<>(Math.round(player.position() * 1e6)));
            m.put("MinimumRate", new Variant<>(1.0));
            m.put("MaximumRate", new Variant<>(1.0));
            m.put("CanGoNext", new Variant<>(player.canGoNext()));
            m.put("CanGoPrevious", new Variant<>(player.canGoPrevious()));
            m.put("CanPlay", new Variant<>(hasTrack || !player.queueTracks().isEmpty()));
            m.put("CanPause", new Variant<>(hasTrack));
            m.put("CanSeek", new Variant<>(hasTrack));
            m.put("CanControl", new Variant<>(true));
        } else {
            throw new DBusExecutionException("unknown interface " + iface);
        }
        return m;
    }

    // ---- controller events → D-Bus signals --------------------------------------

    @Override
    public void onTrackChanged(Track track) {
        emit("Metadata", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek", "PlaybackStatus");
    }

    @Override
    public void onStatusChanged(PlaybackController.Status status) {
        emit("PlaybackStatus", "CanPause", "CanSeek");
    }

    @Override
    public void onSeeked(double seconds) {
        if (conn == null) return;
        try {
            conn.sendMessage(new Seeked(OBJECT_PATH, Math.round(seconds * 1e6)));
        } catch (DBusException | RuntimeException ignored) {
            // bus gone; media keys just stop working
        }
    }

    @Override
    public void onVolumeChanged(double volume) {
        emit("Volume");
    }

    @Override
    public void onModesChanged(boolean shuffle, PlayQueue.Repeat repeat) {
        emit("Shuffle", "LoopStatus", "CanGoNext", "CanGoPrevious");
    }

    private void emit(String... names) {
        if (conn == null) return;
        Map<String, Variant<?>> all = GetAll(PLAYER_IFACE);
        Map<String, Variant<?>> changed = new HashMap<>();
        for (String n : names) changed.put(n, all.get(n));
        try {
            conn.sendMessage(new PropertiesChanged(OBJECT_PATH, PLAYER_IFACE, changed, List.of()));
        } catch (DBusException | RuntimeException ignored) {
            // bus gone
        }
    }

    // ---- mapping helpers (package-private for tests) ----------------------------

    static String trackPath(Track t) {
        return "/org/bloombox/track/" + t.id();
    }

    static Map<String, Variant<?>> metadata(Track t, Function<Track, Path> artLookup) {
        Map<String, Variant<?>> m = new LinkedHashMap<>();
        if (t == null) {
            m.put("mpris:trackid", new Variant<>(new DBusPath(NO_TRACK)));
            return m;
        }
        m.put("mpris:trackid", new Variant<>(new DBusPath(trackPath(t))));
        if (t.durationMs() > 0) m.put("mpris:length", new Variant<>(t.durationMs() * 1000L));
        m.put("xesam:title", new Variant<>(t.title()));
        if (!t.artist().isEmpty()) m.put("xesam:artist", new Variant<>(List.of(t.artist()), "as"));
        if (!t.album().isEmpty()) m.put("xesam:album", new Variant<>(t.album()));
        if (t.trackNo() > 0) m.put("xesam:trackNumber", new Variant<>(t.trackNo()));
        m.put("xesam:url", new Variant<>(t.path().toUri().toString()));
        Path art = artLookup == null ? null : artLookup.apply(t);
        if (art != null) m.put("mpris:artUrl", new Variant<>(art.toUri().toString()));
        return m;
    }

    static String playbackStatus(PlaybackController.Status s) {
        return switch (s) {
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            case STOPPED -> "Stopped";
        };
    }

    static String loopStatus(PlayQueue.Repeat r) {
        return switch (r) {
            case OFF -> "None";
            case ALL -> "Playlist";
            case ONE -> "Track";
        };
    }

    static PlayQueue.Repeat repeatFromLoopStatus(String s) {
        return switch (s) {
            case "Track" -> PlayQueue.Repeat.ONE;
            case "Playlist" -> PlayQueue.Repeat.ALL;
            case "None" -> PlayQueue.Repeat.OFF;
            default -> throw new DBusExecutionException("invalid LoopStatus " + s);
        };
    }
}
