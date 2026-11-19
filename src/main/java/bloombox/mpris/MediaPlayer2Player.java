package bloombox.mpris;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/** MPRIS player interface: https://specifications.freedesktop.org/mpris-spec/latest/Player_Interface.html */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
public interface MediaPlayer2Player extends DBusInterface {
    void Next();

    void Previous();

    void Pause();

    void PlayPause();

    void Stop();

    void Play();

    /** Relative seek, in microseconds. */
    void Seek(long offset);

    /** Absolute seek, in microseconds; ignored if {@code trackId} is not the current track. */
    void SetPosition(DBusPath trackId, long position);

    void OpenUri(String uri);

    /** Emitted when the position jumps (not during normal playback). */
    class Seeked extends DBusSignal {
        private final long position;

        public Seeked(String path, long position) throws DBusException {
            super(path, position);
            this.position = position;
        }

        public long getPosition() {
            return position;
        }
    }
}
