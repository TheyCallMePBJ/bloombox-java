package bloombox.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * User settings persisted at {@code $XDG_CONFIG_HOME/bloombox/config.properties}
 * (default {@code ~/.config/bloombox/config.properties}). Edited from the in-app
 * settings screen; never required to exist.
 */
public final class Settings {

    public static final String MUSIC_DIR = "library.dir";
    public static final String VOLUME = "player.volume";
    public static final String ART_MODE = "ui.art";          // auto | kitty | blocks | off
    public static final String VISUALIZER = "ui.visualizer"; // cava | off
    public static final String SEEKBAR = "ui.seekbar";       // wave | bars | line

    private final Path file;
    private final Properties props = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    public static Settings load() {
        return load(defaultLocation());
    }

    public static Settings load(Path file) {
        Settings s = new Settings(file);
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                s.props.load(r);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("bloombox: ignoring unreadable settings file " + file + ": " + e.getMessage());
            }
        }
        return s;
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(w, "bloombox settings");
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public Optional<Path> musicDir() {
        String v = props.getProperty(MUSIC_DIR);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(Path.of(expandHome(v)));
    }

    public void setMusicDir(Path dir) {
        props.setProperty(MUSIC_DIR, dir.toAbsolutePath().normalize().toString());
    }

    public int volume() {
        return clamp(intProp(VOLUME, 70), 0, 130);
    }

    public void setVolume(int v) {
        props.setProperty(VOLUME, Integer.toString(clamp(v, 0, 130)));
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }

    public static Path configHome() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("bloombox");
    }

    public static Path cacheHome() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("bloombox");
    }

    private static Path defaultLocation() {
        return configHome().resolve("config.properties");
    }

    private int intProp(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, Integer.toString(def)).strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String expandHome(String p) {
        return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
    }
}
