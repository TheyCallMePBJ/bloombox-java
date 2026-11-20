package bloombox.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code bloombox bench [--quick] [--seed N] [--out DIR] [--only e1,e4,...]}
 *
 * <p>Runs the experiments and writes, into a fresh timestamped directory (and a
 * {@code latest} link next to it): one CSV per experiment, {@code summary.md} with tables
 * and the environment (JVM, CPU, heap, seed) for reproducibility. Charts:
 * {@code python3 bench/plot.py DIR}.
 */
public final class BenchMain {
    private BenchMain() {}

    static final List<Experiment> ALL = List.of(new PrefixBench(), new PhraseBench(), new SearchBench(),
            new BloomBench(), new KmpBench(), new KMeansBench(), new ShuffleBench());

    public static final String USAGE = """
            usage: bloombox bench [--quick] [--seed N] [--out DIR] [--only e1,e2,...]
              --quick      smaller sizes, fewer repetitions (~1 min instead of several)
              --seed N     random seed for data and queries (default 42); same seed = same inputs
              --out DIR    output directory (default bench-results/<timestamp>)
              --only LIST  run a subset, e.g. --only e1,e4
            experiments: """ + String.join(", ", ALL.stream().map(Experiment::id).toList()) + "\n";

    public static void main(String[] args) throws IOException {
        boolean quick = false;
        long seed = 42;
        Path out = null;
        Set<String> only = new LinkedHashSet<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--seed" -> seed = Long.parseLong(value(args, ++i, "--seed"));
                case "--out" -> out = Path.of(value(args, ++i, "--out"));
                case "--only" -> {
                    for (String s : value(args, ++i, "--only").split(",")) only.add(s.strip().toLowerCase(Locale.ROOT));
                }
                case "-h", "--help" -> {
                    System.out.print(USAGE);
                    return;
                }
                default -> throw new IllegalArgumentException("unknown bench option '" + args[i] + "'\n" + USAGE);
            }
        }
        List<Experiment> chosen = new ArrayList<>();
        for (Experiment e : ALL) {
            if (only.isEmpty() || only.stream().anyMatch(o -> e.id().equals(o) || e.id().startsWith(o + "_"))) chosen.add(e);
        }
        if (chosen.isEmpty()) throw new IllegalArgumentException("no experiment matches --only " + only + "\n" + USAGE);
        Path base = Path.of("bench-results");
        if (out == null) out = base.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")));
        Files.createDirectories(out);
        run(chosen, quick, seed, out);
        if (out.getParent() != null && out.getParent().equals(base)) {
            Path latest = base.resolve("latest");
            try {
                Files.deleteIfExists(latest);
                Files.createSymbolicLink(latest, out.getFileName());
            } catch (IOException | UnsupportedOperationException ignored) {
                // links are a convenience only
            }
        }
    }

    static void run(List<Experiment> experiments, boolean quick, long seed, Path out) throws IOException {
        BenchContext ctx = new BenchContext(quick, seed, out);
        String env = environment(quick, seed);
        ctx.log("bloombox benchmarks → %s%n%s", out.toAbsolutePath(), env);
        ctx.markdown("# bloombox benchmark results\n");
        ctx.markdown("```\n" + env + "```\n");
        ctx.markdown("Method: each workload is warmed up (" + (ctx.warmupNanos / 1_000_000) + " ms) so the JIT compiles it, "
                + "then timed " + ctx.reps + " times; tables show the **median** time per operation (CSV also has min/max). "
                + "Operation counts (nodes visited, character comparisons, Bloom probes) are exact and machine-independent. "
                + "Inputs are synthetic and fully determined by the seed.\n");
        long t0 = System.nanoTime();
        for (Experiment e : experiments) {
            long s = System.nanoTime();
            ctx.log("%n== %s", e.title());
            e.run(ctx);
            ctx.dropLibraries();
            ctx.log("   (%.1f s)", (System.nanoTime() - s) / 1e9);
        }
        Files.writeString(out.resolve("summary.md"), ctx.summary(), StandardCharsets.UTF_8);
        ctx.log("%ndone in %.1f s → %s", (System.nanoTime() - t0) / 1e9, out.resolve("summary.md").toAbsolutePath());
    }

    static String environment(boolean quick, long seed) {
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, """
                date        %s
                java        %s (%s)
                vm args     %s
                os          %s %s (%s)
                cpu         %s, %d logical cores
                max heap    %d MiB
                seed        %d
                mode        %s
                """, LocalDateTime.now().withNano(0), System.getProperty("java.version"), System.getProperty("java.vm.name"),
                String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()),
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                cpuModel(), rt.availableProcessors(), rt.maxMemory() / (1024 * 1024), seed, quick ? "quick" : "full");
    }

    private static String cpuModel() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (l.startsWith("model name")) return l.substring(l.indexOf(':') + 1).strip();
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux
        }
        return "unknown";
    }

    private static String value(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value\n" + USAGE);
        return args[i];
    }
}
