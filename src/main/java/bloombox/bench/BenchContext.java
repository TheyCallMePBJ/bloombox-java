package bloombox.bench;

import bloombox.library.Library;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared benchmark machinery: timing with warm-up, CSV output, Markdown summary, cached
 * synthetic libraries, heap measurement.
 *
 * <p>Timing method: a workload ("batch" of {@code ops} operations) is first run repeatedly
 * for the warm-up period so the JIT compiles the hot code; then it is run {@code reps}
 * times and each run's wall time / ops is recorded. We report the median (robust to GC
 * pauses and scheduler noise) plus min and max. Results feed a volatile sink so the JIT
 * cannot eliminate the measured work as dead code.
 */
public final class BenchContext {

    /** Median / min / max time per operation over the measured repetitions, in nanoseconds. */
    public record Stats(double medianNs, double minNs, double maxNs, int reps) {}

    /** Prevents dead-code elimination of benchmark results. */
    public static volatile long sink;

    final boolean quick;
    final long seed;
    final Path out;
    final int reps;
    final long warmupNanos;
    private final StringBuilder md = new StringBuilder();
    private final Map<Integer, Library> libraries = new HashMap<>();

    public BenchContext(boolean quick, long seed, Path out) {
        this.quick = quick;
        this.seed = seed;
        this.out = out;
        this.reps = quick ? 3 : 7;
        this.warmupNanos = (quick ? 50 : 500) * 1_000_000L;
    }

    /** Synthetic library with {@code tracks} tracks (generated once per size, then cached). */
    public Library library(int tracks) {
        return libraries.computeIfAbsent(tracks, n -> SyntheticLibrary.generate(SyntheticLibrary.Config.defaults(n, seed)));
    }

    public void dropLibraries() {
        libraries.clear();
    }

    /** Times {@code batch}, which performs {@code ops} operations per call. */
    public Stats time(int ops, Runnable batch) {
        if (ops < 1) throw new IllegalArgumentException("ops must be >= 1");
        long end = System.nanoTime() + warmupNanos;
        int warm = 0;
        do {
            batch.run();
            warm++;
        } while (System.nanoTime() < end || warm < 2);
        double[] r = new double[reps];
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            batch.run();
            r[i] = (System.nanoTime() - t0) / (double) ops;
        }
        Arrays.sort(r);
        return new Stats(r[reps / 2], r[0], r[reps - 1], reps);
    }

    /** Heap in use after asking for full GCs; differences of this give approximate structure sizes. */
    public static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            best = Math.min(best, rt.totalMemory() - rt.freeMemory());
        }
        return best;
    }

    public void log(String fmt, Object... args) {
        System.out.printf(Locale.ROOT, fmt + "%n", args);
        System.out.flush();
    }

    // ---- output

    public Csv csv(String name, String... header) throws IOException {
        return new Csv(out.resolve(name + ".csv"), header);
    }

    public void markdown(String text) {
        md.append(text).append('\n');
    }

    /** Appends a GitHub-flavoured Markdown table. */
    public void table(String[] header, List<Object[]> rows) {
        md.append("| ").append(String.join(" | ", header)).append(" |\n|");
        for (String ignored : header) md.append(" --- |");
        md.append('\n');
        for (Object[] row : rows) {
            md.append("| ");
            for (int i = 0; i < row.length; i++) md.append(i > 0 ? " | " : "").append(fmt(row[i]));
            md.append(" |\n");
        }
        md.append('\n');
    }

    String summary() {
        return md.toString();
    }

    /** Numbers to 4 significant digits without exponent; integers as integers. */
    public static String fmt(Object o) {
        if (o instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) return d.toString();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString(d.longValue());
            return new BigDecimal(d).round(new MathContext(4)).stripTrailingZeros().toPlainString();
        }
        if (o instanceof Float f) return fmt(f.doubleValue());
        return String.valueOf(o);
    }

    /** CSV writer; values formatted with {@link #fmt}, strings quoted when needed. */
    public static final class Csv implements Closeable {
        private final BufferedWriter w;
        private final int columns;

        Csv(Path file, String... header) throws IOException {
            Files.createDirectories(file.getParent());
            w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            columns = header.length;
            w.write(String.join(",", header));
            w.newLine();
        }

        public void row(Object... values) throws IOException {
            if (values.length != columns) throw new IllegalArgumentException("expected " + columns + " values");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) sb.append(',');
                String v = fmt(values[i]);
                sb.append(v.contains(",") || v.contains("\"") ? '"' + v.replace("\"", "\"\"") + '"' : v);
            }
            w.write(sb.toString());
            w.newLine();
        }

        @Override
        public void close() throws IOException {
            w.close();
        }
    }
}
