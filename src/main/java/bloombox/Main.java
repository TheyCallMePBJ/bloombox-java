package bloombox;

import bloombox.config.Settings;
import bloombox.library.Lyrics;
import bloombox.library.LrcParser;
import bloombox.library.Track;
import bloombox.player.MpvPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--play")) {
            smokePlay(Path.of(args[1]));
            return;
        }
        System.out.println("bloombox 0.1.0 — TUI not built yet.");
        System.out.println("Dev mode:  java -jar target/bloombox.jar --play <audio-file>");
    }

    /** Phase-1 smoke test: play one file through mpv and print synced lyrics to stdout. */
    private static void smokePlay(Path file) throws Exception {
        if (!Files.isRegularFile(file) || Track.formatOf(file) == null) {
            System.err.println("not a supported audio file (flac/mp3/ogg/opus): " + file);
            System.exit(2);
        }
        Path lrc = Track.lrcSiblingOf(file);
        Lyrics lyrics = Files.isRegularFile(lrc) ? LrcParser.parse(lrc) : Lyrics.EMPTY;
        System.out.printf("lyrics: %d lines (%s)%n", lyrics.lines().size(), lyrics.synced() ? "synced" : "unsynced");

        Settings settings = Settings.load();
        CountDownLatch done = new CountDownLatch(1);
        try (MpvPlayer player = MpvPlayer.start(settings.volume())) {
            player.addListener(new MpvPlayer.Listener() {
                int lastLine = -2;

                @Override
                public void onPosition(double s) {
                    int idx = lyrics.indexAt((long) (s * 1000));
                    if (idx != lastLine) {
                        lastLine = idx;
                        String text = idx >= 0 ? lyrics.lines().get(idx).text() : "♪";
                        System.out.printf("[%02d:%05.2f] %s%n", (int) s / 60, s % 60, text);
                    }
                }

                @Override
                public void onEndFile(boolean naturalEnd, String reason) {
                    if (!"redirect".equals(reason)) done.countDown();
                }

                @Override
                public void onDisconnected() {
                    done.countDown();
                }
            });
            player.load(file);
            done.await();
        }
    }
}
