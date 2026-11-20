package bloombox.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BenchHelpersTest {

    @TempDir
    Path dir;

    @Test
    void chiSquareSurvivalMatchesTables() {
        // standard table values: P(χ²₂₃ ≥ 35.172) = 0.05, P(χ²₁ ≥ 3.841) = 0.05, P(χ²₂ ≥ x) = e^(−x/2)
        assertEquals(0.05, ShuffleBench.chiSquareSurvival(35.172, 23), 1e-3);
        assertEquals(0.05, ShuffleBench.chiSquareSurvival(3.841, 1), 1e-3);
        assertEquals(Math.exp(-2.5), ShuffleBench.chiSquareSurvival(5, 2), 1e-9);
        assertEquals(1.0, ShuffleBench.chiSquareSurvival(0, 23));
        assertEquals(Math.log(24), ShuffleBench.logGamma(5), 1e-9);
    }

    @Test
    void permutationIndexIsABijectionOnS4() {
        Set<Integer> seen = new HashSet<>();
        int[] p = {0, 1, 2, 3};
        permute(p, 0, seen);
        assertEquals(24, seen.size());
        for (int i : seen) assertTrue(i >= 0 && i < 24);
    }

    private static void permute(int[] a, int k, Set<Integer> seen) {
        if (k == a.length) {
            seen.add(ShuffleBench.permIndex(a));
            return;
        }
        for (int i = k; i < a.length; i++) {
            int t = a[k]; a[k] = a[i]; a[i] = t;
            permute(a, k + 1, seen);
            t = a[k]; a[k] = a[i]; a[i] = t;
        }
    }

    @Test
    void shufflersReturnPermutations() {
        java.util.Random r = new java.util.Random(1);
        for (ShuffleBench.Shuffler s : List.of(ShuffleBench.FISHER_YATES, ShuffleBench.NAIVE_SWAP, ShuffleBench.SORT_BY_KEY)) {
            int[] a = new int[100];
            for (int i = 0; i < a.length; i++) a[i] = i;
            s.shuffle(a, r);
            assertArrayEquals(java.util.stream.IntStream.range(0, 100).toArray(), java.util.Arrays.stream(a).sorted().toArray());
        }
    }

    @Test
    void numbersAreFormattedForTables() {
        assertEquals("1235", BenchContext.fmt(1234.5));
        assertEquals("0.01004", BenchContext.fmt(0.0100351));
        assertEquals("42", BenchContext.fmt(42.0));
        assertEquals("NaN", BenchContext.fmt(Double.NaN));
        assertEquals("x", BenchContext.fmt("x"));
    }

    @Test
    void timingRejectsZeroOpsAndReportsOrderedStats() {
        BenchContext ctx = new BenchContext(true, 1, dir);
        assertThrows(IllegalArgumentException.class, () -> ctx.time(0, () -> { }));
        BenchContext.Stats s = ctx.time(10, () -> BenchContext.sink += System.nanoTime() % 3);
        assertTrue(s.minNs() <= s.medianNs() && s.medianNs() <= s.maxNs());
        assertEquals(3, s.reps());
    }

    @Test
    void smokeRunWritesCsvAndSummary() throws Exception {
        List<Experiment> fast = BenchMain.ALL.stream()
                .filter(e -> e.id().startsWith("e5") || e.id().startsWith("e6") || e.id().startsWith("e7")).toList();
        BenchMain.run(fast, true, 3, dir);
        assertTrue(Files.size(dir.resolve("e5_kmp.csv")) > 100);
        assertTrue(Files.size(dir.resolve("e6_kmeans.csv")) > 100);
        assertTrue(Files.exists(dir.resolve("e7_shuffle_uniformity.csv")));
        String md = Files.readString(dir.resolve("summary.md"));
        assertTrue(md.contains("E5") && md.contains("E7") && md.contains("seed        3"), md);
        // Fisher–Yates must pass the uniformity test, the naive swap must fail it
        List<String> rows = Files.readAllLines(dir.resolve("e7_shuffle_uniformity.csv"));
        String[] fy = rows.get(1).split(","), naive = rows.get(2).split(",");
        assertEquals("fisher-yates", fy[0]);
        int fyRejected = Integer.parseInt(fy[9]), reps = Integer.parseInt(fy[8]);
        assertTrue(fyRejected <= reps / 2, "uniform shuffle rejected " + fyRejected + "/" + reps);
        assertEquals(reps, Integer.parseInt(naive[9]), "biased shuffle rejected every time");
        assertTrue(Double.parseDouble(naive[4]) < 1e-6);
    }

    @Test
    void chiSquareOfExactlyUniformCountsIsZero() {
        long[] c = new long[24];
        java.util.Arrays.fill(c, 100);
        assertEquals(0, ShuffleBench.chiSquare(c, 2_400), 1e-12);
        c[0] = 124; c[1] = 76;
        assertEquals((24.0 * 24 + 24.0 * 24) / 100, ShuffleBench.chiSquare(c, 2_400), 1e-9);
    }

    @Test
    void cliRejectsBadOptions() {
        assertThrows(IllegalArgumentException.class, () -> BenchMain.main(new String[]{"--bogus"}));
        assertThrows(IllegalArgumentException.class, () -> BenchMain.main(new String[]{"--seed"}));
        assertThrows(IllegalArgumentException.class, () -> BenchMain.main(new String[]{"--only", "e99", "--out", dir.toString()}));
    }
}
