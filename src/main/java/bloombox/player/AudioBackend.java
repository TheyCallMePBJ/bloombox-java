package bloombox.player;

import java.nio.file.Path;

/** The audio-engine operations {@link PlaybackController} needs. Implemented by {@link MpvPlayer}. */
public interface AudioBackend {
    void addListener(MpvPlayer.Listener l);
    void load(Path file);
    void stop();
    void setPaused(boolean paused);
    void seekAbsolute(double seconds);
    void seekRelative(double seconds);
    void setVolume(double volume);
    double position();
    double duration();
    boolean isPaused();
    double volume();
}
