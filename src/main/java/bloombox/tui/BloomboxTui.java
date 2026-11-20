package bloombox.tui;

import bloombox.config.Settings;
import bloombox.core.text.Tokenizer;
import bloombox.index.Field;
import bloombox.index.Query;
import bloombox.index.SearchIndex;
import bloombox.index.SearchReport;
import bloombox.index.SearchResult;
import bloombox.library.Library;
import bloombox.library.LibraryLoader;
import bloombox.library.Lyrics;
import bloombox.library.Track;
import bloombox.mpris.MprisService;
import bloombox.player.MpvPlayer;
import bloombox.player.PlayQueue;
import bloombox.player.PlaybackController;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The full-screen terminal UI. Single UI thread: input, state and rendering all
 * happen in {@link #loop()}; background work (scanning, waveform decoding, art,
 * visualizer, player events) posts results back through {@link #post(Runnable)}
 * or by setting {@link #dirty}.
 */
public final class BloomboxTui implements PlaybackController.Listener {

    private enum Tab { LIBRARY, QUEUE }

    private enum Overlay { NONE, HELP, SETTINGS }

    private static final int MIN_W = 60, MIN_H = 16;
    private static final int SEARCH_LIMIT = 2000;

    // ---- services
    private final Settings settings;
    private Screen screen;
    private MpvPlayer mpv;
    private PlaybackController ctl;
    private MprisService mpris;
    private final ArtService art;
    private final WaveformService waveforms = new WaveformService(this::markDirty);
    private CavaVisualizer cava;
    private KittyImages kitty;

    // ---- data
    private volatile Library library = Library.empty();
    private volatile SearchIndex index;
    private boolean[] albumStripe = new boolean[0];

    // ---- UI state
    private Tab tab = Tab.LIBRARY;
    private Overlay overlay = Overlay.NONE;
    private final ListState libList = new ListState();
    private final ListState queueList = new ListState();
    private final TextInput search = new TextInput();
    private boolean searchFocused;
    private List<SearchResult> results;          // null = no active query
    private SearchReport lastReport;
    private List<String> highlightTerms = List.of();
    private String ghost = "";                   // autocomplete suffix
    private boolean showAlgo;
    private String status = "";
    private TextColor statusColor = Theme.DIM;
    private long statusUntil;
    private volatile String scanProgress;        // non-null while scanning
    private int settingsRow;
    private final TextInput settingsEdit = new TextInput();
    private boolean settingsEditing;
    private volatile boolean dirty = true;
    private boolean running = true;
    private final ConcurrentLinkedQueue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();

    // ---- layout (computed each frame; used by mouse handling)
    private int listTop, listRows, leftW, waveY, tabsLibX0, tabsLibX1, tabsQueueX0, tabsQueueX1;
    private long lastClickAt;
    private int lastClickRow = -1;

    public BloomboxTui(Settings settings) {
        this.settings = settings;
        this.art = new ArtService(Settings.cacheHome().resolve("art"), this::markDirty);
    }

    // =========================================================== lifecycle

    public void run() throws IOException {
        Path log = Settings.cacheHome().resolve("bloombox.log");
        Files.createDirectories(log.getParent());
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(Files.newOutputStream(log), true)); // keep stray output off the screen

        mpv = MpvPlayer.start(settings.volume());
        ctl = new PlaybackController(library, mpv);
        ctl.addListener(this);
        mpris = new MprisService(ctl, art::cachedArtFile, () -> post(() -> running = false));
        try {
            mpris.start();
        } catch (Exception e) {
            System.err.println("MPRIS unavailable: " + e);
            mpris = null;
        }

        Utf8UnixTerminal terminal = new Utf8UnixTerminal(System.in, System.out, UnixLikeTerminal.CtrlCBehaviour.TRAP);
        terminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE);
        screen = new TerminalScreen(terminal);
        Thread restore = new Thread(this::shutdown, "bloombox-shutdown");
        Runtime.getRuntime().addShutdownHook(restore);
        try {
            screen.startScreen();
            screen.setCursorPosition(null);
            kitty = KittyImages.detect(settings.get(Settings.ART_MODE, "auto"));
            startVisualizer();
            if (settings.musicDir().isPresent()) {
                startScan(settings.musicDir().get());
            } else {
                openSettings();
                flash("Welcome! Set your music folder to get started.", Theme.ACCENT2);
            }
            loop();
        } finally {
            shutdown();
            try { Runtime.getRuntime().removeShutdownHook(restore); } catch (IllegalStateException ignored) { }
            System.setErr(originalErr);
        }
    }

    private volatile boolean shutDown;

    private void shutdown() {
        if (shutDown) return;
        shutDown = true;
        try {
            settings.setVolume((int) Math.round(mpv.volume()));
            settings.save();
        } catch (Exception ignored) { }
        if (cava != null) cava.close();
        if (kitty != null) kitty.close();
        try { if (screen != null) screen.stopScreen(); } catch (Exception ignored) { }
        if (mpris != null) mpris.close();
        if (mpv != null) mpv.close();
        waveforms.close();
        art.close();
    }

    private void loop() throws IOException {
        long lastFrame = 0;
        while (running) {
            KeyStroke k;
            while ((k = screen.pollInput()) != null) {
                handleKey(k);
                dirty = true;
            }
            Runnable r;
            while ((r = uiQueue.poll()) != null) {
                r.run();
                dirty = true;
            }
            TerminalSize resized = screen.doResizeIfNecessary();
            boolean full = resized != null;
            long now = System.currentTimeMillis();
            boolean fading = updateTheme(now);
            boolean animate = ctl.status() == PlaybackController.Status.PLAYING || scanProgress != null;
            if (full || dirty || fading || (animate && now - lastFrame >= (cava != null && cava.isRunning() ? 33 : 100))) {
                dirty = false;
                render();
                screen.refresh(full ? Screen.RefreshType.COMPLETE : Screen.RefreshType.DELTA);
                lastFrame = now;
            }
            try {
                Thread.sleep(12);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void markDirty() {
        dirty = true;
    }

    // ---- dynamic theme

    private static final String[] THEME_MODES = {"cover", "static"};
    private int themedArtId = Integer.MIN_VALUE;
    private final java.util.Map<Integer, Theme.Scheme> schemes = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Theme.Scheme> e) {
            return size() > 64;
        }
    };

    /**
     * Points the theme at the playing track's cover (or the default). Waits while the art is
     * still loading so that changing albums fades cover → cover without a flash of the default.
     * Returns true while a cross-fade is running.
     */
    private boolean updateTheme(long now) {
        boolean on = "cover".equals(settings.get(Settings.THEME, "cover"));
        Track t = ctl.current();
        int key;
        if (!on) key = -1;
        else if (t == null) key = themedArtId == Integer.MIN_VALUE ? -1 : themedArtId; // keep last
        else if (!art.known(t)) key = themedArtId == Integer.MIN_VALUE ? -1 : themedArtId; // still loading
        else {
            ArtService.Art a = art.get(t);
            key = a == null ? -1 : a.id();
            if (a != null && !schemes.containsKey(key)) schemes.put(key, Theme.Scheme.fromCover(a.image()));
        }
        if (key != themedArtId) {
            themedArtId = key;
            Theme.transitionTo(key == -1 ? Theme.Scheme.DEFAULT : schemes.get(key), now);
        }
        return Theme.tick(now);
    }

    private void post(Runnable r) {
        uiQueue.add(r);
    }

    private void flash(String msg, TextColor color) {
        status = msg;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + 5000;
    }

    // =========================================================== library loading

    private void startScan(Path dir) {
        if (scanProgress != null) return;
        scanProgress = "scanning…";
        Thread t = new Thread(() -> {
            try {
                Library lib = LibraryLoader.load(dir, (done, total) -> {
                    scanProgress = "scanning " + done + "/" + total;
                    dirty = true;
                });
                scanProgress = "indexing " + lib.size() + " tracks…";
                dirty = true;
                SearchIndex idx = SearchIndex.build(lib);
                post(() -> installLibrary(lib, idx));
            } catch (Exception e) {
                post(() -> {
                    scanProgress = null;
                    flash("Scan failed: " + e.getMessage(), Theme.ERROR);
                });
            }
        }, "bloombox-scan");
        t.setDaemon(true);
        t.start();
    }

    private void installLibrary(Library lib, SearchIndex idx) {
        library = lib;
        index = idx;
        ctl.setLibrary(lib);
        albumStripe = new boolean[lib.size()];
        boolean shade = false;
        for (int i = 1; i < lib.size(); i++) {
            Track a = lib.track(i - 1), b = lib.track(i);
            if (!a.album().equals(b.album()) || !a.artist().equals(b.artist())) shade = !shade;
            albumStripe[i] = shade;
        }
        scanProgress = null;
        libList.selected = 0;
        libList.offset = 0;
        runSearch();
        flash(lib.report().summary(), lib.report().fromFilename() > 0 ? Theme.WARN : Theme.GOOD);
    }

    // =========================================================== search

    private void runSearch() {
        String text = search.text();
        Query q = Query.parse(text);
        ghost = "";
        if (q.isEmpty() || index == null) {
            results = null;
            highlightTerms = List.of();
            if (q.isEmpty()) lastReport = null;
            return;
        }
        SearchIndex.Outcome out = index.search(q, new SearchIndex.Options(SEARCH_LIMIT, true));
        results = out.results();
        lastReport = out.report();
        List<String> terms = new ArrayList<>();
        for (Query.Term t : q.terms()) terms.add(t.text());
        highlightTerms = terms;
        libList.selected = 0;
        libList.offset = 0;
        ghost = completion(text);
    }

    /** Trie-backed autocomplete for the word being typed (shown as faint ghost text). */
    private String completion(String text) {
        if (text.isEmpty() || text.endsWith(" ") || search.cursor() != text.length()) return "";
        if (text.chars().filter(c -> c == '"').count() % 2 == 1) return ""; // inside a phrase
        String last = text.substring(text.lastIndexOf(' ') + 1);
        Field only = null;
        int colon = last.indexOf(':');
        if (colon >= 0) {
            only = Field.byName(last.substring(0, colon));
            last = last.substring(colon + 1);
        }
        String norm = Tokenizer.normalize(last);
        if (norm.isEmpty() || !norm.chars().allMatch(Character::isLetterOrDigit)) return "";
        for (Field f : only != null ? List.of(only) : List.of(Field.TITLE, Field.ARTIST, Field.ALBUM, Field.LYRICS)) {
            for (String s : index.suggest(f, norm, 2)) {
                if (s.length() > norm.length()) return s.substring(norm.length());
            }
        }
        return "";
    }

    // =========================================================== list model

    private int rowCount() {
        if (tab == Tab.QUEUE) return ctl.queueTracks().size();
        return results != null ? results.size() : library.size();
    }

    private Track rowTrack(int i) {
        if (tab == Tab.QUEUE) return library.track(ctl.queueTracks().get(i));
        return results != null ? results.get(i).track() : library.track(i);
    }

    private ListState list() {
        return tab == Tab.QUEUE ? queueList : libList;
    }

    private void playSelected() {
        int n = rowCount();
        if (n == 0) return;
        int sel = list().selected;
        if (tab == Tab.QUEUE) {
            ctl.playQueuePosition(sel);
            return;
        }
        List<Integer> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(rowTrack(i).id());
        double start = 0;
        if (results != null) {
            SearchResult r = results.get(sel);
            boolean lyricOnly = r.lyricTimeMs() >= 0 && !r.matched(Field.TITLE)
                    && !r.matched(Field.ARTIST) && !r.matched(Field.ALBUM);
            if (lyricOnly) {
                start = Math.max(0, r.lyricTimeMs() / 1000.0 - 0.3);
                flash("Jumping to lyric at " + Canvas.time(start), Theme.ACCENT2);
            }
        }
        ctl.playQueue(ids, sel, start);
    }

    private void locatePlaying() {
        Track cur = ctl.current();
        if (cur == null) return;
        if (tab == Tab.QUEUE) {
            int p = ctl.queuePosition();
            if (p >= 0) queueList.center(p, rowCount(), listRows);
            return;
        }
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).track().id() == cur.id()) {
                    libList.center(i, results.size(), listRows);
                    return;
                }
            }
            search.clear();
            runSearch();
        }
        libList.center(cur.id(), library.size(), listRows);
    }

    // =========================================================== input

    private void handleKey(KeyStroke k) {
        if (k instanceof MouseAction m) {
            handleMouse(m);
            return;
        }
        KeyType t = k.getKeyType();
        Character c = t == KeyType.Character ? k.getCharacter() : null;
        if (t == KeyType.EOF) {
            running = false;
            return;
        }
        if (c != null && k.isCtrlDown() && (c == 'c' || c == 'q')) {
            running = false;
            return;
        }
        if (overlay == Overlay.SETTINGS) {
            handleSettingsKey(k);
            return;
        }
        if (overlay == Overlay.HELP) {
            overlay = Overlay.NONE;
            return;
        }
        if (searchFocused && handleSearchKey(k)) return;

        int n = rowCount();
        ListState ls = list();
        switch (t) {
            case ArrowUp -> ls.move(-1, n);
            case ArrowDown -> ls.move(1, n);
            case PageUp -> ls.move(-Math.max(1, listRows - 1), n);
            case PageDown -> ls.move(Math.max(1, listRows - 1), n);
            case Home -> ls.selected = 0;
            case End -> ls.selected = Math.max(0, n - 1);
            case Enter -> playSelected();
            case ArrowLeft -> ctl.seekRelative(k.isShiftDown() ? -30 : -5);
            case ArrowRight -> ctl.seekRelative(k.isShiftDown() ? 30 : 5);
            case Tab, ReverseTab -> tab = tab == Tab.LIBRARY ? Tab.QUEUE : Tab.LIBRARY;
            case Escape -> {
                if (results != null || !search.isEmpty()) {
                    search.clear();
                    runSearch();
                }
            }
            case F1 -> overlay = Overlay.HELP;
            case F2 -> showAlgo = !showAlgo;
            case F10 -> openSettings();
            default -> { }
        }
        if (c == null || k.isCtrlDown()) return;
        switch (c) {
            case '/' -> {
                tab = Tab.LIBRARY;
                searchFocused = true;
            }
            case 'k' -> ls.move(-1, n);
            case 'j' -> ls.move(1, n);
            case 'g' -> ls.selected = 0;
            case 'G' -> ls.selected = Math.max(0, n - 1);
            case ' ' -> ctl.playPause();
            case 'n' -> ctl.next();
            case 'p' -> ctl.previous();
            case 'x' -> ctl.stop();
            case 's' -> ctl.toggleShuffle();
            case 'r' -> ctl.cycleRepeat();
            case '+', '=' -> ctl.changeVolume(5);
            case '-', '_' -> ctl.changeVolume(-5);
            case ',' -> ctl.seekRelative(-5);
            case '.' -> ctl.seekRelative(5);
            case 'a' -> {
                if (n > 0 && tab == Tab.LIBRARY) {
                    Track tr = rowTrack(ls.selected);
                    ctl.append(tr.id());
                    flash("Queued: " + tr.title(), Theme.ACCENT2);
                }
            }
            case 'l' -> locatePlaying();
            case '1' -> tab = Tab.LIBRARY;
            case '2' -> tab = Tab.QUEUE;
            case '?' -> overlay = Overlay.HELP;
            case 'i' -> showAlgo = !showAlgo;
            case 'o' -> openSettings();
            case 'q' -> running = false;
            default -> { }
        }
    }

    /** Returns true if the key was consumed by the search box. */
    private boolean handleSearchKey(KeyStroke k) {
        KeyType t = k.getKeyType();
        switch (t) {
            case Escape -> {
                if (!search.isEmpty()) {
                    search.clear();
                    runSearch();
                } else {
                    searchFocused = false;
                }
                return true;
            }
            case Enter -> {
                searchFocused = false;
                playSelected();
                return true;
            }
            case Tab -> {
                if (!ghost.isEmpty()) {
                    search.set(search.text() + ghost);
                    runSearch();
                } else {
                    searchFocused = false;
                }
                return true;
            }
            case ArrowUp, ArrowDown, PageUp, PageDown, F1, F2, F10 -> {
                return false; // navigate results / toggle panels while typing
            }
            default -> {
                if (search.handle(k)) runSearch();
                else if (t == KeyType.ArrowLeft || t == KeyType.ArrowRight || t == KeyType.Home || t == KeyType.End) {
                    ghost = completion(search.text());
                }
                return true;
            }
        }
    }

    private void handleMouse(MouseAction m) {
        int x = m.getPosition().getColumn(), y = m.getPosition().getRow();
        MouseActionType a = m.getActionType();
        if (overlay != Overlay.NONE) {
            if (a == MouseActionType.CLICK_DOWN && overlay == Overlay.HELP) overlay = Overlay.NONE;
            return;
        }
        boolean inList = x < leftW && y >= listTop && y < listTop + listRows;
        if (a == MouseActionType.SCROLL_UP || a == MouseActionType.SCROLL_DOWN) {
            int d = a == MouseActionType.SCROLL_UP ? -3 : 3;
            if (inList) list().scroll(d, rowCount(), listRows);
            else ctl.changeVolume(d < 0 ? 5 : -5);
            return;
        }
        if (a != MouseActionType.CLICK_DOWN) return;
        if (y == 0) {
            if (x >= tabsLibX0 && x < tabsLibX1) tab = Tab.LIBRARY;
            else if (x >= tabsQueueX0 && x < tabsQueueX1) tab = Tab.QUEUE;
            return;
        }
        if (y == listTop - 2 && x < leftW) {
            searchFocused = true;
            return;
        }
        if (inList) {
            int row = list().offset + (y - listTop);
            if (row < rowCount()) {
                long now = System.currentTimeMillis();
                list().selected = row;
                if (row == lastClickRow && now - lastClickAt < 400) playSelected();
                lastClickRow = row;
                lastClickAt = now;
            }
            return;
        }
        if (y == waveY || y == waveY + 1) {
            double d = ctl.duration();
            int w = screen.getTerminalSize().getColumns() - 2;
            if (d > 0 && w > 0) ctl.seekAbsolute(d * Math.max(0, Math.min(1, (x - 1) / (double) w)));
        }
    }

    // ---- settings overlay

    private static final String[] ART_MODES = {"auto", "kitty", "blocks", "off"};
    private static final String[] VIZ_MODES = {"cava", "off"};
    private static final int SETTINGS_ROWS = 6;

    private void openSettings() {
        overlay = Overlay.SETTINGS;
        settingsRow = 0;
        settingsEditing = false;
    }

    private void handleSettingsKey(KeyStroke k) {
        KeyType t = k.getKeyType();
        if (settingsEditing) {
            if (t == KeyType.Escape) {
                settingsEditing = false;
            } else if (t == KeyType.Enter) {
                String raw = settingsEdit.text().strip();
                Path dir = Path.of(raw.startsWith("~/") ? System.getProperty("user.home") + raw.substring(1) : raw)
                        .toAbsolutePath().normalize();
                if (raw.isEmpty() || !Files.isDirectory(dir)) {
                    flash("Not a directory: " + dir, Theme.ERROR);
                    return;
                }
                settingsEditing = false;
                settings.setMusicDir(dir);
                saveSettings();
                startScan(dir);
            } else {
                settingsEdit.handle(k);
            }
            return;
        }
        switch (t) {
            case Escape -> overlay = Overlay.NONE;
            case ArrowUp -> settingsRow = (settingsRow + SETTINGS_ROWS - 1) % SETTINGS_ROWS;
            case ArrowDown, Tab -> settingsRow = (settingsRow + 1) % SETTINGS_ROWS;
            case Enter, ArrowLeft, ArrowRight -> activateSetting(t == KeyType.ArrowLeft ? -1 : 1);
            case Character -> {
                char c = k.getCharacter();
                if (c == 'q' || c == 'o') overlay = Overlay.NONE;
                else if (c == ' ') activateSetting(1);
                else if (c == 'k') settingsRow = (settingsRow + SETTINGS_ROWS - 1) % SETTINGS_ROWS;
                else if (c == 'j') settingsRow = (settingsRow + 1) % SETTINGS_ROWS;
            }
            default -> { }
        }
    }

    private void activateSetting(int dir) {
        switch (settingsRow) {
            case 0 -> {
                settingsEditing = true;
                settingsEdit.set(settings.musicDir().map(Path::toString).orElse(System.getProperty("user.home") + "/Music"));
            }
            case 1 -> {
                settings.set(Settings.ART_MODE, cycle(ART_MODES, settings.get(Settings.ART_MODE, "auto"), dir));
                saveSettings();
                if (kitty != null) kitty.close();
                kitty = KittyImages.detect(settings.get(Settings.ART_MODE, "auto"));
            }
            case 2 -> {
                settings.set(Settings.VISUALIZER, cycle(VIZ_MODES, settings.get(Settings.VISUALIZER, "cava"), dir));
                saveSettings();
                startVisualizer();
            }
            case 3 -> {
                settings.set(Settings.SEEKBAR, cycle(Waveform.STYLES, settings.get(Settings.SEEKBAR, "wave"), dir));
                saveSettings();
            }
            case 4 -> {
                settings.set(Settings.THEME, cycle(THEME_MODES, settings.get(Settings.THEME, "cover"), dir));
                saveSettings();
            }
            case 5 -> settings.musicDir().ifPresentOrElse(d -> {
                startScan(d);
                overlay = Overlay.NONE;
            }, () -> flash("Set a music folder first", Theme.WARN));
            default -> { }
        }
    }

    private static String cycle(String[] values, String current, int dir) {
        int i = java.util.Arrays.asList(values).indexOf(current);
        return values[Math.floorMod((i < 0 ? 0 : i) + dir, values.length)];
    }

    private void saveSettings() {
        try {
            settings.save();
        } catch (IOException e) {
            flash("Could not save settings: " + e.getMessage(), Theme.ERROR);
        }
    }

    private void startVisualizer() {
        if (cava != null) {
            cava.close();
            cava = null;
        }
        if (!"cava".equals(settings.get(Settings.VISUALIZER, "cava"))) return;
        if (!LibraryLoader.onPath("cava")) return;
        cava = new CavaVisualizer(Settings.cacheHome(), this::markDirty);
        cava.start();
    }

    // =========================================================== player events

    @Override
    public void onTrackChanged(Track track) {
        if (track != null) {
            waveforms.request(track.path());
            art.request(track);
        }
        dirty = true;
    }

    @Override
    public void onStatusChanged(PlaybackController.Status s) {
        dirty = true;
    }

    @Override
    public void onModesChanged(boolean shuffle, PlayQueue.Repeat repeat) {
        dirty = true;
    }

    @Override
    public void onVolumeChanged(double volume) {
        dirty = true;
    }

    @Override
    public void onError(String message) {
        post(() -> flash(message, Theme.ERROR));
    }

    // =========================================================== rendering

    private void render() {
        TerminalSize size = screen.getTerminalSize();
        int w = size.getColumns(), h = size.getRows();
        Canvas cv = new Canvas(screen.newTextGraphics(), w, h);
        cv.fill(0, 0, w, h, Theme.BG);
        if (w < MIN_W || h < MIN_H) {
            cv.text(1, h / 2, "terminal too small (" + w + "×" + h + ", need " + MIN_W + "×" + MIN_H + ")",
                    w - 2, Theme.DIM, Theme.BG);
            return;
        }
        int rightW = w >= 100 ? Math.max(34, Math.min(64, (int) (w * 0.36))) : w >= 80 ? 30 : 0;
        leftW = w - rightW;
        int mainY = 1, bottomH = 3, mainH = h - 1 - bottomH;

        drawTopBar(cv, w);
        drawLeft(cv, 0, mainY, leftW, mainH);
        if (rightW > 0) drawRight(cv, leftW, mainY, rightW, mainH);
        drawBottom(cv, h - bottomH, w);

        if (overlay == Overlay.HELP) drawHelp(cv, w, h);
        if (overlay == Overlay.SETTINGS) drawSettings(cv, w, h);
    }

    private void drawTopBar(Canvas cv, int w) {
        cv.fill(0, 0, w, 1, Theme.BAR);
        int x = cv.text(1, 0, "✿ bloombox", 12, Theme.ACCENT, Theme.BAR, SGR.BOLD) + 4;
        String lib = " Library " + String.format(Locale.ROOT, "%,d", library.size()) + " ";
        String q = " Queue " + ctl.queueTracks().size() + " ";
        tabsLibX0 = x;
        x += cv.text(x, 0, lib, 30, tab == Tab.LIBRARY ? Theme.BG : Theme.DIM,
                tab == Tab.LIBRARY ? Theme.ACCENT : Theme.BAR, SGR.BOLD);
        tabsLibX1 = x;
        x += 1;
        tabsQueueX0 = x;
        x += cv.text(x, 0, q, 20, tab == Tab.QUEUE ? Theme.BG : Theme.DIM,
                tab == Tab.QUEUE ? Theme.ACCENT : Theme.BAR, SGR.BOLD);
        tabsQueueX1 = x;

        String right;
        TextColor rc;
        if (scanProgress != null) {
            right = spinner() + " " + scanProgress;
            rc = Theme.ACCENT2;
        } else if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            right = status;
            rc = statusColor;
        } else {
            right = "F1 help · / search · F2 algorithm";
            rc = Theme.FAINT;
        }
        cv.textRight(w - 1, 0, right, Math.max(0, w - x - 4), rc, Theme.BAR);
    }

    private String spinner() {
        String frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
        int i = (int) (System.currentTimeMillis() / 90 % frames.length());
        return frames.substring(i, i + 1);
    }

    // ---- left: search bar + list (+ algorithm panel)

    private void drawLeft(Canvas cv, int x0, int y0, int w, int h) {
        cv.fill(x0, y0, w, h, Theme.BG);
        drawSearchBar(cv, x0, y0, w);
        int algoH = showAlgo ? Math.min(11, h / 2) : 0;
        int headerY = y0 + 1;
        listTop = headerY + 1;
        listRows = h - 2 - algoH;
        drawList(cv, x0, headerY, w, listRows);
        if (algoH > 0) drawAlgo(cv, x0 + 1, y0 + h - algoH, w - 2, algoH);
    }

    private void drawSearchBar(Canvas cv, int x0, int y, int w) {
        TextColor bg = searchFocused ? Theme.PANEL : Theme.BG;
        cv.fill(x0, y, w, 1, bg);
        cv.text(x0 + 1, y, "⌕", 2, searchFocused ? Theme.ACCENT : Theme.DIM, bg);
        int tx = x0 + 3;
        String info = "";
        if (results != null && lastReport != null) {
            info = String.format("%d result%s · %.2f ms", lastReport.results(), lastReport.results() == 1 ? "" : "s",
                    lastReport.elapsedNanos() / 1e6);
        } else if (!search.isEmpty() && index == null) {
            info = "indexing…";
        }
        int infoW = Canvas.width(info);
        int fieldW = w - (tx - x0) - infoW - 3;
        if (search.isEmpty() && !searchFocused) {
            cv.text(tx, y, "search title, artist, album, lyrics   \"phrase\"  artist:  l:", fieldW, Theme.FAINT, bg);
        } else {
            String text = search.text();
            int used = cv.text(tx, y, text, fieldW, Theme.TEXT, bg);
            if (searchFocused) {
                String beforeCursor = text.substring(0, search.cursor());
                int cx = tx + Math.min(fieldW - 1, Canvas.width(beforeCursor));
                if (!ghost.isEmpty() && search.cursor() == text.length()) {
                    cv.text(tx + used, y, ghost, fieldW - used, Theme.FAINT, bg);
                }
                String under = search.cursor() < text.length()
                        ? text.substring(search.cursor(), text.offsetByCodePoints(search.cursor(), 1))
                        : ghost.isEmpty() ? " " : ghost.substring(0, ghost.offsetByCodePoints(0, 1));
                cv.text(cx, y, under, 2, Theme.BG, Theme.ACCENT);
            }
        }
        if (!info.isEmpty()) cv.textRight(x0 + w - 1, y, info, infoW, Theme.DIM, bg);
    }

    private void drawList(Canvas cv, int x0, int headerY, int w, int rows) {
        boolean searchMode = tab == Tab.LIBRARY && results != null;
        int n = rowCount();
        ListState ls = list();
        ls.clamp(n, rows);

        int timeW = 6, numW = 4, marker = 2;
        int flex = Math.max(10, w - marker - numW - timeW - 3);
        int titleW = flex * 40 / 100, artistW = flex * 27 / 100, thirdW = flex - titleW - artistW;
        int xNum = x0 + marker, xTitle = xNum + numW, xArtist = xTitle + titleW + 1,
                xThird = xArtist + artistW + 1, xTime = x0 + w - timeW - 1;

        cv.fill(x0, headerY, w, 1, Theme.BG);
        TextColor hc = Theme.FAINT;
        cv.text(xNum, headerY, "#", numW, hc, Theme.BG);
        cv.text(xTitle, headerY, "TITLE", titleW, hc, Theme.BG);
        cv.text(xArtist, headerY, "ARTIST", artistW, hc, Theme.BG);
        cv.text(xThird, headerY, searchMode ? "ALBUM / LYRIC MATCH" : "ALBUM", thirdW, hc, Theme.BG);
        cv.textRight(xTime + timeW, headerY, "TIME", timeW, hc, Theme.BG);

        if (n == 0) {
            String msg;
            if (tab == Tab.QUEUE) msg = "Queue is empty — press Enter on a track, or 'a' to add one.";
            else if (scanProgress != null) msg = "Loading library…";
            else if (searchMode) msg = "No matches for “" + search.text().strip() + "”";
            else if (settings.musicDir().isEmpty()) msg = "No music folder set — press o to open settings.";
            else msg = "No audio files found (flac, mp3, ogg, opus).";
            cv.text(x0 + 3, headerY + 2, msg, w - 6, Theme.DIM, Theme.BG);
            return;
        }

        Track playing = ctl.current();
        int queuePos = ctl.queuePosition();
        for (int r = 0; r < rows; r++) {
            int i = ls.offset + r;
            int y = headerY + 1 + r;
            if (i >= n) break;
            Track t = rowTrack(i);
            boolean sel = i == ls.selected;
            boolean isPlaying = playing != null && (tab == Tab.QUEUE ? i == queuePos : t.id() == playing.id());
            TextColor bg = sel ? Theme.SELECT
                    : tab == Tab.LIBRARY && !searchMode && i < albumStripe.length && albumStripe[i] ? Theme.STRIPE
                    : Theme.BG;
            cv.fill(x0, y, w, 1, bg);
            if (sel) cv.ch(x0, y, '▌', Theme.ACCENT, bg);
            TextColor fg = isPlaying ? Theme.ACCENT : Theme.TEXT;
            if (isPlaying) cv.ch(x0 + 1, y, ctl.status() == PlaybackController.Status.PAUSED ? '‖' : '▶', Theme.ACCENT, bg);

            String num = tab == Tab.QUEUE ? String.valueOf(i + 1) : t.trackNo() > 0 ? String.valueOf(t.trackNo()) : "";
            cv.textRight(xNum + numW - 1, y, num, numW - 1, Theme.FAINT, bg);
            drawHighlighted(cv, xTitle, y, t.title(), titleW, fg, bg, searchMode, isPlaying);
            drawHighlighted(cv, xArtist, y, t.artist(), artistW, isPlaying ? Theme.ACCENT : Theme.DIM, bg, searchMode, false);
            SearchResult sr = searchMode ? results.get(i) : null;
            if (sr != null && sr.lyricText() != null && !sr.matched(Field.ALBUM)) {
                String when = sr.lyricTimeMs() >= 0 ? Canvas.time(sr.lyricTimeMs() / 1000.0) + " " : "";
                int used = cv.text(xThird, y, "♪ " + when, thirdW, Theme.ACCENT2, bg);
                drawHighlighted(cv, xThird + used, y, sr.lyricText(), thirdW - used, Theme.ACCENT2, bg, true, false);
            } else {
                drawHighlighted(cv, xThird, y, t.album(), thirdW, Theme.DIM, bg, searchMode, false);
            }
            cv.textRight(xTime + timeW, y, t.durationMs() > 0 ? Canvas.time(t.durationMs() / 1000.0) : "", timeW,
                    Theme.FAINT, bg);
        }
        // scrollbar
        if (n > rows && rows > 2) {
            int barH = Math.max(1, rows * rows / n);
            int barY = (int) ((long) (rows - barH) * ls.offset / Math.max(1, n - rows));
            for (int r = 0; r < rows; r++) {
                boolean on = r >= barY && r < barY + barH;
                cv.ch(x0 + w - 1, headerY + 1 + r, on ? '┃' : '│', on ? Theme.ACCENT_DIM : Theme.PANEL, Theme.BG);
            }
        }
    }

    /** Draws text, colouring the leading part of words that match a query term. */
    private void drawHighlighted(Canvas cv, int x, int y, String text, int w, TextColor fg, TextColor bg,
                                 boolean highlight, boolean bold) {
        if (w <= 0 || text.isEmpty()) return;
        SGR[] base = bold ? new SGR[]{SGR.BOLD} : new SGR[0];
        if (!highlight || highlightTerms.isEmpty()) {
            cv.text(x, y, text, w, fg, bg, base);
            return;
        }
        String fitted = Canvas.fit(text, w);
        int col = x, i = 0;
        while (i < fitted.length()) {
            int j = i;
            if (Character.isLetterOrDigit(fitted.codePointAt(i))) {
                while (j < fitted.length() && (Character.isLetterOrDigit(fitted.codePointAt(j)) || fitted.charAt(j) == '\'')) {
                    j += Character.charCount(fitted.codePointAt(j));
                }
                String word = fitted.substring(i, j);
                String norm = Tokenizer.normalizeForMatch(word).replace(" ", "");
                int hl = 0;
                for (String term : highlightTerms) {
                    String tt = term.replace(" ", "");
                    if (!tt.isEmpty() && norm.startsWith(tt)) hl = Math.max(hl, Math.min(word.length(), tt.length()));
                }
                if (hl > 0) {
                    col += cv.text(col, y, word.substring(0, hl), w, Theme.ACCENT, bg, SGR.BOLD);
                    col += cv.text(col, y, word.substring(hl), w, fg, bg, base);
                } else {
                    col += cv.text(col, y, word, w, fg, bg, base);
                }
            } else {
                j = i + Character.charCount(fitted.codePointAt(i));
                col += cv.text(col, y, fitted.substring(i, j), w, fg, bg, base);
            }
            i = j;
        }
    }

    private void drawAlgo(Canvas cv, int x, int y, int w, int h) {
        cv.box(x, y, w, h, "Algorithm", Theme.BORDER, Theme.PANEL);
        List<String> lines = new ArrayList<>();
        SearchIndex idx = index;
        if (idx != null) {
            SearchIndex.BuildStats b = idx.buildStats();
            lines.add(String.format("index: %,d tracks (%,d with lyrics) · %,d postings · built in %d ms",
                    b.tracks(), b.tracksWithLyrics(), b.postings(), b.buildMillis()));
            lines.add(String.format("radix-trie nodes: title %,d · artist %,d · album %,d · lyrics %,d",
                    b.trieNodes().get(Field.TITLE), b.trieNodes().get(Field.ARTIST),
                    b.trieNodes().get(Field.ALBUM), b.trieNodes().get(Field.LYRICS)));
            lines.add(String.format("trigram bloom filters: %.1f KiB total, target fpp 1%%", b.bloomBits() / 8192.0));
        } else {
            lines.add("index not built yet");
        }
        if (lastReport != null) {
            lines.add("");
            lines.add("last query: " + search.text().strip());
            lines.addAll(lastReport.describe());
        } else {
            lines.add("");
            lines.add("type a query to see trie / Bloom / KMP work here");
        }
        for (int i = 0; i < lines.size() && i < h - 2; i++) {
            String l = lines.get(i);
            TextColor c = l.startsWith("bloom") || l.startsWith("kmp") ? Theme.ACCENT2
                    : l.startsWith("last query") ? Theme.ACCENT : Theme.TEXT;
            cv.text(x + 2, y + 1 + i, l, w - 4, c, Theme.PANEL);
        }
    }

    // ---- right: art, track info, visualizer, lyrics

    private void drawRight(Canvas cv, int x0, int y0, int w, int h) {
        cv.fill(x0, y0, w, h, Theme.PANEL);
        Track t = ctl.current();
        int inner = w - 4;
        int artW = inner, artH = (artW + 1) / 2;
        int maxArtH = Math.max(4, h * 45 / 100);
        if (artH > maxArtH) {
            artH = maxArtH;
            artW = artH * 2;
        }
        int artX = x0 + 2 + (inner - artW) / 2, artY = y0 + 1;
        drawArt(cv, t, artX, artY, artW, artH);

        int y = artY + artH + 1;
        if (t != null) {
            cv.text(x0 + 2, y, t.title(), inner, Theme.TEXT, Theme.PANEL, SGR.BOLD);
            cv.text(x0 + 2, y + 1, t.artist().isEmpty() ? "Unknown artist" : t.artist(), inner, Theme.ACCENT, Theme.PANEL);
            cv.text(x0 + 2, y + 2, t.album(), inner, Theme.DIM, Theme.PANEL);
        } else {
            cv.text(x0 + 2, y, "Nothing playing", inner, Theme.DIM, Theme.PANEL);
            cv.text(x0 + 2, y + 1, "Enter plays · / searches · F1 help", inner, Theme.FAINT, Theme.PANEL);
        }
        y += 4;

        int vizH = cava != null && h - (y - y0) >= 10 ? 4 : 0;
        if (vizH > 0) {
            drawVisualizer(cv, x0 + 2, y, inner, vizH);
            y += vizH + 1;
        }
        int lyrH = y0 + h - y - 1;
        if (lyrH >= 3) drawLyrics(cv, t, x0 + 2, y, inner, lyrH);
    }

    private void drawArt(Canvas cv, Track t, int x, int y, int w, int h) {
        ArtService.Art a = t == null ? null : art.get(t);
        String mode = settings.get(Settings.ART_MODE, "auto");
        if (a == null || "off".equals(mode)) {
            // placeholder: soft gradient square with a flower
            for (int j = 0; j < h; j++) {
                TextColor row = Theme.mix(Theme.STRIPE, Theme.SELECT, j / (double) Math.max(1, h - 1));
                cv.fill(x, y + j, w, 1, row);
            }
            String label = t == null ? "✿" : art.isLoading(t) ? "…" : "✿";
            cv.text(x + w / 2, y + h / 2, label, 1, Theme.ACCENT_DIM, Theme.mix(Theme.STRIPE, Theme.SELECT, 0.5));
            return;
        }
        if (kitty != null && kitty.enabled()) {
            kitty.draw(cv, a, x, y, w, h);
        } else {
            HalfBlock.draw(cv, a.image(), x, y, w, h);
        }
    }

    private void drawVisualizer(Canvas cv, int x, int y, int w, int h) {
        int[] bars = cava.bars();
        Visualizer.draw(cv, bars, x, y, w, h, ctl.status() == PlaybackController.Status.PLAYING);
    }

    /** Lyric lines word-wrapped to the pane width; cached per (library, track, width). */
    private record WrappedLyrics(Library lib, int trackId, int width, List<String> rows, int[] lineOfRow, int[] firstRow) {}

    private WrappedLyrics wrapped;

    private WrappedLyrics wrapLyrics(Track t, Lyrics ly, int w) {
        WrappedLyrics c = wrapped;
        if (c != null && c.lib() == library && c.trackId() == t.id() && c.width() == w) return c;
        List<String> rows = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        int[] firstRow = new int[ly.lines().size()];
        for (int i = 0; i < ly.lines().size(); i++) {
            firstRow[i] = rows.size();
            String text = ly.lines().get(i).text();
            for (String part : Canvas.wrap(text.isEmpty() ? "♪" : text, w)) {
                rows.add(part);
                owner.add(i);
            }
        }
        wrapped = new WrappedLyrics(library, t.id(), w, rows, owner.stream().mapToInt(Integer::intValue).toArray(), firstRow);
        return wrapped;
    }

    private void drawLyrics(Canvas cv, Track t, int x, int y, int w, int h) {
        Lyrics ly = t == null ? Lyrics.EMPTY : library.lyrics(t.id());
        if (ly.isEmpty()) {
            if (t != null) cv.text(x + Math.max(0, (w - 9) / 2), y + h / 2, "no lyrics", w, Theme.FAINT, Theme.PANEL);
            return;
        }
        WrappedLyrics wl = wrapLyrics(t, ly, w);
        int total = wl.rows().size();
        int cur = -1, top;
        if (ly.synced()) {
            // keep the active line's block vertically centred
            cur = ly.indexAt((long) (ctl.position() * 1000));
            int line = Math.max(0, cur);
            int start = wl.firstRow()[line];
            int end = line + 1 < wl.firstRow().length ? wl.firstRow()[line + 1] : total;
            top = start + (end - start) / 2 - h / 2;
        } else if (total <= h) {
            top = -(h - total) / 2; // short text: centre it
        } else {
            // no timestamps: scroll through the text in step with playback progress
            double d = ctl.duration();
            double frac = d > 0 ? Math.max(0, Math.min(1, ctl.position() / d)) : 0;
            top = (int) Math.round(frac * (total - h));
        }
        for (int r = 0; r < h; r++) {
            int row = top + r;
            if (row < 0 || row >= total) continue;
            String text = wl.rows().get(row);
            int line = wl.lineOfRow()[row];
            boolean active = ly.synced() && line == cur;
            TextColor fg;
            if (!ly.synced()) fg = Theme.mix(Theme.DIM, Theme.TEXT, 0.45);
            else if (active) fg = Theme.ACCENT;
            else fg = Theme.mix(Theme.DIM, Theme.FAINT, Math.min(1, Math.abs(line - Math.max(0, cur)) / 6.0));
            int tx = x + Math.max(0, (w - Canvas.width(text)) / 2);
            cv.text(tx, y + r, text, w, fg, Theme.PANEL, active ? new SGR[]{SGR.BOLD} : new SGR[0]);
        }
    }

    // ---- bottom: waveform seek bar + transport line

    private void drawBottom(Canvas cv, int y, int w) {
        cv.fill(0, y, w, 3, Theme.BAR);
        waveY = y;
        Track t = ctl.current();
        double pos = ctl.position(), dur = ctl.duration();
        double frac = dur > 0 ? Math.max(0, Math.min(1, pos / dur)) : 0;
        float[] peaks = t == null ? null : waveforms.peaks(t.path());
        Waveform.draw(cv, peaks, 1, y, w - 2, 2, frac, t != null, settings.get(Settings.SEEKBAR, "wave"));

        int sy = y + 2;
        PlaybackController.Status st = ctl.status();
        String icon = st == PlaybackController.Status.PLAYING ? "▶" : st == PlaybackController.Status.PAUSED ? "‖" : "■";
        cv.text(1, sy, icon, 2, st == PlaybackController.Status.STOPPED ? Theme.FAINT : Theme.ACCENT, Theme.BAR, SGR.BOLD);

        String right = String.format("%s / %s   vol %d%%   %s %s",
                Canvas.time(pos), Canvas.time(dur), Math.round(ctl.volume()),
                ctl.shuffle() ? "⇄ shuffle" : "⇄ ·", repeatLabel(ctl.repeat()));
        int rw = Canvas.width(right);
        cv.textRight(w - 1, sy, right, rw, Theme.DIM, Theme.BAR);
        if (t != null) {
            int x = 3 + cv.text(3, sy, t.title(), (w - rw - 8) * 6 / 10, Theme.TEXT, Theme.BAR, SGR.BOLD);
            cv.text(x, sy, "  " + (t.artist().isEmpty() ? "" : t.artist()), w - rw - 6 - x, Theme.ACCENT, Theme.BAR);
        } else {
            cv.text(3, sy, "stopped", 20, Theme.FAINT, Theme.BAR);
        }
    }

    private static String repeatLabel(PlayQueue.Repeat r) {
        return switch (r) {
            case OFF -> "↻ ·";
            case ALL -> "↻ all";
            case ONE -> "↻ one";
        };
    }

    // ---- overlays

    private void drawHelp(Canvas cv, int w, int h) {
        String[][] keys = {
                {"Enter / double-click", "play selection (lyric hits start at the line)"},
                {"Space", "play / pause"},
                {"n  p", "next / previous (p restarts if > 3 s in)"},
                {"← →  , .", "seek 5 s  (Shift+← → 30 s, click the waveform)"},
                {"+ -  wheel", "volume"},
                {"s  r", "shuffle · repeat (off → all → one)"},
                {"/", "search; Tab accepts completion; Esc clears"},
                {"↑ ↓ j k PgUp PgDn g G", "move"},
                {"a", "add selection to queue"},
                {"Tab  1  2", "switch Library / Queue"},
                {"l", "locate the playing track"},
                {"F2  i", "algorithm panel (trie / Bloom / KMP stats)"},
                {"o  F10", "settings (music folder, art, visualizer)"},
                {"q  Ctrl+C", "quit"},
                {"", ""},
                {"search syntax", "blue mon · artist:daft · al:disc · l:love · \"exact phrase\""},
        };
        int bw = Math.min(w - 4, 76), bh = Math.min(h - 2, keys.length + 4);
        int bx = (w - bw) / 2, by = (h - bh) / 2;
        cv.box(bx, by, bw, bh, "Keys", Theme.ACCENT_DIM, Theme.PANEL);
        for (int i = 0; i < keys.length && i < bh - 4; i++) {
            cv.text(bx + 2, by + 2 + i, keys[i][0], 22, Theme.ACCENT, Theme.PANEL);
            cv.text(bx + 25, by + 2 + i, keys[i][1], bw - 27, Theme.TEXT, Theme.PANEL);
        }
    }

    private void drawSettings(Canvas cv, int w, int h) {
        int bw = Math.min(w - 4, 80), bh = 17;
        int bx = (w - bw) / 2, by = Math.max(1, (h - bh) / 2);
        cv.box(bx, by, bw, bh, "Settings", Theme.ACCENT_DIM, Theme.PANEL);
        String dir = settings.musicDir().map(Path::toString).orElse("(not set)");
        String[][] rows = {
                {"Music folder", settingsEditing ? null : dir},
                {"Album art", settings.get(Settings.ART_MODE, "auto") + artDetail()},
                {"Visualizer", settings.get(Settings.VISUALIZER, "cava")
                        + (LibraryLoader.onPath("cava") ? "" : "  (cava not installed)")},
                {"Seek bar", settings.get(Settings.SEEKBAR, "wave")},
                {"Theme", "cover".equals(settings.get(Settings.THEME, "cover"))
                        ? "cover  (" + Theme.current().description() + ")" : "static"},
                {"Rescan library", scanProgress != null ? scanProgress : "press Enter"},
        };
        for (int i = 0; i < rows.length; i++) {
            int y = by + 2 + i * 2;
            boolean sel = i == settingsRow;
            TextColor bg = sel ? Theme.SELECT : Theme.PANEL;
            cv.fill(bx + 1, y, bw - 2, 1, bg);
            if (sel) cv.ch(bx + 1, y, '▌', Theme.ACCENT, bg);
            cv.text(bx + 3, y, rows[i][0], 16, sel ? Theme.ACCENT : Theme.TEXT, bg, SGR.BOLD);
            int vx = bx + 21, vw = bw - 23;
            if (i == 0 && settingsEditing) {
                String txt = settingsEdit.text();
                int shown = Math.min(vw - 1, Canvas.width(txt));
                String visible = Canvas.width(txt) > vw - 1 ? "…" + txt.substring(Math.max(0, txt.length() - (vw - 2))) : txt;
                cv.text(vx, y, visible, vw, Theme.TEXT, Theme.BG);
                cv.ch(vx + Math.min(vw - 1, Canvas.width(visible)), y, ' ', Theme.BG, Theme.ACCENT);
                if (shown < 0) return;
            } else {
                cv.text(vx, y, rows[i][1], vw, i >= 1 && i <= 4 ? Theme.ACCENT2 : Theme.DIM, bg);
            }
        }
        String hint = settingsEditing ? "Enter save & scan · Esc cancel · Ctrl+U clear"
                : "↑↓ select · Enter/←→ change · Esc close";
        cv.text(bx + 3, by + bh - 2, hint, bw - 6, Theme.FAINT, Theme.PANEL);
    }

    private String artDetail() {
        return switch (settings.get(Settings.ART_MODE, "auto")) {
            case "auto", "kitty" -> kitty == null ? "" : kitty.enabled() ? "  (" + kitty.reason() + ")"
                    : "  (half-blocks: " + kitty.reason() + ")";
            default -> "";
        };
    }
}
