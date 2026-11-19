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
import bloombox.library.LrcParser;
import bloombox.library.Lyrics;
import bloombox.library.Track;
import bloombox.player.MpvPlayer;

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
              bloombox scan [DIR]                 scan library (DIR is saved as the default)
              bloombox search [OPTIONS] QUERY...  search the library and explain the work done
                  --no-bloom   baseline: KMP every lyric track (no Bloom screening)
                  --plain-trie use the uncompressed trie instead of the radix trie
                  --limit N    max results to print (default 20)
              bloombox --play FILE                play one file with synced lyrics on stdout

            query syntax:
              blue mon              words (prefixes), ANDed, any field
              artist:daft t:digi    restrict to title/t, artist/a, album/al, lyrics/l
              "how does it feel"    exact phrase (lyrics: Bloom screen + KMP)
            """;

    public static void main(String[] args) throws Exception {
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
        Library lib = loadLibrary(settings);
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

    private static Library loadLibrary(Settings settings) throws Exception {
        Path dir = settings.musicDir().orElse(null);
        if (dir == null) fail("no music directory configured; run: bloombox scan ~/Music");
        boolean tty = System.console() != null;
        Library lib = LibraryLoader.load(dir, (done, total) -> {
            if (tty && (done % 25 == 0 || done == total)) System.err.printf("\rscanning %d/%d", done, total);
        });
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
        Path lrc = Track.findLrc(file);
        Lyrics lyrics = lrc != null ? LrcParser.parse(lrc) : Lyrics.EMPTY;
        System.out.printf("lyrics: %d lines (%s)%n", lyrics.lines().size(), lyrics.synced() ? "synced" : "unsynced");

        CountDownLatch done = new CountDownLatch(1);
        try (MpvPlayer player = MpvPlayer.start(Settings.load().volume())) {
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
