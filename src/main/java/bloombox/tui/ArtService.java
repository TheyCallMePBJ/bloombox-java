package bloombox.tui;

import bloombox.library.Track;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds, scales and caches cover art.
 *
 * <p>Source order: embedded picture (via jaudiotagger), then a cover image in the
 * track's folder ({@code cover/folder/front/album.*}, else the only image there).
 * The image is scaled to at most 512 px and stored as PNG under
 * {@code ~/.cache/bloombox/art/<sha1>.png}; that file feeds the kitty graphics
 * protocol and MPRIS {@code mpris:artUrl}. Art is cached per (folder, album).
 */
final class ArtService implements AutoCloseable {

    /** A decoded cover. {@code id} is a small unique number used as the kitty image id. */
    record Art(int id, BufferedImage image, Path pngFile, byte[] png) {}

    private static final int MAX_PX = 512;
    private static final List<String> NAMES = List.of("cover", "folder", "front", "album", "albumart", "art");
    // kitty image ids are shared by everything in the terminal window: start at a random
    // offset (< 2^24, so the id fits in the placeholder's RGB foreground colour)
    private static final AtomicInteger IDS =
            new AtomicInteger(0x10000 + new java.util.Random().nextInt(0xE00000));

    private final Path dir;
    private final Runnable onReady;
    private final Map<String, Optional<Art>> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loading = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bloombox-art");
        t.setDaemon(true);
        return t;
    });

    ArtService(Path dir, Runnable onReady) {
        this.dir = dir;
        this.onReady = onReady;
    }

    /** Loaded art for a track, or null if not (yet) available. Never blocks. */
    Art get(Track t) {
        Optional<Art> a = cache.get(key(t));
        return a == null ? null : a.orElse(null);
    }

    /** True once the art lookup for {@code t} has finished (with or without finding art). */
    boolean known(Track t) {
        return cache.get(key(t)) != null;
    }

    boolean isLoading(Track t) {
        return loading.containsKey(key(t));
    }

    /** Starts loading in the background. */
    void request(Track t) {
        String k = key(t);
        if (cache.containsKey(k) || loading.putIfAbsent(k, Boolean.TRUE) != null) return;
        worker.submit(() -> {
            try {
                load(t);
                onReady.run();
            } finally {
                loading.remove(k);
            }
        });
    }

    /** Blocking lookup of the cached PNG file (for MPRIS artUrl). */
    Path cachedArtFile(Track t) {
        if (t == null) return null;
        Art a = load(t);
        return a == null ? null : a.pngFile();
    }

    // synchronized: the art worker and the MPRIS thread (cachedArtFile) may ask for the same
    // track at once; without this both would decode it and create two images with different ids
    private synchronized Art load(Track t) {
        String k = key(t);
        Optional<Art> cached = cache.get(k);
        if (cached != null) return cached.orElse(null);
        Art art = null;
        try {
            Path out = dir.resolve(sha1(k) + ".png");
            BufferedImage img = null;
            byte[] png = null;
            if (Files.isRegularFile(out)) {
                png = Files.readAllBytes(out);
                img = ImageIO.read(new ByteArrayInputStream(png));
            }
            if (img == null) {
                BufferedImage src = findSource(t);
                if (src != null) {
                    img = scale(src, MAX_PX);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", bos);
                    png = bos.toByteArray();
                    Files.createDirectories(dir);
                    Files.write(out, png);
                }
            }
            if (img != null) art = new Art(IDS.getAndIncrement(), img, out, png);
        } catch (IOException | RuntimeException e) {
            art = null; // unreadable art never breaks playback
        }
        cache.put(k, Optional.ofNullable(art));
        return art;
    }

    private static BufferedImage findSource(Track t) {
        try {
            Tag tag = AudioFileIO.read(t.path().toFile()).getTag();
            Artwork aw = tag == null ? null : tag.getFirstArtwork();
            if (aw != null && aw.getBinaryData() != null) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(aw.getBinaryData()));
                if (img != null) return img;
            }
        } catch (Exception | LinkageError ignored) {
            // fall through to folder images
        }
        Path folder = t.path().getParent();
        if (folder == null) return null;
        Path best = null;
        int bestRank = Integer.MAX_VALUE, images = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = n.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = n.substring(dot + 1), base = n.substring(0, dot);
                if (!ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png")) continue;
                images++;
                int rank = NAMES.indexOf(base);
                if (rank < 0) rank = NAMES.size();
                if (rank < bestRank) {
                    best = p;
                    bestRank = rank;
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (best == null || (bestRank == NAMES.size() && images > 1)) return null; // ambiguous
        try {
            return ImageIO.read(best.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    /** Fits {@code src} into max×max preserving aspect ratio (bicubic). */
    static BufferedImage scale(BufferedImage src, int max) {
        double s = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s)), h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static String key(Track t) {
        Path parent = t.path().getParent();
        return (parent == null ? "" : parent.toString()) + '\u0000' + t.album();
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
