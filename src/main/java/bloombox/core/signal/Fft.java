package bloombox.core.signal;

/**
 * Iterative radix-2 Cooley–Tukey fast Fourier transform, in place.
 *
 * <p>Divide and conquer: a DFT of size n is two DFTs of size n/2 (even- and odd-indexed
 * samples) combined with n/2 "butterflies", so T(n) = 2T(n/2) + O(n) = O(n log n), against
 * O(n²) for the direct sum. The recursion is unrolled bottom-up: the input is permuted into
 * bit-reversed order, then log₂ n passes merge blocks of size 2, 4, …, n. Twiddle factors
 * e^(−2πik/n) are precomputed once per size, so a transform does no trigonometry.
 *
 * <p>Space: O(n) for the tables; the transform itself allocates nothing.
 */
public final class Fft {
    private final int n;
    private final double[] cos, sin;
    private final int[] rev;

    /** @param n transform size, a power of two ≥ 1 */
    public Fft(int n) {
        if (n < 1 || (n & (n - 1)) != 0) throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        this.n = n;
        cos = new double[Math.max(1, n / 2)];
        sin = new double[Math.max(1, n / 2)];
        for (int k = 0; k < n / 2; k++) {
            double a = 2 * Math.PI * k / n;
            cos[k] = Math.cos(a);
            sin[k] = Math.sin(a);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = bits == 0 ? 0 : Integer.reverse(i) >>> (32 - bits);
    }

    public int size() {
        return n;
    }

    /** Forward transform X[k] = Σ x[j]·e^(−2πijk/n), overwriting {@code re}/{@code im}. */
    public void transform(double[] re, double[] im) {
        if (re.length != n || im.length != n) throw new IllegalArgumentException("arrays must have length " + n);
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >>> 1, step = n / size;
            for (int start = 0; start < n; start += size) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    int a = start + j, b = a + half;
                    // (re[b] + i·im[b]) · (cos − i·sin)
                    double tr = re[b] * cos[k] + im[b] * sin[k];
                    double ti = im[b] * cos[k] - re[b] * sin[k];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }

    /** Direct O(n²) DFT, the baseline (any n ≥ 1). Writes into {@code outRe}/{@code outIm}. */
    public static void dft(double[] re, double[] im, double[] outRe, double[] outIm) {
        int n = re.length;
        for (int k = 0; k < n; k++) {
            double sr = 0, si = 0;
            for (int j = 0; j < n; j++) {
                double a = 2 * Math.PI * ((long) j * k % n) / n;
                double c = Math.cos(a), s = Math.sin(a);
                sr += re[j] * c + im[j] * s;
                si += im[j] * c - re[j] * s;
            }
            outRe[k] = sr;
            outIm[k] = si;
        }
    }
}
