package bloombox.player;

import bloombox.library.Library;
import bloombox.library.Track;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Owns "what is playing": connects the {@link PlayQueue} to the {@link AudioBackend}
 * and is the single entry point for every control surface (TUI keys, MPRIS media
 * keys, CLI). All public methods are thread-safe.
 *
 * <p>Events are dispatched on a dedicated thread (never while holding the lock),
 * so listeners may safely call back into the controller.
 */
public final class PlaybackController implements MpvPlayer.Listener {

    public enum Status { PLAYING, PAUSED, STOPPED }

    /** Callbacks for UIs / MPRIS. Invoked on the event thread. */
    public interface Listener {
        /** {@code track} is null when playback stopped (end of queue or stop()). */
        default void onTrackChanged(Track track) {}
        default void onStatusChanged(Status status) {}
        default void onPosition(double seconds) {}
        /** Position jumped (seek); MPRIS emits its Seeked signal from this. */
        default void onSeeked(double seconds) {}
        default void onVolumeChanged(double volume) {}
        default void onModesChanged(boolean shuffle, PlayQueue.Repeat repeat) {}
        default void onError(String message) {}
    }

    /** "Previous" restarts the current track instead when we are past this point. */
    static final double RESTART_THRESHOLD_S = 3.0;

    private volatile Library library;
    private final AudioBackend audio;
    private final PlayQueue queue;
    private final Executor events;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Track current;
    private Status status = Status.STOPPED;
    private double pendingStart = -1;
    private boolean awaitingSeekRestart;
    private boolean reportNextPositionAsSeek;
    private int consecutiveErrors;

    public PlaybackController(Library library, AudioBackend audio, PlayQueue queue, Executor events) {
        this.library = library;
        this.audio = audio;
        this.queue = queue;
        this.events = events;
        audio.addListener(this);
    }

    public PlaybackController(Library library, AudioBackend audio) {
        this(library, audio, new PlayQueue(), Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bloombox-events");
            t.setDaemon(true);
            return t;
        }));
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // ---- queries ----------------------------------------------------------------

    public synchronized Track current() { return current; }
    public synchronized Status status() { return status; }
    public double position() { return current == null ? 0 : audio.position(); }
    public double duration() {
        double d = audio.duration();
        return d > 0 ? d : current == null ? 0 : current.durationMs() / 1000.0;
    }
    public double volume() { return audio.volume(); }
    public synchronized boolean shuffle() { return queue.shuffle(); }
    public synchronized PlayQueue.Repeat repeat() { return queue.repeat(); }
    public synchronized boolean canGoNext() { return queue.hasNext(); }
    public synchronized boolean canGoPrevious() { return queue.hasPrevious() || current != null; }
    public synchronized List<Integer> queueTracks() { return queue.tracks(); }
    public synchronized List<Integer> queuePlayOrder() { return queue.playOrder(); }
    public synchronized int queuePosition() { return queue.currentPosition(); }
    /** Index of the playing entry in {@link #queuePlayOrder()}, or -1. */
    public synchronized int queuePlayIndex() { return queue.cursor(); }
    /** Changes when the queue's contents or play order change (see {@link PlayQueue#version()}). */
    public synchronized int queueVersion() { return queue.version(); }
    /** The track queued to play next, or null (see {@link PlayQueue#peekNext()}). */
    public synchronized Track upNext() {
        int id = queue.peekNext();
        return id < 0 || id >= library.size() ? null : library.track(id);
    }
    public Library library() { return library; }

    /**
     * Swaps in a freshly scanned library. Track ids are not stable across scans, so
     * playback stops and the queue is cleared.
     */
    public synchronized void setLibrary(Library newLibrary) {
        if (current != null) audio.stop();
        queue.clear();
        library = newLibrary;
        setCurrent(null);
    }

    // ---- commands ---------------------------------------------------------------

    /** Replaces the queue with {@code trackIds} and plays position {@code start} from {@code startSeconds}. */
    public synchronized void playQueue(List<Integer> trackIds, int start, double startSeconds) {
        for (int id : trackIds) {
            if (id < 0 || id >= library.size()) throw new IllegalArgumentException("unknown track id " + id);
        }
        queue.set(trackIds, start);
        consecutiveErrors = 0;
        startCurrent(startSeconds);
    }

    /** Plays the queue entry at {@code position} (user order). */
    public synchronized void playQueuePosition(int position) {
        queue.jumpTo(position);
        startCurrent(0);
    }

    /** Plays the entry at {@code index} of the play order (what the Queue view shows). */
    public synchronized void playQueueOrderIndex(int index) {
        queue.jumpToOrderIndex(index);
        startCurrent(0);
    }

    public synchronized void append(int trackId) {
        boolean wasEmpty = queue.isEmpty();
        queue.append(trackId);
        if (wasEmpty) startCurrent(0);
    }

    public synchronized void play() {
        if (current == null) {
            if (!queue.isEmpty()) startCurrent(0);
        } else {
            audio.setPaused(false);
        }
    }

    public synchronized void pause() {
        if (current != null) audio.setPaused(true);
    }

    public synchronized void playPause() {
        if (current == null) play();
        else audio.setPaused(!audio.isPaused());
    }

    public synchronized void stop() {
        audio.stop();
        setCurrent(null);
    }

    public synchronized void next() {
        if (!queue.hasNext()) return;
        queue.next(true);
        startCurrent(0);
    }

    public synchronized void previous() {
        if (current != null && audio.position() > RESTART_THRESHOLD_S) {
            seekAbsolute(0);
            return;
        }
        if (queue.hasPrevious()) {
            queue.previous();
            startCurrent(0);
        } else if (current != null) {
            seekAbsolute(0);
        }
    }

    public synchronized void seekAbsolute(double seconds) {
        if (current == null) return;
        double d = duration();
        double target = Math.max(0, d > 0 ? Math.min(seconds, d - 0.5) : seconds);
        awaitingSeekRestart = true;
        audio.seekAbsolute(target);
    }

    public synchronized void seekRelative(double seconds) {
        if (current == null) return;
        seekAbsolute(audio.position() + seconds);
    }

    public void setVolume(double v) {
        audio.setVolume(Math.max(0, Math.min(130, v)));
    }

    public void changeVolume(double delta) {
        setVolume(audio.volume() + delta);
    }

    public synchronized void setShuffle(boolean on) {
        queue.setShuffle(on);
        fireModes();
    }

    public synchronized void toggleShuffle() {
        setShuffle(!queue.shuffle());
    }

    public synchronized void setRepeat(PlayQueue.Repeat r) {
        queue.setRepeat(r);
        fireModes();
    }

    public synchronized void cycleRepeat() {
        setRepeat(queue.repeat().next());
    }

    // ---- internals --------------------------------------------------------------

    private void startCurrent(double startSeconds) {
        int id = queue.current();
        if (id < 0) {
            stop();
            return;
        }
        Track t = library.track(id);
        pendingStart = startSeconds > 0 ? startSeconds : -1;
        audio.load(t.path());
        setCurrent(t);
    }

    private void setCurrent(Track t) {
        boolean changed = t != current;
        current = t;
        Status s = t == null ? Status.STOPPED : Status.PLAYING;
        if (changed) fire(l -> l.onTrackChanged(t));
        setStatus(s);
    }

    private void setStatus(Status s) {
        if (s == status) return;
        status = s;
        fire(l -> l.onStatusChanged(s));
    }

    private void fireModes() {
        boolean sh = queue.shuffle();
        PlayQueue.Repeat r = queue.repeat();
        fire(l -> l.onModesChanged(sh, r));
    }

    private void fire(java.util.function.Consumer<Listener> event) {
        events.execute(() -> {
            for (Listener l : listeners) {
                try {
                    event.accept(l);
                } catch (RuntimeException e) {
                    // a misbehaving listener must not break playback
                }
            }
        });
    }

    // ---- audio backend events (reader thread) ------------------------------------

    @Override
    public synchronized void onFileLoaded() {
        consecutiveErrors = 0;
        if (pendingStart > 0) {
            awaitingSeekRestart = true;
            audio.seekAbsolute(pendingStart);
            pendingStart = -1;
        }
    }

    @Override
    public synchronized void onPlaybackRestart() {
        if (awaitingSeekRestart) {
            awaitingSeekRestart = false;
            reportNextPositionAsSeek = true;
        }
    }

    @Override
    public void onPosition(double seconds) {
        boolean seek;
        synchronized (this) {
            if (current == null) return;
            seek = reportNextPositionAsSeek;
            reportNextPositionAsSeek = false;
        }
        if (seek) fire(l -> l.onSeeked(seconds));
        fire(l -> l.onPosition(seconds));
    }

    @Override
    public synchronized void onPause(boolean paused) {
        if (current != null) setStatus(paused ? Status.PAUSED : Status.PLAYING);
    }

    @Override
    public void onVolume(double volume) {
        fire(l -> l.onVolumeChanged(volume));
    }

    @Override
    public synchronized void onEndFile(boolean naturalEnd, String reason) {
        if (naturalEnd) {
            advanceAfterEnd();
        } else if ("error".equals(reason)) {
            String name = current == null ? "track" : current.path().getFileName().toString();
            fire(l -> l.onError("could not play " + name));
            // skip unplayable files, but never loop forever over a queue of broken files
            if (++consecutiveErrors < Math.max(1, queue.size())) advanceAfterEnd();
            else stop();
        }
        // "stop" / "redirect" / "quit": replaced or stopped by us; nothing to do
    }

    private void advanceAfterEnd() {
        int id = queue.next(false);
        if (id < 0) {
            setCurrent(null);
        } else {
            startCurrent(0);
        }
    }

    @Override
    public void onDisconnected() {
        fire(l -> l.onError("audio engine (mpv) exited"));
        synchronized (this) {
            setCurrent(null);
        }
    }
}
