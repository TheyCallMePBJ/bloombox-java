package bloombox.mpris;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;

/** Root MPRIS interface: https://specifications.freedesktop.org/mpris-spec/latest/Media_Player.html */
@DBusInterfaceName("org.mpris.MediaPlayer2")
@SuppressWarnings("checkstyle:MethodName")
public interface MediaPlayer2 extends DBusInterface {
    void Raise();

    void Quit();
}
