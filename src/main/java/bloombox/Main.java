package bloombox;

import bloombox.config.Settings;
import bloombox.core.trie.PrefixIndex;
import bloombox.core.trie.RadixTrie;
import bloombox.core.trie.Trie;
import bloombox.index.Field;
import bloombox.index.Query;
import bloombox.index.SearchIndex;
import bloombox.index.SearchResult;
import bloombox.library.Library;
import bloombox.library.LibraryLoader;
import bloombox.library.LibraryScanner;
import bloombox.library.LrcParser;
import bloombox.library.Lyrics;
import bloombox.library.Track;
import bloombox.mpris.MprisService;
import bloombox.player.MpvPlayer;
import bloombox.player.PlaybackController;
import bloombox.tui.BloomboxTui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

public final class Main {

    private static final String USAGE = """
            bloombox 0.1.0

            usage:
              bloombox                            start the terminal UI
              bloombox scan [DIR]                 scan library (DIR is saved as the default)
              bloombox search [OPTIONS] QUERY...  search the library and explain the work done
                  --no-bloom   baseline: KMP every lyric track (no Bloom screening)
                  --plain-trie use the uncompressed trie instead of the radix trie
                  --limit N    max results to print (default 20)
              bloombox play [--shuffle] [QUERY...] headless player with MPRIS (media keys);
                                                  queues search results, or the whole library.
                                                  type n/p/t/s/r/+/-/q then Enter to control
              bloombox --play FILE                play one file with synced lyrics on stdout
              bloombox bench [--quick] [...]      run the benchmark suite (see: bloombox bench --help)

            query syntax:
              blue mon              words (prefixes), ANDed, any field
              artist:daft t:digi    restrict to title/t, artist/a, album/al, lyrics/l
              "how does it feel"    exact phrase (lyrics: Bloom screen + KMP)
            """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 && System.console() != null) {
            new BloomboxTui(Settings.load()).run();
            System.exit(0); // daemon helpers (dbus, executors) must not keep the JVM alive
            return;
        }
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        try {
            switch (args[0]) {
                case "--play" -> {
                    if (args.length != 2) fail("usage: bloombox --play FILE");
                    smokePlay(Path.of(args[1]));
                }
                case "scan" -> scan(args);
                case "search" -> search(args);
                case "play" -> headlessPlay(args);
                case "bench" -> bloombox.bench.BenchMain.main(java.util.Arrays.copyOfRange(args, 1, args.length));
                default -> fail("unknown command '" + args[0] + "'\n\n" + USAGE);
            }
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }
    }

    private static void scan(String[] args) throws Exception {
        Settings settings = Settings.load();
        if (args.length > 1) {
            Path dir = Path.of(args[1]).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) fail("not a directory: " + dir);
            settings.setMusicDir(dir);
            settings.save();
        }
        Library lib = loadLibrary(settings, true);
        System.out.println(lib.report().summary());
        for (String w : lib.report().warnings().subList(0, Math.min(10, lib.report().warnings().size()))) {
            System.out.println("  warn: " + w);
        }
        SearchIndex idx = SearchIndex.build(lib);
        SearchIndex.BuildStats b = idx.buildStats();
        System.out.printf("index: built in %d ms, %d postings, bloom %.1f KiB total%n",
                b.buildMillis(), b.postings(), b.bloomBits() / 8.0 / 1024);
        for (Field f : Field.values()) {
            System.out.printf("  %-7s %7d distinct words, %8d radix-trie nodes%n",
                    f.label(), b.distinctWords().get(f), b.trieNodes().get(f));
        }
    }

    private static void search(String[] args) throws Exception {
        boolean useBloom = true;
        Supplier<PrefixIndex<Integer>> trie = RadixTrie::new;
        int limit = 20;
        List<String> words = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--no-bloom" -> useBloom = false;
                case "--plain-trie" -> trie = Trie::new;
                case "--limit" -> {
                    if (i + 1 >= args.length) fail("--limit needs a number");
                    try {
                        limit = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        fail("--limit needs a number");
                    }
                    if (limit < 1) fail("--limit must be >= 1");
                }
                default -> words.add(args[i]);
            }
        }
        Query q = Query.parse(String.join(" ", words));
        if (q.isEmpty()) fail("empty query (only punctuation or nothing given)");

        Library lib = loadLibrary(Settings.load());
        SearchIndex idx = SearchIndex.build(lib, trie, 0.01);
        SearchIndex.Outcome out = idx.search(q, new SearchIndex.Options(limit, useBloom));

        if (out.results().isEmpty()) System.out.println("no matches");
        for (SearchResult r : out.results()) {
            Track t = r.track();
            List<String> fields = new ArrayList<>();
            for (Field f : Field.values()) if (r.matched(f)) fields.add(f.label());
            System.out.printf("%4d %-20s %s — %s  [%s]%n", r.score(), fields, nz(t.artist()), t.title(), nz(t.album()));
            if (r.lyricText() != null) {
                String at = r.lyricTimeMs() >= 0
                        ? String.format("%d:%02d", r.lyricTimeMs() / 60000, r.lyricTimeMs() / 1000 % 60) : "--:--";
                System.out.printf("%26s♪ %s  %s%n", "", at, r.lyricText());
            }
        }
        System.out.println();
        for (String line : out.report().describe()) System.out.println("· " + line);
    }

    private static void headlessPlay(String[] args) throws Exception {
        boolean shuffle = false;
        List<String> words = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--shuffle")) shuffle = true;
            else words.add(args[i]);
        }
        Settings settings = Settings.load();
        Library lib = loadLibrary(settings);
        if (lib.isEmpty()) fail("library is empty");
        List<Integer> ids = new ArrayList<>();
        if (words.isEmpty()) {
            for (Track t : lib.tracks()) ids.add(t.id());
        } else {
            SearchIndex.Outcome out = SearchIndex.build(lib).search(Query.parse(String.join(" ", words)),
                    new SearchIndex.Options(10_000, true));
            for (SearchResult r : out.results()) ids.add(r.track().id());
            if (ids.isEmpty()) fail("no matches for: " + String.join(" ", words));
        }

        MpvPlayer mpv = MpvPlayer.start(settings.volume(), settings.replayGain());
        PlaybackController ctl = new PlaybackController(lib, mpv);
        CountDownLatch quit = new CountDownLatch(1);
        MprisService mpris = new MprisService(ctl, t -> null, quit::countDown);
        try {
            mpris.start();
            System.out.println("MPRIS: registered as " + mpris.busName());
        } catch (Exception e) {
            System.out.println("MPRIS unavailable (" + e.getMessage() + "); media keys disabled");
        }
        ctl.addListener(new PlaybackController.Listener() {
            @Override
            public void onTrackChanged(Track t) {
                System.out.println(t == null ? "■ stopped" : "▶ " + nz(t.artist()) + " — " + t.title());
            }

            @Override
            public void onStatusChanged(PlaybackController.Status s) {
                System.out.println("  [" + s.name().toLowerCase() + "]");
            }

            @Override
            public void onModesChanged(boolean sh, bloombox.player.PlayQueue.Repeat r) {
                System.out.println("  shuffle " + (sh ? "on" : "off") + ", repeat " + r.name().toLowerCase());
            }

            @Override
            public void onError(String message) {
                System.out.println("  ! " + message);
            }
        });
        Runnable shutdown = () -> {
            settings.setVolume((int) Math.round(ctl.volume()));
            try { settings.save(); } catch (Exception ignored) { }
            mpris.close();
            mpv.close();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown));

        ctl.setShuffle(shuffle);
        ctl.playQueue(ids, 0, 0);
        System.out.println(ids.size() + " tracks queued. n=next p=prev t=play/pause s=shuffle r=repeat +/-=volume q=quit");

        Thread stdin = new Thread(() -> {
            try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = in.readLine()) != null) {
                    switch (line.strip()) {
                        case "n" -> ctl.next();
                        case "p" -> ctl.previous();
                        case "t", "" -> ctl.playPause();
                        case "s" -> ctl.toggleShuffle();
                        case "r" -> ctl.cycleRepeat();
                        case "+" -> ctl.changeVolume(5);
                        case "-" -> ctl.changeVolume(-5);
                        case "q" -> { quit.countDown(); return; }
                        default -> System.out.println("? n p t s r + - q");
                    }
                }
            } catch (java.io.IOException ignored) {
                // stdin closed
            }
        }, "stdin");
        stdin.setDaemon(true);
        stdin.start();
        quit.await();
        System.exit(0); // runs the shutdown hook
    }

    private static Library loadLibrary(Settings settings) throws Exception {
        return loadLibrary(settings, false);
    }

    /** {@code rescan=false}: use the saved library if there is one (search / play); true: walk the folder. */
    private static Library loadLibrary(Settings settings, boolean rescan) throws Exception {
        Path dir = settings.musicDir().orElse(null);
        if (dir == null) fail("no music directory configured; run: bloombox scan ~/Music");
        boolean tty = System.console() != null;
        LibraryScanner.Progress progress = (done, total) -> {
            if (tty && (done % 25 == 0 || done == total)) System.err.printf("\rscanning %d/%d", done, total);
        };
        Library lib = rescan ? LibraryLoader.scan(dir, progress) : LibraryLoader.load(dir, progress);
        if (tty) System.err.print("\r\033[K");
        return lib;
    }

    private static String nz(String s) {
        return s.isEmpty() ? "?" : s;
    }

    private static void fail(String msg) {
        System.err.println("bloombox: " + msg);
        System.exit(2);
    }

    /** Dev helper: play one file through mpv and print synced lyrics to stdout. */
    private static void smokePlay(Path file) throws Exception {
        if (!Files.isRegularFile(file) || Track.formatOf(file) == null) {
            fail("not a supported audio file (flac/mp3/ogg/opus): " + file);
        }
        Path lrc = Track.findLyricsFile(file);
        Lyrics lyrics = lrc != null ? LrcParser.parse(lrc) : Lyrics.EMPTY;
        System.out.printf("lyrics: %d lines (%s)%n", lyrics.lines().size(), lyrics.synced() ? "synced" : "unsynced");

        CountDownLatch done = new CountDownLatch(1);
        Settings st = Settings.load();
        try (MpvPlayer player = MpvPlayer.start(st.volume(), st.replayGain())) {
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
