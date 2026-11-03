#!/usr/bin/env python3
"""Summarizes the simulation data into the results in 04-results.

Averages every scene of the runs of 1 scenario, external vehicle count, and
strategy, starting warmup_s seconds into each run. Processing time runs from a
scene's arrival until all triggered transformations have completed.

Typical usage example:

  python analyze.py
  python analyze.py --data ../02-data --results ../04-results
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

import pandas as pd
from matplotlib import pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
STAGES = ["parse_ms", "update_ms", "query_ms", "transformation_ms"]
STAGE_LABELS = ["Parsing", "Model update", "Query", "Transformation"]
STRATEGIES = ["incremental", "batch-update", "batch", "java-traversal"]
STRATEGY_LABELS = {
    "incremental": "Event driven with updates",
    "batch-update": "Batch with updates",
    "batch": "Batch with reconstruction",
    "java-traversal": "Java graph traversal",
}
# Color, marker, line style, and line width. Batch with updates lies almost on
# top of event driven, so it is a thin dashed line over event driven's wide one.
STRATEGY_STYLE = {
    "incremental": ("#2a78d6", "o", "-", 2.6),
    "batch-update": ("#1baf7a", "^", (0, (3, 1.5)), 1.2),
    "batch": ("#eb6834", "s", "-", 1.3),
    "java-traversal": ("#6250d6", "D", "-", 1.3),
}
# Components of the processing, in the order the profiler samples are stacked.
COMPONENTS = ["Rete network", "VIATRA Base", "EVM", "EMF", "Java graph traversal", "Other"]
COMPONENT_LABELS = {
    "Rete network": "Query evaluation (Rete network)",
    "VIATRA Base": "Base index (VIATRA Query Base)",
    "EVM": "Rule activations and firing (EVM)",
    "EMF": "Model changes (EMF)",
    "Java graph traversal": "Java graph traversal",
    "Other": "Other",
}
COMPONENT_COLORS = ["#1baf7a", "#2a78d6", "#eda100", "#eb6834", "#6250d6", "#8f8d86"]
INK = "#0b0b0b"
MUTED = "#52514e"
GRID = "#e4e3dc"


def load(data):
    """Returns every measured scene as 1 DataFrame, tagged with its run.

    Raises:
        ValueError: A run's measurements don't cover every scene it sent.
    """
    frames = []
    for path in sorted((data / "measurements").glob("*.csv")):
        scenario, external, seed, strategy = path.stem.split("-", 3)
        meta_path = data / "scenes" / f"{path.stem}.jsonl.meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        frame = pd.read_csv(path)
        if len(frame) != meta["scenes"]:
            raise ValueError(
                f"{path.name}: {len(frame)} of {meta['scenes']} scenes measured"
            )
        frame["scenario"] = scenario.upper()
        frame["external"] = int(external)
        frame["seed"] = int(seed)
        frame["strategy"] = strategy
        frames.append(frame)
    return pd.concat(frames, ignore_index=True)


def summarize(scenes, warmup_scenes):
    """Returns 1 row per scenario, external vehicle count, and strategy."""
    keys = ["scenario", "external", "strategy"]
    steady = scenes[scenes["tick"] > warmup_scenes]
    grouped = steady.groupby(keys)
    summary = grouped.agg(
        runs=("seed", "nunique"),
        scenes=("tick", "size"),
        vertices=("vertices", "mean"),
        vehicles=("vehicles", "mean"),
        edges=("edges", "mean"),
        distance_edges=("distance", "mean"),
        spatial_edges=("spatial", "mean"),
        lane_edges=("lane", "mean"),
        rss_longitudinal_edges=("rss_longitudinal", "mean"),
        rss_lateral_edges=("rss_lateral", "mean"),
        processing_ms=("total_ms", "mean"),
    )
    for stage in STAGES:
        summary[stage] = grouped[stage].mean()
    summary = summary.reset_index()
    # Labels each configuration by scenario, external vehicles, and edges, such
    # as A-40V-1945E, with the edges of the incremental strategy.
    edges = summary[summary["strategy"] == "incremental"].set_index(
        ["scenario", "external"]
    )["edges"]
    summary["configuration"] = [
        f"{scenario}-{external}V-{edges[(scenario, external)]:.0f}E"
        for scenario, external in zip(summary["scenario"], summary["external"])
    ]
    return summary


def scene_digest(path):
    """Returns a digest of a scene file's messages, without send timestamps."""
    digest = hashlib.sha256()
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            message = json.loads(line)
            message.pop("timestamp", None)
            digest.update(json.dumps(message, sort_keys=True).encode())
    return digest.hexdigest()


def check_scenes(data):
    """Returns, per seed, whether every strategy received the same scenes."""
    runs = {}
    for path in sorted((data / "scenes").glob("*.jsonl.gz")):
        runs.setdefault(run_name(path), []).append(path)
    rows = [
        {
            "run": name,
            "same_scenes": len(paths) > 1
            and len({scene_digest(path) for path in paths}) == 1,
        }
        for name, paths in runs.items()
    ]
    return pd.DataFrame(rows)


def run_name(path):
    """Returns the scenario, external vehicles, and seed of a scene file's name."""
    scenario, external, seed, _ = path.name.removesuffix(".jsonl.gz").split(
        "-", 3
    )
    return f"{scenario}-{external}-{seed}"


def present(summary):
    """Returns the strategies in the summary, in the order of STRATEGIES."""
    return [s for s in STRATEGIES if s in set(summary["strategy"])]


def write_table(path, header, rows, columns):
    """Writes a booktabs tabular with the given header lines and rows."""
    lines = [
        rf"\begin{{tabular}}{{@{{}}{columns}@{{}}}}",
        r"\toprule",
        *header,
        r"\midrule",
        *(" & ".join(row) + r" \\" for row in rows),
        r"\bottomrule",
        r"\end{tabular}",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_rq1_table(summary, path):
    """Writes the average processing time of every strategy."""
    strategies = present(summary)
    wide = summary.pivot_table(
        index=["scenario", "external", "configuration"],
        columns="strategy",
        values="processing_ms",
    )
    rows = [
        [configuration, *(f"{row[strategy]:.1f}" for strategy in strategies)]
        for (_, _, configuration), row in wide.iterrows()
    ]
    labels = " & ".join(STRATEGY_LABELS[s] for s in strategies)
    write_table(
        path,
        [rf"Configuration & {labels} \\"],
        rows,
        "l" + "r" * len(strategies),
    )


def write_rq2_table(summary, path):
    """Writes the average time per stage of every strategy."""
    rows = []
    for _, row in summary.sort_values(
        ["scenario", "external", "strategy"], ascending=[True, True, False]
    ).iterrows():
        rows.append(
            [
                row["configuration"],
                row["strategy"],
                *(f"{row[stage]:.2f}" for stage in STAGES),
            ]
        )
    write_table(
        path,
        [r"Configuration & Strategy & " + " & ".join(STAGE_LABELS) + r" \\"],
        rows,
        "llrrrr",
    )


def style(axis):
    """Applies the shared look of the plots to axis."""
    axis.spines[["top", "right"]].set_visible(False)
    axis.spines[["left", "bottom"]].set_color(MUTED)
    axis.tick_params(colors=MUTED, labelcolor=INK, labelsize=7)
    axis.grid(axis="y", color=GRID, linewidth=0.5)
    axis.set_axisbelow(True)


def by_external(summary):
    """Returns the summary averaged over both scenarios.

    1 row per external vehicle count and strategy.
    """
    columns = ["vertices", "vehicles", "edges", "processing_ms", *STAGES]
    return summary.groupby(["external", "strategy"], as_index=False)[
        columns
    ].mean()


def plot_scaling(
    summary, path, period_ms, x="edges", x_label="Edges in the scene graph", x_from_zero=True
):
    """Plots average processing time against the summary column x.

    The figure is as wide as a column of the paper, so its text prints at the
    size set here.
    """
    averages = by_external(summary)
    figure, axis = plt.subplots(figsize=(3.3, 2.5))
    style(axis)
    axis.tick_params(labelsize=8)
    for strategy in present(summary):
        color, marker, linestyle, linewidth = STRATEGY_STYLE[strategy]
        rows = averages[averages["strategy"] == strategy].sort_values(x)
        axis.plot(
            rows[x],
            rows["processing_ms"],
            color=color,
            linestyle=linestyle,
            linewidth=linewidth,
            marker=marker,
            markersize=4.5,
            markeredgecolor="white",
            markeredgewidth=0.6,
            label=STRATEGY_LABELS[strategy],
        )
    if x_from_zero:
        axis.set_xlim(left=0)
    axis.set_ylim(bottom=0)
    axis.axhline(period_ms, color=MUTED, linewidth=0.8)
    axis.annotate(
        f"{period_ms:.0f} ms scene period",
        (0.02, period_ms),
        xycoords=("axes fraction", "data"),
        xytext=(0, 3),
        textcoords="offset points",
        va="bottom",
        fontsize=7.5,
        color=MUTED,
    )
    axis.set_xlabel(x_label, fontsize=8, color=INK)
    axis.set_ylabel("Average time per scene (ms)", fontsize=8, color=INK)
    # Every strategy stays low at the left of the plot, so its upper left
    # corner is free for the legend.
    axis.legend(
        fontsize=8,
        frameon=False,
        loc="upper left",
        handlelength=1.8,
        borderaxespad=0.2,
    )
    figure.tight_layout()
    figure.savefig(path, bbox_inches="tight")
    figure.savefig(path.with_suffix(".png"), dpi=200, bbox_inches="tight")
    plt.close(figure)


def component_shares(path):
    """Returns each component's share of the profiler samples.

    1 row per external vehicle count and strategy, with the samples of every
    profiled scenario pooled.
    """
    samples = pd.read_csv(path)
    samples["component"] = samples["component"].where(
        samples["component"].isin(COMPONENTS), "Other"
    )
    pooled = (
        samples.groupby(["external", "strategy", "component"])["samples"]
        .sum()
        .unstack(fill_value=0)
        .reindex(columns=COMPONENTS, fill_value=0)
    )
    return pooled.div(pooled.sum(axis=1), axis=0).reset_index()


def plot_components(summary, shares, path):
    """Plots the average processing time per scene split by component, against edge count.

    Each component gets its share of the profiler samples times the average processing
    time per scene. 1 panel per strategy, with a point per external vehicle count.
    """
    averages = by_external(summary).merge(shares, on=["external", "strategy"])
    for component in COMPONENTS:
        averages[component] = averages[component] * averages["processing_ms"]
    strategies = present(summary)
    figure, axes = plt.subplots(
        1,
        len(strategies),
        figsize=(7.0, 2.6),
        sharey=True,
        squeeze=False,
    )
    for axis, strategy in zip(axes[0], strategies):
        style(axis)
        rows = averages[averages["strategy"] == strategy].sort_values("edges")
        axis.stackplot(
            rows["edges"],
            *(rows[component] for component in COMPONENTS),
            colors=COMPONENT_COLORS,
            labels=[COMPONENT_LABELS[component] for component in COMPONENTS],
            linewidth=0,
        )
        axis.plot(
            rows["edges"],
            rows["processing_ms"],
            linestyle="none",
            marker="o",
            markersize=3,
            color=INK,
        )
        axis.set_title(STRATEGY_LABELS[strategy], fontsize=8, color=INK)
        axis.set_xlabel("Edges in the scene graph", fontsize=7, color=INK)
    axes[0][0].set_ylabel("Average time per scene (ms)", fontsize=7, color=INK)
    # Set after every panel is drawn, since the shared axes freeze the limits.
    for axis in axes.flat:
        axis.set_xlim(left=0)
        axis.set_ylim(bottom=0)
    handles, labels = axes[0][0].get_legend_handles_labels()
    figure.legend(
        handles[::-1],
        labels[::-1],
        fontsize=7,
        frameon=False,
        loc="lower center",
        ncol=3,
    )
    figure.tight_layout(rect=(0, 0.14, 1, 1))
    figure.savefig(path, bbox_inches="tight")
    figure.savefig(path.with_suffix(".png"), dpi=200, bbox_inches="tight")
    plt.close(figure)


def parse_args():
    """Returns the data, results, and configuration paths."""
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--data", type=Path, default=ROOT / "02-data")
    parser.add_argument("--results", type=Path, default=ROOT / "04-results")
    parser.add_argument(
        "--config",
        type=Path,
        default=ROOT / "01-configurations" / "evaluation.json",
    )
    parser.add_argument(
        "--profile",
        type=Path,
        default=ROOT / "02-data" / "profile" / "component_samples.csv",
    )
    return parser.parse_args()


def main():
    """Writes the statistics, tables, and plots to the results directory."""
    args = parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    period_ms = 1000.0 / config["scene_rate_hz"]
    warmup_scenes = round(config["warmup_s"] * config["scene_rate_hz"])
    plots = args.results / "plots"
    statistics = args.results / "statistics"
    tables = args.results / "tables"
    for folder in (plots, statistics, tables):
        folder.mkdir(parents=True, exist_ok=True)
    summary = summarize(load(args.data), warmup_scenes)
    summary.to_csv(statistics / "summary.csv", index=False, float_format="%.4f")
    check = check_scenes(args.data)
    check.to_csv(statistics / "scene_check.csv", index=False)
    write_rq1_table(summary, tables / "rq1_processing_time.tex")
    write_rq2_table(summary, tables / "rq2_stages.tex")
    plot_scaling(summary, plots / "rq3_scaling.pdf", period_ms)
    plot_scaling(
        summary,
        plots / "rq3_scaling_vehicles.pdf",
        period_ms,
        x="vehicles",
        x_label="Vehicles in the scene",
    )
    plot_scaling(
        summary,
        plots / "rq3_scaling_nodes.pdf",
        period_ms,
        x="vertices",
        x_label="Nodes in the scene graph",
        # The road network's nodes are the same in every configuration.
        x_from_zero=False,
    )
    plot_components(
        summary, component_shares(args.profile), plots / "rq2_components.pdf"
    )
    with pd.option_context("display.width", 250, "display.max_columns", 40):
        print(summary.round(2).to_string(index=False))
    print(
        f"Seeds with the same scenes for every strategy: "
        f"{int(check['same_scenes'].sum())} of {len(check)}"
    )


if __name__ == "__main__":
    main()
