# bloombox benchmark results

```
date        2026-11-20T15:23:40
java        17.0.21.0.2 (OpenJDK 64-Bit Server VM)
vm args     -Xmx8g
os          Linux 7.3.4-1-cachyos (amd64)
cpu         Intel(R) Core(TM) Ultra 7 270K Plus, 24 logical cores
max heap    8192 MiB
seed        42
mode        full
```

Method: each workload is warmed up (500 ms) so the JIT compiles it, then timed 7 times; tables show the **median** time per operation (CSV also has min/max). Operation counts (nodes visited, character comparisons, Bloom probes) are exact and machine-independent. Inputs are synthetic and fully determined by the seed.

## E1 · Prefix index: Trie vs Radix trie vs linear scan

Prefix queries return *all* matches (as the search index uses them). Keys: pseudo-words. Expected: trie/radix cost depends on prefix length and output size, not on K; linear scan grows with K. Counters: the trie counts nodes (child lookup is a binary search over a sorted char array); the radix trie counts nodes and edge-label characters compared; the linear scan counts characters compared.

| keys K | structure | query | µs/query (median) | nodes visited/query | char cmps/query | nodes | heap bytes/key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1000 | trie | prefix-4 | 0.04288 | 10.65 | 0 | 3014 | 268.1 |
| 1000 | trie | miss | 0.004627 | 1 | 0 | 3014 | 268.1 |
| 1000 | radix | prefix-4 | 0.05482 | 6.015 | 3.7 | 1156 | 174.8 |
| 1000 | radix | miss | 0.006565 | 1 | 0 | 1156 | 174.8 |
| 1000 | linear | prefix-4 | 2.659 | 0 | 897.1 | 1000 | 83.21 |
| 1000 | linear | miss | 2.241 | 0 | 775 | 1000 | 83.21 |
| 10000 | trie | prefix-4 | 0.2447 | 37.4 | 0 | 33200 | 293.8 |
| 10000 | trie | miss | 0.004405 | 1 | 0 | 33200 | 293.8 |
| 10000 | radix | prefix-4 | 0.1379 | 16.06 | 3.918 | 12174 | 184.2 |
| 10000 | radix | miss | 0.006293 | 1 | 0 | 12174 | 184.2 |
| 10000 | linear | prefix-4 | 35.2 | 0 | 10220 | 10000 | 83.98 |
| 10000 | linear | miss | 27.8 | 0 | 9316 | 10000 | 83.98 |
| 100000 | trie | prefix-4 | 2.588 | 232.6 | 0 | 278212 | 253.1 |
| 100000 | trie | miss | 0.005071 | 1 | 0 | 278212 | 253.1 |
| 100000 | radix | prefix-4 | 1.35 | 107.8 | 3.984 | 126445 | 191.1 |
| 100000 | radix | miss | 0.006295 | 1 | 0 | 126445 | 191.1 |
| 100000 | linear | prefix-4 | 515.2 | 0 | 108100 | 100000 | 86.73 |
| 100000 | linear | miss | 456.4 | 0 | 99078 | 100000 | 86.73 |
| 1000000 | trie | prefix-4 | 31.85 | 2122 | 0 | 2577585 | 240.7 |
| 1000000 | trie | miss | 0.004784 | 1 | 0 | 2577585 | 240.7 |
| 1000000 | radix | prefix-4 | 20.06 | 947.5 | 4 | 1148746 | 183 |
| 1000000 | radix | miss | 0.005714 | 1 | 0 | 1148746 | 183 |
| 1000000 | linear | prefix-4 | 15930 | 0 | 1085000 | 1000000 | 93.08 |
| 1000000 | linear | miss | 15680 | 0 | 998390 | 1000000 | 93.08 |

## E2 · Lyric phrase search: Bloom + KMP vs KMP vs String.contains

Phrases: *present* = 2–3 consecutive words from a random lyric line; *absent* = two mid-frequency words that never co-occur (verified). Tracks scanned = tracks whose lyrics are actually searched. Screen false-pass rate = tracks the trigram filter passed but KMP rejected ÷ tracks without the phrase. It includes Bloom hash collisions (target 1%) *and* phrases whose trigrams all occur in the track but not together, so it can exceed the per-trigram FPR; the pure Bloom FPR is measured in E4.

| tracks | phrase | method | µs/query (median) | tracks scanned/query | screen false-pass rate | same results |
| --- | --- | --- | --- | --- | --- | --- |
| 500 | present | bloom+kmp | 82.44 | 47.39 | 0.0942 | ✓ |
| 500 | present | kmp | 520.7 | 295 | – | ✓ |
| 500 | present | string.contains | 71.77 | 295 | – | ✓ |
| 500 | absent | bloom+kmp | 9.776 | 0.42 | 0.0014 | ✓ |
| 500 | absent | kmp | 489.3 | 295 | – | ✓ |
| 500 | absent | string.contains | 64.78 | 295 | – | ✓ |
| 2000 | present | bloom+kmp | 327.8 | 180.5 | 0.0916 | ✓ |
| 2000 | present | kmp | 2083 | 1196 | – | ✓ |
| 2000 | present | string.contains | 299.9 | 1196 | – | ✓ |
| 2000 | absent | bloom+kmp | 38.58 | 1.34 | 0.0011 | ✓ |
| 2000 | absent | kmp | 2005 | 1196 | – | ✓ |
| 2000 | absent | string.contains | 299.9 | 1196 | – | ✓ |
| 8000 | present | bloom+kmp | 1520 | 751.5 | 0.1067 | ✓ |
| 8000 | present | kmp | 8581 | 4796 | – | ✓ |
| 8000 | present | string.contains | 1428 | 4796 | – | ✓ |
| 8000 | absent | bloom+kmp | 156.3 | 8.24 | 0.0017 | ✓ |
| 8000 | absent | kmp | 7989 | 4796 | – | ✓ |
| 8000 | absent | string.contains | 1423 | 4796 | – | ✓ |
| 32000 | present | bloom+kmp | 5993 | 2622 | 0.1034 | ✓ |
| 32000 | present | kmp | 36240 | 19405 | – | ✓ |
| 32000 | present | string.contains | 6110 | 19405 | – | ✓ |
| 32000 | absent | bloom+kmp | 672.6 | 21.15 | 0.0011 | ✓ |
| 32000 | absent | kmp | 33180 | 19405 | – | ✓ |
| 32000 | absent | string.contains | 5993 | 19405 | – | ✓ |

## E3 · Search-as-you-type over the full index

Each keystroke prefix (1–5 chars) of a title word is one query over all fields; results limited to 500 (the app default). Heap = approximate retained size of the whole index (tries + Bloom filters + normalised text).

| tracks | structure | µs/keystroke query (median) | build ms | index heap MiB | trie nodes |
| --- | --- | --- | --- | --- | --- |
| 500 | radix | 55.23 | 28.85 | 2.827 | 10870 |
| 500 | trie | 58.02 | 28.53 | 3.38 | 24642 |
| 500 | linear | 345.7 | 24.94 | 4.405 | 38142 |
| 2000 | radix | 160.8 | 100.7 | 9.126 | 22125 |
| 2000 | trie | 165.9 | 103.8 | 10.57 | 52233 |
| 2000 | linear | 1351 | 90.93 | 17.65 | 155491 |
| 8000 | radix | 553.9 | 373.2 | 30.55 | 31607 |
| 8000 | trie | 580.8 | 371.4 | 32.22 | 77416 |
| 8000 | linear | 7992 | 367 | 71.32 | 625328 |
| 32000 | radix | 1861 | 1427 | 112.2 | 41763 |
| 32000 | trie | 1982 | 1429 | 114.4 | 100451 |
| 32000 | linear | 31150 | 1347 | 281.6 | 2529681 |

## E4 · Bloom filter: measured vs theoretical false-positive rate, vs HashSet

Random 64-bit keys; FPR measured over 1000000 keys known to be absent. No false negatives were observed (checked for every inserted key). HashSet bytes/key is the measured retained heap of a HashSet<Long>.

| n | target p | k | bits/key | measured FPR | theoretical FPR | Bloom ns/probe | Bloom bytes/key | HashSet ns/probe | HashSet bytes/key |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1000 | 0.1 | 3 | 4.793 | 0.10228 | 0.10069 | 15.03 | 0.6 | 7.183 | 64.27 |
| 1000 | 0.01 | 7 | 9.586 | 0.00979 | 0.01003 | 17.54 | 1.2 | 7.183 | 64.27 |
| 1000 | 0.001 | 10 | 14.38 | 0.00104 | 0.00100 | 17.2 | 1.8 | 7.183 | 64.27 |
| 10000 | 0.1 | 3 | 4.793 | 0.10121 | 0.10071 | 14.99 | 0.5992 | 8.85 | 62.56 |
| 10000 | 0.01 | 7 | 9.585 | 0.01002 | 0.01004 | 17.63 | 1.198 | 8.85 | 62.56 |
| 10000 | 0.001 | 10 | 14.38 | 0.00103 | 0.00100 | 17.19 | 1.798 | 8.85 | 62.56 |
| 100000 | 0.1 | 3 | 4.793 | 0.10074 | 0.10071 | 15.25 | 0.5991 | 7.686 | 66.49 |
| 100000 | 0.01 | 7 | 9.585 | 0.00994 | 0.01004 | 18.44 | 1.198 | 7.686 | 66.49 |
| 100000 | 0.001 | 10 | 14.38 | 0.00099 | 0.00100 | 18.22 | 1.797 | 7.686 | 66.49 |
| 1000000 | 0.1 | 3 | 4.793 | 0.10041 | 0.10071 | 16.38 | 0.5991 | 14.14 | 69 |
| 1000000 | 0.01 | 7 | 9.585 | 0.01015 | 0.01004 | 19.67 | 1.198 | 14.14 | 69 |
| 1000000 | 0.001 | 10 | 14.38 | 0.00095 | 0.00100 | 19.25 | 1.797 | 14.14 | 69 |

## E5 · Substring search: KMP vs naive (vs JDK indexOf)

Pattern length m = 64, always absent (full scan). Adversarial: text a^n, pattern a^(m−1)b. Natural: concatenated synthetic lyrics.

| text | n | KMP cmps/char | naive cmps/char | KMP µs | naive µs | JDK indexOf µs |
| --- | --- | --- | --- | --- | --- | --- |
| adversarial | 10000 | 1.994 | 63.6 | 36.48 | 93.06 | 74.81 |
| adversarial | 100000 | 1.999 | 63.96 | 364.5 | 991.3 | 681.4 |
| adversarial | 1000000 | 2 | 64 | 3654 | 9932 | 7635 |
| natural | 10000 | 1.009 | 1.003 | 18.1 | 7.81 | 1.045 |
| natural | 100000 | 1.01 | 1.01 | 181.2 | 79.32 | 10.47 |
| natural | 1000000 | 1.01 | 1.01 | 1821 | 795.6 | 105.5 |

## E6 · Palette clustering: k-means++ vs random seeding

k = 6; 30 synthetic covers per size (6 colour regions, shares 2–40%, Gaussian noise). Excess = inertia ÷ best inertia found for that image − 1 (0% = as good as the better method). Lost clusters = runs that ended with an empty cluster.

| pixels | seeding | mean excess % | p90 excess % | mean iterations | lost clusters | median µs |
| --- | --- | --- | --- | --- | --- | --- |
| 256 | k-means++ | 4.239 | 9.289 | 6.833 | 0/30 | 56.7 |
| 256 | random | 96.2 | 203 | 9.1 | 0/30 | 317.2 |
| 1024 | k-means++ | 10.16 | 11.22 | 9.7 | 0/30 | 170.1 |
| 1024 | random | 208.8 | 609.6 | 12.63 | 0/30 | 201 |
| 4096 | k-means++ | 3.772 | 0.00007213 | 10.43 | 0/30 | 1068 |
| 4096 | random | 75.81 | 313.8 | 15.5 | 0/30 | 1146 |
| 16384 | k-means++ | 4.161 | 8.338 | 14.5 | 0/30 | 3691 |
| 16384 | random | 98.39 | 239.9 | 19.1 | 0/30 | 4112 |

## E7 · Shuffle: Fisher–Yates vs naive swap vs sort-by-random-key

240000 shuffles of [0,1,2,3] per test; expected share of each of the 24 permutations = 4.167%. Pearson chi-square test, 23 degrees of freedom (critical value 35.17 at α = 0.05; E[χ²] = 23 if uniform). One test of a uniform shuffle still rejects 5% of the time by chance, so the test is repeated with 40 independent seeds: a uniform shuffle should be rejected in ≈ 5% of them, a biased one in all.

| method | χ² (seed 0) | p-value (seed 0) | median χ² over seeds | rejected at α = 0.05 | rarest permutation % | commonest permutation % |
| --- | --- | --- | --- | --- | --- | --- |
| fisher-yates | 33.95 | 0.066 | 22.68 | 2/40 (5%) | 4.064 | 4.307 |
| naive-swap | 7275 | < 1e-6 | 7163 | 40/40 (100%) | 3.107 | 5.811 |
| sort-by-key | 19.4 | 0.678 | 22.45 | 2/40 (5%) | 4.093 | 4.228 |

Cost of one shuffle (uniform methods only):

| n | method | µs/shuffle (median) | ns/element |
| --- | --- | --- | --- |
| 10000 | fisher-yates | 69.15 | 6.915 |
| 10000 | sort-by-key | 358.2 | 35.82 |
| 100000 | fisher-yates | 706.4 | 7.064 |
| 100000 | sort-by-key | 4371 | 43.71 |
| 1000000 | fisher-yates | 7824 | 7.824 |
| 1000000 | sort-by-key | 51490 | 51.49 |

