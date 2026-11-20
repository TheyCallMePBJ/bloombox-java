#!/usr/bin/env python3
"""Charts for `bloombox bench` results.

usage: python3 bench/plot.py [RESULTS_DIR]      (default: bench-results/latest)

Reads the CSVs written by the Java benchmark harness and writes <name>.png (150 dpi, for the
report) and <name>.svg next to them. Needs only matplotlib.

Chart conventions: one y-axis per chart (two measures -> two panels), at most three series
per chart (validated colour-blind-safe palette slots), a legend plus direct end-of-line
labels and distinct marker shapes so no series is identified by colour alone, light
recessive grid. The exact numbers are in summary.md.
"""
import csv
import math
import os
import sys
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter  # noqa: E402

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK2 = "#52514e"
GRID = "#e4e3de"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a"]  # categorical slots 1-3 (validated, light mode)
MARKERS = ["o", "s", "^"]
# colour follows the entity in every chart: the app's choice is slot 1, the main baseline slot 2
SLOT = {"radix": 0, "trie": 1, "linear": 2,
        "bloom+kmp": 0, "kmp": 1, "string.contains": 2,
        "kmeans++": 0, "random": 1,
        "naive": 2}  # kmp keeps its E2 colour in E5

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
    "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
    "axes.titlesize": 11, "axes.titleweight": "bold", "legend.frameon": False,
    "lines.linewidth": 2, "lines.markersize": 6,
})


def read(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def num(v):
    try:
        x = float(v)
        return x if math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


def human(x, _pos=None):
    for unit, div in (("G", 1e9), ("M", 1e6), ("k", 1e3)):
        if abs(x) >= div:
            v = x / div
            return f"{v:g}{unit}"
    return f"{x:g}"


def time_label(ns):
    if ns >= 1e6:
        return f"{ns / 1e6:.3g} ms"
    if ns >= 1e3:
        return f"{ns / 1e3:.3g} µs"
    return f"{ns:.3g} ns"


def series_plot(ax, data, order, ylabel, logy=True, logx=True, label_ends=True, fmt=None):
    """data: name -> list of (x, y). Colour/marker come from SLOT (fixed per entity); labels the last point."""
    ends = []
    for i, name in enumerate(order):
        pts = sorted(p for p in data.get(name, []) if p[1] is not None)
        if not pts:
            continue
        xs, ys = zip(*pts)
        slot = SLOT.get(name, i)
        ax.plot(xs, ys, color=SERIES[slot], marker=MARKERS[slot], label=name, zorder=3)
        ends.append((xs[-1], ys[-1], f"{name}" + (f"  {fmt(ys[-1])}" if fmt else "")))
    if logx:
        ax.set_xscale("log")
    if logy:
        ax.set_yscale("log")
    tidy_log_axes(ax)
    if label_ends:
        place_end_labels(ax, ends, logy)
    ax.xaxis.set_major_formatter(FuncFormatter(human))
    ax.set_ylabel(ylabel)
    ax.margins(x=0.25)


def tidy_log_axes(ax):
    """Log axes: ticks at 1-2-5 per decade with plain labels (1k, 20, 0.5), no minor labels."""
    for axis, scale in ((ax.xaxis, ax.get_xscale()), (ax.yaxis, ax.get_yscale())):
        if scale != "log":
            continue
        lo, hi = axis.get_view_interval()
        decades = math.log10(max(hi, 1e-300) / max(lo, 1e-300))
        subs = (1.0, 2.0, 5.0) if decades <= 2.5 else (1.0, 3.0) if decades <= 4 else (1.0,)
        axis.set_major_locator(LogLocator(base=10, subs=subs))
        axis.set_major_formatter(FuncFormatter(human))
        axis.set_minor_formatter(NullFormatter())


def place_end_labels(ax, ends, logy, gap_pts=11):
    """Direct labels at line ends, pushed apart vertically so close finishes don't collide."""
    if not ends:
        return
    ax.figure.canvas.draw()
    to_disp = ax.transData.transform
    items = sorted(((to_disp((x, y))[1], x, y, t) for x, y, t in ends))
    gap = gap_pts * ax.figure.dpi / 72
    placed = []
    for ydisp, x, y, t in items:
        target = ydisp if not placed else max(ydisp, placed[-1] + gap)
        placed.append(target)
        ax.annotate(t, (x, y), xytext=(6, (target - ydisp) * 72 / ax.figure.dpi), textcoords="offset points",
                    va="center", fontsize=9, color=INK2)


def finish(fig, out, name, title, subtitle):
    fig.suptitle(title, x=0.01, ha="left", fontsize=13, fontweight="bold", color=INK)
    fig.text(0.01, 0.905, subtitle, ha="left", fontsize=9.5, color=INK2)
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    for ext in ("png", "svg"):
        fig.savefig(os.path.join(out, f"{name}.{ext}"), dpi=150)
    plt.close(fig)
    print("  wrote", name + ".png/.svg")


# ---- E1 ---------------------------------------------------------------------------------------

def e1(d):
    rows = read(os.path.join(d, "e1_prefix.csv"))
    order = ["trie", "radix", "linear"]
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.2))
    for ax, q, title in zip(axes[:2], ["prefix-4", "miss"],
                            ["Prefix query: 4 chars, returns all matches", "Lookup cost alone: prefix with no match"]):
        data = defaultdict(list)
        for r in rows:
            if r["query"] == q:
                data[r["structure"]].append((num(r["keys"]), num(r["ns_per_query_median"])))
        series_plot(ax, data, order, "time per query (ns, log)", fmt=time_label)
        ax.set_xlabel("keys in index (log)")
        ax.set_title(title, loc="left")
    # memory: bytes per key at the largest size, one bar per structure
    ax = axes[2]
    kmax = max(num(r["keys"]) for r in rows)
    mem = {r["structure"]: num(r["heap_bytes_per_key"]) for r in rows if num(r["keys"]) == kmax}
    vals = [mem.get(s, 0) for s in order]
    ax.bar(order, vals, color=SERIES[0], width=0.55, zorder=3)
    for i, v in enumerate(vals):
        ax.annotate(f"{v:.0f} B", (i, v), xytext=(0, 3), textcoords="offset points", ha="center", fontsize=9, color=INK2)
    ax.set_ylabel("heap bytes per key")
    ax.set_title(f"Memory at {human(kmax)} keys", loc="left")
    ax.grid(axis="x", visible=False)
    finish(fig, d, "e1_prefix", "E1 · Prefix index: trie cost follows the prefix and the matches; linear scan follows the key count",
           "Median of 7 timed runs after JIT warm-up. A miss costs ~5 ns at any size (O(m)); a prefix query grows only with the matches it returns (O(m + r)).")


# ---- E2 ---------------------------------------------------------------------------------------

def e2(d):
    rows = read(os.path.join(d, "e2_phrase.csv"))
    order = ["bloom+kmp", "kmp", "string.contains"]
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.2))
    for ax, kind in zip(axes[:2], ["absent", "present"]):
        data = defaultdict(list)
        for r in rows:
            if r["phrase"] == kind:
                data[r["method"]].append((num(r["tracks"]), num(r["ns_per_query_median"])))
        series_plot(ax, data, order, "time per query (ns, log)", fmt=time_label)
        ax.set_xlabel("tracks in library (log)")
        ax.set_title("Phrase not in any lyrics" if kind == "absent" else "Phrase taken from a lyric line", loc="left")
    ax = axes[2]
    # method keeps its colour; phrase kind is the line style (bloom+kmp only — kmp scans everything either way)
    styles = [("kmp", "absent", "-", "kmp (any phrase)"), ("bloom+kmp", "present", "--", "bloom+kmp · present"),
              ("bloom+kmp", "absent", "-", "bloom+kmp · absent")]
    for method, kind, ls, label in styles:
        pts = sorted((num(r["lyric_tracks"]), num(r["tracks_scanned_per_query"])) for r in rows
                     if r["method"] == method and r["phrase"] == kind)
        slot = SLOT[method]
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color=SERIES[slot], marker=MARKERS[slot], linestyle=ls,
                label=label, zorder=3)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.xaxis.set_major_formatter(FuncFormatter(human))
    tidy_log_axes(ax)
    ax.set_ylabel("lyric tracks searched per query (log)")
    ax.set_xlabel("tracks with lyrics (log)")
    ax.set_title("Tracks KMP has to search", loc="left")
    lo, hi = ax.get_ylim()
    ax.set_ylim(lo, hi * 60)  # headroom so the legend sits above the lines
    ax.legend(loc="upper left", fontsize=8.5)
    finish(fig, d, "e2_phrase", "E2 · Lyric phrase search: the Bloom screen pays off when a phrase is rare or absent",
           "All three methods returned identical results for every query. KMP alone searches every lyric track; the screen skips tracks that cannot match.")


# ---- E3 ---------------------------------------------------------------------------------------

def e3(d):
    rows = read(os.path.join(d, "e3_search.csv"))
    order = ["radix", "trie", "linear"]
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    data, build = defaultdict(list), defaultdict(dict)
    for r in rows:
        if r["query"] == "typed-prefix":
            data[r["structure"]].append((num(r["tracks"]), num(r["ns_per_query_median"])))
        build[r["structure"]][num(r["tracks"])] = num(r["heap_mib"])
    series_plot(axes[0], data, order, "time per keystroke query (ns, log)", fmt=time_label)
    axes[0].set_xlabel("tracks in library (log)")
    axes[0].set_title("Search-as-you-type latency", loc="left")
    mem = {s: [(t, m) for t, m in v.items()] for s, v in build.items()}
    series_plot(axes[1], mem, order, "index heap (MiB, log)", fmt=lambda v: f"{v:.0f} MiB")
    axes[1].set_xlabel("tracks in library (log)")
    axes[1].set_title("Whole-index memory", loc="left")
    finish(fig, d, "e3_search", "E3 · End-to-end search: trie-backed index vs a linear-scan index",
           "Each keystroke prefix (1–5 chars) of a title word, searched over title, artist, album and lyric words.")


# ---- E4 ---------------------------------------------------------------------------------------

def e4(d):
    rows = read(os.path.join(d, "e4_bloom.csv"))
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    ax = axes[0]
    targets = sorted({num(r["target_fpp"]) for r in rows}, reverse=True)
    for i, p in enumerate(targets[:3]):
        pts = sorted((num(r["n"]), num(r["measured_fpp"]), num(r["theoretical_fpp"])) for r in rows if num(r["target_fpp"]) == p)
        xs = [x for x, _, _ in pts]
        ax.plot(xs, [t for _, _, t in pts], color=SERIES[i], linestyle="--", linewidth=1.2, zorder=2)
        ax.plot(xs, [m for _, m, _ in pts], color=SERIES[i], marker=MARKERS[i], linestyle="none", label=f"target p = {p:g}", zorder=3)
        ax.annotate(f"p = {p:g}", (xs[-1], pts[-1][1]), xytext=(8, 0), textcoords="offset points", va="center", fontsize=9, color=INK2)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.xaxis.set_major_formatter(FuncFormatter(human))
    tidy_log_axes(ax)
    ax.set_xlabel("keys inserted n (log)")
    ax.set_ylabel("false-positive rate (log)")
    ax.set_title("Measured (markers) vs theory (dashed)", loc="left")
    ax.legend(loc="center left", bbox_to_anchor=(0.0, 0.72), fontsize=8.5)
    ax.margins(x=0.2)
    ax = axes[1]
    nmax = max(num(r["n"]) for r in rows)
    sel = [r for r in rows if num(r["n"]) == nmax]
    labels = [f"Bloom p={num(r['target_fpp']):g}" for r in sel] + ["HashSet<Long>"]
    vals = [num(r["bloom_bytes_per_key"]) for r in sel] + [num(sel[0]["hashset_bytes_per_key"])]
    ax.barh(labels, vals, color=SERIES[0], height=0.55, zorder=3)
    for i, v in enumerate(vals):
        ax.annotate(f"{v:.1f} B", (v, i), xytext=(4, 0), textcoords="offset points", va="center", fontsize=9, color=INK2)
    ax.set_xscale("log")
    tidy_log_axes(ax)
    ax.set_xlabel("bytes per key (log)")
    ax.set_title(f"Memory at n = {human(nmax)}", loc="left")
    ax.grid(axis="y", visible=False)
    ax.invert_yaxis()
    finish(fig, d, "e4_bloom", "E4 · Bloom filter: measured false-positive rate matches theory",
           "Rates measured over keys known to be absent; zero false negatives. A Bloom filter needs ~1.44·log2(1/p) bits per key, whatever the key.")


# ---- E5 ---------------------------------------------------------------------------------------

def e5(d):
    rows = read(os.path.join(d, "e5_kmp.csv"))
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for ax, kind in zip(axes, ["adversarial", "natural"]):
        data = defaultdict(list)
        for r in rows:
            if r["text"] == kind and r["method"] in ("kmp", "naive"):
                data[r["method"]].append((num(r["n"]), num(r["cmps_per_char"])))
        series_plot(ax, data, ["kmp", "naive"], "character comparisons per text character", logy=False,
                    fmt=lambda v: f"{v:.2f}")
        ax.set_ylim(bottom=0)
        ax.set_xlabel("text length n (log)")
        ax.set_title("Adversarial: text aaa…, pattern a…ab (m = 64)" if kind == "adversarial" else "Natural lyric text",
                     loc="left")
    finish(fig, d, "e5_kmp", "E5 · KMP never exceeds 2 comparisons per character; naive search reaches m",
           "Exact comparison counts (machine-independent). Wall times, including the JDK's vectorised indexOf, are in summary.md.")


# ---- E6 ---------------------------------------------------------------------------------------

def e6(d):
    rows = read(os.path.join(d, "e6_kmeans.csv"))
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    agg = defaultdict(lambda: defaultdict(list))
    for r in rows:
        agg[r["seeding"]][num(r["pixels"])].append(r)
    order = ["kmeans++", "random"]
    mean_ex = {s: [(px, 100 * sum(num(x["excess_over_best"]) for x in v) / len(v)) for px, v in agg[s].items()] for s in order}
    series_plot(axes[0], mean_ex, order, "mean excess inertia over best (%)", logy=False, fmt=lambda v: f"{v:.0f}%")
    axes[0].set_ylim(bottom=0)
    axes[0].set_xlabel("pixels sampled (log)")
    axes[0].set_title("Clustering error (lower is better)", loc="left")
    iters = {s: [(px, sum(num(x["iterations"]) for x in v) / len(v)) for px, v in agg[s].items()] for s in order}
    series_plot(axes[1], iters, order, "mean Lloyd iterations to converge", logy=False, fmt=lambda v: f"{v:.1f}")
    axes[1].set_ylim(bottom=0)
    axes[1].set_xlabel("pixels sampled (log)")
    axes[1].set_title("Iterations", loc="left")
    finish(fig, d, "e6_kmeans", "E6 · k-means++ seeding gives near-best palettes; random seeding often misses colours",
           "Synthetic covers with six colour regions (one only 2% of the image). Excess = inertia ÷ best inertia found for that image − 1.")


# ---- E7 ---------------------------------------------------------------------------------------

def e7(d):
    rows = read(os.path.join(d, "e7_shuffle_uniformity.csv"))
    fig, (ax, ax2) = plt.subplots(1, 2, figsize=(13, 3.9), gridspec_kw={"width_ratios": [1.6, 1]})
    names = [r["method"] for r in rows]
    chis = [num(r["chi_square"]) for r in rows]
    ax.barh(names, chis, color=SERIES[0], height=0.5, zorder=3)
    ax.axvline(35.17, color=INK2, linestyle="--", linewidth=1.2, zorder=2)
    ax.annotate("critical value 35.17 (α = 0.05): right of the line = not uniform", (35.17, -0.62), xytext=(4, 0),
                textcoords="offset points", fontsize=9, color=INK2, va="center")
    for i, (c, r) in enumerate(zip(chis, rows)):
        p = num(r["p_value"])
        ptxt = "p < 1e-6" if p is not None and p < 1e-6 else f"p = {p:.2f}"
        ax.annotate(f"χ² = {c:.1f}, {ptxt}", (c, i), xytext=(4, 0), textcoords="offset points", va="center", fontsize=9,
                    color=INK2, zorder=5, bbox=dict(fc=SURFACE, ec="none", pad=1.5))
    ax.set_xscale("log")
    tidy_log_axes(ax)
    ax.set_xlabel("chi-square statistic vs uniform, 23 d.f. (log)")
    ax.grid(axis="y", visible=False)
    ax.set_ylim(len(names) - 0.5, -0.95)
    ax.margins(x=0.35)
    ax.set_title("One test (seed 0)", loc="left")
    if "rejections_at_0.05" in rows[0]:
        reps = int(num(rows[0]["replicates"]))
        rate = [100 * num(r["rejections_at_0.05"]) / reps for r in rows]
        ax2.barh(names, rate, color=SERIES[0], height=0.5, zorder=3)
        ax2.axvline(5, color=INK2, linestyle="--", linewidth=1.2, zorder=2)
        ax2.annotate("5% = expected for a uniform shuffle", (5, -0.62), xytext=(4, 0), textcoords="offset points",
                     fontsize=9, color=INK2, va="center")
        for i, (v, r) in enumerate(zip(rate, rows)):
            ax2.annotate(f"{int(num(r['rejections_at_0.05']))}/{reps}", (v, i), xytext=(6, 0), textcoords="offset points",
                         va="center", fontsize=9, color=INK2, zorder=5, bbox=dict(fc=SURFACE, ec="none", pad=1.5))
        ax2.set_xlim(0, 115)
        ax2.set_ylim(len(names) - 0.5, -0.95)
        ax2.set_xlabel("tests rejecting uniformity at α = 0.05 (%)")
        ax2.set_title(f"Repeated over {reps} independent seeds", loc="left")
        ax2.grid(axis="y", visible=False)
        ax2.set_yticklabels([])
    finish(fig, d, "e7_shuffle", "E7 · Fisher–Yates is uniform; the naive swap shuffle is measurably biased",
           f"Counts of all 24 orderings of 4 items, {int(num(rows[0]['trials'])):,} shuffles per test, Pearson χ² with 23 d.f.")


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else os.path.join("bench-results", "latest")
    if not os.path.isdir(d):
        sys.exit(f"no results directory: {d} (run: java -jar target/bloombox.jar bench)")
    for fn, csvname in ((e1, "e1_prefix"), (e2, "e2_phrase"), (e3, "e3_search"), (e4, "e4_bloom"),
                        (e5, "e5_kmp"), (e6, "e6_kmeans"), (e7, "e7_shuffle_uniformity")):
        if os.path.exists(os.path.join(d, csvname + ".csv")):
            fn(d)
        else:
            print("  skipped", csvname, "(not in this run)")


if __name__ == "__main__":
    main()
