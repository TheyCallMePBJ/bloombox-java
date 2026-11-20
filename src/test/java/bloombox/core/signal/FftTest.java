package bloombox.core.signal;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FftTest {

    @Test
    void matchesDirectDftOnRandomInput() {
        Random r = new Random(1);
        for (int n : new int[]{1, 2, 4, 8, 64, 512}) {
            double[] re = new double[n], im = new double[n];
            for (int i = 0; i < n; i++) {
                re[i] = r.nextGaussian();
                im[i] = r.nextGaussian();
            }
            double[] dr = new double[n], di = new double[n];
            Fft.dft(re, im, dr, di);
            new Fft(n).transform(re, im);
            for (int k = 0; k < n; k++) {
                assertEquals(dr[k], re[k], 1e-9 * n, "re n=" + n + " k=" + k);
                assertEquals(di[k], im[k], 1e-9 * n, "im n=" + n + " k=" + k);
            }
        }
    }

    @Test
    void pureToneLandsInItsBin() {
        int n = 256, bin = 19;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.cos(2 * Math.PI * bin * i / n);
        new Fft(n).transform(re, im);
        for (int k = 0; k < n; k++) {
            double mag = Math.hypot(re[k], im[k]);
            if (k == bin || k == n - bin) assertEquals(n / 2.0, mag, 1e-9);
            else assertEquals(0, mag, 1e-9);
        }
    }

    @Test
    void impulseHasFlatSpectrumAndParsevalHolds() {
        int n = 128;
        double[] re = new double[n], im = new double[n];
        re[0] = 1;
        new Fft(n).transform(re, im);
        for (int k = 0; k < n; k++) assertEquals(1, Math.hypot(re[k], im[k]), 1e-12);

        Random r = new Random(4);
        double[] x = new double[n], y = new double[n];
        double timeEnergy = 0;
        for (int i = 0; i < n; i++) {
            x[i] = r.nextDouble() - 0.5;
            timeEnergy += x[i] * x[i];
        }
        new Fft(n).transform(x, y);
        double freqEnergy = 0;
        for (int k = 0; k < n; k++) freqEnergy += x[k] * x[k] + y[k] * y[k];
        assertEquals(timeEnergy, freqEnergy / n, 1e-9, "Parseval: Σ|x|² = Σ|X|²/n");
    }

    @Test
    void edgeCasesAndValidation() {
        double[] re = {3.5}, im = {-1};
        new Fft(1).transform(re, im);
        assertArrayEquals(new double[]{3.5}, re, "size-1 transform is the identity");
        assertThrows(IllegalArgumentException.class, () -> new Fft(0));
        assertThrows(IllegalArgumentException.class, () -> new Fft(12));
        assertThrows(IllegalArgumentException.class, () -> new Fft(8).transform(new double[4], new double[4]));
    }

    @Test
    void reusableAcrossCalls() {
        Fft f = new Fft(16);
        for (int t = 0; t < 3; t++) {
            double[] re = new double[16], im = new double[16];
            re[1] = 1;
            f.transform(re, im);
            assertEquals(1, Math.hypot(re[5], im[5]), 1e-12);
        }
    }
}
